package com.windstrom5.rebit.presentation.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.windstrom5.rebit.presentation.theme.SignalBlue
import com.windstrom5.rebit.presentation.theme.SurfacePanelHigh
import com.windstrom5.rebit.presentation.theme.TextPrimary
import com.windstrom5.rebit.presentation.theme.TextSecondary
import com.windstrom5.rebit.presentation.viewmodel.PlayerViewModel
import org.koin.androidx.compose.koinViewModel

/** Floating now-playing bar shown above the navigation bar on Home and Library. */
@Composable
fun MiniPlayerBar(
    onOpenPlayer: () -> Unit,
    modifier: Modifier = Modifier,
    playerViewModel: PlayerViewModel = koinViewModel()
) {
    val state by playerViewModel.uiState.collectAsStateWithLifecycle()
    val track = state.playback.currentTrack ?: return
    val duration = track.durationMs.coerceAtLeast(1L)

    Surface(
        onClick = onOpenPlayer,
        modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        shape = RoundedCornerShape(22.dp),
        color = SurfacePanelHigh,
        shadowElevation = 8.dp
    ) {
        Column {
            Row(
                Modifier.fillMaxWidth().padding(start = 10.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TrackArtwork(
                    uriString = track.filePath,
                    title = track.title,
                    artist = track.artist,
                    isPlaying = state.playback.isPlaying,
                    isLarge = false,
                    cornerRadius = 14.dp,
                    modifier = Modifier.size(46.dp)
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        track.title,
                        style = MaterialTheme.typography.titleSmall,
                        color = TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        track.artist ?: "Unknown artist",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                IconButton(
                    onClick = { if (state.playback.isPlaying) playerViewModel.pause() else playerViewModel.resume() }
                ) {
                    Icon(
                        if (state.playback.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        if (state.playback.isPlaying) "Pause" else "Play",
                        tint = SignalBlue,
                        modifier = Modifier.size(28.dp)
                    )
                }
                IconButton(onClick = { playerViewModel.nextTrack() }) {
                    Icon(Icons.Filled.SkipNext, "Next", tint = TextPrimary, modifier = Modifier.size(26.dp))
                }
            }
            LinearProgressIndicator(
                progress = { (state.playback.positionMs.toFloat() / duration).coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().height(2.dp),
                color = SignalBlue,
                trackColor = Color.Transparent
            )
        }
    }
}
