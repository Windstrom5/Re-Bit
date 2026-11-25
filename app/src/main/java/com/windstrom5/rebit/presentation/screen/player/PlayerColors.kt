package com.windstrom5.rebit.presentation.screen.player

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import com.windstrom5.rebit.presentation.components.ArtworkPalette
import com.windstrom5.rebit.presentation.theme.TextOnAccent

/** Colours derived from the current artwork, tuned so text and icons stay legible on the dark UI. */
@Immutable
internal data class PlayerColors(
    val accent: Color,
    val onAccent: Color,
    val glow: Color,
    val deep: Color
)

@Composable
internal fun rememberPlayerColors(palette: ArtworkPalette): PlayerColors {
    val accent by animateColorAsState(readableAccent(palette.vibrantColor), tween(700), label = "accent")
    val glow by animateColorAsState(palette.dominantColor, tween(900), label = "glow")
    val deep by animateColorAsState(palette.darkVibrantColor, tween(900), label = "deep")
    return PlayerColors(
        accent = accent,
        onAccent = if (accent.luminance() > 0.5f) TextOnAccent else Color.White,
        glow = glow,
        deep = deep
    )
}

/** Dark album colours make a poor accent on a near-black UI; lift them until they read. */
private fun readableAccent(c: Color): Color =
    if (c.luminance() < 0.30f) lerp(c, Color.White, 0.45f) else c

internal fun formatTime(ms: Long): String {
    val totalSeconds = ms.coerceAtLeast(0L) / 1000
    val h = totalSeconds / 3600
    val m = (totalSeconds % 3600) / 60
    val s = totalSeconds % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
