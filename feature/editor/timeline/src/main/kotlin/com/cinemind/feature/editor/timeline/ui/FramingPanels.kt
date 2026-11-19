package com.cinemind.feature.editor.timeline.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.RotateLeft
import androidx.compose.material.icons.automirrored.filled.RotateRight
import androidx.compose.material.icons.filled.Flip
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
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.cinemind.core.domain.editing.ClipFraming
import com.cinemind.core.domain.editing.CropRect
import com.cinemind.core.domain.editing.MotionLook
import com.cinemind.core.domain.editing.MotionPreset
import com.cinemind.core.domain.editing.SpeedMath
import com.cinemind.core.domain.editing.TransformMath
import com.cinemind.core.domain.model.Clip
import com.cinemind.core.ui.components.CineMindSheet
import com.cinemind.core.ui.components.SettingRow
import com.cinemind.core.ui.format.formatShortTime
import com.cinemind.core.ui.theme.CineMind
import com.cinemind.core.ui.theme.Radius
import com.cinemind.core.ui.theme.Spacing
import java.util.Locale
import kotlin.math.abs

/**
 * Crop & move for one clip: crop (aspect presets or edges), fill the frame, rotate 90°, flip,
 * straighten, position / zoom / opacity, and keyframes (animate between looks at different
 * moments, or pick a ready-made move). Each change is one undo step and shows in the preview.
 */
@Composable
internal fun FramingSheet(
    target: TimelineViewModel.FramingTarget,
    playheadOffsetUs: Long,
    onSeek: (offsetUs: Long, exact: Boolean) -> Unit,
    onFraming: (ClipFraming) -> Unit,
    onDismiss: () -> Unit,
) {
    val clip = target.clip
    val framing = remember(clip.framing) { ClipFraming.decode(clip.framing) }
    var tab by remember { mutableIntStateOf(0) }
    CineMindSheet(onDismiss = onDismiss, title = "Crop & move") {
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
            FramePill("Crop", tab == 0) { tab = 0 }
            FramePill("Rotate", tab == 1) { tab = 1 }
            FramePill("Position & motion", tab == 2) { tab = 2 }
        }
        Spacer(Modifier.height(Spacing.m))
        when (tab) {
            0 -> CropTab(target, framing, onFraming)
            1 -> RotateTab(clip, framing, playheadOffsetUs, onFraming)
            else -> MotionTab(clip, framing, playheadOffsetUs, onSeek, onFraming)
        }
        if (!framing.isNone) {
            TextButton(onClick = { onFraming(ClipFraming(volume = framing.volume, muted = framing.muted)) }) { Text("Reset to as filmed") }
        }
    }
}

@Composable
private fun CropTab(target: TimelineViewModel.FramingTarget, framing: ClipFraming, onFraming: (ClipFraming) -> Unit) {
    val c = CineMind.colors
    val crop = framing.crop
    // Aspect presets apply to the picture as it is shown (after quarter turns).
    val turned = framing.quarterTurns % 2 == 1
    val sw = if (turned) target.sourceHeight else target.sourceWidth
    val sh = if (turned) target.sourceWidth else target.sourceHeight
    fun forAspect(w: Int, h: Int): CropRect {
        val r = CropRect.forAspect(sw, sh, w, h)
        return if (turned) CropRect(r.top, r.left, r.bottom, r.right) else r
    }
    Text("Aspect", style = MaterialTheme.typography.labelLarge, color = c.textSecondary)
    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xs), modifier = Modifier.horizontalScroll(rememberScrollState())) {
        FramePill("Original", crop.isNone) { onFraming(framing.copy(crop = CropRect())) }
        FramePill("Project frame", false) { onFraming(framing.copy(crop = forAspect(target.frameWidth, target.frameHeight))) }
        listOf(9 to 16, 16 to 9, 1 to 1, 4 to 5, 4 to 3, 21 to 9).forEach { (w, h) ->
            FramePill("$w:$h", false) { onFraming(framing.copy(crop = forAspect(w, h))) }
        }
    }
    Spacer(Modifier.height(Spacing.s))
    EdgeSlider("Left", crop.left) { onFraming(framing.copy(crop = crop.copy(left = it).clamped())) }
    EdgeSlider("Right", crop.right) { onFraming(framing.copy(crop = crop.copy(right = it).clamped())) }
    EdgeSlider("Top", crop.top) { onFraming(framing.copy(crop = crop.copy(top = it).clamped())) }
    EdgeSlider("Bottom", crop.bottom) { onFraming(framing.copy(crop = crop.copy(bottom = it).clamped())) }
    SettingRow("Fill the frame", subtitle = "Zoom so there are no bars (edges are cut off). Off: whole picture with bars.") {
        Switch(checked = framing.fill, onCheckedChange = { onFraming(framing.copy(fill = it)) })
    }
}

