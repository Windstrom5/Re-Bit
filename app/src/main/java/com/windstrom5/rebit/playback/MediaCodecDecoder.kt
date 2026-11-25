package com.windstrom5.rebit.playback

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Build
import com.windstrom5.rebit.domain.model.PcmFormat
import java.nio.ByteOrder

/**
 * Decodes the formats Android ships codecs for — ALAC, AAC, MP3, Opus, Vorbis — through
 * MediaExtractor + MediaCodec, to the little-endian integer PCM the engines expect.
 *
 * Hi-res ALAC: platform decoders output 16-bit PCM by default, which would silently truncate a
 * 24-bit ALAC file. For ALAC deeper than 16 bits this asks for float output (exact for 24-bit:
 * float has a 24-bit mantissa) and converts it back to 24-bit integers, so a 24/96 ALAC plays
 * as 24/96. If the device's decoder refuses float, it says so honestly by reporting 16-bit.
 * Lossy codecs decode to 16-bit — their output has nothing below that worth keeping.
 */
class MediaCodecDecoder(private val context: Context) : AudioDecoder {

    private var extractor: MediaExtractor? = null
    private var codec: MediaCodec? = null
    @Volatile private var closed = false
    private var inputDone = false
    private var outputDone = false
    private var floatOutput = false
    private var outBits = 16
    private var outRate = 0
    private var sourceBits = 16
    private var channels = 2
    private val pending = ArrayDeque<ByteArray>()
    private var pendingOffset = 0

    override fun open(uriString: String): PcmFormat {
        close()
        closed = false
        val ex = MediaExtractor()
        ex.setDataSource(context, Uri.parse(uriString), null)
        val track = (0 until ex.trackCount).firstOrNull {
            ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
        } ?: run {
            ex.release()
            throw IllegalArgumentException("No audio track in $uriString")
        }
        ex.selectTrack(track)
        val format = ex.getTrackFormat(track)
        val mime = format.getString(MediaFormat.KEY_MIME)!!
        val rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        sourceBits = declaredBitDepth(format)
        outRate = rate

        val wantFloat = mime == MIME_ALAC && sourceBits > 16 && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N
        val mc = MediaCodec.createDecoderByType(mime)
        var configured = false
        if (wantFloat) {
            try {
                format.setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_FLOAT)
                mc.configure(format, null, null, 0)
                configured = true
            } catch (_: Exception) {
                format.removeKey(MediaFormat.KEY_PCM_ENCODING)
            }
        }
        if (!configured) mc.configure(format, null, null, 0)
        mc.start()
        extractor = ex
        codec = mc

