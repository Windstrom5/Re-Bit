package com.windstrom5.rebit.data.wikipedia

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Wikipedia asked us to slow down (HTTP 429). [retryAfterSec] is how long it said to wait. */
class WikipediaRateLimitedException(val retryAfterSec: Int) :
    IOException("Wikipedia is limiting requests right now. Try again in a minute.")

/** Thin wrapper over the MediaWiki Action API. Same HttpURLConnection + org.json style as LyricsScraperClient. */
open class WikipediaClient {

    /**
     * One request returns the top [limit] hits with intro extract, short description,
     * thumbnail and URL, so no second round-trip per candidate is needed.
     * @throws IOException on network or HTTP failure (callers must not cache that as "not found").
     */
    open suspend fun search(query: String, language: String, limit: Int = 8): List<WikiCandidate> =
        withContext(Dispatchers.IO) {
            val q = URLEncoder.encode(query, "UTF-8")
            val url = "https://$language.wikipedia.org/w/api.php?action=query&format=json&formatversion=2" +
                "&generator=search&gsrsearch=$q&gsrlimit=$limit&gsrnamespace=0" +
                "&prop=extracts%7Cpageimages%7Cdescription%7Cinfo" +
                "&exintro=1&explaintext=1&exchars=900&exlimit=max" +
                "&piprop=thumbnail&pithumbsize=480&inprop=url&redirects=1"
            parse(httpGet(url))
        }

    internal fun parse(body: String): List<WikiCandidate> {
        val pages = JSONObject(body).optJSONObject("query")?.optJSONArray("pages") ?: return emptyList()
        val out = ArrayList<WikiCandidate>(pages.length())
        for (i in 0 until pages.length()) {
            val p = pages.getJSONObject(i)
            if (p.has("missing")) continue
            out += WikiCandidate(
                title = p.optString("title"),
                description = p.optString("description").takeIf { it.isNotBlank() },
                extract = p.optString("extract").takeIf { it.isNotBlank() },
                thumbnailUrl = p.optJSONObject("thumbnail")?.optString("source")?.takeIf { it.isNotBlank() },
                pageUrl = p.optString("fullurl"),
                rank = p.optInt("index", i + 1)
            )
        }
        return out.sortedBy { it.rank }
    }

    private fun httpGet(urlStr: String): String {
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Accept", "application/json")
        }
        try {
            val code = conn.responseCode
            if (code == 429 || (code == 503 && conn.getHeaderField("Retry-After") != null)) {
                throw WikipediaRateLimitedException(conn.getHeaderField("Retry-After")?.trim()?.toIntOrNull() ?: 60)
            }
            if (code == 403) throw IOException("Wikipedia refused the request (HTTP 403)")
            if (code !in 200..299) throw IOException("Wikipedia HTTP $code")
            return conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        // Wikimedia asks API clients to identify themselves.
        const val USER_AGENT = "BitPerfectUSB-AudioPlayer/1.0 (Android; thesis music player)"
        private const val TIMEOUT_MS = 8000
    }
}
