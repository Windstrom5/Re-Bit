package com.windstrom5.rebit.data.genre

import com.windstrom5.rebit.data.wikipedia.WikiCandidate
import com.windstrom5.rebit.data.wikipedia.WikiMatcher
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GenreLogicTest {

    @Test
    fun normalize_cleansJunkAndAliases() {
        assertNull(GenreLogic.normalize(null))
        assertNull(GenreLogic.normalize("  "))
        assertNull(GenreLogic.normalize("Unknown"))
        assertNull(GenreLogic.normalize("(255)"))
        assertEquals("J-Pop", GenreLogic.normalize("jpop"))
        assertEquals("J-Pop", GenreLogic.normalize("J-POP"))
        assertEquals("Hip-Hop", GenreLogic.normalize("hip hop"))
        assertEquals("R&B", GenreLogic.normalize("RnB"))
        assertEquals("Progressive Rock", GenreLogic.normalize("progressive rock"))
        assertEquals("Post-Rock", GenreLogic.normalize("post-rock"))
    }

    @Test
    fun normalize_takesFirstOfSeveralButKeepsSlashGenres() {
        assertEquals("Rock", GenreLogic.normalize("Rock; Pop"))
        assertEquals("Jazz", GenreLogic.normalize("Jazz, Funk"))
        assertEquals("R&B/Soul", GenreLogic.normalize("R&B/Soul"))
    }

    @Test
    fun normalize_decodesId3v1Numbers() {
        assertEquals("Rock", GenreLogic.normalize("(17)"))
        assertEquals("Rock", GenreLogic.normalize("17"))
        assertEquals("Pop", GenreLogic.normalize("(13)"))
        assertEquals("Techno", GenreLogic.normalize("(18)Techno"))
        assertNull(GenreLogic.normalize("2024"))
        assertEquals("90s", GenreLogic.normalize("90s")) // a name, not an ID3 number
    }

    @Test
    fun pickGenre_prefersCuratedGenres_thenKnownTagsOnly() {
        assertEquals("J-Pop", GenreLogic.pickGenre(listOf("j-pop" to 5, "pop rock" to 2), emptyList()))
        // free-form tags: "japanese" and "seen live" are not genres
        val tags = listOf("seen live" to 9, "japanese" to 7, "rock" to 3)
        assertEquals("Rock", GenreLogic.pickGenre(emptyList(), tags))
        assertNull(GenreLogic.pickGenre(emptyList(), listOf("seen live" to 4)))
    }

    @Test
    fun scriptOf_detectsKanaHangulAndHan() {
        assertEquals(ScriptHint.JAPANESE, GenreLogic.scriptOf("ペントハウス"))
        assertEquals(ScriptHint.JAPANESE, GenreLogic.scriptOf("夜に駆ける の歌"))
        assertEquals(ScriptHint.KOREAN, GenreLogic.scriptOf("방탄소년단"))
        assertEquals(ScriptHint.HAN, GenreLogic.scriptOf("周杰倫"))
        assertNull(GenreLogic.scriptOf("Penthouse"))
        assertNull(GenreLogic.scriptOf(null))
    }

    @Test
    fun wikiHints_forJPop_pointAtJapan_andAgainstOtherNations() {
        val h = GenreLogic.wikiHints("J-Pop", null, null)
        assertTrue("japanese" in h.positive)
        assertTrue("english" !in h.positive)
        assertTrue("english" in h.negative)
        assertEquals("japanese", h.queryTerms.first())
        assertTrue(GenreLogic.wikiHints(null, null, null).isEmpty)
        // genre with no nationality hint adds a bonus but never claims a conflict
        assertTrue(GenreLogic.wikiHints("Rock", null, null).negative.isEmpty())
    }

    // ── The "Penthouse" case: same name, two artists ────────────────────────

    private fun cand(title: String, description: String, extract: String, rank: Int) =
        WikiCandidate(title, description, extract, null, "https://en.wikipedia.org/wiki/$title", rank)

    private val oldBand = cand(
        "Penthouse (band)", "English rock band",
        "Penthouse were an English rock band formed in 1981 in Manchester.", rank = 1
    )
    private val japaneseAct = cand(
        "Penthouse (Japanese band)", "Japanese pop band",
        "Penthouse is a Japanese J-pop band formed in Tokyo in 2012.", rank = 2
    )

    @Test
    fun withoutHints_theFirstExactMatchWins() {
        assertEquals(oldBand, WikiMatcher.pickArtist(listOf(oldBand, japaneseAct), "Penthouse"))
    }

    @Test
    fun withJPopHints_theJapaneseActWins() {
        val hints = GenreLogic.wikiHints("J-Pop", "JP", null)
        assertEquals(japaneseAct, WikiMatcher.pickArtist(listOf(oldBand, japaneseAct), "Penthouse", hints))
    }

    @Test
    fun japaneseScriptAlone_isEnoughToChooseTheJapaneseAct() {
        val hints = GenreLogic.wikiHints(null, null, GenreLogic.scriptOf("ペントハウス"))
        assertEquals(japaneseAct, WikiMatcher.pickArtist(listOf(oldBand, japaneseAct), "Penthouse", hints))
    }

    @Test
    fun hints_neverPromoteALooseMatchOverAnExactTitle() {
        val loose = cand("Penthouse Sessions", "Japanese pop music group", "A Japanese J-pop group.", rank = 1)
        val hints = GenreLogic.wikiHints("J-Pop", "JP", null)
        assertEquals(oldBand, WikiMatcher.pickArtist(listOf(loose, oldBand), "Penthouse", hints.copy(negative = emptyList())))
    }

    @Test
    fun anArticleAboutAnotherNationality_isRejected_notJustDownRanked() {
        // The reported bug: only an American rap group came back for a Japanese song.
        val players = cand(
            "Penthouse Players Clique", "American hip hop group",
            "Penthouse Players Clique was an American hip hop duo of rappers Playa Hamm and Tweed Cadillac.", rank = 1
        )
        val hints = GenreLogic.wikiHints(null, null, GenreLogic.scriptOf("一生分のおねだりさ"))
        assertNull(WikiMatcher.pickArtist(listOf(players), "Penthouse", hints))
        // without hints it is still returned, as before
        assertEquals(players, WikiMatcher.pickArtist(listOf(players), "Penthouse"))
    }

    @Test
    fun japaneseHints_addTheJapaneseEdition_andLocalSearchTerms() {
        val hints = GenreLogic.wikiHints("J-Pop", null, null)
        assertEquals(listOf("ja"), hints.languages)
        assertTrue(WikiMatcher.hintedArtistQueries("Penthouse", hints, "ja").any { it.contains("バンド") })
        assertTrue(WikiMatcher.hintedArtistQueries("Penthouse", hints, "en").first().contains("japanese"))
        assertTrue(GenreLogic.wikiHints("Rock", null, null).languages.isEmpty())
    }

    // ── MusicBrainz parsing ──────────────────────────────────────────────

    @Test
    fun parsesArtistSearchResults() {
        val json = JSONObject(
            """{"artists":[{"id":"abc-123","type":"Group","score":100,"name":"Penthouse","country":"JP",
               "tags":[{"count":2,"name":"j-pop"},{"count":1,"name":"seen live"}],
               "genres":[{"count":3,"name":"j-pop"}]}]}"""
        )
        val a = MusicBrainzParser.artists(json).single()
        assertEquals("abc-123", a.mbid)
        assertEquals("JP", a.country)
        assertEquals("J-Pop", GenreLogic.pickGenre(a.genres, a.tags))
    }

    @Test
    fun parsesRecordingHits_andFallsBackToAreaCode() {
        val rec = JSONObject(
            """{"recordings":[{"score":98,"title":"Penthouse",
               "artist-credit":[{"name":"Penthouse","artist":{"id":"id-1","name":"Penthouse"}}]}]}"""
        )
        val hit = MusicBrainzParser.recordingHits(rec).single()
        assertEquals("id-1", hit.artistId)
        assertEquals(98, hit.score)

        val area = JSONObject("""{"id":"x","name":"N","area":{"iso-3166-1-codes":["KR"]}}""")
        assertEquals("KR", MusicBrainzParser.artist(area)?.country)
    }
}
