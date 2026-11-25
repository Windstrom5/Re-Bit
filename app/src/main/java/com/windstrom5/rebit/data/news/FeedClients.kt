package com.windstrom5.rebit.data.news

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Spaces out calls so we stay inside each provider's fair-use rule. */
class RateGate(private val minIntervalMs: Long) {
    private val mutex = Mutex()
    private var last = 0L
    suspend fun await() = mutex.withLock {
        val wait = last + minIntervalMs - System.currentTimeMillis()
        if (wait > 0) delay(wait)
        last = System.currentTimeMillis()
    }
}

internal fun httpGetText(url: String, userAgent: String): String {
    val conn = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 10_000
        readTimeout = 15_000
        setRequestProperty("User-Agent", userAgent)
        setRequestProperty("Accept", "application/json")
    }
    try {
        if (conn.responseCode !in 200..299) throw IOException("HTTP ${conn.responseCode}")
        return conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
    } finally {
        conn.disconnect()
    }
}

/** MusicBrainz: free, no key. Rule: max 1 request/second and a meaningful User-Agent. */
open class MusicBrainzClient(private val gate: RateGate = RateGate(1_100)) {

    /** Best artist match for [name] with a high confidence score, or null. */
    open suspend fun findArtistId(name: String): String? = withContext(Dispatchers.IO) {
        gate.await()
        val q = URLEncoder.encode("artist:\"${name.replace("\"", "")}\"", "UTF-8")
        val body = httpGetText("https://musicbrainz.org/ws/2/artist/?query=$q&fmt=json&limit=3", NewsLogic.USER_AGENT)
        val artists = JSONObject(body).optJSONArray("artists") ?: return@withContext null
        for (i in 0 until artists.length()) {
            val a = artists.getJSONObject(i)
            val exact = a.optString("name").equals(name, ignoreCase = true)
            if (a.optInt("score") >= 95 && exact) return@withContext a.optString("id").takeIf { it.isNotBlank() }
        }
        null
    }

    /** Albums, singles and EPs by artist (browse API); pages up to [maxPages]x100 groups. */
    open suspend fun releaseGroups(mbid: String, maxPages: Int = 2): List<MbReleaseGroup> = withContext(Dispatchers.IO) {
        val out = ArrayList<MbReleaseGroup>()
        var offset = 0
        repeat(maxPages) {
            gate.await()
            val body = httpGetText(
                "https://musicbrainz.org/ws/2/release-group?artist=$mbid&type=album%7Csingle%7Cep&limit=100&offset=$offset&fmt=json",
                NewsLogic.USER_AGENT
            )
            val json = JSONObject(body)
            val arr = json.optJSONArray("release-groups") ?: return@repeat
            for (i in 0 until arr.length()) {
                val g = arr.getJSONObject(i)
                out += MbReleaseGroup(
                    id = g.optString("id"),
                    title = g.optString("title"),
                    primaryType = g.optString("primary-type").takeIf { it.isNotBlank() },
                    firstReleaseMs = NewsLogic.parseMusicBrainzDate(g.optString("first-release-date"))
                )
            }
            offset += 100
            if (offset >= json.optInt("release-group-count", 0)) return@withContext out
        }
        out
    }
}

/** GDELT DOC 2.0: free, no key. Rule: one request per ~5 seconds. May return plain-text errors with HTTP 200. */
open class GdeltClient(private val gate: RateGate = RateGate(5_500)) {

    open suspend fun search(query: String, timespan: String, maxRecords: Int = 75): List<NewsArticle> =
        withContext(Dispatchers.IO) {
            gate.await()
            val q = URLEncoder.encode(query, "UTF-8")
            val body = httpGetText(
                "https://api.gdeltproject.org/api/v2/doc/doc?query=$q&mode=artlist&format=json" +
                    "&maxrecords=$maxRecords&sort=datedesc&timespan=$timespan",
                NewsLogic.USER_AGENT
            )
            // Errors ("query too short", throttling) arrive as plain text, not JSON.
            val trimmed = body.trim()
            if (!trimmed.startsWith("{")) return@withContext emptyList()
            val arr = try {
                JSONObject(trimmed).optJSONArray("articles")
            } catch (e: JSONException) {
                null
            } ?: return@withContext emptyList()
            buildList {
                for (i in 0 until arr.length()) {
                    val a = arr.getJSONObject(i)
                    val url = a.optString("url")
                    val title = a.optString("title")
                    if (url.isBlank() || title.isBlank()) continue
                    add(NewsArticle(url, title, a.optString("domain").takeIf { it.isNotBlank() }, NewsLogic.parseGdeltDate(a.optString("seendate"))))
                }
            }
        }
}
