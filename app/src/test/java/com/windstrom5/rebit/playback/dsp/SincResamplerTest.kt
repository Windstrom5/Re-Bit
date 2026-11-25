package com.windstrom5.rebit.playback.dsp

import com.windstrom5.rebit.domain.model.ResamplerQuality
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

class SincResamplerTest {

    private fun sine(freq: Double, rate: Int, frames: Int, channels: Int = 1, amp: Double = 0.5) =
        FloatArray(frames * channels) { i ->
            val frame = i / channels
            (amp * sin(2 * PI * freq * frame / rate)).toFloat()
        }

    /** RMS of channel [ch], skipping [skip] frames at each end (filter warm-up / tail). */
    private fun rms(x: FloatArray, channels: Int = 1, ch: Int = 0, skip: Int = 0): Double {
        val frames = x.size / channels
        var sum = 0.0
        var n = 0
        for (f in skip until frames - skip) {
            val v = x[f * channels + ch].toDouble()
            sum += v * v
            n++
        }
        return sqrt(sum / n)
    }

    private fun runChunked(r: SincResampler, input: FloatArray, channels: Int, chunkFrames: Int): FloatArray {
        val out = ArrayList<Float>()
        var i = 0
        while (i < input.size) {
            val end = minOf(input.size, i + chunkFrames * channels)
            r.process(input.copyOfRange(i, end)).forEach { out += it }
            i = end
        }
        r.flush().forEach { out += it }
        return out.toFloatArray()
    }

    @Test
    fun `same rate is a passthrough`() {
        val r = SincResampler(2, 48_000, 48_000)
        val input = sine(1000.0, 48_000, 256, channels = 2)
        assertSame(input, r.process(input))
    }

    @Test
    fun `output length tracks the rate ratio over many chunks`() {
        val r = SincResampler(2, 44_100, 48_000)
        val frames = 44_100
        val out = runChunked(r, sine(440.0, 44_100, frames, channels = 2), 2, chunkFrames = 1000)
        val outFrames = out.size / 2
        assertEquals(48_000.0, outFrames.toDouble(), 3.0)
    }

    @Test
    fun `passband tone keeps its level when upsampling 44_1 to 48 kHz`() {
        val r = SincResampler(1, 44_100, 48_000, ResamplerQuality.BALANCED)
        val input = sine(1000.0, 44_100, 22_050)
        val out = runChunked(r, input, 1, 777)
        val inDb = 20 * log10(rms(input))
        val outDb = 20 * log10(rms(out, skip = 200))
        assertEquals(inDb, outDb, 0.05)
    }

    @Test
    fun `passband tone keeps its frequency`() {
        val r = SincResampler(1, 96_000, 48_000)
        val out = runChunked(r, sine(1000.0, 96_000, 96_000), 1, 4096)
        var crossings = 0
        for (i in 1 until out.size) if ((out[i - 1] < 0f) != (out[i] < 0f)) crossings++
        // 1 kHz for 1 s → 2000 zero crossings
        assertEquals(2000.0, crossings.toDouble(), 4.0)
    }

    @Test
    fun `tone above the output Nyquist is rejected when downsampling`() {
        // 30 kHz at 96 kHz would alias to 18 kHz at 48 kHz with no anti-alias filter.
        for ((quality, minRejectionDb) in listOf(
            ResamplerQuality.FAST to 50.0,
            ResamplerQuality.BALANCED to 80.0,
            ResamplerQuality.MASTERING to 100.0
        )) {
            val r = SincResampler(1, 96_000, 48_000, quality)
            val input = sine(30_000.0, 96_000, 48_000)
            val out = runChunked(r, input, 1, 2048)
            val rejection = 20 * log10(rms(input) / rms(out, skip = 500))
            assertTrue("$quality rejection was $rejection dB", rejection > minRejectionDb)
        }
    }

    @Test
    fun `chunk size does not change the output`() {
        val input = sine(3000.0, 44_100, 8000, channels = 2)
        val a = runChunked(SincResampler(2, 44_100, 48_000), input, 2, 64)
        val b = runChunked(SincResampler(2, 44_100, 48_000), input, 2, 5000)
        assertEquals(a.size, b.size)
        for (i in a.indices) assertEquals("sample $i", a[i], b[i], 1e-5f)
    }

    @Test
    fun `DC passes at unity gain`() {
        val r = SincResampler(1, 48_000, 44_100)
        val out = runChunked(r, FloatArray(48_000) { 0.5f }, 1, 1024)
        val mid = out.copyOfRange(2000, out.size - 2000)
        mid.forEach { assertEquals(0.5f, it, 0.001f) }
    }

    @Test
    fun `channels stay independent`() {
        val frames = 4000
        val input = FloatArray(frames * 2) { i -> if (i % 2 == 0) (0.5 * sin(2 * PI * 1000 * (i / 2) / 48_000.0)).toFloat() else 0f }
        val out = runChunked(SincResampler(2, 48_000, 44_100), input, 2, 512)
        assertTrue(rms(out, channels = 2, ch = 0, skip = 100) > 0.3)
        assertEquals(0.0, rms(out, channels = 2, ch = 1, skip = 100), 1e-7)
    }

    @Test
    fun `reset clears history`() {
        val r = SincResampler(1, 44_100, 48_000)
        r.process(FloatArray(1000) { 1f })
        r.reset()
        val silent = r.process(FloatArray(1000))
        assertArrayEquals(FloatArray(silent.size), silent, 0f)
        assertTrue(abs(SincResampler.besselI0(0.0) - 1.0) < 1e-12)
    }
}
