package com.windstrom5.rebit.data.genre

import com.windstrom5.rebit.data.wikipedia.WikiHints

enum class ScriptHint { JAPANESE, KOREAN, HAN }

/**
 * Pure genre helpers: cleaning messy tag values, choosing a genre from online tags, and turning
 * "what we know about this song" into hints for the Wikipedia matcher. No Android, so it is
 * unit-tested on the JVM.
 */
object GenreLogic {

    /** The 80 standard ID3v1 genres, indexed by the number some taggers store instead of a name. */
    private val ID3V1 = listOf(
        "Blues", "Classic Rock", "Country", "Dance", "Disco", "Funk", "Grunge", "Hip-Hop", "Jazz", "Metal",
        "New Age", "Oldies", "Other", "Pop", "R&B", "Rap", "Reggae", "Rock", "Techno", "Industrial",
        "Alternative", "Ska", "Death Metal", "Pranks", "Soundtrack", "Euro-Techno", "Ambient", "Trip-Hop", "Vocal", "Jazz+Funk",
        "Fusion", "Trance", "Classical", "Instrumental", "Acid", "House", "Game", "Sound Clip", "Gospel", "Noise",
        "Alternative Rock", "Bass", "Soul", "Punk", "Space", "Meditative", "Instrumental Pop", "Instrumental Rock", "Ethnic", "Gothic",
        "Darkwave", "Techno-Industrial", "Electronic", "Pop-Folk", "Eurodance", "Dream", "Southern Rock", "Comedy", "Cult", "Gangsta",
        "Top 40", "Christian Rap", "Pop/Funk", "Jungle", "Native American", "Cabaret", "New Wave", "Psychedelic", "Rave", "Showtunes",
        "Trailer", "Lo-Fi", "Tribal", "Acid Punk", "Acid Jazz", "Polka", "Retro", "Musical", "Rock & Roll", "Hard Rock"
    )

    private val JUNK = setOf(
        "unknown", "other", "genre", "none", "(none)", "<unknown>", "misc", "miscellaneous", "various",
        "n/a", "undefined", "not set", "blank", "default"
    )

    private val ALIASES = mapOf(
        "jpop" to "J-Pop", "j pop" to "J-Pop", "j-pop" to "J-Pop", "japanese pop" to "J-Pop",
        "kpop" to "K-Pop", "k pop" to "K-Pop", "k-pop" to "K-Pop", "korean pop" to "K-Pop",
        "jrock" to "J-Rock", "j rock" to "J-Rock", "j-rock" to "J-Rock", "japanese rock" to "J-Rock",
        "citypop" to "City Pop", "city pop" to "City Pop",
        "hiphop" to "Hip-Hop", "hip hop" to "Hip-Hop", "hip-hop" to "Hip-Hop", "rap" to "Hip-Hop",
        "rnb" to "R&B", "r&b" to "R&B", "r and b" to "R&B", "rhythm and blues" to "R&B", "rhythm & blues" to "R&B",
        "electronica" to "Electronic", "edm" to "Electronic", "electronic dance music" to "Electronic", "electro" to "Electronic",
        "alternrock" to "Alternative Rock", "alt rock" to "Alternative Rock", "alt-rock" to "Alternative Rock",
        "ost" to "Soundtrack", "original soundtrack" to "Soundtrack", "score" to "Soundtrack",
        "pop/rock" to "Pop Rock", "pop rock" to "Pop Rock"
    )

    /** Genres we accept from free-form online *tags* (which also hold things like "seen live"). */
    private val VOCAB = setOf(
        "pop", "rock", "hip-hop", "r&b", "electronic", "jazz", "classical", "metal", "country", "folk", "blues",
        "soul", "funk", "reggae", "punk", "indie", "alternative", "dance", "house", "techno", "trance", "ambient",
        "soundtrack", "j-pop", "k-pop", "j-rock", "city pop", "anime", "latin", "gospel", "disco", "new wave",
        "synth-pop", "shoegaze", "emo", "hardcore", "ska", "world", "new age", "easy listening", "singer-songwriter",
        "idol", "vocaloid", "enka", "lo-fi", "drum and bass", "dubstep", "trip-hop", "progressive rock", "hard rock",
        "heavy metal", "death metal", "black metal", "math rock", "post-rock", "acoustic", "opera", "pop rock",
        "alternative rock", "indie rock", "indie pop", "dream pop", "art pop", "j-core", "visual kei", "dangdut",
        "bossa nova", "samba", "salsa", "chiptune", "video game music", "downtempo", "idm", "garage rock",
        "folk rock", "country rock", "soft rock", "power pop", "britpop", "grunge", "psychedelic rock"
    )

    private val NUMERIC_PAREN = Regex("""^\((\d{1,3})\)\s*(.*)$""")
    private val NUMERIC_ONLY = Regex("""^\d{1,3}$""")

    /** Cleans a raw tag value into a display genre, or null if it says nothing ("Unknown", "(255)", ""). */
    fun normalize(raw: String?): String? {
        var s = raw?.replace('\u0000', ';')?.trim() ?: return null
        // "Rock; Pop" / "Rock, Pop" list several; the first is the primary. "R&B/Soul" is one genre, so "/" stays.
        s = s.split(';', ',').map { it.trim() }.firstOrNull { it.isNotEmpty() } ?: return null

        val paren = NUMERIC_PAREN.matchEntire(s)
        if (paren != null) {
            val rest = paren.groupValues[2].trim()
            s = if (rest.isNotEmpty()) rest else (ID3V1.getOrNull(paren.groupValues[1].toInt()) ?: return null)
        } else if (NUMERIC_ONLY.matches(s)) {
            s = ID3V1.getOrNull(s.toInt()) ?: return null
        }
        // A bare number that isn't an ID3 code (a year, a track count) is not a genre.
        if (s.all { it.isDigit() }) return null

        val key = s.lowercase().replace('_', ' ').trim()
        if (key.isEmpty() || key in JUNK) return null
        ALIASES[key]?.let { return it }
        return titleCase(s).take(40)
    }

