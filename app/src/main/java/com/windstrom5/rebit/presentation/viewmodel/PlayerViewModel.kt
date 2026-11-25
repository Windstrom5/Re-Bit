package com.windstrom5.rebit.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.windstrom5.rebit.data.repository.LyricsRepository
import com.windstrom5.rebit.data.settings.SettingsRepository
import com.windstrom5.rebit.domain.model.AbLoopState
import com.windstrom5.rebit.domain.model.DacProfile
import com.windstrom5.rebit.domain.model.EngineType
import com.windstrom5.rebit.domain.model.LyricsData
import com.windstrom5.rebit.domain.model.LyricsProvider
import com.windstrom5.rebit.domain.model.LyricsSearchResult
import com.windstrom5.rebit.domain.model.PlaybackQueue
import com.windstrom5.rebit.domain.model.PlaybackState
import com.windstrom5.rebit.domain.model.SleepTimerState
import com.windstrom5.rebit.domain.model.UsbTransferStrategy
import com.windstrom5.rebit.domain.usecase.NextTrackUseCase
import com.windstrom5.rebit.domain.usecase.ObserveDacProfilesUseCase
import com.windstrom5.rebit.domain.usecase.ObservePlaybackStateUseCase
import com.windstrom5.rebit.domain.usecase.ObserveQueueUseCase
import com.windstrom5.rebit.domain.usecase.PausePlaybackUseCase
import com.windstrom5.rebit.domain.usecase.PreviousTrackUseCase
import com.windstrom5.rebit.domain.usecase.ResumePlaybackUseCase
import com.windstrom5.rebit.domain.usecase.SeekPlaybackUseCase
import com.windstrom5.rebit.domain.usecase.SetQueueUseCase
import com.windstrom5.rebit.domain.usecase.StartPlaybackUseCase
import com.windstrom5.rebit.domain.usecase.StopPlaybackUseCase
import com.windstrom5.rebit.domain.usecase.ToggleShuffleUseCase
import com.windstrom5.rebit.usb.UsbDacManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class AudioRoute(val label: String, val fidelityBase: Int) {
    INTERNAL_SPEAKER("Internal Speaker", 65),
    WIRED_HEADPHONES("Wired (3.5mm Jack)", 75),
    BLUETOOTH_STANDARD("Bluetooth (SBC/AAC)", 35),
    BLUETOOTH_LDAC("Bluetooth (LDAC 990k)", 55),
    USB_DAC("External USB DAC", 100)
}

data class PlayerUiState(
    val dacProfiles: List<DacProfile> = emptyList(),
    val selectedDac: DacProfile? = null,
    val selectedEngine: EngineType = EngineType.CUSTOM_USB_DIRECT,
    val playback: PlaybackState = PlaybackState(),
    val activeTransferStrategy: UsbTransferStrategy = UsbTransferStrategy.PIPELINED,
    val error: String? = null,
    val eqEnabled: Boolean = false,
    val eqGains: List<Float> = listOf(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f),
    val selectedIemProfile: String? = null,
    val eqPreampDb: Float = 0.0f,
    /** Number of exact parametric filters in use (0 = the 10 graphic bands are driving the EQ). */
    val eqFilterCount: Int = 0,
    val eqFilterPreampDb: Float = 0.0f,
    val eqSignature: String = "",
    val crossfeedEnabled: Boolean = false,
    val crossfeedStrength: Float = 0.3f,
    val replayGainEnabled: Boolean = false,
    val normalizationEnabled: Boolean = false,
    val simulatedRoute: AudioRoute = AudioRoute.INTERNAL_SPEAKER,
    val videlityScore: Int = 100,
    val hasNext: Boolean = false,
    val hasPrevious: Boolean = false,
    val shuffleEnabled: Boolean = false,
    val formatQualityLabel: String = "",
    val noDacWarning: Boolean = false,
    val hardwareVolumePercent: Float = 1.0f,
    val hardwareVolumeEnabled: Boolean = true,
    val hardwareVolumeSteps: Int = 100,
    val hardwareVolumeStepIndex: Int = 85,
    val usbBufferLatencyMs: Int = 32,
    val dsdMode: com.windstrom5.rebit.domain.model.DsdPlaybackMode = com.windstrom5.rebit.domain.model.DsdPlaybackMode.DOP,
    val spatialWidth: Float = 1.0f,
    val reverbEnabled: Boolean = false,
    val reverbPreset: com.windstrom5.rebit.domain.model.ReverbPreset = com.windstrom5.rebit.domain.model.ReverbPreset.STUDIO_ROOM,
    val reverbWetDry: Float = 0.25f,
    val replayGainMode: com.windstrom5.rebit.domain.model.ReplayGainMode = com.windstrom5.rebit.domain.model.ReplayGainMode.TRACK,
    val replayGainPreampDb: Float = 0.0f,
    val preventClipping: Boolean = true,
    val bufferMode: com.windstrom5.rebit.domain.model.BufferMode = com.windstrom5.rebit.domain.model.BufferMode.ADAPTIVE,
    val manualBufferSizeBytes: Int = com.windstrom5.rebit.domain.model.PlaybackConfig.DEFAULT_BUFFER_BYTES,
    val queuePosition: Int = 0,
    val queueTotalTracks: Int = 0,
    val lyrics: LyricsData = LyricsData(),
    val activeLyricIndex: Int = -1,
    val showLyricsView: Boolean = false,
    val lyricsProvider: LyricsProvider = LyricsProvider.AUTO,
    val isSearchingLyrics: Boolean = false,
    val onlineSearchResults: List<LyricsSearchResult> = emptyList(),
    val sleepTimer: SleepTimerState = SleepTimerState(),
    val abLoop: AbLoopState = AbLoopState(),
    val playbackQueue: PlaybackQueue = PlaybackQueue(),
    val aiState: com.windstrom5.rebit.playback.ai.AiEngineState = com.windstrom5.rebit.playback.ai.AiEngineState(),
    val karaokeScore: com.windstrom5.rebit.playback.ai.KaraokeScoreState = com.windstrom5.rebit.playback.ai.KaraokeScoreState(),
    val sharedListening: com.windstrom5.rebit.sync.SharedListeningState = com.windstrom5.rebit.sync.SharedListeningState(),
    val dsdRemasteringEnabled: Boolean = false,
    val dsdRemasterTarget: com.windstrom5.rebit.domain.model.DsdRemasterTarget = com.windstrom5.rebit.domain.model.DsdRemasterTarget.DSD64,
    val tubeWarmthEnabled: Boolean = false,
    val tubeWarmthDrive: Float = 0.35f,
    val hiResDirectOutputEnabled: Boolean = true
)

