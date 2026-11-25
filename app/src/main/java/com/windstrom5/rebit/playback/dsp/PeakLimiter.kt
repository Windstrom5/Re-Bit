package com.windstrom5.rebit.playback.dsp

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Transparent look-ahead brickwall limiter — the safety net at the end of the DSP chain.
 *
 * EQ boosts, positive ReplayGain, crossfeed sums and reverb tails can all push a hot master past
 * 0 dBFS. Clamping each sample (what every stage used to do on its own) squares off those peaks
 * into harsh odd-harmonic distortion. This limiter instead lowers the gain *smoothly, just before*
 * the peak arrives, so nothing ever exceeds [ceiling] and nothing is clipped.
 *
 * Algorithm (sample-exact, no overshoot):
 *  1. target[n] = min(1, ceiling / max|x[n]| across channels)
 *  2. m[n] = minimum of target over the last L samples (L = look-ahead)
 *  3. r[n] = m[n] when falling, otherwise rises toward it with the release time constant
 *  4. g[n] = moving average of r over L samples, applied to the input delayed by L − 1
 * Every r inside the averaging window that ends L − 1 samples after a peak is ≤ that peak's
 * target, so the averaged gain is too: the ceiling holds exactly, and the gain curve is a smooth
 * ramp rather than a step.
 *
 * Adds L − 1 samples of latency (≈1.5 ms). When no sample comes near the ceiling, the gain stays
 * at exactly 1.0 and the output is the input, delayed.
 */
class PeakLimiter(
    private val channels: Int,
    sampleRateHz: Int,
    lookAheadMs: Double = 1.5,
    releaseMs: Double = 60.0,
    val ceiling: Float = DEFAULT_CEILING
) {
    private val lookAhead: Int = max(1, (sampleRateHz * lookAheadMs / 1000.0).roundToInt())
    private val delay = lookAhead - 1
    private val releaseCoef: Double = 1.0 - exp(-1.0 / (sampleRateHz * releaseMs / 1000.0))

    // Ring of recent per-frame targets for the sliding minimum.
    private val targets = DoubleArray(lookAhead) { 1.0 }
    // Ring of recent released gains for the moving average.
    private val released = DoubleArray(lookAhead) { 1.0 }
    private var releasedSum = lookAhead.toDouble()
    private var lastReleased = 1.0
    // Delay line for the audio itself.
    private val delayLine = FloatArray((delay + 1) * channels)
    private var ringPos = 0
    private var delayPos = 0

    /** Smallest gain applied since the last [takeMaxReductionDb] call (1.0 = no reduction). */
    @Volatile private var minGainSinceRead = 1.0

    /** Total number of frames whose gain was reduced, since construction. */
    @Volatile var limitedFrames: Long = 0L
        private set

    val latencyFrames: Int get() = delay

    /** Processes interleaved [samples] in place (output is delayed by [latencyFrames]). */
    fun process(samples: FloatArray, length: Int = samples.size) {
        val frames = length / channels
        var minGain = minGainSinceRead
        var limited = 0L
        for (f in 0 until frames) {
            val base = f * channels
            var peak = 0f
            for (ch in 0 until channels) peak = max(peak, abs(samples[base + ch]))
            val target = if (peak > ceiling) ceiling.toDouble() / peak else 1.0

            targets[ringPos] = target
            var m = 1.0
            for (t in targets) if (t < m) m = t

            val r = if (m < lastReleased) m else lastReleased + (m - lastReleased) * releaseCoef
            lastReleased = r
            releasedSum += r - released[ringPos]
            released[ringPos] = r
            ringPos = (ringPos + 1) % lookAhead

            val gain = (releasedSum / lookAhead).coerceAtMost(1.0)
            if (gain < 0.99999) limited++
            if (gain < minGain) minGain = gain

            // Swap the incoming frame with the one leaving the delay line.
            val d = delayPos * channels
            for (ch in 0 until channels) {
                val incoming = samples[base + ch]
                val outgoing = if (delay == 0) incoming else delayLine[d + ch]
                if (delay > 0) delayLine[d + ch] = incoming
                var y = (outgoing * gain).toFloat()
                // Float rounding of the averaged gain can leave a peak a hair above the ceiling.
                if (y > ceiling) y = ceiling else if (y < -ceiling) y = -ceiling
                samples[base + ch] = y
            }
            if (delay > 0) delayPos = (delayPos + 1) % delay
        }
        minGainSinceRead = minGain
        limitedFrames += limited
    }

    /** Peak gain reduction (dB, ≥ 0) since the previous call — for a "limiting" indicator. */
    fun takeMaxReductionDb(): Float {
        val g = minGainSinceRead
        minGainSinceRead = 1.0
        return if (g >= 1.0) 0f else (-20.0 * log10(g)).toFloat()
    }

    companion object {
        /** −0.1 dBFS: leaves room for the DAC's own reconstruction-filter overshoot. */
        const val DEFAULT_CEILING = 0.9886f
    }
}
