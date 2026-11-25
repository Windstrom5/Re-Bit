package com.windstrom5.rebit.domain.audiophile

import kotlin.math.roundToInt

/** Plain derived facts about a stream, shown in Audio Info. */
object AudioFacts {
    fun nominalBitrateKbps(rateHz: Int, bits: Int, channels: Int): Long = rateHz.toLong() * bits * channels / 1000

    /** Textbook limit of a linear PCM format: 6.02·N + 1.76 dB (16-bit ≈ 98 dB, 24-bit ≈ 146 dB). */
    fun theoreticalDynamicRangeDb(bits: Int): Int = (6.02 * bits + 1.76).roundToInt()

    fun nyquistKhz(rateHz: Int): Double = rateHz / 2000.0

    /** File size as a percentage of the same audio stored as raw PCM; null when it can't be known. */
    fun sizePercentOfPcm(fileBytes: Long, durationMs: Long, rateHz: Int, bits: Int, channels: Int): Int? {
        val raw = durationMs / 1000.0 * rateHz * channels * (bits / 8.0)
        return if (raw <= 0.0 || fileBytes <= 0L) null else (fileBytes * 100.0 / raw).roundToInt()
    }

    /**
     * How many bits actually carry data, from the OR of every sample seen so far. If the lowest N bits
     * never turn on, the real resolution is [containerBits] − N: a 16-bit master padded into a 24-bit
     * file reads as 16. Null until some audio has been seen.
     */
    fun effectiveBits(activeBitMask: Int, containerBits: Int): Int? {
        if (activeBitMask == 0) return null
        return (containerBits - Integer.numberOfTrailingZeros(activeBitMask)).coerceIn(1, containerBits)
    }

    /** Buffer duration for [bytes] of PCM in this format. */
    fun bufferLatencyMs(bytes: Int, rateHz: Int, bits: Int, channels: Int): Double {
        val frameBytes = channels * (bits / 8.0)
        return if (frameBytes <= 0.0 || rateHz <= 0) 0.0 else bytes / frameBytes / rateHz * 1000.0
    }

    fun channelLabel(channels: Int): String = when (channels) {
        1 -> "Mono"
        2 -> "Stereo"
        6 -> "5.1"
        8 -> "7.1"
        else -> "$channels channels"
    }
}
