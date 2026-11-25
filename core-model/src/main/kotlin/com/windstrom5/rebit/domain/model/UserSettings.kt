package com.windstrom5.rebit.domain.model

/**
 * Whether AdaptiveOptimizationEngine's buffer-resize recommendations are
 * actually applied during playback (ADAPTIVE, the default), or the buffer
 * size stays exactly where the person pinned it (MANUAL) — useful for
 * reproducing a specific point on the Experiment D stability-latency curve
 * during normal listening rather than only inside the Research tab.
 */
enum class BufferMode { ADAPTIVE, MANUAL }

/**
 * Two distinct, genuinely different strategies for driving the isochronous USB
 * endpoint — both bypass AudioFlinger entirely and are equally bit-perfect;
 * they differ in *how* they schedule transfers, not *whether* they modify data.
 */
/**
 * PIPELINED and SYNCHRONOUS are two scheduling strategies within the same
 * pure-Kotlin/JVM transfer engine (see UsbIsochronousAudioStreamer). NATIVE
 * is a fundamentally different engine — an NDK/C++ isochronous transfer loop
 * (see core-usb/src/main/cpp/usb_native_streamer.cpp) that avoids JVM garbage
 * collection pauses during the timing-critical transfer window, at real
 * additional risk: a bug there can crash the whole process, not just throw a
 * catchable exception. Read that file's header before selecting it.
 */
enum class UsbTransferStrategy { PIPELINED, SYNCHRONOUS, NATIVE }

/**
 * Which USB Audio Class protocol version to activate on a DAC that exposes
 * more than one (a composite/dual-mode altsetting layout — genuinely common
 * on higher-end DACs that ship both a UAC1 altsetting for maximum OS
 * compatibility and a UAC2 altsetting for higher bandwidth/lower latency).
 * AUTO picks whichever altsetting DacCapabilityAnalyzer found first that
 * matches the requested format — usually fine, but on a device with quirky
 * or ambiguous descriptors, forcing one version can be the difference between
 * silence and a working stream. Meaningless (silently ignored) on a DAC that
 * only exposes one protocol version to begin with.
 */
enum class UsbProtocolPreference { AUTO, FORCE_UAC1, FORCE_UAC2 }

enum class EqPreset(val label: String, val gains: List<Float>) {
    FLAT("Flat / Neutral", listOf(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f)),
    HARMAN_TARGET("Harman Target", listOf(3f, 2f, 1f, 0f, -1f, 0f, 1f, 2f, 2f, 3f)),
    AUDIOPHILE_NEUTRAL("Audiophile Reference", listOf(0f, 0f, 0f, 0f, 0f, 0f, 0f, 1f, 1f, 2f)),
    BASS_EXTENSION("Bass Extension", listOf(5f, 4f, 3f, 1f, 0f, 0f, 0f, 0f, 1f, 1f)),
    VOCAL_CLARITY("Vocal Clarity", listOf(-1f, 0f, 1f, 2f, 4f, 3f, 2f, 1f, 0f, 0f))
}

enum class DsdPlaybackMode(val label: String) {
    PCM_CONVERT("DSD-to-PCM"),
    DOP("DoP (DSD over PCM)"),
    NATIVE_DSD("Native DSD Direct")
}

enum class ReverbPreset(val label: String, val decaySec: Float, val roomSize: Float) {
    STUDIO_ROOM("Studio Room", 0.8f, 0.3f),
    CONCERT_HALL("Concert Hall", 2.2f, 0.75f),
    LIVE_STAGE("Live Stage", 1.5f, 0.55f),
    INTIMATE_CLUB("Intimate Club", 1.0f, 0.4f),
    CATHEDRAL("Cathedral", 3.8f, 0.95f)
}

enum class ReplayGainMode(val label: String) {
    TRACK("Track Loudness (EBU R128)"),
    ALBUM("Album Dynamic Range")
}

enum class KaraokeModeType(val label: String) {
    INSTRUMENTAL_ONLY("Pure Instrumental (AI Vocal Suppress)"),
    VOCAL_ISOLATION("Acapella (Solo Vocal Isolate)"),
    OFF("Standard Stereo")
}

enum class DsdRemasterTarget(val label: String, val multiplier: Int) {
    DSD64("DSD64 (2.8224 MHz / 64x)", 64),
    DSD128("DSD128 (5.6448 MHz / 128x)", 128),
    DSD256("DSD256 (11.2896 MHz / 256x)", 256)
}

