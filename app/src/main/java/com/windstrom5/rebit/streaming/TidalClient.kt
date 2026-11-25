package com.windstrom5.rebit.streaming

import android.util.Base64
import kotlinx.coroutines.delay
import org.json.JSONObject

/**
 * Tidal catalogue + lossless stream access for a paying subscriber.
 *
 * Sign-in uses the OAuth 2.0 device-code flow with a client id the listener
 * supplies (nothing is bundled). Playback info comes from Tidal's playback
 * endpoint, which is not part of Tidal's public developer API, so access
 * depends on what Tidal enables for the client id you use.
 *
 * Only the plain-FLAC "LOSSLESS" tier (16-bit / 44.1 kHz) is handled. Tidal's
 * Hi-Res tier is delivered as segmented MPEG-DASH, which this build does not
 * assemble.
 */
class TidalClient(private val store: StreamingCredentialStore) {

    data class DeviceCode(
        val deviceCode: String,
        val userCode: String,
        val verificationUrl: String,
        val expiresInSec: Int,
        val intervalSec: Int
    )

    data class TidalFile(val url: String, val sampleRateHz: Int, val bitDepth: Int)

    val isLoggedIn: Boolean get() = !store.get(K_REFRESH).isNullOrEmpty()
    val savedClientId: String get() = store.get(K_CLIENT_ID).orEmpty()

    suspend fun beginDeviceLogin(clientId: String, clientSecret: String): DeviceCode {
        require(clientId.isNotBlank()) { "Enter your Tidal client id." }
        store.put(K_CLIENT_ID, clientId.trim())
        if (clientSecret.isNotBlank()) store.put(K_CLIENT_SECRET, clientSecret.trim()) else store.remove(K_CLIENT_SECRET)
        val json = StreamingHttp.request(
            url = "$AUTH/device_authorization",
            method = "POST",
            headers = clientAuthHeader(),
            form = mapOf("client_id" to clientId.trim(), "scope" to SCOPE)
        )
        val link = json.optStringOrNull("verificationUriComplete") ?: json.optStringOrNull("verificationUri")
            ?: throw StreamingException("Tidal did not return a sign-in link.")
        return DeviceCode(
            deviceCode = json.getString("deviceCode"),
            userCode = json.optString("userCode"),
            verificationUrl = if (link.startsWith("http")) link else "https://$link",
            expiresInSec = json.optInt("expiresIn", 300),
            intervalSec = json.optInt("interval", 2).coerceAtLeast(2)
        )
    }

    /** Polls until the user approves the code in their browser, or it expires. */
    suspend fun completeDeviceLogin(code: DeviceCode) {
        val clientId = store.get(K_CLIENT_ID) ?: throw StreamingException("Tidal is not set up.")
        val deadline = System.currentTimeMillis() + code.expiresInSec * 1000L
        while (System.currentTimeMillis() < deadline) {
            delay(code.intervalSec * 1000L)
            try {
                val json = StreamingHttp.request(
                    url = "$AUTH/token",
                    method = "POST",
                    headers = clientAuthHeader(),
                    form = mapOf(
                        "client_id" to clientId,
                        "device_code" to code.deviceCode,
                        "grant_type" to "urn:ietf:params:oauth:grant-type:device_code",
                        "scope" to SCOPE
                    )
                )
                saveTokens(json)
                return
            } catch (e: StreamingException) {
                if (e.errorCode == "authorization_pending" || e.errorCode == "slow_down") continue
                throw e
            }
        }
        throw StreamingException("The Tidal sign-in code expired. Start again.")
    }

    fun logout() = store.remove(K_ACCESS, K_REFRESH, K_EXPIRES, K_COUNTRY)

    suspend fun search(query: String, limit: Int = 30): List<StreamTrack> {
        val json = StreamingHttp.request(
            url = "$API/search/tracks?" + StreamingHttp.query(
                "query" to query, "limit" to limit.toString(), "countryCode" to country()
            ),
            headers = bearer()
        )
        val items = json.optJSONArray("items") ?: return emptyList()
        return (0 until items.length()).mapNotNull { i ->
            val o = items.optJSONObject(i) ?: return@mapNotNull null
            val album = o.optJSONObject("album")
            val cover = album?.optStringOrNull("cover")
            StreamTrack(
                provider = StreamingProvider.TIDAL,
                remoteId = o.opt("id")?.toString() ?: return@mapNotNull null,
                title = o.optString("title") + (o.optStringOrNull("version")?.let { " ($it)" } ?: ""),
                artist = o.optJSONObject("artist")?.optStringOrNull("name") ?: "Unknown artist",
                album = album?.optStringOrNull("title"),
                durationMs = o.optLong("duration") * 1000L,
                artworkUrl = cover?.let { "https://resources.tidal.com/images/${it.replace('-', '/')}/320x320.jpg" },
                // Playback here is CD-quality FLAC even for Hi-Res albums (see class doc).
                maxSampleRateHz = 44_100,
                maxBitDepth = 16,
                streamable = o.optBoolean("allowStreaming", true)
            )
        }
    }

