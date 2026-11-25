package com.windstrom5.rebit.playback.dsp

import com.windstrom5.rebit.domain.model.ReverbPreset
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class StudioReverbProcessorTest {

    private fun tailEnergy(preset: ReverbPreset, rate: Int, fromSec: Double): Double {
        val r = StudioReverbProcessor(enabled = true, preset = preset, wetDry = 1f, sampleRateHz = rate)
        val frames = (rate * 3.0).toInt()
        val x = FloatArray(frames * 2)
        x[0] = 1f; x[1] = 1f
        r.process(x, frames)
        var e = 0.0
        for (f in (rate * fromSec).toInt() until frames) e += (x[f * 2] * x[f * 2]).toDouble()
        x.forEach { assertTrue("unstable output $it", abs(it) < 2f) }
        return e
    }

    @Test
    fun `longer presets ring longer`() {
        val room = tailEnergy(ReverbPreset.STUDIO_ROOM, 48_000, 1.0)
        val cathedral = tailEnergy(ReverbPreset.CATHEDRAL, 48_000, 1.0)
        assertTrue("cathedral tail $cathedral should exceed room tail $room", cathedral > room * 10)
    }

    @Test
    fun `decay time is independent of sample rate`() {
        // How fast the tail falls (energy in 1.0–3.0 s relative to 0.5–3.0 s) must not depend on
        // the rate — with fixed sample-count delays the room shrank as the rate rose.
        fun decayShape(rate: Int) = tailEnergy(ReverbPreset.CONCERT_HALL, rate, 1.0) /
            tailEnergy(ReverbPreset.CONCERT_HALL, rate, 0.5)
        val at44 = decayShape(44_100)
        val at192 = decayShape(192_000)
        assertTrue("decay shape differs across rates: $at44 vs $at192", abs(at44 - at192) < 0.08)
    }

    @Test
    fun `left and right tails decorrelate`() {
        val r = StudioReverbProcessor(enabled = true, preset = ReverbPreset.CONCERT_HALL, wetDry = 1f, sampleRateHz = 48_000)
        val frames = 48_000
        val x = FloatArray(frames * 2)
        x[0] = 1f; x[1] = 1f
        r.process(x, frames)
        var diff = 0.0
        for (f in 5000 until frames) diff += abs(x[f * 2] - x[f * 2 + 1])
        assertTrue(diff > 0.1)
    }
}
