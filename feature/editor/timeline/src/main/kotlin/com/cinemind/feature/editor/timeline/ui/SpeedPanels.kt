package com.cinemind.feature.editor.timeline.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AcUnit
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.cinemind.core.domain.editing.SpeedEase
import com.cinemind.core.domain.editing.SpeedMath
import com.cinemind.core.domain.editing.SpeedPoint
import com.cinemind.core.domain.editing.SpeedRampPreset
import com.cinemind.core.domain.model.Clip
import com.cinemind.core.ui.components.CineMindSheet
import com.cinemind.core.ui.components.SettingRow
import com.cinemind.core.ui.format.formatShortTime
import com.cinemind.core.ui.theme.CineMind
import com.cinemind.core.ui.theme.Radius
import com.cinemind.core.ui.theme.Spacing
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToLong

private val LOG_MIN = ln(SpeedMath.MIN_SPEED)
private val LOG_MAX = ln(SpeedMath.MAX_SPEED)

/** 0..1 on a log scale, so 0.25× and 4× sit the same distance from 1×. */
private fun speedToUnit(s: Float) = ((ln(s) - LOG_MIN) / (LOG_MAX - LOG_MIN)).coerceIn(0f, 1f)
private fun unitToSpeed(u: Float): Float {
    val raw = kotlin.math.E.toFloat().pow(LOG_MIN + u.coerceIn(0f, 1f) * (LOG_MAX - LOG_MIN))
    // Snap near the common speeds so 1× is easy to hit.
    return SpeedMath.PRESETS.firstOrNull { abs(it - raw) / it < 0.04f } ?: ((raw * 100).roundToLong() / 100f)
}

private fun speedLabel(s: Float) = String.format(Locale.ROOT, if (s < 1f) "%.2f×" else "%.1f×", s).replace(".0×", "×")

/**
 * Speed tool for a video clip: constant speed (0.1×–8×, presets, apply to every clip), speed ramps
 * (ready-made curves and a curve editor whose points stay on the same moment of the footage) and
 * freeze frame. Every change is one undo step; the preview rebuilds with the new timing.
 */
@Composable
internal fun SpeedSheet(
    target: TimelineViewModel.SpeedTarget,
    onConstant: (speed: Float, allClips: Boolean) -> Unit,
    onRamp: (List<SpeedPoint>) -> Unit,
    onFreeze: () -> Unit,
    onDismiss: () -> Unit,
) {
    val c = CineMind.colors
    val clip = target.clip
    val curve = remember(clip) { SpeedMath.decode(clip.speedCurve) }
    var tab by remember(clip.id) { mutableIntStateOf(if (curve.size >= 2) 1 else 0) }
    CineMindSheet(onDismiss = onDismiss, title = "Speed") {
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
            Pill("Constant", tab == 0) { tab = 0 }
            Pill("Speed ramp", tab == 1) { tab = 1 }
        }
        Spacer(Modifier.height(Spacing.m))
        if (tab == 0) ConstantTab(target, onConstant) else RampTab(target, curve, onRamp)

        Spacer(Modifier.height(Spacing.m))
        val smooth = SpeedMath.smoothSlowestSpeed(target.sourceFps, target.projectFps)
        if (SpeedMath.slowest(clip) < smooth - 0.01f) {
            Text(
                "This clip was filmed at ${target.sourceFps.toInt()} fps, so below ${speedLabel(smooth)} frames repeat and motion looks stepped. " +
                    "For smooth slow motion, film at 60 or 120 fps.",
                style = MaterialTheme.typography.bodySmall,
                color = c.warning,
            )
            Spacer(Modifier.height(Spacing.s))
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(Radius.m))
                .background(c.surfaceSunken)
                .clickable(onClick = onFreeze)
                .padding(Spacing.m),
        ) {
            Icon(Icons.Default.AcUnit, contentDescription = null, tint = c.accent)
            Spacer(Modifier.padding(start = Spacing.s))
            Text("Freeze frame at the playhead (holds 2 s)", style = MaterialTheme.typography.bodyMedium, color = c.textPrimary)
        }
        Text(
            "Speed works on the main video track. Sound follows the speed (higher or lower pitch).",
            style = MaterialTheme.typography.bodySmall,
            color = c.textTertiary,
            modifier = Modifier.padding(top = Spacing.s),
        )
    }
}

