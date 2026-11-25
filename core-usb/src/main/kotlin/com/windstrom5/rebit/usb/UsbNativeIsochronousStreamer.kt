package com.windstrom5.rebit.usb

import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.util.Log
import com.windstrom5.rebit.domain.model.PcmFormat
import com.windstrom5.rebit.domain.model.UsbStreamingOption
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Kotlin side of the native isochronous USB Audio Class engine
 * (core-usb/src/main/cpp/usb_native_streamer.cpp + uac_stream_core.h) — the transport behind
 * every "USB Direct" driver profile.
 *
 * Android's Java USB API can't do isochronous transfers, so the C++ engine submits them to the
 * kernel's usbfs directly, the way libusb and UAPP-class players do: packets paced per (micro)
 * frame from the bus speed and the endpoint's bInterval, sized from the DAC's asynchronous
 * feedback (or the exact nominal rate), batched ~1 ms per URB with a millisecond-sized queue.
 *
 * This class claims the interface, sets the DAC's sampling frequency, then runs a pump thread
 * that pulls container-packed PCM from [start]'s pcmSource and pushes it into the native ring.
 * At end of input it waits for the ring to drain before reporting completion, so the last
 * moment of a track is actually heard.
 */
class UsbNativeIsochronousStreamer(
    private val connection: UsbDeviceConnection,
    private val usbInterface: UsbInterface,
    private val endpoint: UsbEndpoint,
    private val option: UsbStreamingOption,
    private val format: PcmFormat,
    private val feedbackEndpoint: UsbEndpoint? = null,
    /** Target depth of the transfer queue (Settings › USB buffer latency). */
    private val targetLatencyMs: Int = 32,
    /** False = "fixed rate": ignore the DAC's feedback and send the exact nominal rate. */
    private val useFeedback: Boolean = true
) {
    private val running = AtomicBoolean(false)
    private var pumpThread: Thread? = null
    @Volatile private var nativeHandle: Long = 0

    val dropoutCount = AtomicInteger(0)

    /** See nativeGetStats for the layout. */
    private val stats = LongArray(STATS_SIZE)
    private val frameBytes: Int
        get() = (option.containerBytes.takeIf { it > 0 } ?: (format.bitDepth / 8)) * format.channels

    /** Frames the DAC has actually been sent (not just queued) — drives the position display. */
    @Volatile var framesSent: Long = 0L
        private set

    /** DAC-reported (feedback) sample rate in Hz, or the nominal rate without feedback. */
    @Volatile var measuredRateHz: Double = format.sampleRateHz.toDouble()
        private set

    /** Milliseconds of audio queued between the decoder and the wire. */
    @Volatile var bufferedMs: Double = 0.0
        private set

    /** One-line description of the active transport, for diagnostics. */
    @Volatile var transportSummary: String = ""
        private set

    @Volatile var packetsPerSecond: Int = 0
        private set
    @Volatile var queueMs: Double = 0.0
        private set
    @Volatile var feedbackActive: Boolean = false
        private set
    @Volatile var underruns: Long = 0L
        private set
    @Volatile var packetErrors: Long = 0L
        private set

    /**
     * @param pcmSource next chunk of container-packed PCM; empty = nothing yet; null = end of input
     * @param onEndOfStream called once the last queued audio has actually gone out over USB
     */
    fun start(
        pcmSource: () -> ByteArray?,
        onDropout: () -> Unit,
        onBytesTransmitted: (ByteArray, Int) -> Unit = { _, _ -> },
        onError: (Throwable) -> Unit = {},
        onEndOfStream: () -> Unit = {}
    ) {
        if (running.getAndSet(true)) return

        val claimed = try {
            connection.claimInterface(usbInterface, true)
        } catch (t: Throwable) {
            running.set(false)
            onError(t)
            return
        }
        if (!claimed) {
            running.set(false)
            onError(IllegalStateException("Couldn't claim the DAC's streaming interface."))
            return
        }

        if (!setSampleRateOnDevice()) {
            Log.w(TAG, "DAC didn't acknowledge SET_CUR ${format.sampleRateHz} Hz — streaming anyway")
        }

        val fd = connection.fileDescriptor
        if (fd < 0) {
            connection.releaseInterface(usbInterface)
            running.set(false)
            onError(IllegalStateException("USB connection has no file descriptor."))
            return
        }

        nativeHandle = nativeOpen(
            fd,
            endpoint.address, endpoint.maxPacketSize, endpoint.interval,
            feedbackEndpoint?.address ?: 0, feedbackEndpoint?.maxPacketSize ?: 0,
            format.sampleRateHz, frameBytes, option.isUac2,
            targetLatencyMs, useFeedback
        )
        if (nativeHandle == 0L) {
            connection.releaseInterface(usbInterface)
            running.set(false)
            onError(IllegalStateException("Native USB engine failed to open (logcat tag BitPerfectUsbNative)."))
            return
        }
        if (!nativeStart(nativeHandle)) {
            nativeClose(nativeHandle)
            nativeHandle = 0
            connection.releaseInterface(usbInterface)
            running.set(false)
            onError(IllegalStateException("Native USB transfer thread failed to start."))
            return
        }

        pumpThread = Thread({
            var lastUnderruns = 0L
            var lastErrors = 0L
            var endedNormally = false
            try {
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO)
                while (running.get()) {
                    val chunk = pcmSource()
                    if (chunk == null) {
                        endedNormally = true
                        break
                    }
                    if (chunk.isEmpty()) continue
                    var offset = 0
                    while (offset < chunk.size && running.get()) {
                        val n = nativeWrite(nativeHandle, chunk, offset, chunk.size - offset, WRITE_TIMEOUT_MS)
                        if (n <= 0) {
                            pollStats()
                            if (stats[6] != 0L) throw IllegalStateException("USB DAC disconnected.")
                        }
                        offset += n.coerceAtLeast(0)
                    }
                    onBytesTransmitted(chunk, chunk.size)

                    pollStats()
                    if (stats[6] != 0L) throw IllegalStateException("USB DAC disconnected.")
                    val underruns = stats[1]
                    val errors = stats[2] + stats[3]
                    if (underruns > lastUnderruns || errors > lastErrors) {
                        dropoutCount.addAndGet(((underruns - lastUnderruns) + (errors - lastErrors)).toInt())
                        onDropout()
                        lastUnderruns = underruns
                        lastErrors = errors
                    }
                }
                if (endedNormally && running.get()) {
                    // Let the queued tail of the track play out, then report the end.
                    nativeEndOfStream(nativeHandle)
                    val deadline = System.currentTimeMillis() + DRAIN_TIMEOUT_MS
                    while (running.get() && System.currentTimeMillis() < deadline) {
                        pollStats()
                        if (stats[5] < frameBytes || stats[6] != 0L) break
                        Thread.sleep(5)
                    }
                    // Plus the URB queue already handed to the kernel.
                    Thread.sleep((stats[10] / 1000L).coerceIn(0L, 300L))
                    onEndOfStream()
                }
            } catch (t: Throwable) {
                if (running.get()) onError(t)
            }
        }, "usb-native-pump").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }
    }

    private fun pollStats() {
        val h = nativeHandle
        if (h == 0L) return
        nativeGetStats(h, stats)
        framesSent = stats[7]
        measuredRateHz = stats[4] / 1000.0
        val bytesPerSecond = format.sampleRateHz.toDouble() * frameBytes
        bufferedMs = stats[5] / bytesPerSecond * 1000.0 + stats[10] / 1000.0
        packetsPerSecond = stats[9].toInt()
        queueMs = stats[10] / 1000.0
        feedbackActive = stats[8] > 0
        underruns = stats[1]
        packetErrors = stats[2] + stats[3]
        transportSummary = "${stats[9]} pkt/s · ${stats[10] / 1000} ms queue · " +
            if (stats[8] > 0) "async feedback ${"%.3f".format(measuredRateHz / 1000.0)} kHz" else "nominal clock"
    }

    fun stop() {
        running.set(false)
        val h = nativeHandle
        if (h != 0L) nativeStop(h) // unblocks a pump thread waiting in nativeWrite
        pumpThread?.join(1_000)
        pumpThread = null
        if (h != 0L) {
            // Clear the handle first: a pump thread that somehow outlived the join sees 0 and
            // gets a harmless no-op from the native side instead of a freed engine.
            nativeHandle = 0
            nativeClose(h)
        }
        try { connection.releaseInterface(usbInterface) } catch (_: Throwable) {}
    }

    /** Class-specific SET_CUR for the sampling frequency (UAC2 clock source / UAC1 endpoint). */
    private fun setSampleRateOnDevice(): Boolean {
        val rate = format.sampleRateHz
        return try {
            if (option.isUac2) {
                val clockId = option.clockSourceId ?: return false
                val acIface = option.acInterfaceNumber ?: return false
                val data = byteArrayOf(
                    (rate and 0xFF).toByte(), ((rate shr 8) and 0xFF).toByte(),
                    ((rate shr 16) and 0xFF).toByte(), ((rate shr 24) and 0xFF).toByte()
                )
                val wValue = UsbAudioConstants.CS_SAM_FREQ_CONTROL shl 8
                val wIndex = (clockId shl 8) or acIface
                connection.controlTransfer(
                    UsbAudioConstants.REQTYPE_CLASS_INTERFACE_OUT, UsbAudioConstants.REQUEST_CUR,
                    wValue, wIndex, data, data.size, 500
                ) == data.size
            } else {
                val data = byteArrayOf(
                    (rate and 0xFF).toByte(), ((rate shr 8) and 0xFF).toByte(), ((rate shr 16) and 0xFF).toByte()
                )
                val wValue = UsbAudioConstants.EP_SAMPLING_FREQ_CONTROL shl 8
                connection.controlTransfer(
                    UsbAudioConstants.REQTYPE_CLASS_ENDPOINT_OUT, UsbAudioConstants.REQUEST_CUR,
                    wValue, option.endpointAddress, data, data.size, 500
                ) == data.size
            }
        } catch (t: Throwable) {
            Log.w(TAG, "setSampleRateOnDevice failed: ${t.message}")
            false
        }
    }

    private external fun nativeOpen(
        fd: Int,
        dataEndpoint: Int, dataMaxPacketSize: Int, dataInterval: Int,
        feedbackEndpoint: Int, feedbackMaxPacketSize: Int,
        sampleRateHz: Int, frameSizeBytes: Int, isUac2: Boolean,
        targetLatencyMs: Int, useFeedback: Boolean
    ): Long

    private external fun nativeStart(handle: Long): Boolean
    private external fun nativeWrite(handle: Long, data: ByteArray, offset: Int, length: Int, timeoutMs: Int): Int
    private external fun nativeEndOfStream(handle: Long)
    private external fun nativeGetStats(handle: Long, outStats: LongArray)
    private external fun nativeStop(handle: Long)
    private external fun nativeClose(handle: Long)

    companion object {
        private const val TAG = "UsbNativeIsochronousStreamer"
        private const val WRITE_TIMEOUT_MS = 250
        private const val DRAIN_TIMEOUT_MS = 3_000L
        private const val STATS_SIZE = 11

        @Volatile private var libraryLoaded = false
        private val loadLock = Any()

        /** Loads the native library once; throws if this build has no .so for the device's ABI. */
        fun ensureLibraryLoaded() {
            if (libraryLoaded) return
            synchronized(loadLock) {
                if (libraryLoaded) return
                System.loadLibrary("bitperfectusb_native")
                libraryLoaded = true
            }
        }
    }
}
