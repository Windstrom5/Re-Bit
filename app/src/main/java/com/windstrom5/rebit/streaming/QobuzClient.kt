package com.windstrom5.rebit.streaming

import java.security.MessageDigest
import kotlin.math.roundToInt

/** Builds the `request_sig` Qobuz requires on `track/getFileUrl`. Pure, so it is unit-testable. */
object QobuzSigner {
    /** md5("trackgetFileUrlformat_id{fmt}intentstreamtrack_id{id}{timestamp}{secret}") */
    fun signFileUrl(trackId: String, formatId: Int, timestampSec: Long, appSecret: String): String {
        val raw = "trackgetFileUrlformat_id${formatId}intentstreamtrack_id$trackId$timestampSec$appSecret"
        return MessageDigest.getInstance("MD5").digest(raw.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }
}

data class QobuzFile(val url: String, val sampleRateHz: Int, val bitDepth: Int, val formatId: Int)

/**
 * Qobuz catalogue + stream access for a paying subscriber.
 *
 * The listener supplies their own account (email + password, used once to mint
 * a session token) and the application id / secret issued to them by Qobuz.
 * Nothing is bundled with the app, and nothing here touches DRM: Qobuz serves
 * ordinary FLAC over HTTPS, which the existing FlacDecoder plays unchanged.
 */
class QobuzClient(private val store: StreamingCredentialStore) {

    val isLoggedIn: Boolean get() = !store.get(K_TOKEN).isNullOrEmpty()
    val savedAppId: String get() = store.get(K_APP_ID).orEmpty()

    suspend fun login(userOrEmail: String, password: String, appId: String, appSecret: String) {
        require(appId.isNotBlank() && appSecret.isNotBlank()) { "Enter your Qobuz app id and secret." }
        val userKey = if ('@' in userOrEmail) "email" else "username"
        val json = StreamingHttp.request(
            url = "$BASE/user/login?" + StreamingHttp.query(
                userKey to userOrEmail.trim(), "password" to password, "app_id" to appId.trim()
            ),
            headers = mapOf("X-App-Id" to appId.trim())
        )
        val token = json.optStringOrNull("user_auth_token")
            ?: throw StreamingException("Qobuz did not return a session. Check your login and app id.")
        store.put(K_APP_ID, appId.trim())
        store.put(K_SECRET, appSecret.trim())
        store.put(K_TOKEN, token)
    }

    fun logout() = store.remove(K_TOKEN)

    suspend fun search(query: String, limit: Int = 30): List<StreamTrack> {
        val json = StreamingHttp.request(
            url = "$BASE/catalog/search?" + StreamingHttp.query("query" to query, "limit" to limit.toString()),
            headers = authHeaders()
        )
        val items = json.optJSONObject("tracks")?.optJSONArray("items") ?: return emptyList()
        return (0 until items.length()).mapNotNull { i ->
            val o = items.optJSONObject(i) ?: return@mapNotNull null
            val album = o.optJSONObject("album")
            val version = o.optStringOrNull("version")
            StreamTrack(
                provider = StreamingProvider.QOBUZ,
                remoteId = o.opt("id")?.toString() ?: return@mapNotNull null,
                title = o.optString("title") + (version?.let { " ($it)" } ?: ""),
                artist = o.optJSONObject("performer")?.optStringOrNull("name")
                    ?: album?.optJSONObject("artist")?.optStringOrNull("name") ?: "Unknown artist",
                album = album?.optStringOrNull("title"),
                durationMs = o.optLong("duration") * 1000L,
                artworkUrl = album?.optJSONObject("image")?.optStringOrNull("large"),
                maxSampleRateHz = (o.optDouble("maximum_sampling_rate", 44.1) * 1000).roundToInt(),
                maxBitDepth = o.optInt("maximum_bit_depth", 16),
                streamable = o.optBoolean("streamable", true)
            )
        }
    }

    /**
     * Asks for the best quality the account allows, stepping down if Qobuz
     * refuses a tier. 27 = 24-bit up to 192 kHz, 7 = 24-bit up to 96 kHz,
     * 6 = 16-bit / 44.1 kHz FLAC.
     */
    suspend fun fileUrl(trackId: String, preferHiRes: Boolean): QobuzFile {
        val secret = store.get(K_SECRET) ?: throw StreamingException("Qobuz is not set up.")
        val formats = if (preferHiRes) listOf(27, 7, 6) else listOf(6)
        var lastError: StreamingException? = null
        for (format in formats) {
            val ts = System.currentTimeMillis() / 1000
            try {
                val json = StreamingHttp.request(
                    url = "$BASE/track/getFileUrl?" + StreamingHttp.query(
                        "format_id" to format.toString(),
                        "intent" to "stream",
                        "track_id" to trackId,
                        "request_ts" to ts.toString(),
                        "request_sig" to QobuzSigner.signFileUrl(trackId, format, ts, secret)
                    ),
                    headers = authHeaders()
                )
                val url = json.optStringOrNull("url")
                if (url == null || json.optBoolean("sample", false)) {
                    throw StreamingException("Qobuz only offers a 30-second preview of this track on your plan.")
                }
                return QobuzFile(
                    url = url,
                    sampleRateHz = (json.optDouble("sampling_rate", 44.1) * 1000).roundToInt(),
                    bitDepth = json.optInt("bit_depth", 16),
                    formatId = json.optInt("format_id", format)
                )
            } catch (e: StreamingException) {
                lastError = e
            }
        }
        throw lastError ?: StreamingException("Qobuz refused the stream request.")
    }

    private fun authHeaders(): Map<String, String> {
        val appId = store.get(K_APP_ID)
        val token = store.get(K_TOKEN)
        if (appId.isNullOrEmpty() || token.isNullOrEmpty()) throw StreamingException("Sign in to Qobuz first.")
        return mapOf("X-App-Id" to appId, "X-User-Auth-Token" to token)
    }

    private companion object {
        const val BASE = "https://www.qobuz.com/api.json/0.2"
        const val K_APP_ID = "qobuz_app_id"
        const val K_SECRET = "qobuz_app_secret"
        const val K_TOKEN = "qobuz_token"
    }
}
