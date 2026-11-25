package com.windstrom5.rebit.data.history

import org.junit.Assert.assertEquals
import org.junit.Test

class StatsMathTest {
    private val day = StatsMath.DAY_MS
    private val tz = 7 * 3_600_000L // UTC+7
    private val now = 20_000 * day + 3 * 3_600_000L

    @Test fun todayStartsAtLocalMidnight() {
        assertEquals(20_000 * day - tz, StatsMath.sinceMs(RankRange.TODAY, now, tz))
    }

    @Test fun localDayRollsOverWithTimeZone() {
        assertEquals(20_001L, StatsMath.localDay(20_000 * day + 20 * 3_600_000L, tz))
    }

    @Test fun rollingWindows() {
        assertEquals(now - 7 * day, StatsMath.sinceMs(RankRange.WEEK, now, tz))
        assertEquals(now - 30 * day, StatsMath.sinceMs(RankRange.MONTH, now, tz))
        assertEquals(0L, StatsMath.sinceMs(RankRange.ALL, now, tz))
    }

    @Test fun streak() {
        assertEquals(3, StatsMath.streak(listOf(10L, 9, 8, 5), today = 10))
        assertEquals(2, StatsMath.streak(listOf(9L, 8), today = 10)) // yesterday keeps it alive
        assertEquals(0, StatsMath.streak(listOf(7L, 6), today = 10))
    }

    @Test fun playCountThreshold() {
        assertEquals(30_000L, StatsMath.countThresholdMs(240_000))
        assertEquals(10_000L, StatsMath.countThresholdMs(20_000))
        assertEquals(3_000L, StatsMath.countThresholdMs(2_000))
    }

    @Test fun formatting() {
        assertEquals("1h 05m", StatsMath.formatListening(65 * 60_000L))
        assertEquals("42m", StatsMath.formatListening(42 * 60_000L))
        assertEquals("<1m", StatsMath.formatListening(30_000L))
        assertEquals("0m", StatsMath.formatListening(0L))
    }

    @Test fun fillDaysZeroFills() {
        assertEquals(listOf(8L to 0L, 9L to 5L, 10L to 0L), StatsMath.fillDays(mapOf(9L to 5L), today = 10, days = 3))
    }

    @Test fun listenTimerPausesAndCapsGaps() {
        val t = ListenTimer()
        t.tick(false, 0); t.tick(true, 1_000); t.tick(true, 1_500); t.tick(true, 2_000)
        t.tick(false, 2_500)
        t.tick(true, 60_000); t.tick(true, 60_500)
        assertEquals(1_500L, t.listenedMs) // pause adds nothing; resuming restarts the clock
    }
}
