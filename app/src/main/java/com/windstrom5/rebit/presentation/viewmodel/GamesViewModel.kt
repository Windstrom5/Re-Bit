package com.windstrom5.rebit.presentation.viewmodel

import androidx.lifecycle.ViewModel
import com.windstrom5.rebit.domain.eqtrainer.EqDifficulty
import com.windstrom5.rebit.domain.eqtrainer.EqTrainerStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Feeds the Games hub with the player's best scores. */
class GamesViewModel(private val store: EqTrainerStore) : ViewModel() {

    private val _eqBest = MutableStateFlow(read())
    /** Best accuracy per difficulty for games that have been played. */
    val eqBest: StateFlow<Map<EqDifficulty, Int>> = _eqBest.asStateFlow()

    private val _eqPlayed = MutableStateFlow(store.gamesPlayed)
    val eqGamesPlayed: StateFlow<Int> = _eqPlayed.asStateFlow()

    /** Called when the hub appears, since scores change while a game screen is open. */
    fun refresh() {
        _eqBest.value = read()
        _eqPlayed.value = store.gamesPlayed
    }

    private fun read(): Map<EqDifficulty, Int> =
        EqDifficulty.entries.associateWith { store.best(it) }.filterValues { it >= 0 }
}
