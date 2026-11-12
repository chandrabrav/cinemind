package com.cinemind.feature.color.ui

import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Compare
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cinemind.core.domain.color.ColorGrade
import com.cinemind.core.ui.components.ChoicePills
import com.cinemind.core.ui.components.CineMindSheet
import com.cinemind.core.ui.components.GlassCard
import com.cinemind.core.ui.components.GradientButton
import com.cinemind.core.ui.components.SettingRow
import com.cinemind.core.ui.components.StatusPill
import com.cinemind.core.ui.components.Tone
import com.cinemind.core.ui.theme.CineMind
import com.cinemind.core.ui.theme.Radius
import com.cinemind.core.ui.theme.Spacing
import com.cinemind.feature.color.library.LutEntry
import com.cinemind.feature.color.library.LutSource
import java.util.Locale

private enum class ColorTab(val label: String) { LOOKS("Looks"), ADJUST("Adjust") }

/**
 * Colour tool: searchable looks (CineMind looks, imported .cube files, the optional film pack) with
 * AI suggestions for this video and live thumbnails on its own frame, plus manual adjustments.
 * Every change is applied to the clip(s) right away (one undo step) through [onGrade].
 */
@Composable
fun ColorSheet(
    grade: ColorGrade,
    targetLabel: String,
    allClips: Boolean,
    onAllClipsChange: (Boolean) -> Unit,
    frameUri: String?,
    frameIsVideo: Boolean,
    frameTimeUs: Long,
    themeText: String,
    onGrade: (ColorGrade) -> Unit,
    onCompare: (Boolean) -> Unit,
    onDismiss: () -> Unit,
    viewModel: ColorViewModel = hiltViewModel(),
) {
    val c = CineMind.colors
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    LaunchedEffect(frameUri, frameTimeUs) { viewModel.start(frameUri, frameIsVideo, frameTimeUs, themeText) }
    var tab by rememberSaveable { mutableStateOf(ColorTab.LOOKS) }
    val context = LocalContext.current
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val name = runCatching {
                context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cur ->
                    if (cur.moveToFirst()) cur.getString(0) else null
                }
            }.getOrNull()
            viewModel.importCube(uri, name) { onGrade(grade.copy(lutId = it.id, lutIntensity = 1f)) }
        }
    }

    CineMindSheet(onDismiss = onDismiss, title = "Colour") {
        Column(Modifier.heightIn(max = 640.dp).verticalScroll(rememberScrollState())) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ChoicePills(ColorTab.entries, tab, { it.label }, { tab = it }, contentPadding = PaddingValues(0.dp), modifier = Modifier.weight(1f))
                CompareButton(onCompare)
            }
            SettingRow("Apply to all clips", subtitle = targetLabel) { Switch(checked = allClips, onCheckedChange = onAllClipsChange) }
            ui.message?.let {
                Text(it, color = c.textSecondary, style = MaterialTheme.typography.bodySmall, modifier = Modifier.clickable { viewModel.dismissMessage() }.padding(vertical = Spacing.xs))
            }
            when (tab) {
                ColorTab.LOOKS -> LooksTab(
                    ui = ui,
                    grade = grade,
                    viewModel = viewModel,
                    onGrade = onGrade,
                    onImport = { importer.launch(arrayOf("*/*")) },
                )
                ColorTab.ADJUST -> AdjustTab(grade, onGrade)
            }
        }
    }
}

/** Press and hold to see the clip without its grade. */
@Composable
private fun CompareButton(onCompare: (Boolean) -> Unit) {
    val c = CineMind.colors
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(RoundedCornerShape(Radius.pill))
            .background(c.surfaceSunken)
            .semantics { contentDescription = "Hold to compare with the original" }
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown()
                    onCompare(true)
                    waitForUpOrCancellation()
                    onCompare(false)
                }
            }
            .padding(horizontal = Spacing.m, vertical = Spacing.s),
    ) {
        Icon(Icons.Default.Compare, contentDescription = null, tint = c.textSecondary, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(Spacing.xs))
        Text("Hold: before", style = MaterialTheme.typography.labelMedium, color = c.textSecondary)
    }
}

