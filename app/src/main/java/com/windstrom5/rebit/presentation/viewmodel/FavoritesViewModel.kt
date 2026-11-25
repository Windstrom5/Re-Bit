package com.windstrom5.rebit.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.windstrom5.rebit.data.history.ListeningRepository
import com.windstrom5.rebit.domain.model.AudioTrackModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Liked songs, shared by the player (heart button), library (heart + filter) and Home. */
class FavoritesViewModel(private val listening: ListeningRepository) : ViewModel() {

    val favoritePaths: StateFlow<Set<String>> = listening.favoritePaths()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    fun toggle(track: AudioTrackModel) {
        viewModelScope.launch {
            listening.toggleFavorite(track, currentlyFavorite = track.filePath in favoritePaths.value)
        }
    }
}
