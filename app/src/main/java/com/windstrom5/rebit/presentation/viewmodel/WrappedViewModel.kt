package com.windstrom5.rebit.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.windstrom5.rebit.data.history.ListeningRepository
import com.windstrom5.rebit.data.history.WrappedData
import com.windstrom5.rebit.data.history.WrappedPeriod
import com.windstrom5.rebit.data.wikipedia.WikiLookup
import com.windstrom5.rebit.data.wikipedia.WikipediaRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class WrappedUiState(
    val period: WrappedPeriod = WrappedPeriod.MONTH,
    val loading: Boolean = true,
    val data: WrappedData? = null,
    /** Wikipedia portrait of the #1 artist, if one was found. */
    val topArtistImageUrl: String? = null,
    /** "Headphones · DAC · Amp" from My setup, if filled in. */
    val gearLine: String? = null
)

class WrappedViewModel(
    private val listening: ListeningRepository,
    private val wikipedia: WikipediaRepository,
    gearPrefs: com.windstrom5.rebit.data.gear.GearPrefs
) : ViewModel() {
    private val gearLine = gearPrefs.load().summary.takeIf { it.isNotBlank() }

    private val _state = MutableStateFlow(WrappedUiState())
    val state: StateFlow<WrappedUiState> = _state

    private var job: Job? = null

    init { load(WrappedPeriod.MONTH) }

    fun setPeriod(period: WrappedPeriod) {
        if (period != _state.value.period) load(period)
    }

    private fun load(period: WrappedPeriod) {
        job?.cancel()
        _state.value = WrappedUiState(period = period, loading = true, gearLine = gearLine)
        job = viewModelScope.launch {
            val data = listening.wrapped(period)
            _state.update { it.copy(loading = false, data = data) }
            val top = data.topArtists.firstOrNull()?.artist ?: return@launch
            val url = (wikipedia.lookupArtist(top) as? WikiLookup.Found)?.summary?.thumbnailUrl
            _state.update { if (it.period == period) it.copy(topArtistImageUrl = url) else it }
        }
    }
}
