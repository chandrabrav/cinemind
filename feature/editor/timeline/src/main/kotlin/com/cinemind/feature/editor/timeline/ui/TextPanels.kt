package com.cinemind.feature.editor.timeline.ui

import android.graphics.RectF
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateRotation
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cinemind.core.domain.captions.CaptionFont
import com.cinemind.core.domain.text.Stickers
import com.cinemind.core.domain.text.TextAnim
import com.cinemind.core.domain.text.TextLoop
import com.cinemind.core.domain.text.TextKind
import com.cinemind.core.domain.text.TextLayer
import com.cinemind.core.domain.text.TextLayerEditing
import com.cinemind.core.domain.text.TextPreset
import com.cinemind.core.domain.text.TextStyle
import com.cinemind.core.ui.components.CineMindSheet
import com.cinemind.core.ui.components.GradientButton
import com.cinemind.core.ui.components.SettingRow
import com.cinemind.core.ui.format.formatShortTime
import com.cinemind.core.ui.text.TextLayerPainter
import com.cinemind.core.ui.theme.CineMind
import com.cinemind.core.ui.theme.Radius
import com.cinemind.core.ui.theme.Spacing
import kotlin.math.abs

/**
 * Draws titles and stickers over the preview with the same painter as the export. When
 * [interactive], tapping a layer selects it (tapping empty space clears the selection) and the
 * selected layer can be dragged, pinched (size) and twisted (rotation) with fingers; the change is
 * saved as one undo step when the fingers lift.
 */
@Composable
internal fun TextLayersOverlay(
    layers: List<TextLayer>,
    positionUs: Long,
    selectedId: String?,
    modifier: Modifier = Modifier,
    interactive: Boolean = false,
    onSelect: (String?) -> Unit = {},
    onTransform: (TextLayer) -> Unit = {},
    /** Tap on empty video while nothing is selected (the stage's own tap action, e.g. play/pause). */
    onEmptyTap: () -> Unit = {},
    /** False: only draws (and outlines the selection); touches are handled by [StageGestures]. */
    handleGestures: Boolean = true,
    /** Drawn area of each layer, for hit-testing elsewhere. */
    onBounds: (Map<String, RectF>) -> Unit = {},
) {
    if (layers.isEmpty()) return
    val c = CineMind.colors
    val painter = remember { TextLayerPainter() }
    var live by remember { mutableStateOf<TextLayer?>(null) }
    var bounds by remember { mutableStateOf<Map<String, RectF>>(emptyMap()) }
    val shown = live?.let { l -> layers.map { if (it.id == l.id) l else it } } ?: layers
    val frames = TextLayerEditing.framesAt(shown, positionUs)
    val currentLayers by rememberUpdatedState(layers)
    val currentSelected by rememberUpdatedState(selectedId)
    val currentBounds by rememberUpdatedState(bounds)
    val select by rememberUpdatedState(onSelect)
    val transform by rememberUpdatedState(onTransform)
    val emptyTap by rememberUpdatedState(onEmptyTap)

    val gestures = if (!interactive || !handleGestures) Modifier else Modifier
        .pointerInput(Unit) {
            detectTapGestures { pos ->
                val hit = currentBounds.entries.lastOrNull { (_, r) -> r.contains(pos.x, pos.y) }?.key
                if (hit == null && currentSelected == null) emptyTap() else select(hit)
            }
        }
        .pointerInput(Unit) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                val id = currentSelected ?: return@awaitEachGesture
                val r = currentBounds[id] ?: return@awaitEachGesture
                val slop = 24.dp.toPx()
                if (!RectF(r.left - slop, r.top - slop, r.right + slop, r.bottom + slop).contains(down.position.x, down.position.y)) return@awaitEachGesture
                var layer = currentLayers.firstOrNull { it.id == id } ?: return@awaitEachGesture
                var moved = false
                do {
                    val event = awaitPointerEvent()
                    val pan = event.calculatePan()
                    val zoom = event.calculateZoom()
                    val rot = event.calculateRotation()
                    if (pan != Offset.Zero || zoom != 1f || rot != 0f) {
                        moved = true
                        val w = size.width.toFloat().coerceAtLeast(1f)
                        val h = size.height.toFloat().coerceAtLeast(1f)
                        layer = layer.copy(
                            x = (layer.x + pan.x / w).coerceIn(0f, 1f),
                            y = (layer.y + pan.y / h).coerceIn(0f, 1f),
                            size = (layer.size * zoom).coerceIn(0.015f, 0.6f),
                            rotation = snapAngle(layer.rotation + rot),
                        )
                        live = layer
                        event.changes.forEach { it.consume() }
                    }
                } while (event.changes.any { it.pressed })
                if (moved) transform(layer)
                live = null
            }
        }

    Canvas(modifier.then(gestures)) {
        var out: Map<String, RectF> = emptyMap()
        drawIntoCanvas { canvas -> out = painter.drawAll(canvas.nativeCanvas, size.width.toInt(), size.height.toInt(), frames) }
        if (interactive) {
            out[selectedId]?.let { r ->
                drawRect(
                    color = c.selection,
                    topLeft = Offset(r.left, r.top),
                    size = Size(r.width(), r.height()),
                    style = Stroke(width = 2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 8f))),
                )
            }
        }
        if (out != bounds) {
            bounds = out
            onBounds(out)
        }
    }
}

