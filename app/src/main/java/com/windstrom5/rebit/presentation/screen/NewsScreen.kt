package com.windstrom5.rebit.presentation.screen

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Article
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.windstrom5.rebit.data.news.FeedItemEntity
import com.windstrom5.rebit.data.news.FeedKind
import com.windstrom5.rebit.data.news.NewsLogic
import com.windstrom5.rebit.presentation.components.AudioPanel
import com.windstrom5.rebit.presentation.components.audioPanel
import com.windstrom5.rebit.presentation.components.ModernTopBar
import com.windstrom5.rebit.presentation.theme.BackgroundInk
import com.windstrom5.rebit.presentation.theme.HifiBrass
import com.windstrom5.rebit.presentation.theme.SignalBlue
import com.windstrom5.rebit.presentation.theme.SignalTeal
import com.windstrom5.rebit.presentation.theme.SurfacePanel
import com.windstrom5.rebit.presentation.theme.TextPrimary
import com.windstrom5.rebit.presentation.theme.TextSecondary
import com.windstrom5.rebit.presentation.viewmodel.NewsViewModel
import org.koin.androidx.compose.koinViewModel

private enum class FeedFilter(val label: String) { ALL("All"), RELEASES("Releases"), NEWS("News") }

/** New releases (MusicBrainz) and headlines (GDELT) for the artists in your library. */
@Composable
fun NewsScreen(
    onBack: () -> Unit,
    viewModel: NewsViewModel = koinViewModel()
) {
    val feed by viewModel.feed.collectAsStateWithLifecycle()
    val followed by viewModel.followed.collectAsStateWithLifecycle()
    val syncing by viewModel.syncing.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    var filter by remember { mutableStateOf(FeedFilter.ALL) }
    var showSettings by remember { mutableStateOf(false) }
    var showArtists by remember { mutableStateOf(false) }
    val uri = LocalUriHandler.current
    val now = remember(feed) { System.currentTimeMillis() }

    // Items stay highlighted as "new" while you're here; they're marked read when you leave.
    val unreadAtOpen = remember { mutableStateOf<Set<String>?>(null) }
    LaunchedEffect(feed.isNotEmpty()) {
        if (unreadAtOpen.value == null && feed.isNotEmpty()) unreadAtOpen.value = feed.filter { !it.read }.map { it.id }.toSet()
    }
    DisposableEffect(Unit) { onDispose { viewModel.markAllRead() } }

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        viewModel.setNotify(granted)
    }
    fun enableNotifications() {
        if (Build.VERSION.SDK_INT >= 33) permission.launch(Manifest.permission.POST_NOTIFICATIONS)
        else viewModel.setNotify(true)
    }

    val shown = feed.filter {
        when (filter) {
            FeedFilter.ALL -> true
            FeedFilter.RELEASES -> it.kind == FeedKind.RELEASE.name
            FeedFilter.NEWS -> it.kind == FeedKind.NEWS.name
        }
    }

    Scaffold(
        containerColor = BackgroundInk,
        topBar = {
            ModernTopBar(
                title = "Artist news",
                onBack = onBack,
                subtitle = if (followed.isEmpty()) "Following artists from your library"
                else "Following ${followed.count { it.enabled }} of ${followed.size} artists",
                actions = {
                    if (syncing) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp).padding(end = 0.dp), strokeWidth = 2.dp, color = HifiBrass)
                        Spacer(Modifier.width(12.dp))
                    } else {
                        IconButton(onClick = { viewModel.refresh() }) { Icon(Icons.Filled.Refresh, "Check now", tint = TextPrimary) }
                    }
                    IconButton(onClick = { showSettings = !showSettings }) {
                        Icon(Icons.Filled.Tune, "Notification settings", tint = if (showSettings) HifiBrass else TextSecondary)
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (showSettings) {
                item {
                    AudioPanel {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            SettingSwitch("Notify me", "Alerts for new releases and news", settings.notify) { on ->
                                if (on) enableNotifications() else viewModel.setNotify(false)
                            }
                            SettingSwitch("Check every 15 minutes", "Finds news sooner. Off checks twice a day and saves battery.", settings.fast, onChange = viewModel::setFast)
                            SettingSwitch("New releases", "Albums, singles and EPs (MusicBrainz)", settings.releases, onChange = viewModel::setReleases)
                            SettingSwitch("News", "Music headlines (GDELT)", settings.news, onChange = viewModel::setNews)
                            Row(
                                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { showArtists = !showArtists }.padding(vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("Manage followed artists", style = MaterialTheme.typography.bodyLarge, color = TextPrimary, modifier = Modifier.weight(1f))
                                Text(if (showArtists) "Hide" else "Show", style = MaterialTheme.typography.labelLarge, color = SignalBlue)
                            }
                            if (showArtists) {
                                followed.forEach { a ->
                                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                        Text(a.name, style = MaterialTheme.typography.bodyMedium, color = if (a.enabled) TextPrimary else TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                                        Switch(checked = a.enabled, onCheckedChange = { viewModel.setArtistEnabled(a.name, it) }, colors = switchColors())
                                    }
                                }
                            }
                        }
                    }
                }
            }
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(FeedFilter.entries) { f ->
                        val selected = filter == f
                        FilterChip(
                            selected = selected, onClick = { filter = f },
                            label = { Text(f.label, style = MaterialTheme.typography.labelLarge) },
                            shape = RoundedCornerShape(50),
                            colors = FilterChipDefaults.filterChipColors(
                                containerColor = SurfacePanel, labelColor = TextSecondary,
                                selectedContainerColor = HifiBrass.copy(alpha = 0.2f), selectedLabelColor = HifiBrass
                            ),
                            border = FilterChipDefaults.filterChipBorder(
                                enabled = true, selected = selected,
                                borderColor = Color.Transparent, selectedBorderColor = Color.Transparent
                            )
                        )
                    }
                }
            }
            if (shown.isEmpty()) {
                item { EmptyFeed(syncing, followed.isEmpty(), onCheck = { viewModel.refresh() }) }
            } else {
                items(shown, key = { it.id }) { item ->
                    FeedCard(item, now, isNew = unreadAtOpen.value?.contains(item.id) == true) {
                        item.url?.let { runCatching { uri.openUri(it) } }
                    }
                }
            }
            item {
                Text(
                    "Releases from MusicBrainz and headlines from GDELT — free public sources, checked about every 12 hours. " +
                        "Headline matching is by artist name, so very common names can occasionally mismatch.",
                    style = MaterialTheme.typography.labelSmall, color = TextSecondary,
                    modifier = Modifier.padding(top = 8.dp, start = 4.dp, end = 4.dp)
                )
            }
        }
    }
}