@Composable
private fun EdgeSlider(label: String, value: Float, onDone: (Float) -> Unit) {
    val c = CineMind.colors
    var v by remember(value) { mutableFloatStateOf(value) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = c.textSecondary, modifier = Modifier.width(56.dp))
        Slider(
            value = v,
            onValueChange = { v = it },
            onValueChangeFinished = { onDone(v) },
            valueRange = 0f..0.45f,
            colors = sliderColors(),
            modifier = Modifier.weight(1f).semantics { contentDescription = "Crop $label" },
        )
        Text("${(v * 100).toInt()}%", style = MaterialTheme.typography.labelSmall, color = c.textTertiary, modifier = Modifier.width(40.dp))
    }
}

@Composable
private fun RotateTab(clip: Clip, framing: ClipFraming, playheadOffsetUs: Long, onFraming: (ClipFraming) -> Unit) {
    val c = CineMind.colors
    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s), modifier = Modifier.horizontalScroll(rememberScrollState())) {
        RoundTool(Icons.AutoMirrored.Filled.RotateLeft, "Rotate left") { onFraming(framing.copy(quarterTurns = (framing.quarterTurns + 3) % 4)) }
        RoundTool(Icons.AutoMirrored.Filled.RotateRight, "Rotate right") { onFraming(framing.copy(quarterTurns = (framing.quarterTurns + 1) % 4)) }
        RoundTool(Icons.Default.Flip, "Mirror", selected = framing.flipH) { onFraming(framing.copy(flipH = !framing.flipH)) }
        RoundTool(Icons.Default.Flip, "Flip upside down", selected = framing.flipV, iconRotation = 90f) { onFraming(framing.copy(flipV = !framing.flipV)) }
    }
    Spacer(Modifier.height(Spacing.m))
    val look = TransformMath.lookAtTimeline(clip, framing, playheadOffsetUs)
    val animated = framing.hasMotion
    var angle by remember(look.rotation) { mutableFloatStateOf(look.rotation) }
    Text("Straighten: ${String.format(Locale.ROOT, "%.1f°", angle)}", style = MaterialTheme.typography.labelLarge, color = c.textSecondary)
    Slider(
        value = angle,
        onValueChange = { angle = if (abs(it) < 0.4f) 0f else it },
        onValueChangeFinished = {
            // Straightening also zooms in just enough to hide the corners.
            val zoom = if (look.scale <= 1.001f) straightenZoom(angle) else look.scale
            onFraming(TransformMath.setLook(framing, sourceAt(clip, playheadOffsetUs), look.copy(rotation = angle, scale = zoom), animate = animated))
        },
        valueRange = -45f..45f,
        colors = sliderColors(),
        modifier = Modifier.semantics { contentDescription = "Straighten angle" },
    )
    Text(
        if (animated) "This clip has motion keyframes: the angle is set at the playhead." else "Rotate 90° and flips change the picture itself; straighten tilts it in the frame.",
        style = MaterialTheme.typography.bodySmall,
        color = c.textTertiary,
    )
}

/** Zoom that hides the corners of a picture tilted by [deg] (for a portrait/landscape frame, approximate). */
private fun straightenZoom(deg: Float): Float {
    val r = Math.toRadians(abs(deg).toDouble())
    return (kotlin.math.cos(r) + kotlin.math.sin(r)).toFloat().coerceAtLeast(1f).let { if (deg == 0f) 1f else it }
}

private fun sourceAt(clip: Clip, offsetUs: Long) = clip.sourceInUs + SpeedMath.timelineToSource(clip, offsetUs)

