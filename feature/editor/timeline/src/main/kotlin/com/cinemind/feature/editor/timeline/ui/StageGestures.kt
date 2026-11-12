package com.cinemind.feature.editor.timeline.ui

import android.graphics.RectF
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateRotation
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.cinemind.core.domain.text.TextLayer
import com.cinemind.core.ui.theme.CineMind
import com.cinemind.feature.editor.timeline.preview.PreviewClip
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/** Where an overlay sits in the frame (fractions of the frame; rotation clockwise in degrees). */
internal data class OverlayBox(val id: String, val x: Float, val y: Float, val widthFrac: Float, val aspect: Float, val rotation: Float, val opacity: Float) {
    companion object {
        fun of(item: PreviewClip): OverlayBox {
            val a = item.asset
            val aspect = if (a != null && a.width > 0 && a.height > 0) a.height.toFloat() / a.width else 1f
            return OverlayBox(item.clip.id, item.clip.transformX, item.clip.transformY, item.clip.scale, aspect, item.clip.rotation, item.clip.opacity)
        }
    }

    /** Corners in pixels of a [w]×[h] frame. */
    fun corners(w: Float, h: Float): List<Offset> {
        val bw = widthFrac * w
        val bh = bw * aspect
        val cx = x * w
        val cy = y * h
        val r = Math.toRadians(rotation.toDouble())
        val cs = cos(r).toFloat()
        val sn = sin(r).toFloat()
        return listOf(-bw / 2 to -bh / 2, bw / 2 to -bh / 2, bw / 2 to bh / 2, -bw / 2 to bh / 2).map { (dx, dy) ->
            Offset(cx + dx * cs - dy * sn, cy + dx * sn + dy * cs)
        }
    }

    fun contains(p: Offset, w: Float, h: Float, slop: Float): Boolean {
        // Rotate the point into the box's frame.
        val r = Math.toRadians(-rotation.toDouble())
        val dx = p.x - x * w
        val dy = p.y - y * h
        val lx = dx * cos(r).toFloat() - dy * sin(r).toFloat()
        val ly = dx * sin(r).toFloat() + dy * cos(r).toFloat()
        val bw = widthFrac * w
        return abs(lx) <= bw / 2 + slop && abs(ly) <= bw * aspect / 2 + slop
    }
}

/**
 * Touch layer over the preview frame (on top of everything drawn there):
 * - tap a title/sticker or a picture-in-picture overlay to select it; tap empty video to play/pause;
 * - drag the selected item to move it, pinch to resize, twist to rotate. Titles update live;
 *   overlays show a live outline and are applied (one undo step) when the fingers lift.
 * Only active while paused, so playback taps still play/pause.
 */
