package com.windstrom5.rebit.playback.ai

import com.windstrom5.rebit.domain.model.KaraokeModeType
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Real-time karaoke DSP front end: vocal suppression / acapella isolation and a
 * pitch shifter, driven by [SpectralSeparationEngine] for the actual separation math.
 *
 * Honesty note (read this before changing separation quality): there used to be a
 * naive time-domain center-channel-cancellation step here (L-R sum/difference across
 * a crude 3-band crossover). That technique only removes content that is perfectly
 * centered and in-phase, so alongside the vocal it also gutted center-panned kick,
 * snare, bass and lead guitar, and did nothing for wide/reverberant vocals — that
 * was the "bad separation" this class used to produce. It has been replaced with a
 * delegate to [SpectralSeparationEngine], a per-frequency-bin adaptive soft-masking
 * STFT engine that judges "how vocal-like is this bin" from phase coherence and
 * level symmetry between channels instead of a single whole-signal decision. It is
 * still classic DSP, not a trained neural network — see that class's doc comment
 * for what would actually be required to make it one, and see
 * [com.windstrom5.rebit.playback.ai.ExternalStemResolver] for the tier that
 * *is* real neural separation (an actual Demucs render, played back bit-perfectly
 * with no DSP at all).
 */
class AiVocalIsolator(context: android.content.Context? = null) {

    /** The actual separation engine. Public so a caller can push a real trained
     *  model in via [SpectralSeparationEngine.loadCustomModel] if one becomes
     *  available; unused by default (no model ships with this app). */
    val spectralEngine = SpectralSeparationEngine(context)

    val isCustomModelLoaded: Boolean
        get() = spectralEngine.isModelLoaded

    @Deprecated("Renamed to isCustomModelLoaded — this name implied a bundled TFLite model that was never real.", ReplaceWith("isCustomModelLoaded"))
    val isTfliteModelLoaded: Boolean
        get() = isCustomModelLoaded

    private var pitchShiftSemitones: Int = 0
    /** Rebuilt when the sample rate changes — see [KeyShifter]. */
    private var keyShifter: KeyShifter? = null

    // Live pitch tracking result
    @Volatile
    var detectedPitchHz: Float = 0f
        private set
    @Volatile
    var detectedMusicalNote: String = "--"
        private set

    fun setKeyShift(semitones: Int) {
        pitchShiftSemitones = semitones.coerceIn(-12, 12)
    }

    /** Frames the separation delays the audio by (0 when nothing is being separated). */
    fun latencyFrames(sampleRate: Int): Int = spectralEngine.latencyFrames(sampleRate)

    /** Share of the centre currently judged to be vocal, 0..1 — drives a "vocal removed" meter. */
    val vocalActivity: Float get() = spectralEngine.vocalActivity

    /** Clears separation and key-shift history: call on seek or when karaoke is re-enabled,
     *  otherwise a fraction of a second of stale audio from before plays out first. */
    fun reset() {
        spectralEngine.reset()
        keyShifter?.reset()
    }

    /**
     * Separates one self-contained block with the output time-aligned to the input (the
     * separator's latency is compensated), for offline use on isolated chunks. Resets state.
     */
    fun renderAligned(
        input: FloatArray,
        sampleRate: Int,
        mode: KaraokeModeType,
        strength: Float,
        preserveBass: Boolean
    ): FloatArray {
        reset()
        val lat = latencyFrames(sampleRate) * 2
        val buf = input.copyOf(input.size + lat)
        if (mode != KaraokeModeType.OFF) {
            spectralEngine.processFloats(buf, sampleRate, mode, strength.coerceIn(0f, 1f), preserveBass)
        }
        reset()
        return buf.copyOfRange(lat, lat + input.size)
    }

    /**
     * Pushes [latencyFrames] of silence through the separator and returns what comes out —
     * the tail of the real audio still held inside the filter. Offline renders call this once
     * at end of stream so the rendered stem isn't missing its last ~50 ms.
     */
    fun flush(
        sampleRate: Int,
        mode: KaraokeModeType,
        strength: Float,
        preserveBass: Boolean
    ): FloatArray {
        val tail = FloatArray(latencyFrames(sampleRate) * 2)
        if (mode != KaraokeModeType.OFF) {
            spectralEngine.processFloats(tail, sampleRate, mode, strength.coerceIn(0f, 1f), preserveBass)
        }
        return tail
    }

    /**
     * Master processing method for stereo 16-bit PCM buffers. Applies vocal
     * suppression/isolation, then optional key shifting, seamlessly.
     *
     * Like any short-time-Fourier-transform DSP, [spectralEngine] needs a window
     * of context before it produces meaningful output (roughly one FFT hop —
     * see that class), so it's built to be called repeatedly with sequential
     * chunks of a stream, the way real playback engines call it buffer after
     * buffer. A single call on a very short one-shot buffer (well under a
     * hop's worth of samples) won't show separation within that call alone.
     */
    fun process(
        pcm: ShortArray,
        sampleRate: Int = 44100,
        mode: KaraokeModeType = KaraokeModeType.INSTRUMENTAL_ONLY,
        strength: Float = 0.90f,
        preserveBass: Boolean = true,
        keyShiftSemitones: Int = 0
    ) {
        val floats = FloatArray(pcm.size)
        for (i in pcm.indices) {
            floats[i] = pcm[i] / 32768.0f
        }

        processFloats(floats, sampleRate, mode, strength, preserveBass, keyShiftSemitones)

        for (i in pcm.indices) {
            pcm[i] = (floats[i] * 32767.0f).coerceIn(-32768.0f, 32767.0f).toInt().toShort()
        }
    }

