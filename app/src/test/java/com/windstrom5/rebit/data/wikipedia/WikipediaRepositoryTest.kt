package com.windstrom5.rebit.data.wikipedia

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class WikipediaRepositoryTest {

    private fun cand(title: String, description: String, extract: String, rank: Int = 1) =
        WikiCandidate(title, description, extract, null, "https://x/$title", rank)

    private class FakeClient(val handler: (query: String, lang: String) -> List<WikiCandidate>) : WikipediaClient() {
        val calls = ArrayList<String>()
        override suspend fun search(query: String, language: String, limit: Int): List<WikiCandidate> {
            calls += "$language|$query"
            return handler(query, language)
        }
    }

    private fun repo(client: WikipediaClient, langs: List<String> = listOf("en", "id")) =
        WikipediaRepository(client, langs, minRequestGapMs = 0L)

    private val band = cand("Penthouse (Japanese band)", "Japanese pop band", "Penthouse is a Japanese J-pop band formed in Tokyo.")

    @Test
    fun foundInTheFirstEdition() = runBlocking {
        val r = repo(FakeClient { _, _ -> listOf(band) }).lookupArtist("Penthouse")
        assertTrue(r is WikiLookup.Found)
    }

    @Test
    fun aFailingSecondaryEdition_doesNotTurnNotFoundIntoAnError() = runBlocking {
        // English answers "nothing", the device-language edition is down.
        val client = FakeClient { _, lang -> if (lang == "en") emptyList() else throw IOException("boom") }
        val r = repo(client).lookupArtist("Penthouse")
        assertEquals(WikiLookup.NotFound, r)
    }

    @Test
    fun ifEveryEditionFails_itIsAnError() = runBlocking {
        val r = repo(FakeClient { _, _ -> throw IOException("Wikipedia HTTP 500") }).lookupArtist("Penthouse")
        assertTrue(r is WikiLookup.Failed)
    }

    @Test
    fun rateLimit_stopsImmediately_andPausesFurtherRequests() = runBlocking {
        val client = FakeClient { _, _ -> throw WikipediaRateLimitedException(60) }
        val repository = repo(client)
        val first = repository.lookupArtist("Penthouse")
        assertTrue(first is WikiLookup.Failed)
        assertEquals(1, client.calls.size) // did not try the other editions or queries

        val second = repository.lookupArtist("Some Other Band")
        assertTrue(second is WikiLookup.Failed)
        assertEquals(1, client.calls.size) // paused: no network call at all
    }

    @Test
    fun identicalSearches_areServedFromMemory() = runBlocking {
        val client = FakeClient { _, _ -> emptyList() }
        val repository = repo(client, listOf("en"))
        repository.lookupArtist("Penthouse")
        val after1 = client.calls.size
        repository.lookupSong("Penthouse", "Penthouse") // overlapping queries reuse earlier answers or add few new ones
        repository.lookupArtist("Penthouse")           // result cached; must not call again
        assertTrue(client.calls.size >= after1)
        val repeat = client.calls.toList()
        assertEquals(repeat.size, repeat.distinct().size) // never the same request twice
    }

    @Test
    fun japaneseHints_fallBackToTheJapaneseEdition_whenEnglishOnlyHasAnotherCountry() = runBlocking {
        val american = cand("Penthouse Players Clique", "American hip hop group", "An American hip hop duo of rappers.")
        val client = FakeClient { _, lang -> if (lang == "ja") listOf(band) else listOf(american) }
        val hints = WikiHints(
            positive = listOf("japanese", "japan"), negative = listOf("american", "english"),
            queryTerms = listOf("japanese"), languages = listOf("ja")
        )
        val r = repo(client).lookupArtist("Penthouse", hints)
        assertTrue(r is WikiLookup.Found)
        assertEquals("ja", (r as WikiLookup.Found).summary.language)
    }
}
