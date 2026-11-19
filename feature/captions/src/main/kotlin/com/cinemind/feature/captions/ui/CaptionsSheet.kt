package com.cinemind.feature.captions.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.cinemind.core.domain.captions.Caption
import com.cinemind.core.domain.captions.CaptionAlign
import com.cinemind.core.domain.captions.CaptionAnimation
import com.cinemind.core.domain.captions.CaptionFont
import com.cinemind.core.domain.captions.CaptionLayout
import com.cinemind.core.domain.captions.CaptionMode
import com.cinemind.core.domain.captions.CaptionPresets
import com.cinemind.core.domain.captions.CaptionScript
import com.cinemind.core.domain.captions.CaptionSource
import com.cinemind.core.domain.captions.CaptionStyle
import com.cinemind.core.domain.captions.SpokenLanguage
import com.cinemind.core.ui.captions.CaptionPainter
import com.cinemind.core.ui.components.ChoicePills
import com.cinemind.core.ui.components.CineMindSheet
import com.cinemind.core.ui.components.GlassCard
import com.cinemind.core.ui.components.GradientButton
import com.cinemind.core.ui.components.SettingRow
import com.cinemind.core.ui.components.StatusPill
import com.cinemind.core.ui.components.Tone
import com.cinemind.core.ui.format.formatShortTime
import com.cinemind.core.ui.theme.CineMind
import com.cinemind.core.ui.theme.Radius
import com.cinemind.core.ui.theme.Spacing
import com.cinemind.feature.captions.speech.SpeechSource

private enum class CaptionTab(val label: String) { TEXT("Captions"), STYLE("Style") }

/**
 * Captions tool: speech-to-text, the editable caption list, and the caption look.
 * @param sources the timeline's clips with sound, for speech-to-text.
 */
@Composable
fun CaptionsSheet(
    state: CaptionsUi,
    sources: List<SpeechSource>,
    positionUs: Long,
    totalUs: Long,
    frameRatio: Float,
    onSeek: (Long) -> Unit,
    viewModel: CaptionsViewModel,
    onOpenAiModels: () -> Unit,
    onDismiss: () -> Unit,
) {
    var tab by rememberSaveable { mutableStateOf(CaptionTab.TEXT) }
    CineMindSheet(onDismiss = onDismiss, title = "Captions") {
        Column(Modifier.heightIn(max = 620.dp).verticalScroll(rememberScrollState())) {
            ChoicePills(CaptionTab.entries, tab, { it.label }, { tab = it }, contentPadding = PaddingValues(0.dp))
            Spacer(Modifier.height(Spacing.m))
            when (tab) {
                CaptionTab.TEXT -> TextTab(state, sources, positionUs, totalUs, onSeek, viewModel, onOpenAiModels)
                CaptionTab.STYLE -> StyleTab(state.style, frameRatio, viewModel)
            }
        }
    }
}

