package com.windstrom5.rebit.presentation.screen.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.windstrom5.rebit.domain.audiophile.PathStage
import com.windstrom5.rebit.domain.audiophile.SignalPath
import com.windstrom5.rebit.domain.audiophile.StageState
import com.windstrom5.rebit.presentation.components.LedDot
import com.windstrom5.rebit.presentation.components.PurityRing
import com.windstrom5.rebit.presentation.theme.HifiBrass
import com.windstrom5.rebit.presentation.theme.SignalBlue
import com.windstrom5.rebit.presentation.theme.SignalTeal
import com.windstrom5.rebit.presentation.theme.SurfacePanel
import com.windstrom5.rebit.presentation.theme.TextPrimary
import com.windstrom5.rebit.presentation.theme.TextSecondary

private val WarnRed = Color(0xFFFF6B6B)

/** The whole chain from file to DAC, with a purity score for how untouched the signal is. */
@Composable
internal fun SignalPathDialog(path: SignalPath, onDismiss: () -> Unit, onMoreDetails: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            modifier = Modifier.fillMaxWidth().padding(16.dp).heightIn(max = 640.dp),
            shape = RoundedCornerShape(28.dp),
            color = SurfacePanel
        ) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(20.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    PurityRing(path.purity)
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Signal path", style = MaterialTheme.typography.labelLarge, color = TextSecondary)
                        Text(path.verdict, style = MaterialTheme.typography.headlineSmall, color = TextPrimary)
                        Text(
                            if (path.modified.isEmpty()) "Nothing changes the samples between your file and the DAC."
                            else "${path.modified.size} stage${if (path.modified.size == 1) "" else "s"} change or may change the signal.",
                            style = MaterialTheme.typography.bodySmall, color = TextSecondary
                        )
                    }
                }
                Spacer(Modifier.height(20.dp))
                SignalPathTimeline(path)
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onMoreDetails) { Text("Audio details", color = SignalBlue) }
                    TextButton(onClick = onDismiss) { Text("Close", color = TextSecondary) }
                }
            }
        }
    }
}

/** The file → DAC chain as a timeline of LEDs; shared with the Audio Info dialog. */
@Composable
internal fun SignalPathTimeline(path: SignalPath) {
    path.stages.forEachIndexed { i, stage -> StageRow(stage, isLast = i == path.stages.lastIndex) }
}

@Composable
private fun StageRow(stage: PathStage, isLast: Boolean) {
    val color = when (stage.state) {
        StageState.CLEAN -> SignalTeal
        StageState.MODIFIED -> HifiBrass
        StageState.WARNING -> WarnRed
    }
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
        Column(Modifier.width(24.dp).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.height(5.dp))
            LedDot(color, size = 9.dp)
            if (!isLast) {
                Box(Modifier.weight(1f).width(2.dp).background(Color.White.copy(alpha = 0.12f)))
            }
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f).padding(bottom = if (isLast) 0.dp else 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stage.name, style = MaterialTheme.typography.titleSmall, color = TextPrimary, modifier = Modifier.weight(1f))
                if (stage.penalty > 0) {
                    Text("−${stage.penalty}", style = MaterialTheme.typography.labelMedium, color = color)
                }
            }
            Text(stage.detail, style = MaterialTheme.typography.bodySmall, color = if (stage.state == StageState.CLEAN) TextSecondary else color)
        }
    }
}
