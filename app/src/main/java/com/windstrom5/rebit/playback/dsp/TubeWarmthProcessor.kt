package com.windstrom5.rebit.playback.dsp

import kotlin.math.abs
import kotlin.math.tanh

/**
 * Vacuum Tube Warmth & Analog Harmonic Exciter (12AX7 / Single-Ended Triode Simulation).
 *
 * Implements an asymmetric polynomial transfer curve with soft triode saturation:
 * - Introduces subtle even-order (2nd harmonic) warmth and odd-order (3rd harmonic) depth.
 * - Softly compresses high-amplitude transient peaks without harsh digital clipping.
 * - Adds analog richness to digital recordings, especially paired with DSD remastering.
 */
class TubeWarmthProcessor(
    private var drive: Float = 0.35f
) {
    fun setDrive(newDrive: Float) {
        this.drive = newDrive.coerceIn(0.0f, 1.0f)
    }

    /**
     * Processes in-place interleaved float PCM samples [-1.0f, 1.0f].
     */
    fun process(samples: FloatArray) {
        if (drive <= 0.001f) return

        val k2 = 0.22f * drive // 2nd Harmonic (Octave Warmth)
        val k3 = 0.08f * drive // 3rd Harmonic (Presence & Body)
        val gainComp = 1.0f / (1.0f + 0.15f * drive)

        for (i in samples.indices) {
            val x = samples[i]

            // Asymmetrical Triode Non-Linearity: f(x) = x + k2 * x^2 + k3 * x^3
            val x2 = x * x
            val x3 = x2 * x
            var y = x + (k2 * x2) - (k3 * x3)

            // Soft saturation curve (Tanh soft knee for analog head-room)
            if (abs(y) > 0.7f) {
                val sign = if (y >= 0f) 1f else -1f
                val excess = abs(y) - 0.7f
                y = (0.7f + 0.3f * tanh(excess / 0.3f)) * sign
            }

            samples[i] = (y * gainComp).coerceIn(-1.0f, 1.0f)
        }
    }
}
