package com.windstrom5.rebit.data.scanner

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.windstrom5.rebit.R
import com.windstrom5.rebit.domain.model.ScanPhase
import com.windstrom5.rebit.domain.model.ScanProgress
import com.windstrom5.rebit.domain.repository.AudioLibraryRepository
import com.windstrom5.rebit.presentation.MainActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import org.koin.java.KoinJavaComponent
import java.util.concurrent.TimeUnit

/** Runs the incremental library scan in the background; survives leaving the screen or the app. */
class LibraryScanWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = coroutineScope {
        val repo = KoinJavaComponent.get<AudioLibraryRepository>(AudioLibraryRepository::class.java)
        val notifier = ScanNotifier(applicationContext)
        val watcher = launch { repo.scanProgress.collect { notifier.update(it) } }
        try {
            repo.rescanLibrary()
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (runAttemptCount < 2) Result.retry() else Result.failure()
        } finally {
            watcher.cancel()
            notifier.finish()
        }
    }
}

object LibraryScanScheduler {
    private const val NOW = "library_scan_now"
    private const val PERIODIC = "library_scan_periodic"

    /** Queued behind a running scan (not dropped), so a folder added mid-scan is still picked up. */
    fun runNow(context: Context) {
        val request = OneTimeWorkRequestBuilder<LibraryScanWorker>().build()
        WorkManager.getInstance(context).enqueueUniqueWork(NOW, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
    }

    /** Picks up newly copied files while the app is idle. Incremental, so it's cheap when nothing changed. */
    fun schedulePeriodic(context: Context) {
        val request = PeriodicWorkRequestBuilder<LibraryScanWorker>(12, TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build())
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, request)
    }
}

/** Quiet progress notification; only appears if a scan takes more than a couple of seconds. */
class ScanNotifier(context: Context) {
    private val ctx = context.applicationContext
    private var startedAt = 0L
    private var shown = false
    private var last = ScanProgress()

    private companion object {
        const val CHANNEL = "library_scan"
        const val ID = 7101
        const val DELAY_MS = 2_500L
    }

    private fun canPost(): Boolean {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return false
        return NotificationManagerCompat.from(ctx).areNotificationsEnabled()
    }

    private fun builder(): NotificationCompat.Builder {
        if (Build.VERSION.SDK_INT >= 26) {
            val ch = NotificationChannel(CHANNEL, "Library scanning", NotificationManager.IMPORTANCE_LOW)
            ctx.getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
        val open = PendingIntent.getActivity(
            ctx, 0, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentIntent(open)
            .setOnlyAlertOnce(true)
            .setSilent(true)
    }

    fun update(p: ScanProgress) {
        last = p
        if (!p.isRunning || !canPost()) return
        if (startedAt == 0L) startedAt = System.currentTimeMillis()
        if (System.currentTimeMillis() - startedAt < DELAY_MS) return
        val (text, determinate) = when (p.phase) {
            ScanPhase.LISTING -> "Looking for music… ${p.discovered} files found" to false
            ScanPhase.ENRICHING -> "Reading tags ${p.processed} / ${p.total}" to true
            else -> "Reading tracks ${p.processed} / ${p.total}" to true
        }
        val n = builder()
            .setContentTitle("Scanning your library")
            .setContentText(text)
            .setOngoing(true)
            .setProgress(p.total.coerceAtLeast(0), p.processed, !determinate || p.total == 0)
            .build()
        NotificationManagerCompat.from(ctx).notify(ID, n)
        shown = true
    }

    fun finish() {
        val nm = NotificationManagerCompat.from(ctx)
        nm.cancel(ID)
        if (shown && last.phase == ScanPhase.DONE && canPost()) {
            val parts = listOfNotNull(
                if (last.added > 0) "${last.added} new" else null,
                if (last.removed > 0) "${last.removed} removed" else null
            )
            val n = builder()
                .setContentTitle("Library updated · ${"%,d".format(last.libraryCount)} tracks")
                .setContentText(if (parts.isEmpty()) "Nothing changed" else parts.joinToString(" · "))
                .setAutoCancel(true)
                .setTimeoutAfter(8_000)
                .build()
            nm.notify(ID + 1, n)
        }
    }
}
