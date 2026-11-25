package com.windstrom5.rebit.data.history

/** Time-range presets for rankings. [days] = 0 means "since local midnight"; null = all time. */
enum class RankRange(val label: String, val days: Int?) {
    TODAY("Today", 0),
    WEEK("7 days", 7),
    MONTH("30 days", 30),
    ALL("All time", null)
}

/** Recap windows for the Wrapped screen. Rolling windows ending now (not calendar-aligned). */
enum class WrappedPeriod(val label: String, val adjective: String, val days: Int?) {
    WEEK("Week", "Weekly", 7),
    MONTH("Month", "Monthly", 30),
    YEAR("Year", "Yearly", 365),
    ALL("All time", "All-time", null);

    fun sinceMs(nowMs: Long): Long = if (days == null) 0L else nowMs - days * StatsMath.DAY_MS
}

object StatsMath {
    const val DAY_MS = 86_400_000L

    /** Epoch-ms lower bound for [range]. Today = local midnight, N days = rolling window. */
    fun sinceMs(range: RankRange, nowMs: Long, tzOffsetMs: Long): Long = when (range.days) {
        null -> 0L
        0 -> localDay(nowMs, tzOffsetMs) * DAY_MS - tzOffsetMs
        else -> nowMs - range.days * DAY_MS
    }

    /** Days since epoch in the user's local time zone. */
    fun localDay(epochMs: Long, tzOffsetMs: Long): Long = Math.floorDiv(epochMs + tzOffsetMs, DAY_MS)

    /**
     * Consecutive listening days ending today. A streak that ended yesterday is still alive
     * (you haven't had the chance to listen yet today); anything older is 0.
     */
    fun streak(listenDays: Collection<Long>, today: Long): Int {
        val days = listenDays.toHashSet()
        var cursor = when {
            today in days -> today
            (today - 1) in days -> today - 1
            else -> return 0
        }
        var n = 0
        while (cursor in days) { n++; cursor-- }
        return n
    }

    /** A play counts after 30 s, or half the track if it is shorter than a minute (min 3 s). */
    fun countThresholdMs(trackDurationMs: Long): Long =
        if (trackDurationMs <= 0L) 30_000L else minOf(30_000L, maxOf(3_000L, trackDurationMs / 2))

    /** "1h 05m", "42m", "<1m". */
    fun formatListening(ms: Long): String {
        val totalMin = ms / 60_000
        return when {
            ms <= 0L -> "0m"
            totalMin < 1 -> "<1m"
            totalMin < 60 -> "${totalMin}m"
            else -> "%dh %02dm".format(totalMin / 60, totalMin % 60)
        }
    }

    /** Fills missing days with zeros so a bar chart always has [days] bars ending today. */
    fun fillDays(rows: Map<Long, Long>, today: Long, days: Int): List<Pair<Long, Long>> =
        (today - days + 1..today).map { it to (rows[it] ?: 0L) }

    /** Longest run of consecutive listening days anywhere in [listenDays]. */
    fun longestStreak(listenDays: Collection<Long>): Int {
        if (listenDays.isEmpty()) return 0
        val sorted = listenDays.toSortedSet().toList()
        var best = 1
        var run = 1
        for (i in 1 until sorted.size) {
            run = if (sorted[i] == sorted[i - 1] + 1) run + 1 else 1
            if (run > best) best = run
        }
        return best
    }

    /** "About 3 albums" — a relatable yardstick for a listening total (avg album ≈ 45 min). */
    fun albumsEquivalent(listenedMs: Long): Int = (listenedMs / (45 * 60_000L)).toInt()

    fun greeting(hourOfDay: Int): String = when (hourOfDay) {
        in 5..11 -> "Good morning"
        in 12..17 -> "Good afternoon"
        in 18..22 -> "Good evening"
        else -> "Late night listening"
    }
}

/**
 * Accumulates real listening time from playback snapshots. Wall-clock based and capped per tick,
 * so pausing stops the clock and seeking cannot inflate the total.
 */
class ListenTimer(private val maxGapMs: Long = 2_000L) {
    var listenedMs: Long = 0L
        private set
    private var lastTickMs: Long? = null

    fun tick(isPlaying: Boolean, nowMs: Long) {
        val last = lastTickMs
        if (isPlaying && last != null) listenedMs += (nowMs - last).coerceIn(0L, maxGapMs)
        lastTickMs = if (isPlaying) nowMs else null
    }
}

/** What your listening says about you — shown on the Wrapped recap. */
data class ListeningPersona(val title: String, val blurb: String)

object PersonaMath {
    /** [hourPlays] has 24 entries (local hour), [weekdayPlays] 7 entries (Mon=0 .. Sun=6). */
    fun persona(hourPlays: IntArray, weekdayPlays: IntArray): ListeningPersona {
        val total = hourPlays.sum()
        if (total == 0) return ListeningPersona("Fresh ears", "Play a few songs and your listening personality will show up.")
        fun share(hours: IntRange) = hours.sumOf { hourPlays[it % 24] }.toDouble() / total
        val night = share(22..27) // 22:00–03:59
        val morning = share(5..9)
        val workday = share(10..17)
        val weekend = (weekdayPlays.getOrElse(5) { 0 } + weekdayPlays.getOrElse(6) { 0 }).toDouble() /
            weekdayPlays.sum().coerceAtLeast(1)
        return when {
            night >= 0.35 -> ListeningPersona("Night Owl", "Most of your music plays after dark.")
            morning >= 0.30 -> ListeningPersona("Early Bird", "You start the day with music.")
            weekend >= 0.55 -> ListeningPersona("Weekend Warrior", "Saturdays and Sundays are when you turn it up.")
            workday >= 0.50 -> ListeningPersona("Daytime Listener", "Music keeps your workday moving.")
            else -> ListeningPersona("All-Rounder", "You listen at all hours, any day of the week.")
        }
    }

    fun peakHour(hourPlays: IntArray): Int? =
        hourPlays.withIndex().maxByOrNull { it.value }?.takeIf { it.value > 0 }?.index

    fun hourLabel(h: Int): String = when {
        h == 0 -> "12 AM"
        h < 12 -> "$h AM"
        h == 12 -> "12 PM"
        else -> "${h - 12} PM"
    }

    val WEEKDAYS = listOf("Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday")
}
