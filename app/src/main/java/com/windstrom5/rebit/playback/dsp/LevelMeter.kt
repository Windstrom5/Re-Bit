package com.windstrom5.rebit.playback.dsp

import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Per-channel peak / RMS level of the most recent audio block, for the live meter on the
 * Now Playing screen.
 *
 * Written from the audio thread ([measure]), read from the UI thread ([snapshot]). Values are
 * published as one immutable [Levels] object through a volatile field, so a reader always sees a
 * consistent pair of channels. Ballistics (fall-back, peak hold) belong to the UI; this only
 * reports what the last block measured.
 */
class LevelMeter {

    data class Levels(
        /** Linear peak per channel, 0..1 (can exceed 1 on a clipped float stream). */
        val peak: FloatArray,
        /** Linear RMS per channel. */
        val rms: FloatArray,
        /** System.nanoTime() when this block was measured. */
        val timestampNanos: Long,
        /** True when any sample of the block reached full scale. */
        val clipped: Boolean
    ) {
        val peakDb: FloatArray get() = FloatArray(peak.size) { toDb(peak[it]) }
        val rmsDb: FloatArray get() = FloatArray(rms.size) { toDb(rms[it]) }

        override fun equals(other: Any?): Boolean = other is Levels &&
            peak.contentEquals(other.peak) && rms.contentEquals(other.rms) &&
            timestampNanos == other.timestampNanos && clipped == other.clipped

        override fun hashCode(): Int =
            (peak.contentHashCode() * 31 + rms.contentHashCode()) * 31 + timestampNanos.hashCode()
    }

    @Volatile private var latest: Levels? = null

    /** Rate of the samples being measured (the engine's output rate), for the analyzer. */
    @Volatile var sampleRateHz: Int = 0

    // Last HISTORY frames of the first two channels, for the spectrum analyzer. Guarded by
    // [historyLock]: the audio thread holds it only for an arraycopy-sized moment per block.
    private val historyL = FloatArray(HISTORY)
    private val historyR = FloatArray(HISTORY)
    private var historyPos = 0
    private var historyCount = 0
    private val historyLock = Any()

    private fun pushHistory(l: Float, r: Float) {
        historyL[historyPos] = l
        historyR[historyPos] = r
        historyPos = (historyPos + 1) and (HISTORY - 1)
        if (historyCount < HISTORY) historyCount++
    }

    /**
     * Copies the most recent frames (oldest first) into [outL]/[outR] (each ≥ [HISTORY] long).
     * Returns how many frames were copied — 0 before any audio has been measured.
     */
    fun copyRecent(outL: FloatArray, outR: FloatArray): Int = synchronized(historyLock) {
        val n = historyCount
        val start = (historyPos - n + HISTORY) and (HISTORY - 1)
        for (i in 0 until n) {
            val idx = (start + i) and (HISTORY - 1)
            outL[i] = historyL[idx]
            outR[i] = historyR[idx]
        }
        n
    }

    /** Measures interleaved float samples (±1.0 full scale). */
    fun measure(samples: FloatArray, length: Int, channels: Int, nowNanos: Long = System.nanoTime()) {
        if (channels <= 0 || length < channels) return
        val peak = FloatArray(channels)
        val sumSq = DoubleArray(channels)
        val frames = length / channels
        var clipped = false
        for (f in 0 until frames) {
            val base = f * channels
            for (ch in 0 until channels) {
                val s = samples[base + ch]
                val a = abs(s)
                if (a > peak[ch]) peak[ch] = a
                if (a >= CLIP_LEVEL) clipped = true
                sumSq[ch] += (s * s).toDouble()
            }
        }
        latest = Levels(peak, FloatArray(channels) { sqrt(sumSq[it] / frames).toFloat() }, nowNanos, clipped)
        synchronized(historyLock) {
            val from = maxOf(0, frames - HISTORY)
            for (f in from until frames) {
                val l = samples[f * channels]
                pushHistory(l, if (channels > 1) samples[f * channels + 1] else l)
            }
        }
    }

    /** Measures little-endian integer PCM without converting the whole block to float first. */
    fun measurePcm(bytes: ByteArray, length: Int, bitDepth: Int, channels: Int, nowNanos: Long = System.nanoTime()) {
        if (channels <= 0 || bitDepth !in setOf(16, 24, 32)) return
        val bytesPerSample = bitDepth / 8
        val frames = length / (bytesPerSample * channels)
        if (frames == 0) return
        val scale = 1.0 / Math.scalb(1.0, bitDepth - 1)
        val peak = FloatArray(channels)
        val sumSq = DoubleArray(channels)
        var clipped = false
        // Every 4th frame is plenty for a meter and keeps this cheap on the bit-perfect path.
        val stride = if (frames > 2048) 4 else 1
        var counted = 0
        var f = 0
        while (f < frames) {
            for (ch in 0 until channels) {
                val o = (f * channels + ch) * bytesPerSample
                val v: Int = when (bytesPerSample) {
                    2 -> ((bytes[o + 1].toInt() shl 8) or (bytes[o].toInt() and 0xFF)).toShort().toInt()
                    3 -> (bytes[o].toInt() and 0xFF) or ((bytes[o + 1].toInt() and 0xFF) shl 8) or (bytes[o + 2].toInt() shl 16)
                    else -> (bytes[o].toInt() and 0xFF) or ((bytes[o + 1].toInt() and 0xFF) shl 8) or
                        ((bytes[o + 2].toInt() and 0xFF) shl 16) or (bytes[o + 3].toInt() shl 24)
                }
                val s = v * scale
                val a = abs(s).toFloat()
                if (a > peak[ch]) peak[ch] = a
                if (a >= CLIP_LEVEL) clipped = true
                sumSq[ch] += s * s
            }
            counted++
            f += stride
        }
        latest = Levels(peak, FloatArray(channels) { sqrt(sumSq[it] / counted).toFloat() }, nowNanos, clipped)
        // Spectrum history needs contiguous samples: decode the last HISTORY frames unstrided.
        synchronized(historyLock) {
            for (fr in maxOf(0, frames - HISTORY) until frames) {
                val base = fr * channels * bytesPerSample
                val l = (pcmAt(bytes, base, bytesPerSample) * scale).toFloat()
                val r = if (channels > 1) (pcmAt(bytes, base + bytesPerSample, bytesPerSample) * scale).toFloat() else l
                pushHistory(l, r)
            }
        }
    }

    private fun pcmAt(b: ByteArray, o: Int, bytesPerSample: Int): Int = when (bytesPerSample) {
        2 -> ((b[o + 1].toInt() shl 8) or (b[o].toInt() and 0xFF)).toShort().toInt()
        3 -> (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8) or (b[o + 2].toInt() shl 16)
        else -> (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8) or
            ((b[o + 2].toInt() and 0xFF) shl 16) or (b[o + 3].toInt() shl 24)
    }

    fun snapshot(): Levels? = latest

    fun reset() {
        latest = null
        synchronized(historyLock) { historyCount = 0; historyPos = 0 }
    }

    companion object {
        /** -0.01 dBFS: a sample this hot is a full-scale (likely clipped) sample. */
        const val CLIP_LEVEL = 0.9988f
        /** Frames of history kept for the analyzer (power of two). */
        const val HISTORY = 4096
        const val FLOOR_DB = -90f

        fun toDb(linear: Float): Float = if (linear <= 0f) FLOOR_DB else max(FLOOR_DB, (20.0 * log10(linear.toDouble())).toFloat())
    }
}
