package com.windstrom5.rebit.playback.ai

import android.content.Context
import android.content.res.AssetFileDescriptor
import android.util.Log
import com.windstrom5.rebit.domain.model.KaraokeModeType
import org.tensorflow.lite.Interpreter
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Adaptive spectral (STFT) stereo separation engine for karaoke vocal
 * suppression / acapella isolation.
 *
 * What this actually is — read before assuming it's a neural net: it decomposes
 * stereo audio into a time-frequency grid (Short-Time Fourier Transform), and
 * for each frequency bin independently estimates "how much does this bin look
 * like a vocal" from two independent families of cue that get combined:
 *  - Spatial cues — inter-channel phase coherence and level symmetry. Catches
 *    anything mono and centered, which is usually the lead vocal, but is
 *    blind to timbre: a centered kick, bass, or guitar solo scores the same
 *    way, and a vocal that isn't hard-panned dead center (double-tracked,
 *    reverbed, mixed with any stereo width — common in modern masters) scores
 *    low despite genuinely being a vocal.
 *  - Harmonic cues — each frame, a lightweight autocorrelation pitch tracker
 *    (see [estimateFrameFundamental]) finds the dominant fundamental in the
 *    typical vocal range, and bins sitting on that fundamental's harmonic
 *    series get a salience boost, gated by how confident the pitch estimate
 *    is. This is panning-independent — it can rescue a vocal the spatial cue
 *    alone would have missed — but it's blind to space: it doesn't know
 *    whether the pitched content it found is the singer or a lead guitar
 *    playing the same note, and stays silent (contributes nothing) whenever
 *    the frame doesn't have a clear single dominant pitch, e.g. dense
 *    polyphonic sections or unpitched vocal consonants.
 * Together these catch more real-world vocals than either alone, and each
 * covers the other's blind spot — but it is still two DSP heuristics voting,
 * not a trained model. It has no notion of timbre and no way to learn "this
 * specific harmonic content is a human voice" the way a model trained on
 * labeled stems would. No true "hear the difference between a voice and a
 * trumpet playing the same note" separation is possible without that (that's
 * what Demucs/Spleeter/MDX-Net actually are).
 *
 * [loadCustomModel] is a genuine hook for that: hand it a real trained
 * separation model (mask-prediction TFLite, matching the I/O shape
 * [applyTfliteInferenceMask] expects) and it takes over from the DSP heuristic
 * above. No model ships with this app — nothing is loaded by default, and
 * [isModelLoaded] tells you honestly whether one has been. See
 * tools/README_DEMUCS.md for the actually-working alternative to training or
 * converting one yourself: run real Demucs offline and let
 * [ExternalStemResolver] find the pre-separated result and play it back with
 * zero DSP at all.
 */
class SpectralSeparationEngine(private val context: Context? = null) {

    private var interpreter: Interpreter? = null
    var isModelLoaded: Boolean = false
        private set

    // STFT parameters: 2048-point FFT for reasonable frequency resolution while
    // staying real-time-safe (11.6ms hop @ 44.1kHz).
    private val fftSize = 2048
    private val halfFft = fftSize / 2
    private val hopSize = 512 // 75% overlap for artifact-free OLA reconstruction

    // Hann analysis & synthesis window
    private val window = FloatArray(fftSize) { i ->
        (0.5 * (1.0 - cos(2.0 * PI * i / (fftSize - 1)))).toFloat()
    }

    // Window normalization sum for 75% overlap Hann window (sum of w[n]^2 across hops)
    private val windowNorm = 1.5f

    // Overlap-add FIFO buffers for streaming audio. State persists across calls —
    // this engine is built to be fed sequential chunks of a stream (the way a
    // real playback engine calls it, buffer after buffer), not one-shot buffers;
    // see the note on [AiVocalIsolator.process].
    private val inputRingL = FloatArray(fftSize * 2)
    private val inputRingR = FloatArray(fftSize * 2)
    private val outputRingL = FloatArray(fftSize * 2)
    private val outputRingR = FloatArray(fftSize * 2)
    private var inWritePos = 0
    private var outReadPos = 0

    // Temporary FFT buffers
    private val realL = FloatArray(fftSize)
    private val imagL = FloatArray(fftSize)
    private val realR = FloatArray(fftSize)
    private val imagR = FloatArray(fftSize)

