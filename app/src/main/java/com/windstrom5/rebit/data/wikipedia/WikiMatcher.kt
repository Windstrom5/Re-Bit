package com.windstrom5.rebit.data.wikipedia

import java.text.Normalizer

/**
 * Pure string logic for turning messy file tags into search queries and for deciding
 * which search hit (if any) really is the artist / song. No Android or network
 * dependencies, so it is unit-tested on the JVM.
 *
 * Why this exists: Wikipedia's search returns the *best text match*, not the best
 * *musical* match. Searching "Home" or "Queen" happily returns a dictionary entry or a
 * monarch, so every candidate is verified against its short description / intro.
 */
object WikiMatcher {

    private val PLACEHOLDER_ARTISTS = setOf(
        "unknown", "<unknown>", "unknown artist", "various artists", "various", "va", "n/a", "none", ""
    )

    private val MUSIC_WORDS = Regex(
        "\\b(singer|songwriter|singer-songwriter|rapper|band|musician|duo|trio|quartet|quintet|" +
            "vocalist|vocal group|composer|dj|record producer|music producer|guitarist|pianist|violinist|" +
            "drummer|bassist|cellist|saxophonist|trumpeter|orchestra|ensemble|choir|boy band|girl group|" +
            "recording artist|conductor|supergroup|musical group|music group|musical project|" +
            // id
            "penyanyi|musisi|grup musik|band musik|penulis lagu|pencipta lagu|produser musik|komposer|" +
            // es / fr / de
            "cantante|banda|músico|grupo musical|chanteur|chanteuse|groupe de musique|musicien|sänger|sängerin|musiker)\\b" +
            "|歌手|バンド|ミュージシャン|作曲家|音楽ユニット|アイドル|가수|밴드",
        RegexOption.IGNORE_CASE
    )

    private val SONG_WORDS = Regex(
        "\\b(song|single|ballad|track|composition|recording|lagu|canción|chanson|lied)\\b|楽曲|シングル|노래",
        RegexOption.IGNORE_CASE
    )

    private val ALBUM_WORDS = Regex("\\b(album|ep|mixtape|soundtrack|compilation)\\b", RegexOption.IGNORE_CASE)
    private val DISAMBIG = Regex("may refer to|disambiguation|dapat merujuk|bisa merujuk", RegexOption.IGNORE_CASE)

    // "(feat. X)", "[Remastered 2011]", "- Live", "(Official Video)" ...
    private val BRACKETED = Regex("""\s*[(\[【][^)\]】]*[)\]】]""")
    private val TRAILING_DASH_NOISE = Regex(
        """\s+[-–—]\s+(remaster(ed)?(\s+\d{4})?|live.*|mono|stereo|single version|radio edit|album version|\d{4}\s+remaster(ed)?)\s*$""",
        RegexOption.IGNORE_CASE
    )
    private val LEADING_TRACK_NO = Regex("""^\s*\d{1,3}\s*[-._)]\s*""")
    private val FEATURING = Regex("""\s+(?:feat\.?|ft\.?|featuring|with)\s+.*$""", RegexOption.IGNORE_CASE)
    private val ARTIST_SPLIT = Regex(
        """\s*(?:,|;|/|\sfeat\.?\s|\sft\.?\s|\sfeaturing\s|\swith\s|\sx\s|\sand\s|\s&\s|\s×\s)\s*""",
        RegexOption.IGNORE_CASE
    )

    fun isPlaceholderArtist(artist: String?): Boolean =
        artist == null || artist.trim().lowercase() in PLACEHOLDER_ARTISTS

    fun cleanSongTitle(raw: String): String =
        raw.replace(LEADING_TRACK_NO, "")
            .replace(BRACKETED, "")
            .replace(TRAILING_DASH_NOISE, "")
            .trim()

    /**
     * Artist names to try, most specific first. "Simon & Garfunkel" is tried whole before
     * falling back to "Simon", while "Daft Punk feat. Pharrell Williams" yields "Daft Punk".
     */
    fun artistNames(raw: String?): List<String> {
        if (isPlaceholderArtist(raw)) return emptyList()
        val whole = raw!!.replace(BRACKETED, "").replace(FEATURING, "").trim()
        val parts = whole.split(ARTIST_SPLIT).map { it.trim() }.filter { it.isNotEmpty() }
        return (listOf(whole) + parts).distinctBy { normalize(it) }.filter { it.isNotBlank() }
    }