@Composable
private fun MotionTab(
    clip: Clip,
    framing: ClipFraming,
    playheadOffsetUs: Long,
    onSeek: (Long, Boolean) -> Unit,
    onFraming: (ClipFraming) -> Unit,
) {
    val c = CineMind.colors
    val length = (clip.timelineOutUs - clip.timelineInUs).coerceAtLeast(1)
    var here by remember(clip.id) { mutableLongStateOf(playheadOffsetUs) }
    var animate by remember(clip.id) { mutableStateOf(framing.keys.size >= 2) }
    val sourceHere = sourceAt(clip, here)
    val look = TransformMath.lookAtTimeline(clip, framing, here)
    val keyHere = framing.keys.any { abs(it.sourceUs - sourceHere) <= 100_000 }
    val set = { l: MotionLook -> onFraming(TransformMath.setLook(framing, sourceHere, l, animate)) }

    Text("Ready-made moves", style = MaterialTheme.typography.labelLarge, color = c.textSecondary)
    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xs), modifier = Modifier.horizontalScroll(rememberScrollState())) {
        MotionPreset.entries.forEach { p ->
            FramePill(p.label, false) {
                animate = true
                onFraming(framing.copy(keys = p.keysFor(clip)))
            }
        }
    }
    Spacer(Modifier.height(Spacing.s))
    SettingRow("Animate with keyframes", subtitle = if (animate) "Set a look at different moments; it moves between them" else "Off: one position for the whole clip") {
        Switch(checked = animate, onCheckedChange = { on ->
            animate = on
            if (!on && framing.keys.size >= 2) onFraming(framing.copy(keys = listOf(framing.keys.first().copy(sourceUs = sourceHere).let { k -> k.copy(x = look.x, y = look.y, scale = look.scale, rotation = look.rotation, opacity = look.opacity) })))
        })
    }
    if (animate) {
        KeyframeBar(clip, framing, here, length) { off, exact ->
            here = off
            onSeek(off, exact)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                (if (keyHere) "Keyframe at " else "Playhead at ") + formatShortTime(here),
                style = MaterialTheme.typography.bodySmall,
                color = if (keyHere) c.accent else c.textSecondary,
                modifier = Modifier.weight(1f),
            )
            if (keyHere) {
                TextButton(onClick = { onFraming(TransformMath.removeKeyNear(framing, sourceHere)) }) { Text("Remove keyframe", color = c.danger) }
            } else {
                TextButton(onClick = { set(look) }) { Text("Add keyframe") }
            }
        }
    }
    LookSlider("Left ↔ right", look.x, -1f..1f, "%.2f") { set(look.copy(x = it)) }
    LookSlider("Up ↕ down", look.y, -1f..1f, "%.2f") { set(look.copy(y = it)) }
    LookSlider("Zoom", look.scale, ClipFraming.MIN_SCALE..3f, "%.2f×") { set(look.copy(scale = it)) }
    LookSlider("Rotation", look.rotation, -180f..180f, "%.0f°") { set(look.copy(rotation = it)) }
    LookSlider("Opacity", look.opacity, 0f..1f, "%.2f") { set(look.copy(opacity = it)) }
    Text(
        "Changes apply at the playhead" + (if (animate) " (a keyframe is added there)." else " to the whole clip."),
        style = MaterialTheme.typography.bodySmall,
        color = c.textTertiary,
    )
}

/** The clip's length with keyframe marks; drag to move the playhead within the clip. */
@Composable
private fun KeyframeBar(clip: Clip, framing: ClipFraming, here: Long, length: Long, onSeek: (Long, Boolean) -> Unit) {
    val c = CineMind.colors
    var v by remember(clip.id) { mutableFloatStateOf(here.toFloat() / length) }
    Column {
        Canvas(Modifier.fillMaxWidth().height(14.dp).padding(horizontal = 10.dp)) {
            drawLine(c.hairline, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), strokeWidth = 2f)
            framing.keys.forEach { k ->
                val off = SpeedMath.sourceToTimeline(clip, (k.sourceUs - clip.sourceInUs).coerceAtLeast(0))
                val x = (off.toFloat() / length).coerceIn(0f, 1f) * size.width
                drawCircle(c.accent, radius = 9f, center = Offset(x, size.height / 2))
            }
        }
        Slider(
            value = v,
            onValueChange = {
                v = it
                onSeek((it * length).toLong(), false)
            },
            onValueChangeFinished = { onSeek((v * length).toLong(), true) },
            colors = sliderColors(),
            modifier = Modifier.semantics { contentDescription = "Position in clip" },
        )
    }
}

@Composable
private fun LookSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, fmt: String, onDone: (Float) -> Unit) {
    val c = CineMind.colors
    var v by remember(value) { mutableFloatStateOf(value.coerceIn(range.start, range.endInclusive)) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = c.textSecondary, modifier = Modifier.width(92.dp))
        Slider(
            value = v,
            onValueChange = { v = it },
            onValueChangeFinished = { onDone(v) },
            valueRange = range,
            colors = sliderColors(),
            modifier = Modifier.weight(1f).semantics { contentDescription = label },
        )
        Text(String.format(Locale.ROOT, fmt, v), style = MaterialTheme.typography.labelSmall, color = c.textTertiary, modifier = Modifier.width(48.dp))
    }
}

@Composable
private fun RoundTool(icon: ImageVector, label: String, selected: Boolean = false, iconRotation: Float = 0f, onClick: () -> Unit) {
    val c = CineMind.colors
    val shape = RoundedCornerShape(Radius.m)
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(shape)
            .background(if (selected) c.accentContainer else c.surfaceSunken)
            .border(1.dp, if (selected) c.accent else c.hairline, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = Spacing.m, vertical = Spacing.s),
    ) {
        Icon(icon, contentDescription = null, tint = if (selected) c.onAccentContainer else c.textPrimary, modifier = Modifier.rotate(iconRotation))
        Text(label, style = MaterialTheme.typography.labelSmall, color = if (selected) c.onAccentContainer else c.textSecondary)
    }
}

@Composable
private fun FramePill(text: String, selected: Boolean, onClick: () -> Unit) {
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

@Composable
private fun sliderColors() = CineMind.colors.let { c ->
    SliderDefaults.colors(thumbColor = c.selection, activeTrackColor = c.selection, inactiveTrackColor = c.surfaceSunken)
}
