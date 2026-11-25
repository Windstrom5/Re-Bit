package com.windstrom5.rebit.playback.ai

import com.windstrom5.rebit.domain.model.KaraokeModeType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

class StereoVocalSeparatorTest {

    private val rate = 48_000

    /** A "voice": 220 Hz with decaying harmonics and a ±1 % 5.5 Hz vibrato (proper FM, so every
     *  harmonic wobbles by the same ratio), identical in both channels. */
    private fun voice(t: Int): Float {
        val beta = 0.01 * 220.0 / 5.5 // peak phase deviation of the fundamental
        val vib = beta * sin(2 * PI * 5.5 * t / rate)
        var v = 0.0
        for (h in 1..8) v += sin(2 * PI * 220.0 * h * t / rate + h * vib) / h
        return (0.25 * v).toFloat()
    }

    /** A "guitar": 330 Hz harmonic tone panned hard left. */
    private fun guitar(t: Int): Float {
        var v = 0.0
        for (h in 1..5) v += sin(2 * PI * 330.0 * h * t / rate) / (h * h)
        return (0.3 * v).toFloat()
    }

    private fun render(
        frames: Int,
        mode: KaraokeModeType,
        strength: Float,
        preserveBass: Boolean = true,
        gen: (Int) -> Pair<Float, Float>
    ): Pair<FloatArray, FloatArray> {
        val input = FloatArray(frames * 2)
        for (t in 0 until frames) {
            val (l, r) = gen(t)
            input[2 * t] = l; input[2 * t + 1] = r
        }
        val out = input.copyOf()
        val sep = StereoVocalSeparator(rate)
        var i = 0
        while (i < out.size) { // odd chunk size, like a real playback engine
            val end = minOf(out.size, i + 2 * 937)
            val chunk = out.copyOfRange(i, end)
            sep.process(chunk, mode, strength, preserveBass)
            System.arraycopy(chunk, 0, out, i, chunk.size)
            i = end
        }
        return input to out
    }

    /** Energy of [signal]'s projection on a reference, over frames [from, to), delay-compensated. */
    private fun correlatedEnergy(out: FloatArray, ch: Int, delay: Int, from: Int, to: Int, ref: (Int) -> Float): Double {
        var dot = 0.0
        var refE = 0.0
        for (t in from until to) {
            val r = ref(t).toDouble()
            dot += out[2 * (t + delay) + ch] * r
            refE += r * r
        }
        return (dot / refE).let { it * it * refE }
    }

    private fun db(x: Double) = 10 * log10(x.coerceAtLeast(1e-20))

    @Test
    fun `strength zero reconstructs the input exactly, delayed by the latency`() {
        val sep = StereoVocalSeparator(rate)
        val frames = 20_000
        val input = FloatArray(frames * 2) { i -> (0.4 * sin(2 * PI * 777 * (i / 2) / rate + (i % 2))).toFloat() }
        val out = input.copyOf()
        sep.process(out, KaraokeModeType.INSTRUMENTAL_ONLY, 0f, true)
        val d = sep.latencyFrames
        for (t in d until frames) {
            assertEquals("L at $t", input[2 * (t - d)], out[2 * t], 2e-5f)
            assertEquals("R at $t", input[2 * (t - d) + 1], out[2 * t + 1], 2e-5f)
        }
    }

    @Test
    fun `instrumental mode removes the centred voice and keeps the panned guitar`() {
        val frames = rate * 2
        val (_, out) = render(frames, KaraokeModeType.INSTRUMENTAL_ONLY, 1f) { t ->
            (voice(t) + guitar(t)) to voice(t)
        }
        val d = 2048
        val from = rate / 2
        val to = frames - d
        // Voice is in both channels: measure it on the right, where it is alone.
        val voiceBefore = correlatedEnergy(FloatArray(frames * 2).also { a -> for (t in 0 until frames) a[2 * t + 1] = voice(t) }, 1, 0, from, to) { voice(it) }
        val voiceAfter = correlatedEnergy(out, 1, d, from, to) { voice(it) }
        val guitarAfter = correlatedEnergy(out, 0, d, from, to) { guitar(it) }
        val guitarBefore = correlatedEnergy(FloatArray(frames * 2).also { a -> for (t in 0 until frames) a[2 * t] = guitar(t) }, 0, 0, from, to) { guitar(it) }

        val voiceDrop = db(voiceBefore) - db(voiceAfter)
        val guitarDrop = db(guitarBefore) - db(guitarAfter)
        assertTrue("voice should drop by > 15 dB, dropped $voiceDrop dB", voiceDrop > 15.0)
        assertTrue("guitar should stay within 1.5 dB, changed $guitarDrop dB", kotlin.math.abs(guitarDrop) < 1.5)
    }

