package com.windstrom5.rebit.playback.dsp

import com.windstrom5.rebit.domain.model.DitherMode
import kotlin.math.floor

/**
 * Reduces float samples (full scale = ±1.0) to signed integers of [bits] width, optionally with
 * TPDF dither and error-feedback noise shaping.
 *
 *  - **Rounding** is symmetric (full scale = 2^(bits-1)), so a 16-bit source that went through
 *    no processing comes back out sample-for-sample identical. The previous packer scaled by
 *    32767 and truncated toward zero, which altered every single sample even with all DSP off.
 *  - **TPDF dither** (±1 LSB triangular) makes the quantization error statistically independent
 *    of the signal: fades and reverb tails decay into a smooth noise floor instead of gritty
 *    distortion.
 *  - **Noise shaping** feeds the quantization error back through a filter so the noise spectrum
 *    is tilted away from the ear's most sensitive region (2–5 kHz) into the top octave. Uses
 *    Lipshitz's 5-tap E-weighted filter at 44.1/48 kHz and a 2nd-order highpass shape at higher
 *    rates. Only applied for outputs of 16 bits or fewer — at 24 bits the floor is already below
 *    any DAC's analog noise.
 *
 * One instance per output stream: it keeps per-channel error history and RNG state, so it must
 * not be shared between threads.
 */
class DitherQuantizer(
    val bits: Int,
    private val channels: Int,
    val mode: DitherMode,
    sampleRateHz: Int,
    seed: Int = 0x2545F491
) {
    init {
        require(bits in 8..32) { "bits must be 8..32" }
        require(channels > 0) { "channels must be > 0" }
    }

    private val scale: Double = Math.scalb(1.0, bits - 1)
    private val maxValue: Double = scale - 1.0
    private val minValue: Double = -scale

    /** Dither is pointless for a 32-bit integer output (below float precision anyway). */
    private val ditherActive: Boolean = mode != DitherMode.OFF && bits <= 24

    private val shaping: DoubleArray =
        if (mode == DitherMode.NOISE_SHAPED && bits <= 16) shapingFilterFor(sampleRateHz) else DoubleArray(0)

    /** errors[ch][0] is the most recent error. */
    private val errors = Array(channels) { DoubleArray(shaping.size) }

    private var rng = if (seed == 0) 1 else seed

    val isNoiseShaped: Boolean get() = shaping.isNotEmpty()

    /**
     * Quantizes one sample of channel [ch].
     * @param dither false skips dither/shaping for this sample — used when the signal is
     *   already exactly representable (an unprocessed source at or below the output depth).
     */
    fun quantize(sample: Float, ch: Int, dither: Boolean = true): Long {
        var v = sample.toDouble() * scale
        val shape = dither && shaping.isNotEmpty()
        val e = errors[ch]
        if (shape) {
            var fb = 0.0
            for (i in shaping.indices) fb += shaping[i] * e[i]
            v -= fb
        }
        val d = if (dither && ditherActive) tpdf() else 0.0
        var q = floor(v + d + 0.5)
        if (q > maxValue) q = maxValue else if (q < minValue) q = minValue
        if (shape) {
            // Clamp the fed-back error: on a clipped sample the "error" is the overload itself,
            // and feeding that back would make the shaping filter ring or go unstable.
            val err = (q - v).coerceIn(-ERROR_LIMIT, ERROR_LIMIT)
            for (i in e.lastIndex downTo 1) e[i] = e[i - 1]
            e[0] = err
        }
        return q.toLong()
    }

    fun reset() {
        errors.forEach { it.fill(0.0) }
    }

    /** Triangular PDF in (-1, 1) LSB: the difference of two uniform variates. */
    private fun tpdf(): Double = uniform() - uniform()

    private fun uniform(): Double {
        var x = rng
        x = x xor (x shl 13)
        x = x xor (x ushr 17)
        x = x xor (x shl 5)
        rng = x
        return (x.toLong() and 0xFFFFFFFFL).toDouble() / 4294967296.0
    }

    companion object {
        private const val ERROR_LIMIT = 4.0

        /** Lipshitz/Wannamaker 5-tap "E-weighted" noise-shaping filter (designed for 44.1 kHz). */
        val LIPSHITZ_5 = doubleArrayOf(2.033, -2.165, 1.959, -1.590, 0.6149)

        /** (1 - z⁻¹)²: second-order highpass shape, safe at any rate. */
        val SECOND_ORDER = doubleArrayOf(2.0, -1.0)

        fun shapingFilterFor(sampleRateHz: Int): DoubleArray =
            if (sampleRateHz in 40_000..50_000) LIPSHITZ_5 else SECOND_ORDER
    }
}
