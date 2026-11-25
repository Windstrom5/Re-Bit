package com.windstrom5.rebit.playback.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class LevelMeterTest {

    @Test
    fun `full scale sine reads 0 dB peak and -3 dB RMS`() {
        val m = LevelMeter()
        val x = FloatArray(48_000 * 2) { i -> sin(2 * PI * 1000 * (i / 2) / 48_000.0).toFloat() * if (i % 2 == 0) 1f else 0.5f }
        m.measure(x, x.size, 2, nowNanos = 1L)
        val l = m.snapshot()!!
        assertEquals(1f, l.peak[0], 0.001f)
        assertEquals(0.5f, l.peak[1], 0.001f)
        assertEquals(-3.01f, l.rmsDb[0], 0.05f)
        assertEquals(-9.03f, l.rmsDb[1], 0.05f)
        assertTrue(l.clipped)
    }

    @Test
    fun `silence reads the floor and is not clipped`() {
        val m = LevelMeter()
        m.measure(FloatArray(512), 512, 2)
        val l = m.snapshot()!!
        assertEquals(LevelMeter.FLOOR_DB, l.peakDb[0], 0f)
        assertFalse(l.clipped)
    }

    @Test
    fun `integer PCM is measured without conversion`() {
        val m = LevelMeter()
        // 16-bit stereo: left = +16384 (-6 dB), right = -32768 (full scale)
        val frames = 100
        val pcm = ByteArray(frames * 4)
        for (f in 0 until frames) {
            pcm[f * 4] = 0x00; pcm[f * 4 + 1] = 0x40
            pcm[f * 4 + 2] = 0x00; pcm[f * 4 + 3] = 0x80.toByte()
        }
        m.measurePcm(pcm, pcm.size, 16, 2)
        val l = m.snapshot()!!
        assertEquals(-6.02f, l.peakDb[0], 0.05f)
        assertEquals(0f, l.peakDb[1], 0.01f)
    }

    @Test
    fun `reset clears the reading`() {
        val m = LevelMeter()
        m.measure(FloatArray(4) { 0.5f }, 4, 2)
        m.reset()
        assertNull(m.snapshot())
    }
}
