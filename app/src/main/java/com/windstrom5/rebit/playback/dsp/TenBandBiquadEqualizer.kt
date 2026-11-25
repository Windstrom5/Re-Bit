package com.windstrom5.rebit.playback.dsp

import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.pow
import kotlin.math.max

/**
 * 10-Band Studio-Grade Biquad IIR Equalizer Filter Bank.
 *
 * Implements Robert Bristow-Johnson (RBJ) Audio EQ Cookbook peaking and shelving filters
 * with Direct Form II Transposed processing for stereo audio streams.
 *
 * Covers 10 standard ISO octave center frequencies:
 * 31.25 Hz, 62.5 Hz, 125 Hz, 250 Hz, 500 Hz, 1 kHz, 2 kHz, 4 kHz, 8 kHz, 16 kHz.
 *
 * Delivers genuine per-band frequency response shaping for IEM / Headphone target curve
 * calibrations (AutoEQ Harman Target, Diffuse Field, custom user curves) rather than
 * scalar amplitude scaling.
 */
class TenBandBiquadEqualizer(
    private val sampleRateHz: Int = 44100
) {
    companion object {
        val FREQUENCIES = listOf(31.25f, 62.5f, 125f, 250f, 500f, 1000f, 2000f, 4000f, 8000f, 16000f)
        // Q-factor optimized for 1-octave bandwidth across 10 bands
        private const val DEFAULT_Q = 1.4142f
        const val MAX_PARAMETRIC_FILTERS = 16
    }

    private class BiquadSection {
        var b0 = 1.0f
        var b1 = 0.0f
        var b2 = 0.0f
        var a1 = 0.0f
        var a2 = 0.0f

        // Direct Form II Transposed delay states (Stereo: Ch 0 and Ch 1)
        var s1L = 0.0f
        var s2L = 0.0f
        var s1R = 0.0f
        var s2R = 0.0f

        fun reset() {
            s1L = 0.0f
            s2L = 0.0f
            s1R = 0.0f
            s2R = 0.0f
        }

        inline fun processSampleL(x: Float): Float {
            val y = b0 * x + s1L
            s1L = b1 * x - a1 * y + s2L
            s2L = b2 * x - a2 * y
            return y
        }

        inline fun processSampleR(x: Float): Float {
            val y = b0 * x + s1R
            s1R = b1 * x - a1 * y + s2R
            s2R = b2 * x - a2 * y
            return y
        }
    }

    /**
     * Double-precision biquad for the parametric path. Narrow low-frequency filters (e.g. 54 Hz,
     * Q 5) at 96/192 kHz sit so close to z = 1 that single-precision state is audibly inaccurate.
     */
    private class DoubleBiquad {
        var b0 = 1.0; var b1 = 0.0; var b2 = 0.0; var a1 = 0.0; var a2 = 0.0
        var s1L = 0.0; var s2L = 0.0; var s1R = 0.0; var s2R = 0.0
        fun reset() { s1L = 0.0; s2L = 0.0; s1R = 0.0; s2R = 0.0 }
        fun processL(x: Double): Double { val y = b0 * x + s1L; s1L = b1 * x - a1 * y + s2L; s2L = b2 * x - a2 * y; return y }
        fun processR(x: Double): Double { val y = b0 * x + s1R; s1R = b1 * x - a1 * y + s2R; s2R = b2 * x - a2 * y; return y }
    }

    private val peq = Array(MAX_PARAMETRIC_FILTERS) { DoubleBiquad() }
    private var peqCount = 0
    private var peqPreamp = 1.0
    private var lastPeqFilters: List<com.windstrom5.rebit.domain.model.EqFilter>? = null
    private var lastPeqPreampDb = Float.NaN

    private val filters = Array(10) { BiquadSection() }
    private var currentGains = FloatArray(10)
    private var isBypassed = true
    private var preampGain = 1.0f

    init {
        updateCoefficients(FloatArray(10) { 0f })
    }

    /**
     * Called on every audio buffer with the current settings. Parametric filters (an AutoEq IEM
     * preset) take over when present; otherwise the 10 graphic bands apply as before.
     * Coefficients are only recomputed when the filter list actually changes (same list instance).
     */
    fun applySettings(
        gains: List<Float>,
        parametric: List<com.windstrom5.rebit.domain.model.EqFilter>,
        parametricPreampDb: Float
    ) {
        if (parametric.isEmpty()) {
            if (peqCount != 0) { peqCount = 0; lastPeqFilters = null }
            setGains(gains)
            return
        }
        if (parametric === lastPeqFilters && parametricPreampDb == lastPeqPreampDb) return
        val used = parametric.take(MAX_PARAMETRIC_FILTERS)
        val fs = sampleRateHz.toDouble()
        for (i in used.indices) {
            val c = com.windstrom5.rebit.playback.autoeq.EqMath.coefficients(used[i], fs)
            val s = peq[i]
            s.b0 = c[0]; s.b1 = c[1]; s.b2 = c[2]; s.a1 = c[3]; s.a2 = c[4]
        }
        peqPreamp = 10.0.pow(parametricPreampDb.coerceAtMost(0f).toDouble() / 20.0)
        peqCount = used.size
        lastPeqFilters = parametric
        lastPeqPreampDb = parametricPreampDb
    }

    private fun processParametric(samples: FloatArray) {
        val n = peqCount
        val pre = peqPreamp
        val frames = samples.size / 2
        for (i in 0 until frames) {
            var l = samples[2 * i] * pre
            var r = samples[2 * i + 1] * pre
            for (b in 0 until n) {
                val f = peq[b]
                l = f.processL(l)
                r = f.processR(r)
            }
            samples[2 * i] = l.toFloat()
            samples[2 * i + 1] = r.toFloat()
        }
    }

    fun setGains(gains: List<Float>) {
        val newGains = FloatArray(10) { i -> gains.getOrElse(i) { 0f }.coerceIn(-12f, 12f) }
        updateCoefficients(newGains)
    }

    fun reset() {
        for (f in filters) {
            f.reset()
        }
        for (f in peq) f.reset()
    }

    private fun updateCoefficients(newGains: FloatArray) {
        currentGains = newGains
        isBypassed = newGains.all { it == 0f }

        // Preamp headroom calculation to prevent digital clipping when boosting bands
        val maxBoostDb = newGains.maxOrNull() ?: 0f
        preampGain = if (maxBoostDb > 0f) {
            10.0f.pow(-maxBoostDb * 0.45f / 20.0f) // Dynamic soft headroom compensation
        } else {
            1.0f
        }

        val fs = sampleRateHz.toFloat()

        for (i in 0 until 10) {
            val gainDb = newGains[i]
            val f0 = FREQUENCIES[i].coerceAtMost(fs * 0.48f)
            val sec = filters[i]

            if (gainDb == 0f) {
                sec.b0 = 1.0f
                sec.b1 = 0.0f
                sec.b2 = 0.0f
                sec.a1 = 0.0f
                sec.a2 = 0.0f
                continue
            }

            val a = 10.0f.pow(gainDb / 40.0f)
            val w0 = (2.0 * Math.PI * f0 / fs).toFloat()
            val cosW0 = cos(w0)
            val sinW0 = sin(w0)
            val alpha = sinW0 / (2.0f * DEFAULT_Q)

            if (i == 0) {
                // Low Shelf for 31.25Hz sub-bass
                val sqrtA = sqrt(a)
                val aPlus1 = a + 1.0f
                val aMinus1 = a - 1.0f
                val twoSqrtAAlpha = 2.0f * sqrtA * alpha

                val a0 = aPlus1 + aMinus1 * cosW0 + twoSqrtAAlpha
                sec.b0 = (a * (aPlus1 - aMinus1 * cosW0 + twoSqrtAAlpha)) / a0
                sec.b1 = (2.0f * a * (aMinus1 - aPlus1 * cosW0)) / a0
                sec.b2 = (a * (aPlus1 - aMinus1 * cosW0 - twoSqrtAAlpha)) / a0
                sec.a1 = (-2.0f * (aMinus1 + aPlus1 * cosW0)) / a0
                sec.a2 = (aPlus1 + aMinus1 * cosW0 - twoSqrtAAlpha) / a0
            } else if (i == 9) {
                // High Shelf for 16kHz brilliance
                val sqrtA = sqrt(a)
                val aPlus1 = a + 1.0f
                val aMinus1 = a - 1.0f
                val twoSqrtAAlpha = 2.0f * sqrtA * alpha

                val a0 = aPlus1 - aMinus1 * cosW0 + twoSqrtAAlpha
                sec.b0 = (a * (aPlus1 + aMinus1 * cosW0 + twoSqrtAAlpha)) / a0
                sec.b1 = (-2.0f * a * (aMinus1 + aPlus1 * cosW0)) / a0
                sec.b2 = (a * (aPlus1 + aMinus1 * cosW0 - twoSqrtAAlpha)) / a0
                sec.a1 = (2.0f * (aMinus1 - aPlus1 * cosW0)) / a0
                sec.a2 = (aPlus1 - aMinus1 * cosW0 - twoSqrtAAlpha) / a0
            } else {
                // Peaking EQ for parametric bands
                val a0 = 1.0f + alpha / a
                sec.b0 = (1.0f + alpha * a) / a0
                sec.b1 = (-2.0f * cosW0) / a0
                sec.b2 = (1.0f - alpha * a) / a0
                sec.a1 = (-2.0f * cosW0) / a0
                sec.a2 = (1.0f - alpha / a) / a0
            }
        }
    }

    /**
     * In-place stereo float sample processing [-1.0f, 1.0f]
     */
    fun processStereo(samples: FloatArray) {
        if (peqCount > 0) { processParametric(samples); return }
        if (isBypassed && preampGain == 1.0f) return

        val count = samples.size / 2
        for (i in 0 until count) {
            val idxL = i * 2
            val idxR = idxL + 1

            var sL = samples[idxL] * preampGain
            var sR = samples[idxR] * preampGain

            for (b in 0 until 10) {
                val f = filters[b]
                sL = f.processSampleL(sL)
                sR = f.processSampleR(sR)
            }

            samples[idxL] = sL
            samples[idxR] = sR
        }
    }
}