@Composable
private fun ConstantTab(target: TimelineViewModel.SpeedTarget, onConstant: (Float, Boolean) -> Unit) {
    val c = CineMind.colors
    val clip = target.clip
    val current = if (SpeedMath.decode(clip.speedCurve).size >= 2) 1f else clip.speed
    var speed by remember(clip) { mutableFloatStateOf(current) }
    var all by remember { mutableStateOf(false) }
    val sourceLen = clip.sourceOutUs - clip.sourceInUs
    Text(speedLabel(speed), style = MaterialTheme.typography.headlineMedium, color = c.textPrimary)
    Text(
        "${formatShortTime(sourceLen)} of footage plays in ${formatShortTime((sourceLen / speed).roundToLong())}",
        style = MaterialTheme.typography.bodySmall,
        color = c.textSecondary,
    )
    Slider(
        value = speedToUnit(speed),
        onValueChange = { speed = unitToSpeed(it) },
        onValueChangeFinished = { onConstant(speed, all) },
        colors = SliderDefaults.colors(thumbColor = c.selection, activeTrackColor = c.selection, inactiveTrackColor = c.surfaceSunken),
        modifier = Modifier.semantics { contentDescription = "Clip speed" },
    )
    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xs), modifier = Modifier.horizontalScroll(rememberScrollState())) {
        SpeedMath.PRESETS.forEach { p ->
            Pill(speedLabel(p), abs(speed - p) < 0.001f) {
                speed = p
                onConstant(p, all)
            }
        }
    }
    SettingRow("Apply to every video clip", subtitle = "Off: only this clip") {
        Switch(checked = all, onCheckedChange = {
            all = it
            if (it) onConstant(speed, true)
        })
    }
}

@Composable
private fun RampTab(target: TimelineViewModel.SpeedTarget, saved: List<SpeedPoint>, onRamp: (List<SpeedPoint>) -> Unit) {
    val c = CineMind.colors
    val clip = target.clip
    var points by remember(clip.id) { mutableStateOf(saved) }
    var selected by remember(clip.id) { mutableIntStateOf(-1) }
    val commit = { list: List<SpeedPoint> -> points = list; onRamp(list) }

    Text("Ready-made", style = MaterialTheme.typography.labelLarge, color = c.textSecondary)
    Spacer(Modifier.height(Spacing.xs))
    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xs), modifier = Modifier.horizontalScroll(rememberScrollState())) {
        SpeedRampPreset.entries.forEach { p ->
            Pill(p.label, false) {
                selected = -1
                commit(p.pointsFor(clip))
            }
        }
    }
    Spacer(Modifier.height(Spacing.m))
    if (points.size < 2) {
        Text(
            "Pick a ready-made ramp, or start a curve and drag its points: up is faster, down is slower. Tap the curve to add a point.",
            style = MaterialTheme.typography.bodySmall,
            color = c.textSecondary,
        )
        TextButton(onClick = {
            val p = listOf(SpeedPoint(clip.sourceInUs, clip.speed), SpeedPoint(clip.sourceOutUs, clip.speed))
            selected = -1
            commit(p)
        }) { Text("Start a curve") }
        return
    }
    val inUs = clip.sourceInUs
    val len = (clip.sourceOutUs - clip.sourceInUs).coerceAtLeast(1)
    RampEditor(
        points = points,
        inUs = inUs,
        lengthUs = len,
        selected = selected,
        onSelect = { selected = it },
        onDrag = { points = it },
        onDragEnd = { onRamp(points) },
        onAdd = { list, index -> selected = index; commit(list) },
    )
    val previewClip = clip.copy(speedCurve = SpeedMath.encode(points))
    Text(
        "${formatShortTime(len)} of footage plays in ${formatShortTime(SpeedMath.timelineDurationUs(previewClip))}",
        style = MaterialTheme.typography.bodySmall,
        color = c.textSecondary,
    )
    val sel = points.getOrNull(selected)
    if (sel != null) {
        Spacer(Modifier.height(Spacing.s))
        Text("Point ${selected + 1}: ${speedLabel(sel.speed)} · change to next point", style = MaterialTheme.typography.labelLarge, color = c.textSecondary)
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xs), modifier = Modifier.horizontalScroll(rememberScrollState())) {
            SpeedEase.entries.forEach { e ->
                Pill(e.label, sel.ease == e) { commit(points.toMutableList().also { it[selected] = sel.copy(ease = e) }) }
            }
        }
        if (points.size > 2) {
            TextButton(onClick = {
                val next = points.filterIndexed { i, _ -> i != selected }
                selected = -1
                commit(next)
            }) { Text("Remove this point", color = c.danger) }
        }
    }
    TextButton(onClick = {
        selected = -1
        commit(emptyList())
        points = emptyList()
    }) { Text("Remove ramp (back to normal speed)") }
}

/**
 * The speed curve: x = position in the clip's footage, y = speed on a log scale (1× in the middle
 * line). Drag a point to change it (end points only move up/down); tap empty space to add one.
 */