private fun snapAngle(a: Float): Float {
    val wrapped = ((a + 540f) % 360f) - 180f
    return listOf(-90f, 0f, 90f).firstOrNull { abs(wrapped - it) < 3f } ?: wrapped
}

private val SWATCHES = listOf(
    0xFFFFFFFF, 0xFF111111, 0xFFFFD43B, 0xFFFF6B6B, 0xFFFF8FAB, 0xFF7DF9FF, 0xFF4DABF7, 0xFF51CF66, 0xFFB197FC, 0xFFFFA94D, 0xFFFFF4E0,
).map { it.toInt() }

/** Adds a title (with a look) or a sticker at the playhead. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AddTextSheet(onAddText: (String, TextPreset) -> Unit, onAddSticker: (String) -> Unit, onDismiss: () -> Unit, onSuggest: (() -> Unit)? = null) {
    val c = CineMind.colors
    var tab by remember { mutableIntStateOf(0) }
    var text by remember { mutableStateOf("") }
    var preset by remember { mutableStateOf(TextPreset.BOLD_TITLE) }
    CineMindSheet(onDismiss = onDismiss, title = "Text & stickers") {
        if (onSuggest != null) {
            TextButton(onClick = onSuggest) { Text("✨ Suggest titles & stickers for this video") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
            TextPill("Text", tab == 0) { tab = 0 }
            TextPill("Stickers", tab == 1) { tab = 1 }
        }
        Spacer(Modifier.height(Spacing.m))
        if (tab == 0) {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it.take(200) },
                label = { Text("Your text") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 1,
                maxLines = 4,
            )
            Spacer(Modifier.height(Spacing.s))
            Text("Look", style = MaterialTheme.typography.labelLarge, color = c.textSecondary)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.xs), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                TextPreset.entries.forEach { p -> PresetChip(p, p == preset) { preset = p } }
            }
            Spacer(Modifier.height(Spacing.m))
            GradientButton(
                "Add at the playhead",
                onClick = { onAddText(text.ifBlank { "Your title" }, preset) },
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            Stickers.sets.forEach { (name, items) ->
                Text(name, style = MaterialTheme.typography.labelLarge, color = c.textSecondary, modifier = Modifier.padding(top = Spacing.s))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.xs), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    items.forEach { s ->
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(48.dp)
                                .clip(RoundedCornerShape(Radius.m))
                                .background(c.surfaceSunken)
                                .clickable { onAddSticker(s) }
                                .semantics { contentDescription = "Add sticker $s" },
                        ) { Text(s, fontSize = 26.sp) }
                    }
                }
            }
        }
    }
}

/** Edits the selected title or sticker: words, look, colours, size, angle, animation, timing. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun EditTextSheet(
    layer: TextLayer,
    onChange: (TextLayer) -> Unit,
    onDuplicate: () -> Unit,
    onFront: () -> Unit,
    onDelete: () -> Unit,
    onMove: (Long) -> Unit,
    onTrim: (Long, Long) -> Unit,
    onDismiss: () -> Unit,
) {
    val c = CineMind.colors
    val st = layer.styleObj
    val sticker = layer.kindEnum == TextKind.STICKER
    val setStyle = { s: TextStyle -> onChange(layer.copy(style = s.encode())) }
    var tab by remember { mutableIntStateOf(0) }
    CineMindSheet(onDismiss = onDismiss, title = if (sticker) "Sticker" else "Text") {
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s), modifier = Modifier.horizontalScroll(rememberScrollState())) {
            TextPill(if (sticker) "Sticker" else "Words & look", tab == 0) { tab = 0 }
            if (!sticker) TextPill("Colour & effects", tab == 1) { tab = 1 }
            TextPill("Animation", tab == 2) { tab = 2 }
            TextPill("Timing", tab == 3) { tab = 3 }
        }
        Spacer(Modifier.height(Spacing.m))
        Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState())) {
        when (tab) {
            0 -> {
                if (!sticker) {
                    var draft by remember(layer.id) { mutableStateOf(layer.text) }
                    OutlinedTextField(
                        value = draft,
                        onValueChange = { draft = it.take(200) },
                        label = { Text("Text") },
                        modifier = Modifier.fillMaxWidth(),
                        maxLines = 4,
                        trailingIcon = {
                            if (draft != layer.text && draft.isNotBlank()) TextButton(onClick = { onChange(layer.copy(text = draft)) }) { Text("Done") }
                        },
                    )
                    Spacer(Modifier.height(Spacing.s))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.xs), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                        TextPreset.entries.forEach { p ->
                            PresetChip(p, false) { onChange(layer.copy(style = p.style.encode(), size = p.size)) }
                        }
                    }
                    Spacer(Modifier.height(Spacing.s))
                    Text("Font", style = MaterialTheme.typography.labelLarge, color = c.textSecondary)
                    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xs), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                        CaptionFont.entries.forEach { f -> TextPill(f.label, st.font == f, fontFamilyOf(f)) { setStyle(st.copy(font = f)) } }
                    }
                    ValueSlider("Weight", st.weight.toFloat(), 100f..900f, { "${it.toInt()}" }) { setStyle(st.copy(weight = ((it / 100).toInt() * 100).coerceIn(100, 900))) }
                    ValueSlider("Letter spacing", st.spacing, 0f..0.6f, { String.format(java.util.Locale.ROOT, "%.2f", it) }) { setStyle(st.copy(spacing = it)) }
                    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                        TextPill("Left", st.align == -1) { setStyle(st.copy(align = -1)) }
                        TextPill("Centre", st.align == 0) { setStyle(st.copy(align = 0)) }
                        TextPill("Right", st.align == 1) { setStyle(st.copy(align = 1)) }
                        TextPill("ABC", st.uppercase) { setStyle(st.copy(uppercase = !st.uppercase)) }
                    }
                }
                ValueSlider("Size", layer.size, 0.015f..0.4f, { "${(it * 100).toInt()}" }) { onChange(layer.copy(size = it)) }
                ValueSlider("Angle", layer.rotation, -180f..180f, { "${it.toInt()}°" }) { onChange(layer.copy(rotation = it)) }
                Text("Drag it on the preview to move it; pinch to resize; twist to rotate.", style = MaterialTheme.typography.bodySmall, color = c.textTertiary)
            }
            1 -> {
                Text("Text colour", style = MaterialTheme.typography.labelLarge, color = c.textSecondary)
                Swatches(st.color) { setStyle(st.copy(color = it)) }
                ValueSlider("Outline", st.outline, 0f..0.3f, { "${(it * 100).toInt()}" }) { setStyle(st.copy(outline = it)) }
                if (st.outline > 0f) Swatches(st.outlineColor) { setStyle(st.copy(outlineColor = it)) }
                SettingRow("Shadow") { Switch(checked = st.shadow, onCheckedChange = { setStyle(st.copy(shadow = it, glow = if (it) false else st.glow)) }) }
                SettingRow("Glow") { Switch(checked = st.glow, onCheckedChange = { setStyle(st.copy(glow = it, shadow = if (it) false else st.shadow)) }) }
                ValueSlider("Box behind", st.boxOpacity, 0f..1f, { "${(it * 100).toInt()}%" }) { setStyle(st.copy(boxOpacity = it)) }
                if (st.boxOpacity > 0f) Swatches(st.boxColor) { setStyle(st.copy(boxColor = it)) }
                SettingRow("Gradient") { Switch(checked = st.hasGradient, onCheckedChange = { setStyle(st.copy(color2 = if (it) 0xFFFFA94D.toInt() else 0)) }) }
                if (st.hasGradient) Swatches(st.color2) { setStyle(st.copy(color2 = it)) }
                ValueSlider("3D depth", st.extrude, 0f..0.25f, { "${(it * 100).toInt()}" }) { setStyle(st.copy(extrude = it)) }
                if (st.extrude > 0f) Swatches(st.extrudeColor) { setStyle(st.copy(extrudeColor = it)) }
                SettingRow(
                    title = "Behind the person",
                    subtitle = "Text sits behind whoever is in the shot. Needs the clip's cutout (AI → Cut out subject).",
                ) { Switch(checked = st.behindSubject, onCheckedChange = { setStyle(st.copy(behindSubject = it)) }) }
            }
            2 -> {
                Text("Entrance", style = MaterialTheme.typography.labelLarge, color = c.textSecondary)
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xs), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                    TextAnim.entries.filter { !sticker || it != TextAnim.TYPEWRITER }.forEach { a -> TextPill(a.label, st.animIn == a) { setStyle(st.copy(animIn = a)) } }
                }
                Spacer(Modifier.height(Spacing.s))
                Text("Exit", style = MaterialTheme.typography.labelLarge, color = c.textSecondary)
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xs), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                    listOf(TextAnim.NONE, TextAnim.FADE, TextAnim.ZOOM, TextAnim.SLIDE_UP, TextAnim.SLIDE_LEFT).forEach { a ->
                        TextPill(a.label, st.animOut == a) { setStyle(st.copy(animOut = a)) }
                    }
                }
                Spacer(Modifier.height(Spacing.s))
                Text("Keeps moving", style = MaterialTheme.typography.labelLarge, color = c.textSecondary)
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xs), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                    TextLoop.entries.filter { !sticker || it != TextLoop.WAVE }.forEach { l -> TextPill(l.label, st.loop == l) { setStyle(st.copy(loop = l)) } }
                }
            }
            else -> {
                Text("${formatShortTime(layer.startUs)} – ${formatShortTime(layer.endUs)} (${formatShortTime(layer.lengthUs)})", style = MaterialTheme.typography.bodyMedium, color = c.textPrimary)
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xs), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                    TextPill("◀ 0.5 s", false) { onMove(-500_000) }
                    TextPill("0.5 s ▶", false) { onMove(500_000) }
                    TextPill("Shorter", false) { onTrim(0, -500_000) }
                    TextPill("Longer", false) { onTrim(0, 500_000) }
                    TextPill("Start earlier", false) { onTrim(-500_000, 0) }
                    TextPill("Start later", false) { onTrim(500_000, 0) }
                }
            }
        }
        }
        Spacer(Modifier.height(Spacing.s))
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
            TextButton(onClick = onDuplicate) { Text("Duplicate") }
            TextButton(onClick = onFront) { Text("Bring to front") }
            TextButton(onClick = onDelete) { Text("Delete", color = c.danger) }
        }
    }
}

@Composable
private fun Swatches(selected: Int, onPick: (Int) -> Unit) {
    val c = CineMind.colors
    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xs), modifier = Modifier.horizontalScroll(rememberScrollState()).padding(vertical = Spacing.xs)) {
        SWATCHES.forEach { col ->
            Box(
                Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(Color(col))
                    .border(if (col == selected) 3.dp else 1.dp, if (col == selected) c.selection else c.hairline, CircleShape)
                    .clickable { onPick(col) }
                    .semantics { contentDescription = "Colour #" + Integer.toHexString(col).takeLast(6) },
            )
        }
    }
}

@Composable
private fun ValueSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, fmt: (Float) -> String, onDone: (Float) -> Unit) {
    val c = CineMind.colors
    var v by remember(value) { mutableFloatStateOf(value.coerceIn(range.start, range.endInclusive)) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = c.textSecondary, modifier = Modifier.width(96.dp))
        Slider(
            value = v,
            onValueChange = { v = it },
            onValueChangeFinished = { onDone(v) },
            valueRange = range,
            colors = SliderDefaults.colors(thumbColor = c.selection, activeTrackColor = c.selection, inactiveTrackColor = c.surfaceSunken),
            modifier = Modifier.weight(1f).semantics { contentDescription = label },
        )
        Text(fmt(v), style = MaterialTheme.typography.labelSmall, color = c.textTertiary, modifier = Modifier.width(44.dp))
    }
}

/** The same typeface the preview and export draw with, for showing font and look names. */
internal fun fontFamilyOf(font: CaptionFont, weight: Int = 700): FontFamily =
    FontFamily(androidx.compose.ui.text.font.Typeface(com.cinemind.core.ui.text.FontBook.typeface(font, weight)))

