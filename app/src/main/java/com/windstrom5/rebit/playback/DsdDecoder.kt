package com.windstrom5.rebit.playback

import android.content.Context
import android.net.Uri
import com.windstrom5.rebit.domain.model.PcmFormat
import com.windstrom5.rebit.playback.dsd.DsdContainer
import com.windstrom5.rebit.playback.dsd.DsdFrameReader
import com.windstrom5.rebit.playback.dsd.DsdInfo
import com.windstrom5.rebit.playback.dsd.DsdToPcmConverter
import java.io.InputStream

/**
 * DSD decoder for DSF and DSDIFF (DFF) files, in one of two output modes:
 *
 *  - [Output.DOP] — DSD over PCM: the untouched 1-bit stream framed in 24-bit PCM at rate/16
 *    (DSD64 → 176.4 kHz) for a DoP-capable USB DAC. Bit-perfect DSD.
 *  - [Output.PCM] — converted to 24-bit 88.2 kHz PCM by [DsdToPcmConverter], for the Android
 *    output path or a DAC without DoP.
 *
 * Seeking reopens the file and jumps to the containing block (DSF) or byte (DFF).
 */
class DsdDecoder(
    private val context: Context,
    private val output: Output = Output.PCM
) : AudioDecoder {

    enum class Output { DOP, PCM }

    private var uri: Uri? = null
    private var info: DsdInfo? = null
    private var input: InputStream? = null
    private var reader: DsdFrameReader? = null
    private val dop = DopPacker()
    private var converter: DsdToPcmConverter? = null
    private var scratch = ByteArray(0)
    private var pending = ByteArray(0)
    private var pendingOffset = 0

    val dsdInfo: DsdInfo? get() = info

    override fun open(uriString: String): PcmFormat {
        close()
        val u = Uri.parse(uriString)
        val stream = context.contentResolver.openInputStream(u)?.buffered(256 * 1024)
            ?: throw IllegalArgumentException("Cannot open DSD file: $uriString")
        val i = DsdContainer.parse(stream) ?: run {
            stream.close()
            throw IllegalArgumentException("Not a supported DSF/DFF file (DST-compressed DFF isn't supported)")
        }
        uri = u
        info = i
        input = stream
        reader = DsdFrameReader(i, stream)
        dop.reset()
        return when (output) {
            Output.DOP -> PcmFormat(i.dsdRateHz / 16, 24, i.channels)
            Output.PCM -> {
                val c = DsdToPcmConverter(i.dsdRateHz, i.channels)
                converter = c
                PcmFormat(c.outputRateHz, 24, i.channels)
            }
        }
    }

    override fun read(buffer: ByteArray): Int {
        val i = info ?: return -1
        val r = reader ?: return -1
        var written = 0
        while (written < buffer.size) {
            if (pendingOffset >= pending.size) {
                if (!produce(i, r, buffer.size - written)) break
                if (pending.isEmpty()) break
            }
            val n = minOf(pending.size - pendingOffset, buffer.size - written)
            System.arraycopy(pending, pendingOffset, buffer, written, n)
            pendingOffset += n
            written += n
        }
        // Hand back whole 24-bit frames only.
        val frame = 3 * i.channels
        val whole = written - written % frame
        if (whole < written) {
            // Return the partial frame's bytes to the front of the pending buffer.
            pendingOffset -= written - whole
        }
        return if (whole == 0) -1 else whole
    }

    /** Decodes the next block into [pending]. Returns false at end of stream. */
    private fun produce(i: DsdInfo, r: DsdFrameReader, wantBytes: Int): Boolean {
        val ch = i.channels
        val outFrames = (wantBytes / (3 * ch)).coerceIn(64, 16_384)
        val dsdFrames = when (output) {
            Output.DOP -> outFrames * 2
            Output.PCM -> outFrames * (converter?.decimation ?: 32) / 8
        }
        if (scratch.size < dsdFrames * ch) scratch = ByteArray(dsdFrames * ch)
        val got = r.read(scratch, dsdFrames)
        if (got <= 0) return false
        pending = when (output) {
            Output.DOP -> dop.packInterleaved(scratch, got, ch)
            Output.PCM -> encode24(converter!!.process(scratch, got))
        }
        pendingOffset = 0
        return true
    }

    override fun seek(positionMs: Long) {
        val i = info ?: return
        val u = uri ?: return
        val frame = (positionMs * (i.dsdRateHz / 8L) / 1000L).coerceIn(0L, i.bytesPerChannel)
        // DoP needs pairs of frames; keep the marker sequence starting cleanly.
        val aligned = frame - frame % 2
        try { input?.close() } catch (_: Exception) {}
        val stream = context.contentResolver.openInputStream(u)?.buffered(256 * 1024) ?: return
        DsdContainer.skipFully(stream, DsdFrameReader.seekOffset(i, aligned))
        input = stream
        reader = DsdFrameReader(i, stream, aligned)
        converter?.reset()
        dop.reset()
        pending = ByteArray(0)
        pendingOffset = 0
    }

    override fun close() {
        try { input?.close() } catch (_: Exception) {}
        input = null
        reader = null
        info = null
        converter = null
        pending = ByteArray(0)
        pendingOffset = 0
    }

    private fun encode24(samples: FloatArray): ByteArray {
        val out = ByteArray(samples.size * 3)
        var o = 0
        for (s in samples) {
            val v = Math.round(s * 8_388_608f).coerceIn(-8_388_608, 8_388_607)
            out[o++] = (v and 0xFF).toByte()
            out[o++] = ((v shr 8) and 0xFF).toByte()
            out[o++] = ((v shr 16) and 0xFF).toByte()
        }
        return out
    }
}
