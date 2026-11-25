package com.windstrom5.rebit.streaming

import com.windstrom5.rebit.domain.model.PcmFormat

/**
 * Online catalogues the app can sign in to with the listener's OWN account.
 *
 * Only [QOBUZ] and [TIDAL] hand out plain (non-DRM) FLAC, so only those can be
 * routed through the bit-perfect USB engine. [SPOTIFY] never exposes decodable
 * audio to third-party apps — it is supported as a remote control for the
 * official Spotify app (see [SpotifyClient]) and is NOT bit-perfect.
 */
enum class StreamingProvider(val id: String, val displayName: String, val bitPerfectCapable: Boolean) {
    QOBUZ("qobuz", "Qobuz", true),
    TIDAL("tidal", "Tidal", true),
    SPOTIFY("spotify", "Spotify", false);

    companion object {
        fun fromId(id: String): StreamingProvider? = entries.firstOrNull { it.id == id }
    }
}

/** One search result from a streaming catalogue. */
data class StreamTrack(
    val provider: StreamingProvider,
    val remoteId: String,
    val title: String,
    val artist: String,
    val album: String?,
    val durationMs: Long,
    val artworkUrl: String?,
    val maxSampleRateHz: Int,
    val maxBitDepth: Int,
    val streamable: Boolean = true
) {
    /** Synthetic path stored in [com.windstrom5.rebit.domain.model.AudioTrackModel.filePath]. */
    val uri: String get() = StreamSourceResolver.buildUri(provider, remoteId)

    val qualityLabel: String
        get() = when {
            maxBitDepth <= 0 -> "Lossy"
            maxBitDepth > 16 || maxSampleRateHz > 48_000 ->
                "${maxBitDepth}-bit / ${formatKhz(maxSampleRateHz)} kHz"
            else -> "CD 16-bit / 44.1 kHz"
        }

    private fun formatKhz(hz: Int): String =
        if (hz % 1000 == 0) (hz / 1000).toString() else String.format(java.util.Locale.US, "%.1f", hz / 1000.0)
}

/** A remote track downloaded into the app cache, ready for the normal decoders. */
data class ResolvedStream(val fileUri: String, val pcm: PcmFormat)

/** Download progress shown while a remote track is being buffered. */
data class BufferState(val title: String, val bytesRead: Long, val totalBytes: Long) {
    val fraction: Float
        get() = if (totalBytes > 0) (bytesRead.toFloat() / totalBytes).coerceIn(0f, 1f) else 0f
}

/**
 * Implemented by [StreamingManager]; consumed by PlaybackController so the
 * playback layer never depends on any provider-specific code.
 */
interface StreamSourceResolver {
    /** Turns a `stream://provider/id` path into a local file:// FLAC plus its real PCM format. */
    suspend fun resolve(streamUri: String): ResolvedStream

    companion object {
        const val SCHEME = "stream://"

        fun buildUri(provider: StreamingProvider, remoteId: String) = "$SCHEME${provider.id}/$remoteId"

        fun isStreamUri(path: String) = path.startsWith(SCHEME)

        /** Splits `stream://qobuz/123` into (QOBUZ, "123"), or null if malformed. */
        fun parse(path: String): Pair<StreamingProvider, String>? {
            if (!isStreamUri(path)) return null
            val rest = path.removePrefix(SCHEME)
            val slash = rest.indexOf('/')
            if (slash <= 0 || slash == rest.lastIndex) return null
            val provider = StreamingProvider.fromId(rest.substring(0, slash)) ?: return null
            return provider to rest.substring(slash + 1)
        }
    }
}
