package com.windstrom5.rebit.presentation.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColors = darkColorScheme(
    primary = SignalBlue,
    onPrimary = TextOnAccent,
    primaryContainer = SignalBlueDim,
    onPrimaryContainer = TextPrimary,
    secondary = HifiBrass,
    onSecondary = TextOnAccent,
    secondaryContainer = HifiBrassDim,
    onSecondaryContainer = TextPrimary,
    tertiary = SignalTeal,
    onTertiary = TextOnAccent,
    background = BackgroundInk,
    onBackground = TextPrimary,
    surface = SurfaceInk,
    onSurface = TextPrimary,
    surfaceVariant = SurfacePanel,
    onSurfaceVariant = TextSecondary,
    surfaceContainerLowest = BackgroundInk,
    surfaceContainerLow = SurfaceInk,
    surfaceContainer = SurfacePanel,
    surfaceContainerHigh = SurfacePanelHigh,
    surfaceContainerHighest = SurfacePanelHigh,
    outline = OutlineSubtle,
    outlineVariant = OutlineSubtle,
    error = StatusError,
    onError = Color(0xFF1A0603)
)

@Composable
fun BitPerfectUsbTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColors,
        typography = AppTypography,
        shapes = AppShapes,
        content = content
    )
}