@Composable
internal fun StageGestures(
    enabled: Boolean,
    texts: List<TextLayer>,
    textBounds: Map<String, RectF>,
    overlays: List<PreviewClip>,
    selectedTextId: String?,
    selectedOverlayId: String?,
    onSelectText: (String?) -> Unit,
    onSelectOverlay: (String?) -> Unit,
    onEmptyTap: () -> Unit,
    onLiveText: (TextLayer?) -> Unit,
    onTextDone: (TextLayer) -> Unit,
    onOverlayDone: (x: Float, y: Float, scale: Float, rotation: Float, opacity: Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = CineMind.colors
    var liveBox by remember { mutableStateOf<OverlayBox?>(null) }
    val boxes = overlays.map { OverlayBox.of(it) }
    val currentTexts by rememberUpdatedState(texts)
    val currentBounds by rememberUpdatedState(textBounds)
    val currentBoxes by rememberUpdatedState(boxes)
    val selText by rememberUpdatedState(selectedTextId)
    val selOverlay by rememberUpdatedState(selectedOverlayId)
    val selectText by rememberUpdatedState(onSelectText)
    val selectOverlay by rememberUpdatedState(onSelectOverlay)
    val emptyTap by rememberUpdatedState(onEmptyTap)
    val live by rememberUpdatedState(onLiveText)
    val textDone by rememberUpdatedState(onTextDone)
    val overlayDone by rememberUpdatedState(onOverlayDone)
    val active by rememberUpdatedState(enabled)

    Canvas(
        modifier
            .semantics { contentDescription = "Preview. Tap a title or overlay to select it; drag, pinch or twist to move, resize or rotate it." }
            .pointerInput(Unit) {
                detectTapGestures { pos ->
                    if (!active) { emptyTap(); return@detectTapGestures }
                    val w = size.width.toFloat()
                    val h = size.height.toFloat()
                    val text = currentBounds.entries.lastOrNull { (_, r) -> r.contains(pos.x, pos.y) }?.key
                    val overlay = if (text == null) currentBoxes.lastOrNull { it.contains(pos, w, h, 8.dp.toPx()) }?.id else null
                    when {
                        text != null -> selectText(text)
                        overlay != null -> selectOverlay(overlay)
                        selText != null -> selectText(null)
                        selOverlay != null -> selectOverlay(null)
                        else -> emptyTap()
                    }
                }
            }
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    if (!active) return@awaitEachGesture
                    val w = size.width.toFloat().coerceAtLeast(1f)
                    val h = size.height.toFloat().coerceAtLeast(1f)
                    val slop = 28.dp.toPx()
                    val textId = selText
                    val overlayId = selOverlay
                    var text = textId?.let { id -> currentTexts.firstOrNull { it.id == id } }
                    var box = overlayId?.let { id -> currentBoxes.firstOrNull { it.id == id } }
                    val r = textId?.let { currentBounds[it] }
                    val onText = text != null && r != null && RectF(r.left - slop, r.top - slop, r.right + slop, r.bottom + slop).contains(down.position.x, down.position.y)
                    val onBox = !onText && box != null && box.contains(down.position, w, h, slop)
                    if (!onText && !onBox) return@awaitEachGesture
                    var moved = false
                    do {
                        val event = awaitPointerEvent()
                        val pan = event.calculatePan()
                        val zoom = event.calculateZoom()
                        val rot = event.calculateRotation()
                        if (pan != Offset.Zero || zoom != 1f || rot != 0f) {
                            moved = true
                            if (onText) {
                                text = text!!.copy(
                                    x = (text!!.x + pan.x / w).coerceIn(0f, 1f),
                                    y = (text!!.y + pan.y / h).coerceIn(0f, 1f),
                                    size = (text!!.size * zoom).coerceIn(0.015f, 0.6f),
                                    rotation = snap(text!!.rotation + rot),
                                )
                                live(text)
                            } else {
                                box = box!!.copy(
                                    x = (box!!.x + pan.x / w).coerceIn(0f, 1f),
                                    y = (box!!.y + pan.y / h).coerceIn(0f, 1f),
                                    widthFrac = (box!!.widthFrac * zoom).coerceIn(0.05f, 1f),
                                    rotation = snap(box!!.rotation + rot),
                                )
                                liveBox = box
                            }
                            event.changes.forEach { it.consume() }
                        }
                    } while (event.changes.any { it.pressed })
                    if (moved) {
                        if (onText) textDone(text!!)
                        else box?.let { b -> overlayDone(b.x, b.y, b.widthFrac, ((b.rotation % 360f) + 360f) % 360f, b.opacity) }
                    }
                    live(null)
                    liveBox = null
                }
            },
    ) {
        // Outline of the selected overlay (live while it is being moved).
        val shown = liveBox ?: boxes.firstOrNull { it.id == selectedOverlayId }
        if (shown != null && enabled) {
            val pts = shown.corners(size.width, size.height)
            val path = Path().apply {
                moveTo(pts[0].x, pts[0].y)
                pts.drop(1).forEach { lineTo(it.x, it.y) }
                close()
            }
            drawPath(path, c.selection, style = Stroke(width = 2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(14f, 8f))))
            pts.forEach { drawCircle(c.selection, radius = 5.dp.toPx(), center = it) }
        }
    }
}

private fun snap(a: Float): Float {
    val wrapped = ((a + 540f) % 360f) - 180f
    return listOf(-90f, 0f, 90f, 180f, -180f).firstOrNull { abs(wrapped - it) < 3f } ?: wrapped
}
