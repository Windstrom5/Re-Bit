package com.windstrom5.rebit.playback

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.util.Log
import com.windstrom5.rebit.benchmark.BenchmarkRunner
import com.windstrom5.rebit.data.settings.SettingsRepository
import com.windstrom5.rebit.domain.engine.AdaptiveOptimizationEngine
import com.windstrom5.rebit.domain.engine.PlaybackIntegrityEngine
import com.windstrom5.rebit.domain.model.AudioFileFormat
import com.windstrom5.rebit.domain.model.AudioTrackModel
import com.windstrom5.rebit.domain.model.BenchmarkSample
import com.windstrom5.rebit.domain.model.BufferMode
import com.windstrom5.rebit.domain.model.BufferRecommendation
import com.windstrom5.rebit.domain.model.DacProfile
import com.windstrom5.rebit.domain.model.EngineType
import com.windstrom5.rebit.domain.model.IntegrityResult
import com.windstrom5.rebit.domain.model.KaraokeModeType
import com.windstrom5.rebit.domain.model.LiveMetricPoint
import com.windstrom5.rebit.domain.model.PcmFormat
import com.windstrom5.rebit.domain.model.PlaybackConfig
import com.windstrom5.rebit.domain.model.PlaybackQueue
import com.windstrom5.rebit.domain.model.PlaybackSession
import com.windstrom5.rebit.domain.model.UsbTransferStrategy
import com.windstrom5.rebit.domain.model.PlaybackState
import com.windstrom5.rebit.domain.model.ReplayGainMode
import com.windstrom5.rebit.domain.repository.BenchmarkRepository
import com.windstrom5.rebit.usb.UsbDacManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger

/**
 * Application-scoped orchestrator tying together decoder selection, engine
 * selection, live integrity verification, adaptive buffer optimization, and
 * benchmark/session logging (Sections 3.4–3.7). Both the Player screen and
 * ExperimentOrchestrator drive playback through this one class, so the two
 * consumers can never observe inconsistent behavior.
 */