    // Raw (unwindowed) mono mixdown of the current analysis frame, used only for
    // pitch tracking below — autocorrelation wants the un-tapered waveform, not
    // the Hann-windowed copy used for the FFT.
    private val monoPitchBuffer = FloatArray(fftSize)

    // Per-bin harmonic salience for the current frame — see computeHarmonicSalience.
    private val harmonicSalience = FloatArray(halfFft + 1)

    // Temporal smoothing memory for artifact-free masks (prevents musical noise)
    private val prevMaskL = FloatArray(halfFft + 1) { 1.0f }
    private val prevMaskR = FloatArray(halfFft + 1) { 1.0f }

    // Smooths the detected fundamental frame-to-frame so a single noisy pitch
    // estimate doesn't visibly wobble the mask.
    private var smoothedF0Hz = 0f
    private var smoothedConfidence = 0f

    /** The DSP separator used whenever no custom model is loaded — see [StereoVocalSeparator].
     *  Rebuilt when the sample rate changes (its FFT size scales with the rate). */
    private var separator: StereoVocalSeparator? = null

    /** Output delay of the active path, in frames, so offline renders can trim it. */
    fun latencyFrames(sampleRate: Int): Int =
        if (interpreter != null && isModelLoaded) fftSize * 2 else separatorFor(sampleRate).latencyFrames

    /** Share of the centre judged vocal on the last frame (0..1); 0 for the model path. */
    val vocalActivity: Float get() = separator?.lastVocalActivity ?: 0f

    /** Drops all streaming history (call after a seek or when karaoke is switched back on). */
    fun reset() {
        separator?.reset()
        java.util.Arrays.fill(inputRingL, 0f); java.util.Arrays.fill(inputRingR, 0f)
        java.util.Arrays.fill(outputRingL, 0f); java.util.Arrays.fill(outputRingR, 0f)
        inWritePos = 0
        outReadPos = 0
    }

    private fun separatorFor(sampleRate: Int): StereoVocalSeparator =
        separator?.takeIf { it.sampleRateHz == sampleRate }
            ?: StereoVocalSeparator(sampleRate).also { separator = it }

    init {
        initializeCustomModelIfPresent()
    }

    /**
     * Looks for a user-supplied model at assets/models/vocal_separator.tflite.
     * None ships with this app, so in practice this is always a no-op unless
     * someone adds one — see the class doc comment.
     */
    private fun initializeCustomModelIfPresent() {
        if (context == null) return

        try {
            val options = Interpreter.Options().apply {
                setNumThreads(4)
                setUseXNNPACK(true)
            }

            val modelBuffer = loadModelFromAssets("models/vocal_separator.tflite")
            if (modelBuffer != null) {
                interpreter = Interpreter(modelBuffer, options)
                isModelLoaded = true
                Log.i(TAG, "Custom vocal-separation model found in assets and loaded with XNNPACK delegation.")
            } else {
                Log.d(TAG, "No custom separation model bundled — using the built-in adaptive-spectral-mask heuristic.")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Custom model load failed (${e.message}); falling back to the built-in DSP heuristic.")
        }
    }

    /**
     * Loads a real user-supplied .tflite stem-separation model (e.g. a
     * lightweight separation network converted for mobile) so it replaces the
     * DSP heuristic below. Expected I/O shape matches [applyTfliteInferenceMask].
     */
    fun loadCustomModel(file: File): Boolean {
        return try {
            val options = Interpreter.Options().apply {
                setNumThreads(4)
                setUseXNNPACK(true)
            }
            val fis = FileInputStream(file)
            val fileChannel = fis.channel
            val startOffset = 0L
            val declaredLength = fileChannel.size()
            val modelBuffer = fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength)

            interpreter?.close()
            interpreter = Interpreter(modelBuffer, options)
            isModelLoaded = true
            Log.i(TAG, "Custom TFLite model loaded from ${file.absolutePath}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load custom TFLite model: ${e.message}", e)
            false
        }
    }

