package com.windstrom5.rebit.playback

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.util.Log
import com.windstrom5.rebit.domain.model.PcmFormat
import com.windstrom5.rebit.domain.model.TrafficLogEntry
import com.windstrom5.rebit.domain.model.UsbTransferStrategy
import com.windstrom5.rebit.usb.ActivatedStreamingEndpoint
import com.windstrom5.rebit.usb.UsbDacManager
import com.windstrom5.rebit.usb.UsbIsochronousAudioStreamer
import com.windstrom5.rebit.usb.UsbNativeIsochronousStreamer
import com.windstrom5.rebit.usb.UsbTrafficLog
import java.util.concurrent.atomic.AtomicInteger

/**
 * The Chapter 4.1 "treatment" path: raw decoder bytes go straight to the DAC's
 * isochronous OUT endpoint via [UsbIsochronousAudioStreamer], completely
 * bypassing AudioFlinger, the Audio HAL, and ALSA (Section 3.4). No resampling,
 * no bit-depth conversion, no mixing — whatever the decoder produced is exactly
 * what reaches the wire, on the exact alternate setting matching that format,
 * with the DAC's clock explicitly switched to match before streaming starts.
 *
 * [activated] must already reflect the alternate setting selected for the
 * source's exact format — see UsbDacManager.activateStreamingOption. This
 * class owns releasing it back to idle on [stop].
 *
 * A [TransferVerifier] checksums bytes at both boundaries — as read from the
 * decoder, and as actually queued to the USB endpoint — so "bit-perfect" here
 * is checkable proof, not just a claim (see [PlaybackEngine.verificationSnapshot]).
 */
