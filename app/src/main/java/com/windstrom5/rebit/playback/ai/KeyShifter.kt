package com.windstrom5.rebit.playback.ai

import kotlin.math.abs
import kotlin.math.pow

/**
 * Real-time key shifter for karaoke (±12 semitones, tempo unchanged): two crossfaded read
 * taps sweep through a short delay line at the pitch ratio, each fading out before it jumps.
 *
 * Fixes over the previous inline version:
 *  - **Direction.** The delay must *shrink* to raise pitch (the read tap then runs faster than
 *    the write tap). The old code grew it, so "+2 semitones" played about 2 semitones lower.
 *  - **Fractional reads.** Taps are linearly interpolated between samples instead of truncated
 *    to whole samples, removing the zipper/crackle on sustained notes.
 *  - **Grain length in time, not samples** (~40 ms), so 96 and 192 kHz sound the same as 44.1.
 *
 * Stateful and single-threaded; one instance per stream.
 */
class KeyShifter(val sampleRateHz: Int) {

    private val grain: Int = (sampleRateHz * GRAIN_SECONDS).toInt().coerceAtLeast(64)
    private val size: Int = Integer.highestOneBit(grain * 2 + 8) shl 1
    private val mask = size - 1
    private val bufL = FloatArray(size)
    private val bufR = FloatArray(size)
    private var writePos = 0
    private var phase = 0.0

    /** Shifts interleaved stereo [samples] in place by [semitones]. 0 passes audio through. */
    fun process(samples: FloatArray, semitones: Int, length: Int = samples.size) {
        if (semitones == 0) return
        val ratio = 2.0.pow(semitones.coerceIn(-12, 12) / 12.0)
        // d(delay)/dt = 1 − ratio  →  read speed = ratio
        val phaseStep = (1.0 - ratio) / grain
        var i = 0
        while (i + 1 < length) {
            bufL[writePos] = samples[i]
            bufR[writePos] = samples[i + 1]

            phase += phaseStep
            if (phase >= 1.0) phase -= 1.0 else if (phase < 0.0) phase += 1.0
            val p2 = if (phase + 0.5 >= 1.0) phase - 0.5 else phase + 0.5

            val g1 = (1.0 - abs(2.0 * phase - 1.0)).toFloat()
            val g2 = (1.0 - abs(2.0 * p2 - 1.0)).toFloat()
            val d1 = phase * grain + 1.0
            val d2 = p2 * grain + 1.0

            samples[i] = tap(bufL, d1) * g1 + tap(bufL, d2) * g2
            samples[i + 1] = tap(bufR, d1) * g1 + tap(bufR, d2) * g2

            writePos = (writePos + 1) and mask
            i += 2
        }
    }

    fun reset() {
        bufL.fill(0f); bufR.fill(0f)
        writePos = 0
        phase = 0.0
    }

    /** Linear-interpolated read [delay] samples behind the write head. */
    private fun tap(buf: FloatArray, delay: Double): Float {
        val pos = writePos - delay
        val i0 = kotlin.math.floor(pos).toInt()
        val frac = (pos - i0).toFloat()
        val a = buf[i0 and mask]
        val b = buf[(i0 + 1) and mask]
        return a + (b - a) * frac
    }

    private companion object {
        const val GRAIN_SECONDS = 0.040
    }
}
