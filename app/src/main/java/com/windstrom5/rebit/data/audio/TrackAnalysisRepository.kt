package com.windstrom5.rebit.data.audio

import com.windstrom5.rebit.playback.AudioDecoders
import android.content.Context
import com.windstrom5.rebit.domain.audiophile.SpectrogramData
import com.windstrom5.rebit.domain.audiophile.TrackAnalysis
import com.windstrom5.rebit.domain.audiophile.TrackAnalyzer
import com.windstrom5.rebit.domain.model.AudioFileFormat
import com.windstrom5.rebit.domain.model.AudioTrackModel
import com.windstrom5.rebit.playback.AudioDecoder
import com.windstrom5.rebit.playback.FlacDecoder
import com.windstrom5.rebit.playback.WavDecoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.Locale

/**
 * Measures a track's real dynamics by decoding it (peak, RMS, crest, TT-DR, clipping, DC offset).
 * Results are cached per file (path + size), so each track is analysed at most once.
 */
class TrackAnalysisRepository(private val context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("track_analysis", Context.MODE_PRIVATE)

    private fun key(track: AudioTrackModel): String =
        MessageDigest.getInstance("SHA-1").digest("${track.filePath}|${track.fileSizeBytes}".toByteArray())
            .joinToString("") { "%02x".format(it) }

    private val spectrogramDir = File(context.filesDir, "spectrograms").apply { mkdirs() }

    /** v3 adds EBU R128 loudness, loudness range and true peak. Older results are re-measured. */
    fun cached(track: AudioTrackModel): TrackAnalysis? {
        val k = key(track)
        val p = prefs.getString(k, null)?.split("|") ?: return null
        if (p.size != 17 || p[0] != "v3") return null
        return try {
            TrackAnalysis(
                drScore = p[1].toInt(), peakDb = p[2].toDouble(), rmsDb = p[3].toDouble(), crestDb = p[4].toDouble(),
                clippedSamples = p[5].toLong(), dcOffsetPercent = p[6].toDouble(), analyzedSeconds = p[7].toDouble(),
                bandwidthHz = p[8].toInt(), midSideRatio = p[9].toDouble(), effectivelyMono = p[10] == "1",
                effectiveBitDepth = p[11].toInt(), totalSamples = p[12].toLong(),
                spectrogram = loadSpectrogram(k, p[13].toInt()),
                integratedLufs = p[14].toDoubleOrNull(),
                loudnessRangeLu = p[15].toDoubleOrNull(),
                truePeakDbtp = p[16].toDoubleOrNull()
            )
        } catch (e: NumberFormatException) {
            null
        }
    }

    private fun store(track: AudioTrackModel, a: TrackAnalysis, sampleRateHz: Int) {
        val k = key(track)
        val s = String.format(
            Locale.US, "v3|%d|%.3f|%.3f|%.3f|%d|%.4f|%.2f|%d|%.4f|%d|%d|%d|%d|%s|%s|%s",
            a.drScore, a.peakDb, a.rmsDb, a.crestDb, a.clippedSamples, a.dcOffsetPercent, a.analyzedSeconds,
            a.bandwidthHz, a.midSideRatio, if (a.effectivelyMono) 1 else 0, a.effectiveBitDepth, a.totalSamples, sampleRateHz,
            a.integratedLufs?.let { String.format(Locale.US, "%.2f", it) } ?: "-",
            a.loudnessRangeLu?.let { String.format(Locale.US, "%.2f", it) } ?: "-",
            a.truePeakDbtp?.let { String.format(Locale.US, "%.2f", it) } ?: "-"
        )
        prefs.edit().putString(k, s).apply()
        a.spectrogram?.let { saveSpectrogram(k, it) }
    }

    private fun saveSpectrogram(key: String, d: SpectrogramData) {
        runCatching {
            DataOutputStream(File(spectrogramDir, "$key.bin").outputStream().buffered()).use { out ->
                out.writeInt(d.columns); out.writeInt(d.rows); out.write(d.levels)
            }
        }
    }

    private fun loadSpectrogram(key: String, sampleRateHz: Int): SpectrogramData? {
        val f = File(spectrogramDir, "$key.bin")
        if (!f.exists()) return null
        return runCatching {
            DataInputStream(f.inputStream().buffered()).use { input ->
                val cols = input.readInt()
                val rows = input.readInt()
                require(cols in 1..4096 && rows in 1..1024)
                val levels = ByteArray(cols * rows)
                input.readFully(levels)
                SpectrogramData(cols, rows, sampleRateHz, levels)
            }
        }.getOrNull()
    }

    /** Decodes the whole file in the background. Returns null for silent/undecodable files. */
    suspend fun analyze(track: AudioTrackModel, onProgress: (Float) -> Unit): TrackAnalysis? =
        withContext(Dispatchers.Default) {
            val decoder: AudioDecoder = AudioDecoders.create(track.format, context)
            try {
                val fmt = decoder.open(track.filePath)
                val analyzer = TrackAnalyzer(
                    fmt.sampleRateHz, fmt.channels, fmt.bitDepth,
                    expectedFrames = (track.durationMs / 1000.0 * fmt.sampleRateHz).toLong()
                )
                val expected = (track.durationMs / 1000.0) * TrackAnalyzer.bytesPerSecond(fmt.sampleRateHz, fmt.channels, fmt.bitDepth)
                val buffer = ByteArray(64 * 1024)
                var total = 0L
                var lastReport = 0f
                while (true) {
                    ensureActive()
                    val n = decoder.read(buffer)
                    if (n < 0) break
                    if (n > 0) {
                        analyzer.feed(buffer, n)
                        total += n
                        val p = if (expected > 0) (total / expected).toFloat().coerceIn(0f, 1f) else 0f
                        if (p - lastReport >= 0.02f) { lastReport = p; onProgress(p) }
                    }
                }
                analyzer.finish()?.also { store(track, it, fmt.sampleRateHz) }
            } finally {
                try { decoder.close() } catch (_: Exception) {}
            }
        }
}
