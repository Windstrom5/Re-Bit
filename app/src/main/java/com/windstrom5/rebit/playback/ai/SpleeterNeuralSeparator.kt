package com.windstrom5.rebit.playback.ai

import android.content.Context
import android.util.Log
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

/**
 * Real, trained neural source separation — genuinely different from
 * everything else in playback/ai/. This wraps a TFLite export of Deezer's
 * actual pretrained Spleeter 2-stems checkpoint (MIT licensed), not a DSP
 * heuristic and not an untrained placeholder.
 *
 * Provenance and verification, stated plainly: the checkpoint was fetched
 * directly from Deezer's own GitHub releases
 * (github.com/deezer/spleeter/releases/download/v1.4.0/2stems.tar.gz) and
 * verified before conversion — ran it on Spleeter's own official test file,
 * confirmed the two outputs sum back to the original mix (proper
 * complementary masking, not pass-through) and that their spectral
 * characteristics match vocals vs. accompaniment as expected, and cross-
 * checked output order against Deezer's own source config
 * (instrument_list: ["vocals", "accompaniment"]) — index 0 is vocals, index
 * 1 is accompaniment. The TFLite conversion itself succeeded and was
 * confirmed to require Select TF ops (Flex) — the model computes its own
 * STFT/inverse-STFT internally using complex-number tensors, which core
 * TFLite has no kernels for at all, regardless of input shape (tried fixed
 * shapes specifically to check — made no difference, this is fundamental
 * to the graph, not a conversion-settings issue). What was NOT verified:
 * numeric output of the actual .tflite+Flex inference path itself (this
 * build environment's Python TFLite runtime doesn't have Flex wired up to
 * test locally) — confidence here rests on the original TF1 session
 * (same graph, same weights) working correctly, plus Flex delegate
 * execution being a mature, widely-used TFLite capability for exactly this
 * kind of graph.
 *
 * Trained at 44.1kHz — see [isUsable] for why other sample rates fall back
 * to the DSP path rather than risk feeding the model audio at the wrong
 * effective pitch/timing (this app has no resampler to correct for that).
 *
 * Processes long audio in sequential, non-overlapping chunks (like
 * Spleeter's own reference implementation does for long files) rather than
 * one giant inference call, to keep peak memory bounded on-device. A subtle,
 * known, accepted trade-off: chunk boundaries may have a faint seam, since
 * nothing crossfades across them — worth revisiting with overlap-discard
 * blending if it's audible in practice.
 */
class SpleeterNeuralSeparator(private val context: Context) {

    private var interpreter: Interpreter? = null
    private var loadAttempted = false

    val isModelLoaded: Boolean
        get() = interpreter != null

    /** Only meaningful at exactly the model's trained sample rate and for
     *  stereo sources — see class doc comment for why. */
    fun isUsable(sampleRateHz: Int, channels: Int): Boolean =
        sampleRateHz == TRAINED_SAMPLE_RATE_HZ && channels == 2 && ensureLoaded()

    private fun ensureLoaded(): Boolean {
        if (loadAttempted) return interpreter != null
        loadAttempted = true
        interpreter = try {
            val afd = context.assets.openFd(MODEL_ASSET_PATH)
            val buffer: MappedByteBuffer = FileInputStream(afd.fileDescriptor).use { fis ->
                fis.channel.map(FileChannel.MapMode.READ_ONLY, afd.startOffset, afd.declaredLength)
            }
            // No special Flex registration needed here — having
            // tensorflow-lite-select-tf-ops on the classpath is sufficient;
            // it registers its delegate automatically for graphs that need it.
            Interpreter(buffer, Interpreter.Options())
        } catch (e: Throwable) {
            // Missing asset, missing Flex dependency at runtime, or any
            // other load failure — degrade to the DSP path rather than
            // crash stem extraction. Logged once, not per chunk.
            Log.w(TAG, "Spleeter model unavailable, on-device neural separation disabled: ${e.message}", e)
            null
        }
        return interpreter != null
    }

    /**
     * Separates one chunk of interleaved stereo float PCM ([-1, 1] range,
     * L/R/L/R/...) into vocals and accompaniment, same interleaved format.
     * Returns null if the model isn't usable — caller should fall back to
     * the DSP path. Chunk length is whatever the caller passes; see
     * [CHUNK_SECONDS] for the size this is actually called with in practice.
     */
    fun separateChunk(interleavedStereo: FloatArray, sampleRateHz: Int): Pair<FloatArray, FloatArray>? {
        val interp = interpreter ?: return null
        val frameCount = interleavedStereo.size / 2
        if (frameCount == 0) return FloatArray(0) to FloatArray(0)

        return try {
            val input = Array(frameCount) { i -> floatArrayOf(interleavedStereo[i * 2], interleavedStereo[i * 2 + 1]) }

            interp.resizeInput(0, intArrayOf(frameCount, 2))
            interp.allocateTensors()

            val vocalsOut = Array(frameCount) { FloatArray(2) }
            val accompanimentOut = Array(frameCount) { FloatArray(2) }
            // Output index 0 = vocals, 1 = accompaniment — see class doc
            // comment for how that ordering was verified.
            val outputs: MutableMap<Int, Any> = mutableMapOf(0 to vocalsOut, 1 to accompanimentOut)
            interp.runForMultipleInputsOutputs(arrayOf<Any>(input), outputs)

            val vocalsInterleaved = FloatArray(frameCount * 2)
            val accompInterleaved = FloatArray(frameCount * 2)
            for (i in 0 until frameCount) {
                vocalsInterleaved[i * 2] = vocalsOut[i][0]
                vocalsInterleaved[i * 2 + 1] = vocalsOut[i][1]
                accompInterleaved[i * 2] = accompanimentOut[i][0]
                accompInterleaved[i * 2 + 1] = accompanimentOut[i][1]
            }
            vocalsInterleaved to accompInterleaved
        } catch (e: Throwable) {
            Log.w(TAG, "Neural separation failed for a chunk (${frameCount} frames), falling back for this chunk: ${e.message}", e)
            null
        }
    }

    fun close() {
        interpreter?.close()
        interpreter = null
    }

    companion object {
        private const val TAG = "SpleeterNeuralSeparator"
        private const val MODEL_ASSET_PATH = "models/spleeter_2stems.tflite"
        const val TRAINED_SAMPLE_RATE_HZ = 44100

        /** Non-overlapping chunk size for long-file processing — bounds peak
         *  memory instead of running inference on an entire song at once.
         *  30s balances per-chunk TFLite call overhead against memory use;
         *  see class doc comment for the boundary-seam trade-off this implies. */
        const val CHUNK_SECONDS = 30
    }
}