    private fun loadModelFromAssets(assetPath: String): ByteBuffer? {
        if (context == null) return null
        return try {
            val afd: AssetFileDescriptor = context.assets.openFd(assetPath)
            val fis = FileInputStream(afd.fileDescriptor)
            val fileChannel = fis.channel
            fileChannel.map(FileChannel.MapMode.READ_ONLY, afd.startOffset, afd.declaredLength)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Processes stereo audio through the STFT adaptive-mask separation engine
     * (or a loaded custom model, if present).
     */
    fun processFloats(
        floats: FloatArray,
        sampleRate: Int,
        mode: KaraokeModeType,
        strength: Float,
        preserveBass: Boolean
    ) {
        if (mode == KaraokeModeType.OFF) return

        val s = strength.coerceIn(0f, 1f)
        if (interpreter == null || !isModelLoaded) {
            // No trained model: the mid/side + harmonic/percussive separator.
            separatorFor(sampleRate).process(floats, mode, s, preserveBass)
            return
        }
        val binHz = sampleRate.toFloat() / fftSize
        val bassCutoffBin = if (preserveBass) (150f / binHz).toInt().coerceAtLeast(1) else 0
        val trebleCutoffBin = (9000f / binHz).toInt().coerceAtMost(halfFft)
        val vocalCoreStartBin = (180f / binHz).toInt().coerceAtLeast(1)
        val vocalCoreEndBin = (5500f / binHz).toInt().coerceAtMost(halfFft)

        var sampleIdx = 0
        while (sampleIdx < floats.size) {
            // Push incoming samples into input ring buffer
            val inL = floats[sampleIdx]
            val inR = if (sampleIdx + 1 < floats.size) floats[sampleIdx + 1] else inL

            inputRingL[inWritePos] = inL
            inputRingR[inWritePos] = inR

            // Retrieve synthesized OLA samples from output ring buffer
            floats[sampleIdx] = outputRingL[outReadPos]
            if (sampleIdx + 1 < floats.size) {
                floats[sampleIdx + 1] = outputRingR[outReadPos]
            }

            // Clear retrieved output slot
            outputRingL[outReadPos] = 0f
            outputRingR[outReadPos] = 0f

            inWritePos = (inWritePos + 1) % inputRingL.size
            outReadPos = (outReadPos + 1) % outputRingL.size
            sampleIdx += 2

            // Execute STFT frame every hopSize samples (75% overlap)
            if (inWritePos % hopSize == 0) {
                processStftFrame(sampleRate, bassCutoffBin, trebleCutoffBin, vocalCoreStartBin, vocalCoreEndBin, mode, s)
            }
        }
    }

    /**
     * Performs forward STFT, applies the adaptive soft mask (or custom model
     * inference), and executes iSTFT overlap-add.
     */
    private fun processStftFrame(
        sampleRate: Int,
        bassCutoffBin: Int,
        trebleCutoffBin: Int,
        vocalStartBin: Int,
        vocalEndBin: Int,
        mode: KaraokeModeType,
        strength: Float
    ) {
        // 1. Extract windowed frame from input ring buffer (for the FFT), and a
        // raw unwindowed mono mixdown alongside it (for pitch tracking below —
        // autocorrelation wants the un-tapered waveform).
        val startReadIdx = (inWritePos - fftSize + inputRingL.size) % inputRingL.size
        var readIdx = startReadIdx
        for (n in 0 until fftSize) {
            val w = window[n]
            val rawL = inputRingL[readIdx]
            val rawR = inputRingR[readIdx]
            realL[n] = rawL * w
            imagL[n] = 0f
            realR[n] = rawR * w
            imagR[n] = 0f
            monoPitchBuffer[n] = (rawL + rawR) * 0.5f
            readIdx = (readIdx + 1) % inputRingL.size
        }

        // 1b. Track the dominant fundamental in this frame (panning-independent
        // vocal cue — see class doc comment) and turn it into a per-bin
        // harmonic salience map.
        val (rawF0, rawConfidence) = estimateFrameFundamental(monoPitchBuffer, sampleRate)
        // Smooth frame-to-frame so a single noisy estimate doesn't wobble the mask;
        // snap forward fast on a confident new pitch, decay slowly when it's lost.
        if (rawConfidence > 0.15f) {
            val trackingRate = if (smoothedConfidence < 0.1f) 1.0f else 0.5f
            smoothedF0Hz += (rawF0 - smoothedF0Hz) * trackingRate
            smoothedConfidence += (rawConfidence - smoothedConfidence) * trackingRate
        } else {
            smoothedConfidence *= 0.7f
        }
        computeHarmonicSalience(smoothedF0Hz, sampleRate)

        // 2. Forward FFT
        fft(realL, imagL)
        fft(realR, imagR)

        // 3. Adaptive per-bin soft masking (or custom model inference, if loaded)
        val interp = interpreter
        if (interp != null && isModelLoaded) {
            applyTfliteInferenceMask(interp, mode, strength)
        } else {
            val alpha = 1.75f // Mask power exponent — higher is more surgical, more artifact risk
            val smoothFactor = 0.65f // Temporal mask smoothing factor

            for (k in 0..halfFft) {
                val rL = realL[k]
                val iL = imagL[k]
                val rR = realR[k]
                val iR = imagR[k]

                val pwrL = rL * rL + iL * iL
                val pwrR = rR * rR + iR * iR
                val magL = sqrt(pwrL)
                val magR = sqrt(pwrR)
                val eps = 1e-7f

                // Complex cross-channel correlation Re(Z_L * Z_R*) and Im(Z_L * Z_R*)
                val crossReal = rL * rR + iL * iR
                val crossImag = iL * rR - rL * iR

                // Inter-channel Phase Difference (IPD)
                val ipd = atan2(crossImag, crossReal)
                val cosHalfIpd = cos(ipd * 0.5f).coerceIn(-1.0f, 1.0f)
                val phaseCoherence = cosHalfIpd * cosHalfIpd // 1.0 when in-phase (center content), 0.0 when out-of-phase

                // Inter-channel Level Difference (ICLD) & Coherence (ICC)
                val totalPower = pwrL + pwrR + eps
                val icc = (2f * crossReal / totalPower).coerceIn(0f, 1f)
                val icld = (abs(magL - magR) / (magL + magR + eps)).coerceIn(0f, 1f)
                val symmetry = (1.0f - icld).coerceIn(0f, 1f)

                // "How vocal-like is this bin" — captures mono lead vocals, double-tracked
                // vocals, & close harmonies, at the cost of also catching any other mono
                // centered source (kick, bass, lead guitar) — see class doc comment.
                val vocalSpatialSaliency = (icc * 0.70f + phaseCoherence * 0.30f) * symmetry

                // Panning-aware harmonic reinforcement: harmonic resonance helps catch center vocals,
                // gated by stereo symmetry so hard-panned instruments (guitars, side synths) are fully preserved.
                val vocalSaliency = (vocalSpatialSaliency + harmonicSalience[k] * smoothedConfidence * 0.55f * symmetry)
                    .coerceIn(0f, 1f)

                val rawMaskL: Float
                val rawMaskR: Float

                if (k < bassCutoffBin || k > trebleCutoffBin) {
                    // Sub-bass (<150Hz) and high-air (>9kHz): fully preserved regardless of panning
                    rawMaskL = 1.0f
                    rawMaskR = 1.0f
                } else {
                    val isVocalCore = k in vocalStartBin..vocalEndBin
                    val depth = if (isVocalCore) strength else (strength * 0.85f)

                    // Estimated vocal-like power density vs everything-else power density
                    val vocalPwr = totalPower * vocalSaliency
                    val instPwr = totalPower * (1.0f - vocalSaliency)

                    if (mode == KaraokeModeType.INSTRUMENTAL_ONLY) {
                        // M_inst = (P_inst / (P_inst + P_vocal * depth))^alpha
                        val denom = instPwr + vocalPwr * (depth * 1.5f) + eps
                        val wienerRatio = (instPwr / denom).coerceIn(0f, 1f)
                        val wienerMask = Math.pow(wienerRatio.toDouble(), alpha.toDouble()).toFloat()

                        // Dynamic headroom makeup gain to keep the remaining mix full
                        val makeupGain = 1.0f + (depth * 0.15f)
                        rawMaskL = (wienerMask * makeupGain).coerceIn(0f, 1.0f)
                        rawMaskR = (wienerMask * makeupGain).coerceIn(0f, 1.0f)
                    } else {
                        // M_vocal = (P_vocal * depth / (P_inst + P_vocal * depth))^alpha
                        val denom = instPwr + vocalPwr * depth + eps
                        val wienerRatio = ((vocalPwr * depth) / denom).coerceIn(0f, 1f)
                        val wienerMask = Math.pow(wienerRatio.toDouble(), alpha.toDouble()).toFloat()

                        rawMaskL = wienerMask.coerceIn(0f, 1.0f)
                        rawMaskR = wienerMask.coerceIn(0f, 1.0f)
                    }
                }

                // Temporal smoothing across successive frames (reduces chirping/watery artifacts)
                val finalMaskL = smoothFactor * prevMaskL[k] + (1.0f - smoothFactor) * rawMaskL
                val finalMaskR = smoothFactor * prevMaskR[k] + (1.0f - smoothFactor) * rawMaskR
                prevMaskL[k] = finalMaskL
                prevMaskR[k] = finalMaskR

                // Apply frequency-domain mask while preserving original stereo phase
                realL[k] = rL * finalMaskL
                imagL[k] = iL * finalMaskL
                realR[k] = rR * finalMaskR
                imagR[k] = iR * finalMaskR

                // Mirror for negative frequencies
                if (k > 0 && k < halfFft) {
                    val mirror = fftSize - k
                    realL[mirror] = realL[k]
                    imagL[mirror] = -imagL[k]
                    realR[mirror] = realR[k]
                    imagR[mirror] = -imagR[k]
                }
            }
        }

        // 4. Inverse FFT (iSTFT)
        ifft(realL, imagL)
        ifft(realR, imagR)

        // 5. Overlap-add (OLA) into output ring buffer with synthesis window
        var writeIdx = startReadIdx
        val scale = 1.0f / (windowNorm * fftSize)
        for (n in 0 until fftSize) {
            val w = window[n]
            outputRingL[writeIdx] += realL[n] * w * scale
            outputRingR[writeIdx] += realR[n] * w * scale
            writeIdx = (writeIdx + 1) % outputRingL.size
        }
    }

    /**
     * Autocorrelation pitch detector over the typical vocal fundamental range
     * (~85Hz–1000Hz, roughly F2 to B5). Same technique [AiVocalIsolator] already
     * used elsewhere for its UI note display — reused here for a different job:
     * shaping the mask itself, once per hop rather than once per buffer, so it's
     * decimated (every 3rd sample, coarse lag step) to stay cheap at that rate.
     * Returns (fundamentalHz, confidence 0..1) — confidence is the normalized
     * autocorrelation peak height, near 0 for noisy/unpitched/polyphonic frames
     * and near 1 for a clean, strongly periodic single note.
     */
    private fun estimateFrameFundamental(buffer: FloatArray, sampleRate: Int): Pair<Float, Float> {
        val window = buffer.size
        val minLag = (sampleRate / 1000f).toInt().coerceAtLeast(1) // ~1000Hz upper bound
        val maxLag = (sampleRate / 85f).toInt().coerceAtMost(window / 2) // ~85Hz lower bound
        if (maxLag <= minLag) return 0f to 0f

        var energy = 0f
        for (j in buffer.indices step 3) energy += buffer[j] * buffer[j]
        if (energy < 1e-6f) return 0f to 0f // near silence, not worth tracking

        var bestCorr = 0f
        var bestLag = -1
        var lag = minLag
        while (lag <= maxLag) {
            var corr = 0f
            var j = 0
            val limit = window - lag
            while (j < limit) {
                corr += buffer[j] * buffer[j + lag]
                j += 3
            }
            if (corr > bestCorr) {
                bestCorr = corr
                bestLag = lag
            }
            lag++
        }

        if (bestLag <= 0) return 0f to 0f
        val f0 = sampleRate.toFloat() / bestLag
        // Normalize against frame energy so confidence reflects periodicity
        // strength, not just loudness.
        val confidence = (bestCorr / energy).coerceIn(0f, 1f)
        return f0 to confidence
    }

    /**
     * Fills [harmonicSalience] with a bump around each harmonic of [f0Hz] up to
     * the Nyquist-adjacent top of the vocal range. Cheap by construction: rather
     * than testing every bin against every harmonic (O(bins × harmonics)), it
     * walks the (few dozen) harmonics and paints a small neighborhood around
     * each one's center bin (O(harmonics × neighborhood)).
     */
    private fun computeHarmonicSalience(f0Hz: Float, sampleRate: Int) {
        java.util.Arrays.fill(harmonicSalience, 0f)
        if (f0Hz < 50f) return // no usable pitch this frame

        val binHz = sampleRate.toFloat() / fftSize
        val topBin = (5500f / binHz).toInt().coerceAtMost(halfFft) // harmonic search ceiling
        var harmonic = 1
        while (true) {
            val centerBin = (harmonic * f0Hz / binHz).roundToInt()
            if (centerBin > topBin) break
            if (centerBin >= 1) {
                // Triangular bump ±2 bins wide, full strength at center, tapering out —
                // higher harmonics get slightly less weight (the fundamental and low
                // harmonics carry most of a voice's pitch salience).
                val harmonicWeight = 1.0f / (1.0f + harmonic * 0.15f)
                for (offset in -2..2) {
                    val bin = centerBin + offset
                    if (bin < 0 || bin > halfFft) continue
                    val bump = harmonicWeight * (1.0f - abs(offset) / 3.0f)
                    if (bump > harmonicSalience[bin]) harmonicSalience[bin] = bump
                }
            }
            harmonic++
        }
    }

    private fun applyTfliteInferenceMask(interp: Interpreter, mode: KaraokeModeType, strength: Float) {
        // Model input: [1, halfFft+1, 1, 2] (Mag L, Mag R) -> Output: same shape (Mask L, Mask R)
        try {
            val inputTensor = Array(1) { Array(halfFft + 1) { Array(1) { FloatArray(2) } } }
            val outputTensor = Array(1) { Array(halfFft + 1) { Array(1) { FloatArray(2) } } }

            for (k in 0..halfFft) {
                inputTensor[0][k][0][0] = sqrt(realL[k] * realL[k] + imagL[k] * imagL[k])
                inputTensor[0][k][0][1] = sqrt(realR[k] * realR[k] + imagR[k] * imagR[k])
            }

            interp.run(inputTensor, outputTensor)

            for (k in 0..halfFft) {
                val maskL = outputTensor[0][k][0][0].coerceIn(0f, 1f)
                val maskR = outputTensor[0][k][0][1].coerceIn(0f, 1f)

                val finalMaskL = if (mode == KaraokeModeType.INSTRUMENTAL_ONLY) (1.0f - maskL * strength) else maskL * strength
                val finalMaskR = if (mode == KaraokeModeType.INSTRUMENTAL_ONLY) (1.0f - maskR * strength) else maskR * strength

                realL[k] *= finalMaskL
                imagL[k] *= finalMaskL
                realR[k] *= finalMaskR
                imagR[k] *= finalMaskR

                if (k > 0 && k < halfFft) {
                    val mirror = fftSize - k
                    realL[mirror] = realL[k]
                    imagL[mirror] = -imagL[k]
                    realR[mirror] = realR[k]
                    imagR[mirror] = -imagR[k]
                }
            }
        } catch (_: Exception) {}
    }

    /**
     * In-place Radix-2 Cooley-Tukey FFT.
     */
    private fun fft(real: FloatArray, imag: FloatArray) {
        val n = real.size
        var j = 0
        for (i in 0 until n - 1) {
            if (i < j) {
                val tr = real[i]; real[i] = real[j]; real[j] = tr
                val ti = imag[i]; imag[i] = imag[j]; imag[j] = ti
            }
            var k = n shr 1
            while (k <= j) {
                j -= k
                k = k shr 1
            }
            j += k
        }

        var l = 2
        while (l <= n) {
            val halfL = l shr 1
            val angle = -2.0 * PI / l
            val wStepR = cos(angle).toFloat()
            val wStepI = sin(angle).toFloat()

            var i = 0
            while (i < n) {
                var wR = 1.0f
                var wI = 0.0f
                for (m in 0 until halfL) {
                    val idx1 = i + m
                    val idx2 = idx1 + halfL
                    val uR = real[idx1]
                    val uI = imag[idx1]
                    val vR = real[idx2] * wR - imag[idx2] * wI
                    val vI = real[idx2] * wI + imag[idx2] * wR

                    real[idx1] = uR + vR
                    imag[idx1] = uI + vI
                    real[idx2] = uR - vR
                    imag[idx2] = uI - vI

                    val nextWR = wR * wStepR - wI * wStepI
                    val nextWI = wR * wStepI + wI * wStepR
                    wR = nextWR
                    wI = nextWI
                }
                i += l
            }
            l = l shl 1
        }
    }

    /**
     * In-place Inverse FFT (iFFT).
     */
    private fun ifft(real: FloatArray, imag: FloatArray) {
        val n = real.size
        for (i in 0 until n) imag[i] = -imag[i]
        fft(real, imag)
        for (i in 0 until n) imag[i] = -imag[i]
    }

    fun close() {
        interpreter?.close()
        interpreter = null
        isModelLoaded = false
    }

    companion object {
        private const val TAG = "SpectralSeparationEngine"
    }
}