@Composable
private fun TextTab(
    state: CaptionsUi,
    sources: List<SpeechSource>,
    positionUs: Long,
    totalUs: Long,
    onSeek: (Long) -> Unit,
    viewModel: CaptionsViewModel,
    onOpenAiModels: () -> Unit,
) {
    val c = CineMind.colors
    GlassCard(modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(Spacing.m)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.GraphicEq, contentDescription = null, tint = c.accent)
            Spacer(Modifier.width(Spacing.s))
            Text("Speech to text", style = MaterialTheme.typography.titleSmall, color = c.textPrimary, modifier = Modifier.weight(1f))
            StatusPill("On this phone", Tone.Success)
        }
        Spacer(Modifier.height(Spacing.s))
        Text("Spoken language", style = MaterialTheme.typography.labelLarge, color = c.textSecondary)
        ChoicePills(SpokenLanguage.entries, state.language, { it.label }, viewModel::setLanguage, contentPadding = PaddingValues(0.dp))
        Spacer(Modifier.height(Spacing.s))
        Text("Write captions as", style = MaterialTheme.typography.labelLarge, color = c.textSecondary)
        ChoicePills(CaptionScript.entries, state.script, { it.label }, viewModel::setScript, contentPadding = PaddingValues(0.dp))
        Text(state.script.description, style = MaterialTheme.typography.bodySmall, color = c.textTertiary)
        Spacer(Modifier.height(Spacing.m))

        val job = state.job
        when {
            job.running -> {
                LinearProgressIndicator(
                    progress = { job.progress },
                    color = c.accent,
                    trackColor = c.surfaceSunken,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(job.message, style = MaterialTheme.typography.bodySmall, color = c.textSecondary, modifier = Modifier.weight(1f))
                    TextButton(onClick = viewModel::cancelJob) { Text("Cancel", color = c.danger) }
                }
            }
            !state.speechModelReady -> {
                Text(
                    "Speech-to-text uses the on-device AI model (Gemma 4). Install it once on Wi-Fi; your audio never leaves the phone. " +
                        "You can add and edit captions by hand without it.",
                    style = MaterialTheme.typography.bodySmall,
                    color = c.textSecondary,
                )
                Spacer(Modifier.height(Spacing.s))
                GradientButton("Set up on-device AI", onClick = onOpenAiModels)
            }
            else -> {
                GradientButton(
                    if (state.captions.isEmpty()) "Transcribe speech" else "Transcribe again (replaces captions)",
                    onClick = { viewModel.generate(sources) },
                    enabled = sources.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (sources.isEmpty()) {
                    Text("No video clips with sound on the timeline.", style = MaterialTheme.typography.bodySmall, color = c.textTertiary)
                }
                Text(
                    "Song lyrics, heavy music or noise are often recognised wrongly; unsure words are marked ● for you to check.",
                    style = MaterialTheme.typography.bodySmall,
                    color = c.textTertiary,
                )
            }
        }
        job.error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = c.danger, modifier = Modifier.padding(top = Spacing.xs)) }
        job.summary?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = c.success, modifier = Modifier.padding(top = Spacing.xs)) }
    }

    Spacer(Modifier.height(Spacing.m))
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        TextButton(onClick = { viewModel.addAt(positionUs, totalUs) }) {
            Icon(Icons.Default.Add, contentDescription = null, tint = c.accent)
            Spacer(Modifier.width(Spacing.xs))
            Text("Add at playhead", color = c.accent)
        }
        Spacer(Modifier.weight(1f))
        IconButton(onClick = viewModel::undo, enabled = state.canUndo) {
            Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = "Undo caption change", tint = if (state.canUndo) c.textPrimary else c.textTertiary.copy(alpha = 0.4f))
        }
        IconButton(onClick = viewModel::redo, enabled = state.canRedo) {
            Icon(Icons.AutoMirrored.Filled.Redo, contentDescription = "Redo caption change", tint = if (state.canRedo) c.textPrimary else c.textTertiary.copy(alpha = 0.4f))
        }
    }
    if (state.captions.isNotEmpty()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (state.speechModelReady) {
                TextButton(onClick = viewModel::translateAllToEnglish, enabled = !state.job.running) {
                    Icon(Icons.Default.Translate, contentDescription = null, tint = c.textSecondary)
                    Spacer(Modifier.width(Spacing.xs))
                    Text("Translate all to English", color = c.textSecondary)
                }
            }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = viewModel::clearAll) {
                Icon(Icons.Default.DeleteSweep, contentDescription = null, tint = c.danger)
                Spacer(Modifier.width(Spacing.xs))
                Text("Clear all", color = c.danger)
            }
        }
    }

    if (state.captions.isEmpty()) {
        Text(
            "No captions yet. Transcribe the speech, or move the playhead and add one by hand.",
            style = MaterialTheme.typography.bodyMedium,
            color = c.textTertiary,
            modifier = Modifier.padding(vertical = Spacing.l),
        )
    }
    state.captions.forEach { caption ->
        CaptionRow(
            caption = caption,
            active = positionUs >= caption.startUs && positionUs < caption.endUs,
            onSeek = { onSeek(caption.startUs) },
            onText = { viewModel.editText(caption.id, it) },
            onRetime = { s, e -> viewModel.retime(caption.id, s, e, totalUs) },
            onSplit = { viewModel.split(caption.id, positionUs.coerceIn(caption.startUs, caption.endUs)) },
            onDelete = { viewModel.delete(caption.id) },
        )
    }
}

