package com.windstrom5.rebit.playback.dsp

import com.windstrom5.rebit.domain.model.ReverbPreset
import kotlin.math.pow

/**
 * Studio Acoustic Reverb Environment Simulator.
 *
 * A stereo Schroeder/Moorer reverb in the Freeverb arrangement: per channel, four parallel
 * low-pass-damped feedback comb filters into two series allpass diffusers. The right channel's
 * delay lines are offset by a small "stereo spread" so the two sides decorrelate and the tail
 * sounds wide instead of mono-in-the-middle.
 *
 * Delay lengths are specified at 44.1 kHz and scaled to the actual [sampleRateHz], so a room
 * keeps its size at 48, 96 or 192 kHz (the previous version used fixed sample counts, so the
 * same preset shrank to a quarter of its size at 176.4 kHz). The comb feedback is derived from
 * the preset's RT60 decay time, so "Cathedral" genuinely rings longer than "Studio Room".
 */
class StudioReverbProcessor(
    var enabled: Boolean = false,
    var preset: ReverbPreset = ReverbPreset.STUDIO_ROOM,
    var wetDry: Float = 0.25f, // 0.0 = 100% Dry, 1.0 = 100% Wet
    private val sampleRateHz: Int = 44_100
) {
    private val rateScale = sampleRateHz / 44_100.0

    private val combL = COMB_TUNING.map { Comb(scaled(it)) }
    private val combR = COMB_TUNING.map { Comb(scaled(it + STEREO_SPREAD)) }
    private val allpassL = ALLPASS_TUNING.map { Allpass(scaled(it)) }
    private val allpassR = ALLPASS_TUNING.map { Allpass(scaled(it + STEREO_SPREAD)) }

    private var configuredPreset: ReverbPreset? = null

    private fun scaled(samplesAt44k: Int) = (samplesAt44k * rateScale).toInt().coerceAtLeast(1)

    /** Processes interleaved stereo float samples in place. */
    fun process(samples: FloatArray, frameCount: Int) {
        if (!enabled || wetDry <= 0.001f) return
        if (configuredPreset != preset) configure(preset)

        val wet = wetDry.coerceIn(0f, 1f)
        val dry = 1f - wet * 0.5f
        val wetGain = wet * WET_SCALE

        for (i in 0 until frameCount) {
            val l = i * 2
            val r = l + 1
            if (r >= samples.size) break
            val input = (samples[l] + samples[r]) * INPUT_GAIN

            var outL = 0f
            var outR = 0f
            for (c in combL) outL += c.process(input)
            for (c in combR) outR += c.process(input)
            for (a in allpassL) outL = a.process(outL)
            for (a in allpassR) outR = a.process(outR)

            samples[l] = samples[l] * dry + outL * wetGain
            samples[r] = samples[r] * dry + outR * wetGain
        }
    }

    private fun configure(p: ReverbPreset) {
        configuredPreset = p
        // RT60: the time for the tail to fall 60 dB. A comb of delay D samples needs feedback
        // g = 10^(-3·D / (RT60·fs)) to decay that fast.
        val rt60 = p.decaySec.coerceIn(0.2f, 6f).toDouble()
        val damping = (0.15 + 0.5 * (1.0 - p.roomSize)).toFloat().coerceIn(0.05f, 0.7f)
        for (c in combL + combR) {
            c.feedback = 10.0.pow(-3.0 * c.length / (rt60 * sampleRateHz)).toFloat().coerceIn(0f, 0.98f)
            c.damping = damping
        }
    }

    private class Comb(size: Int) {
        private val buf = FloatArray(size)
        private var idx = 0
        private var filterStore = 0f
        val length = size
        var feedback = 0.8f
        var damping = 0.3f

        fun process(input: Float): Float {
            val out = buf[idx]
            filterStore = out * (1f - damping) + filterStore * damping
            buf[idx] = input + filterStore * feedback
            if (++idx >= buf.size) idx = 0
            return out
        }
    }

    private class Allpass(size: Int) {
        private val buf = FloatArray(size)
        private var idx = 0

        fun process(input: Float): Float {
            val delayed = buf[idx]
            val out = -input + delayed
            buf[idx] = input + delayed * 0.5f
            if (++idx >= buf.size) idx = 0
            return out
        }
    }

    private companion object {
        // Freeverb's tunings (samples at 44.1 kHz), first four combs.
        val COMB_TUNING = intArrayOf(1116, 1188, 1277, 1356)
        val ALLPASS_TUNING = intArrayOf(556, 441)
        const val STEREO_SPREAD = 23
        /** Freeverb uses 0.015 into 8 combs; half the combs here, so twice the input. */
        const val INPUT_GAIN = 0.03f
        const val WET_SCALE = 3f
    }
}
