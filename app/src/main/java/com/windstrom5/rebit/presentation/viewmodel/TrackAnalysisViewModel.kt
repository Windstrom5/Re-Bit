package com.windstrom5.rebit.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.windstrom5.rebit.data.audio.TrackAnalysisRepository
import com.windstrom5.rebit.domain.audiophile.TrackAnalysis
import com.windstrom5.rebit.domain.model.AudioTrackModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class AnalysisUi(
    val trackPath: String? = null,
    val result: TrackAnalysis? = null,
    val running: Boolean = false,
    val progress: Float = 0f,
    val error: String? = null
)

/** Cached or on-demand measurement of the playing track's dynamics. */
class TrackAnalysisViewModel(private val repo: TrackAnalysisRepository) : ViewModel() {
    private val _state = MutableStateFlow(AnalysisUi())
    val state: StateFlow<AnalysisUi> = _state
    private var current: AudioTrackModel? = null
    private var job: Job? = null

    fun onTrack(track: AudioTrackModel?) {
        if (track?.filePath == current?.filePath) return
        job?.cancel()
        current = track
        _state.value = AnalysisUi(trackPath = track?.filePath, result = track?.let { repo.cached(it) })
    }

    fun measure() {
        val track = current ?: return
        job?.cancel()
        _state.update { it.copy(running = true, progress = 0f, error = null) }
        job = viewModelScope.launch {
            try {
                val r = repo.analyze(track) { p -> _state.update { if (it.trackPath == track.filePath) it.copy(progress = p) else it } }
                _state.update {
                    if (it.trackPath != track.filePath) it
                    else it.copy(
                        running = false, result = r,
                        error = if (r == null) "This track couldn't be analysed (silent or unreadable)." else null
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { if (it.trackPath == track.filePath) it.copy(running = false, error = e.message ?: "Analysis failed.") else it }
            }
        }
    }
}