class PlaybackController(
    private val context: Context,
    private val usbDacManager: UsbDacManager,
    private val integrityEngine: PlaybackIntegrityEngine,
    private val optimizationEngine: AdaptiveOptimizationEngine,
    private val benchmarkRunner: BenchmarkRunner,
    private val benchmarkRepository: BenchmarkRepository,
    private val settingsRepository: SettingsRepository,
    private val vorbisCommentReader: FlacVorbisCommentReader,
    /** Turns stream:// paths (Qobuz/Tidal) into a local FLAC; null disables streaming. */
    private val streamResolver: com.windstrom5.rebit.streaming.StreamSourceResolver? = null
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    /** Finds a real, externally-separated stem for the current track — see its
     *  own doc comment. Constructed here (not injected) since it needs nothing
     *  but [context], the same pattern PlaybackIntegrityEngine etc. don't need. */
    private val externalStemResolver = com.windstrom5.rebit.playback.ai.ExternalStemResolver(context)
    private var monitorJob: Job? = null
    @Volatile private var currentEngine: PlaybackEngine? = null
    private var currentDecoder: AudioDecoder? = null
    /** Non-null while a gapless-capable session is playing — see [GaplessDecoder]. */
    @Volatile private var gapless: GaplessDecoder? = null
    private var prefetchJob: Job? = null
    /** Identifies the (current track -> next queue slot) pair already checked for prefetch, so an
     *  ineligible next track (different format, stream) isn't re-checked every tick. */
    private var prefetchCheckedKey: String? = null
    private var currentSessionId: Long? = null
    private val dropoutsThisTick = AtomicInteger(0)
    private var accumulator = SessionAccumulator()
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var audioFocusRequest: AudioFocusRequest? = null
    /** Epoch ms of the last PlaybackService.start() call; 0 if the service isn't
     *  currently expected to be running. Lets stop() guarantee the service a
     *  minimum lifetime — see the comment on MIN_SERVICE_LIFETIME_MS below. */
    private var serviceStartedAtMs: Long = 0L
    /** What seekTo() needs to restart the same track/engine/DAC at a new
     *  position — remembered here rather than requiring the caller to supply
     *  them again, the same way stop() needs no params from its callers. */
    private var lastTrack: AudioTrackModel? = null
    private var lastEngineType: EngineType = EngineType.CUSTOM_USB_DIRECT
    private var lastDac: DacProfile? = null
    private var lastSampleRateHz: Int = 0

    private val _clockReLockEvent = kotlinx.coroutines.flow.MutableSharedFlow<Pair<Int, Int>>(extraBufferCapacity = 1)
    val clockReLockEvent: kotlinx.coroutines.flow.SharedFlow<Pair<Int, Int>> = _clockReLockEvent

    /**
     * Sets the DAC's own hardware volume — see UsbDacManager.setHardwareVolume
     * for why this is the bit-perfect-safe path (the DAC attenuates; the PCM
     * samples this app sends stay untouched).
     *
     * [dac] is passed in rather than read from [lastDac] so this works with a
     * connected-but-idle DAC too — someone reasonably expects the volume
     * slider to work before pressing play, and lastDac is only populated once
     * playback has started. Falls back to lastDac when no profile is supplied.
     * Returns false rather than guessing when no analyzed profile is available
     * or the DAC exposes no volume Feature Unit at all.
     */
    fun setHardwareVolume(volumePercent: Float, dac: DacProfile? = null): Boolean {
        val device = usbDacManager.currentDevice ?: return false
        val profile = dac ?: lastDac ?: return false
        if (!profile.supportsHardwareVolume) return false
        val conn = usbDacManager.openConnection(device) ?: return false
        return try {
            usbDacManager.setHardwareVolume(conn, profile, volumePercent)
        } finally {
            try { conn.close() } catch (_: Exception) {}
        }
    }

    /** The track position (ms) this session's engine started counting from —
     *  0 for a fresh play, or the seek target when resuming from a seek. The
     *  monitor loop adds engine.currentPositionMs() (which always counts from
     *  0 per-session) to this to get an absolute track position. */
    @Volatile private var sessionStartPositionMs: Long = 0L

    private val _state = MutableStateFlow(PlaybackState())
    val state: StateFlow<PlaybackState> = _state

    private val _queue = MutableStateFlow(PlaybackQueue())
    val queue: StateFlow<PlaybackQueue> = _queue

    /** Library-driven entry point used by the Player screen. */
    suspend fun start(
        requestedTrack: AudioTrackModel,
        engineType: EngineType,
        dac: DacProfile?,
        initialPositionMs: Long = 0L
    ): Long {
        // seekTo() restarts from lastTrack, so keep the stream:// original — the
        // re-resolve it triggers is an instant cache hit, not a second download.
        lastTrack = requestedTrack
        val track = materializeStreamedTrack(requestedTrack)
        lastEngineType = engineType
        lastDac = dac

        val settings = settingsRepository.current
        val karaokeActive = (settings.karaokeModeEnabled || settings.aiVocalSuppressMode) &&
            settings.karaokeModeType != KaraokeModeType.OFF

        // Resolves the best available source for karaoke playback, in priority order:
        //  1. A real, externally-separated stem (tools/demucs_separator.py output,
        //     copied or pushed onto the phone) — genuinely perfect separation,
        //     bit-perfect with zero DSP, and the only tier that works through the
        //     Custom USB Direct engine (which never runs DSP by design).
        //  2. This app's own on-device pre-rendered cache (AiStemExtractor) — not
        //     real separation, but computed once instead of live every buffer.
        //  3. Neither found: falls through to live real-time DSP, which only
        //     AudioTrackPlaybackEngine applies (see its prepareWriteBuffer).
        var isExternalStudioStem = false
        val resolvedFile: Pair<String, AudioFileFormat>? = if (!karaokeActive) {
            null
        } else {
            val external = externalStemResolver.resolve(track, settings.karaokeModeType)
            if (external != null) {
                isExternalStudioStem = true
                external.uri to external.format
            } else {
                val stemDir = java.io.File(context.externalCacheDir ?: context.cacheDir, "stem_cache")
                val prefix = if (settings.karaokeModeType == KaraokeModeType.INSTRUMENTAL_ONLY) "instrumental" else "acapella"
                val file = java.io.File(stemDir, "stem_${track.id}_$prefix.wav")
                if (file.exists() && file.length() > 44) {
                    android.net.Uri.fromFile(file).toString() to AudioFileFormat.WAV
                } else null
            }
        }

        val decoder: AudioDecoder
        val effectiveFilePath: String
        if (resolvedFile != null) {
            val (uri, format) = resolvedFile
            decoder = AudioDecoders.create(format, context)
            effectiveFilePath = uri
            Log.i(
                TAG,
                if (isExternalStudioStem) "Routing playback to external studio-separated stem (bit-perfect, zero DSP): $uri"
                else "Routing playback to on-device pre-rendered stem cache: $uri"
            )
        } else {
            decoder = AudioDecoders.create(track.format, context, dsdOutputFor(track, engineType, dac))
            effectiveFilePath = track.filePath
        }

        // Expected total is the *decoded PCM* byte count, not the on-disk file size —
        // those match closely for WAV but diverge a lot for FLAC, which is compressed.
        val bytesPerSecond = track.pcm.sampleRateHz * track.pcm.channels * (track.pcm.bitDepth / 8).coerceAtLeast(1)
        val expectedPcmBytes = (track.durationMs / 1000.0 * bytesPerSecond).toLong().takeIf { it > 0L }

        val effectiveBufferSize = if (settings.bufferMode == BufferMode.MANUAL) {
            settings.manualBufferSizeBytes
        } else {
            PlaybackConfig.DEFAULT_BUFFER_BYTES
        }

        return startWithDecoder(
            decoder, effectiveFilePath, engineType, dac, track.id.takeIf { it != 0L },
            initialBufferSizeBytes = effectiveBufferSize,
            track = track, totalBytesExpected = expectedPcmBytes,
            respectBufferMode = true,
            initialPositionMs = initialPositionMs,
            isPlayingCachedStem = (resolvedFile != null),
            isPlayingExternalStudioStem = isExternalStudioStem
        )
    }

    /**
     * For stream:// tracks, buffers the remote FLAC and swaps in the local file URI
     * and the PCM format the service actually delivered; every other track passes
     * through untouched, so local playback behaves exactly as before.
     */
    private suspend fun materializeStreamedTrack(t: AudioTrackModel): AudioTrackModel {
        if (!com.windstrom5.rebit.streaming.StreamSourceResolver.isStreamUri(t.filePath)) return t
        val resolver = streamResolver
            ?: throw IllegalStateException("Streaming is not available in this build.")
        val resolved = resolver.resolve(t.filePath)
        return t.copy(filePath = resolved.fileUri, pcm = resolved.pcm, format = AudioFileFormat.FLAC)
    }

    /**
     * Jumps the current track to [positionMs]. Implemented as a full
     * stop-then-restart at the new position rather than seeking a live
     * decoder in place — the engine's playback thread is continuously
     * calling decoder.read() on its own thread, and seeking that same
     * decoder concurrently from here would reproduce the exact kind of
     * MediaCodec teardown race this app already hit once (see FlacDecoder's
     * isClosed handling). Stopping first (which now correctly waits for that
     * thread to actually exit before returning) makes seeking single-threaded
     * and safe, at the cost of a brief stop/restart instead of a seamless
     * jump — a fine trade for correctness over a still-fairly-fast seek.
     */
    suspend fun seekTo(positionMs: Long) {
        val track = lastTrack ?: return
        val clamped = positionMs.coerceIn(0L, track.durationMs.takeIf { it > 0L } ?: positionMs)
        start(track, lastEngineType, lastDac, initialPositionMs = clamped)
    }

    /**
     * Captures [tracks] (typically whatever list a track was tapped from — the
     * Library screen's currently filtered/sorted view) as the queue Next/Previous
     * will walk, with [startAt] as the starting point. Does not itself start
     * playback — call [start] separately, same as before. Re-tapping any track
     * from any list replaces the queue; nothing sets it automatically.
     */
    fun setQueue(tracks: List<AudioTrackModel>, startAt: AudioTrackModel) {
        val startIndex = tracks.indexOfFirst { it.id == startAt.id }.let { if (it < 0) 0 else it }
        val shuffle = _queue.value.shuffleEnabled
        val order = buildPlayOrder(tracks.size, startIndex, shuffle)
        _queue.value = PlaybackQueue(
            tracks = tracks,
            playOrder = order,
            playPosition = order.indexOf(startIndex).let { if (it < 0) 0 else it },
            shuffleEnabled = shuffle
        )
        discardPrefetchedNext()
    }

    /**
     * Pauses without discarding anything needed to resume — implemented as a
     * full engine stop (same underlying mechanism as [seekTo]'s stop-then-restart,
     * for the same single-threaded-decoder-safety reason) that deliberately
     * leaves currentTrack/positionMs alone so [resume] knows exactly where to
     * restart from. A no-op if nothing is currently playing.
     *
     * IMPORTANT: uses `isPausedOverride = true` so stop() writes
     * `isPaused = true` atomically in its own state update rather than
     * setting it on the line after — this eliminates the transient
     * `isPlaying=false, isPaused=false` window that caused Compose to
     * recompose into the "else → replayCurrentTrack()" branch and
     * restart playback behind the user's back.
     */
    suspend fun pause() {
        if (!_state.value.isPlaying) return
        stop(stopService = false, isPausedOverride = true)
    }

    /** Resumes from wherever [pause] left off. A no-op if not currently paused. */
    suspend fun resume() {
        if (!_state.value.isPaused) return
        val track = lastTrack ?: return
        start(track, lastEngineType, lastDac, initialPositionMs = _state.value.positionMs)
    }

    suspend fun nextTrack() = advanceQueue(+1)

    suspend fun previousTrack() = advanceQueue(-1)

    /**
     * Toggles shuffle without interrupting whatever's currently playing — the
     * playing track is kept at the front of the new order either way, so this
     * only changes what Next/Previous visit from here on, not what's audible
     * right now.
     */
    fun toggleShuffle() {
        val q = _queue.value
        if (!q.hasQueue) {
            _queue.value = q.copy(shuffleEnabled = !q.shuffleEnabled)
            return
        }
        val newShuffle = !q.shuffleEnabled
        val currentQueueIndex = q.playOrder[q.playPosition]
        val newOrder = buildPlayOrder(q.tracks.size, currentQueueIndex, newShuffle)
        _queue.value = q.copy(
            playOrder = newOrder,
            playPosition = newOrder.indexOf(currentQueueIndex).let { if (it < 0) 0 else it },
            shuffleEnabled = newShuffle
        )
        discardPrefetchedNext()
    }

    private suspend fun advanceQueue(direction: Int) {
        val q = _queue.value
        if (!q.hasQueue) return
        val newPosition = (q.playPosition + direction + q.playOrder.size) % q.playOrder.size
        val queueIndex = q.playOrder[newPosition]
        val track = q.tracks.getOrNull(queueIndex) ?: return
        _queue.value = q.copy(playPosition = newPosition)
        start(track, lastEngineType, lastDac)
    }

    /** Called when a track finishes naturally (not a manual stop) — advances to
     *  whatever's next in the queue if there's genuinely more than one track
     *  queued AND this was a real queue-eligible session, otherwise stops
     *  exactly as before. Both conditions matter: hasMultiple alone isn't
     *  enough, because ExperimentOrchestrator's synthetic-tone sweeps call
     *  startWithDecoder directly with no AudioTrackModel (so currentTrack is
     *  null) — without the currentTrack check, a sweep finishing naturally
     *  while a multi-track queue happened to be left over from a previous
     *  Library session would wrongly auto-advance into a real track
     *  mid-experiment. */
    private suspend fun onTrackFinished() {
        if (_queue.value.hasMultiple && _state.value.currentTrack != null) {
            advanceQueue(+1)
        } else {
            stop()
        }
    }

    /** Identity order with shuffle off; a shuffled permutation with [startIndex]
     *  kept first (so enabling shuffle never interrupts what's currently
     *  playing) with shuffle on. */
    private fun buildPlayOrder(size: Int, startIndex: Int, shuffle: Boolean): List<Int> {
        if (size <= 0) return emptyList()
        val indices = (0 until size).toMutableList()
        if (!shuffle) return indices
        indices.remove(startIndex)
        indices.shuffle()
        return listOf(startIndex) + indices
    }

    /**
     * Lower-level entry point used by both [start] and ExperimentOrchestrator: takes an
     * already-selected decoder (real file or [SyntheticToneDecoder]) so experiment sweeps
     * can hold the signal source constant while varying engine/format/buffer size.
     */
    suspend fun startWithDecoder(
        decoder: AudioDecoder,
        sourceUri: String,
        engineType: EngineType,
        dac: DacProfile?,
        trackId: Long? = null,
        initialBufferSizeBytes: Int = PlaybackConfig.DEFAULT_BUFFER_BYTES,
        track: AudioTrackModel? = null,
        totalBytesExpected: Long? = null,
        /** True only for regular listening (see [start]) — governs whether the
         *  AdaptiveOptimizationEngine is allowed to resize the buffer mid-session
         *  per Settings > Buffer Mode. Experiment sweeps always ignore this and
         *  stay adaptive, since they need reproducible, uniform conditions across
         *  every config regardless of what the person has personally pinned. */
        respectBufferMode: Boolean = false,
        /** Non-zero when this session is resuming from a seek rather than
         *  starting fresh — see [seekTo]. Applied to the decoder right after
         *  open(), before the engine ever starts reading from it. */
        initialPositionMs: Long = 0L,
        isPlayingCachedStem: Boolean = false,
        isPlayingExternalStudioStem: Boolean = false
    ): Long {
        stop(stopService = false) // ensure a clean slate (leave the foreground service running — we're about to restart it below anyway)

        // decoder.open() does blocking I/O and, for FLAC, a MediaCodec warm-up loop
        // that can take up to ~1s worst case. This function is called directly from
        // LibraryViewModel's viewModelScope (Dispatchers.Main.immediate), so without
        // this withContext, that work — plus stop()'s own blocking teardown above —
        // runs straight on the UI thread. That's what was starving the Looper right
        // when PlaybackService.start() below needs it free to dispatch
        // onStartCommand() and call startForeground() before Android's timeout,
        // which is what was crashing the app with ForegroundServiceDidNotStartInTimeException.
        val actualFormat = try {
            withContext(Dispatchers.IO) {
                val format = decoder.open(sourceUri)
                // Seeking here — after open(), before the engine's playback thread
                // ever starts calling read() — is what keeps this single-threaded
                // and safe. See AudioDecoder.seek's doc for why concurrent seek
                // would be unsafe with the MediaCodec/jflac decoders underneath.
                if (initialPositionMs > 0L) decoder.seek(initialPositionMs)
                if (lastSampleRateHz > 0 && lastSampleRateHz != format.sampleRateHz) {
                    _clockReLockEvent.tryEmit(lastSampleRateHz to format.sampleRateHz)
                }
                lastSampleRateHz = format.sampleRateHz
                format
            }

        } catch (e: Exception) {
            // This is the failure that was previously invisible: if opening the
            // decoder throws here, it used to propagate straight back to whichever
            // screen called startWithDecoder (e.g. LibraryViewModel.playTrack's own
            // try/catch) — but by the time that catch runs, the person has already
            // navigated to the Player screen, which reads a *different* error source
            // (this controller's own _state.error) and never saw it. Setting it here
            // means it shows up wherever playback state is actually being observed.
            Log.e(TAG, "Failed to open decoder for $sourceUri", e)
            _state.value = _state.value.copy(
                isPlaying = false,
                error = e.message ?: "Couldn't open this track."
            )
            currentDecoder = null
            throw e
        }
        currentDecoder = decoder

        // ReplayGain/Normalization's data source — real Vorbis-comment tags read
        // from the file, not computed. Skipped for the synthetic-tone URIs
        // ExperimentOrchestrator/DacRateBenchmark use (nothing to read there, and
        // FlacVorbisCommentReader would just fail the contentResolver.openInputStream
        // call anyway — this just avoids that pointless attempt).
        val replayGainTags = if (!sourceUri.startsWith("synthetic://")) {
            withContext(Dispatchers.IO) { vorbisCommentReader.read(sourceUri) }
        } else {
            FlacVorbisCommentReader.ReplayGainTags(null, null)
        }
        val (replayGainDb, replayGainPeak) =
            replayGainTags.select(album = settingsRepository.current.replayGainMode == ReplayGainMode.ALBUM)

        var activeEngineType = engineType
        val engine: PlaybackEngine = if (engineType == EngineType.CUSTOM_USB_DIRECT) {
            val device = usbDacManager.currentDevice
            if (device == null || dac == null) {
                Log.w(TAG, "No USB DAC attached or analyzed profile available. Falling back to AudioTrack engine.")
                activeEngineType = EngineType.ANDROID_AUDIOTRACK
                AudioTrackPlaybackEngine(context, settingsRepository).apply {
                    this.isPlayingCachedStem = isPlayingCachedStem
                }
            } else {
                try {
                    buildUsbDirectEngine(
                        actualFormat, dac, settingsRepository.current.usbRequestPoolSize, settingsRepository.current.usbTransferStrategy
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to initialize USB Direct engine. Falling back to AudioTrack engine.", e)
                    activeEngineType = EngineType.ANDROID_AUDIOTRACK
                    AudioTrackPlaybackEngine(context, settingsRepository).apply {
                        this.isPlayingCachedStem = isPlayingCachedStem
                    }
                }
            }
        } else {
            AudioTrackPlaybackEngine(context, settingsRepository).apply {
                this.isPlayingCachedStem = isPlayingCachedStem
            }
        }

        val integrity = integrityEngine.evaluate(actualFormat, dac, activeEngineType)
        var bufferSize = initialBufferSizeBytes
        val isManualBuffer = respectBufferMode && settingsRepository.current.bufferMode == BufferMode.MANUAL

        currentEngine = engine
        accumulator = SessionAccumulator()
        benchmarkRunner.resetForNewRun()

        val sessionId = benchmarkRepository.startSession(
            PlaybackSession(
                trackId = trackId,
                dacProfileId = dac?.id,
                engineType = activeEngineType,
                startEpochMs = System.currentTimeMillis(),
                endEpochMs = null,
                integrityScore = integrity.score,
                avgLatencyMs = 0.0,
                avgCpuPercent = 0.0,
                avgMemoryMb = 0.0,
                dropoutCount = 0,
                bufferSizeBytes = bufferSize
            )
        )
        currentSessionId = sessionId

        sessionStartPositionMs = initialPositionMs
        _state.value = PlaybackState(
            isPlaying = true,
            currentTrack = track,
            positionMs = initialPositionMs,
            engineType = activeEngineType,
            bufferSizeBytes = bufferSize,
            integrity = integrity,
            totalBytesExpected = totalBytesExpected,
            isPlayingExternalStudioStem = isPlayingExternalStudioStem
        )

        try {
            PlaybackService.start(context)
            serviceStartedAtMs = System.currentTimeMillis()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to start PlaybackService: ${e.message}")
            serviceStartedAtMs = 0L
        }
        requestAudioFocus()

        // Gapless: only for real library listening (not experiment sweeps or karaoke stems),
        // where a queue exists to continue into.
        val streamDecoder: AudioDecoder =
            if (track != null && respectBufferMode && !isPlayingCachedStem && settingsRepository.current.gaplessEnabled) {
                GaplessDecoder(decoder, actualFormat) { prepared -> onGaplessTransition(prepared, engine) }
                    .also { gapless = it; currentDecoder = it }
            } else {
                decoder
            }
        prefetchCheckedKey = null

        engine.start(
            decoder = streamDecoder,
            format = actualFormat,
            bufferSizeBytes = bufferSize,
            onDropout = {
                dropoutsThisTick.incrementAndGet()
                benchmarkRunner.dropoutDetector.onDropout()
            },
            onCompletion = { scope.launch { onTrackFinished() } },
            onError = { throwable -> scope.launch { handleEngineError(throwable) } },
            replayGainDb = replayGainDb,
            trackPeak = replayGainPeak
        )

        monitorJob = scope.launch {
            val tickStartMs = System.currentTimeMillis()
            val history = ArrayDeque<LiveMetricPoint>()
            var previousTransmittedBytes = 0L

            while (isActive) {
                try {
                    delay(TICK_INTERVAL_MS)
                    val cpu = benchmarkRunner.sampleCpuPercent()
                    val mem = benchmarkRunner.sampleMemoryMb()
                    val latency = engine.currentLatencyEstimateMs()
                    val dropoutsSinceLastTick = dropoutsThisTick.getAndSet(0)

                    benchmarkRunner.latencyMonitor.record(latency)
                    accumulator.record(cpu, mem, latency, dropoutsSinceLastTick)

                    val stability = optimizationEngine.tick(dropoutsSinceLastTick, latency, bufferSize)
                    if (!isManualBuffer && stability.recommendation != BufferRecommendation.MAINTAIN) {
                        bufferSize = stability.recommendedBufferSizeBytes
                        engine.setBufferSize(bufferSize)
                    }

                    try {
                        benchmarkRepository.recordSample(
                            sessionId,
                            BenchmarkSample(
                                timestampMs = System.currentTimeMillis(),
                                cpuPercent = cpu,
                                memoryMb = mem,
                                latencyMs = latency,
                                cumulativeDropouts = accumulator.totalDropouts,
                                engineType = engineType
                            )
                        )
                    } catch (dbEx: Exception) {
                        Log.e("PlaybackController", "Failed to record sample to database", dbEx)
                    }

                    val elapsedSeconds = (System.currentTimeMillis() - tickStartMs) / 1000.0
                    history.addLast(LiveMetricPoint(elapsedSeconds, latency, cpu))
                    while (history.size > MAX_HISTORY_POINTS) history.removeFirst()

                    val verification = engine.verificationSnapshot()
                    val transferRate = ((verification.transmittedBytes - previousTransmittedBytes) / (TICK_INTERVAL_MS / 1000.0))
                        .coerceAtLeast(0.0)
                    previousTransmittedBytes = verification.transmittedBytes
                    val traffic = engine.trafficLog()

                    var positionMs = (sessionStartPositionMs + engine.currentPositionMs()).coerceAtLeast(0L)
                    // The playing track can change under this loop (gapless), so read it fresh.
                    val playingTrack = _state.value.currentTrack
                    playingTrack?.durationMs?.let { duration ->
                        if (duration > 0L) positionMs = positionMs.coerceAtMost(duration)
                    }
                    val (pitchHz, pitchNote) = engine.detectedPitch()
                    if (playingTrack != null) maybePrefetchNext(playingTrack, positionMs)
                    val processing = engine.processingSummary()
                    val usbLink = engine.usbLinkStats()
                    val limiterDb = engine.takeLimiterReductionDb()

                    // update{} rather than value = value.copy(): a gapless transition on the
                    // playback thread may be writing currentTrack at the same moment.
                    _state.update { it.copy(
                        bufferSizeBytes = bufferSize,
                        stability = stability,
                        liveLatencyMs = latency,
                        liveCpuPercent = cpu,
                        liveMemoryMb = mem,
                        recentHistory = history.toList(),
                        verification = verification,
                        trafficLog = traffic,
                        transferRateBytesPerSec = transferRate,
                        positionMs = positionMs,
                        detectedPitchHz = pitchHz,
                        detectedMusicalNote = pitchNote,
                        processing = processing,
                        limiterReductionDb = limiterDb,
                        usbLink = usbLink
                    ) }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e("PlaybackController", "Error in monitor loop", e)
                }
            }
        }

        return sessionId
    }

    /**
     * @param stopService Whether to also tear down the actual Android Service.
     *   Pass false when this is being called from [startWithDecoder] to clear
     *   state before immediately starting a new track — stopping the service
     *   there and then starting it again moments later (stopService()
     *   immediately followed by startForegroundService() for the same
     *   component) is exactly the pattern that produces
     *   ForegroundServiceDidNotStartInTimeException: the stop can land at
     *   ActivityManagerService in a way that cancels the pending foreground
     *   promotion before onStartCommand() ever gets to call startForeground().
     *   There's no need to tear it down at all when we're about to restart it
     *   for the next track anyway — startForegroundService() on an
     *   already-running instance just redelivers onStartCommand(), which is
     *   exactly what we want.
     */
    /**
     * @param isPausedOverride When true (set only by [pause]), the final state
     *   update writes `isPaused = true` atomically alongside `isPlaying = false`,
     *   preventing a transient frame where both are false — that brief window
     *   was causing Compose to hit the `else → replayCurrentTrack()` branch
     *   and restart playback behind the user's back.
     */
    suspend fun stop(stopService: Boolean = true, isPausedOverride: Boolean = false) {
        // Immediately mark as not-playing so any concurrent Compose recompose
        // sees the correct state even before the engine is fully torn down.
        _state.value = _state.value.copy(
            isPlaying = false,
            isPaused = isPausedOverride
        )

        monitorJob?.cancel()
        monitorJob = null
        prefetchJob?.cancel()
        prefetchJob = null
        gapless = null
        // Capture the final verdict before the engine is torn down — this is the
        // most meaningful comparison point, since source/transmitted byte counts
        // reliably converge at end of track.
        val finalVerification = currentEngine?.verificationSnapshot()
        withContext(Dispatchers.IO) {
            currentEngine?.stop()
            currentDecoder?.close()
        }

        currentSessionId?.let { sessionId ->
            benchmarkRepository.endSession(
                sessionId = sessionId,
                endEpochMs = System.currentTimeMillis(),
                avgLatencyMs = accumulator.avgLatency(),
                avgCpuPercent = accumulator.avgCpu(),
                avgMemoryMb = accumulator.avgMemory(),
                dropoutCount = accumulator.totalDropouts,
                verifiedBitPerfect = finalVerification?.verified
            )
        }
        currentSessionId = null
        currentEngine = null
        currentDecoder = null
        if (stopService) {
            // Guarantee the service a minimum lifetime before stopping it. Without
            // this, anything that makes playback end very quickly after it started
            // — a track that fails to decode almost instantly (as seen on some
            // MediaTek codec builds), a very short file, or rapid track-switching —
            // can call stopService() while startForegroundService()'s
            // onStartCommand()/startForeground() is still pending. That specific
            // ordering is what throws ForegroundServiceDidNotStartInTimeException
            // and kills the whole app. This is an AOSP-level timing requirement,
            // not a MediaTek-specific one, so the same race can happen on any
            // chipset/OEM skin (Snapdragon, Exynos, Tensor, etc.) — the guard has
            // to be unconditional, not gated on device type.
            val elapsedSinceServiceStart = System.currentTimeMillis() - serviceStartedAtMs
            if (serviceStartedAtMs > 0L && elapsedSinceServiceStart < MIN_SERVICE_LIFETIME_MS) {
                delay(MIN_SERVICE_LIFETIME_MS - elapsedSinceServiceStart)
            }
            serviceStartedAtMs = 0L
            try {
                PlaybackService.stop(context)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to stop PlaybackService: ${e.message}")
            }
        }
        abandonAudioFocus()
        // Final state write — preserves isPausedOverride if set, otherwise
        // clears isPaused (genuine stop while paused, or a seek restart).
        _state.value = _state.value.copy(
            isPlaying = false,
            isPaused = isPausedOverride,
            verification = finalVerification ?: _state.value.verification,
            gaplessNextReady = false,
            limiterReductionDb = 0f
        )
    }

    /**
     * DoP (bit-perfect DSD) only when it can actually reach a DoP DAC: USB Direct, DoP or native
     * DSD selected, and the analyzed DAC offers 24-bit (or 24-in-32) PCM at the DoP carrier rate
     * (DSD rate / 16). Everything else gets DSD converted to high-resolution PCM.
     */
    private fun dsdOutputFor(track: AudioTrackModel, engineType: EngineType, dac: DacProfile?): DsdDecoder.Output {
        if (!track.format.isDsd) return DsdDecoder.Output.PCM
        val mode = settingsRepository.current.dsdMode
        if (engineType != EngineType.CUSTOM_USB_DIRECT || dac == null || usbDacManager.currentDevice == null) return DsdDecoder.Output.PCM
        if (mode == com.windstrom5.rebit.domain.model.DsdPlaybackMode.PCM_CONVERT) return DsdDecoder.Output.PCM
        val carrier = PcmFormat(track.pcm.sampleRateHz / 16, 24, track.pcm.channels)
        val ok = dac.findExactStreamingOption(carrier, settingsRepository.current.usbProtocolPreference) != null
        return if (ok) DsdDecoder.Output.DOP else DsdDecoder.Output.PCM
    }

    /** Recent output samples for the spectrum analyzer; returns frames copied (0 when idle). */
    fun recentSamples(outL: FloatArray, outR: FloatArray): Int =
        currentEngine?.levelMeter?.copyRecent(outL, outR) ?: 0

    /** Output rate of the running stream, for the analyzer's frequency axis. */
    fun outputSampleRateHz(): Int =
        currentEngine?.levelMeter?.sampleRateHz?.takeIf { it > 0 } ?: lastSampleRateHz

    /** Latest output level from the running engine, polled by the Now Playing meter. */
    fun levelSnapshot(): com.windstrom5.rebit.playback.dsp.LevelMeter.Levels? =
        currentEngine?.levelMeter?.snapshot()

    // -- Gapless ---------------------------------------------------------------------------

    /** What a primed next track carries through [GaplessDecoder] to [onGaplessTransition]. */
    private class GaplessTag(
        val track: AudioTrackModel,
        val playPosition: Int,
        val queueTracks: List<AudioTrackModel>,
        val tags: FlacVorbisCommentReader.ReplayGainTags
    )

    /**
     * Opens and primes the next queued track [PREFETCH_WINDOW_MS] before the current one ends,
     * so the splice at end of stream costs nothing on the playback thread. Only same-format,
     * local tracks qualify; anything else ends normally and goes through [onTrackFinished].
     */
    private fun maybePrefetchNext(current: AudioTrackModel, positionMs: Long) {
        val g = gapless ?: return
        if (g.hasPendingNext || prefetchJob?.isActive == true) return
        if (current.durationMs <= 0L || current.durationMs - positionMs > PREFETCH_WINDOW_MS) return
        val q = _queue.value
        if (!q.hasMultiple) return
        val nextPosition = (q.playPosition + 1) % q.playOrder.size
        val next = q.tracks.getOrNull(q.playOrder[nextPosition]) ?: return

        val key = "${current.filePath}|$nextPosition|${System.identityHashCode(q.tracks)}"
        if (key == prefetchCheckedKey) return
        prefetchCheckedKey = key
        if (com.windstrom5.rebit.streaming.StreamSourceResolver.isStreamUri(next.filePath)) return
        if (next.pcm != g.streamFormat) {
            Log.i(TAG, "Gapless: next track is ${next.pcm}, stream is ${g.streamFormat}; output will restart instead")
            return
        }

        prefetchJob = scope.launch(Dispatchers.IO) {
            val decoder: AudioDecoder = AudioDecoders.create(next.format, context, dsdOutputFor(next, lastEngineType, lastDac))
            try {
                val format = decoder.open(next.filePath)
                if (format != g.streamFormat) {
                    decoder.close()
                    return@launch
                }
                val tags = vorbisCommentReader.read(next.filePath)
                val prepared = GaplessDecoder.Prepared(decoder, format, GaplessTag(next, nextPosition, q.tracks, tags))
                if (gapless !== g || !g.offerNext(prepared)) {
                    decoder.close()
                    return@launch
                }
                if (gapless !== g) {
                    // Stopped while we were offering: reclaim and close it.
                    g.withdrawNext()?.let { runCatching { it.decoder.close() } }
                    return@launch
                }
                _state.update { it.copy(gaplessNextReady = true) }
                Log.i(TAG, "Gapless: primed '${next.title}'")
            } catch (e: Exception) {
                Log.w(TAG, "Gapless: couldn't prime next track (${e.message})")
                runCatching { decoder.close() }
            }
        }
    }

    /** Runs on the engine's playback thread at the exact sample where the next track begins. */
    private fun onGaplessTransition(prepared: GaplessDecoder.Prepared, engine: PlaybackEngine) {
        val tag = prepared.tag as? GaplessTag ?: return
        sessionStartPositionMs = -engine.currentPositionMs()
        lastTrack = tag.track
        val album = settingsRepository.current.replayGainMode == ReplayGainMode.ALBUM
        val (db, peak) = tag.tags.select(album)
        engine.updateTrackGain(db, peak)
        _queue.update { q -> if (q.tracks === tag.queueTracks) q.copy(playPosition = tag.playPosition) else q }
        val pcm = tag.track.pcm
        val bytesPerSecond = pcm.sampleRateHz * pcm.channels * (pcm.bitDepth / 8).coerceAtLeast(1)
        _state.update {
            it.copy(
                currentTrack = tag.track,
                positionMs = 0L,
                totalBytesExpected = (tag.track.durationMs / 1000.0 * bytesPerSecond).toLong().takeIf { b -> b > 0L },
                gaplessNextReady = false,
                gaplessTransitions = it.gaplessTransitions + 1
            )
        }
        Log.i(TAG, "Gapless: now playing '${tag.track.title}'")
    }

    /** Drops a primed next track that no longer matches what Next would play. */
    private fun discardPrefetchedNext() {
        prefetchJob?.cancel()
        prefetchCheckedKey = null
        gapless?.withdrawNext()?.let { runCatching { it.decoder.close() } }
        _state.update { it.copy(gaplessNextReady = false) }
    }

    private fun requestAudioFocus() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build()
                    )
                    .setAcceptsDelayedFocusGain(true)
                    .setOnAudioFocusChangeListener { focusChange ->
                        Log.d(TAG, "Audio focus changed: $focusChange")
                    }
                    .build()
                audioFocusRequest = focusRequest
                audioManager.requestAudioFocus(focusRequest)
            } else {
                @Suppress("DEPRECATION")
                audioManager.requestAudioFocus(
                    { focusChange -> Log.d(TAG, "Audio focus changed: $focusChange") },
                    AudioManager.STREAM_MUSIC,
                    AudioManager.AUDIOFOCUS_GAIN
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to request AudioFocus: ${e.message}")
        }
    }

    private fun abandonAudioFocus() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
                audioFocusRequest = null
            } else {
                @Suppress("DEPRECATION")
                audioManager.abandonAudioFocus({ /* no-op */ })
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to abandon AudioFocus: ${e.message}")
        }
    }

    fun verifyIntegrity(format: PcmFormat, dac: DacProfile?, engineType: EngineType): IntegrityResult =
        integrityEngine.evaluate(format, dac, engineType)

    /**
     * Handles a failure surfaced from a background playback thread (see
     * PlaybackEngine.onError). This is the difference between "the app shows an
     * error message" and "the app disappears" — an uncaught exception on a raw
     * background Thread otherwise terminates the whole process with no recovery
     * chance at all, which is exactly the crash this exists to prevent.
     */
    private suspend fun handleEngineError(throwable: Throwable) {
        Log.e(TAG, "Playback engine failed", throwable)
        val message = throwable.message?.takeIf { it.isNotBlank() }
            ?: "Playback stopped unexpectedly (${throwable::class.simpleName})."
        stop()
        _state.value = _state.value.copy(error = message)
    }

    private fun buildUsbDirectEngine(
        format: PcmFormat,
        dac: DacProfile?,
        requestPoolSize: Int,
        transferStrategy: UsbTransferStrategy
    ): UsbDirectPlaybackEngine {
        val device = usbDacManager.currentDevice ?: error("No USB DAC attached for the custom engine.")
        val profile = dac ?: error("This DAC hasn't been analyzed yet — visit the DAC tab and tap Analyze Capabilities first.")
        val connection = usbDacManager.openConnection(device) ?: error("USB permission not granted for this DAC.")
        val settings = settingsRepository.current
        val protocolPreference = settings.usbProtocolPreference

        // First try an exact match (same rate + bit depth + channels). If the DAC's analyzed
        // profile doesn't have the exact combination, try best-effort (same bit depth, nearest
        // rate) before giving up. Logging makes clear which path was taken — a format log in
        // logcat is the fastest way to diagnose a "plays but wrong rate" situation.
        val exactOption = profile.findExactStreamingOption(format, protocolPreference)
        val activated: com.windstrom5.rebit.usb.ActivatedStreamingEndpoint = if (exactOption != null) {
            Log.i(TAG, "USB Direct: exact format match ${format.sampleRateHz}Hz/${format.bitDepth}-bit/${format.channels}ch → " +
                "altsetting ${exactOption.alternateSetting} on interface ${exactOption.interfaceNumber}" +
                (if (protocolPreference != com.windstrom5.rebit.domain.model.UsbProtocolPreference.AUTO) " (protocol preference: $protocolPreference)" else ""))
            usbDacManager.activateStreamingOption(device, connection, profile, format, protocolPreference)
                ?: run {
                    connection.close()
                    error("USB Direct: failed to activate exact-match altsetting for ${format.sampleRateHz}Hz/${format.bitDepth}-bit.")
                }
        } else {
            val bestOption = profile.findBestStreamingOption(format, protocolPreference)
            if (bestOption == null) {
                connection.close()
                error(
                    "No streaming option for ${format.channels}ch found on this DAC. " +
                        "Re-run Analyze Capabilities or check the DAC's USB Audio Class descriptors."
                )
            }
            val bestRate = bestOption.supportedSampleRates.minByOrNull { kotlin.math.abs(it - format.sampleRateHz) }
                ?: bestOption.supportedSampleRates.first()
            Log.w(TAG, "USB Direct: no exact match for ${format.sampleRateHz}Hz/${format.bitDepth}-bit. " +
                "Best-effort: using altsetting ${bestOption.alternateSetting} " +
                "(${bestOption.bitDepth}-bit, rate=${bestRate}Hz). " +
                "Re-run Analyze Capabilities if the DAC should support ${format.sampleRateHz}Hz natively.")
            val bestFormat = PcmFormat(bestRate, bestOption.bitDepth, format.channels)
            usbDacManager.activateStreamingOption(device, connection, profile, bestFormat, protocolPreference)
                ?: run {
                    connection.close()
                    error("USB Direct: failed to activate best-effort altsetting for ${bestOption.bitDepth}-bit/${bestRate}Hz.")
                }
        }

        return UsbDirectPlaybackEngine(
            usbDacManager, device, connection, activated, requestPoolSize, transferStrategy,
            useAdaptiveClockCorrection = settings.useAdaptiveClockCorrection,
            settingsRepository = settingsRepository
        )
    }


    private class SessionAccumulator {
        private var cpuSum = 0.0
        private var memSum = 0.0
        private var latencySum = 0.0
        private var tickCount = 0
        var totalDropouts = 0
            private set

        fun record(cpu: Double, mem: Double, latency: Double, dropoutsThisTick: Int) {
            cpuSum += cpu
            memSum += mem
            latencySum += latency
            tickCount++
            totalDropouts += dropoutsThisTick
        }

        fun avgCpu() = if (tickCount > 0) cpuSum / tickCount else 0.0
        fun avgMemory() = if (tickCount > 0) memSum / tickCount else 0.0
        fun avgLatency() = if (tickCount > 0) latencySum / tickCount else 0.0
    }

    companion object {
        private const val TAG = "PlaybackController"
        private const val TICK_INTERVAL_MS = 500L
        private const val MAX_HISTORY_POINTS = 60 // ~30 seconds at the 500ms tick interval
        // Comfortably above what onStartCommand()->startForeground() needs even on a
        // stalled/loaded main thread or a slower OEM skin; short enough to be
        // imperceptible on a manual stop.
        private const val MIN_SERVICE_LIFETIME_MS = 600L
        /** How long before the end of a track the next one is opened for a gapless splice. */
        private const val PREFETCH_WINDOW_MS = 15_000L
    }
}
