package com.windstrom5.rebit.presentation.viewmodel

import androidx.lifecycle.ViewModel
import com.windstrom5.rebit.data.audio.TestSignalPlayer
import com.windstrom5.rebit.domain.audiophile.SignalConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

data class TestSignalsUiState(val config: SignalConfig = SignalConfig(), val playing: Boolean = false)

/** State for the Listening lab. Owns the player so the tone stops when the screen goes away. */
class TestSignalsViewModel : ViewModel() {
    private val player = TestSignalPlayer()
    private val _state = MutableStateFlow(TestSignalsUiState())
    val state: StateFlow<TestSignalsUiState> = _state

    fun update(transform: (SignalConfig) -> SignalConfig) {
        _state.update { it.copy(config = transform(it.config)) }
        player.setConfig(_state.value.config)
    }

    fun start() {
        player.setConfig(_state.value.config)
        player.start()
        _state.update { it.copy(playing = true) }
    }

    fun stop() {
        player.stop()
        _state.update { it.copy(playing = false) }
    }

    override fun onCleared() {
        player.release()
    }
}
