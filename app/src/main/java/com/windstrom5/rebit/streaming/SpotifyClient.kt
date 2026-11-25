package com.windstrom5.rebit.streaming

import android.net.Uri
import android.util.Base64
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Spotify through its official Web API only.
 *
 * Spotify encrypts its audio and never gives third-party apps the decoded
 * samples, so this app CANNOT play Spotify through the USB DAC driver (and
 * Spotify tops out at lossy 320 kbps anyway). What it can do, legitimately:
 * sign in with your own account via OAuth PKCE, search the catalogue, and tell
 * the official Spotify app (Spotify Connect) to play a track. Audio then
 * comes out of the Spotify app's own path — not bit-perfect.
 *
 * Requires a Spotify Premium account and a client id from
 * developer.spotify.com with redirect URI [REDIRECT_URI] registered.
 */
class SpotifyClient(private val store: StreamingCredentialStore) {

    val isLoggedIn: Boolean get() = !store.get(K_REFRESH).isNullOrEmpty()
    val savedClientId: String get() = store.get(K_CLIENT_ID).orEmpty()

    private var pendingVerifier: String? = null
    private var pendingState: String? = null

    /** Builds the browser URL for the PKCE authorization-code flow. */
    fun buildAuthUri(clientId: String): Uri {
        require(clientId.isNotBlank()) { "Enter your Spotify client id." }
        store.put(K_CLIENT_ID, clientId.trim())
        val verifier = randomUrlSafe(64)
        val state = randomUrlSafe(16)
        pendingVerifier = verifier
        pendingState = state
        val challenge = Base64.encodeToString(
            MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)),
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
        )
        return Uri.parse("https://accounts.spotify.com/authorize").buildUpon()
            .appendQueryParameter("client_id", clientId.trim())
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("redirect_uri", REDIRECT_URI)
            .appendQueryParameter("scope", SCOPES)
            .appendQueryParameter("code_challenge_method", "S256")
            .appendQueryParameter("code_challenge", challenge)
            .appendQueryParameter("state", state)
            .build()
    }

    /** Finishes sign-in from the `bitperfectusb://spotify-callback?...` redirect. */
    suspend fun handleRedirect(uri: Uri) {
        uri.getQueryParameter("error")?.let { throw StreamingException("Spotify sign-in was cancelled ($it).") }
        val code = uri.getQueryParameter("code") ?: throw StreamingException("Spotify returned no authorization code.")
        if (uri.getQueryParameter("state") != pendingState) throw StreamingException("Spotify sign-in state mismatch.")
        val verifier = pendingVerifier ?: throw StreamingException("Start Spotify sign-in from the app first.")
        val clientId = store.get(K_CLIENT_ID) ?: throw StreamingException("Spotify is not set up.")
        val json = StreamingHttp.request(
            url = TOKEN_URL,
            method = "POST",
            form = mapOf(
                "client_id" to clientId,
                "grant_type" to "authorization_code",
                "code" to code,
                "redirect_uri" to REDIRECT_URI,
                "code_verifier" to verifier
            )
        )
        saveTokens(json)
        pendingVerifier = null
        pendingState = null
    }

    fun logout() = store.remove(K_ACCESS, K_REFRESH, K_EXPIRES)

    suspend fun search(query: String, limit: Int = 30): List<StreamTrack> {
        val json = StreamingHttp.request(
            url = "$API/search?" + StreamingHttp.query("q" to query, "type" to "track", "limit" to limit.toString()),
            headers = bearer()
        )
        val items = json.optJSONObject("tracks")?.optJSONArray("items") ?: return emptyList()
        return (0 until items.length()).mapNotNull { i ->
            val o = items.optJSONObject(i) ?: return@mapNotNull null
            val album = o.optJSONObject("album")
            StreamTrack(
                provider = StreamingProvider.SPOTIFY,
                remoteId = o.optStringOrNull("id") ?: return@mapNotNull null,
                title = o.optString("name"),
                artist = o.optJSONArray("artists")?.optJSONObject(0)?.optStringOrNull("name") ?: "Unknown artist",
                album = album?.optStringOrNull("name"),
                durationMs = o.optLong("duration_ms"),
                artworkUrl = album?.optJSONArray("images")?.optJSONObject(0)?.optStringOrNull("url"),
                maxSampleRateHz = 44_100,
                maxBitDepth = 0, // lossy
                streamable = o.optBoolean("is_playable", true)
            )
        }
    }

    /** Asks the official Spotify app (active Connect device) to play [trackId]. */
    suspend fun playInSpotifyApp(trackId: String) {
        try {
            StreamingHttp.request(
                url = "$API/me/player/play",
                method = "PUT",
                headers = bearer(),
                jsonBody = JSONObject().put("uris", org.json.JSONArray().put("spotify:track:$trackId")).toString()
            )
        } catch (e: StreamingException) {
            throw when (e.httpCode) {
                404 -> StreamingException("No active Spotify device. Open the Spotify app, play anything once, then try again.", 404)
                403 -> StreamingException("Spotify needs a Premium account to be controlled remotely.", 403)
                else -> e
            }
        }
    }

    private suspend fun bearer(): Map<String, String> {
        val expiry = store.get(K_EXPIRES)?.toLongOrNull() ?: 0L
        if (store.get(K_ACCESS).isNullOrEmpty() || System.currentTimeMillis() > expiry - 60_000) refresh()
        val token = store.get(K_ACCESS) ?: throw StreamingException("Sign in to Spotify first.")
        return mapOf("Authorization" to "Bearer $token")
    }

    private suspend fun refresh() {
        val refreshToken = store.get(K_REFRESH) ?: throw StreamingException("Sign in to Spotify first.")
        val clientId = store.get(K_CLIENT_ID) ?: throw StreamingException("Spotify is not set up.")
        val json = StreamingHttp.request(
            url = TOKEN_URL,
            method = "POST",
            form = mapOf("client_id" to clientId, "grant_type" to "refresh_token", "refresh_token" to refreshToken)
        )
        saveTokens(json)
    }

    private fun saveTokens(json: JSONObject) {
        val access = json.optStringOrNull("access_token")
            ?: throw StreamingException("Spotify did not return an access token.")
        store.put(K_ACCESS, access)
        json.optStringOrNull("refresh_token")?.let { store.put(K_REFRESH, it) }
        store.put(K_EXPIRES, (System.currentTimeMillis() + json.optLong("expires_in", 3600) * 1000L).toString())
    }

    private fun randomUrlSafe(bytes: Int): String {
        val raw = ByteArray(bytes).also { SecureRandom().nextBytes(it) }
        return Base64.encodeToString(raw, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }

    companion object {
        const val REDIRECT_URI = "bitperfectusb://spotify-callback"
        private const val SCOPES = "user-modify-playback-state user-read-playback-state"
        private const val TOKEN_URL = "https://accounts.spotify.com/api/token"
        private const val API = "https://api.spotify.com/v1"
        private const val K_CLIENT_ID = "spotify_client_id"
        private const val K_ACCESS = "spotify_access"
        private const val K_REFRESH = "spotify_refresh"
        private const val K_EXPIRES = "spotify_expires"
    }
}