    private val SEPARATORS = Regex("(?<=[-/])|(?=[-/])")

    private fun titleCase(s: String): String = s.trim().split(Regex("\\s+")).joinToString(" ") { word ->
        word.split(SEPARATORS).joinToString("") { part ->
            when {
                part == "-" || part == "/" -> part
                // Acronyms stay as typed: "EDM", "OST", "UK".
                part.length in 2..3 && part == part.uppercase() && part.any { it.isLetter() } -> part
                part.contains('&') -> part.uppercase()
                else -> part.lowercase().replaceFirstChar { c -> c.uppercase() }
            }
        }
    }

    /**
     * MusicBrainz gives curated [genres] (trust the most-voted) and free-form [tags] (only accept
     * ones that really are genres). Counts are community vote totals.
     */
    fun pickGenre(genres: List<Pair<String, Int>>, tags: List<Pair<String, Int>>): String? {
        genres.sortedByDescending { it.second }.firstNotNullOfOrNull { normalize(it.first) }?.let { return it }
        return tags.sortedByDescending { it.second }
            .mapNotNull { normalize(it.first) }
            .firstOrNull { it.lowercase() in VOCAB }
    }

    /** Which writing system a title/artist uses, if it is a distinctive one. */
    fun scriptOf(text: String?): ScriptHint? {
        if (text.isNullOrBlank()) return null
        var kana = 0
        var hangul = 0
        var han = 0
        for (ch in text) {
            when (Character.UnicodeBlock.of(ch)) {
                Character.UnicodeBlock.HIRAGANA,
                Character.UnicodeBlock.KATAKANA,
                Character.UnicodeBlock.KATAKANA_PHONETIC_EXTENSIONS -> kana++
                Character.UnicodeBlock.HANGUL_SYLLABLES,
                Character.UnicodeBlock.HANGUL_JAMO,
                Character.UnicodeBlock.HANGUL_COMPATIBILITY_JAMO -> hangul++
                Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS,
                Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A -> han++
                else -> Unit
            }
        }
        return when {
            kana > 0 -> ScriptHint.JAPANESE
            hangul > 0 -> ScriptHint.KOREAN
            han > 0 -> ScriptHint.HAN
            else -> null
        }
    }

    // ── Wikipedia hints ────────────────────────────────────────────────────

    /** Words a Wikipedia description uses for each country: demonym first, then the place name. */
    private val COUNTRY_TERMS = mapOf(
        "JP" to listOf("japanese", "japan"),
        "KR" to listOf("korean", "south korea"),
        "CN" to listOf("chinese", "china"),
        "TW" to listOf("taiwanese", "taiwan"),
        "HK" to listOf("hong kong", "cantopop"),
        "ID" to listOf("indonesian", "indonesia"),
        "GB" to listOf("english", "british", "england", "united kingdom"),
        "US" to listOf("american", "united states"),
        "CA" to listOf("canadian", "canada"),
        "AU" to listOf("australian", "australia"),
        "DE" to listOf("german", "germany"),
        "FR" to listOf("french", "france"),
        "SE" to listOf("swedish", "sweden"),
        "BR" to listOf("brazilian", "brazil"),
        "ES" to listOf("spanish", "spain"),
        "IT" to listOf("italian", "italy"),
        "RU" to listOf("russian", "russia")
    )

    private val GENRE_NATION = mapOf(
        "j-pop" to "JP", "j-rock" to "JP", "city pop" to "JP", "anime" to "JP", "visual kei" to "JP",
        "vocaloid" to "JP", "enka" to "JP", "idol" to "JP", "j-core" to "JP", "k-pop" to "KR"
    )

    /**
     * Builds the hints the matcher uses to tell apart same-named artists. A J-Pop song by "Penthouse"
     * should match the Japanese act, not an English 1980s band with the same name.
     */
    fun wikiHints(genre: String?, country: String?, script: ScriptHint?): WikiHints {
        val g = genre?.lowercase()?.trim()?.takeIf { it.isNotEmpty() }
        val nation = country?.uppercase()?.takeIf { it in COUNTRY_TERMS }
            ?: g?.let { GENRE_NATION[it] }
            ?: when (script) {
                ScriptHint.JAPANESE -> "JP"
                ScriptHint.KOREAN -> "KR"
                else -> null
            }

        val positive = LinkedHashSet<String>()
        if (g != null) {
            positive += g
            if (g == "j-pop") positive += "jpop"
            if (g == "k-pop") positive += "kpop"
        }
        if (nation != null) positive += COUNTRY_TERMS.getValue(nation)
        if (script == ScriptHint.HAN && nation == null) positive += listOf("chinese", "japanese", "taiwanese")

        // Only claim a conflict when we are fairly sure of the nationality.
        val negative = if (nation == null) emptyList() else
            COUNTRY_TERMS.filterKeys { it != nation }.values.map { it.first() }
        // Wikipedia descriptions say "Japanese band" far more often than "J-pop", so search that first.
        val queryTerms = listOfNotNull(nation?.let { COUNTRY_TERMS.getValue(it).first() }, g).distinct()
        val languages = when (nation) {
            "JP" -> listOf("ja")
            "KR" -> listOf("ko")
            "CN", "TW", "HK" -> listOf("zh")
            else -> emptyList()
        }
        return WikiHints(positive.toList(), negative, queryTerms.ifEmpty { positive.take(2) }, languages)
    }
}
