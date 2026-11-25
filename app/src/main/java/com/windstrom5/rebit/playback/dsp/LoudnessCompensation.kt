package com.windstrom5.rebit.playback.dsp

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Equal-loudness ("loudness") compensation. The ear's sensitivity to bass — and to a lesser
 * degree the extreme treble — falls away faster than to the midrange as playback gets quieter
 * (ISO 226 equal-loudness contours), so music played softly sounds thin. This restores the
 * balance with a low shelf and a gentle high shelf whose boost grows with how far the volume is
 * below the reference (full) level, and is zero at the reference.
 *
 * The slopes approximate the difference between ISO 226 contours: about +0.33 dB of bass and
 * +0.1 dB of treble per dB of attenuation, capped at +12 / +4 dB.
 */
class LoudnessCompensation(private val sampleRateHz: Int, private val channels: Int) {

    private val low = Array(channels) { Biquad() }
    private val high = Array(channels) { Biquad() }
    private var configuredAttenuation = Float.NaN
    private var configuredStrength = Float.NaN

    var bassBoostDb = 0f
        private set
    var trebleBoostDb = 0f
        private set

    /** Sets the volume shortfall below the reference level, in dB (≥ 0), and strength 0..1.5. */
    fun configure(attenuationDb: Float, strength: Float) {
        if (attenuationDb == configuredAttenuation && strength == configuredStrength) return
        configuredAttenuation = attenuationDb
        configuredStrength = strength
        val (b, t) = boostsFor(attenuationDb, strength)
        bassBoostDb = b
        trebleBoostDb = t
        for (ch in 0 until channels) {
            low[ch].setShelf(sampleRateHz, BASS_HZ, b, isLow = true)
            high[ch].setShelf(sampleRateHz, TREBLE_HZ, t, isLow = false)
        }
    }

    val isActive: Boolean get() = bassBoostDb > 0.05f || trebleBoostDb > 0.05f

    /** Interleaved samples in place. */
    fun process(samples: FloatArray, length: Int = samples.size) {
        if (!isActive) return
        val frames = length / channels
        for (f in 0 until frames) {
            for (ch in 0 until channels) {
                val i = f * channels + ch
                samples[i] = high[ch].process(low[ch].process(samples[i]))
            }
        }
    }

    private class Biquad {
        private var b0 = 1.0; private var b1 = 0.0; private var b2 = 0.0; private var a1 = 0.0; private var a2 = 0.0
        private var x1 = 0.0; private var x2 = 0.0; private var y1 = 0.0; private var y2 = 0.0

        /** RBJ cookbook shelf, slope 1. */
        fun setShelf(fs: Int, f0: Double, gainDb: Float, isLow: Boolean) {
            val a = 10.0.pow(gainDb / 40.0)
            val w0 = 2 * PI * f0 / fs
            val cw = cos(w0)
            val alpha = sin(w0) / 2 * sqrt(2.0)
            val sqA2 = 2 * sqrt(a) * alpha
            val n0: Double; val n1: Double; val n2: Double; val d0: Double; val d1: Double; val d2: Double
            if (isLow) {
                n0 = a * ((a + 1) - (a - 1) * cw + sqA2); n1 = 2 * a * ((a - 1) - (a + 1) * cw); n2 = a * ((a + 1) - (a - 1) * cw - sqA2)
                d0 = (a + 1) + (a - 1) * cw + sqA2; d1 = -2 * ((a - 1) + (a + 1) * cw); d2 = (a + 1) + (a - 1) * cw - sqA2
            } else {
                n0 = a * ((a + 1) + (a - 1) * cw + sqA2); n1 = -2 * a * ((a - 1) + (a + 1) * cw); n2 = a * ((a + 1) + (a - 1) * cw - sqA2)
                d0 = (a + 1) - (a - 1) * cw + sqA2; d1 = 2 * ((a - 1) - (a + 1) * cw); d2 = (a + 1) - (a - 1) * cw - sqA2
            }
            b0 = n0 / d0; b1 = n1 / d0; b2 = n2 / d0; a1 = d1 / d0; a2 = d2 / d0
        }

        fun process(x: Float): Float {
            val y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
            x2 = x1; x1 = x.toDouble(); y2 = y1; y1 = y
            return y.toFloat()
        }
    }

    companion object {
        const val BASS_HZ = 110.0
        const val TREBLE_HZ = 9_000.0

        fun boostsFor(attenuationDb: Float, strength: Float): Pair<Float, Float> {
            val att = attenuationDb.coerceIn(0f, 60f) * strength.coerceIn(0f, 1.5f)
            return (att * 0.33f).coerceAtMost(12f) to (att * 0.10f).coerceAtMost(4f)
        }

        /**
         * Rough playback attenuation (dB below full) for Android's media volume index: the
         * default media curve spans about 60 dB over the steps, close to logarithmic.
         */
        fun attenuationForVolume(index: Int, max: Int): Float {
            if (max <= 0 || index >= max) return 0f
            if (index <= 0) return 60f
            return ((1f - index.toFloat() / max) * 45f).coerceIn(0f, 60f)
        }
    }
}