class PlayerViewModel(
    observeDacProfilesUseCase: ObserveDacProfilesUseCase,
    private val startPlaybackUseCase: StartPlaybackUseCase,
    private val stopPlaybackUseCase: StopPlaybackUseCase,
    private val seekPlaybackUseCase: SeekPlaybackUseCase,
    private val observePlaybackStateUseCase: ObservePlaybackStateUseCase,
    private val settingsRepository: SettingsRepository,
    private val usbDacManager: UsbDacManager,
    private val pausePlaybackUseCase: PausePlaybackUseCase,
    private val resumePlaybackUseCase: ResumePlaybackUseCase,
    private val nextTrackUseCase: NextTrackUseCase,
    private val previousTrackUseCase: PreviousTrackUseCase,
    private val toggleShuffleUseCase: ToggleShuffleUseCase,
    observeQueueUseCase: ObserveQueueUseCase,
    private val lyricsRepository: LyricsRepository,
    private val setQueueUseCase: SetQueueUseCase,
    private val stemExtractor: com.windstrom5.rebit.playback.ai.AiStemExtractor? = null,
    private val sharedListeningManager: com.windstrom5.rebit.sync.SharedListeningManager,
    private val usbAutoSetup: com.windstrom5.rebit.data.usb.UsbAutoSetup
) : ViewModel() {

    /** Polled by the Now Playing level meter every frame while playing (cheap: one volatile read). */
    fun outputLevels(): com.windstrom5.rebit.playback.dsp.LevelMeter.Levels? = observePlaybackStateUseCase.outputLevels()

    /** For the live analyzer: recent output samples, and the rate they're at. */
    fun recentSamples(outL: FloatArray, outR: FloatArray): Int = observePlaybackStateUseCase.recentSamples(outL, outR)
    fun outputSampleRateHz(): Int = observePlaybackStateUseCase.outputSampleRateHz()

    private val _selectedDac = MutableStateFlow<DacProfile?>(null)
    private val _selectedEngine = MutableStateFlow(settingsRepository.current.defaultEngine)
    private val _simulatedRoute = MutableStateFlow(AudioRoute.INTERNAL_SPEAKER)
    private val _error = MutableStateFlow<String?>(null)
    private val _showLyricsView = MutableStateFlow(false)
    private val _lyrics = MutableStateFlow(LyricsData())
    private val _lyricsProvider = MutableStateFlow(lyricsRepository.getPreferredProvider())
    private val _isSearchingLyrics = MutableStateFlow(false)
    private val _onlineSearchResults = MutableStateFlow<List<LyricsSearchResult>>(emptyList())
    private val _sleepTimer = MutableStateFlow(SleepTimerState())
    private val _abLoop = MutableStateFlow(AbLoopState())

    /** Progress of automatic USB DAC setup (permission, analysis) for the Audio info dialog. */
    val usbSetup: StateFlow<com.windstrom5.rebit.data.usb.UsbSetupState> = usbAutoSetup.state

    fun retryUsbSetup() = usbAutoSetup.retry()

    init {
        // Auto-setup (and the Settings screen) change the default engine behind this screen's back.
        viewModelScope.launch {
            settingsRepository.settings.collect { s ->
                if (_selectedEngine.value != s.defaultEngine) _selectedEngine.value = s.defaultEngine
            }
        }
    }

    private var sleepTimerJob: Job? = null
    private var lastLoadedLyricsTrackId: Long? = null
    private var lastNotifiedLyricLineIndex: Int = -1
    private var lastHeartbeatBroadcastMs: Long = 0L

    /** Serializes pause/resume/seek so they never race each other:
     *  - pause() + immediate resume() won't start playing over a still-running stop
     *  - seek spam won't queue N concurrent stop+restart cycles */
    private val playbackMutex = Mutex()

    /** Holds the pending debounced seek coroutine — cancelled and replaced each
     *  time the user moves the seekbar before it fires, so only the final
     *  resting position triggers the expensive stop+restart. */
    private var seekDebounceJob: Job? = null

    /** Track id that [_error] was last cleared/set for. Starting a *different*
     *  track happens through LibraryViewModel (a separate instance), which has
     *  no way to touch this ViewModel's _error — without this, an error from
     *  one track would keep shadowing playback.error forever, even once a
     *  totally different, successfully-playing track was current. */
    private var errorTrackId: Long? = null

    val aiAudioEngine = com.windstrom5.rebit.playback.ai.AiAudioEngine()
    val karaokeMicScorer = com.windstrom5.rebit.playback.ai.KaraokeMicScorer()

    init {
        // Sync initial settings to AI engine
        val initSettings = settingsRepository.current
        aiAudioEngine.setAutoPilotActive(initSettings.aiAutoPilotEnabled)
        aiAudioEngine.setDseeUpscalerActive(initSettings.aiDseeUpscalerEnabled)
        aiAudioEngine.setKaraokeConfig(
            enabled = initSettings.karaokeModeEnabled || initSettings.aiVocalSuppressMode,
            mode = initSettings.karaokeModeType,
            strength = initSettings.karaokeVocalSuppressionStrength,
            preserveBass = initSettings.karaokeBassPreservation,
            keyShift = initSettings.karaokeKeyShiftSemitones
        )

        // Automatically load lyrics when current track changes
        viewModelScope.launch {
            observePlaybackStateUseCase().collect { playback ->
                val track = playback.currentTrack
                if (track != null && track.id != lastLoadedLyricsTrackId) {
                    lastLoadedLyricsTrackId = track.id
                    lastNotifiedLyricLineIndex = -1
                    _lyrics.value = lyricsRepository.loadLyricsForTrack(track, _lyricsProvider.value)
                    val isCached = isStemCached(settingsRepository.current.karaokeModeType)
                    aiAudioEngine.setStemCachedActive(isCached)
                    broadcastIfHosting(com.windstrom5.rebit.sync.SyncCommand.TrackChange(track.title, track.artist, track.durationMs))
                } else if (track == null) {
                    lastLoadedLyricsTrackId = null
                    _lyrics.value = LyricsData()
                    aiAudioEngine.setStemCachedActive(false)
                }

                // Periodic Shared Listening resync (see
                // SharedListeningManager) — throttled since this tick fires
                // roughly every 500ms and a heartbeat every tick would be
                // needlessly chatty for messages this small a correction.
                if (track != null && sharedListeningManager.state.value.role == com.windstrom5.rebit.sync.SharedListeningRole.HOST) {
                    val now = System.currentTimeMillis()
                    if (now - lastHeartbeatBroadcastMs >= HEARTBEAT_INTERVAL_MS) {
                        lastHeartbeatBroadcastMs = now
                        broadcastIfHosting(
                            com.windstrom5.rebit.sync.SyncCommand.Heartbeat(playback.positionMs, playback.isPlaying, now)
                        )
                    }
                }

                // Feed the currently-active lyric line into the mic scorer
                // whenever it changes, so per-line grading (see
                // KaraokeMicScorer.setCurrentLyricLine) tracks the same line
                // the synced-lyrics display is showing. Only bothers when
                // scoring is actually active — harmless either way, but no
                // reason to do the lookup otherwise.
                if (karaokeMicScorer.scoreState.value.isMicActive) {
                    val lyrics = _lyrics.value
                    val activeIdx = lyrics.findActiveIndex(playback.positionMs)
                    if (activeIdx != lastNotifiedLyricLineIndex) {
                        lastNotifiedLyricLineIndex = activeIdx
                        karaokeMicScorer.setCurrentLyricLine(activeIdx, lyrics.lines.getOrNull(activeIdx)?.text ?: "")
                    }
                }

                // Check A-B repeat looper
                val loop = _abLoop.value
                val pointA = loop.pointAMs
                val pointB = loop.pointBMs
                if (loop.isEnabled && pointA != null && pointB != null) {
                    if (playback.positionMs >= pointB) {
                        seekTo(pointA)
                    }
                }
            }
        }
    }

    private val engineRouteAndDevice = combine(_selectedEngine, _simulatedRoute, usbDacManager.connectedDevice) { engine, route, device ->
        Triple(engine, route, device)
    }

    private val lyricsAndExtraUi = combine(
        _lyrics,
        _showLyricsView,
        _lyricsProvider,
        _isSearchingLyrics,
        _onlineSearchResults
    ) { lyrics, showLyrics, provider, isSearching, results ->
        LyricsUiTuple(lyrics, showLyrics, provider, isSearching, results)
    }

    private val extraStates = combine(_sleepTimer, _abLoop, aiAudioEngine.aiState, karaokeMicScorer.scoreState, sharedListeningManager.state) { timer, loop, ai, score, sharedListening ->
        ScoreMegaExtra(timer, loop, ai, score, sharedListening)
    }

    private val combinedFlows = combine(engineRouteAndDevice, observeQueueUseCase(), lyricsAndExtraUi, extraStates) { a, b, c, extra ->
        CombineMegaTuple(a, b, c, extra.sleepTimer, extra.abLoop, extra.aiState, extra.karaokeScore, extra.sharedListening)
    }

    val uiState: StateFlow<PlayerUiState> = combine(
        observeDacProfilesUseCase(),
        _selectedDac,
        combinedFlows,
        observePlaybackStateUseCase(),
        settingsRepository.settings
    ) { dacs, selectedDacOverride, megaTuple, playback, userSettings ->
        val engineTuple = megaTuple.engineRouteAndDevice
        val queue = megaTuple.queue
        val lyricsTuple = megaTuple.lyricsTuple
        val timer = megaTuple.sleepTimer
        val loop = megaTuple.abLoop
        val aiState = megaTuple.aiState

        val engine = engineTuple.first
        val simRoute = engineTuple.second
        val connectedUsbDevice = engineTuple.third

        // Use the profile of the DAC that is actually plugged in. Taking "the first saved profile"
        // handed USB Direct a different DAC's capabilities whenever an old profile existed.
        fun DacProfile.isConnectedDevice() =
            connectedUsbDevice != null && vendorId == connectedUsbDevice.vendorId && productId == connectedUsbDevice.productId
        val selected = if (connectedUsbDevice != null) {
            selectedDacOverride?.takeIf { it.isConnectedDevice() } ?: dacs.firstOrNull { it.isConnectedDevice() }
        } else {
            selectedDacOverride ?: dacs.firstOrNull()
        }

        if (playback.currentTrack?.id != errorTrackId) {
            _error.value = null
            errorTrackId = playback.currentTrack?.id
        }
        
        val actualEngine = if (selected == null || connectedUsbDevice == null) EngineType.ANDROID_AUDIOTRACK else engine
        
        val activeRoute = if (actualEngine == EngineType.CUSTOM_USB_DIRECT && selected != null && connectedUsbDevice != null) {
            AudioRoute.USB_DAC
        } else {
            simRoute
        }

        var baseFidelity = activeRoute.fidelityBase
        if (userSettings.eqEnabled && baseFidelity > 35) baseFidelity -= 5
        if (userSettings.crossfeedEnabled && baseFidelity > 35) baseFidelity -= 8
        if (userSettings.replayGainEnabled && baseFidelity > 35) baseFidelity -= 3
        if (userSettings.normalizationEnabled && baseFidelity > 35) baseFidelity -= 3

        val sourceTrack = playback.currentTrack
        val sourcePcm = sourceTrack?.pcm

        val formatQualityLabel = when {
            actualEngine == EngineType.CUSTOM_USB_DIRECT && selected != null && connectedUsbDevice != null ->
                "BIT-PERFECT USB"
            userSettings.dsdRemasteringEnabled ->
                "DSD REMASTER (${userSettings.dsdRemasterTarget.name})"
            actualEngine == EngineType.ANDROID_HIRES_DIRECT ->
                "HI-RES DIRECT HAL"
            userSettings.tubeWarmthEnabled ->
                "TUBE WARMTH"
            sourcePcm == null -> ""
            actualEngine == EngineType.ANDROID_AUDIOTRACK && (sourcePcm.bitDepth > 16 || sourcePcm.sampleRateHz > 48_000) ->
                "RESAMPLED"
            else -> "16-BIT"
        }

        val noDacWarning = engine == EngineType.CUSTOM_USB_DIRECT &&
            (selected == null || connectedUsbDevice == null)

        val activeLyricIdx = lyricsTuple.lyrics.findActiveIndex(playback.positionMs)

        PlayerUiState(
            dacProfiles = dacs,
            selectedDac = selected,
            selectedEngine = actualEngine,
            playback = playback,
            activeTransferStrategy = userSettings.usbTransferStrategy,
            error = _error.value ?: playback.error,
            eqEnabled = userSettings.eqEnabled,
            eqGains = userSettings.eqGains,
            selectedIemProfile = userSettings.selectedIemProfile,
            eqPreampDb = userSettings.eqPreampDb,
            eqFilterCount = userSettings.eqFilters.size,
            eqFilterPreampDb = userSettings.eqFilterPreampDb,
            eqSignature = userSettings.eqSignature,
            crossfeedEnabled = userSettings.crossfeedEnabled,
            crossfeedStrength = userSettings.crossfeedStrength,
            replayGainEnabled = userSettings.replayGainEnabled,
            normalizationEnabled = userSettings.normalizationEnabled,
            simulatedRoute = activeRoute,
            videlityScore = baseFidelity.coerceIn(0, 100),
            hasNext = queue.hasQueue,
            hasPrevious = queue.hasQueue,
            shuffleEnabled = queue.shuffleEnabled,
            formatQualityLabel = formatQualityLabel,
            noDacWarning = noDacWarning,
            hardwareVolumePercent = (userSettings.hardwareVolumeStepIndex.toFloat() / userSettings.hardwareVolumeSteps.coerceAtLeast(1)),
            hardwareVolumeEnabled = userSettings.hardwareVolumeEnabled,
            hardwareVolumeSteps = userSettings.hardwareVolumeSteps,
            hardwareVolumeStepIndex = userSettings.hardwareVolumeStepIndex,
            usbBufferLatencyMs = userSettings.usbBufferLatencyMs,
            dsdMode = userSettings.dsdMode,
            spatialWidth = userSettings.spatialWidth,
            reverbEnabled = userSettings.reverbEnabled,
            reverbPreset = userSettings.reverbPreset,
            reverbWetDry = userSettings.reverbWetDry,
            replayGainMode = userSettings.replayGainMode,
            replayGainPreampDb = userSettings.replayGainPreampDb,
            preventClipping = userSettings.preventClipping,
            bufferMode = userSettings.bufferMode,
            manualBufferSizeBytes = userSettings.manualBufferSizeBytes,
            queuePosition = if (queue.hasQueue && queue.playPosition >= 0) queue.playPosition + 1 else 1,
            queueTotalTracks = if (queue.hasQueue) queue.tracks.size else 1,
            lyrics = lyricsTuple.lyrics,
            activeLyricIndex = activeLyricIdx,
            showLyricsView = lyricsTuple.showLyrics,
            lyricsProvider = lyricsTuple.provider,
            isSearchingLyrics = lyricsTuple.isSearching,
            onlineSearchResults = lyricsTuple.searchResults,
            sleepTimer = timer,
            abLoop = loop,
            playbackQueue = queue,
            karaokeScore = megaTuple.karaokeScore,
            sharedListening = megaTuple.sharedListening,
            dsdRemasteringEnabled = userSettings.dsdRemasteringEnabled,
            dsdRemasterTarget = userSettings.dsdRemasterTarget,
            tubeWarmthEnabled = userSettings.tubeWarmthEnabled,
            tubeWarmthDrive = userSettings.tubeWarmthDrive,
            hiResDirectOutputEnabled = userSettings.hiResDirectOutputEnabled,
            aiState = aiState.copy(
                isAutoPilotActive = userSettings.aiAutoPilotEnabled,
                isDseeUpscalerActive = userSettings.aiDseeUpscalerEnabled,
                isVocalSuppressionActive = userSettings.aiVocalSuppressMode || userSettings.karaokeModeEnabled,
                karaokeModeEnabled = userSettings.karaokeModeEnabled,
                karaokeModeType = userSettings.karaokeModeType,
                karaokeVocalSuppressionStrength = userSettings.karaokeVocalSuppressionStrength,
                karaokeBassPreservation = userSettings.karaokeBassPreservation,
                karaokeKeyShiftSemitones = userSettings.karaokeKeyShiftSemitones
            )
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), PlayerUiState())

    fun toggleLyricsView() {
        _showLyricsView.value = !_showLyricsView.value
    }

    fun setLyricsProvider(provider: LyricsProvider) {
        _lyricsProvider.value = provider
        lyricsRepository.setPreferredProvider(provider)
        reloadLyricsOnline(forceProvider = provider)
    }

    fun reloadLyricsOnline(forceProvider: LyricsProvider? = null) {
        val currentTrack = uiState.value.playback.currentTrack ?: return
        viewModelScope.launch {
            _lyrics.value = lyricsRepository.loadLyricsForTrack(currentTrack, forceProvider ?: _lyricsProvider.value)
        }
    }

    fun searchOnlineLyrics(query: String) {
        viewModelScope.launch {
            _isSearchingLyrics.value = true
            _onlineSearchResults.value = lyricsRepository.searchOnlineLyrics(query, _lyricsProvider.value)
            _isSearchingLyrics.value = false
        }
    }

    fun selectLyricsResult(result: LyricsSearchResult) {
        val currentTrack = uiState.value.playback.currentTrack ?: return
        viewModelScope.launch {
            _lyrics.value = lyricsRepository.applySelectedOnlineLyrics(currentTrack, result)
            _onlineSearchResults.value = emptyList()
        }
    }

    fun importCustomLyrics(content: String) {
        val currentTrack = uiState.value.playback.currentTrack ?: return
        viewModelScope.launch {
            val parsed = lyricsRepository.saveCustomLyrics(currentTrack, content)
            _lyrics.value = parsed
        }
    }

    fun deleteCustomLyrics() {
        val currentTrack = uiState.value.playback.currentTrack ?: return
        viewModelScope.launch {
            lyricsRepository.deleteCustomLyrics(currentTrack)
            _lyrics.value = lyricsRepository.loadLyricsForTrack(currentTrack, _lyricsProvider.value)
        }
    }

    fun setSleepTimer(minutes: Int, isEndOfTrack: Boolean = false) {
        sleepTimerJob?.cancel()
        if (isEndOfTrack) {
            val track = uiState.value.playback.currentTrack
            val remainingMs = if (track != null) {
                (track.durationMs - uiState.value.playback.positionMs).coerceAtLeast(1000L)
            } else 60_000L
            val totalSec = (remainingMs / 1000).toInt()
            _sleepTimer.value = SleepTimerState(isEnabled = true, remainingSeconds = totalSec, totalSeconds = totalSec, isEndOfTrack = true)
        } else {
            val totalSec = minutes * 60
            _sleepTimer.value = SleepTimerState(isEnabled = true, remainingSeconds = totalSec, totalSeconds = totalSec, isEndOfTrack = false)
        }

        sleepTimerJob = viewModelScope.launch {
            while (_sleepTimer.value.isEnabled && _sleepTimer.value.remainingSeconds > 0) {
                delay(1000L)
                val newRemaining = _sleepTimer.value.remainingSeconds - 1
                _sleepTimer.value = _sleepTimer.value.copy(remainingSeconds = newRemaining)
                if (newRemaining <= 0) break
            }
            if (_sleepTimer.value.isEnabled) {
                _sleepTimer.value = SleepTimerState(isEnabled = false, remainingSeconds = 0, totalSeconds = 0)
                pause()
            }
        }
    }

    fun cancelSleepTimer() {
        sleepTimerJob?.cancel()
        _sleepTimer.value = SleepTimerState(isEnabled = false, remainingSeconds = 0, totalSeconds = 0)
    }

    fun setLoopPointA() {
        val currentPos = uiState.value.playback.positionMs
        _abLoop.value = _abLoop.value.copy(pointAMs = currentPos, isEnabled = _abLoop.value.pointBMs != null)
    }

    fun setLoopPointB() {
        val currentPos = uiState.value.playback.positionMs
        val pointA = _abLoop.value.pointAMs ?: 0L
        if (currentPos > pointA) {
            _abLoop.value = _abLoop.value.copy(pointBMs = currentPos, isEnabled = true)
        }
    }

    fun clearAbLoop() {
        _abLoop.value = AbLoopState(isEnabled = false, pointAMs = null, pointBMs = null)
    }

    fun playQueueTrack(track: com.windstrom5.rebit.domain.model.AudioTrackModel) {
        viewModelScope.launch {
            playbackMutex.withLock {
                try {
                    _error.value = null
                    val queue = uiState.value.playbackQueue
                    if (queue.tracks.isNotEmpty()) {
                        setQueueUseCase(queue.tracks, track)
                    }
                    startPlaybackUseCase(track, uiState.value.selectedEngine, uiState.value.selectedDac)
                } catch (t: Throwable) {
                    _error.value = t.message ?: "Failed to play selected track."
                }
            }
        }
    }

    fun selectDac(dac: DacProfile) {
        _selectedDac.value = dac
    }

    fun selectEngine(engine: EngineType) {
        _selectedEngine.value = engine
        settingsRepository.setDefaultEngine(engine)
        // Picking USB Direct with no usable DAC profile used to do nothing, silently. Finish setup now.
        if (engine == EngineType.CUSTOM_USB_DIRECT) usbAutoSetup.ensureReady()
    }

    fun setUsbTransferStrategy(strategy: UsbTransferStrategy) {
        settingsRepository.setUsbTransferStrategy(strategy)
    }

    fun setBufferMode(mode: com.windstrom5.rebit.domain.model.BufferMode) {
        settingsRepository.setBufferMode(mode)
    }

    fun setManualBufferSize(bytes: Int) {
        settingsRepository.setManualBufferSize(bytes)
    }

    fun selectSimulatedRoute(route: AudioRoute) {
        _simulatedRoute.value = route
    }

    fun toggleEq() {
        val currentEnabled = uiState.value.eqEnabled
        settingsRepository.setEqEnabled(!currentEnabled)
    }

    fun updateEqGains(gains: List<Float>) {
        settingsRepository.setEqGains(gains)
    }

    fun applyAutoEqProfile(profile: com.windstrom5.rebit.playback.autoeq.AutoEqProfile) {
        settingsRepository.setSelectedIemProfile(profile.displayName, profile.gains)
        if (!uiState.value.eqEnabled) {
            settingsRepository.setEqEnabled(true)
        }
    }

    /** Applies an exact AutoEq IEM correction (plus optional genre tuning) and turns the EQ on. */
    fun applyParametricEq(apply: com.windstrom5.rebit.playback.autoeq.IemEqApply) {
        settingsRepository.applyParametricEq(
            profileName = apply.profileName,
            filters = apply.filters,
            preampDb = apply.preampDb,
            displayGains = apply.displayGains,
            signature = apply.signatureId,
            signatureStrength = apply.signatureStrength
        )
    }

    fun resetEqFlat() {
        settingsRepository.setSelectedIemProfile(null, listOf(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f))
    }

    fun setEqPreampDb(preampDb: Float) {
        settingsRepository.setEqPreampDb(preampDb)
    }

    fun toggleCrossfeed() {
        settingsRepository.setCrossfeedEnabled(!uiState.value.crossfeedEnabled)
    }

    fun setCrossfeedStrength(strength: Float) {
        settingsRepository.setCrossfeedStrength(strength)
    }

    fun toggleReplayGain() {
        settingsRepository.setReplayGainEnabled(!uiState.value.replayGainEnabled)
    }

    fun toggleNormalization() {
        settingsRepository.setNormalizationEnabled(!uiState.value.normalizationEnabled)
    }

    fun replayCurrentTrack() {
        val track = uiState.value.playback.currentTrack ?: return
        viewModelScope.launch {
            playbackMutex.withLock {
                try {
                    _error.value = null
                    startPlaybackUseCase(track, uiState.value.selectedEngine, uiState.value.selectedDac)
                } catch (t: Throwable) {
                    _error.value = t.message ?: "Playback failed to start."
                }
            }
        }
    }

    fun seekTo(positionMs: Long) {
        seekDebounceJob?.cancel()
        seekDebounceJob = viewModelScope.launch {
            playbackMutex.withLock {
                try {
                    seekPlaybackUseCase(positionMs)
                    broadcastIfHosting(com.windstrom5.rebit.sync.SyncCommand.Seek(positionMs, System.currentTimeMillis()))
                } catch (t: Throwable) {
                    _error.value = t.message ?: "Seek failed."
                }
            }
        }
    }

    fun stop() {
        viewModelScope.launch { stopPlaybackUseCase() }
    }

    fun pause() {
        seekDebounceJob?.cancel()
        viewModelScope.launch {
            playbackMutex.withLock {
                try {
                    pausePlaybackUseCase()
                    broadcastIfHosting(com.windstrom5.rebit.sync.SyncCommand.Pause(uiState.value.playback.positionMs))
                } catch (t: Throwable) {
                    _error.value = t.message ?: "Pause failed."
                }
            }
        }
    }

    fun resume() {
        viewModelScope.launch {
            playbackMutex.withLock {
                try {
                    resumePlaybackUseCase()
                    broadcastIfHosting(com.windstrom5.rebit.sync.SyncCommand.Play(uiState.value.playback.positionMs, System.currentTimeMillis()))
                } catch (t: Throwable) {
                    _error.value = t.message ?: "Resume failed."
                }
            }
        }
    }

    fun nextTrack() {
        viewModelScope.launch {
            try {
                nextTrackUseCase()
            } catch (t: Throwable) {
                _error.value = t.message ?: "Couldn't skip to the next track."
            }
        }
    }

    fun previousTrack() {
        viewModelScope.launch {
            try {
                previousTrackUseCase()
            } catch (t: Throwable) {
                _error.value = t.message ?: "Couldn't skip to the previous track."
            }
        }
    }

    /**
     * Sets the DAC's own hardware volume. Goes through UsbDacManager
     * directly (rather than PlaybackController) deliberately: this needs to
     * work with a connected-but-idle DAC, before anything has started
     * playing.
     *
     * Needs the analyzed DacProfile to address the control request — see
     * UsbDacManager.setHardwareVolume, which used to guess at the interface
     * number and Feature Unit ID and silently did nothing on any DAC whose
     * real values fell outside those guesses. The result was also discarded
     * here, which is why that failure was invisible rather than surfacing
     * as an error.
     */
    fun setHardwareVolume(percent: Float) {
        val clamped = percent.coerceIn(0.0f, 1.0f)
        val profile = uiState.value.selectedDac
        if (profile == null) {
            _error.value = "Analyze the DAC first (DAC tab → Analyze Capabilities) to enable hardware volume."
            return
        }
        if (!profile.supportsHardwareVolume) {
            _error.value = "${profile.productName} doesn't expose a USB volume control — use its own volume knob, or your IEM's inline control."
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            val dev = usbDacManager.currentDevice ?: return@launch
            val conn = usbDacManager.openConnection(dev) ?: return@launch
            val ok = try {
                usbDacManager.setHardwareVolume(conn, profile, clamped)
            } finally {
                try { conn.close() } catch (_: Exception) {}
            }
            if (!ok) {
                _error.value = "${profile.productName} rejected the volume command — check logcat (tag UsbDacManager) for details."
            }
        }
    }

    fun setHardwareVolumeStep(step: Int, maxSteps: Int) {
        settingsRepository.setHardwareVolumeSteps(maxSteps)
        settingsRepository.setHardwareVolumeStepIndex(step.coerceIn(0, maxSteps))
        val clampedPercent = (step.toFloat() / maxSteps.coerceAtLeast(1)).coerceIn(0.0f, 1.0f)
        setHardwareVolume(clampedPercent)
    }

    fun toggleHardwareVolume() {
        settingsRepository.setHardwareVolumeEnabled(!uiState.value.hardwareVolumeEnabled)
    }

    fun setSpatialWidth(width: Float) {
        settingsRepository.setSpatialWidth(width)
    }

    fun toggleReverb() {
        settingsRepository.setReverbEnabled(!uiState.value.reverbEnabled)
    }

    fun setReverbPreset(preset: com.windstrom5.rebit.domain.model.ReverbPreset) {
        settingsRepository.setReverbPreset(preset)
    }

    fun setReverbWetDry(wetDry: Float) {
        settingsRepository.setReverbWetDry(wetDry)
    }

    fun setReplayGainMode(mode: com.windstrom5.rebit.domain.model.ReplayGainMode) {
        settingsRepository.setReplayGainMode(mode)
    }

    fun setReplayGainPreampDb(preampDb: Float) {
        settingsRepository.setReplayGainPreampDb(preampDb)
    }

    fun togglePreventClipping() {
        settingsRepository.setPreventClipping(!uiState.value.preventClipping)
    }

    fun setUsbBufferLatency(latencyMs: Int) {
        settingsRepository.setUsbBufferLatency(latencyMs)
    }

    fun setDsdMode(mode: com.windstrom5.rebit.domain.model.DsdPlaybackMode) {
        settingsRepository.setDsdMode(mode)
    }

    fun toggleAiAutoPilot(enabled: Boolean) {
        settingsRepository.setAiAutoPilotEnabled(enabled)
        aiAudioEngine.setAutoPilotActive(enabled)
        if (enabled) {
            val suggested = aiAudioEngine.aiState.value.suggestedEqGains
            updateEqGains(suggested)
        }
    }

    fun toggleAiDsee(enabled: Boolean) {
        settingsRepository.setAiDseeUpscalerEnabled(enabled)
        aiAudioEngine.setDseeUpscalerActive(enabled)
    }

    fun toggleAiVocalSuppression(enabled: Boolean) {
        settingsRepository.setAiVocalSuppressMode(enabled)
        settingsRepository.setKaraokeModeEnabled(enabled)
        aiAudioEngine.setVocalSuppressionActive(enabled)
    }

    fun setKaraokeConfig(
        enabled: Boolean,
        mode: com.windstrom5.rebit.domain.model.KaraokeModeType = settingsRepository.current.karaokeModeType,
        strength: Float = settingsRepository.current.karaokeVocalSuppressionStrength,
        preserveBass: Boolean = settingsRepository.current.karaokeBassPreservation,
        keyShift: Int = settingsRepository.current.karaokeKeyShiftSemitones
    ) {
        settingsRepository.setKaraokeVocalSuppressionStrength(strength)
        settingsRepository.setKaraokeBassPreservation(preserveBass)
        settingsRepository.setKaraokeKeyShiftSemitones(keyShift)
        aiAudioEngine.setKaraokeConfig(enabled, mode, strength, preserveBass, keyShift)

        // Delegates mode/enabled + hot-reload to setKaraokeModeType, which
        // is also where the "auto-extract a real neural stem if this track
        // doesn't have one cached yet" check lives — see its doc comment.
        // Keeping that logic in one place rather than duplicating the
        // cache-check here too.
        val targetMode = if (enabled) mode else com.windstrom5.rebit.domain.model.KaraokeModeType.OFF
        setKaraokeModeType(targetMode)
    }

    fun toggleKaraokeMode() {
        val currentEnabled = settingsRepository.current.karaokeModeEnabled
        setKaraokeConfig(!currentEnabled)
    }

    fun setKaraokeModeType(mode: com.windstrom5.rebit.domain.model.KaraokeModeType) {
        val isEnabled = mode != com.windstrom5.rebit.domain.model.KaraokeModeType.OFF
        val isCached = if (isEnabled) isStemCached(mode) else false

        // Turning on a mode with no stem cached yet — this used to just
        // hot-reload straight to live real-time DSP, silently skipping the
        // real neural separation tier entirely unless the person happened
        // to already know about (and had tapped) the separate "pre-render"
        // button first. J-pop's vocal/instrument unison is exactly the case
        // DSP structurally can't solve (see SpectralSeparationEngine's doc
        // comment) but the neural model can — so if it's usable for this
        // track, kick it off automatically rather than settling for the
        // tier that's known not to fix what someone's actually reporting.
        if (isEnabled && !isCached && stemExtractor != null) {
            extractInstrumentalStem(mode)
            return
        }

        applyKaraokeModeTypeAndHotReload(mode, isCached)
    }

    /** The actual settings-persist + hot-reload logic — split out so both
     *  setKaraokeModeType (immediate path) and extractInstrumentalStem's
     *  completion callback (deferred path, once a stem exists to switch to)
     *  can reach it without calling back into setKaraokeModeType itself,
     *  which would recurse into the auto-extract check above. */
    private fun applyKaraokeModeTypeAndHotReload(mode: com.windstrom5.rebit.domain.model.KaraokeModeType, isCached: Boolean) {
        val isEnabled = mode != com.windstrom5.rebit.domain.model.KaraokeModeType.OFF
        settingsRepository.setKaraokeModeEnabled(isEnabled)
        settingsRepository.setKaraokeModeType(mode)
        aiAudioEngine.setKaraokeConfig(
            enabled = isEnabled,
            mode = mode,
            isStemCached = isCached
        )

        // Hot-reload track seamlessly if playback is currently active so it immediately switches
        // between the original track and the clean cached stem without losing playback position!
        val currentTrack = uiState.value.playback.currentTrack
        if (currentTrack != null && uiState.value.playback.isPlaying) {
            val currentPos = uiState.value.playback.positionMs
            viewModelScope.launch {
                playbackMutex.withLock {
                    seekPlaybackUseCase(currentPos)
                }
            }
        }
    }

    fun isStemCached(mode: com.windstrom5.rebit.domain.model.KaraokeModeType = com.windstrom5.rebit.domain.model.KaraokeModeType.INSTRUMENTAL_ONLY): Boolean {
        val track = uiState.value.playback.currentTrack ?: return false
        val extractor = stemExtractor ?: return false
        return extractor.getCachedStem(track.id, mode) != null
    }

    fun setKaraokeStrength(strength: Float) {
        settingsRepository.setKaraokeVocalSuppressionStrength(strength)
        aiAudioEngine.setKaraokeConfig(
            enabled = settingsRepository.current.karaokeModeEnabled,
            strength = strength
        )
    }

    fun setKaraokeKeyShift(semitones: Int) {
        settingsRepository.setKaraokeKeyShiftSemitones(semitones)
        aiAudioEngine.setKaraokeConfig(
            enabled = settingsRepository.current.karaokeModeEnabled,
            keyShift = semitones
        )
    }

    fun toggleKaraokeBassPreservation() {
        val newPreserve = !settingsRepository.current.karaokeBassPreservation
        settingsRepository.setKaraokeBassPreservation(newPreserve)
        aiAudioEngine.setKaraokeConfig(
            enabled = settingsRepository.current.karaokeModeEnabled,
            preserveBass = newPreserve
        )
    }

    fun toggleKaraokeMicScoring(context: android.content.Context, enabled: Boolean) {
        settingsRepository.setKaraokeMicScoringEnabled(enabled)
        if (enabled) {
            karaokeMicScorer.start(
                context = context,
                // Was reading aiAudioEngine.getVocalIsolator() — an instance
                // that never receives real PCM (nothing calls
                // processPcmBuffer/feedPcmSnapshot on it), so this was
                // always stuck reporting 0Hz/"--" regardless of what was
                // playing. The engine that's actually fed live audio is
                // whichever PlaybackEngine is running DSP right now — see
                // PlaybackEngine.detectedPitch() — surfaced here through
                // PlaybackState instead.
                getReferencePitch = { uiState.value.playback.detectedPitchHz },
                getReferenceNote = { uiState.value.playback.detectedMusicalNote }
            )
        } else {
            karaokeMicScorer.stop()
        }
    }

    fun resetKaraokeScore() {
        karaokeMicScorer.resetScore()
    }

    // ── Shared Listening ──────────────────────────────────────────────
    // See SharedListeningManager's class doc comment for the full scope
    // and honest verification status. Short version: HOST broadcasts
    // play/pause/seek/track-change to GUESTs over Bluetooth/Wi-Fi Direct;
    // GUESTs need the same track locally already (matched by title/artist/
    // duration, not real audio streaming) — see SyncCommand.TrackChange.

    val sharedListeningState = sharedListeningManager.state

    fun startHostingSharedSession(sessionName: String) {
        sharedListeningManager.startHosting(sessionName) { /* host doesn't react to guest commands in this design */ }
    }

    fun discoverSharedSessions() {
        sharedListeningManager.startDiscovery { command -> handleReceivedSyncCommand(command) }
    }

    fun joinSharedSession(endpointId: String, localDisplayName: String) {
        sharedListeningManager.joinHost(endpointId, localDisplayName)
    }

    fun leaveSharedSession() {
        sharedListeningManager.leaveSession()
    }

    /** Best-effort local match for an incoming TrackChange — searches the
     *  current queue (not the full library; a reasonable first-pass scope,
     *  see SharedListeningManager's doc comment on what this feature is and
     *  isn't) by title/artist and duration within a small tolerance, since
     *  metadata can round differently between two people's copies of the
     *  same file. */
    private fun findLocalMatch(title: String, artist: String?, durationMs: Long): com.windstrom5.rebit.domain.model.AudioTrackModel? {
        val candidates = uiState.value.playbackQueue.tracks
        return candidates.firstOrNull { candidate ->
            candidate.title.equals(title, ignoreCase = true) &&
                (artist == null || candidate.artist?.equals(artist, ignoreCase = true) == true) &&
                kotlin.math.abs(candidate.durationMs - durationMs) < 2000L
        }
    }

    private fun handleReceivedSyncCommand(command: com.windstrom5.rebit.sync.SyncCommand) {
        when (command) {
            is com.windstrom5.rebit.sync.SyncCommand.TrackChange -> {
                val match = findLocalMatch(command.title, command.artist, command.durationMs)
                if (match != null) {
                    playQueueTrack(match)
                }
                // Surfaced via sharedListeningState for the UI to show
                // "doesn't have this song" rather than silently doing
                // nothing — see SharedListeningState.trackUnavailableLocally.
            }
            is com.windstrom5.rebit.sync.SyncCommand.Play -> {
                val compensatedPosition = command.positionMs + (System.currentTimeMillis() - command.hostTimestampMs)
                seekTo(compensatedPosition.coerceAtLeast(0))
                resume()
            }
            is com.windstrom5.rebit.sync.SyncCommand.Pause -> {
                pause()
                seekTo(command.positionMs.coerceAtLeast(0))
            }
            is com.windstrom5.rebit.sync.SyncCommand.Seek -> {
                val compensatedPosition = command.positionMs + (System.currentTimeMillis() - command.hostTimestampMs)
                seekTo(compensatedPosition.coerceAtLeast(0))
            }
            is com.windstrom5.rebit.sync.SyncCommand.Heartbeat -> {
                // Periodic drift correction — only nudge if it's actually
                // drifted enough to matter, so this doesn't fight normal
                // seek-debounce/network jitter with constant micro-corrections.
                val compensatedPosition = command.positionMs + (System.currentTimeMillis() - command.hostTimestampMs)
                val localPosition = uiState.value.playback.positionMs
                if (kotlin.math.abs(localPosition - compensatedPosition) > HEARTBEAT_DRIFT_THRESHOLD_MS) {
                    seekTo(compensatedPosition.coerceAtLeast(0))
                }
                if (command.isPlaying && !uiState.value.playback.isPlaying) resume()
                if (!command.isPlaying && uiState.value.playback.isPlaying) pause()
            }
        }
    }

    /** Host-only — call after any local play/pause/seek/track-change so
     *  connected guests stay in sync. A no-op when not hosting or nobody's
     *  connected (see SharedListeningManager.broadcastCommand). */
    private fun broadcastIfHosting(command: com.windstrom5.rebit.sync.SyncCommand) {
        if (sharedListeningManager.state.value.role == com.windstrom5.rebit.sync.SharedListeningRole.HOST) {
            sharedListeningManager.broadcastCommand(command)
        }
    }

    fun applyAiSuggestedEq(gains: List<Float>) {
        updateEqGains(gains)
    }

    fun toggleShuffle() {
        toggleShuffleUseCase()
    }

    private val _stemExtractionProgress = MutableStateFlow<com.windstrom5.rebit.playback.ai.StemExtractionProgress?>(null)
    val stemExtractionProgress: StateFlow<com.windstrom5.rebit.playback.ai.StemExtractionProgress?> = _stemExtractionProgress.asStateFlow()

    fun extractInstrumentalStem(mode: com.windstrom5.rebit.domain.model.KaraokeModeType = com.windstrom5.rebit.domain.model.KaraokeModeType.INSTRUMENTAL_ONLY) {
        val track = uiState.value.playback.currentTrack ?: return
        val extractor = stemExtractor ?: return
        if (isStemCached(mode)) {
            // Already in cache: instantly select mode and show cached completion
            applyKaraokeModeTypeAndHotReload(mode, isCached = true)
            _stemExtractionProgress.value = com.windstrom5.rebit.playback.ai.StemExtractionProgress(
                progressPercent = 100,
                statusMessage = "Stem already in cache! Auto-selected.",
                isCompleted = true,
                outputFile = extractor.getCachedStem(track.id, mode)
            )
            return
        }
        viewModelScope.launch {
            extractor.extractStem(track, mode).collect { progress ->
                _stemExtractionProgress.value = progress
                if (progress.isCompleted && progress.outputFile != null) {
                    // Auto-route playback to the freshly generated clean cached stem!
                    applyKaraokeModeTypeAndHotReload(mode, isCached = true)
                }
            }
        }
    }

    fun dismissStemExtractionProgress() {
        _stemExtractionProgress.value = null
    }

    fun getStemCacheSizeBytes(): Long {
        return stemExtractor?.getCacheSizeBytes() ?: 0L
    }

    fun clearStemCache(): Boolean {
        val result = stemExtractor?.clearStemCache() ?: false
        val currentTrack = uiState.value.playback.currentTrack
        if (currentTrack != null && uiState.value.playback.isPlaying) {
            val currentPos = uiState.value.playback.positionMs
            viewModelScope.launch {
                playbackMutex.withLock {
                    seekPlaybackUseCase(currentPos)
                }
            }
        }
        return result
    }

    override fun onCleared() {
        super.onCleared()
        karaokeMicScorer.stop()
        sharedListeningManager.stopAll()
    }

    companion object {
        private const val SEEK_DEBOUNCE_MS = 280L
        /** Below this drift, a Shared Listening heartbeat is treated as
         *  close enough — avoids fighting normal jitter with constant
         *  micro-seeks. */
        private const val HEARTBEAT_DRIFT_THRESHOLD_MS = 750L
        /** Minimum gap between two Shared Listening heartbeat broadcasts —
         *  the position-tick observer fires roughly every 500ms, which
         *  would otherwise mean a heartbeat every tick. */
        private const val HEARTBEAT_INTERVAL_MS = 3000L
    }
}

private data class LyricsUiTuple(
    val lyrics: LyricsData,
    val showLyrics: Boolean,
    val provider: LyricsProvider,
    val isSearching: Boolean,
    val searchResults: List<LyricsSearchResult>
)

private data class ScoreMegaExtra(
    val sleepTimer: SleepTimerState,
    val abLoop: AbLoopState,
    val aiState: com.windstrom5.rebit.playback.ai.AiEngineState,
    val karaokeScore: com.windstrom5.rebit.playback.ai.KaraokeScoreState,
    val sharedListening: com.windstrom5.rebit.sync.SharedListeningState
)

private data class CombineMegaTuple(
    val engineRouteAndDevice: Triple<EngineType, AudioRoute, android.hardware.usb.UsbDevice?>,
    val queue: PlaybackQueue,
    val lyricsTuple: LyricsUiTuple,
    val sleepTimer: SleepTimerState,
    val abLoop: AbLoopState,
    val aiState: com.windstrom5.rebit.playback.ai.AiEngineState,
    val karaokeScore: com.windstrom5.rebit.playback.ai.KaraokeScoreState,
    val sharedListening: com.windstrom5.rebit.sync.SharedListeningState
)



