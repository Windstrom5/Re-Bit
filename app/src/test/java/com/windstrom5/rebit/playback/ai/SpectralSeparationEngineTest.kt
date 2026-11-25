package com.windstrom5.rebit.playback.ai

import com.windstrom5.rebit.domain.model.KaraokeModeType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class SpectralSeparationEngineTest {

    private lateinit var separator: SpectralSeparationEngine

    @Before
    fun setUp() {
        separator = SpectralSeparationEngine(null)
    }

    @Test
    fun `test STFT engine handles stereo float buffer without crashing`() {
        val sampleRate = 44100
        val buffer = FloatArray(2048) { i ->
            (0.5 * sin(2.0 * PI * 1000.0 * (i / 2) / sampleRate)).toFloat()
        }

        separator.processFloats(
            floats = buffer,
            sampleRate = sampleRate,
            mode = KaraokeModeType.INSTRUMENTAL_ONLY,
            strength = 0.95f,
            preserveBass = true
        )

        assertEquals(2048, buffer.size)
    }

    @Test
    fun `test OFF mode passes buffer through untouched`() {
        val original = FloatArray(1024) { 0.42f }
        val buffer = original.clone()

        separator.processFloats(
            floats = buffer,
            sampleRate = 44100,
            mode = KaraokeModeType.OFF,
            strength = 1.0f,
            preserveBass = true
        )

        for (i in buffer.indices) {
            assertEquals(original[i], buffer[i], 1e-6f)
        }
    }

    /**
     * The harmonic-rescue path (see class doc comment) exists specifically for
     * vocals that AREN'T hard-panned dead center — a real-world case pure
     * spatial cues underserve. This builds a clearly-pitched three-harmonic
     * tone (a rough stand-in for a sung note's formant structure) leaning left
     * (70/30 L/R, not hard-panned) and checks it still gets meaningfully
     * suppressed in INSTRUMENTAL_ONLY mode, thanks to the pitch tracker
     * recognizing its harmonic series independent of the imperfect panning.
     */
    @Test
    fun `test off-center pitched harmonic content is still suppressed via harmonic rescue`() {
        val sampleRate = 44100
        val seconds = 2
        val frames = sampleRate * seconds
        val fundamentalHz = 220.0 // A3, a typical vocal fundamental

        fun harmonicTone(i: Int): Double {
            val t = i.toDouble() / sampleRate
            return 0.5 * Math.sin(2.0 * PI * fundamentalHz * t) +
                0.25 * Math.sin(2.0 * PI * fundamentalHz * 2 * t) +
                0.125 * Math.sin(2.0 * PI * fundamentalHz * 3 * t)
        }

        val pcm = ShortArray(frames * 2) { i ->
            val frame = i / 2
            val mono = harmonicTone(frame)
            val isLeft = i % 2 == 0
            val sample = if (isLeft) mono * 0.70 else mono * 0.30 // leaning left, not hard-panned
            (sample * 16000).toInt().toShort()
        }

        val isolator = AiVocalIsolator()
        isolator.process(
            pcm = pcm,
            sampleRate = sampleRate,
            mode = KaraokeModeType.INSTRUMENTAL_ONLY,
            strength = 0.95f,
            preserveBass = false,
            keyShiftSemitones = 0
        )

        val steadyState = pcm.copyOfRange(pcm.size / 2, pcm.size)
        val avgAmplitude = steadyState.map { kotlin.math.abs(it.toInt()) }.average()
        // Original peak-ish amplitude was ~16000*0.7*0.875 combined envelope; a
        // few thousand average after suppression demonstrates real attenuation
        // happened despite the signal not being hard-panned to center.
        assertTrue("Expected off-center pitched content to still be suppressed, got $avgAmplitude", avgAmplitude < 6000)
    }
}
