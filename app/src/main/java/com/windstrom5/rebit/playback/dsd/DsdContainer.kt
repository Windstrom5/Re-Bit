package com.windstrom5.rebit.playback.dsd

import java.io.IOException
import java.io.InputStream

/**
 * What a DSD file declares, from its DSF or DSDIFF (DFF) header.
 *
 * @property dsdRateHz 1-bit sample rate per channel (2 822 400 for DSD64)
 * @property bytesPerChannel real audio bytes per channel (8 DSD samples each)
 * @property dataOffset byte offset of the first audio byte in the file
 */
data class DsdInfo(
    val dsdRateHz: Int,
    val channels: Int,
    val bytesPerChannel: Long,
    val dataOffset: Long,
    val layout: Layout,
    /** DSF only: bytes per channel per block (normally 4096). */
    val blockSize: Int = 0,
    /** DSF with bitsPerSample = 1 stores each byte least-significant-bit first. */
    val lsbFirst: Boolean = false
) {
    enum class Layout { DSF_BLOCKS, DFF_INTERLEAVED }

    val durationMs: Long get() = if (dsdRateHz > 0) bytesPerChannel * 8L * 1000L / dsdRateHz else 0L
    val multiple: Int get() = Math.round(dsdRateHz / 44_100.0).toInt()
}

/** Parses DSF (Sony) and DSDIFF/DFF (Philips) headers. DST-compressed DFF is rejected. */
object DsdContainer {

    fun parse(input: InputStream): DsdInfo? {
        val magic = ByteArray(4)
        if (readFully(input, magic) < 4) return null
        return when (String(magic, Charsets.US_ASCII)) {
            "DSD " -> parseDsf(input)
            "FRM8" -> parseDff(input)
            else -> null
        }
    }

    // ── DSF: little-endian, 'DSD ' chunk, 'fmt ' chunk, 'data' chunk ─────────────────────
    private fun parseDsf(input: InputStream): DsdInfo? {
        val dsdRest = ByteArray(24) // size(8) + total size(8) + metadata pointer(8)
        if (readFully(input, dsdRest) < 24) return null
        var pos = 28L
        val dsdChunkSize = le64(dsdRest, 0)
        if (dsdChunkSize > 28) { skipFully(input, dsdChunkSize - 28); pos = dsdChunkSize }

        var rate = 0
        var channels = 0
        var bits = 1
        var sampleCount = 0L
        var blockSize = 4096
        val hdr = ByteArray(12)
        while (true) {
            if (readFully(input, hdr) < 12) return null
            val id = String(hdr, 0, 4, Charsets.US_ASCII)
            val size = le64(hdr, 4)
            pos += 12
            when (id) {
                "fmt " -> {
                    val body = ByteArray((size - 12).toInt().coerceIn(0, 256))
                    if (readFully(input, body) < 40) return null
                    if (le32(body, 4) != 0) return null // formatID 0 = DSD raw
                    channels = le32(body, 12)
                    rate = le32(body, 16)
                    bits = le32(body, 20)
                    sampleCount = le64(body, 24)
                    blockSize = le32(body, 32)
                    skipFully(input, size - 12 - body.size)
                    pos += size - 12
                }
                "data" -> {
                    if (rate <= 0 || channels <= 0 || blockSize <= 0) return null
                    return DsdInfo(
                        dsdRateHz = rate,
                        channels = channels,
                        bytesPerChannel = (sampleCount + 7) / 8,
                        dataOffset = pos,
                        layout = DsdInfo.Layout.DSF_BLOCKS,
                        blockSize = blockSize,
                        lsbFirst = bits == 1
                    )
                }
                else -> {
                    skipFully(input, size - 12)
                    pos += size - 12
                }
            }
        }
    }

    // ── DSDIFF: big-endian, FRM8 'DSD ' form, PROP/SND chunk, then the DSD data chunk ────
    private fun parseDff(input: InputStream): DsdInfo? {
        val formRest = ByteArray(12) // size(8) + form type(4)
        if (readFully(input, formRest) < 12) return null
        if (String(formRest, 8, 4, Charsets.US_ASCII) != "DSD ") return null
        var pos = 16L
        var rate = 0
        var channels = 0
        val hdr = ByteArray(12)
        while (true) {
            if (readFully(input, hdr) < 12) return null
            val id = String(hdr, 0, 4, Charsets.US_ASCII)
            val size = be64(hdr, 4)
            pos += 12
            when (id) {
                "PROP" -> {
                    val body = ByteArray(size.coerceIn(0, 1 shl 20).toInt())
                    if (readFully(input, body) < body.size) return null
                    if (body.size < 4 || String(body, 0, 4, Charsets.US_ASCII) != "SND ") return null
                    var o = 4
                    while (o + 12 <= body.size) {
                        val sub = String(body, o, 4, Charsets.US_ASCII)
                        val subSize = be64(body, o + 4).toInt()
                        val d = o + 12
                        when (sub) {
                            "FS  " -> if (d + 4 <= body.size) rate = be32(body, d)
                            "CHNL" -> if (d + 2 <= body.size) channels = be16(body, d)
                            "CMPR" -> if (d + 4 <= body.size && String(body, d, 4, Charsets.US_ASCII) != "DSD ") return null
                        }
                        o = d + subSize + (subSize and 1)
                    }
                    skipFully(input, size - body.size)
                    pos += size
                }
                "DSD " -> {
                    if (rate <= 0 || channels <= 0) return null
                    return DsdInfo(
                        dsdRateHz = rate,
                        channels = channels,
                        bytesPerChannel = size / channels,
                        dataOffset = pos,
                        layout = DsdInfo.Layout.DFF_INTERLEAVED
                    )
                }
                "DST " -> return null // DST-compressed DFF isn't supported
                else -> {
                    skipFully(input, size)
                    pos += size
                }
            }
            if (size % 2L == 1L) { skipFully(input, 1); pos += 1 }
        }
    }

