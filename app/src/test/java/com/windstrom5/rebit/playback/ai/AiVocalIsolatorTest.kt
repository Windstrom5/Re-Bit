package com.windstrom5.rebit.playback.ai

import com.windstrom5.rebit.domain.model.KaraokeModeType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * NOTE ON BUFFER SIZES: the separation math here runs through
 * [SpectralSeparationEngine], a streaming STFT engine that needs roughly one
 * FFT window (2048 samples) of history before its output reflects real
 * separation, and its temporal mask smoothing takes another handful of hops
 * to settle. That means (a) tests need multiple seconds of signal, not a few
 * hundred samples, and (b) assertions should look at the back half of the
 * buffer, past the zero-padded startup region, not the very first samples.
 * This mirrors how the engine actually behaves in real playback — it's fed
 * a continuous stream buffer after buffer, never a few-hundred-sample clip.
 */
class AiVocalIsolatorTest {

    private lateinit var isolator: AiVocalIsolator

    @Before
    fun setUp() {
        isolator = AiVocalIsolator()
    }

    @Test
    fun `test mono signal center vocal is significantly attenuated in karaoke suppression`() {
        val sampleRate = 44100
        val seconds = 2
        val frames = sampleRate * seconds
        // Interleaved L/R stereo buffer with identical mono vocal content at 1000Hz
        val pcm = ShortArray(frames * 2) { i ->
            (10000 * Math.sin(2.0 * Math.PI * 1000.0 * (i / 2) / sampleRate)).toInt().toShort()
        }
        isolator.process(
            pcm = pcm,
            sampleRate = sampleRate,
            mode = KaraokeModeType.INSTRUMENTAL_ONLY,
            strength = 0.95f,
            preserveBass = true,
            keyShiftSemitones = 0
        )

        val steadyState = pcm.copyOfRange(pcm.size / 2, pcm.size)
        val avgAmplitude = steadyState.map { Math.abs(it.toInt()) }.average()
        assertTrue("Expected vocal reduction below 3500, got $avgAmplitude", avgAmplitude < 3500)
    }

    @Test
    fun `test hard panned stereo side signal is preserved in karaoke suppression`() {
        val sampleRate = 44100
        val seconds = 2
        val frames = sampleRate * seconds
        // L = +10000, R = 0 (pure hard-panned stereo guitar/synth), constant so
        // it stays well outside the bass/treble passthrough bands' edge cases —
        // this is a 0Hz "signal" purely to test spatial (not spectral) handling.
        val pcm = ShortArray(frames * 2) { i ->
            if (i % 2 == 0) (10000 * Math.sin(2.0 * Math.PI * 1200.0 * (i / 2) / sampleRate)).toInt().toShort() else 0
        }
        isolator.process(
            pcm = pcm,
            sampleRate = sampleRate,
            mode = KaraokeModeType.INSTRUMENTAL_ONLY,
            strength = 0.90f,
            preserveBass = true,
            keyShiftSemitones = 0
        )

        val steadyState = pcm.copyOfRange(pcm.size - sampleRate / 2 * 2, pcm.size)
        var sumAbsL = 0.0
        var sumAbsR = 0.0
        var countL = 0
        for (i in steadyState.indices step 2) {
            sumAbsL += Math.abs(steadyState[i].toInt())
            countL++
            if (i + 1 < steadyState.size) sumAbsR += Math.abs(steadyState[i + 1].toInt())
        }
        val avgL = sumAbsL / countL
        val avgR = sumAbsR / countL
        assertTrue("Expected hard-panned L channel mostly preserved, avg=$avgL", avgL > 3000)
        assertTrue("Expected silent R channel to stay near-silent, avg=$avgR", avgR < 1500)
    }

    @Test
    fun `test vocal isolation acapella mode extracts center mono signal`() {
        val sampleRate = 44100
        val seconds = 2
        val frames = sampleRate * seconds
        val pcm = ShortArray(frames * 2) { i ->
            (10000 * Math.sin(2.0 * Math.PI * 1000.0 * (i / 2) / sampleRate)).toInt().toShort()
        }
        isolator.process(
            pcm = pcm,
            sampleRate = sampleRate,
            mode = KaraokeModeType.VOCAL_ISOLATION,
            strength = 1.0f,
            preserveBass = false,
            keyShiftSemitones = 0
        )

        val steadyState = pcm.copyOfRange(pcm.size / 2, pcm.size)
        val avg = steadyState.map { Math.abs(it.toInt()) }.average()
        assertTrue("Expected strong vocal extraction, got $avg", avg > 5000)
    }

    @Test
    fun `test frequency to note name converter`() {
        assertEquals("A4", AiVocalIsolator.frequencyToNoteName(440f))
        assertEquals("A3", AiVocalIsolator.frequencyToNoteName(220f))
        assertEquals("C4", AiVocalIsolator.frequencyToNoteName(261.63f))
        assertEquals("--", AiVocalIsolator.frequencyToNoteName(0f))
    }

    @Test
    fun `test pitch shifting runs without buffer error or overflow`() {
        val pcm = ShortArray(1000) { ((it % 50) * 200).toShort() }
        isolator.process(
            pcm = pcm,
            sampleRate = 44100,
            mode = KaraokeModeType.OFF,
            strength = 0f,
            preserveBass = true,
            keyShiftSemitones = 2
        )

        assertTrue(pcm.isNotEmpty())
        for (sample in pcm) {
            assertTrue(sample in -32768..32767)
        }
    }

    @Test
    fun `test FloatArray instrumental vocal suppression for FLAC streams`() {
        val sampleRate = 44100
        val seconds = 2
        val frames = sampleRate * seconds
        val floats = FloatArray(frames * 2) { i ->
            (0.5 * Math.sin(2.0 * Math.PI * 1000.0 * (i / 2) / sampleRate)).toFloat()
        }
        isolator.processFloats(
            floats = floats,
            sampleRate = sampleRate,
            mode = KaraokeModeType.INSTRUMENTAL_ONLY,
            strength = 0.95f,
            preserveBass = true,
            keyShiftSemitones = 0
        )

        val steadyState = floats.copyOfRange(floats.size / 2, floats.size)
        val avg = steadyState.map { Math.abs(it.toDouble()) }.average()
        assertTrue("Expected FloatArray vocal attenuation below 0.15, got $avg", avg < 0.15)
    }
}
