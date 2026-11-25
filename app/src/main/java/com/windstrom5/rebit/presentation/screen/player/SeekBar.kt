package com.windstrom5.rebit.presentation.screen.player

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.unit.dp

/**
 * Thin seek bar: a slim track that thickens and grows a thumb while being dragged.
 * [onDrag] fires continuously with a 0..1 fraction; [onCommit] fires on release or tap.
 */
@Composable
internal fun SeekBar(
    fraction: Float,
    accent: Color,
    onDrag: (Float) -> Unit,
    onCommit: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    var active by remember { mutableStateOf(false) }
    val dragCb by rememberUpdatedState(onDrag)
    val commitCb by rememberUpdatedState(onCommit)
    val trackHeight by animateDpAsState(if (active) 8.dp else 4.dp, tween(150), label = "seek_h")
    val thumbRadius by animateDpAsState(if (active) 9.dp else 0.dp, tween(150), label = "seek_thumb")
    val f = fraction.coerceIn(0f, 1f)

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(32.dp)
            .semantics {
                contentDescription = "Seek"
                progressBarRangeInfo = ProgressBarRangeInfo(f, 0f..1f)
                setProgress { target -> commitCb(target.coerceIn(0f, 1f)); true }
            }
            .pointerInput(Unit) {
                detectTapGestures { o -> commitCb((o.x / size.width).coerceIn(0f, 1f)) }
            }
            .pointerInput(Unit) {
                var last = 0f
                detectHorizontalDragGestures(
                    onDragStart = { o ->
                        active = true
                        last = (o.x / size.width).coerceIn(0f, 1f)
                        dragCb(last)
                    },
                    onDragEnd = { active = false; commitCb(last) },
                    onDragCancel = { active = false },
                    onHorizontalDrag = { change, _ ->
                        change.consume()
                        last = (change.position.x / size.width).coerceIn(0f, 1f)
                        dragCb(last)
                    }
                )
            }
    ) {
        val h = trackHeight.toPx()
        val pad = 10.dp.toPx() // keeps the thumb inside the canvas at both ends
        val w = size.width - pad * 2
        val y = (size.height - h) / 2f
        val radius = CornerRadius(h / 2f)
        drawRoundRect(Color.White.copy(alpha = 0.16f), Offset(pad, y), Size(w, h), radius)
        drawRoundRect(accent, Offset(pad, y), Size((w * f).coerceAtLeast(h), h), radius)
        if (thumbRadius.toPx() > 0.5f) {
            drawCircle(accent, thumbRadius.toPx(), Offset(pad + w * f, size.height / 2f))
        }
    }
}
