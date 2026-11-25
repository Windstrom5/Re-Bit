package com.windstrom5.rebit.data.history

import com.windstrom5.rebit.domain.model.AudioTrackModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import java.util.TimeZone

/** Read/write facade over [ListeningDao]; owns the "what does Today / 7 days mean" logic. */
class ListeningRepository(private val dao: ListeningDao) {

    private fun tzOffset(now: Long): Long = TimeZone.getDefault().getOffset(now).toLong()

    private fun since(range: RankRange): Long {
        val now = System.currentTimeMillis()
        return StatsMath.sinceMs(range, now, tzOffset(now))
    }

    fun topTracks(range: RankRange, limit: Int = 20): Flow<List<TrackRankRow>> = dao.topTracks(since(range), limit)
    fun topArtists(range: RankRange, limit: Int = 12): Flow<List<ArtistRankRow>> = dao.topArtists(since(range), limit)
    fun totals(range: RankRange): Flow<RangeTotals> = dao.totals(since(range))
    fun recentlyPlayed(limit: Int = 15): Flow<List<TrackRankRow>> = dao.recentlyPlayed(limit)

    /** Listening ms per local day for the last [days] days (zero-filled), oldest first. */
    fun activity(days: Int = 14): Flow<List<Pair<Long, Long>>> {
        val now = System.currentTimeMillis()
        val tz = tzOffset(now)
        val today = StatsMath.localDay(now, tz)
        val since = (today - days + 1) * StatsMath.DAY_MS - tz
        return dao.dailyTotals(since, tz).map { rows ->
            StatsMath.fillDays(rows.associate { it.day to it.listenedMs }, today, days)
        }
    }

    fun streakDays(): Flow<Int> {
        val now = System.currentTimeMillis()
        val tz = tzOffset(now)
        return dao.listenDays(tz).map { StatsMath.streak(it, StatsMath.localDay(System.currentTimeMillis(), tz)) }
    }

    /** One-shot recap for [period]; empty/zeroed if nothing was played. */
    suspend fun wrapped(period: WrappedPeriod): WrappedData {
        val now = System.currentTimeMillis()
        val tz = tzOffset(now)
        val since = period.sinceMs(now)
        val hours = IntArray(24).also { arr -> dao.hourHistogram(since, tz).forEach { if (it.bucket in 0..23) arr[it.bucket] = it.plays } }
        val weekdays = IntArray(7).also { arr -> dao.weekdayHistogram(since, tz).forEach { if (it.bucket in 0..6) arr[it.bucket] = it.plays } }
        return WrappedData(
            period = period,
            totals = dao.totalsOnce(since),
            topTracks = dao.topTracksOnce(since, 5),
            topArtists = dao.topArtistsOnce(since, 5),
            hourPlays = hours.toList(),
            weekdayPlays = weekdays.toList(),
            longestStreak = StatsMath.longestStreak(dao.listenDaysSince(since, tz)),
            persona = PersonaMath.persona(hours, weekdays)
        )
    }

    fun playedPaths(): Flow<List<String>> = dao.playedPaths()
    fun rediscover(limit: Int = 12): Flow<List<TrackRankRow>> =
        dao.rediscover(System.currentTimeMillis() - 45 * StatsMath.DAY_MS, limit)

    fun favorites(): Flow<List<FavoriteEntity>> = dao.favorites()
    fun favoritePaths(): Flow<Set<String>> = dao.favorites().map { list -> list.map { it.filePath }.toSet() }

    suspend fun toggleFavorite(track: AudioTrackModel, currentlyFavorite: Boolean) {
        if (currentlyFavorite) dao.removeFavorite(track.filePath)
        else dao.addFavorite(FavoriteEntity(track.filePath, track.title, track.artist, System.currentTimeMillis()))
    }

    suspend fun recordPlay(event: PlayEventEntity): Long = dao.insertPlay(event)
    suspend fun updateListened(id: Long, ms: Long) = dao.updateListened(id, ms)
    suspend fun clearHistory() = dao.clearHistory()
}
