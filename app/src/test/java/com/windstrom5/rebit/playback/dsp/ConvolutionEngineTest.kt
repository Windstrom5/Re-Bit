package com.windstrom5.rebit.playback.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

class ConvolutionEngineTest {

    private fun runChunked(c: ConvolutionEngine, input: FloatArray, chunkFrames: Int): FloatArray {
        val out = input.copyOf()
        var i = 0
        while (i < out.size) {
            val end = minOf(out.size, i + chunkFrames * 2)
            val chunk = out.copyOfRange(i, end)
            c.process(chunk)
            System.arraycopy(chunk, 0, out, i, chunk.size)
            i = end
        }
        return out
    }

    @Test
    fun `matches direct convolution, per channel, after normalisation`() {
        val rnd = Random(42)
        val irL = FloatArray(3000) { (rnd.nextFloat() - 0.5f) * Math.exp(-it / 600.0).toFloat() }
        val irR = FloatArray(2500) { (rnd.nextFloat() - 0.5f) * Math.exp(-it / 400.0).toFloat() }
        val frames = 20_000
        val input = FloatArray(frames * 2) { rnd.nextFloat() - 0.5f }
        val c = ConvolutionEngine(irL, irR, blockSize = 512)
        val out = runChunked(c, input, 777)
        val g = c.normalisationGain
        val lat = c.blockSize
        for (t in listOf(lat + 10, lat + 4000, frames - 1)) {
            for ((ch, ir) in listOf(0 to irL, 1 to irR)) {
                var y = 0.0
                val n = t - lat
                for (k in ir.indices) if (n - k >= 0) y += ir[k] * input[2 * (n - k) + ch]
                assertEquals("ch $ch at $t", (y * g).toFloat(), out[2 * t + ch], 2e-3f)
            }
        }
    }

    @Test
    fun `a unit impulse response passes audio through, delayed by one block`() {
        val c = ConvolutionEngine(floatArrayOf(1f), blockSize = 256)
        val frames = 4000
        val input = FloatArray(frames * 2) { (0.5 * sin(2 * PI * 1000 * (it / 2) / 48_000.0)).toFloat() }
        val out = runChunked(c, input, 100)
        for (t in 256 until frames) {
            assertEquals(input[2 * (t - 256)], out[2 * t], 1e-5f)
            assertEquals(input[2 * (t - 256) + 1], out[2 * t + 1], 1e-5f)
        }
    }

    @Test
    fun `normalisation never adds gain`() {
        // An IR that boosts: 4× gain impulse.
        val c = ConvolutionEngine(floatArrayOf(4f), blockSize = 256)
        val input = FloatArray(2000) { 0.25f }
        val out = runChunked(c, input, 64)
        assertTrue(out.all { abs(it) <= 0.2501f })
    }

    @Test
    fun `loudness compensation boosts bass at low volume and nothing at full`() {
        val (b0, t0) = LoudnessCompensation.boostsFor(0f, 1f)
        assertEquals(0f, b0, 0f); assertEquals(0f, t0, 0f)
        val (b30, t30) = LoudnessCompensation.boostsFor(30f, 1f)
        assertEquals(9.9f, b30, 0.01f); assertEquals(3f, t30, 0.01f)
        assertEquals(12f, LoudnessCompensation.boostsFor(60f, 1.5f).first, 0f) // capped
        assertEquals(0f, LoudnessCompensation.attenuationForVolume(15, 15), 0f)
        assertTrue(LoudnessCompensation.attenuationForVolume(5, 15) > 25f)

        val rate = 48_000
        fun gainDb(freq: Double): Double {
            val lc = LoudnessCompensation(rate, 1)
            lc.configure(30f, 1f)
            val x = FloatArray(rate) { (0.1 * sin(2 * PI * freq * it / rate)).toFloat() }
            lc.process(x)
            var e = 0.0
            for (i in rate / 2 until rate) e += x[i] * x[i]
            return 20 * log10(sqrt(e / (rate / 2)) / (0.1 / sqrt(2.0)))
        }
        assertTrue("bass lifted", gainDb(40.0) > 7.0)
        assertEquals("midrange untouched", 0.0, gainDb(1000.0), 1.0)
        assertTrue("treble lifted a little", gainDb(16_000.0) in 1.5..4.5)
    }
}