@Composable
private fun CaptionRow(
    caption: Caption,
    active: Boolean,
    onSeek: () -> Unit,
    onText: (String) -> Unit,
    onRetime: (Long, Long) -> Unit,
    onSplit: () -> Unit,
    onDelete: () -> Unit,
) {
    val c = CineMind.colors
    val shape = RoundedCornerShape(Radius.m)
    var text by remember(caption.id, caption.text) { mutableStateOf(caption.text) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = Spacing.xs)
            .clip(shape)
            .background(if (active) c.accentContainer else c.surfaceSunken)
            .border(1.dp, if (active) c.accent else c.hairline, shape)
            .padding(Spacing.s),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "${formatShortTime(caption.startUs)} – ${formatShortTime(caption.endUs)}",
                style = MaterialTheme.typography.labelLarge,
                color = if (active) c.onAccentContainer else c.textPrimary,
                modifier = Modifier.clickable(onClick = onSeek).padding(vertical = Spacing.xs),
            )
            Spacer(Modifier.width(Spacing.s))
            if (caption.uncertain) StatusPill("● check", Tone.Warning)
            Spacer(Modifier.weight(1f))
            Text(sourceLabel(caption), style = MaterialTheme.typography.labelSmall, color = c.textTertiary, maxLines = 1)
        }
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            textStyle = MaterialTheme.typography.bodyLarge,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { onText(text) }),
            shape = RoundedCornerShape(Radius.s),
            modifier = Modifier
                .fillMaxWidth()
                .onFocusChanged { if (!it.isFocused && text != caption.text) onText(text) },
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Nudge("Start", onEarlier = { onRetime(caption.startUs - STEP_US, caption.endUs) }, onLater = { onRetime(caption.startUs + STEP_US, caption.endUs) })
            Spacer(Modifier.width(Spacing.s))
            Nudge("End", onEarlier = { onRetime(caption.startUs, caption.endUs - STEP_US) }, onLater = { onRetime(caption.startUs, caption.endUs + STEP_US) })
            Spacer(Modifier.weight(1f))
            IconButton(onClick = onSplit) { Icon(Icons.Default.ContentCut, contentDescription = "Split caption at playhead", tint = c.textSecondary) }
            IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, contentDescription = "Delete caption", tint = c.danger) }
        }
    }
}

private const val STEP_US = 100_000L

@Composable
private fun Nudge(label: String, onEarlier: () -> Unit, onLater: () -> Unit) {
    val c = CineMind.colors
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = c.textTertiary)
        TextButton(onClick = onEarlier, contentPadding = PaddingValues(horizontal = Spacing.xs), modifier = Modifier.semantics { contentDescription = "$label 0.1 seconds earlier" }) {
            Text("−0.1", color = c.textPrimary)
        }
        TextButton(onClick = onLater, contentPadding = PaddingValues(horizontal = Spacing.xs), modifier = Modifier.semantics { contentDescription = "$label 0.1 seconds later" }) {
            Text("+0.1", color = c.textPrimary)
        }
    }
}

private fun sourceLabel(c: Caption): String {
    val from = when (c.source) {
        CaptionSource.SPEECH.name -> "Speech"
        CaptionSource.TRANSLATION.name -> "Translated"
        else -> "Typed"
    }
    return if (c.language.isNotEmpty()) "$from · ${c.language}" else from
}

// ---------------------------------------------------------------- style

private val TEXT_COLORS = listOf(0xFFFFFFFF, 0xFFFFF4D6, 0xFFFFD43B, 0xFF7DD3FC, 0xFFFF8FAB, 0xFF111111).map { it.toInt() }
private val HIGHLIGHT_COLORS = listOf(0xFFFFD43B, 0xFF7DD3FC, 0xFFFF8FAB, 0xFF86EFAC, 0xFFFF6B3D, 0xFFFFFFFF).map { it.toInt() }
private val BOX_COLORS = listOf(0xFF000000, 0xFF1E1B4B, 0xFFFFFFFF, 0xFF7C2D12).map { it.toInt() }
private val WEIGHTS = listOf(400 to "Regular", 600 to "Semibold", 800 to "Bold", 900 to "Black")