    /**
     * Suppresses lead vocals from stereo 16-bit PCM while preserving stereo instrumental imaging.
     */
    fun processKaraokeVocalSuppression(
        pcm: ShortArray,
        strength: Float = 0.90f,
        preserveBass: Boolean = true,
        sampleRate: Int = 44100
    ) {
        process(pcm, sampleRate, KaraokeModeType.INSTRUMENTAL_ONLY, strength, preserveBass, 0)
    }

    /**
     * Isolates pure vocal track from stereo 16-bit PCM for acapella / lyric practice.
     */
    fun processVocalIsolation(
        pcm: ShortArray,
        isolationLevel: Float = 0.90f,
        sampleRate: Int = 44100
    ) {
        process(pcm, sampleRate, KaraokeModeType.VOCAL_ISOLATION, isolationLevel, false, 0)
    }

    /**
     * High-performance processing for FloatArray buffers directly from FLAC/WAV decoders.
     */
    fun processFloats(
        floats: FloatArray,
        sampleRate: Int = 44100,
        mode: KaraokeModeType = KaraokeModeType.INSTRUMENTAL_ONLY,
        strength: Float = 0.90f,
        preserveBass: Boolean = true,
        keyShiftSemitones: Int = 0
    ) {
        this.pitchShiftSemitones = keyShiftSemitones.coerceIn(-12, 12)

        if (mode != KaraokeModeType.OFF) {
            spectralEngine.processFloats(floats, sampleRate, mode, strength.coerceIn(0f, 1f), preserveBass)
        }

        if (this.pitchShiftSemitones != 0) {
            val shifter = keyShifter?.takeIf { it.sampleRateHz == sampleRate }
                ?: KeyShifter(sampleRate).also { keyShifter = it }
            shifter.process(floats, this.pitchShiftSemitones)
        }

        estimatePitchFloats(floats, sampleRate)
    }

    /**
     * Estimates the fundamental musical pitch (F0) using autocorrelation.
     */
    private fun estimatePitchFloats(floats: FloatArray, sampleRate: Int) {
        val window = floats.size.coerceAtMost(1024)
        if (window < 256) return

        var maxCorr = 0f
        var bestLag = -1
        val minLag = (sampleRate / 1000).coerceAtLeast(1)
        val maxLag = (sampleRate / 60).coerceAtMost(window / 2)

        for (lag in minLag..maxLag step 2) {
            var corr = 0f
            for (j in 0 until window - lag step 4) {
                corr += (floats[j] * floats[j + lag])
            }
            if (corr > maxCorr) {
                maxCorr = corr
                bestLag = lag
            }
        }

        if (bestLag > 0 && maxCorr > 0.05f) {
            val freq = sampleRate.toFloat() / bestLag
            detectedPitchHz = freq
            detectedMusicalNote = frequencyToNoteName(freq)
        }
    }

    companion object {
        private val NOTE_NAMES = arrayOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")

        fun frequencyToNoteName(freq: Float): String {
            if (freq < 20f || freq > 5000f) return "--"
            val midi = (12.0 * (Math.log(freq.toDouble() / 440.0) / Math.log(2.0)) + 69.0).roundToInt()
            val noteIndex = (midi % 12 + 12) % 12
            val octave = (midi / 12) - 1
            return "${NOTE_NAMES[noteIndex]}$octave"
        }
    }
}

/**
 * 2nd-order Direct Form I Biquad IIR Filter with numerically stable state feedback.
 * Still used by [com.windstrom5.rebit.playback.ParametricEqProcessor]'s EQ bands —
 * no longer used inside [AiVocalIsolator] itself (separation moved to a proper STFT
 * engine, see [SpectralSeparationEngine]), kept here unchanged for that consumer.
 */
class BiquadFilter {
    private var b0 = 1.0f
    private var b1 = 0.0f
    private var b2 = 0.0f
    private var a1 = 0.0f
    private var a2 = 0.0f

    private var x1 = 0.0f
    private var x2 = 0.0f
    private var y1 = 0.0f
    private var y2 = 0.0f

    fun setLowPass(sampleRate: Int, cutoffHz: Float = 140.0f) {
        val omega = (2.0 * Math.PI * cutoffHz.toDouble() / sampleRate.toDouble()).toFloat()
        val alpha = (kotlin.math.sin(omega.toDouble()) / (2.0 * 0.70710678)).toFloat()
        val cosW = kotlin.math.cos(omega.toDouble()).toFloat()
        val a0 = 1.0f + alpha
        b0 = ((1.0f - cosW) * 0.5f) / a0
        b1 = (1.0f - cosW) / a0
        b2 = ((1.0f - cosW) * 0.5f) / a0
        a1 = (-2.0f * cosW) / a0
        a2 = (1.0f - alpha) / a0
    }

    fun setHighPass(sampleRate: Int, cutoffHz: Float = 8500.0f) {
        val omega = (2.0 * Math.PI * cutoffHz.toDouble() / sampleRate.toDouble()).toFloat()
        val alpha = (kotlin.math.sin(omega.toDouble()) / (2.0 * 0.70710678)).toFloat()
        val cosW = kotlin.math.cos(omega.toDouble()).toFloat()
        val a0 = 1.0f + alpha
        b0 = ((1.0f + cosW) * 0.5f) / a0
        b1 = (-(1.0f + cosW)) / a0
        b2 = ((1.0f + cosW) * 0.5f) / a0
        a1 = (-2.0f * cosW) / a0
        a2 = (1.0f - alpha) / a0
    }

    fun process(input: Float): Float {
        val out = b0 * input + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
        x2 = x1
        x1 = input
        y2 = y1
        y1 = out
        return out
    }

    fun reset() {
        x1 = 0.0f; x2 = 0.0f; y1 = 0.0f; y2 = 0.0f
    }
}
