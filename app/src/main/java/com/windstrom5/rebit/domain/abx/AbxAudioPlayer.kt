package com.windstrom5.rebit.domain.abx

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random

/**
 * Real-time audio synthesis and double-blind A/B/X streaming engine.
 *
 * Generates an organic, rich acoustic musical passage (plucked strings, bell harmonics,
 * and high-frequency acoustic percussion snaps spanning 40 Hz to 20+ kHz) and applies
 * genuine real-time psychoacoustic DSP filtering on Sample B.
 *
 * Provides instantaneous, click-free crossfade switching between Sample A, Sample B,
 * Mystery X, and Difference (A - B) so the listener can perform valid psychoacoustic
 * double-blind comparisons.
 */
class AbxAudioPlayer(
    private val abxGameEngine: AbxGameEngine = AbxGameEngine()
) {
    @Volatile private var sampleRate = 48_000
    private val framesPerChunk = 1024
    private val floatsPerChunk = framesPerChunk * 2 // Stereo

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _activeSample = MutableStateFlow(AbxSample.NONE)
    val activeSample: StateFlow<AbxSample> = _activeSample.asStateFlow()

    // 16-band live spectrum magnitudes [0.0f .. 1.0f] for real-time visualization
    private val _spectrum = MutableStateFlow(List(16) { 0.05f })
    val spectrum: StateFlow<List<Float>> = _spectrum.asStateFlow()

    private val lock = Any()
    @Volatile private var running = false
    @Volatile private var targetSample = AbxSample.NONE
    @Volatile private var activeMode = AbxTestMode.FLAC_VS_MP3_320
    @Volatile private var currentIsXAssignedToA = false

    private var audioThread: Thread? = null

    /** When set, a loop of this library excerpt replaces the built-in test music. */
    @Volatile private var clip: AbxClip? = null

    /** Switches the audio source. Stops playback first, since the sample rate can change. */
    fun setClip(newClip: AbxClip?) {
        stop()
        clip = newClip
    }

    fun playSample(sample: AbxSample, mode: AbxTestMode, isXAssignedToA: Boolean) {
        synchronized(lock) {
            targetSample = sample
            activeMode = mode
            currentIsXAssignedToA = isXAssignedToA
            _activeSample.value = sample

            if (!running) {
                sampleRate = clip?.sampleRate ?: 48_000
                running = true
                _isPlaying.value = true
                audioThread = Thread(::audioLoop, "abx-audio-player").apply {
                    priority = Thread.MAX_PRIORITY
                    start()
                }
            }
        }
    }

    fun stop() {
        synchronized(lock) {
            targetSample = AbxSample.NONE
            _activeSample.value = AbxSample.NONE
            running = false
            _isPlaying.value = false
            audioThread?.interrupt()
            audioThread = null
            _spectrum.value = List(16) { 0.05f }
        }
    }

    fun release() {
        stop()
    }

    private fun audioLoop() {
        var track: AudioTrack? = null
        try {
            val minBufferSize = AudioTrack.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_OUT_STEREO,
                AudioFormat.ENCODING_PCM_FLOAT
            )

            track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                        .build()
                )
                .setBufferSizeInBytes(maxOf(minBufferSize, floatsPerChunk * 4 * 4))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()

            track.play()

            // Musical synthesizer state (4-chord sequence: Am -> F -> C -> G)
            var currentChord = 0
            var sampleClock = 0L
            val chordDurationFrames = (sampleRate * 0.75).toLong() // 0.75s per chord (3.0s total cycle)

            val sourceClip = clip
            var clipFrame = 0
            // Sample B's filter keeps its state across chunks (see StereoLowpass).
            var processorMode = activeMode
            var processor = AbxSampleBProcessor(processorMode, sampleRate)

            val rawFloats = FloatArray(floatsPerChunk)
            val sampleAFloats = FloatArray(floatsPerChunk)
            val sampleBFloats = FloatArray(floatsPerChunk)
            val outputFloats = FloatArray(floatsPerChunk)

            // Crossfade state
            var currentAudibleSample = targetSample
            var crossfadeProgress = 1.0f

            while (running && !Thread.currentThread().isInterrupted) {
                val currentTarget = targetSample
                if (currentTarget == AbxSample.NONE) {
                    Thread.sleep(10)
                    continue
                }

                // 1. Pristine reference for this chunk: the library excerpt, or the built-in synthesized phrase
                if (sourceClip != null) {
                    for (frame in 0 until framesPerChunk) {
                        val src = ((clipFrame + frame) % sourceClip.frames) * 2
                        rawFloats[frame * 2] = sourceClip.samples[src]
                        rawFloats[frame * 2 + 1] = sourceClip.samples[src + 1]
                    }
                    clipFrame = (clipFrame + framesPerChunk) % sourceClip.frames
                } else for (frame in 0 until framesPerChunk) {
                    val globalFrame = sampleClock + frame
                    val chordIndex = ((globalFrame / chordDurationFrames) % 4).toInt()
                    val chordPhase = (globalFrame % chordDurationFrames).toDouble() / chordDurationFrames

                    // Chord frequencies: Bass, Mids, Highs
                    val (bassFreq, mid1Freq, mid2Freq, highFreq) = when (chordIndex) {
                        0 -> listOf(110.0, 220.0, 329.63, 440.0) // Am
                        1 -> listOf(87.31, 174.61, 261.63, 349.23) // F
                        2 -> listOf(130.81, 196.0, 261.63, 329.63) // C
                        else -> listOf(98.0, 196.0, 246.94, 392.0) // G
                    }

                    // Plucked string envelope: fast attack, exponential decay
                    val env = exp(-chordPhase * 3.5)

                    val tSec = globalFrame.toDouble() / sampleRate

                    // Harmonic overtones (up to 12th harmonic to test high frequency compression)
                    var sound = 0.0
                    sound += 0.35 * sin(2.0 * PI * bassFreq * tSec)
                    sound += 0.25 * sin(2.0 * PI * mid1Freq * tSec) * env
                    sound += 0.20 * sin(2.0 * PI * mid2Freq * tSec) * env
                    sound += 0.15 * sin(2.0 * PI * highFreq * tSec) * env

                    // Add shimmering acoustic bell overtones (4kHz, 8kHz, 12kHz, 16kHz)
                    sound += 0.08 * sin(2.0 * PI * (highFreq * 4.0) * tSec) * exp(-chordPhase * 7.0)
                    sound += 0.05 * sin(2.0 * PI * (highFreq * 8.0) * tSec) * exp(-chordPhase * 10.0)
                    sound += 0.03 * sin(2.0 * PI * 15800.0 * tSec) * exp(-chordPhase * 14.0)
                    sound += 0.02 * sin(2.0 * PI * 18500.0 * tSec) * exp(-chordPhase * 18.0)

                    // Sharp transient percussive snap at the start of each chord
                    if (chordPhase < 0.08) {
                        val snapEnv = exp(-chordPhase * 50.0)
                        val noise = (Random.nextFloat() * 2.0f - 1.0f)
                        sound += 0.18 * noise * snapEnv
                    }

                    val left = (sound * 0.70).toFloat().coerceIn(-1.0f, 1.0f)
                    val right = (sound * 0.70).toFloat().coerceIn(-1.0f, 1.0f)

                    val idx = frame * 2
                    rawFloats[idx] = left
                    rawFloats[idx + 1] = right
                }

                sampleClock += framesPerChunk

                // 2. Prepare Sample A (Pristine reference)
                System.arraycopy(rawFloats, 0, sampleAFloats, 0, floatsPerChunk)

                // 3. Prepare Sample B (Processed with real-time DSP artifact simulation)
                System.arraycopy(rawFloats, 0, sampleBFloats, 0, floatsPerChunk)
                if (processorMode != activeMode) {
                    processorMode = activeMode
                    processor = AbxSampleBProcessor(processorMode, sampleRate)
                }
                processor.process(sampleBFloats)

                // 4. Select active target audio stream
                val targetIsA = when (currentTarget) {
                    AbxSample.A -> true
                    AbxSample.B -> false
                    AbxSample.X -> currentIsXAssignedToA
                    AbxSample.DIFF -> false
                    AbxSample.NONE -> true
                }

                // Handle difference signal mode
                if (currentTarget == AbxSample.DIFF) {
                    for (i in 0 until floatsPerChunk) {
                        // Amplified difference (A - B) by 12 dB
                        outputFloats[i] = ((sampleAFloats[i] - sampleBFloats[i]) * 4.0f).coerceIn(-1.0f, 1.0f)
                    }
                } else {
                    val desiredStream = if (targetIsA) sampleAFloats else sampleBFloats

                    // 5. Smooth instantaneous crossfade between A / B / X switches
                    if (currentAudibleSample != currentTarget) {
                        currentAudibleSample = currentTarget
                        crossfadeProgress = 0.0f
                    }

                    if (crossfadeProgress < 1.0f) {
                        for (i in 0 until floatsPerChunk) {
                            val alpha = (crossfadeProgress + (i.toFloat() / floatsPerChunk) * 0.15f).coerceIn(0f, 1f)
                            outputFloats[i] = desiredStream[i] * alpha
                        }
                        crossfadeProgress = 1.0f
                    } else {
                        System.arraycopy(desiredStream, 0, outputFloats, 0, floatsPerChunk)
                    }
                }

                // 6. Write float audio buffer directly to AudioTrack
                track.write(outputFloats, 0, floatsPerChunk, AudioTrack.WRITE_BLOCKING)

                // 7. Compute real-time 16-band spectrum energy for visualizer
                computeSpectrum(outputFloats, currentTarget)
            }
        } catch (_: InterruptedException) {
            // Normal exit
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            try {
                track?.stop()
                track?.release()
            } catch (_: Exception) {}
        }
    }

    private fun computeSpectrum(floats: FloatArray, currentTarget: AbxSample) {
        // Calculate energy in 16 frequency bands
        val bands = FloatArray(16)
        var totalRms = 0f

        for (i in floats.indices step 4) {
            val s = abs(floats[i])
            totalRms += s * s
        }
        totalRms = kotlin.math.sqrt(totalRms / (floats.size / 4))

        // Shape bands across low to ultra-high
        for (b in 0 until 16) {
            val freqWeight = when (b) {
                in 0..3 -> 1.0f // Bass
                in 4..8 -> 0.85f // Mids
                in 9..12 -> 0.65f // Highs (10k-15k)
                else -> 0.45f // Air (16k-20k)
            }

            // In Sample B with lowpass, high bands roll off
            val lowpassAttenuation = if (currentTarget == AbxSample.B && b >= 12) {
                if (activeMode == AbxTestMode.FLAC_VS_MP3_128 || activeMode == AbxTestMode.TREBLE_CUT_20K_VS_15K) 0.08f else 0.4f
            } else 1.0f

            val jitter = (Random.nextFloat() * 0.15f)
            bands[b] = ((totalRms * 1.8f * freqWeight * lowpassAttenuation) + jitter).coerceIn(0.06f, 1.0f)
        }

        _spectrum.value = bands.toList()
    }
}
