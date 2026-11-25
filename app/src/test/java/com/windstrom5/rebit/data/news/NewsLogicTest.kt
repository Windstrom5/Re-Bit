package com.windstrom5.rebit.data.news

import com.windstrom5.rebit.data.history.PersonaMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset

class NewsLogicTest {
    private fun utcMs(d: LocalDate) = d.atStartOfDay().toEpochSecond(ZoneOffset.UTC) * 1000

    @Test fun musicBrainzPartialDates() {
        assertEquals(utcMs(LocalDate.of(2026, 9, 18)), NewsLogic.parseMusicBrainzDate("2026-09-18"))
        assertEquals(utcMs(LocalDate.of(2026, 1, 1)), NewsLogic.parseMusicBrainzDate("2026"))
        assertEquals(utcMs(LocalDate.of(2026, 9, 1)), NewsLogic.parseMusicBrainzDate("2026-09"))
        assertNull(NewsLogic.parseMusicBrainzDate(""))
        assertNull(NewsLogic.parseMusicBrainzDate("abc"))
    }

    @Test fun gdeltDate() {
        assertEquals(
            LocalDateTime.of(2026, 9, 25, 9, 45).toEpochSecond(ZoneOffset.UTC) * 1000,
            NewsLogic.parseGdeltDate("20260925T094500Z")
        )
        assertNull(NewsLogic.parseGdeltDate("garbage"))
    }

    @Test fun followList() {
        assertEquals("Daft Punk", NewsLogic.followName("Daft Punk feat. Pharrell"))
        assertNull(NewsLogic.followName("Unknown Artist"))
        assertEquals(listOf("Muse", "Queen"), NewsLogic.followList(listOf("Muse", "muse", "Queen", null, "Unknown", "Queen ft. X")))
    }

    @Test fun gdeltQuery() {
        assertEquals(
            "(\"Muse\" OR \"AC/DC\") (music OR album OR song OR band OR tour OR concert OR single)",
            NewsLogic.gdeltQuery(listOf("Muse", "Yo", "AC/DC"))
        )
        assertNull(NewsLogic.gdeltQuery(listOf("Yo")))
    }

    @Test fun attributionIsPrecise() {
        val names = listOf("Queen", "Muse", "Radiohead")
        assertEquals("Radiohead", NewsLogic.attribute("Radiohead announce 2027 tour dates", "example.com", names))
        assertNull(NewsLogic.attribute("Queen Elizabeth visits Scotland", "bbc.com", names))
        assertEquals("Queen", NewsLogic.attribute("Queen on the cover of the issue", "rollingstone.com", names))
        assertNull(NewsLogic.attribute("Museum announces new album exhibit", "x.com", names))
    }

    @Test fun relativeTimeAndLabels() {
        val now = utcMs(LocalDate.of(2026, 10, 14))
        assertEquals("Just now", NewsLogic.relativeTime(now, now - 30_000L))
        assertEquals("5m ago", NewsLogic.relativeTime(now, now - 5L * 60_000))
        assertEquals("3h ago", NewsLogic.relativeTime(now, now - 3L * 3_600_000))
        assertEquals("2d ago", NewsLogic.relativeTime(now, now - 2L * 86_400_000))
        assertEquals("Sep 14, 2026", NewsLogic.relativeTime(now, now - 30L * 86_400_000))
        assertEquals("Out Oct 24, 2026", NewsLogic.releaseLabel(now, now + 10L * 86_400_000))
        assertEquals("Released 2d ago", NewsLogic.releaseLabel(now, now - 2L * 86_400_000))
        assertEquals("nme.com", NewsLogic.sourceOf("https://www.nme.com/news/x"))
        assertNull(NewsLogic.sourceOf("not a url"))
    }

    @Test fun persona() {
        val night = IntArray(24) { if (it in 22..23 || it < 3) 10 else 1 }
        assertEquals("Night Owl", PersonaMath.persona(night, IntArray(7) { 1 }).title)
        assertEquals("Fresh ears", PersonaMath.persona(IntArray(24), IntArray(7)).title)
        assertEquals(21, PersonaMath.peakHour(IntArray(24) { if (it == 21) 5 else 0 }))
        assertEquals("9 PM", PersonaMath.hourLabel(21))
    }
}
