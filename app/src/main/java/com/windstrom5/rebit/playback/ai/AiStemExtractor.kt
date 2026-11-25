package com.windstrom5.rebit.playback.ai

import com.windstrom5.rebit.playback.AudioDecoders
import android.content.Context
import android.util.Log
import com.windstrom5.rebit.domain.model.AudioFileFormat
import com.windstrom5.rebit.domain.model.AudioTrackModel
import com.windstrom5.rebit.domain.model.KaraokeModeType
import com.windstrom5.rebit.domain.repository.AudioLibraryRepository
import com.windstrom5.rebit.playback.FlacDecoder
import com.windstrom5.rebit.playback.WavDecoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile

data class StemExtractionProgress(
    val progressPercent: Int = 0,
    val statusMessage: String = "",
    val isCompleted: Boolean = false,
    val outputFile: File? = null,
    val error: String? = null
)

/**
 * On-device stem extractor. Two tiers, chosen automatically per track:
 *  - Neural (preferred when usable): [SpleeterNeuralSeparator] — a real
 *    trained model, genuinely good separation. Only usable at its trained
 *    44.1kHz and for stereo sources — see that class's doc comment.
 *  - DSP fallback: [AiVocalIsolator] run in a non-real-time batch pass —
 *    used for any track the neural tier can't handle (wrong sample rate,
 *    mono, or the model failing to load at all), and per-chunk if neural
 *    inference itself fails partway through a track.
 */
