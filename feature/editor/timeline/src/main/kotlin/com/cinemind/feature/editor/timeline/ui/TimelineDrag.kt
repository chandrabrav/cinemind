package com.cinemind.feature.editor.timeline.ui

import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback

/** Which kind of timeline item is being dragged. */
internal enum class DragKind { OVERLAY, VOICE, SFX, TEXT }

/** Live horizontal drag of a timeline block: [px] while dragging, applied by [modifier]. */
internal class TimeDrag(val px: Float, val modifier: Modifier)

/**
 * Long-press, then drag sideways: the block follows the finger ([TimeDrag.px]) and on release
 * [onEnd] gets how far it moved, in timeline time (one undo step in the view model). A short tap
 * still selects the block as before.
 */
@Composable
internal fun rememberTimeDrag(key: Any, pps: Float, onStart: () -> Unit, onEnd: (deltaUs: Long, deltaPx: Float) -> Unit): TimeDrag {
    val haptics = LocalHapticFeedback.current
    var px by remember(key) { mutableFloatStateOf(0f) }
    val start by rememberUpdatedState(onStart)
    val end by rememberUpdatedState(onEnd)
    val scale by rememberUpdatedState(pps)
    val modifier = Modifier.pointerInput(key) {
        detectDragGesturesAfterLongPress(
            onDragStart = {
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                px = 0f
                start()
            },
            onDragEnd = {
                val moved = px
                px = 0f
                if (moved != 0f) end((moved / scale * 1_000_000f).toLong(), moved)
            },
            onDragCancel = { px = 0f },
        ) { change, amount ->
            change.consume()
            px += amount.x
        }
    }
    return TimeDrag(px, modifier)
}