enum class DsdNoiseShaperProfile(val label: String) {
    FIFTH_ORDER_PSYCHOACOUSTIC("5th-Order Psychoacoustic Optimal"),
    SEVENTH_ORDER_REFERENCE("7th-Order High SNR Studio"),
    MINIMUM_PHASE_ULTRA("Minimum Phase Low-Dispersal")
}

/**
 * Sample-rate converter quality, used only when the output device can't run at the track's
 * own rate (the Android mixer path). Bit-perfect USB Direct never resamples.
 *
 * @property zeroCrossings kernel half-width in zero crossings — longer = steeper filter, more CPU
 * @property kaiserBeta Kaiser window shape — sets stopband attenuation (~10·β dB)
 * @property rolloff passband edge as a fraction of the lower Nyquist frequency
 */
enum class ResamplerQuality(
    val label: String,
    val description: String,
    val zeroCrossings: Int,
    val kaiserBeta: Double,
    val rolloff: Double
) {
    FAST("Fast", "Short sinc · ~60 dB alias rejection · lowest CPU", 8, 6.0, 0.90),
    BALANCED("Balanced", "Windowed sinc · ~90 dB alias rejection", 24, 9.0, 0.94),
    MASTERING("Mastering", "Long sinc · ~120 dB alias rejection · highest CPU", 64, 12.0, 0.97)
}

/**
 * What happens when the processed (32-bit float) signal is reduced to a 16- or 24-bit output.
 * Truncating without dither turns rounding error into distortion that tracks the music;
 * dither turns it into a constant, benign noise floor instead.
 */
enum class DitherMode(val label: String, val description: String) {
    OFF("Off", "Round to nearest · lowest noise, quantization distortion on fades"),
    TPDF("TPDF", "Triangular dither · distortion-free, flat noise floor"),
    NOISE_SHAPED("Noise-shaped", "TPDF + psychoacoustic shaping · noise pushed above 15 kHz")
}

enum class FirFilterType(val label: String) {
    LINEAR_PHASE_SHARP("Linear Phase Sharp"),
    MINIMUM_PHASE_SLOW("Minimum Phase Slow (Analog Warmth)"),
    NOS_DIRECT("NOS (Non-Oversampling Emulation)")
}

/**
 * User-configurable settings tuning both bit-perfect hardware parameters
 * and precision audiophile DSP features (UAPP, Poweramp, Foobar2000 style).
 */