/** A look shown in its own style (font, weight, colour, box). */
@Composable
private fun PresetChip(p: TextPreset, selected: Boolean, onClick: () -> Unit) {
    val c = CineMind.colors
    val s = p.style
    val shape = RoundedCornerShape(Radius.m)
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .clip(shape)
            .background(Color(0xFF1B1D24))
            .border(if (selected) 2.dp else 1.dp, if (selected) c.selection else c.hairline, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = Spacing.m, vertical = Spacing.s),
    ) {
        val label = if (s.uppercase) p.label.uppercase() else p.label
        Text(
            label,
            color = Color(s.color),
            fontSize = 15.sp,
            fontWeight = if (s.font.display) null else FontWeight(s.weight.coerceIn(100, 900)),
            fontFamily = fontFamilyOf(s.font, s.weight),
            modifier = if (s.boxOpacity > 0f) Modifier.background(Color(s.boxColor).copy(alpha = s.boxOpacity), RoundedCornerShape(4.dp)).padding(horizontal = 4.dp) else Modifier,
        )
    }
}

@Composable
private fun TextPill(text: String, selected: Boolean, fontFamily: FontFamily? = null, onClick: () -> Unit) {
    val c = CineMind.colors
    val shape = RoundedCornerShape(Radius.xl)
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        fontFamily = fontFamily,
        color = if (selected) c.onAccent else c.textPrimary,
        modifier = Modifier
            .clip(shape)
            .background(if (selected) c.accent else c.surfaceSunken)
            .border(1.dp, if (selected) c.accent else c.hairline, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = Spacing.m, vertical = Spacing.xs),
    )
}

/** Bar shown while a title or sticker is selected (the preview stays usable for dragging it). */
@Composable
internal fun TextInspectorBar(layer: TextLayer, onEdit: () -> Unit, onDuplicate: () -> Unit, onDelete: () -> Unit, onClose: () -> Unit) {
    val c = CineMind.colors
    val shape = RoundedCornerShape(Radius.l)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.s, vertical = Spacing.xxs)
            .clip(shape)
            .background(c.surfaceGlass)
            .border(1.dp, c.hairline, shape)
            .padding(horizontal = Spacing.s),
    ) {
        Column(Modifier.weight(1f).padding(vertical = Spacing.xs)) {
            Text(layer.text.replace('\n', ' '), style = MaterialTheme.typography.bodyMedium, color = c.textPrimary, maxLines = 1)
            Text("Drag, pinch or twist it on the preview", style = MaterialTheme.typography.labelSmall, color = c.textTertiary, maxLines = 1)
        }
        TextButton(onClick = onEdit) { Text("Edit") }
        TextButton(onClick = onDuplicate) { Text("Copy") }
        TextButton(onClick = onDelete) { Text("Delete", color = c.danger) }
        TextButton(onClick = onClose) { Text("Done") }
    }
}
