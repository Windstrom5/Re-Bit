package com.windstrom5.rebit.playback

/**
 * DoP (DSD over PCM, open standard v1.1) packer.
 *
 * Each 24-bit PCM sample carries 16 DSD bits of one channel plus a marker:
 *   bits 23–16  marker, 0x05 / 0xFA alternating every sample period (same for all channels)
 *   bits 15–8   the *older* DSD byte (first in time)
 *   bits  7–0   the *newer* DSD byte
 * DSD bytes are MSB-first (bit 7 = oldest). DSD64 becomes 176.4 kHz 24-bit DoP.
 *
 * The previous implementation put the older byte in the low position, which a DoP DAC
 * decodes with every pair of bytes swapped in time — i.e. noise.
 */
class DopPacker {
    private var markerState = false

    /**
     * Packs [frames] interleaved DSD frames (one MSB-first byte per channel per frame, see
     * DsdFrameReader) into little-endian 24-bit DoP samples. [frames] must be even; an odd
     * trailing frame is dropped.
     */
    fun packInterleaved(dsd: ByteArray, frames: Int, channels: Int): ByteArray {
        val pairs = frames / 2
        val out = ByteArray(pairs * channels * 3)
        var o = 0
        for (p in 0 until pairs) {
            val marker = if (markerState) DOP_MARKER_2 else DOP_MARKER_1
            markerState = !markerState
            val older = (2 * p) * channels
            val newer = older + channels
            for (c in 0 until channels) {
                out[o++] = dsd[newer + c]
                out[o++] = dsd[older + c]
                out[o++] = marker
            }
        }
        return out
    }

    /**
     * Legacy entry point for channel-planar input (all of channel 0's bytes, then channel 1's…),
     * kept for existing callers. Same corrected byte order as [packInterleaved].
     */
    fun packDsdToDoP24(dsdBytes: ByteArray, channels: Int = 2): ByteArray {
        val bytesPerChannel = dsdBytes.size / channels
        val samplesPerChannel = bytesPerChannel / 2
        val out = ByteArray(samplesPerChannel * channels * 3)
        var outIdx = 0
        for (s in 0 until samplesPerChannel) {
            val marker = if (markerState) DOP_MARKER_2 else DOP_MARKER_1
            markerState = !markerState
            for (ch in 0 until channels) {
                val off = ch * bytesPerChannel + s * 2
                out[outIdx++] = dsdBytes[off + 1] // newer
                out[outIdx++] = dsdBytes[off]     // older
                out[outIdx++] = marker
            }
        }
        return out
    }

    fun reset() {
        markerState = false
    }

    companion object {
        const val DOP_MARKER_1 = 0x05.toByte()
        const val DOP_MARKER_2 = 0xFA.toByte()
    }
}