    internal fun readFully(input: InputStream, buf: ByteArray, off: Int = 0, len: Int = buf.size - off): Int {
        var done = 0
        while (done < len) {
            val n = input.read(buf, off + done, len - done)
            if (n < 0) break
            done += n
        }
        return done
    }

    internal fun skipFully(input: InputStream, count: Long) {
        var left = count
        while (left > 0) {
            val s = input.skip(left)
            if (s <= 0) {
                if (input.read() < 0) throw IOException("Unexpected end of DSD file")
                left -= 1
            } else left -= s
        }
    }

    private fun le32(b: ByteArray, o: Int) = (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8) or
        ((b[o + 2].toInt() and 0xFF) shl 16) or ((b[o + 3].toInt() and 0xFF) shl 24)
    private fun le64(b: ByteArray, o: Int) = (le32(b, o).toLong() and 0xFFFFFFFFL) or (le32(b, o + 4).toLong() shl 32)
    private fun be16(b: ByteArray, o: Int) = ((b[o].toInt() and 0xFF) shl 8) or (b[o + 1].toInt() and 0xFF)
    private fun be32(b: ByteArray, o: Int) = ((b[o].toInt() and 0xFF) shl 24) or ((b[o + 1].toInt() and 0xFF) shl 16) or
        ((b[o + 2].toInt() and 0xFF) shl 8) or (b[o + 3].toInt() and 0xFF)
    private fun be64(b: ByteArray, o: Int) = (be32(b, o).toLong() shl 32) or (be32(b, o + 4).toLong() and 0xFFFFFFFFL)
}

/**
 * Turns a DSD file's audio data into a stream of *frames*: one byte per channel, channels
 * interleaved, each byte most-significant-bit first (= oldest sample first). That's the common
 * form both the DoP packer and the DSD→PCM converter consume, whatever the container stored.
 *
 * [input] must be positioned at [DsdInfo.dataOffset] (or at a block boundary after a seek).
 */
class DsdFrameReader(private val info: DsdInfo, private val input: InputStream, startFrame: Long = 0L) {

    private val ch = info.channels
    private var framesLeft = info.bytesPerChannel - startFrame
    // DSF: one group = blockSize bytes for each channel, back to back.
    private val group = if (info.layout == DsdInfo.Layout.DSF_BLOCKS) ByteArray(info.blockSize * ch) else ByteArray(0)
    private var groupFrames = 0
    private var groupPos = 0

    init {
        if (info.layout == DsdInfo.Layout.DSF_BLOCKS && startFrame % info.blockSize != 0L) {
            // Caller positioned us at the start of the containing block; drop the leading frames.
            val skip = (startFrame % info.blockSize).toInt()
            if (fillGroup()) groupPos = skip.coerceAtMost(groupFrames)
        }
    }

    /** Reads up to [maxFrames] frames into [dst] (needs maxFrames × channels bytes). Returns frames read, 0 at end. */
    fun read(dst: ByteArray, maxFrames: Int): Int {
        val want = minOf(maxFrames.toLong(), framesLeft).toInt()
        if (want <= 0) return 0
        return when (info.layout) {
            DsdInfo.Layout.DFF_INTERLEAVED -> {
                val got = DsdContainer.readFully(input, dst, 0, want * ch) / ch
                framesLeft -= got
                got
            }
            DsdInfo.Layout.DSF_BLOCKS -> {
                var done = 0
                while (done < want) {
                    if (groupPos >= groupFrames && !fillGroup()) break
                    val n = minOf(want - done, groupFrames - groupPos)
                    for (f in 0 until n) {
                        val o = (done + f) * ch
                        for (c in 0 until ch) {
                            val b = group[c * info.blockSize + groupPos + f]
                            dst[o + c] = if (info.lsbFirst) REVERSE[b.toInt() and 0xFF] else b
                        }
                    }
                    groupPos += n
                    done += n
                }
                framesLeft -= done
                done
            }
        }
    }

    private fun fillGroup(): Boolean {
        val got = DsdContainer.readFully(input, group)
        if (got < group.size) {
            // A short final group (rare): only whole per-channel blocks are usable.
            if (got < ch) return false
            java.util.Arrays.fill(group, got, group.size, 0)
        }
        groupFrames = info.blockSize
        groupPos = 0
        return true
    }

    companion object {
        /** Bit-reversal table: LSB-first byte → MSB-first byte. */
        val REVERSE = ByteArray(256) { i ->
            var v = i
            var r = 0
            repeat(8) { r = (r shl 1) or (v and 1); v = v shr 1 }
            r.toByte()
        }

        /** Byte offset in the file where frame [frame] (or the block containing it) starts. */
        fun seekOffset(info: DsdInfo, frame: Long): Long = when (info.layout) {
            DsdInfo.Layout.DFF_INTERLEAVED -> info.dataOffset + frame * info.channels
            DsdInfo.Layout.DSF_BLOCKS -> info.dataOffset + (frame / info.blockSize) * info.blockSize.toLong() * info.channels
        }
    }
}
