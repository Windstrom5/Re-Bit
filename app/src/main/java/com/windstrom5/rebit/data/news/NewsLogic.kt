package com.windstrom5.rebit.data.news

import com.windstrom5.rebit.data.wikipedia.WikiMatcher
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** Pure helpers for the artist feed: no Android, no network, unit-tested on the JVM. */
object NewsLogic {

    /** MusicBrainz dates can be "2026-09-18", "2026-09" or just "2026". Missing parts default to the 1st. */
    fun parseMusicBrainzDate(raw: String?): Long? {
        if (raw.isNullOrBlank()) return null
        val parts = raw.trim().split("-")
        return try {
            val y = parts[0].toInt()
            val m = parts.getOrNull(1)?.toInt() ?: 1
            val d = parts.getOrNull(2)?.toInt() ?: 1
            LocalDate.of(y, m, d).atStartOfDay().toEpochSecond(ZoneOffset.UTC) * 1000
        } catch (e: Exception) {
            null
        }
    }

    private val GDELT_FMT = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")

    /** GDELT "seendate": 20260925T094500Z (UTC). */
    fun parseGdeltDate(raw: String?): Long? = try {
        if (raw.isNullOrBlank()) null
        else LocalDateTime.parse(raw.trim(), GDELT_FMT).toEpochSecond(ZoneOffset.UTC) * 1000
    } catch (e: Exception) {
        null
    }

    /** The name we follow for a tag like "Daft Punk feat. Pharrell" -> "Daft Punk". */
    fun followName(rawArtistTag: String?): String? =
        WikiMatcher.artistNames(rawArtistTag).firstOrNull()?.trim()?.takeIf { it.isNotBlank() }

    /** Unique, case-insensitively de-duplicated follow list from every artist tag in the library. */
    fun followList(artistTags: Collection<String?>): List<String> =
        artistTags.mapNotNull(::followName).distinctBy { it.lowercase() }.sorted()

    /**
     * GDELT query for a batch of artists. Context words keep results musical; very short names
     * (<3 chars) are skipped because GDELT rejects them and they match everything anyway.
     */
    fun gdeltQuery(names: List<String>): String? {
        val usable = names.filter { it.length >= 3 && '"' !in it }
        if (usable.isEmpty()) return null
        val ors = usable.joinToString(" OR ") { "\"$it\"" }
        return "($ors) (music OR album OR song OR band OR tour OR concert OR single)"
    }

    private val MUSIC_WORDS = Regex(
        "\\b(album|single|song|songs|track|tour|concert|band|music|ep|lp|release|releases|released|" +
            "video|live|festival|remix|lyrics|vinyl|reissue|announces?|drops?|debut|collab\\w*|setlist|gig)\\b",
        RegexOption.IGNORE_CASE
    )

    private val MUSIC_DOMAINS = setOf(
        "rollingstone.com", "billboard.com", "pitchfork.com", "nme.com", "consequence.net", "stereogum.com",
        "loudwire.com", "kerrang.com", "brooklynvegan.com", "musicradar.com", "metalinjection.net",
        "ultimateclassicrock.com", "blabbermouth.net", "classicrockhistory.com", "udiscovermusic.com",
        "thefader.com", "complex.com", "hotnewhiphop.com", "xxlmag.com", "allmusic.com", "spin.com",
        "musicfeeds.com.au", "kapanlagi.com", "jpopasia.com", "allkpop.com", "soompi.com", "koreaboo.com"
    )

    /**
     * Which followed artist (if any) a headline is about. The name must appear as whole words in the
     * title (longest name wins), and the article must look musical: a music word in the title or a
     * music outlet as the source. This trades recall for precision — "Queen Elizabeth" stays out.
     */
    fun attribute(title: String, domain: String?, names: Collection<String>): String? {
        val hit = names
            .filter { it.length >= 3 }
            .filter { Regex("(?<![\\p{L}\\p{N}])" + Regex.escape(it) + "(?![\\p{L}\\p{N}])", RegexOption.IGNORE_CASE).containsMatchIn(title) }
            .maxByOrNull { it.length } ?: return null
        val d = domain?.lowercase()?.removePrefix("www.")
        val musical = MUSIC_WORDS.containsMatchIn(title) || (d != null && MUSIC_DOMAINS.any { d == it || d.endsWith(".$it") })
        return if (musical) hit else null
    }

    /** MusicBrainz asks for a meaningful User-Agent. */
    const val USER_AGENT = "BitPerfectUSB/1.0 (Android audiophile player; personal use)"

    fun releaseId(mbid: String) = "rg:$mbid"
    fun newsId(url: String) = "news:$url"

    /** "Radiohead: new album \"Title\"" style strings for notifications. */
    fun releaseLine(primaryType: String?, title: String): String {
        val kind = when (primaryType?.lowercase()) {
            "album" -> "album"
            "single" -> "single"
            "ep" -> "EP"
            else -> "release"
        }
        return "New $kind: $title"
    }

    private val DATE_FMT = DateTimeFormatter.ofPattern("MMM d, yyyy", java.util.Locale.ENGLISH)

    fun formatDate(epochMs: Long): String =
        java.time.Instant.ofEpochMilli(epochMs).atZone(ZoneOffset.UTC).toLocalDate().format(DATE_FMT)

    /** "Just now", "5m ago", "3h ago", "2d ago", else a date. */
    fun relativeTime(nowMs: Long, thenMs: Long): String {
        val diff = nowMs - thenMs
        return when {
            diff < 60_000L -> "Just now"
            diff < 3_600_000L -> "${diff / 60_000L}m ago"
            diff < 86_400_000L -> "${diff / 3_600_000L}h ago"
            diff < 14 * 86_400_000L -> "${diff / 86_400_000L}d ago"
            else -> formatDate(thenMs)
        }
    }

    /** "Out Nov 1, 2026" for future releases, "Released 3d ago" otherwise. */
    fun releaseLabel(nowMs: Long, publishedMs: Long): String =
        if (publishedMs > nowMs) "Out ${formatDate(publishedMs)}" else "Released ${relativeTime(nowMs, publishedMs).let { if (it.endsWith("ago") || it == "Just now") it else "on $it" }}"

    fun sourceOf(url: String?): String? = try {
        url?.let { java.net.URI(it).host?.removePrefix("www.") }
    } catch (e: Exception) {
        null
    }
}
