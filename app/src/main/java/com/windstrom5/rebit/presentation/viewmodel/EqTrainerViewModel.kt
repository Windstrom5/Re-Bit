package com.windstrom5.rebit.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.windstrom5.rebit.domain.abx.AbxClipLoader
import com.windstrom5.rebit.domain.eqtrainer.EqAnswerResult
import com.windstrom5.rebit.domain.eqtrainer.EqDifficulty
import com.windstrom5.rebit.domain.eqtrainer.EqGameState
import com.windstrom5.rebit.domain.eqtrainer.EqMode
import com.windstrom5.rebit.domain.eqtrainer.EqQuestion
import com.windstrom5.rebit.domain.eqtrainer.EqTrainerGame
import com.windstrom5.rebit.domain.eqtrainer.EqTrainerPlayer
import com.windstrom5.rebit.domain.eqtrainer.EqTrainerStore
import com.windstrom5.rebit.domain.model.AudioFileFormat
import com.windstrom5.rebit.domain.model.AudioTrackModel
import com.windstrom5.rebit.domain.usecase.ObserveLibraryUseCase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class EqTrainerUiState(
    val game: EqGameState,
    val playing: Boolean = false,
    /** True while the hidden EQ band is applied; false plays the unaltered sound for comparison. */
    val eqOn: Boolean = true,
    val lastResult: EqAnswerResult? = null,
    val newBest: Boolean = false,
    val libraryTracks: List<AudioTrackModel> = emptyList(),
    /** The library song used as the test sound, or null while pink noise is playing. */
    val clipTrack: AudioTrackModel? = null,
    val clipStartMs: Long = 0L,
    val clipLoading: Boolean = false,
    val clipError: String? = null
)

class EqTrainerViewModel(
    observeLibraryUseCase: ObserveLibraryUseCase,
    private val clipLoader: AbxClipLoader,
    private val store: EqTrainerStore,
    private val player: EqTrainerPlayer,
    private val engine: EqTrainerGame = EqTrainerGame()
) : ViewModel() {

    private val _state = MutableStateFlow(EqTrainerUiState(game = engine.newGame(EqDifficulty.EASY, EqMode.BOOST, 10)))
    val state: StateFlow<EqTrainerUiState> = _state.asStateFlow()

    private var clipJob: Job? = null

    init {
        applyBand(_state.value.game.current)
        viewModelScope.launch { player.isPlaying.collect { p -> _state.update { it.copy(playing = p) } } }
        viewModelScope.launch { player.eqOn.collect { on -> _state.update { it.copy(eqOn = on) } } }
        viewModelScope.launch {
            observeLibraryUseCase().collect { all ->
                val usable = all.filter { it.format.isLossless && !it.format.isDsd }
                    .sortedWith(compareBy({ it.artist?.lowercase() ?: "~" }, { it.title.lowercase() }))
                _state.update { it.copy(libraryTracks = usable) }
            }
        }
    }

    fun startNewGame(difficulty: EqDifficulty = _state.value.game.difficulty, mode: EqMode = _state.value.game.mode, rounds: Int = _state.value.game.totalRounds) {
        val game = engine.newGame(difficulty, mode, rounds)
        _state.update { it.copy(game = game, lastResult = null, newBest = false) }
        applyBand(game.current)
    }

    fun togglePlay() {
        if (player.isPlaying.value) player.stop() else player.start()
    }

    fun stopAudio() = player.stop()

    fun setEqOn(on: Boolean) = player.setEqEnabled(on)

    /** Scores the guess and moves to the next round; the EQ'd sound keeps playing. */
    fun answer(guessedHz: Int) {
        val current = _state.value.game
        val (next, result) = engine.answer(current, guessedHz) ?: return
        _state.update { it.copy(game = next, lastResult = result) }
        if (next.finished) {
            player.stop()
            val best = store.record(next.difficulty, next.accuracyPercent, next.totalRounds)
            _state.update { it.copy(newBest = best) }
        } else {
            applyBand(next.current)
        }
    }

    // ── Test sound: pink noise or a library song ──

    fun chooseLibraryTrack(track: AudioTrackModel, startFraction: Float = 0.30f) {
        clipJob?.cancel()
        player.stop()
        _state.update { it.copy(clipLoading = true, clipError = null) }
        clipJob = viewModelScope.launch {
            try {
                val clip = clipLoader.load(track, startFraction)
                player.setClip(clip)
                _state.update { it.copy(clipTrack = track, clipStartMs = clip.startMs, clipLoading = false) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(clipLoading = false, clipError = e.message ?: "Couldn't read that song.") }
            }
        }
    }

    fun newExcerpt() {
        val track = _state.value.clipTrack ?: return
        chooseLibraryTrack(track, 0.15f + kotlin.random.Random.nextFloat() * 0.55f)
    }

    fun usePinkNoise() {
        clipJob?.cancel()
        player.setClip(null)
        _state.update { it.copy(clipTrack = null, clipLoading = false, clipError = null) }
    }

    private fun applyBand(q: EqQuestion?) {
        if (q != null) player.setBand(q.frequencyHz, q.gainDb, q.q)
    }

    override fun onCleared() {
        player.release()
        super.onCleared()
    }
}
