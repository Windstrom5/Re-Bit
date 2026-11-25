package com.windstrom5.rebit.data.news

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.windstrom5.rebit.R
import com.windstrom5.rebit.presentation.MainActivity

/** Posts "new release" / "news" notifications. Tapping one opens the News screen. */
class NewsNotifier(context: Context) {
    private val ctx = context.applicationContext

    companion object {
        // A new id: a channel's importance can't be raised after creation, and these should pop up as heads-up alerts.
        const val CHANNEL_ID = "artist_feed_alerts"
        private const val OLD_CHANNEL_ID = "artist_feed"
        const val EXTRA_ROUTE = "open_route"
        const val ROUTE_NEWS = "news"
        private const val GROUP = "artist_feed_group"
        private const val SUMMARY_ID = 7001
        private const val MAX_INDIVIDUAL = 5
    }

    private fun canPost(): Boolean {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return false
        return NotificationManagerCompat.from(ctx).areNotificationsEnabled()
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < 26) return
        val channel = NotificationChannel(CHANNEL_ID, "New releases & artist news", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "New albums, singles and headlines from artists you follow, as soon as they're found"
        }
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(channel)
        nm.deleteNotificationChannel(OLD_CHANNEL_ID)
    }

    private fun openNewsIntent(): PendingIntent {
        val intent = Intent(ctx, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(EXTRA_ROUTE, ROUTE_NEWS)
        }
        return PendingIntent.getActivity(ctx, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    fun show(items: List<FeedItemEntity>) {
        if (items.isEmpty() || !canPost()) return
        ensureChannel()
        val nm = NotificationManagerCompat.from(ctx)
        val tap = openNewsIntent()

        // Releases first: they are the most actionable.
        val ordered = items.sortedWith(compareBy({ it.kind != FeedKind.RELEASE.name }, { -it.publishedAtMs }))
        ordered.take(MAX_INDIVIDUAL).forEach { item ->
            val isRelease = item.kind == FeedKind.RELEASE.name
            val n = NotificationCompat.Builder(ctx, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_launcher)
                .setContentTitle(if (isRelease) "${item.artist} · new music" else item.artist)
                .setContentText(item.title)
                .setStyle(NotificationCompat.BigTextStyle().bigText(item.title))
                .setContentIntent(tap)
                .setAutoCancel(true)
                .setGroup(GROUP)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
                .setWhen(item.publishedAtMs.takeIf { it > 0 } ?: item.discoveredAtMs)
                .setShowWhen(true)
                .apply {
                    val link = item.url?.takeIf { it.startsWith("http") }
                    if (link != null) {
                        val view = Intent(Intent.ACTION_VIEW, Uri.parse(link)).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK }
                        addAction(
                            R.drawable.ic_launcher, if (isRelease) "Details" else "Read",
                            PendingIntent.getActivity(ctx, item.id.hashCode(), view, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
                        )
                    }
                }
                .build()
            nm.notify(item.id.hashCode(), n)
        }
        if (items.size > 1) {
            val summary = NotificationCompat.Builder(ctx, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_launcher)
                .setContentTitle("${items.size} updates from artists you listen to")
                .setStyle(
                    NotificationCompat.InboxStyle().also { style ->
                        ordered.take(MAX_INDIVIDUAL).forEach { style.addLine("${it.artist}: ${it.title}") }
                    }
                )
                .setContentIntent(tap)
                .setAutoCancel(true)
                .setGroup(GROUP)
                .setGroupSummary(true)
                .build()
            nm.notify(SUMMARY_ID, summary)
        }
    }
}