@Composable
private fun LooksTab(
    ui: ColorUi,
    grade: ColorGrade,
    viewModel: ColorViewModel,
    onGrade: (ColorGrade) -> Unit,
    onImport: () -> Unit,
) {
    val c = CineMind.colors
    val sliderColors = SliderDefaults.colors(thumbColor = c.accent, activeTrackColor = c.accent, inactiveTrackColor = c.surfaceSunken)
    if (grade.lutId != null) {
        var intensity by remember(grade.lutId, grade.lutIntensity) { mutableFloatStateOf(grade.lutIntensity) }
        Text("Look strength ${(intensity * 100).toInt()}%", style = MaterialTheme.typography.labelLarge, color = c.textSecondary, modifier = Modifier.padding(top = Spacing.s))
        Slider(value = intensity, onValueChange = { intensity = it }, onValueChangeFinished = { onGrade(grade.copy(lutIntensity = intensity)) }, colors = sliderColors)
    }

    // What the suggestions are based on, and the optional on-device AI scene check.
    GlassCard(modifier = Modifier.fillMaxWidth().padding(vertical = Spacing.s), contentPadding = PaddingValues(Spacing.m)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.AutoAwesome, contentDescription = null, tint = c.accent)
            Spacer(Modifier.width(Spacing.s))
            Text("AI suggestions for this video", style = MaterialTheme.typography.titleSmall, color = c.textPrimary, modifier = Modifier.weight(1f))
            if (ui.aiUsed) StatusPill("Scene checked", Tone.Success)
        }
        val stats = ui.stats
        val measured = stats?.let {
            listOfNotNull(
                when { it.brightness < 0.35f -> "dark"; it.brightness > 0.62f -> "bright"; else -> null },
                when { it.saturation < 0.18f -> "flat colour"; it.saturation > 0.45f -> "very colourful"; else -> null },
                when { it.warmth > 0.12f -> "warm light"; it.warmth < -0.12f -> "cool light"; else -> null },
            )
        }.orEmpty()
        val basis = (ui.basis + measured).distinct()
        Text(
            if (basis.isEmpty()) "Based on the frame. Describe your video in Smart edit or ask the AI about the scene for sharper picks."
            else "Based on: " + basis.joinToString(", "),
            style = MaterialTheme.typography.bodySmall,
            color = c.textSecondary,
        )
        if (ui.aiAvailable && !ui.aiUsed) {
            TextButton(onClick = viewModel::askAi, enabled = !ui.aiBusy) {
                if (ui.aiBusy) CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(16.dp)) else Icon(Icons.Default.AutoAwesome, contentDescription = null, tint = c.accent)
                Spacer(Modifier.width(Spacing.xs))
                Text(if (ui.aiBusy) "Looking at the scene…" else "Ask on-device AI about the scene", color = c.accent)
            }
        }
    }

    OutlinedTextField(
        value = ui.query,
        onValueChange = viewModel::setQuery,
        placeholder = { Text("Search looks: beach, night, wedding, film…", color = c.textTertiary) },
        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = c.textTertiary) },
        singleLine = true,
        shape = RoundedCornerShape(Radius.m),
        modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Search looks" },
    )
    Spacer(Modifier.height(Spacing.s))
    ChoicePills(LookFilter.entries, ui.filter, { it.label }, viewModel::setFilter, contentPadding = PaddingValues(0.dp))

    if (ui.filter == LookFilter.PACK && !ui.packNoticeAccepted) {
        PackNotice(ui, onAccept = viewModel::acceptPackNotice)
        return
    }

    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = Spacing.s)) {
        Text("${ui.entries.size} looks", style = MaterialTheme.typography.labelMedium, color = c.textTertiary, modifier = Modifier.weight(1f))
        TextButton(onClick = onImport) {
            Icon(Icons.Default.FileOpen, contentDescription = null, tint = c.accent)
            Spacer(Modifier.width(Spacing.xs))
            Text("Import .cube", color = c.accent)
        }
    }

    val reasons = ui.suggestions.associate { it.entry.id to it.reason }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 104.dp),
        horizontalArrangement = Arrangement.spacedBy(Spacing.s),
        verticalArrangement = Arrangement.spacedBy(Spacing.s),
        modifier = Modifier.fillMaxWidth().height(400.dp),
    ) {
        item {
            LookTile(
                name = "None",
                subtitle = "Original colour",
                thumb = viewModel.originalFrame(),
                selected = grade.lutId == null,
                available = true,
                downloading = false,
                onClick = { onGrade(grade.copy(lutId = null)) },
            )
        }
        items(ui.entries, key = { it.id }) { e ->
            // thumbsVersion makes tiles refresh when their thumbnail is ready.
            val thumb = remember(e.id, ui.thumbsVersion) { viewModel.thumbnail(e.id) }
            LookTile(
                name = e.name,
                subtitle = if (ui.filter == LookFilter.SUGGESTED) reasons[e.id] ?: e.category else "${e.source.label} · ${e.category}",
                thumb = thumb,
                selected = grade.lutId == e.id,
                available = e.available,
                downloading = e.id in ui.downloading,
                onClick = {
                    if (e.available) onGrade(grade.copy(lutId = e.id, lutIntensity = if (grade.lutId == e.id) grade.lutIntensity else 1f))
                    else viewModel.download(e) { ready -> onGrade(grade.copy(lutId = ready.id, lutIntensity = 1f)) }
                },
                onLongClick = if (e.source == LutSource.IMPORTED) ({ viewModel.deleteImported(e) }) else null,
            )
        }
    }
}