    @Test
    fun `centred drums survive while the centred voice is removed`() {
        val frames = rate * 2
        val clickEvery = rate / 8
        fun click(t: Int): Float = if (t % clickEvery < 48) (0.8 * cos(PI * (t % clickEvery) / 96.0)).toFloat() else 0f
        val (_, out) = render(frames, KaraokeModeType.INSTRUMENTAL_ONLY, 1f) { t ->
            val m = voice(t) + click(t)
            m to m
        }
        val d = 2048
        // Energy right around each click vs. the gaps between clicks (where only voice was).
        var clickE = 0.0; var clickIn = 0.0; var gapE = 0.0; var gapIn = 0.0
        for (t in rate / 2 until frames - d) {
            val o = out[2 * (t + d)].toDouble()
            val inV = (voice(t) + click(t)).toDouble()
            if (t % clickEvery < 48) { clickE += o * o; clickIn += inV * inV }
            else if (t % clickEvery in clickEvery / 3 until 2 * clickEvery / 3) { gapE += o * o; gapIn += inV * inV }
        }
        val clickKeptDb = db(clickE) - db(clickIn)
        val gapKeptDb = db(gapE) - db(gapIn)
        assertTrue("clicks should keep most of their energy ($clickKeptDb dB)", clickKeptDb > -6.0)
        assertTrue("voice between clicks should be well down ($gapKeptDb dB)", gapKeptDb < -12.0)
    }

    @Test
    fun `bass under 120 Hz is preserved when asked`() {
        val frames = rate
        fun bass(t: Int) = (0.5 * sin(2 * PI * 60.0 * t / rate)).toFloat()
        val (_, out) = render(frames, KaraokeModeType.INSTRUMENTAL_ONLY, 1f, preserveBass = true) { t -> bass(t) to bass(t) }
        val d = 2048
        var e = 0.0; var eIn = 0.0
        for (t in rate / 4 until frames - d) { e += out[2 * (t + d)].let { it * it }; eIn += bass(t).let { it * it } }
        assertEquals(0.0, db(e) - db(eIn), 0.5)
    }

    @Test
    fun `vocal isolation keeps the voice and drops the panned guitar`() {
        val frames = rate * 2
        val (_, out) = render(frames, KaraokeModeType.VOCAL_ISOLATION, 1f) { t ->
            (voice(t) + guitar(t)) to voice(t)
        }
        val d = 2048
        val from = rate / 2
        val to = frames - d
        val guitarLeft = correlatedEnergy(out, 0, d, from, to) { guitar(it) }
        val voiceLeft = correlatedEnergy(out, 0, d, from, to) { voice(it) }
        assertTrue("guitar should be > 15 dB under the voice (${db(voiceLeft) - db(guitarLeft)})", db(voiceLeft) - db(guitarLeft) > 15.0)
    }

    @Test
    fun `fft size scales with sample rate`() {
        assertEquals(2048, StereoVocalSeparator(44_100).fftSize)
        assertEquals(4096, StereoVocalSeparator(96_000).fftSize)
        assertEquals(8192, StereoVocalSeparator(192_000).fftSize)
    }

    @Test
    fun `reset silences stale audio`() {
        val sep = StereoVocalSeparator(rate)
        val loud = FloatArray(8192) { 0.5f }
        sep.process(loud, KaraokeModeType.INSTRUMENTAL_ONLY, 0.5f, true)
        sep.reset()
        val silent = FloatArray(4096)
        sep.process(silent, KaraokeModeType.INSTRUMENTAL_ONLY, 0.5f, true)
        silent.forEach { assertEquals(0f, it, 0f) }
    }

    @Test
    fun `median helper`() {
        assertEquals(3f, StereoVocalSeparator.median(floatArrayOf(5f, 1f, 3f, 4f, 2f), 5), 0f)
        assertEquals(2.5f, StereoVocalSeparator.median(floatArrayOf(4f, 1f, 3f, 2f), 4), 0f)
    }

    @Test
    fun `fft round trip`() {
        val n = 1024
        val fft = Fft(n)
        val re = FloatArray(n) { sin(it * 0.37).toFloat() }
        val im = FloatArray(n) { cos(it * 0.11).toFloat() }
        val r0 = re.copyOf(); val i0 = im.copyOf()
        fft.transform(re, im, inverse = false)
        fft.transform(re, im, inverse = true)
        for (k in 0 until n) {
            assertEquals(r0[k], re[k] / n, 1e-4f)
            assertEquals(i0[k], im[k] / n, 1e-4f)
        }
        assertTrue(sqrt(1.0) == 1.0)
    }
}
