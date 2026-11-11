package com.cinemind.feature.editor.timeline.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.Animation
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cinemind.core.domain.audio.MusicSettings
import com.cinemind.core.domain.editing.ClipLimits
import com.cinemind.core.domain.editing.ClipTransition
import com.cinemind.core.domain.editing.TimelineEditing
import com.cinemind.core.domain.editing.TransitionMath
import com.cinemind.core.ui.components.ChoicePills
import com.cinemind.core.ui.components.CineMindSheet
import com.cinemind.core.ui.components.GradientButton
import com.cinemind.core.ui.components.SettingRow
import com.cinemind.core.ui.components.StatusPill
import com.cinemind.core.ui.components.ToolButton
import com.cinemind.core.ui.components.hapticClick
import com.cinemind.core.ui.theme.CineMind
import com.cinemind.core.ui.theme.Radius
import com.cinemind.core.ui.theme.Spacing
import com.cinemind.core.ui.theme.formatTimecode
import com.cinemind.feature.editor.timeline.preview.PreviewClip
import java.util.Locale

private fun seconds(ms: Long) = String.format(Locale.ROOT, "%.1f s", ms / 1000f)

/** Controls for the selected clip. Trims are committed when the finger lifts (one undo step each). */
@Composable
internal fun ClipInspector(
    item: PreviewClip,
    index: Int,
    clipCount: Int,
    limits: ClipLimits,
    fps: Int,
    onTrim: (Long, Long) -> Unit,
    onMove: (Int) -> Unit,
    onDelete: () -> Unit,
    onSplit: () -> Unit,
    onClose: () -> Unit,
    onTransition: (ClipTransition, Long) -> Unit,
) {
    val c = CineMind.colors
    val shape = RoundedCornerShape(Radius.l)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.gutter, vertical = Spacing.xs)
            .clip(shape)
            .background(c.surfaceGlass)
            .border(1.dp, c.hairline, shape)
            .padding(horizontal = Spacing.m, vertical = Spacing.xs),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "Clip ${index + 1} of $clipCount",
                style = MaterialTheme.typography.titleSmall,
                color = c.textPrimary,
            )
            if (limits.isStill) {
                Spacer(Modifier.width(Spacing.s))
                StatusPill("Photo")
            }
            Spacer(Modifier.weight(1f))
            InspectorAction(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Move earlier", enabled = index > 0) { onMove(-1) }
            InspectorAction(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Move later", enabled = index < clipCount - 1) { onMove(1) }
            InspectorAction(Icons.Default.ContentCut, "Split at playhead", onClick = onSplit)
            InspectorAction(Icons.Default.Delete, "Remove clip from timeline", tint = c.danger, onClick = onDelete)
            InspectorAction(Icons.Default.Close, "Done", onClick = onClose)
        }
        if (limits.isStill) {
            StillLength(item = item, fps = fps, onTrim = onTrim)
        } else {
            VideoTrim(item = item, limits = limits, fps = fps, onTrim = onTrim)
        }
        val current = ClipTransition.fromName(item.clip.transitionInType)
        val currentMs = item.clip.transitionInMs
        val durationMs = if (current == ClipTransition.CUT || currentMs <= 0) 500L else currentMs
        Text(
            if (index == 0) "Opening" else "Transition from clip $index",
            style = MaterialTheme.typography.labelMedium,
            color = c.textSecondary,
            modifier = Modifier.padding(top = Spacing.xs),
        )
        ChoicePills(
            items = ClipTransition.entries,
            selected = current,
            label = { it.label },
            onSelect = { onTransition(it, durationMs) },
            contentPadding = PaddingValues(0.dp),
        )
        if (current != ClipTransition.CUT) {
            ChoicePills(
                items = TransitionMath.DURATION_OPTIONS_MS,
                selected = currentMs,
                label = { seconds(it) },
                onSelect = { onTransition(current, it) },
                contentPadding = PaddingValues(0.dp),
            )
        }
    }
}