class AiStemExtractor(
    private val context: Context? = null,
    private val audioLibraryRepository: AudioLibraryRepository
) {

    private val vocalIsolator = AiVocalIsolator(context)
    private val neuralSeparator = context?.let { SpleeterNeuralSeparator(it) }

    /**
     * Extracts an instrumental or acapella stem from the given track directly on the device.
     */
    fun extractStem(
        track: AudioTrackModel,
        mode: KaraokeModeType = KaraokeModeType.INSTRUMENTAL_ONLY,
        strength: Float = 0.95f
    ): Flow<StemExtractionProgress> = flow {
        val ctx = context
        if (ctx == null) {
            emit(StemExtractionProgress(0, "Android context required for decoding", isCompleted = true, error = "Context is null"))
            return@flow
        }

        val decoder = AudioDecoders.create(track.format, ctx)

        val pcmFormat = try {
            decoder.open(track.filePath)
        } catch (e: Exception) {
            emit(StemExtractionProgress(0, "Failed to open track: ${e.message}", isCompleted = true, error = e.message))
            return@flow
        }

        val sampleRate = pcmFormat.sampleRateHz
        val channels = pcmFormat.channels
        val bitDepth = pcmFormat.bitDepth

        val useNeural = neuralSeparator?.isUsable(sampleRate, channels) == true
        emit(
            StemExtractionProgress(
                0,
                if (useNeural) "Initializing on-device neural separator (Spleeter)..."
                else "Initializing on-device stem extractor..."
            )
        )

        // Prepare app cache directory (kept in private cache, not in music folder)
        val outputDir = File(ctx.externalCacheDir ?: ctx.cacheDir, "stem_cache").apply {
            if (!exists()) mkdirs()
        }
        val prefix = if (mode == KaraokeModeType.INSTRUMENTAL_ONLY) "instrumental" else "acapella"
        val outputFile = File(outputDir, "stem_${track.id}_$prefix.wav")
        val tempFile = File(outputDir, "stem_${track.id}_$prefix.tmp")

        emit(StemExtractionProgress(5, if (useNeural) "Separating with on-device neural model..." else "Rendering to high-speed audio cache..."))

        var totalBytesWritten = 0L
        val fos = try {
            FileOutputStream(tempFile)
        } catch (e: Exception) {
            decoder.close()
            emit(StemExtractionProgress(0, "Failed to create cache file: ${e.message}", isCompleted = true, error = e.message))
            return@flow
        }
        writeWavHeaderPlaceholder(fos)

        // The DSP separator delays its output by its FFT length. For the stem to line up with
        // the original (lyrics, seeking, A-B loops) that many leading output samples are
        // dropped here, and the tail is recovered with flush() at the end.
        vocalIsolator.reset()
        var skipFloats = if (useNeural) 0 else vocalIsolator.latencyFrames(sampleRate) * 2

        fun writePcm16(floats: FloatArray) {
            val from = minOf(skipFloats, floats.size)
            skipFloats -= from
            val count = floats.size - from
            if (count <= 0) return
            val outBytes = ByteArray(count * 2)
            for (i in 0 until count) {
                val s = Math.round(floats[from + i] * 32768.0f).coerceIn(-32768, 32767)
                val dst = i * 2
                outBytes[dst] = (s and 0xFF).toByte()
                outBytes[dst + 1] = ((s ushr 8) and 0xFF).toByte()
            }
            fos.write(outBytes)
            totalBytesWritten += outBytes.size
        }

        // Fixed-size accumulator for the neural path — filled from however
        // decoder.read() happens to chunk things, flushed as one inference
        // call once it reaches a full CHUNK_SECONDS window. A plain
        // FloatArray + fill-index rather than a growable list: this runs
        // over potentially millions of samples per chunk, and boxing each
        // one into a List<Float> would be real, needless GC pressure on a
        // phone CPU for no benefit, since the target size is already known.
        val chunkFloatTarget = SpleeterNeuralSeparator.CHUNK_SECONDS * sampleRate * 2 // *2: interleaved stereo
        val pendingBuffer = if (useNeural) FloatArray(chunkFloatTarget) else FloatArray(0)
        var pendingCount = 0

        fun processNeuralChunk(chunk: FloatArray) {
            val result = neuralSeparator?.separateChunk(chunk, sampleRate)
            val outputChunk = if (result != null) {
                if (mode == KaraokeModeType.INSTRUMENTAL_ONLY) result.second else result.first
            } else {
                // This specific chunk's inference failed (see
                // SpleeterNeuralSeparator.separateChunk's own error
                // handling) — fall back to the DSP path for just this
                // chunk rather than failing the whole extraction.
                vocalIsolator.renderAligned(chunk, sampleRate, mode, strength, true)
            }
            writePcm16(outputChunk)
        }

        fun feedNeuralAccumulator(incoming: FloatArray) {
            var srcPos = 0
            while (srcPos < incoming.size) {
                val spaceLeft = chunkFloatTarget - pendingCount
                val toCopy = minOf(spaceLeft, incoming.size - srcPos)
                System.arraycopy(incoming, srcPos, pendingBuffer, pendingCount, toCopy)
                pendingCount += toCopy
                srcPos += toCopy
                if (pendingCount == chunkFloatTarget) {
                    processNeuralChunk(pendingBuffer.copyOf(chunkFloatTarget))
                    pendingCount = 0
                }
            }
        }

        val bytesPerSample = (bitDepth / 8).coerceAtLeast(2)
        val frameBytes = channels * bytesPerSample
        val bufferSize = 8192 * frameBytes
        val readBuffer = ByteArray(bufferSize)

        val totalFrames = if (track.durationMs > 0) (track.durationMs * sampleRate) / 1000L else 1000000L
        var processedFrames = 0L

        try {
            while (true) {
                val n = decoder.read(readBuffer)
                if (n <= 0) break

                // Convert to float samples
                val sampleCount = n / bytesPerSample
                val floats = FloatArray(sampleCount)
                when (bitDepth) {
                    24 -> {
                        val factor = 1.0f / 8388608.0f
                        for (i in 0 until sampleCount) {
                            val offset = i * 3
                            val b0 = readBuffer[offset].toInt() and 0xFF
                            val b1 = readBuffer[offset + 1].toInt() and 0xFF
                            val b2 = readBuffer[offset + 2].toInt()
                            val sampleInt = (b2 shl 16) or (b1 shl 8) or b0
                            floats[i] = (sampleInt * factor).coerceIn(-1.0f, 1.0f)
                        }
                    }
                    32 -> {
                        val factor = 1.0f / 2147483648.0f
                        for (i in 0 until sampleCount) {
                            val offset = i * 4
                            val b0 = readBuffer[offset].toInt() and 0xFF
                            val b1 = readBuffer[offset + 1].toInt() and 0xFF
                            val b2 = readBuffer[offset + 2].toInt() and 0xFF
                            val b3 = readBuffer[offset + 3].toInt()
                            val sampleInt = (b3 shl 24) or (b2 shl 16) or (b1 shl 8) or b0
                            floats[i] = (sampleInt * factor).coerceIn(-1.0f, 1.0f)
                        }
                    }
                    else -> { // 16-bit
                        val factor = 1.0f / 32768.0f
                        for (i in 0 until sampleCount) {
                            val offset = i * 2
                            val low = readBuffer[offset].toInt() and 0xFF
                            val high = readBuffer[offset + 1].toInt() shl 8
                            val sample = (high or low).toShort()
                            floats[i] = (sample * factor).coerceIn(-1.0f, 1.0f)
                        }
                    }
                }

                if (useNeural) {
                    feedNeuralAccumulator(floats)
                } else {
                    vocalIsolator.processFloats(
                        floats = floats,
                        sampleRate = sampleRate,
                        mode = mode,
                        strength = strength,
                        preserveBass = true,
                        keyShiftSemitones = 0
                    )
                    writePcm16(floats)
                }

                processedFrames += (sampleCount / channels)
                val percent = minOf(95, 5 + ((processedFrames.toFloat() / totalFrames) * 90).toInt())
                emit(StemExtractionProgress(percent, "Extracting stems on-device... $percent%"))
            }

            // Flush whatever's left in the neural accumulator — the final
            // chunk of a track is essentially always shorter than
            // CHUNK_SECONDS, so this always has something to do when
            // useNeural is true.
            if (useNeural && pendingCount > 0) {
                processNeuralChunk(pendingBuffer.copyOf(pendingCount))
            }
            // DSP path: emit the audio still inside the separator so the stem isn't cut short.
            if (!useNeural) {
                writePcm16(vocalIsolator.flush(sampleRate, mode, strength, true))
            }

            fos.flush()
            fos.close()
            decoder.close()

            // Finalize WAV Header with exact file sizes on temp file
            finalizeWavHeader(tempFile, totalBytesWritten, sampleRate, channels, 16)

            // Atomic rename
            if (outputFile.exists()) {
                outputFile.delete()
            }
            tempFile.renameTo(outputFile)

            emit(
                StemExtractionProgress(
                    progressPercent = 100,
                    statusMessage = if (useNeural) "Cached successfully with on-device neural separation!" else "Cached Successfully! (Ready for Instant Playback)",
                    isCompleted = true,
                    outputFile = outputFile
                )
            )
        } catch (e: Exception) {
            Log.e(TAG, "Stem extraction error: ${e.message}", e)
            try { fos.close() } catch (_: Exception) {}
            try { decoder.close() } catch (_: Exception) {}
            try { if (tempFile.exists()) tempFile.delete() } catch (_: Exception) {}
            emit(StemExtractionProgress(0, "Extraction failed: ${e.message}", isCompleted = true, error = e.message))
        }
    }.flowOn(Dispatchers.IO)

    /**
     * Checks if an instrumental or acapella stem is already cached for the given track.
     */
    fun getCachedStem(trackId: Long, mode: KaraokeModeType): File? {
        val ctx = context ?: return null
        val outputDir = File(ctx.externalCacheDir ?: ctx.cacheDir, "stem_cache")
        val prefix = if (mode == KaraokeModeType.INSTRUMENTAL_ONLY) "instrumental" else "acapella"
        val file = File(outputDir, "stem_${trackId}_$prefix.wav")
        return if (file.exists() && file.length() > 44) file else null
    }

    /**
     * Calculates total bytes consumed by all cached stems.
     */
    fun getCacheSizeBytes(): Long {
        val ctx = context ?: return 0L
        val outputDir = File(ctx.externalCacheDir ?: ctx.cacheDir, "stem_cache")
        if (!outputDir.exists()) return 0L
        return outputDir.listFiles()?.sumOf { it.length() } ?: 0L
    }

    /**
     * Deletes all cached stems to free up storage.
     */
    fun clearStemCache(): Boolean {
        val ctx = context ?: return false
        val outputDir = File(ctx.externalCacheDir ?: ctx.cacheDir, "stem_cache")
        if (!outputDir.exists()) return true
        return outputDir.listFiles()?.all { it.delete() } ?: true
    }

    private fun writeWavHeaderPlaceholder(fos: FileOutputStream) {
        val dummy = ByteArray(44)
        fos.write(dummy)
    }

    private fun finalizeWavHeader(file: File, pcmDataLength: Long, sampleRate: Int, channels: Int, bitDepth: Int) {
        val raf = RandomAccessFile(file, "rw")
        val totalDataLen = pcmDataLength + 36
        val byteRate = sampleRate * channels * (bitDepth / 8)

        raf.seek(0)
        raf.writeBytes("RIFF")
        raf.write(intToLittleEndian(totalDataLen.toInt()))
        raf.writeBytes("WAVE")
        raf.writeBytes("fmt ")
        raf.write(intToLittleEndian(16)) // Subchunk1Size for PCM
        raf.write(shortToLittleEndian(1)) // AudioFormat 1 = PCM
        raf.write(shortToLittleEndian(channels.toShort()))
        raf.write(intToLittleEndian(sampleRate))
        raf.write(intToLittleEndian(byteRate))
        raf.write(shortToLittleEndian((channels * (bitDepth / 8)).toShort())) // BlockAlign
        raf.write(shortToLittleEndian(bitDepth.toShort()))
        raf.writeBytes("data")
        raf.write(intToLittleEndian(pcmDataLength.toInt()))
        raf.close()
    }

    private fun intToLittleEndian(value: Int): ByteArray = byteArrayOf(
        (value and 0xFF).toByte(),
        ((value ushr 8) and 0xFF).toByte(),
        ((value ushr 16) and 0xFF).toByte(),
        ((value ushr 24) and 0xFF).toByte()
    )

    private fun shortToLittleEndian(value: Short): ByteArray = byteArrayOf(
        (value.toInt() and 0xFF).toByte(),
        ((value.toInt() ushr 8) and 0xFF).toByte()
    )

    companion object {
        private const val TAG = "AiStemExtractor"
    }
}