@Composable
private fun PackNotice(ui: ColorUi, onAccept: () -> Unit) {
    val c = CineMind.colors
    val info = ui.packInfo
    GlassCard(modifier = Modifier.fillMaxWidth().padding(vertical = Spacing.s), contentPadding = PaddingValues(Spacing.m)) {
        Text(info?.name ?: "Film pack", style = MaterialTheme.typography.titleSmall, color = c.textPrimary)
        Text(
            "296 film-emulation LUTs from the open-source G'MIC collection, downloaded one at a time (about 130 KB each) " +
                "from github.com/${info?.repo} at a fixed version, and checked before use.",
            style = MaterialTheme.typography.bodySmall,
            color = c.textSecondary,
            modifier = Modifier.padding(vertical = Spacing.xs),
        )
        Text("Licence: ${info?.license}", style = MaterialTheme.typography.bodySmall, color = c.textTertiary)
        Text(
            "CineMind's own looks have no such restrictions. If you publish commercially, prefer them or LUTs you own.",
            style = MaterialTheme.typography.bodySmall,
            color = c.textTertiary,
            modifier = Modifier.padding(top = Spacing.xs),
        )
        Spacer(Modifier.height(Spacing.s))
        GradientButton("Show the film pack", onClick = onAccept)
    }
}

@Composable
private fun LookTile(
    name: String,
    subtitle: String,
    thumb: android.graphics.Bitmap?,
    selected: Boolean,
    available: Boolean,
    downloading: Boolean,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
) {
    val c = CineMind.colors
    val shape = RoundedCornerShape(Radius.m)
    Column(
        modifier = Modifier
            .clip(shape)
            .border(if (selected) 2.dp else 1.dp, if (selected) c.accent else c.hairline, shape)
            .then(
                if (onLongClick != null) Modifier.pointerInput(onLongClick) {
                    detectTapGestures(onTap = { onClick() }, onLongPress = { onLongClick() })
                } else Modifier.clickable(onClick = onClick),
            )
            .semantics { contentDescription = "$name look" + (if (selected) ", selected" else "") + (if (!available) ", tap to download" else "") },
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.fillMaxWidth().aspectRatio(1f).background(c.surfaceSunken),
        ) {
            when {
                thumb != null -> Image(thumb.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                downloading -> CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(24.dp))
                !available -> Icon(Icons.Default.CloudDownload, contentDescription = null, tint = c.textTertiary)
                else -> Icon(Icons.Default.Palette, contentDescription = null, tint = c.textTertiary)
            }
        }
        Text(name, style = MaterialTheme.typography.labelLarge, color = c.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = Spacing.xs, vertical = 2.dp))
        Text(subtitle, style = MaterialTheme.typography.labelSmall, color = c.textTertiary, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = Spacing.xs, end = Spacing.xs, bottom = Spacing.xs))
    }
}

@Composable
private fun AdjustTab(grade: ColorGrade, onGrade: (ColorGrade) -> Unit) {
    val c = CineMind.colors
    val sliderColors = SliderDefaults.colors(thumbColor = c.accent, activeTrackColor = c.accent, inactiveTrackColor = c.surfaceSunken)
    @Composable
    fun adj(label: String, value: Float, range: ClosedFloatingPointRange<Float>, apply: (ColorGrade, Float) -> ColorGrade) {
        var v by remember(value) { mutableFloatStateOf(value) }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = Spacing.s)) {
            Text(label, style = MaterialTheme.typography.labelLarge, color = c.textSecondary, modifier = Modifier.weight(1f))
            Text(String.format(Locale.ROOT, "%+.2f", v), style = MaterialTheme.typography.labelMedium, color = c.textTertiary)
        }
        Slider(value = v, valueRange = range, onValueChange = { v = it }, onValueChangeFinished = { onGrade(apply(grade, v)) }, colors = sliderColors, modifier = Modifier.semantics { contentDescription = label })
    }
    adj("Exposure", grade.exposure, -2f..2f) { g, v -> g.copy(exposure = v) }
    adj("Contrast", grade.contrast, -1f..1f) { g, v -> g.copy(contrast = v) }
    adj("Highlights", grade.highlights, -1f..1f) { g, v -> g.copy(highlights = v) }
    adj("Shadows", grade.shadows, -1f..1f) { g, v -> g.copy(shadows = v) }
    adj("Saturation", grade.saturation, -1f..1f) { g, v -> g.copy(saturation = v) }
    adj("Vibrance", grade.vibrance, -1f..1f) { g, v -> g.copy(vibrance = v) }
    adj("Temperature", grade.temperature, -1f..1f) { g, v -> g.copy(temperature = v) }
    adj("Tint", grade.tint, -1f..1f) { g, v -> g.copy(tint = v) }
    adj("Fade", grade.fade, 0f..1f) { g, v -> g.copy(fade = v) }
    TextButton(
        onClick = { onGrade(ColorGrade(lutId = grade.lutId, lutIntensity = grade.lutIntensity)) },
        enabled = grade.hasAdjustments,
        modifier = Modifier.padding(top = Spacing.s),
    ) { Text("Reset adjustments", color = c.danger) }
}
