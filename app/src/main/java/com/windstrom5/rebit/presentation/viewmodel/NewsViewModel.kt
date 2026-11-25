package com.windstrom5.rebit.presentation.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.windstrom5.rebit.data.news.ArtistFeedRepository
import com.windstrom5.rebit.data.news.FeedItemEntity
import com.windstrom5.rebit.data.news.NewsPrefs
import com.windstrom5.rebit.data.news.NewsScheduler
import com.windstrom5.rebit.data.news.WatchedArtistEntity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class NewsSettings(val notify: Boolean, val releases: Boolean, val news: Boolean, val fast: Boolean = true)

class NewsViewModel(
    private val repository: ArtistFeedRepository,
    private val prefs: NewsPrefs,
    private val appContext: Context
) : ViewModel() {

    val feed: StateFlow<List<FeedItemEntity>> = repository.feed(150)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val unread: StateFlow<Int> = repository.unreadCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    val followed: StateFlow<List<WatchedArtistEntity>> = repository.followedArtists()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val syncing: StateFlow<Boolean> = repository.syncing

    private val _settings = MutableStateFlow(NewsSettings(prefs.notificationsEnabled, prefs.releasesEnabled, prefs.newsEnabled, prefs.fastChecks))
    val settings: StateFlow<NewsSettings> = _settings

    fun lastSyncMs(): Long = prefs.lastSyncMs

    /** Runs through WorkManager so the check survives leaving the screen. */
    fun refresh() = NewsScheduler.runNow(appContext)

    fun markAllRead() { viewModelScope.launch { repository.markAllRead() } }

    fun setNotify(on: Boolean) { prefs.notificationsEnabled = on; _settings.value = _settings.value.copy(notify = on) }
    /** Switches between ~15-minute and ~12-hour background checks and reschedules right away. */
    fun setFast(on: Boolean) {
        prefs.fastChecks = on
        _settings.value = _settings.value.copy(fast = on)
        NewsScheduler.schedule(appContext)
    }
    fun setReleases(on: Boolean) { prefs.releasesEnabled = on; _settings.value = _settings.value.copy(releases = on) }
    fun setNews(on: Boolean) { prefs.newsEnabled = on; _settings.value = _settings.value.copy(news = on) }

    fun setArtistEnabled(name: String, enabled: Boolean) {
        viewModelScope.launch { repository.setArtistEnabled(name, enabled) }
    }
}