@Composable
private fun StyleTab(style: CaptionStyle, frameRatio: Float, viewModel: CaptionsViewModel) {
    val c = CineMind.colors
    val sliderColors = SliderDefaults.colors(thumbColor = c.accent, activeTrackColor = c.accent, inactiveTrackColor = c.surfaceSunken)

    StylePreview(style, frameRatio)
    Spacer(Modifier.height(Spacing.m))

    Label("Presets")
    LazyRow(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
        items(CaptionPresets.ALL) { preset ->
            val selected = preset.id == style.presetId
            Column(
                modifier = Modifier
                    .width(132.dp)
                    .clip(RoundedCornerShape(Radius.m))
                    .background(if (selected) c.accentContainer else c.surfaceSunken)
                    .border(if (selected) 2.dp else 1.dp, if (selected) c.accent else c.hairline, RoundedCornerShape(Radius.m))
                    .clickable { viewModel.applyPreset(preset) }
                    .padding(Spacing.s)
                    .heightIn(min = 64.dp),
            ) {
                Text(preset.label, style = MaterialTheme.typography.labelLarge, color = if (selected) c.onAccentContainer else c.textPrimary, maxLines = 1)
                Text(preset.hint, style = MaterialTheme.typography.labelSmall, color = if (selected) c.onAccentContainer else c.textTertiary, maxLines = 2)
            }
        }
    }

    Label("Show")
    ChoicePills(CaptionMode.entries, style.modeEnum, { it.label }, { m -> viewModel.updateStyle { it.copy(mode = m.name) } }, contentPadding = PaddingValues(0.dp))
    SettingRow("Highlight the spoken word", subtitle = "Karaoke style. Word timing is estimated.") {
        Switch(checked = style.karaoke, onCheckedChange = { k -> viewModel.updateStyle { it.copy(karaoke = k) } })
    }
    Label("Animation")
    ChoicePills(CaptionAnimation.entries, style.animationEnum, { it.label }, { a -> viewModel.updateStyle { it.copy(animation = a.name) } }, contentPadding = PaddingValues(0.dp))

    Label("Font")
    ChoicePills(CaptionFont.entries, style.fontEnum, { it.label }, { f -> viewModel.updateStyle { it.copy(font = f.name) } }, contentPadding = PaddingValues(0.dp))
    ChoicePills(WEIGHTS, WEIGHTS.firstOrNull { it.first == style.weight }, { it.second }, { w -> viewModel.updateStyle { it.copy(weight = w.first) } }, contentPadding = PaddingValues(top = Spacing.s))
    var size by remember(style.size) { mutableFloatStateOf(style.size) }
    Label("Size")
    Slider(value = size, valueRange = 0.03f..0.11f, onValueChange = { size = it }, onValueChangeFinished = { viewModel.updateStyle { it.copy(size = size) } }, colors = sliderColors)
    SettingRow("ALL CAPS") { Switch(checked = style.uppercase, onCheckedChange = { u -> viewModel.updateStyle { it.copy(uppercase = u) } }) }
    SettingRow(
        title = "Mix it up",
        subtitle = "Each phrase gets its own font, size and colour, and its key word pops",
    ) { Switch(checked = style.variety, onCheckedChange = { v -> viewModel.updateStyle { it.copy(variety = v) } }) }
    SettingRow("Behind the person", subtitle = "Captions sit behind whoever is in the shot (uses the clip's cutout: Edit → Cutout)") {
        Switch(checked = style.behindSubject, onCheckedChange = { b -> viewModel.updateStyle { it.copy(behindSubject = b) } })
    }

    Label("Text colour")
    Swatches(TEXT_COLORS, style.textColor) { col -> viewModel.updateStyle { it.copy(textColor = col) } }
    Label("Highlight colour")
    Swatches(HIGHLIGHT_COLORS, style.highlightColor) { col -> viewModel.updateStyle { it.copy(highlightColor = col) } }

    var outline by remember(style.outline) { mutableFloatStateOf(style.outline) }
    Label("Outline")
    Slider(value = outline, valueRange = 0f..0.25f, onValueChange = { outline = it }, onValueChangeFinished = { viewModel.updateStyle { it.copy(outline = outline) } }, colors = sliderColors)
    SettingRow("Shadow") { Switch(checked = style.shadow, onCheckedChange = { s -> viewModel.updateStyle { it.copy(shadow = s) } }) }

    var box by remember(style.backgroundOpacity) { mutableFloatStateOf(style.backgroundOpacity) }
    Label("Background box ${(box * 100).toInt()}%")
    Slider(value = box, onValueChange = { box = it }, onValueChangeFinished = { viewModel.updateStyle { it.copy(backgroundOpacity = box) } }, colors = sliderColors)
    if (style.backgroundOpacity > 0f) Swatches(BOX_COLORS, style.backgroundColor) { col -> viewModel.updateStyle { it.copy(backgroundColor = col) } }

    var y by remember(style.positionY) { mutableFloatStateOf(style.positionY) }
    Label("Position (stays inside the safe area)")
    Slider(
        value = y,
        valueRange = CaptionStyle.SAFE_TOP..CaptionStyle.SAFE_BOTTOM,
        onValueChange = { y = it },
        onValueChangeFinished = { viewModel.updateStyle { it.copy(positionY = y) } },
        colors = sliderColors,
    )
    ChoicePills(CaptionAlign.entries, style.alignEnum, { it.name.lowercase().replaceFirstChar(Char::uppercase) }, { a -> viewModel.updateStyle { it.copy(align = a.name) } }, contentPadding = PaddingValues(0.dp))
}

