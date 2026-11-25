package com.windstrom5.rebit.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.windstrom5.rebit.domain.abx.AbxAudioPlayer
import com.windstrom5.rebit.domain.abx.AbxClipLoader
import com.windstrom5.rebit.domain.abx.AbxGameEngine
import com.windstrom5.rebit.domain.abx.AbxSample
import com.windstrom5.rebit.domain.abx.AbxTestMode
import com.windstrom5.rebit.domain.abx.AbxTrialState
import com.windstrom5.rebit.domain.model.AudioFileFormat
import com.windstrom5.rebit.domain.model.AudioTrackModel
import com.windstrom5.rebit.domain.usecase.ObserveLibraryUseCase
import com.windstrom5.rebit.playback.PlaybackController
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class AbxUiState(
    val selectedMode: AbxTestMode = AbxTestMode.FLAC_VS_MP3_320,
    val trialState: AbxTrialState = AbxTrialState(),
    val isPlaying: Boolean = false,
    val isAudioEnginePlaying: Boolean = false,
    val isUsbDacConnected: Boolean = false,
    val currentTrackTitle: String? = null,
    val lastFeedbackMessage: String? = null,
    val isDifferenceMode: Boolean = false,
    /** A valid ABX vote needs X to have been heard this round. */
    val hasHeardX: Boolean = false,
    /** FLAC/WAV songs from the library that can be used as the test audio. */
    val libraryTracks: List<AudioTrackModel> = emptyList(),
    /** The library song being tested, or null while the built-in test music is in use. */
    val clipTrack: AudioTrackModel? = null,
    val clipStartMs: Long = 0L,
    val clipLoading: Boolean = false,
    val clipError: String? = null
) {
    /** The "library song" mode has no built-in audio, so it needs a song chosen first. */
    val canPlay: Boolean
        get() = !clipLoading && (selectedMode != AbxTestMode.CUSTOM_LIBRARY_TRACK || clipTrack != null)
}

