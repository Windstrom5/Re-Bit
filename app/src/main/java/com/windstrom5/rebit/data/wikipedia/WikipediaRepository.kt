package com.windstrom5.rebit.data.wikipedia

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException
import java.util.Locale

/**
 * Finds the Wikipedia article for an artist or a song, trying each language in order.
 * Found / not-found results are cached in memory; network failures are not, so a retry works.
 */
class WikipediaRepository(
    private val client: WikipediaClient = WikipediaClient(),
    private val languages: List<String> = defaultLanguages(),
    /** Minimum pause between requests. All lookups share one queue so they never arrive as a burst. */
    private val minRequestGapMs: Long = 150L
) {
    private val cache = object : LinkedHashMap<String, WikiLookup>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, WikiLookup>?) = size > 96
    }

    // Identical searches (a retry, the artist and song lookups sharing a query) are answered from memory.
    private val requestCache = object : LinkedHashMap<String, Pair<Long, List<WikiCandidate>>>(128, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pair<Long, List<WikiCandidate>>>?) = size > 160
    }
    private val gate = Mutex()
    private var lastRequestAt = 0L
    @Volatile private var blockedUntil = 0L

    /**
     * The only way requests leave this class: one at a time, spaced out, cached, and paused entirely for a
     * while after Wikipedia says "too many requests" so we don't keep hammering it.
     */
    private suspend fun fetch(query: String, lang: String): List<WikiCandidate> {
        val key = "$lang|$query"
        val now = System.currentTimeMillis()
        synchronized(requestCache) { requestCache[key] }?.let { (at, list) -> if (now - at < REQUEST_CACHE_TTL_MS) return list }
        if (now < blockedUntil) throw WikipediaRateLimitedException(((blockedUntil - now) / 1000).toInt() + 1)
        return gate.withLock {
            val wait = minRequestGapMs - (System.currentTimeMillis() - lastRequestAt)
            if (wait > 0) delay(wait)
            lastRequestAt = System.currentTimeMillis()
            try {
                client.search(query, lang).also { list ->
                    synchronized(requestCache) { requestCache[key] = System.currentTimeMillis() to list }
                }
            } catch (e: WikipediaRateLimitedException) {
                blockedUntil = System.currentTimeMillis() + e.retryAfterSec.coerceIn(10, 300) * 1000L
                throw e
            }
        }
    }

    suspend fun lookupArtist(artist: String?, hints: WikiHints? = null): WikiLookup {
        val names = WikiMatcher.artistNames(artist)
        if (names.isEmpty()) return WikiLookup.NotFound
        return cached("artist|${names.first()}|${hints?.key.orEmpty()}") {
            search(names, hints?.languages.orEmpty()) { name, lang ->
                if (hints != null && !hints.isEmpty) {
                    // Pool the results of the plain and hinted searches ("Penthouse" and "Penthouse japanese"),
                    // then let the hints choose among them instead of trusting the first exact title.
                    val pool = LinkedHashMap<String, WikiCandidate>()
                    for (q in WikiMatcher.hintedArtistQueries(name, hints, lang).take(1) + name) {
                        fetch(q, lang).forEach { pool.putIfAbsent(it.title, it) }
                    }
                    WikiMatcher.pickArtist(pool.values.toList(), name, hints)?.let { return@search it }
                }
                for (q in WikiMatcher.artistQueries(name)) {
                    WikiMatcher.pickArtist(fetch(q, lang), name, hints)?.let { return@search it }
                }
                null
            }
        }
    }

    suspend fun lookupSong(title: String, artist: String?, hints: WikiHints? = null): WikiLookup {
        val clean = WikiMatcher.cleanSongTitle(title)
        if (clean.isBlank()) return WikiLookup.NotFound
        return cached("song|$clean|${artist.orEmpty()}|${hints?.key.orEmpty()}") {
            search(listOf(clean), hints?.languages.orEmpty()) { _, lang ->
                for (q in WikiMatcher.songQueries(title, artist)) {
                    WikiMatcher.pickSong(fetch(q, lang), clean, artist, hints)?.let { return@search it }
                }
                null
            }
        }
    }

    fun searchUrl(query: String, language: String = languages.firstOrNull() ?: "en"): String =
        "https://$language.wikipedia.org/w/index.php?search=" + java.net.URLEncoder.encode(query, "UTF-8")

    private suspend fun cached(key: String, block: suspend () -> WikiLookup): WikiLookup {
        synchronized(cache) { cache[key] }?.let { return it }
        val result = block()
        if (result !is WikiLookup.Failed) synchronized(cache) { cache[key] = result }
        return result
    }

    private suspend fun search(
        names: List<String>,
        extraLanguages: List<String> = emptyList(),
        pick: suspend (name: String, lang: String) -> WikiCandidate?
    ): WikiLookup {
        var failure: String? = null
        var anyEditionAnswered = false
        // The usual editions first; a hinted one (e.g. ja for a Japanese act) is the fallback when the
        // English article turns out to be a different, same-named artist.
        for (lang in (languages + extraLanguages).distinct()) {
            for (name in names) {
                try {
                    val hit = pick(name, lang)
                    anyEditionAnswered = true
                    val extract = hit?.extract
                    if (hit != null && extract != null) {
                        return WikiLookup.Found(
                            WikiSummary(hit.title, hit.description, extract, hit.thumbnailUrl, hit.pageUrl, lang)
                        )
                    }
                } catch (e: WikipediaRateLimitedException) {
                    // Asked to slow down: stop now rather than make it worse.
                    return WikiLookup.Failed(e.message ?: "Wikipedia is limiting requests right now.")
                } catch (e: IOException) {
                    failure = e.message ?: "Network error"
                } catch (e: org.json.JSONException) {
                    failure = "Unexpected response from Wikipedia"
                }
            }
        }
        // An error only counts if NO edition answered. If one answered "nothing found" and another hiccupped
        // (say the Japanese edition), the honest result is "not found", not "couldn't reach Wikipedia".
        return if (!anyEditionAnswered && failure != null) WikiLookup.Failed(failure) else WikiLookup.NotFound
    }

    companion object {
        private const val REQUEST_CACHE_TTL_MS = 10 * 60 * 1000L

        fun defaultLanguages(): List<String> {
            val device = Locale.getDefault().language.takeIf { it.length in 2..3 }
            return listOfNotNull("en", device).distinct()
        }
    }
}
