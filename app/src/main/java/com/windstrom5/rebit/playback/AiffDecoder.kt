package com.windstrom5.rebit.playback

import android.content.Context
import android.net.Uri
import com.windstrom5.rebit.domain.model.PcmFormat
import java.io.IOException
import java.io.InputStream

/**
 * Header of an AIFF / AIFF-C file: what the COMM chunk declares and where the SSND samples start.
 * Pure parsing over an [InputStream], shared by the decoder and the library scanner.
 */
data class AiffHeader(
    val sampleRateHz: Int,
    val bitDepth: Int,
    val channels: Int,
    val frames: Long,
    /** Byte offset of the first sample from the start of the file. */
    val dataOffset: Long,
    /** AIFF is big-endian; AIFF-C "sowt" stores little-endian samples. */
    val littleEndian: Boolean
) {
    val bytesPerSample: Int get() = (bitDepth + 7) / 8
    val frameBytes: Int get() = bytesPerSample * channels
    val durationMs: Long get() = if (sampleRateHz > 0) frames * 1000L / sampleRateHz else 0L

    companion object {
        /**
         * Reads FORM/AIFF or FORM/AIFC up to the start of the sample data. Returns null when the
         * file isn't AIFF or uses a compressed AIFF-C codec (only NONE / sowt / twos are PCM).
         */
        fun parse(input: InputStream): AiffHeader? {
            val head = ByteArray(12)
            if (readFully(input, head) < 12) return null
            if (String(head, 0, 4, Charsets.US_ASCII) != "FORM") return null
            val kind = String(head, 8, 4, Charsets.US_ASCII)
            if (kind != "AIFF" && kind != "AIFC") return null

            var pos = 12L
            var rate = 0
            var bits = 0
            var channels = 0
            var frames = 0L
            var littleEndian = false
            val chunk = ByteArray(8)
            while (true) {
                if (readFully(input, chunk) < 8) return null
                pos += 8
                val id = String(chunk, 0, 4, Charsets.US_ASCII)
                val size = be32(chunk, 4).toLong() and 0xFFFFFFFFL
                when (id) {
                    "COMM" -> {
                        val body = ByteArray(size.coerceAtMost(64).toInt())
                        if (readFully(input, body) < minOf(18, body.size)) return null
                        channels = be16(body, 0)
                        frames = be32(body, 2).toLong() and 0xFFFFFFFFL
                        bits = be16(body, 6)
                        rate = extended80ToInt(body, 8)
                        if (kind == "AIFC" && body.size >= 22) {
                            when (String(body, 18, 4, Charsets.US_ASCII)) {
                                "NONE", "twos" -> littleEndian = false
                                "sowt" -> littleEndian = true
                                else -> return null // compressed AIFF-C (ulaw, ima4, fl32…)
                            }
                        }
                        skipFully(input, size - body.size)
                        pos += size
                    }
                    "SSND" -> {
                        val ssnd = ByteArray(8)
                        if (readFully(input, ssnd) < 8) return null
                        val offset = be32(ssnd, 0).toLong() and 0xFFFFFFFFL
                        skipFully(input, offset)
                        pos += 8 + offset
                        if (rate <= 0 || bits <= 0 || channels <= 0) return null
                        return AiffHeader(rate, bits, channels, frames, pos, littleEndian)
                    }
                    else -> {
                        skipFully(input, size)
                        pos += size
                    }
                }
                // Chunks are padded to an even length.
                if (size % 2L == 1L) {
                    skipFully(input, 1)
                    pos += 1
                }
            }
        }

        /** IEEE 754 80-bit extended → Int (AIFF's sample-rate field). */
        internal fun extended80ToInt(b: ByteArray, o: Int): Int {
            val exponent = ((b[o].toInt() and 0x7F) shl 8) or (b[o + 1].toInt() and 0xFF)
            var mantissa = 0L
            for (i in 0 until 8) mantissa = (mantissa shl 8) or (b[o + 2 + i].toLong() and 0xFF)
            if (exponent == 0 && mantissa == 0L) return 0
            // The mantissa's top bit is the explicit integer bit, so read it unsigned (ushr 1, then
            // compensate the exponent) rather than as a negative Long.
            val shift = exponent - 16383 - 63
            val value = (mantissa ushr 1).toDouble() * Math.pow(2.0, (shift + 1).toDouble())
            return Math.round(value).toInt()
        }

        private fun be16(b: ByteArray, o: Int) = ((b[o].toInt() and 0xFF) shl 8) or (b[o + 1].toInt() and 0xFF)
        private fun be32(b: ByteArray, o: Int) = ((b[o].toInt() and 0xFF) shl 24) or ((b[o + 1].toInt() and 0xFF) shl 16) or
            ((b[o + 2].toInt() and 0xFF) shl 8) or (b[o + 3].toInt() and 0xFF)

        internal fun readFully(input: InputStream, buf: ByteArray): Int {
            var off = 0
            while (off < buf.size) {
                val n = input.read(buf, off, buf.size - off)
                if (n < 0) break
                off += n
            }
            return off
        }

        internal fun skipFully(input: InputStream, count: Long) {
            var left = count
            while (left > 0) {
                val skipped = input.skip(left)
                if (skipped <= 0) {
                    if (input.read() < 0) throw IOException("Unexpected end of file")
                    left -= 1
                } else {
                    left -= skipped
                }
            }
        }
    }
}

