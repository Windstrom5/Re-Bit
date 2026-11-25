package com.windstrom5.rebit.data.news

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import org.koin.java.KoinJavaComponent
import java.util.concurrent.TimeUnit

/** Background check for new releases / headlines. Every ~15 min (or ~12 h if fast checks are off) when online. */
class ArtistNewsWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = try {
        KoinJavaComponent.get<ArtistFeedRepository>(ArtistFeedRepository::class.java).runSync()
        Result.success()
    } catch (e: Exception) {
        if (runAttemptCount < 3) Result.retry() else Result.failure()
    }
}

object NewsScheduler {
    private const val PERIODIC = "artist_feed_periodic"
    private const val NOW = "artist_feed_now"
    private const val FAST_MINUTES = 15L
    private const val SLOW_HOURS = 12L

    /** Skip a foreground check when one finished this recently. */
    const val STALE_AFTER_MS = 10 * 60 * 1000L

    // Not on a nearly-empty battery. Each run is a minute or so of network, bounded by the sync engine.
    private val online = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .setRequiresBatteryNotLow(true)
        .build()

    /**
     * (Re)schedules the background check. UPDATE swaps the interval of an already-scheduled job, so users who
     * installed an earlier build (12 h) move to the new setting without reinstalling.
     */
    fun schedule(context: Context) {
        val fast = NewsPrefs(context).fastChecks
        val builder = if (fast) PeriodicWorkRequestBuilder<ArtistNewsWorker>(FAST_MINUTES, TimeUnit.MINUTES)
        else PeriodicWorkRequestBuilder<ArtistNewsWorker>(SLOW_HOURS, TimeUnit.HOURS)
        val request = builder
            .setConstraints(online)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    fun runNow(context: Context) {
        val request = OneTimeWorkRequestBuilder<ArtistNewsWorker>().setConstraints(online).build()
        WorkManager.getInstance(context).enqueueUniqueWork(NOW, ExistingWorkPolicy.KEEP, request)
    }

    /** For app start/resume: look for news right away unless a check just ran. */
    fun runIfStale(context: Context, maxAgeMs: Long = STALE_AFTER_MS) {
        val prefs = NewsPrefs(context)
        if (!prefs.notificationsEnabled) return
        if (System.currentTimeMillis() - prefs.lastSyncMs >= maxAgeMs) runNow(context)
    }
}
