package com.windstrom5.rebit.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.windstrom5.rebit.domain.usecase.PlayTracksUseCase
import com.windstrom5.rebit.streaming.BufferState
import com.windstrom5.rebit.streaming.StreamTrack
import com.windstrom5.rebit.streaming.StreamingManager
import com.windstrom5.rebit.streaming.StreamingProvider
import com.windstrom5.rebit.streaming.TidalClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class StreamingUiState(
    val provider: StreamingProvider = StreamingProvider.QOBUZ,
    val query: String = "",
    val results: List<StreamTrack> = emptyList(),
    val searching: Boolean = false,
    val searched: Boolean = false,
    val signingIn: Boolean = false,
    val tidalCode: TidalClient.DeviceCode? = null,
    val message: String? = null,
    val playingUri: String? = null
)

class StreamingViewModel(
    private val manager: StreamingManager,
    private val playTracks: PlayTracksUseCase
) : ViewModel() {

    private val _state = MutableStateFlow(StreamingUiState())
    val state: StateFlow<StreamingUiState> = _state.asStateFlow()

    val signedIn: StateFlow<Set<StreamingProvider>> = manager.signedIn
    val buffer: StateFlow<BufferState?> = manager.buffer
    val preferHiRes: StateFlow<Boolean> = manager.preferHiRes

    /** Browser links the screen should open (Tidal approval page, Spotify sign-in). */
    private val _openUrl = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val openUrl: SharedFlow<String> = _openUrl.asSharedFlow()

    val savedQobuzAppId get() = manager.qobuz.savedAppId
    val savedTidalClientId get() = manager.tidal.savedClientId
    val savedSpotifyClientId get() = manager.spotify.savedClientId

    private var tidalLoginJob: Job? = null

    fun selectProvider(provider: StreamingProvider) = _state.update {
        it.copy(provider = provider, results = emptyList(), searched = false, message = null)
    }

    fun onQueryChange(q: String) = _state.update { it.copy(query = q) }

    fun dismissMessage() = _state.update { it.copy(message = null) }

    fun setPreferHiRes(enabled: Boolean) = manager.setPreferHiRes(enabled)

    fun search() {
        val s = _state.value
        if (s.query.isBlank() || s.searching) return
        _state.update { it.copy(searching = true, message = null) }
        viewModelScope.launch {
            try {
                val found = manager.search(s.provider, s.query.trim())
                _state.update { it.copy(results = found, searching = false, searched = true) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(searching = false, message = e.message ?: "Search failed.") }
            }
        }
    }

    fun play(track: StreamTrack) {
        if (!track.streamable) {
            _state.update { it.copy(message = "This track isn't available to stream on your account.") }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(playingUri = track.uri, message = null) }
            try {
                if (track.provider == StreamingProvider.SPOTIFY) {
                    // Spotify: the official app plays it — not bit-perfect, by design.
                    manager.spotify.playInSpotifyApp(track.remoteId)
                } else {
                    val queue = _state.value.results
                        .filter { it.provider == track.provider && it.streamable }
                        .map(manager::toTrackModel)
                    playTracks(queue, manager.toTrackModel(track))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(message = e.message ?: "Playback failed.") }
            }
        }
    }

    // ── Sign-in ────────────────────────────────────────────────────────────

    fun signInQobuz(user: String, password: String, appId: String, appSecret: String) {
        launchSignIn {
            manager.qobuz.login(user, password, appId, appSecret)
        }
    }

    fun signInTidal(clientId: String, clientSecret: String) {
        tidalLoginJob?.cancel()
        tidalLoginJob = viewModelScope.launch {
            _state.update { it.copy(signingIn = true, message = null) }
            try {
                val code = manager.tidal.beginDeviceLogin(clientId, clientSecret)
                _state.update { it.copy(tidalCode = code) }
                _openUrl.tryEmit(code.verificationUrl)
                manager.tidal.completeDeviceLogin(code)
                manager.refreshSessions()
                _state.update { it.copy(signingIn = false, tidalCode = null) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(signingIn = false, tidalCode = null, message = e.message ?: "Tidal sign-in failed.") }
            }
        }
    }

    fun cancelTidalSignIn() {
        tidalLoginJob?.cancel()
        _state.update { it.copy(signingIn = false, tidalCode = null) }
    }

    fun signInSpotify(clientId: String) {
        try {
            _openUrl.tryEmit(manager.spotify.buildAuthUri(clientId).toString())
        } catch (e: IllegalArgumentException) {
            _state.update { it.copy(message = e.message) }
        }
    }

    fun signOut(provider: StreamingProvider) {
        manager.signOut(provider)
        _state.update { it.copy(results = emptyList(), searched = false, message = null) }
    }

    fun clearBuffer() = manager.clearBuffer()

    private fun launchSignIn(block: suspend () -> Unit) {
        viewModelScope.launch {
            _state.update { it.copy(signingIn = true, message = null) }
            try {
                block()
                manager.refreshSessions()
                _state.update { it.copy(signingIn = false) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(signingIn = false, message = e.message ?: "Sign-in failed.") }
            }
        }
    }
}
