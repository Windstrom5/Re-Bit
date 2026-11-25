package com.windstrom5.rebit.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.windstrom5.rebit.domain.audiophile.LibraryInsights
import com.windstrom5.rebit.domain.audiophile.LibraryInsightsData
import com.windstrom5.rebit.domain.audiophile.TrackSpec
import com.windstrom5.rebit.domain.usecase.ObserveLibraryUseCase
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/** Resolution / format breakdown of the whole library. */
class InsightsViewModel(observeLibrary: ObserveLibraryUseCase) : ViewModel() {
    val insights: StateFlow<LibraryInsightsData?> = observeLibrary()
        .map { tracks ->
            LibraryInsights.compute(
                tracks.map {
                    TrackSpec(it.pcm.sampleRateHz, it.pcm.bitDepth, it.format.name, it.durationMs, it.fileSizeBytes, it.title)
                }
            )
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
}
