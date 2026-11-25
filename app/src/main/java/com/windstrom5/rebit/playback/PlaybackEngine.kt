package com.windstrom5.rebit.playback

import com.windstrom5.rebit.domain.model.PcmFormat
import com.windstrom5.rebit.domain.model.TrafficLogEntry
import com.windstrom5.rebit.domain.model.ProcessingSummary
import com.windstrom5.rebit.domain.model.VerificationSnapshot

/**
 * Common surface for both arms of Experiment A (Section 4.1): the standard
 * Android AudioTrack path and the custom USB-direct path. Keeping both behind
 * one interface is what lets ExperimentOrchestrator swap engines while holding
 * everything else constant — the architecture is the only independent variable.
 */
interface PlaybackEngine {
    fun start(
        decoder: AudioDecoder,
        format: PcmFormat,
        bufferSizeBytes: Int,
        onDropout: () -> Unit,
        onCompletion: () -> Unit,
        onError: (Throwable) -> Unit = {},
        /** REPLAYGAIN_TRACK_GAIN from the source file's Vorbis comments, in dB —
         *  see [FlacVorbisCommentReader]. Null when absent (most files) or not
         *  applicable (WAV). Only the AudioTrack engine ever applies this —
         *  UsbDirectPlaybackEngine doesn't reference this parameter at all,
         *  consistent with bit-perfect USB-direct playback never running any DSP. */
        replayGainDb: Float? = null,
        /** REPLAYGAIN_TRACK_PEAK from the same tag block — the Normalization
         *  toggle's data source, distinct from replayGainDb's perceptual gain. */
        trackPeak: Float? = null
    )

    fun stop()

    /** Applied live by the AdaptiveOptimizationEngine feedback loop (Section 3.6). */
    fun setBufferSize(bytes: Int)

    /**
     * A modeled latency estimate in milliseconds — see README "How measurements
     * work" for what is a real hardware/OS reading versus a documented model in
     * this app. Buffer fill time is real arithmetic; any fixed per-engine
     * overhead added on top represents typical published mixer/HAL latency
     * contributions, not a live round-trip timestamp measurement.
     */
    fun currentLatencyEstimateMs(): Double

    /** Elapsed playback position in milliseconds since [start], computed from
     *  actual bytes transferred (not a wall-clock timer, so it stays accurate
     *  across dropouts/stalls). Resets to 0 on every [start]; PlaybackController
     *  adds this to the position it started the session at to get an absolute
     *  track position — see PlaybackController's seek handling. */
    fun currentPositionMs(): Long

    /** Live checksum-based proof of transfer integrity — see [TransferVerifier]. */
    fun verificationSnapshot(): VerificationSnapshot

    /** Most recent isochronous packet events — see [com.windstrom5.rebit.usb.UsbTrafficLog].
     *  Defaults to empty: the AudioTrack engine has no isochronous packets to log, so it
     *  gets this for free rather than needing a meaningless stub implementation. */
    fun trafficLog(): List<TrafficLogEntry> = emptyList()

    /**
     * Live (pitchHz, noteName) from this engine's own vocal-pitch tracker, if
     * it has one that's actually being fed real audio — see
     * AudioTrackPlaybackEngine's vocalIsolator, which runs whenever karaoke
     * mode is active (the same condition that makes it worth tracking at
     * all — karaoke DSP itself only ever runs on this engine, never on
     * bit-perfect USB Direct, so this naturally only reports real data in
     * exactly the situation a caller would want it).
     * Defaults to "no data" (0f, "--"): UsbDirectPlaybackEngine never runs
     * DSP at all (consistent with staying bit-perfect), so it has nothing
     * real to report here rather than a fabricated reading.
     */
    fun detectedPitch(): Pair<Float, String> = 0f to "--"

    /**
     * Live signal level for the Now Playing meter. The AudioTrack engine measures what it
     * actually outputs (after DSP); USB Direct measures the bit-perfect stream it sends.
     */
    val levelMeter: com.windstrom5.rebit.playback.dsp.LevelMeter? get() = null

    /** Peak limiter gain reduction (dB, ≥ 0) since the previous call; 0 when no limiter runs. */
    fun takeLimiterReductionDb(): Float = 0f

    /**
     * Swaps the ReplayGain values mid-stream — used at a gapless track boundary, where the
     * engine keeps running but the next track has its own gain. No-op for engines that never
     * apply gain (USB Direct).
     */
    fun updateTrackGain(replayGainDb: Float?, trackPeak: Float?) {}

    /** Live native-USB link state; null for engines that don't drive USB directly. */
    fun usbLinkStats(): com.windstrom5.rebit.domain.model.UsbLinkStats? = null

    /** What the engine's DSP is doing right now, for the signal-path display. */
    fun processingSummary(): ProcessingSummary = ProcessingSummary()

    val dropoutCount: Int
}