class UsbDirectPlaybackEngine(
    private val usbDacManager: UsbDacManager,
    private val device: UsbDevice,
    private val connection: UsbDeviceConnection,
    private val activated: ActivatedStreamingEndpoint,
    /** User-tunable via Settings > USB Buffering Depth — see UsbIsochronousAudioStreamer. */
    private val requestPoolSize: Int = 4,
    /** User-tunable via Settings > Audio Driver — see UsbTransferStrategy. */
    private val transferStrategy: UsbTransferStrategy = UsbTransferStrategy.PIPELINED,
    /** User-tunable via Settings > Audio Driver ("Pipelined" vs "Pipelined,
     *  Fixed-Rate" profiles) — see UsbIsochronousAudioStreamer's class doc. */
    private val useAdaptiveClockCorrection: Boolean = true,
    private val settingsRepository: com.windstrom5.rebit.data.settings.SettingsRepository? = null
) : PlaybackEngine {

    private var streamer: UsbIsochronousAudioStreamer? = null
    @Volatile private var nativeStreamer: UsbNativeIsochronousStreamer? = null
    private var biquadEqualizer: com.windstrom5.rebit.playback.dsp.TenBandBiquadEqualizer? = null
    private var bufferSizeBytes = 4096
    private var bytesPerSecond = 1
    private val dropouts = AtomicInteger(0)
    private var verifier = TransferVerifier()
    private var trafficLogBuffer = UsbTrafficLog()
    private val meter = com.windstrom5.rebit.playback.dsp.LevelMeter()
    /** Only used when the person has explicitly allowed EQ on the USB path. */
    private var eqQuantizer: com.windstrom5.rebit.playback.dsp.DitherQuantizer? = null
    private var eqLimiter: com.windstrom5.rebit.playback.dsp.PeakLimiter? = null
    @Volatile private var eqActive = false
    @Volatile private var streamLabel: String? = null
    @Volatile private var sampleRateHz: Int = 0
    /** True once the stream is recognised as DoP (DSD over PCM) by its 0x05/0xFA markers. */
    @Volatile private var dopStream = false

    private fun looksLikeDop(b: ByteArray, channels: Int): Boolean {
        val frame = 3 * channels
        if (b.size < frame * 2) return false
        val m1 = b[2]
        val m2 = b[frame + 2]
        return (m1 == 0x05.toByte() && m2 == 0xFA.toByte()) || (m1 == 0xFA.toByte() && m2 == 0x05.toByte())
    }

    override val dropoutCount: Int get() = dropouts.get()
    override val levelMeter: com.windstrom5.rebit.playback.dsp.LevelMeter get() = meter

    override fun takeLimiterReductionDb(): Float = eqLimiter?.takeMaxReductionDb() ?: 0f

    override fun processingSummary(): com.windstrom5.rebit.domain.model.ProcessingSummary {
        val q = eqQuantizer
        return if (eqActive) {
            com.windstrom5.rebit.domain.model.ProcessingSummary(
                dither = q?.takeIf { it.bits <= 24 && it.mode != com.windstrom5.rebit.domain.model.DitherMode.OFF }
                    ?.let { "${it.bits}-bit · ${if (it.isNoiseShaped) "Noise-shaped" else "TPDF"}" },
                stages = listOf("EQ", "Limiter"),
                output = outputLabel()
            )
        } else {
            com.windstrom5.rebit.domain.model.ProcessingSummary(output = outputLabel())
        }
    }

    override fun start(
        decoder: AudioDecoder,
        format: PcmFormat,
        bufferSizeBytes: Int,
        onDropout: () -> Unit,
        onCompletion: () -> Unit,
        onError: (Throwable) -> Unit,
        // Deliberately unused — see the interface doc on why USB-direct never
        // applies ReplayGain/Normalization or any other gain adjustment.
        replayGainDb: Float?,
        trackPeak: Float?
    ) {
        this.bufferSizeBytes = bufferSizeBytes
        bytesPerSecond = format.sampleRateHz * format.channels * (format.bitDepth / 8)
        sampleRateHz = format.sampleRateHz

        // The DAC's declared per-sample container (activated.option.containerBytes) can be
        // wider than the source's own tight packing — e.g. 24-bit audio sent to a DAC whose
        // altsetting declares a 4-byte subslot ("24-in-32"), a common real-world UAC2
        // configuration (see DacCapabilityAnalyzer). PcmContainerPacker repacks decoder
        // output to match before it reaches the wire; TransferVerifier is told the same two
        // sizes so it strips that padding back out when comparing against source bytes,
        // rather than misreporting a correctly-repacked stream as a checksum mismatch.
        val sourceBytesPerSample = (format.bitDepth / 8).coerceAtLeast(1)
        val containerBytes = activated.option.containerBytes.takeIf { it > 0 } ?: sourceBytesPerSample
        val packer = PcmContainerPacker(sourceBytesPerSample, containerBytes, format.channels)
        verifier = TransferVerifier(sourceBytesPerSample, containerBytes)
        verifier.reset()
        trafficLogBuffer = UsbTrafficLog()

        biquadEqualizer = com.windstrom5.rebit.playback.dsp.TenBandBiquadEqualizer(format.sampleRateHz)
        meter.reset()
        meter.sampleRateHz = format.sampleRateHz
        dopStream = false
        streamLabel = "USB Direct · ${format.bitDepth}-bit · ${"%.1f".format(format.sampleRateHz / 1000.0).removeSuffix(".0")} kHz"
        if (format.bitDepth in setOf(16, 24, 32)) {
            val ditherMode = settingsRepository?.current?.ditherMode ?: com.windstrom5.rebit.domain.model.DitherMode.TPDF
            eqQuantizer = com.windstrom5.rebit.playback.dsp.DitherQuantizer(
                format.bitDepth, format.channels,
                if (format.bitDepth == 32) com.windstrom5.rebit.domain.model.DitherMode.OFF else ditherMode,
                format.sampleRateHz
            )
            eqLimiter = com.windstrom5.rebit.playback.dsp.PeakLimiter(format.channels, format.sampleRateHz)
        }

        val newStreamer = UsbIsochronousAudioStreamer(
            connection = connection,
            usbInterface = activated.usbInterface,
            endpoint = activated.dataEndpoint,
            feedbackEndpoint = activated.feedbackEndpoint,
            option = activated.option,
            format = format,
            requestPoolSize = requestPoolSize,
            transferStrategy = transferStrategy,
            useAdaptiveClockCorrection = useAdaptiveClockCorrection
        )

        // Every USB Direct profile runs on the native isochronous engine (Android's Java USB API
        // can't submit isochronous transfers). The Kotlin UsbRequest streamer is only a last
        // resort if the native library can't be loaded on this device.
        val nativeAvailable = try {
            UsbNativeIsochronousStreamer.ensureLibraryLoaded()
            true
        } catch (t: Throwable) {
            Log.w(TAG, "Native USB engine unavailable (${t.message}); using the legacy UsbRequest streamer.", t)
            false
        }

        var completionSignaled = false
        val pcmSource = {
            val chunk = ByteArray(this.bufferSizeBytes)
            val n = decoder.read(chunk)
            val raw: ByteArray? = when {
                n == -1 -> {
                    // The native engine reports completion itself, after its queue has drained.
                    if (!nativeAvailable && !completionSignaled) {
                        completionSignaled = true
                        onCompletion()
                    }
                    null
                }
                n == chunk.size -> chunk
                n <= 0 -> ByteArray(0) // transient stall, keep the pipe primed without stalling the isoc loop
                else -> chunk.copyOf(n) // final partial chunk of the file
            }
            // Record every byte this app read from the source, before any repacking —
            // the other half of the verification boundary, always the decoder's own
            // tight packing regardless of what the DAC's wire container needs.
            if (raw != null && raw.isNotEmpty()) verifier.recordSourceBytes(raw, raw.size)
            when {
                raw == null -> null
                raw.isEmpty() -> raw
                else -> {
                    val current = settingsRepository?.current
                    // "Auto-bypass DSP in bit-perfect mode" (on by default) keeps this path untouched.
                    val eqOn = current != null && current.eqEnabled && !current.autoBypassDspInBitPerfect && format.channels == 2
                    eqActive = eqOn
                    val audioBytes = if (eqOn) processPcmWithEq(raw, format.bitDepth, format.channels) else raw
                    // Meter what actually goes to the DAC (strided, so it stays cheap here) —
                    // unless it's DoP, whose "samples" are DSD bits plus markers, not audio levels.
                    if (format.bitDepth == 24 && looksLikeDop(audioBytes, format.channels)) {
                        dopStream = true
                    } else {
                        meter.measurePcm(audioBytes, audioBytes.size, format.bitDepth, format.channels)
                    }
                    packer.repack(audioBytes)
                }
            }
        }
        val onDropoutCallback = {
            dropouts.incrementAndGet()
            trafficLogBuffer.recordDropout()
            onDropout()
        }
        val onBytesTransmittedCallback = { bytes: ByteArray, length: Int ->
            // Only successfully-queued packets land here — this is genuinely
            // what reached the wire, not just what was attempted.
            verifier.recordTransmittedBytes(bytes, length)
            trafficLogBuffer.recordSuccess(length)
        }

        if (nativeAvailable) {
            val settings = settingsRepository?.current
            // Profiles: Pipelined = DAC-clocked (async feedback) at the chosen buffer latency;
            // Fixed-Rate = same queue, feedback ignored; Low-latency (SYNCHRONOUS) = 8 ms queue.
            val latencyMs = when (transferStrategy) {
                UsbTransferStrategy.SYNCHRONOUS -> 8
                else -> settings?.usbBufferLatencyMs ?: 32
            }
            val followDacClock = transferStrategy == UsbTransferStrategy.SYNCHRONOUS ||
                transferStrategy == UsbTransferStrategy.NATIVE || useAdaptiveClockCorrection
            val newNativeStreamer = UsbNativeIsochronousStreamer(
                connection = connection,
                usbInterface = activated.usbInterface,
                endpoint = activated.dataEndpoint,
                option = activated.option,
                format = format,
                feedbackEndpoint = activated.feedbackEndpoint,
                targetLatencyMs = latencyMs,
                useFeedback = followDacClock
            )
            nativeStreamer = newNativeStreamer
            newNativeStreamer.start(
                pcmSource, onDropoutCallback, onBytesTransmittedCallback, onError,
                onEndOfStream = {
                    if (!completionSignaled) {
                        completionSignaled = true
                        onCompletion()
                    }
                }
            )
            return
        }

        streamer = newStreamer
        newStreamer.start(pcmSource, onDropoutCallback, onBytesTransmittedCallback, onError)
    }

    override fun stop() {
        streamer?.stop()
        streamer = null
        nativeStreamer?.stop()
        nativeStreamer = null
        usbDacManager.deactivateStreamingOption(device, connection, activated)
        connection.close()
    }

    override fun usbLinkStats(): com.windstrom5.rebit.domain.model.UsbLinkStats? {
        val n = nativeStreamer ?: return null
        if (n.packetsPerSecond <= 0) return null
        return com.windstrom5.rebit.domain.model.UsbLinkStats(
            packetsPerSecond = n.packetsPerSecond,
            queueMs = n.queueMs,
            bufferedMs = n.bufferedMs,
            feedbackActive = n.feedbackActive,
            dacClockHz = n.measuredRateHz,
            nominalHz = sampleRateHz,
            underruns = n.underruns,
            packetErrors = n.packetErrors
        )
    }

    /** e.g. "USB Direct · 24-bit · 96 kHz · async feedback 96.002 kHz". */
    private fun outputLabel(): String? {
        val base = if (dopStream) {
            "USB Direct · DoP · ${com.windstrom5.rebit.domain.model.dsdRateLabel(sampleRateHz * 16) ?: "DSD"}"
        } else streamLabel ?: return null
        val native = nativeStreamer ?: return base
        val t = native.transportSummary
        return if (t.isBlank()) base else "$base · $t"
    }

    override fun setBufferSize(bytes: Int) {
        bufferSizeBytes = bytes
    }

    override fun currentLatencyEstimateMs(): Double {
        nativeStreamer?.let { return it.bufferedMs }
        return if (bytesPerSecond > 0) (bufferSizeBytes.toDouble() / bytesPerSecond) * 1000.0 else 0.0
    }

    override fun currentPositionMs(): Long {
        if (bytesPerSecond <= 0) return 0L
        // Native engine: count what has actually been sent to the DAC, not what was decoded
        // ahead into its queue, so lyrics and the seek bar match what you hear.
        nativeStreamer?.let { native ->
            return native.framesSent * 1000L / sampleRateHz.coerceAtLeast(1)
        }
        // Source bytes (not transmitted) are the right measure here: this engine
        // does no bit-depth conversion or resampling (see the class doc comment),
        // so bytes read from the decoder correspond directly to playback time —
        // and using "read" rather than "successfully transmitted over USB" means
        // position keeps advancing correctly even through a transient dropout.
        return (verifier.snapshot().sourceBytes / bytesPerSecond.toDouble() * 1000.0).toLong()
    }

    override fun verificationSnapshot() = verifier.snapshot()

    /**
     * EQ on the USB path (only when the person turned it on, which by definition gives up
     * bit-perfect delivery): decode to float, run the EQ, protect against overs with the peak
     * limiter, and requantize at the stream's own depth with dither — rather than truncating,
     * which is what turned EQ'd 24-bit audio into gritty fades before.
     */
    private fun processPcmWithEq(bytes: ByteArray, bitDepth: Int, channels: Int): ByteArray {
        val eq = biquadEqualizer ?: return bytes
        val settings = settingsRepository?.current ?: return bytes
        val q = eqQuantizer ?: return bytes
        eq.applySettings(settings.eqGains, settings.eqFilters, settings.eqFilterPreampDb)
        val floats = com.windstrom5.rebit.playback.dsp.PcmCodec.toFloat(bytes, bytes.size, bitDepth)
        eq.processStereo(floats)
        eqLimiter?.process(floats, floats.size)
        return com.windstrom5.rebit.playback.dsp.PcmCodec.fromFloat(floats, floats.size, bitDepth, channels, q, dither = true)
    }

    companion object {
        private const val TAG = "UsbDirectPlaybackEngine"
    }
}