class AbxViewModel(
    private val abxGameEngine: AbxGameEngine,
    private val playbackController: PlaybackController,
    observeLibraryUseCase: ObserveLibraryUseCase,
    private val clipLoader: AbxClipLoader,
    private val abxAudioPlayer: AbxAudioPlayer = AbxAudioPlayer(abxGameEngine)
) : ViewModel() {

    private val _uiState = MutableStateFlow(AbxUiState())
    val uiState: StateFlow<AbxUiState> = _uiState.asStateFlow()

    val spectrum: StateFlow<List<Float>> = abxAudioPlayer.spectrum

    init {
        startNewGame(AbxTestMode.FLAC_VS_MP3_320, 10)
        observePlayback()
        observeAudioEngine()
        viewModelScope.launch {
            observeLibraryUseCase().collect { all ->
                val usable = all.filter { it.format.isLossless && !it.format.isDsd }
                    .sortedWith(compareBy({ it.artist?.lowercase() ?: "~" }, { it.title.lowercase() }))
                _uiState.update { it.copy(libraryTracks = usable) }
            }
        }
    }

    private var clipJob: Job? = null

    /** Uses an excerpt of [track] as the test audio. Restarts the trial, since the audio changed. */
    fun chooseLibraryTrack(track: AudioTrackModel, startFraction: Float = 0.30f) {
        clipJob?.cancel()
        abxAudioPlayer.stop()
        _uiState.update { it.copy(clipLoading = true, clipError = null) }
        clipJob = viewModelScope.launch {
            try {
                val clip = clipLoader.load(track, startFraction)
                abxAudioPlayer.setClip(clip)
                _uiState.update { it.copy(clipTrack = track, clipStartMs = clip.startMs, clipLoading = false) }
                startNewGame(_uiState.value.selectedMode, _uiState.value.trialState.totalRounds)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.update { it.copy(clipLoading = false, clipError = e.message ?: "Couldn't read that song.") }
            }
        }
    }

    /** Same song, a different 20 seconds of it. */
    fun newExcerpt() {
        val track = _uiState.value.clipTrack ?: return
        chooseLibraryTrack(track, startFraction = 0.15f + kotlin.random.Random.nextFloat() * 0.55f)
    }

    /** Back to the built-in test music. */
    fun useBuiltInAudio() {
        clipJob?.cancel()
        abxAudioPlayer.setClip(null)
        _uiState.update { it.copy(clipTrack = null, clipLoading = false, clipError = null) }
        startNewGame(_uiState.value.selectedMode, _uiState.value.trialState.totalRounds)
    }

    private fun observePlayback() {
        viewModelScope.launch {
            playbackController.state.collect { playback ->
                _uiState.update {
                    it.copy(
                        isPlaying = playback.isPlaying,
                        currentTrackTitle = playback.currentTrack?.title
                    )
                }
            }
        }
    }

    private fun observeAudioEngine() {
        viewModelScope.launch {
            abxAudioPlayer.isPlaying.collect { playing ->
                _uiState.update { it.copy(isAudioEnginePlaying = playing) }
            }
        }
    }

    fun selectMode(mode: AbxTestMode) {
        _uiState.update { it.copy(selectedMode = mode) }
        startNewGame(mode, _uiState.value.trialState.totalRounds)
    }

    fun startNewGame(mode: AbxTestMode = _uiState.value.selectedMode, rounds: Int = 10) {
        abxAudioPlayer.stop()
        val newState = abxGameEngine.startNewGame(mode, rounds)
        _uiState.update {
            it.copy(
                selectedMode = mode,
                trialState = newState,
                isDifferenceMode = false,
                hasHeardX = false,
                lastFeedbackMessage = "New ${mode.title} challenge started! Listen to A, B, and X, then vote."
            )
        }
    }

    fun selectSample(sample: AbxSample) {
        val currentState = _uiState.value
        if (!currentState.canPlay) {
            _uiState.update { it.copy(lastFeedbackMessage = "Choose a song from your library first.") }
            return
        }
        val updatedState = abxGameEngine.setActiveSample(sample)

        abxAudioPlayer.playSample(
            sample = sample,
            mode = currentState.selectedMode,
            isXAssignedToA = currentState.trialState.isSampleXAssignedToA
        )

        _uiState.update {
            it.copy(
                trialState = updatedState,
                isDifferenceMode = (sample == AbxSample.DIFF),
                hasHeardX = it.hasHeardX || sample == AbxSample.X,
                lastFeedbackMessage = when (sample) {
                    AbxSample.A -> "Now playing Sample A (${currentState.selectedMode.sampleATitle})"
                    AbxSample.B -> "Now playing Sample B (${currentState.selectedMode.sampleBTitle})"
                    AbxSample.X -> "Now playing Mystery X. Listen carefully and vote below!"
                    AbxSample.DIFF -> "Difference Audition (A - B) amplified +12 dB. This is what compression removed!"
                    AbxSample.NONE -> null
                }
            )
        }
    }

    fun togglePlayPauseCurrent() {
        val currentState = _uiState.value
        val currentSample = currentState.trialState.activePlayingSample
        if (currentState.isAudioEnginePlaying) {
            stopAudio()
        } else {
            val target = if (currentSample != AbxSample.NONE) currentSample else AbxSample.A
            selectSample(target)
        }
    }

    fun playDifference() {
        selectSample(AbxSample.DIFF)
    }

    fun stopAudio() {
        abxAudioPlayer.stop()
        val updatedState = abxGameEngine.setActiveSample(AbxSample.NONE)
        _uiState.update {
            it.copy(
                trialState = updatedState,
                isDifferenceMode = false
            )
        }
    }

    fun submitGuess(guessIsA: Boolean) {
        // After the last round the engine would keep counting correct answers past the total.
        if (_uiState.value.trialState.isCompleted) return
        abxAudioPlayer.stop()
        val (isCorrect, updatedTrial) = abxGameEngine.submitGuess(guessIsA)
        val feedback = if (isCorrect) {
            "✓ Correct! Mystery X was ${if (guessIsA) "Sample A" else "Sample B"}."
        } else {
            "✗ Incorrect! Mystery X was actually ${if (guessIsA) "Sample B" else "Sample A"}."
        }

        _uiState.update {
            it.copy(
                trialState = updatedTrial,
                isDifferenceMode = false,
                hasHeardX = false,
                lastFeedbackMessage = feedback
            )
        }
    }

    override fun onCleared() {
        super.onCleared()
        abxAudioPlayer.release()
    }
}