/**
 * Lossless AIFF / AIFF-C (NONE, twos, sowt) decoder: 8–32-bit integer PCM, converted to the
 * little-endian samples the engines expect. Reads whole frames only, so a short read from the
 * underlying stream can never shift the byte/channel alignment.
 */
class AiffDecoder(private val context: Context) : AudioDecoder {

    private var input: InputStream? = null
    private var header: AiffHeader? = null
    private var uri: Uri? = null
    private var bytesLeft = 0L

    override fun open(uriString: String): PcmFormat {
        close()
        val u = Uri.parse(uriString)
        val stream = context.contentResolver.openInputStream(u)?.buffered(64 * 1024)
            ?: throw IllegalArgumentException("Cannot open AIFF: $uriString")
        val h = AiffHeader.parse(stream) ?: run {
            stream.close()
            throw IllegalArgumentException("Not a PCM AIFF/AIFF-C file")
        }
        uri = u
        input = stream
        header = h
        bytesLeft = h.frames * h.frameBytes
        // 8-bit AIFF is signed; the engines treat 8-bit as unsigned WAV-style, so widen to 16.
        return PcmFormat(h.sampleRateHz, if (h.bitDepth <= 8) 16 else h.bytesPerSample * 8, h.channels)
    }

    override fun read(buffer: ByteArray): Int {
        val h = header ?: return -1
        val stream = input ?: return -1
        if (bytesLeft <= 0) return -1
        val outBps = if (h.bitDepth <= 8) 2 else h.bytesPerSample
        val inBps = h.bytesPerSample
        val maxFrames = buffer.size / (outBps * h.channels)
        if (maxFrames == 0) return 0
        val wantBytes = minOf(maxFrames.toLong() * h.frameBytes, bytesLeft).toInt()
        val raw = ByteArray(wantBytes)
        val n = AiffHeader.readFully(stream, raw)
        if (n <= 0) return -1
        val usable = n - n % h.frameBytes
        bytesLeft -= n
        if (usable == 0) return -1

        var o = 0
        var i = 0
        while (i < usable) {
            if (inBps == 1) {
                // signed 8-bit → 16-bit
                buffer[o] = 0
                buffer[o + 1] = raw[i]
            } else if (h.littleEndian) {
                System.arraycopy(raw, i, buffer, o, inBps)
            } else {
                for (b in 0 until inBps) buffer[o + b] = raw[i + inBps - 1 - b]
            }
            i += inBps
            o += outBps
        }
        return o
    }

    override fun seek(positionMs: Long) {
        val h = header ?: return
        val u = uri ?: return
        val frame = (positionMs * h.sampleRateHz / 1000L).coerceIn(0L, h.frames)
        try { input?.close() } catch (_: Exception) {}
        val stream = context.contentResolver.openInputStream(u)?.buffered(64 * 1024) ?: return
        AiffHeader.skipFully(stream, h.dataOffset + frame * h.frameBytes)
        input = stream
        bytesLeft = (h.frames - frame) * h.frameBytes
    }

    override fun close() {
        try { input?.close() } catch (_: Exception) {}
        input = null
        header = null
        bytesLeft = 0
    }
}