@Composable
private fun switchColors() = SwitchDefaults.colors(
    checkedThumbColor = Color.White, checkedTrackColor = HifiBrass,
    uncheckedThumbColor = TextSecondary, uncheckedTrackColor = Color.White.copy(alpha = 0.1f)
)

@Composable
private fun SettingSwitch(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = TextPrimary)
            Text(subtitle, style = MaterialTheme.typography.labelMedium, color = TextSecondary)
        }
        Switch(checked = checked, onCheckedChange = onChange, colors = switchColors())
    }
}

@Composable
private fun FeedCard(item: FeedItemEntity, now: Long, isNew: Boolean, onClick: () -> Unit) {
    val isRelease = item.kind == FeedKind.RELEASE.name
    val tint = if (isRelease) HifiBrass else SignalTeal
    Row(
        Modifier
            .fillMaxWidth()
            .audioPanel(RoundedCornerShape(18.dp))
            .clickable(enabled = item.url != null, onClick = onClick)
            .padding(14.dp),
        verticalAlignment = Alignment.Top
    ) {
        Box(
            Modifier.size(44.dp).clip(RoundedCornerShape(14.dp)).background(tint.copy(alpha = 0.16f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(if (isRelease) Icons.Filled.Album else Icons.Filled.Article, null, tint = tint)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(item.artist, style = MaterialTheme.typography.labelLarge, color = tint, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                if (isNew) {
                    Spacer(Modifier.width(8.dp))
                    Box(Modifier.size(7.dp).clip(CircleShape).background(HifiBrass))
                }
            }
            Spacer(Modifier.height(2.dp))
            Text(item.title, style = MaterialTheme.typography.titleSmall, color = TextPrimary, maxLines = 3, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(4.dp))
            val meta = if (isRelease) NewsLogic.releaseLabel(now, item.publishedAtMs)
            else listOfNotNull(NewsLogic.sourceOf(item.url), NewsLogic.relativeTime(now, item.publishedAtMs)).joinToString(" · ")
            Text(meta, style = MaterialTheme.typography.labelMedium, color = TextSecondary)
        }
        if (item.url != null) {
            Icon(Icons.AutoMirrored.Filled.OpenInNew, "Open", tint = TextSecondary, modifier = Modifier.size(18.dp).padding(top = 2.dp))
        }
    }
}

@Composable
private fun EmptyFeed(syncing: Boolean, noArtists: Boolean, onCheck: () -> Unit) {
    AudioPanel(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(24.dp).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                when {
                    syncing -> "Checking your artists…"
                    noArtists -> "No artists yet"
                    else -> "Nothing new right now"
                },
                style = MaterialTheme.typography.titleSmall, color = TextPrimary
            )
            Text(
                if (noArtists) "Scan your library first — artists are picked up from your music's tags."
                else "New releases and headlines from your artists appear here. Pull a check any time with the refresh button.",
                style = MaterialTheme.typography.bodyMedium, color = TextSecondary,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    }
}
