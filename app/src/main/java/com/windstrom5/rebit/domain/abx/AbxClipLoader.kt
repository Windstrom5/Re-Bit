package com.windstrom5.rebit.domain.abx

import com.windstrom5.rebit.playback.AudioDecoders
import android.content.Context
import com.windstrom5.rebit.domain.model.AudioFileFormat
import com.windstrom5.rebit.domain.model.AudioTrackModel
import com.windstrom5.rebit.playback.AudioDecoder
import com.windstrom5.rebit.playback.FlacDecoder
import com.windstrom5.rebit.playback.WavDecoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Decodes a short excerpt of a library song into memory so ABX can loop it. */
class AbxClipLoader(private val context: Context) {

    /**
     * @param startFraction how far into the song to start (0..1). The default skips most intros.
     * @throws IllegalArgumentException for formats ABX can't use (DSD) or songs that are too short.
     */
    suspend fun load(track: AudioTrackModel, startFraction: Float = 0.30f): AbxClip = withContext(Dispatchers.IO) {
        if (!track.format.isLossless || track.format.isDsd) {
            throw IllegalArgumentException("ABX needs a lossless PCM song (FLAC, WAV, AIFF or ALAC).")
        }
        val decoder: AudioDecoder = AudioDecoders.create(track.format, context)
        try {
            val fmt = decoder.open(track.filePath)
            val bytesPerFrame = AbxPcm.bytesPerSample(fmt.bitDepth) * fmt.channels.coerceAtLeast(1)

            // About 20 seconds, but never more than ~4M frames (keeps 192 kHz files to a sane memory size).
            val seconds = minOf(EXCERPT_SECONDS, MAX_FRAMES / fmt.sampleRateHz).coerceAtLeast(5)
            val wantFrames = fmt.sampleRateHz * seconds
            val wantBytes = wantFrames * bytesPerFrame

            val latestStart = (track.durationMs - seconds * 1000L).coerceAtLeast(0L)
            val startMs = (track.durationMs * startFraction.coerceIn(0f, 1f)).toLong().coerceIn(0L, latestStart)
            if (startMs > 0) decoder.seek(startMs)

            val pcm = ByteArray(wantBytes)
            val chunk = ByteArray(64 * 1024)
            var total = 0
            while (total < wantBytes) {
                val n = decoder.read(chunk)
                if (n <= 0) break
                val take = minOf(n, wantBytes - total)
                System.arraycopy(chunk, 0, pcm, total, take)
                total += take
            }
            total -= total % bytesPerFrame
            require(total >= fmt.sampleRateHz * MIN_SECONDS * bytesPerFrame) { "This song is too short for an ABX excerpt." }

            val floats = AbxPcm.toStereoFloats(pcm, total, fmt.bitDepth, fmt.channels)
            // Headroom: the lossy-simulation filters can overshoot a song that is mastered right up to full scale.
            for (i in floats.indices) floats[i] *= HEADROOM

            AbxClip(
                title = track.title,
                subtitle = track.artist,
                sampleRate = fmt.sampleRateHz,
                samples = floats,
                startMs = startMs
            )
        } finally {
            runCatching { decoder.close() }
        }
    }

    private companion object {
        const val EXCERPT_SECONDS = 20
        const val MIN_SECONDS = 3
        const val MAX_FRAMES = 4_000_000
        const val HEADROOM = 0.85f
    }
}
