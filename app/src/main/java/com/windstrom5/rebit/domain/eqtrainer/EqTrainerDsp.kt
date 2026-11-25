package com.windstrom5.rebit.domain.eqtrainer

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * One parametric "peaking" EQ band (RBJ cookbook), applied to interleaved stereo. State is kept
 * between calls, so it can run on consecutive audio chunks without clicks.
 */
class PeakingBiquad(
    val frequencyHz: Float,
    val gainDb: Float,
    val q: Float,
    val sampleRateHz: Int
) {
    private val b0: Double
    private val b1: Double
    private val b2: Double
    private val a1: Double
    private val a2: Double

    private var z1L = 0.0
    private var z2L = 0.0
    private var z1R = 0.0
    private var z2R = 0.0

    init {
        val a = 10.0.pow(gainDb / 40.0)
        val w0 = 2.0 * PI * frequencyHz / sampleRateHz
        val alpha = sin(w0) / (2.0 * q)
        val cosW = cos(w0)
        val a0 = 1.0 + alpha / a
        b0 = (1.0 + alpha * a) / a0
        b1 = (-2.0 * cosW) / a0
        b2 = (1.0 - alpha * a) / a0
        a1 = (-2.0 * cosW) / a0
        a2 = (1.0 - alpha / a) / a0
    }

    fun processStereo(interleaved: FloatArray) {
        var i = 0
        while (i + 1 < interleaved.size) {
            val xl = interleaved[i].toDouble()
            val yl = b0 * xl + z1L
            z1L = b1 * xl - a1 * yl + z2L
            z2L = b2 * xl - a2 * yl
            interleaved[i] = yl.toFloat()

            val xr = interleaved[i + 1].toDouble()
            val yr = b0 * xr + z1R
            z1R = b1 * xr - a1 * yr + z2R
            z2R = b2 * xr - a2 * yr
            interleaved[i + 1] = yr.toFloat()
            i += 2
        }
    }

    /** The band's frequency response at [hz], in dB. Used to draw the EQ curve. */
    fun magnitudeDb(hz: Double): Double {
        val w = 2.0 * PI * hz / sampleRateHz
        val cw = cos(w); val sw = sin(w)
        val c2 = cos(2 * w); val s2 = sin(2 * w)
        val numRe = b0 + b1 * cw + b2 * c2
        val numIm = -(b1 * sw + b2 * s2)
        val denRe = 1.0 + a1 * cw + a2 * c2
        val denIm = -(a1 * sw + a2 * s2)
        val mag = sqrt(numRe * numRe + numIm * numIm) / sqrt(denRe * denRe + denIm * denIm)
        return 20.0 * log10(mag.coerceAtLeast(1e-12))
    }
}

/** Pink (1/f) noise, Paul Kellet's filter, scaled to about -26 dBFS RMS so boosts keep headroom. */
class PinkNoise(seed: Int) {
    private val rnd = Random(seed)
    private var b0 = 0.0; private var b1 = 0.0; private var b2 = 0.0
    private var b3 = 0.0; private var b4 = 0.0; private var b5 = 0.0; private var b6 = 0.0

    fun next(): Float {
        val w = rnd.nextDouble(-1.0, 1.0)
        b0 = 0.99886 * b0 + w * 0.0555179
        b1 = 0.99332 * b1 + w * 0.0750759
        b2 = 0.96900 * b2 + w * 0.1538520
        b3 = 0.86650 * b3 + w * 0.3104856
        b4 = 0.55000 * b4 + w * 0.5329522
        b5 = -0.7616 * b5 - w * 0.0168980
        val pink = b0 + b1 + b2 + b3 + b4 + b5 + b6 + w * 0.5362
        b6 = w * 0.115926
        return (pink * SCALE).toFloat()
    }

    private companion object {
        // Measured: the raw filter output has RMS ~1.74; this brings it to ~0.05 (-26 dBFS).
        const val SCALE = 0.0288
    }
}
