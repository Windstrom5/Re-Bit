package com.windstrom5.rebit.streaming

import android.content.Context
import android.net.Uri
import com.windstrom5.rebit.domain.model.AudioFileFormat
import com.windstrom5.rebit.domain.model.AudioTrackModel
import com.windstrom5.rebit.domain.model.PcmFormat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Single entry point for everything streaming-related. The UI talks to this for
 * sign-in, search and playback; PlaybackController talks to it only through
 * [StreamSourceResolver].
 */
class StreamingManager(context: Context) : StreamSourceResolver {

    private val store = StreamingCredentialStore(context)
    private val cache = StreamCache(context)
    private val resolveMutex = Mutex()

    val qobuz = QobuzClient(store)
    val tidal = TidalClient(store)
    val spotify = SpotifyClient(store)

    private val _signedIn = MutableStateFlow(currentSignedIn())
    /** Providers that currently hold a valid session. */
    val signedIn: StateFlow<Set<StreamingProvider>> = _signedIn.asStateFlow()

    private val _buffer = MutableStateFlow<BufferState?>(null)
    /** Non-null while a remote track is being buffered before playback. */
    val buffer: StateFlow<BufferState?> = _buffer.asStateFlow()

    private val _preferHiRes = MutableStateFlow(store.get(K_HIRES) != "0")
    val preferHiRes: StateFlow<Boolean> = _preferHiRes.asStateFlow()

    fun setPreferHiRes(enabled: Boolean) {
        store.put(K_HIRES, if (enabled) "1" else "0")
        _preferHiRes.value = enabled
    }

    fun refreshSessions() {
        _signedIn.value = currentSignedIn()
    }

    fun signOut(provider: StreamingProvider) {
        when (provider) {
            StreamingProvider.QOBUZ -> qobuz.logout()
            StreamingProvider.TIDAL -> tidal.logout()
            StreamingProvider.SPOTIFY -> spotify.logout()
        }
        refreshSessions()
    }

    suspend fun search(provider: StreamingProvider, query: String): List<StreamTrack> = when (provider) {
        StreamingProvider.QOBUZ -> qobuz.search(query)
        StreamingProvider.TIDAL -> tidal.search(query)
        StreamingProvider.SPOTIFY -> spotify.search(query)
    }

    /** Wraps a remote result so the existing queue/player can hold it like any other track. */
    fun toTrackModel(t: StreamTrack): AudioTrackModel = AudioTrackModel(
        id = 0L, // 0 = "not in the local library": skips library-only bookkeeping
        filePath = t.uri,
        title = t.title,
        artist = t.artist,
        durationMs = t.durationMs,
        format = AudioFileFormat.FLAC,
        pcm = PcmFormat(t.maxSampleRateHz.coerceAtLeast(44_100), t.maxBitDepth.coerceAtLeast(16), 2),
        fileSizeBytes = 0L,
        dateAddedEpochMs = System.currentTimeMillis()
    )

    /** Downloads (or reuses) the FLAC for a stream:// path and reports its real format. */
    override suspend fun resolve(streamUri: String): ResolvedStream = resolveMutex.withLock {
        val (provider, remoteId) = StreamSourceResolver.parse(streamUri)
            ?: throw StreamingException("Unrecognised streaming track.")
        val hiRes = _preferHiRes.value
        val title = "${provider.displayName} track"
        try {
            val (url, pcm) = when (provider) {
                StreamingProvider.QOBUZ -> qobuz.fileUrl(remoteId, hiRes).let { it.url to PcmFormat(it.sampleRateHz, it.bitDepth, 2) }
                StreamingProvider.TIDAL -> tidal.fileUrl(remoteId).let { it.url to PcmFormat(it.sampleRateHz, it.bitDepth, 2) }
                StreamingProvider.SPOTIFY -> throw StreamingException(
                    "Spotify audio can't be routed through the USB driver. Use “Play in Spotify” instead."
                )
            }
            val key = "${provider.id}_${remoteId}_${pcm.bitDepth}_${pcm.sampleRateHz}"
            _buffer.value = BufferState(title, 0L, 0L)
            val file = cache.fetch(key, url) { read, total -> _buffer.value = BufferState(title, read, total) }
            cache.trim()
            ResolvedStream(Uri.fromFile(file).toString(), pcm)
        } finally {
            _buffer.value = null
        }
    }

    /** Called from MainActivity when Android hands back the Spotify redirect. */
    suspend fun handleSpotifyRedirect(uri: Uri) {
        spotify.handleRedirect(uri)
        refreshSessions()
    }

    fun clearBuffer() = cache.clear()

    private fun currentSignedIn(): Set<StreamingProvider> = buildSet {
        if (qobuz.isLoggedIn) add(StreamingProvider.QOBUZ)
        if (tidal.isLoggedIn) add(StreamingProvider.TIDAL)
        if (spotify.isLoggedIn) add(StreamingProvider.SPOTIFY)
    }

    private companion object {
        const val K_HIRES = "pref_hires"
    }
}