data class UserSettings(
    val bufferMode: BufferMode = BufferMode.ADAPTIVE,
    val manualBufferSizeBytes: Int = PlaybackConfig.DEFAULT_BUFFER_BYTES,
    val defaultEngine: EngineType = EngineType.CUSTOM_USB_DIRECT,
    val keepScreenOnDuringPlayback: Boolean = true,
    /** N-buffering depth for the isochronous USB streamer */
    val usbRequestPoolSize: Int = 4,
    /** Which bit-perfect USB transfer strategy to drive the DAC with */
    val usbTransferStrategy: UsbTransferStrategy = UsbTransferStrategy.PIPELINED,
    /** Off for the Pipelined "Fixed-Rate" driver profile — see
     *  UsbIsochronousAudioStreamer's class doc for what this trades off.
     *  Synchronous mode never uses this regardless of the value here (it's
     *  fixed-rate by construction, not a configurable choice for it). */
    val useAdaptiveClockCorrection: Boolean = true,
    val usbProtocolPreference: UsbProtocolPreference = UsbProtocolPreference.AUTO,
    /** USB Buffer Latency preset in milliseconds (16ms, 32ms, 64ms, 128ms) */
    val usbBufferLatencyMs: Int = 32,
    /** Direct USB Audio Class (UVC) Hardware Register Volume Control */
    val hardwareVolumeEnabled: Boolean = true,
    val hardwareVolumeSteps: Int = 100,
    val hardwareVolumeStepIndex: Int = 85,
    /** DSD & DoP Stream transmission mode */
    val dsdMode: DsdPlaybackMode = DsdPlaybackMode.DOP,
    /** Real-time PCM to DSD Remastering Engine (HQPlayer / TEAC / Sony Walkman style) */
    val dsdRemasteringEnabled: Boolean = false,
    val dsdRemasterTarget: DsdRemasterTarget = DsdRemasterTarget.DSD64,
    val dsdNoiseShaperProfile: DsdNoiseShaperProfile = DsdNoiseShaperProfile.FIFTH_ORDER_PSYCHOACOUSTIC,
    /** Analog Triode Vacuum Tube Warmth Simulator */
    val tubeWarmthEnabled: Boolean = false,
    val tubeWarmthDrive: Float = 0.35f,
    /** Digital Reconstruction Filter */
    val firFilterType: FirFilterType = FirFilterType.MINIMUM_PHASE_SLOW,
    /** Bypass Android AudioFlinger / Request Direct Bit-Perfect Output on Internal DACs */
    val hiResDirectOutputEnabled: Boolean = true,
    /** 10-Band Precision Equalizer */
    val eqEnabled: Boolean = false,
    val eqGains: List<Float> = listOf(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f),
    val eqPreset: EqPreset = EqPreset.FLAT,
    val selectedIemProfile: String? = null,
    val eqPreampDb: Float = 0.0f,
    /**
     * Exact parametric correction (e.g. an AutoEq IEM preset, plus an optional genre tuning).
     * When non-empty it is applied instead of the 10 graphic [eqGains]; editing a graphic band clears it.
     */
    val eqFilters: List<EqFilter> = emptyList(),
    /** Preamp (dB, <= 0) that keeps [eqFilters] from clipping. */
    val eqFilterPreampDb: Float = 0.0f,
    /** Genre/sound-signature id applied on top of the IEM correction ("" = none). */
    val eqSignature: String = "",
    val eqSignatureStrength: Float = 1.0f,
    /** Headphone crossfeed */
    val crossfeedEnabled: Boolean = false,
    val crossfeedStrength: Float = 0.3f,
    /** Poweramp-style Stereo Soundstage Spatial Expander (0.0=Mono, 1.0=Normal, 2.0=Super-Wide) */
    val spatialWidth: Float = 1.0f,
    /** Poweramp-style Studio Acoustic Reverb Environment Simulator */
    val reverbEnabled: Boolean = false,
    val reverbPreset: ReverbPreset = ReverbPreset.STUDIO_ROOM,
    val reverbWetDry: Float = 0.25f,
    /** Foobar2000-style ReplayGain & EBU R128 Dynamic Range Normalizer */
    val replayGainEnabled: Boolean = false,
    val replayGainMode: ReplayGainMode = ReplayGainMode.TRACK,
    val replayGainPreampDb: Float = 0.0f,
    val preventClipping: Boolean = true,
    val normalizationEnabled: Boolean = false,
    /** Automatically bypasses EQ, Spatial, Reverb, and ReplayGain whenever Custom USB Direct is active */
    val autoBypassDspInBitPerfect: Boolean = true,
    /** Play consecutive same-format tracks with no silence between them (live albums, classical). */
    val gaplessEnabled: Boolean = true,
    /** Sample-rate converter used when the Android mixer can't take the track's native rate. */
    val resamplerQuality: ResamplerQuality = ResamplerQuality.BALANCED,
    /** How the float DSP output is reduced to a 16/24-bit output format. */
    val ditherMode: DitherMode = DitherMode.TPDF,
    /** FIR convolution with a user-supplied impulse response (room / headphone correction). */
    val convolutionEnabled: Boolean = false,
    /** content:// URI of the impulse-response file (WAV / FLAC / AIFF, mono or stereo). */
    val convolutionIrUri: String? = null,
    val convolutionIrName: String? = null,
    /** ISO 226 equal-loudness compensation that follows the media volume. */
    val loudnessCompensationEnabled: Boolean = false,
    /** 0.5 = gentle … 1.0 = full ISO 226 difference … 1.5 = strong. */
    val loudnessCompensationStrength: Float = 1.0f,
    /** Sleep timer with smooth 60-second fade-out */
    val sleepTimerMinutes: Int = 0,
    val sleepTimerFadeOutEnabled: Boolean = true,
    /** On-Device TensorFlow Lite AI Audio Suite */
    val aiAutoPilotEnabled: Boolean = false,
    val aiDseeUpscalerEnabled: Boolean = true,
    val aiVocalSuppressMode: Boolean = false,
    /** Real-time AI Karaoke Instrumental & Vocal Isolator Engine */
    val karaokeModeEnabled: Boolean = false,
    val karaokeModeType: KaraokeModeType = KaraokeModeType.INSTRUMENTAL_ONLY,
    val karaokeVocalSuppressionStrength: Float = 0.90f,
    val karaokeBassPreservation: Boolean = true,
    val karaokeKeyShiftSemitones: Int = 0,
    val karaokePlaybackSpeed: Float = 1.0f,
    val karaokeMicScoringEnabled: Boolean = false
) {
    companion object {
        val USB_REQUEST_POOL_RANGE = 2..8
        val EQ_BANDS = listOf(31, 62, 125, 250, 500, 1000, 2000, 4000, 8000, 16000)
        val USB_BUFFER_PRESETS_MS = listOf(16, 32, 64, 128)
        val HARDWARE_STEP_MODES = listOf(64, 100, 256)
    }
}
