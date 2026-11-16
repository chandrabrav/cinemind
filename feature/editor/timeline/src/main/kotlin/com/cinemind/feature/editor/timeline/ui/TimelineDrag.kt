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
import kotlin.math.abs

/** Which kind of timeline item is being dragged. */
internal enum class DragKind { OVERLAY, VOICE, SFX, TEXT }

/** Live drag of a timeline block: [px] sideways or [py] up/down while dragging, applied by [modifier]. */
internal class TimeDrag(val px: Float, val modifier: Modifier, val py: Float = 0f)

/** Movement (dp) after the long-press before the direction is decided and locked. */
internal const val AXIS_SLOP_DP = 12f

/** Which way a drag goes once its direction is locked. */
internal enum class DragAxis { NONE, TIME, LAYER }

/** Decides the drag direction from the movement so far ([NONE] until it passes [slopPx]). */
internal fun lockAxis(dx: Float, dy: Float, slopPx: Float, verticalAllowed: Boolean): DragAxis = when {
    abs(dx) <= slopPx && abs(dy) <= slopPx -> DragAxis.NONE
    verticalAllowed && abs(dy) > abs(dx) -> DragAxis.LAYER
    else -> DragAxis.TIME
}

/**
 * Long-press, then drag. The first [AXIS_SLOP_DP] of movement pick the direction and lock it:
 * - sideways: the block follows the finger ([TimeDrag.px]); on release [onEnd] gets how far it
 *   moved in timeline time (one undo step in the view model);
 * - up/down (only when [onVertical] is given): [TimeDrag.py] follows the finger, [onVertical] gets
 *   the live offset and [onVerticalEnd] the final one (front/back layer order).
 * A sideways move never reorders layers and a vertical move never shifts time. A short tap still
 * selects the block as before.
 */
@Composable
internal fun rememberTimeDrag(
    key: Any,
    pps: Float,
    onStart: () -> Unit,
    onVertical: ((dyPx: Float) -> Unit)? = null,
    onVerticalEnd: ((dyPx: Float) -> Unit)? = null,
    onEnd: (deltaUs: Long, deltaPx: Float) -> Unit,
): TimeDrag {
    val haptics = LocalHapticFeedback.current
    var px by remember(key) { mutableFloatStateOf(0f) }
    var py by remember(key) { mutableFloatStateOf(0f) }
    val start by rememberUpdatedState(onStart)
    val end by rememberUpdatedState(onEnd)
    val vertical by rememberUpdatedState(onVertical)
    val verticalEnd by rememberUpdatedState(onVerticalEnd)
    val scale by rememberUpdatedState(pps)
    val modifier = Modifier.pointerInput(key) {
        var axis = DragAxis.NONE
        var rawX = 0f
        var rawY = 0f
        val slop = AXIS_SLOP_DP * density
        fun reset() {
            px = 0f; py = 0f; rawX = 0f; rawY = 0f; axis = DragAxis.NONE
        }
        detectDragGesturesAfterLongPress(
            onDragStart = {
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                reset()
                start()
            },
            onDragEnd = {
                when (axis) {
                    DragAxis.TIME -> { val moved = px; if (moved != 0f) end((moved / scale * 1_000_000f).toLong(), moved) }
                    DragAxis.LAYER -> verticalEnd?.invoke(py)
                    DragAxis.NONE -> Unit
                }
                reset()
            },
            onDragCancel = {
                if (axis == DragAxis.LAYER) verticalEnd?.invoke(0f)
                reset()
            },
        ) { change, amount ->
            change.consume()
            rawX += amount.x
            rawY += amount.y
            if (axis == DragAxis.NONE) axis = lockAxis(rawX, rawY, slop, vertical != null)
            when (axis) {
                DragAxis.TIME -> px = rawX
                DragAxis.LAYER -> { py = rawY; vertical?.invoke(rawY) }
                DragAxis.NONE -> Unit
            }
        }
    }
    return TimeDrag(px, modifier, py)
}