    suspend fun fileUrl(trackId: String): TidalFile {
        val json = StreamingHttp.request(
            url = "$API/tracks/$trackId/playbackinfopostpaywall?" + StreamingHttp.query(
                "audioquality" to "LOSSLESS",
                "playbackmode" to "STREAM",
                "assetpresentation" to "FULL"
            ),
            headers = bearer()
        )
        val mime = json.optString("manifestMimeType")
        if (!mime.contains("tidal.bts")) {
            throw StreamingException("Tidal returned a segmented (DASH) stream for this track, which isn't supported yet.")
        }
        val manifest = JSONObject(String(Base64.decode(json.getString("manifest"), Base64.DEFAULT), Charsets.UTF_8))
        val url = manifest.optJSONArray("urls")?.optString(0)?.takeIf { it.isNotEmpty() }
            ?: throw StreamingException("Tidal did not return a stream URL for this track.")
        val codec = manifest.optString("codecs").lowercase()
        if (codec.isNotEmpty() && !codec.contains("flac")) {
            throw StreamingException("Tidal returned $codec instead of FLAC for this track.")
        }
        return TidalFile(
            url = url,
            sampleRateHz = json.optInt("sampleRate", 44_100).takeIf { it > 0 } ?: 44_100,
            bitDepth = json.optInt("bitDepth", 16).takeIf { it > 0 } ?: 16
        )
    }

    // ── session plumbing ────────────────────────────────────────────────────

    private fun country(): String = store.get(K_COUNTRY) ?: "US"

    private suspend fun bearer(): Map<String, String> {
        val expiry = store.get(K_EXPIRES)?.toLongOrNull() ?: 0L
        if (store.get(K_ACCESS).isNullOrEmpty() || System.currentTimeMillis() > expiry - 60_000) refresh()
        val token = store.get(K_ACCESS) ?: throw StreamingException("Sign in to Tidal first.")
        return mapOf("Authorization" to "Bearer $token")
    }

    private suspend fun refresh() {
        val refreshToken = store.get(K_REFRESH) ?: throw StreamingException("Sign in to Tidal first.")
        val clientId = store.get(K_CLIENT_ID) ?: throw StreamingException("Tidal is not set up.")
        val json = StreamingHttp.request(
            url = "$AUTH/token",
            method = "POST",
            headers = clientAuthHeader(),
            form = mapOf(
                "client_id" to clientId,
                "refresh_token" to refreshToken,
                "grant_type" to "refresh_token",
                "scope" to SCOPE
            )
        )
        saveTokens(json)
    }

    private fun saveTokens(json: JSONObject) {
        val access = json.optStringOrNull("access_token")
            ?: throw StreamingException("Tidal did not return an access token.")
        store.put(K_ACCESS, access)
        json.optStringOrNull("refresh_token")?.let { store.put(K_REFRESH, it) }
        store.put(K_EXPIRES, (System.currentTimeMillis() + json.optLong("expires_in", 3600) * 1000L).toString())
        json.optJSONObject("user")?.optStringOrNull("countryCode")?.let { store.put(K_COUNTRY, it) }
    }

    private fun clientAuthHeader(): Map<String, String> {
        val id = store.get(K_CLIENT_ID)
        val secret = store.get(K_CLIENT_SECRET)
        if (id.isNullOrEmpty() || secret.isNullOrEmpty()) return emptyMap()
        val basic = Base64.encodeToString("$id:$secret".toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        return mapOf("Authorization" to "Basic $basic")
    }

    private companion object {
        const val AUTH = "https://auth.tidal.com/v1/oauth2"
        const val API = "https://api.tidal.com/v1"
        const val SCOPE = "r_usr w_usr"
        const val K_CLIENT_ID = "tidal_client_id"
        const val K_CLIENT_SECRET = "tidal_client_secret"
        const val K_ACCESS = "tidal_access"
        const val K_REFRESH = "tidal_refresh"
        const val K_EXPIRES = "tidal_expires"
        const val K_COUNTRY = "tidal_country"
    }
}
