package com.windstrom5.rebit.data.wikipedia

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WikiMatcherTest {
    private fun c(t: String, d: String?, e: String?, r: Int) = WikiCandidate(t, d, e, null, "u", r)

    @Test fun cleansNoiseFromSongTitles() {
        assertEquals("Creep", WikiMatcher.cleanSongTitle("01 - Creep (Remastered 2009) [Live]"))
        assertEquals("Hey Jude", WikiMatcher.cleanSongTitle("Hey Jude - Remastered 2015"))
    }

    @Test fun artistNames() {
        assertTrue(WikiMatcher.artistNames("Unknown Artist").isEmpty())
        assertEquals("Daft Punk", WikiMatcher.artistNames("Daft Punk feat. Pharrell Williams").first())
        assertEquals(listOf("Simon & Garfunkel", "Simon"), WikiMatcher.artistNames("Simon & Garfunkel").take(2))
    }

    @Test fun artistPrefersMusicalArticleOverBetterRankedNonMusical() {
        val hits = listOf(
            c("Queen Victoria", "Queen of the United Kingdom", "Victoria was Queen of the UK", 1),
            c("Queen (band)", "British rock band", "Queen are a British rock band formed in London", 2)
        )
        assertEquals("Queen (band)", WikiMatcher.pickArtist(hits, "Queen")?.title)
    }

    @Test fun artistDoesNotMatchSubstring() {
        assertNull(WikiMatcher.pickArtist(listOf(c("Museum", "Institution", "A museum is a band of objects", 1)), "Muse"))
    }

    @Test fun artistIgnoresLeadingThe() {
        assertNotNull(WikiMatcher.pickArtist(listOf(c("The Beatles", "English rock band", "The Beatles were an English rock band", 1)), "Beatles"))
    }

    private val home = listOf(
        c("Home", "Place where one lives", "A home is a dwelling", 1),
        c("Home (Daughtry song)", "2007 single by Daughtry", "\"Home\" is a song by American rock band Daughtry", 2),
        c("Home (Michael Bublé song)", "2005 single", "Home is a song by Michael Bublé", 3)
    )

    @Test fun songMustMentionTheArtist() {
        assertEquals("Home (Daughtry song)", WikiMatcher.pickSong(home, "Home", "Daughtry")?.title)
        assertNull(WikiMatcher.pickSong(home, "Home", "Nobody Else"))
    }

    @Test fun albumIsNotASong() {
        assertNull(WikiMatcher.pickSong(listOf(c("Creep (album)", "album by Foo", "Creep is an album by Foo", 1)), "Creep", "Foo"))
    }

    @Test fun songQueriesUseMainArtistOnly() {
        assertEquals(
            listOf("Get Lucky Daft Punk song", "Get Lucky Daft Punk"),
            WikiMatcher.songQueries("Get Lucky (feat. X)", "Daft Punk feat. Pharrell")
        )
    }
}
