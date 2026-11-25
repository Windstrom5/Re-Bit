package com.windstrom5.rebit.data.news

import android.content.Context

/** Small on/off switches for the feed. SharedPreferences is plenty for three booleans. */
class NewsPrefs(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("artist_feed", Context.MODE_PRIVATE)

    var notificationsEnabled: Boolean
        get() = prefs.getBoolean("notify", true)
        set(v) = prefs.edit().putBoolean("notify", v).apply()

    var releasesEnabled: Boolean
        get() = prefs.getBoolean("releases", true)
        set(v) = prefs.edit().putBoolean("releases", v).apply()

    var newsEnabled: Boolean
        get() = prefs.getBoolean("news", true)
        set(v) = prefs.edit().putBoolean("news", v).apply()

    /**
     * Check every ~15 minutes (the shortest interval Android allows for background work) instead of every
     * 12 hours. There is no push service for these feeds, so frequent polling is how new items are noticed
     * quickly. It uses a little more battery and data; turn it off to go back to twice a day.
     */
    var fastChecks: Boolean
        get() = prefs.getBoolean("fast_checks", true)
        set(v) = prefs.edit().putBoolean("fast_checks", v).apply()

    var lastSyncMs: Long
        get() = prefs.getLong("last_sync", 0L)
        set(v) = prefs.edit().putLong("last_sync", v).apply()
}