@Composable
private fun InspectorAction(
    icon: ImageVector,
    description: String,
    enabled: Boolean = true,
    tint: androidx.compose.ui.graphics.Color = CineMind.colors.textPrimary,
    onClick: () -> Unit,
) {
    IconButton(onClick = hapticClick(onClick = onClick), enabled = enabled) {
        Icon(icon, contentDescription = description, tint = if (enabled) tint else CineMind.colors.textTertiary.copy(alpha = 0.4f))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VideoTrim(item: PreviewClip, limits: ClipLimits, fps: Int, onTrim: (Long, Long) -> Unit) {
    val c = CineMind.colors
    val clip = item.clip
    val sourceEnd = (limits.sourceDurationUs ?: clip.sourceOutUs).coerceAtLeast(1)
    // Local value while dragging; reset whenever the stored clip changes (commit, undo, redo).
    var range by remember(clip.id, clip.sourceInUs, clip.sourceOutUs) {
        mutableStateOf(clip.sourceInUs.toFloat()..clip.sourceOutUs.toFloat())
    }
    RangeSlider(
        value = range,
        onValueChange = { range = it },
        valueRange = 0f..sourceEnd.toFloat(),
        colors = SliderDefaults.colors(thumbColor = c.highlight, activeTrackColor = c.highlight, inactiveTrackColor = c.surfaceSunken),
        onValueChangeFinished = {
            val start = range.start.toLong()
            val end = range.endInclusive.toLong()
            // Snap back to the stored values; the committed (clamped) result arrives via recomposition.
            range = clip.sourceInUs.toFloat()..clip.sourceOutUs.toFloat()
            onTrim(start, end)
        },
    )
    val startUs = range.start.toLong()
    val endUs = range.endInclusive.toLong()
    Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
        Label("In ${formatTimecode(startUs, fps)}")
        Label("Length ${formatTimecode((endUs - startUs).coerceAtLeast(0), fps)}")
        Label("Out ${formatTimecode(endUs, fps)}")
    }
}

@Composable
private fun StillLength(item: PreviewClip, fps: Int, onTrim: (Long, Long) -> Unit) {
    val c = CineMind.colors
    val clip = item.clip
    val stored = (clip.sourceOutUs - clip.sourceInUs).toFloat()
    var length by remember(clip.id, stored) { mutableFloatStateOf(stored) }
    Slider(
        value = length,
        onValueChange = { length = it },
        valueRange = TimelineEditing.MIN_CLIP_US.toFloat()..TimelineEditing.MAX_STILL_US.toFloat(),
        colors = SliderDefaults.colors(thumbColor = c.highlight, activeTrackColor = c.highlight, inactiveTrackColor = c.surfaceSunken),
        onValueChangeFinished = {
            val chosen = length.toLong()
            length = stored
            onTrim(0, chosen)
        },
    )
    Label("Shown for ${formatTimecode(length.toLong(), fps)}")
}

@Composable
private fun Label(text: String) {
    Text(text, style = MaterialTheme.typography.labelSmall, color = CineMind.colors.textTertiary)
}

/** Sets one transition for every cut, plus optional fade-in/out at the very start and end. */
@Composable
internal fun TransitionsSheet(
    onDismiss: () -> Unit,
    onApplyAll: (ClipTransition, Long) -> Unit,
    onOpeningEnding: (Long, Long) -> Unit,
) {
    var type by remember { mutableStateOf(ClipTransition.FADE) }
    var durationMs by remember { mutableLongStateOf(500L) }
    var fadeIn by remember { mutableStateOf(true) }
    var fadeOut by remember { mutableStateOf(true) }
    val c = CineMind.colors

    CineMindSheet(onDismiss = onDismiss, title = "Transitions") {
        Text("Between every clip", style = MaterialTheme.typography.labelLarge, color = c.textSecondary)
        ChoicePills(ClipTransition.entries, type, { it.label }, { type = it }, contentPadding = PaddingValues(0.dp))
        if (type != ClipTransition.CUT) {
            ChoicePills(TransitionMath.DURATION_OPTIONS_MS, durationMs, { seconds(it) }, { durationMs = it }, contentPadding = PaddingValues(0.dp))
        }
        SettingRow("Fade in from black at the start") { Switch(checked = fadeIn, onCheckedChange = { fadeIn = it }) }
        SettingRow("Fade out to black at the end") { Switch(checked = fadeOut, onCheckedChange = { fadeOut = it }) }
        Text(
            "Fades dip through black; true cross-dissolves need multi-track editing, which is coming later. " +
                "Tap a clip to give it its own transition.",
            style = MaterialTheme.typography.bodySmall,
            color = c.textTertiary,
        )
        Spacer(Modifier.height(Spacing.l))
        GradientButton(
            "Apply to all",
            onClick = {
                onApplyAll(type, durationMs)
                onOpeningEnding(if (fadeIn) 600L else 0L, if (fadeOut) 800L else 0L)
                onDismiss()
            },
            icon = Icons.Default.Animation,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** Background music: choose a song, its volume, where it starts, fades; plus the clips' own sound level. */
@Composable
internal fun MusicSheet(
    music: MusicUi?,
    originalVolume: Float,
    onChoose: () -> Unit,
    onUpdate: (MusicSettings) -> Unit,
    onRemove: () -> Unit,
    onOriginalVolume: (Float) -> Unit,
    onDismiss: () -> Unit,
) {
    val c = CineMind.colors
    val sliderColors = SliderDefaults.colors(thumbColor = c.accent, activeTrackColor = c.accent, inactiveTrackColor = c.surfaceSunken)
    CineMindSheet(onDismiss = onDismiss, title = "Music") {
        if (music == null) {
            Text(
                "Add a song from your phone (MP3, M4A, WAV…). It plays under the whole video, loops if it's shorter, and fades in and out.",
                style = MaterialTheme.typography.bodyMedium,
                color = c.textSecondary,
            )
        } else {
            val s = music.settings
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.MusicNote, contentDescription = null, tint = c.trackMusic)
                Spacer(Modifier.width(Spacing.s))
                Column(modifier = Modifier.weight(1f)) {
                    Text(music.name, style = MaterialTheme.typography.titleSmall, color = c.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(formatTimecode(music.durationUs, 30), style = MaterialTheme.typography.labelSmall, color = c.textTertiary)
                }
            }
            var volume by remember(s.volume) { mutableFloatStateOf(s.volume) }
            Label("Music volume ${(volume * 100).toInt()}%")
            Slider(value = volume, onValueChange = { volume = it }, colors = sliderColors, onValueChangeFinished = { onUpdate(s.copy(volume = volume)) })

            val maxStart = (music.durationUs - 1_000_000).coerceAtLeast(0).toFloat()
            var start by remember(s.startOffsetUs) { mutableFloatStateOf(s.startOffsetUs.toFloat().coerceAtMost(maxStart)) }
            Label("Start the song at ${formatTimecode(start.toLong(), 30)}")
            Slider(
                value = start,
                valueRange = 0f..maxOf(maxStart, 1f),
                enabled = maxStart > 0f,
                colors = sliderColors,
                onValueChange = { start = it },
                onValueChangeFinished = { onUpdate(s.copy(startOffsetUs = start.toLong())) },
            )
            SettingRow("Fade in") { Switch(checked = s.fadeInMs > 0, onCheckedChange = { onUpdate(s.copy(fadeInMs = if (it) 1_000 else 0)) }) }
            SettingRow("Fade out") { Switch(checked = s.fadeOutMs > 0, onCheckedChange = { onUpdate(s.copy(fadeOutMs = if (it) 1_500 else 0)) }) }
        }

        var original by remember(originalVolume) { mutableFloatStateOf(originalVolume) }
        Label("Original sound of the clips ${(original * 100).toInt()}%")
        Slider(value = original, onValueChange = { original = it }, colors = sliderColors, onValueChangeFinished = { onOriginalVolume(original) })
        Text(
            "Use only music you're allowed to publish. Spotify songs can't be used in exported videos.",
            style = MaterialTheme.typography.bodySmall,
            color = c.textTertiary,
        )
        Spacer(Modifier.height(Spacing.l))
        Row(verticalAlignment = Alignment.CenterVertically) {
            GradientButton(
                if (music == null) "Choose a song" else "Replace song",
                onClick = onChoose,
                icon = Icons.Default.LibraryMusic,
                modifier = Modifier.weight(1f),
            )
            if (music != null) {
                Spacer(Modifier.width(Spacing.s))
                TextButton(onClick = onRemove) { Text("Remove", color = c.danger) }
            }
        }
    }
}

/** Main editor tools on a glass bar. Clip-specific tools appear in the inspector after tapping a clip. */
@Composable
internal fun EditorToolsBar(
    hasClips: Boolean,
    onSmartEdit: () -> Unit,
    onAddMedia: () -> Unit,
    onTransitions: () -> Unit,
    onMusic: () -> Unit,
    onExport: () -> Unit,
) {
    val c = CineMind.colors
    val shape = RoundedCornerShape(Radius.xl)
    Row(
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.m, vertical = Spacing.s)
            .clip(shape)
            .background(c.surfaceGlass)
            .border(1.dp, c.hairline, shape)
            .padding(vertical = Spacing.xs),
    ) {
        ToolButton(Icons.Default.AutoAwesome, "Smart edit", onClick = onSmartEdit, enabled = hasClips, minWidth = 56.dp)
        ToolButton(Icons.Default.AddPhotoAlternate, "Add", onClick = onAddMedia, minWidth = 56.dp)
        ToolButton(Icons.Default.Animation, "Transitions", onClick = onTransitions, enabled = hasClips, minWidth = 56.dp)
        ToolButton(Icons.Default.MusicNote, "Music", onClick = onMusic, enabled = hasClips, minWidth = 56.dp)
        ToolButton(Icons.Default.FileUpload, "Export", onClick = onExport, enabled = hasClips, minWidth = 56.dp)
    }
}
