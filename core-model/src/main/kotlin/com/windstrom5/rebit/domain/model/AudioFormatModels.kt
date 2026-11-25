package com.windstrom5.rebit.domain.model

/**
 * Every container/codec the library indexes and plays.
 *
 * DSD tracks (DSF/DFF) are stored with a 1-bit [PcmFormat] at their DSD rate (e.g. 2 822 400 Hz
 * for DSD64), which is what the library shows; the decoder turns them into DoP or PCM for playback.
 */
enum class AudioFileFormat(
    val label: String,
    val isLossless: Boolean,
    val isDsd: Boolean = false
) {
    WAV("WAV", true),
    FLAC("FLAC", true),
    AIFF("AIFF", true),
    ALAC("ALAC", true),
    DSF("DSF", true, isDsd = true),
    DFF("DFF", true, isDsd = true),
    MP3("MP3", false),
    AAC("AAC", false),
    OPUS("Opus", false),
    VORBIS("Ogg Vorbis", false);

    companion object {
        /** Tolerant lookup for values stored by older builds or other sources. */
        fun fromName(name: String?): AudioFileFormat =
            entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: FLAC
    }
}

/** "DSD64", "DSD128"… for a DSD bit rate; null when [sampleRateHz] isn't a DSD rate. */
fun dsdRateLabel(sampleRateHz: Int): String? {
    if (sampleRateHz < 2_000_000) return null
    val multiple = Math.round(sampleRateHz / 44_100.0).toInt()
    return "DSD$multiple"
}

/** Which playback path produced a given result — the independent variable of Experiment A. */
enum class EngineType { ANDROID_AUDIOTRACK, CUSTOM_USB_DIRECT, ANDROID_HIRES_DIRECT }

/** The four standardized research protocols from Chapter 3.8 / Chapter 4. */
enum class ExperimentType { EXPERIMENT_A_ARCHITECTURE, EXPERIMENT_B_SAMPLE_RATE, EXPERIMENT_C_DAC_HARDWARE, EXPERIMENT_D_BUFFER_SIZE }

/**
 * A concrete PCM format triple. Two formats are "native-compatible" only if all
 * three fields match exactly — this is the basis of the integrity scoring model.
 */
data class PcmFormat(
    val sampleRateHz: Int,
    val bitDepth: Int,
    val channels: Int
) {
    fun matchesSampleRate(other: PcmFormat) = sampleRateHz == other.sampleRateHz
    fun matchesBitDepth(other: PcmFormat) = bitDepth == other.bitDepth
    fun matchesChannels(other: PcmFormat) = channels == other.channels

    companion object {
        val CD_QUALITY = PcmFormat(44_100, 16, 2)
    }
}