@Composable
private fun RampEditor(
    points: List<SpeedPoint>,
    inUs: Long,
    lengthUs: Long,
    selected: Int,
    onSelect: (Int) -> Unit,
    onDrag: (List<SpeedPoint>) -> Unit,
    onDragEnd: () -> Unit,
    onAdd: (List<SpeedPoint>, Int) -> Unit,
) {
    val c = CineMind.colors
    val shape = RoundedCornerShape(Radius.m)
    var dragging by remember { mutableIntStateOf(-1) }
    val latest by rememberUpdatedState(points)
    val select by rememberUpdatedState(onSelect)
    val drag by rememberUpdatedState(onDrag)
    val dragEnd by rememberUpdatedState(onDragEnd)
    val add by rememberUpdatedState(onAdd)
    fun xOf(p: SpeedPoint, w: Float) = ((p.sourceUs - inUs).toFloat() / lengthUs).coerceIn(0f, 1f) * w
    fun yOf(s: Float, h: Float) = (1f - speedToUnit(s)) * h
    fun nearest(pos: Offset, w: Float, h: Float): Int {
        val i = latest.indices.minByOrNull { (Offset(xOf(latest[it], w), yOf(latest[it].speed, h)) - pos).getDistance() } ?: return -1
        return if ((Offset(xOf(latest[i], w), yOf(latest[i].speed, h)) - pos).getDistance() < 48f) i else -1
    }
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(180.dp)
            .clip(shape)
            .background(c.surfaceSunken)
            .border(1.dp, c.hairline, shape)
            .semantics { contentDescription = "Speed curve editor" }
            .pointerInput(inUs, lengthUs) {
                detectTapGestures { pos ->
                    val w = size.width.toFloat()
                    val h = size.height.toFloat()
                    val hit = nearest(pos, w, h)
                    if (hit >= 0) {
                        select(hit)
                    } else {
                        val src = inUs + (pos.x / w * lengthUs).roundToLong()
                        val p = SpeedPoint(src, unitToSpeed(1f - pos.y / h))
                        val list = (latest + p).sortedBy { it.sourceUs }
                        add(list, list.indexOf(p))
                    }
                }
            }
            .pointerInput(inUs, lengthUs) {
                detectDragGestures(
                    onDragStart = { pos ->
                        dragging = nearest(pos, size.width.toFloat(), size.height.toFloat())
                        if (dragging >= 0) select(dragging)
                    },
                    onDragEnd = { if (dragging >= 0) dragEnd(); dragging = -1 },
                    onDragCancel = { if (dragging >= 0) dragEnd(); dragging = -1 },
                ) { change, _ ->
                    val i = dragging
                    if (i < 0) return@detectDragGestures
                    change.consume()
                    val w = size.width.toFloat()
                    val h = size.height.toFloat()
                    val list = latest.toMutableList()
                    val p = list[i]
                    val speed = unitToSpeed(1f - change.position.y / h)
                    val src = if (i == 0 || i == list.lastIndex) p.sourceUs else {
                        val lo = list[i - 1].sourceUs + 20_000
                        val hi = list[i + 1].sourceUs - 20_000
                        (inUs + (change.position.x / w * lengthUs).roundToLong()).coerceIn(lo, maxOf(lo, hi))
                    }
                    list[i] = p.copy(sourceUs = src, speed = speed)
                    drag(list)
                }
            },
    ) {
        val w = size.width
        val h = size.height
        // Guides at 0.25×, 0.5×, 1×, 2×, 4×.
        listOf(0.25f, 0.5f, 1f, 2f, 4f).forEach { s ->
            val y = yOf(s, h)
            drawLine(
                if (s == 1f) c.textTertiary else c.hairline,
                Offset(0f, y), Offset(w, y),
                strokeWidth = if (s == 1f) 2f else 1f,
                pathEffect = if (s == 1f) null else PathEffect.dashPathEffect(floatArrayOf(8f, 8f)),
            )
        }
        val path = Path()
        val steps = 96
        for (k in 0..steps) {
            val src = inUs + lengthUs * k / steps
            val x = w * k / steps
            val y = yOf(SpeedMath.speedAt(points, src), h)
            if (k == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, c.accent, style = Stroke(width = 5f))
        points.forEachIndexed { i, p ->
            val center = Offset(xOf(p, w), yOf(p.speed, h))
            drawCircle(if (i == selected) c.selection else c.accent, radius = if (i == selected) 18f else 13f, center = center)
            drawCircle(c.surface, radius = 6f, center = center)
        }
    }
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text("Start", style = MaterialTheme.typography.labelSmall, color = c.textTertiary)
        Text("Up = faster · Down = slower", style = MaterialTheme.typography.labelSmall, color = c.textTertiary)
        Text("End", style = MaterialTheme.typography.labelSmall, color = c.textTertiary)
    }
}

@Composable
private fun Pill(text: String, selected: Boolean, onClick: () -> Unit) {
    val c = CineMind.colors
    val shape = RoundedCornerShape(Radius.xl)
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = if (selected) c.onAccent else c.textPrimary,
        modifier = Modifier
            .clip(shape)
            .background(if (selected) c.accent else c.surfaceSunken)
            .border(1.dp, if (selected) c.accent else c.hairline, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = Spacing.m, vertical = Spacing.xs),
    )
}

/** Small "2×" / "Ramp" badge shown on clips whose speed was changed. */
internal fun speedBadge(clip: Clip): String? = when {
    SpeedMath.decode(clip.speedCurve).size >= 2 -> "Ramp"
    clip.speed != 1f -> speedLabel(clip.speed)
    else -> null
}