        // The real output encoding is only known once the codec reports its output format;
        // step() applies it the moment it arrives, before any buffer is converted.
        if (warmUp(mc) == null) runCatching { applyOutputFormat(mc.outputFormat) }
        return PcmFormat(outRate, outBits, channels)
    }

    private fun applyOutputFormat(f: MediaFormat) {
        val encoding = if (f.containsKey(MediaFormat.KEY_PCM_ENCODING)) f.getInteger(MediaFormat.KEY_PCM_ENCODING)
        else AudioFormat.ENCODING_PCM_16BIT
        floatOutput = encoding == AudioFormat.ENCODING_PCM_FLOAT
        outBits = when {
            floatOutput -> if (sourceBits > 16) 24 else 16
            encoding == AudioFormat.ENCODING_PCM_24BIT_PACKED -> 24
            encoding == AudioFormat.ENCODING_PCM_32BIT -> 32
            else -> 16
        }
        if (f.containsKey(MediaFormat.KEY_SAMPLE_RATE)) outRate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        if (f.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
    }

    /** Feeds input until the first output format/buffer appears (bounded), keeping any output. */
    private fun warmUp(mc: MediaCodec): MediaFormat? {
        val deadline = System.currentTimeMillis() + 1_500
        while (System.currentTimeMillis() < deadline && !outputDone) {
            val before = pending.size
            val fmt = step(mc)
            if (fmt != null) return fmt
            if (pending.size > before) return null
        }
        return null
    }

    override fun read(buffer: ByteArray): Int {
        val mc = codec ?: return -1
        val frame = (outBits / 8) * channels
        val limit = buffer.size - buffer.size % frame
        var written = 0
        while (written < limit) {
            if (pending.isEmpty()) {
                if (outputDone) break
                step(mc)
                continue
            }
            val head = pending.first()
            val n = minOf(head.size - pendingOffset, limit - written)
            System.arraycopy(head, pendingOffset, buffer, written, n)
            written += n
            pendingOffset += n
            if (pendingOffset >= head.size) {
                pending.removeFirst()
                pendingOffset = 0
            }
        }
        return if (written == 0 && outputDone) -1 else written
    }

    /** One input + one output round. Returns the new output format if it changed. */
    private fun step(mc: MediaCodec): MediaFormat? {
        if (closed) { outputDone = true; return null }
        val ex = extractor ?: return null
        try {
            if (!inputDone) {
                val inIdx = mc.dequeueInputBuffer(5_000)
                if (inIdx >= 0) {
                    val buf = mc.getInputBuffer(inIdx)!!
                    val size = ex.readSampleData(buf, 0)
                    if (size < 0) {
                        mc.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        mc.queueInputBuffer(inIdx, 0, size, maxOf(0L, ex.sampleTime), 0)
                        ex.advance()
                    }
                }
            }
            val info = MediaCodec.BufferInfo()
            val outIdx = mc.dequeueOutputBuffer(info, 5_000)
            when {
                outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val f = mc.outputFormat
                    applyOutputFormat(f)
                    return f
                }
                outIdx >= 0 -> {
                    if (info.size > 0) {
                        val out = mc.getOutputBuffer(outIdx)!!
                        out.position(info.offset)
                        out.limit(info.offset + info.size)
                        pending.addLast(if (floatOutput) floatToInt(out, info.size) else ByteArray(info.size).also { out.get(it) })
                    }
                    mc.releaseOutputBuffer(outIdx, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                }
            }
        } catch (e: IllegalStateException) {
            if (closed) { outputDone = true; return null }
            throw IllegalStateException("Decoder failed: ${e.message}", e)
        }
        return null
    }

    /** Float PCM → 24- or 16-bit little-endian integers (exact for 24-bit sources). */
    private fun floatToInt(buf: java.nio.ByteBuffer, size: Int): ByteArray {
        val floats = buf.order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        val n = size / 4
        val bps = if (outBits == 24) 3 else 2
        val out = ByteArray(n * bps)
        var o = 0
        for (i in 0 until n) {
            val f = floats.get(i)
            if (bps == 3) {
                val v = Math.round(f * 8_388_608f).coerceIn(-8_388_608, 8_388_607)
                out[o++] = (v and 0xFF).toByte()
                out[o++] = ((v shr 8) and 0xFF).toByte()
                out[o++] = ((v shr 16) and 0xFF).toByte()
            } else {
                val v = Math.round(f * 32_768f).coerceIn(-32_768, 32_767)
                out[o++] = (v and 0xFF).toByte()
                out[o++] = ((v shr 8) and 0xFF).toByte()
            }
        }
        return out
    }

    override fun seek(positionMs: Long) {
        val mc = codec ?: return
        val ex = extractor ?: return
        ex.seekTo(positionMs * 1000, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
        mc.flush()
        pending.clear()
        pendingOffset = 0
        inputDone = false
        outputDone = false
    }

    override fun close() {
        closed = true
        try { codec?.stop() } catch (_: Exception) {}
        try { codec?.release() } catch (_: Exception) {}
        try { extractor?.release() } catch (_: Exception) {}
        codec = null
        extractor = null
        pending.clear()
        pendingOffset = 0
        inputDone = false
        outputDone = false
    }

    companion object {
        const val MIME_ALAC = "audio/alac"

        /**
         * Source bit depth: the extractor's "bits-per-sample" when present, else the ALAC magic
         * cookie (ALACSpecificConfig: frameLength u32, compatibleVersion u8, bitDepth u8 …),
         * which may be preceded by an 'alac' atom header.
         */
        fun declaredBitDepth(format: MediaFormat): Int {
            if (format.containsKey("bits-per-sample")) return format.getInteger("bits-per-sample")
            if (format.getString(MediaFormat.KEY_MIME) != MIME_ALAC) return 16
            val csd = format.getByteBuffer("csd-0") ?: return 16
            val b = ByteArray(csd.remaining()).also { csd.duplicate().get(it) }
            return alacCookieBitDepth(b)
        }

        internal fun alacCookieBitDepth(b: ByteArray): Int {
            var o = 0
            // Skip any atom headers: [size u32]['frma'/'alac'] [version/flags u32 for 'alac']
            while (o + 8 <= b.size) {
                val tag = String(b, o + 4, 4, Charsets.US_ASCII)
                if (tag == "frma") { o += 12; continue }
                if (tag == "alac") { o += 12; break }
                break
            }
            if (o + 6 > b.size) return 16
            val depth = b[o + 5].toInt() and 0xFF
            return if (depth in setOf(16, 20, 24, 32)) depth else 16
        }
    }
}
