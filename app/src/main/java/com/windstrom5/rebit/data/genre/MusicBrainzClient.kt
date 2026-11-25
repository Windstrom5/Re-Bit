package com.windstrom5.rebit.data.genre

import com.windstrom5.rebit.data.wikipedia.WikiMatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class MbArtist(
    val mbid: String,
    val name: String,
    val country: String?,
    val score: Int,
    val genres: List<Pair<String, Int>>,
    val tags: List<Pair<String, Int>>
)

data class MbRecordingHit(val artistId: String, val artistName: String, val score: Int)

/** Turns MusicBrainz JSON into small value types. Pure, so it is unit-tested with sample responses. */
object MusicBrainzParser {

    fun recordingHits(json: JSONObject): List<MbRecordingHit> {
        val recs = json.optJSONArray("recordings") ?: return emptyList()
        return (0 until recs.length()).mapNotNull { i ->
            val r = recs.optJSONObject(i) ?: return@mapNotNull null
            val credit = r.optJSONArray("artist-credit")?.optJSONObject(0)?.optJSONObject("artist")
                ?: return@mapNotNull null
            val id = credit.optString("id").takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            MbRecordingHit(id, credit.optString("name"), r.optInt("score", 0))
        }
    }

    /** One artist object, as found in an artist search result or an artist lookup. */
    fun artist(o: JSONObject): MbArtist? {
        val id = o.optString("id").takeIf { it.isNotEmpty() } ?: return null
        return MbArtist(
            mbid = id,
            name = o.optString("name"),
            country = o.optString("country").takeIf { it.length == 2 }
                ?: o.optJSONObject("area")?.optJSONArray("iso-3166-1-codes")?.optString(0)?.takeIf { it.length == 2 },
            score = o.optInt("score", 100),
            genres = counted(o.optJSONArray("genres")),
            tags = counted(o.optJSONArray("tags"))
        )
    }

    fun artists(json: JSONObject): List<MbArtist> {
        val arr = json.optJSONArray("artists") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.let(::artist) }
    }

    private fun counted(a: JSONArray?): List<Pair<String, Int>> {
        if (a == null) return emptyList()
        return (0 until a.length()).mapNotNull { i ->
            val o = a.optJSONObject(i) ?: return@mapNotNull null
            val name = o.optString("name").takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            name to o.optInt("count", 0)
        }
    }
}

/**
 * Looks an artist up on MusicBrainz (open data, no key) to learn their genre and country.
 * MusicBrainz asks clients to send an identifying User-Agent and to stay under one request per
 * second, so calls are serialised and spaced out; put your contact in [USER_AGENT].
 */
class MusicBrainzClient {

    private val gate = Mutex()
    private var lastRequestAt = 0L

    /**
     * Finds the artist behind a song. When a track title is known it is searched first, so two
     * artists with the same name are told apart by the song they recorded. Returns null if nothing
     * matches confidently; throws [IOException] on network trouble (callers retry later).
     */
    suspend fun find(artist: String, trackTitle: String?): MbArtist? {
        val wanted = WikiMatcher.normalize(artist)
        if (wanted.isBlank()) return null

        if (!trackTitle.isNullOrBlank()) {
            val rec = get("recording", "query" to "recording:\"${esc(trackTitle)}\" AND artist:\"${esc(artist)}\"", "limit" to "5")
            val hit = MusicBrainzParser.recordingHits(rec)
                .firstOrNull { it.score >= 85 && WikiMatcher.normalize(it.artistName) == wanted }
            if (hit != null) {
                MusicBrainzParser.artist(get("artist/${hit.artistId}", "inc" to "genres tags"))?.let { return it }
            }
        }
        val res = get("artist", "query" to "artist:\"${esc(artist)}\"", "limit" to "5")
        return MusicBrainzParser.artists(res)
            .firstOrNull { it.score >= 90 && WikiMatcher.normalize(it.name) == wanted }
    }

    private fun esc(s: String) = s.replace("\\", "\\\\").replace("\"", "\\\"")

    private suspend fun get(path: String, vararg params: Pair<String, String>): JSONObject {
        throttle()
        return withContext(Dispatchers.IO) {
            val query = (params.toList() + ("fmt" to "json"))
                .joinToString("&") { "${URLEncoder.encode(it.first, "UTF-8")}=${URLEncoder.encode(it.second, "UTF-8")}" }
            val conn = (URL("$BASE/$path?$query").openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 20_000
                setRequestProperty("User-Agent", USER_AGENT)
                setRequestProperty("Accept", "application/json")
            }
            try {
                val code = conn.responseCode
                if (code !in 200..299) throw IOException("MusicBrainz returned HTTP $code")
                JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            } finally {
                conn.disconnect()
            }
        }
    }

    private suspend fun throttle() = gate.withLock {
        val wait = MIN_INTERVAL_MS - (System.currentTimeMillis() - lastRequestAt)
        if (wait > 0) delay(wait)
        lastRequestAt = System.currentTimeMillis()
    }

    companion object {
        private const val BASE = "https://musicbrainz.org/ws/2"
        private const val MIN_INTERVAL_MS = 1_100L
        // MusicBrainz asks for app name/version plus a way to contact you. Add yours, e.g. "(you@example.com)".
        const val USER_AGENT = "ReBit/1.0.0-beta.1 (Android music player)"
    }
}