@Composable
private fun Label(text: String) {
    Text(text, style = MaterialTheme.typography.labelLarge, color = CineMind.colors.textSecondary, modifier = Modifier.padding(top = Spacing.m, bottom = Spacing.xs))
}

@Composable
private fun Swatches(colors: List<Int>, selected: Int, onSelect: (Int) -> Unit) {
    val c = CineMind.colors
    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
        colors.forEach { col ->
            val isSel = col == selected
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(Color(col))
                    .border(if (isSel) 3.dp else 1.dp, if (isSel) c.accent else c.hairline, CircleShape)
                    .clickable { onSelect(col) }
                    .semantics { contentDescription = "Colour #" + Integer.toHexString(col).uppercase() + if (isSel) ", selected" else "" },
            )
        }
    }
}

/** Live sample of the style with the safe area marked, looping so animations and highlight show. */
@Composable
private fun StylePreview(style: CaptionStyle, frameRatio: Float) {
    val c = CineMind.colors
    val painter = remember { CaptionPainter() }
    val transition = rememberInfiniteTransition(label = "captionSample")
    val t by transition.animateFloat(0f, 1f, infiniteRepeatable(tween(3600, easing = LinearEasing), RepeatMode.Restart), label = "t")
    val sample = remember { Caption(id = "sample", projectId = "", startUs = 0, endUs = 3_000_000, text = "Bhai, yeh jagah sach mein bahut beautiful hai!") }
    val ratio = frameRatio.coerceIn(0.5f, 1.8f)
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .fillMaxWidth()
            .height(220.dp)
            .clip(RoundedCornerShape(Radius.l))
            .background(c.stage),
    ) {
        Canvas(
            modifier = Modifier
                .aspectRatio(ratio, matchHeightConstraintsFirst = true)
                .background(Brush.verticalGradient(listOf(Color(0xFF3A6EA5), Color(0xFFE9A15B), Color(0xFF2B2D42)))),
        ) {
            val dash = PathEffect.dashPathEffect(floatArrayOf(10f, 8f))
            val guide = Color.White.copy(alpha = 0.55f)
            drawLine(guide, androidx.compose.ui.geometry.Offset(0f, size.height * CaptionStyle.SAFE_TOP), androidx.compose.ui.geometry.Offset(size.width, size.height * CaptionStyle.SAFE_TOP), 2f, pathEffect = dash)
            drawLine(guide, androidx.compose.ui.geometry.Offset(0f, size.height * CaptionStyle.SAFE_BOTTOM), androidx.compose.ui.geometry.Offset(size.width, size.height * CaptionStyle.SAFE_BOTTOM), 2f, pathEffect = dash)
            val tUs = (t * 3_300_000).toLong().coerceAtMost(2_999_999)
            CaptionLayout.frameAt(listOf(sample), style, tUs)?.let { frame ->
                drawIntoCanvas { canvas -> painter.draw(canvas.nativeCanvas, size.width.toInt(), size.height.toInt(), frame, style) }
            }
        }
    }
    Text("Dashed lines: safe area. Captions never go outside it.", style = MaterialTheme.typography.labelSmall, color = c.textTertiary, modifier = Modifier.padding(top = Spacing.xs))
}

/** Draws the caption layer over the editor preview, with the same painter the export uses. */
@Composable
fun CaptionOverlay(captions: List<Caption>, style: CaptionStyle, positionUs: Long, modifier: Modifier = Modifier) {
    val painter = remember { CaptionPainter() }
    val frame = remember(captions, style, positionUs) { CaptionLayout.frameAt(captions, style, positionUs) }
    if (frame == null) return
    Canvas(modifier) {
        drawIntoCanvas { canvas -> painter.draw(canvas.nativeCanvas, size.width.toInt(), size.height.toInt(), frame, style, markUncertain = true) }
    }
}