    /** Lowercase, strip accents and punctuation, collapse spaces. CJK is preserved. */
    fun normalize(s: String): String =
        Normalizer.normalize(s, Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .lowercase()
            .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
            .trim()

    private fun wordContains(haystack: String, needle: String) = " $haystack ".contains(" $needle ")

    private fun stripArticle(n: String) = n.removePrefix("the ").trim()

    /** "Queen (band)" -> "queen" */
    private fun baseTitle(title: String): String = normalize(title.replace(BRACKETED, ""))

    private fun haystack(c: WikiCandidate): String =
        listOfNotNull(c.description, c.extract?.take(400)).joinToString(" ")

    fun isDisambiguation(c: WikiCandidate) =
        DISAMBIG.containsMatchIn(c.extract.orEmpty().take(120)) ||
            DISAMBIG.containsMatchIn(c.description.orEmpty())

    /** True if the text contains Chinese, Japanese or Korean characters. Checked per character, not with a regex. */
    private fun hasCjk(text: String): Boolean = text.any { ch ->
        when (Character.UnicodeScript.of(ch.code)) {
            Character.UnicodeScript.HAN, Character.UnicodeScript.HIRAGANA,
            Character.UnicodeScript.KATAKANA, Character.UnicodeScript.HANGUL -> true
            else -> false
        }
    }

    /**
     * Score adjustment from genre/country hints. Kept well below the 10,000 an exact title earns, so
     * hints choose between same-named articles but never promote a loosely related one over an exact match.
     */
    private class HintVerdict(val bonus: Int, val conflict: Boolean)

    private fun hintVerdict(c: WikiCandidate, hints: WikiHints?): HintVerdict {
        if (hints == null || hints.isEmpty) return HintVerdict(0, false)
        val text = normalize(haystack(c) + " " + c.title)
        fun hit(term: String): Boolean {
            val t = normalize(term)
            if (t.isBlank()) return false
            return if (hasCjk(t)) text.contains(t) else wordContains(text, t)
        }
        val positive = hints.positive.count(::hit).coerceAtMost(3)
        val negative = hints.negative.count(::hit).coerceAtMost(2)
        // An article that names a different nationality and nothing like ours is the wrong person:
        // reject it outright rather than show, say, an American rap group for a Japanese band.
        val conflict = positive == 0 && negative > 0
        return HintVerdict(positive * 1000 - negative * 300, conflict)
    }

    private val LOCAL_ARTIST_TERMS = mapOf(
        "ja" to listOf("バンド", "歌手"),
        "ko" to listOf("가수", "밴드"),
        "zh" to listOf("乐队", "歌手")
    )

    /** Search phrases that pull the right same-named artist up: "Penthouse japanese", or "Penthouse バンド" on ja. */
    fun hintedArtistQueries(name: String, hints: WikiHints, lang: String = "en"): List<String> =
        (LOCAL_ARTIST_TERMS[lang] ?: hints.queryTerms).map { "$name $it" }

    fun pickArtist(candidates: List<WikiCandidate>, name: String, hints: WikiHints? = null): WikiCandidate? {
        val target = stripArticle(normalize(name))
        if (target.isBlank()) return null
        return candidates
            .asSequence()
            .filterNot(::isDisambiguation)
            .filter { MUSIC_WORDS.containsMatchIn(haystack(it)) }
            .mapNotNull { c ->
                val t = stripArticle(baseTitle(c.title))
                val titleScore = when {
                    t == target -> 100
                    wordContains(t, target) || (t.length >= 3 && wordContains(target, t)) -> 40
                    else -> return@mapNotNull null
                }
                val verdict = hintVerdict(c, hints)
                if (verdict.conflict) return@mapNotNull null
                // Exact title wins; ties go to the higher search rank.
                c to (titleScore * 100 - c.rank + verdict.bonus)
            }
            .maxByOrNull { it.second }
            ?.first
    }

    fun pickSong(candidates: List<WikiCandidate>, songTitle: String, artistRaw: String?, hints: WikiHints? = null): WikiCandidate? {
        val target = normalize(songTitle)
        if (target.isBlank()) return null
        val artists = artistNames(artistRaw).map { stripArticle(normalize(it)) }.filter { it.length >= 2 }
        return candidates
            .asSequence()
            .filterNot(::isDisambiguation)
            .mapNotNull { c ->
                val t = baseTitle(c.title)
                val titleScore = when {
                    t == target -> 100
                    target.length >= 4 && wordContains(t, target) -> 40
                    else -> return@mapNotNull null
                }
                val text = haystack(c)
                val isSong = SONG_WORDS.containsMatchIn(text)
                val isAlbumOnly = ALBUM_WORDS.containsMatchIn(c.description.orEmpty()) && !isSong
                if (!isSong || isAlbumOnly) return@mapNotNull null
                val blob = normalize(text + " " + c.title)
                val artistHit = artists.any { blob.contains(it) }
                // With a known artist we insist on seeing it; otherwise only trust an exact title.
                if (artists.isNotEmpty() && !artistHit) return@mapNotNull null
                if (artists.isEmpty() && titleScore < 100) return@mapNotNull null
                val verdict = hintVerdict(c, hints)
                if (verdict.conflict) return@mapNotNull null
                c to (titleScore * 100 + (if (artistHit) 50 else 0) * 10 - c.rank + verdict.bonus)
            }
            .maxByOrNull { it.second }
            ?.first
    }

    fun songQueries(title: String, artistRaw: String?): List<String> {
        val a = artistNames(artistRaw).firstOrNull()
        val t = cleanSongTitle(title)
        if (t.isBlank()) return emptyList()
        return if (a == null) listOf("$t song") else listOf("$t $a song", "$t $a")
    }

    fun artistQueries(name: String): List<String> = listOf(name, "\"$name\" musician", "\"$name\" band")
}
