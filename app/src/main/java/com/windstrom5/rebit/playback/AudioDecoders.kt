package com.windstrom5.rebit.playback

import android.content.Context
import com.windstrom5.rebit.domain.model.AudioFileFormat

/** The one place that maps a library format to its decoder. */
object AudioDecoders {

    /**
     * @param dsdOutput how DSD files are delivered: [DsdDecoder.Output.DOP] only for a USB DAC that
     *   accepts the DoP carrier rate; [DsdDecoder.Output.PCM] for everything else (and for
     *   analysis, which needs real PCM samples).
     */
    fun create(
        format: AudioFileFormat,
        context: Context,
        dsdOutput: DsdDecoder.Output = DsdDecoder.Output.PCM
    ): AudioDecoder = when (format) {
        AudioFileFormat.FLAC -> FlacDecoder(context)
        AudioFileFormat.WAV -> WavDecoder(context)
        AudioFileFormat.AIFF -> AiffDecoder(context)
        AudioFileFormat.DSF, AudioFileFormat.DFF -> DsdDecoder(context, dsdOutput)
        AudioFileFormat.ALAC, AudioFileFormat.AAC, AudioFileFormat.MP3,
        AudioFileFormat.OPUS, AudioFileFormat.VORBIS -> MediaCodecDecoder(context)
    }

    /** Library format from a file name, for files whose header we haven't parsed. */
    fun formatForName(name: String): AudioFileFormat? {
        val ext = name.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "flac" -> AudioFileFormat.FLAC
            "wav", "wave" -> AudioFileFormat.WAV
            "aif", "aiff", "aifc" -> AudioFileFormat.AIFF
            "dsf" -> AudioFileFormat.DSF
            "dff" -> AudioFileFormat.DFF
            "m4a", "mp4", "alac", "caf" -> AudioFileFormat.ALAC // refined to AAC by the probe
            "aac" -> AudioFileFormat.AAC
            "mp3" -> AudioFileFormat.MP3
            "opus" -> AudioFileFormat.OPUS
            "ogg", "oga" -> AudioFileFormat.VORBIS
            else -> null
        }
    }
}
