package com.cinemind.feature.editor.timeline.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.Animation
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Switch
import androidx.compose.material3.TextButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import java.util.Locale
import com.cinemind.core.domain.audio.MusicSettings
import com.cinemind.core.domain.editing.ClipLimits
import com.cinemind.core.domain.editing.ClipTransition
import com.cinemind.core.domain.editing.TimelineEditing
import com.cinemind.core.domain.editing.TransitionMath
import com.cinemind.core.ui.theme.formatTimecode
import com.cinemind.feature.editor.timeline.preview.PreviewClip

@Composable
internal fun EditToolbar(
    canUndo: Boolean,
    canRedo: Boolean,
    hasSelection: Boolean,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(horizontal = 8.dp),
    ) {
        Text(
            text = if (hasSelection) "Editing clip" else "Tap a clip to trim, move or delete it",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .weight(1f)
                .padding(start = 8.dp),
        )
        IconButton(onClick = onUndo, enabled = canUndo) {
            Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = "Undo")
        }
        IconButton(onClick = onRedo, enabled = canRedo) {
            Icon(Icons.AutoMirrored.Filled.Redo, contentDescription = "Redo")
        }
    }
}

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
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "Clip ${index + 1} of $clipCount" + if (limits.isStill) " · photo" else "",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { onMove(-1) }, enabled = index > 0) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "Move earlier")
                }
                IconButton(onClick = { onMove(1) }, enabled = index < clipCount - 1) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Move later")
                }
                IconButton(onClick = onSplit) {
                    Icon(Icons.Default.ContentCut, contentDescription = "Split at playhead")
                }
                IconButton(onClick = onDelete) {
                    Icon(Icons.Default.Delete, contentDescription = "Remove clip from timeline", tint = MaterialTheme.colorScheme.error)
                }
                IconButton(onClick = onClose) {
                    Icon(Icons.Default.Close, contentDescription = "Done")
                }
            }
            if (limits.isStill) {
                StillLength(item = item, fps = fps, onTrim = onTrim)
            } else {
                VideoTrim(item = item, limits = limits, fps = fps, onTrim = onTrim)
            }
            TransitionPicker(
                title = if (index == 0) "Opening" else "Transition from clip $index",
                current = ClipTransition.fromName(item.clip.transitionInType),
                currentMs = item.clip.transitionInMs,
                onPick = onTransition,
            )
        }
    }
}

@Composable
private fun TransitionPicker(
    title: String,
    current: ClipTransition,
    currentMs: Long,
    onPick: (ClipTransition, Long) -> Unit,
) {
    val durationMs = if (current == ClipTransition.CUT || currentMs <= 0) 500L else currentMs
    Text(title, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 4.dp))
    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        items(ClipTransition.entries) { t ->
            FilterChip(selected = current == t, onClick = { onPick(t, durationMs) }, label = { Text(t.label) })
        }
    }
    if (current != ClipTransition.CUT) {
        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            items(TransitionMath.DURATION_OPTIONS_MS) { ms ->
                FilterChip(
                    selected = currentMs == ms,
                    onClick = { onPick(current, ms) },
                    label = { Text(String.format(Locale.ROOT, "%.1f s", ms / 1000f)) },
                )
            }
        }
    }
}

/** Sets one transition for every cut, plus optional fade-in/out at the very start and end. */
@Composable
internal fun TransitionsDialog(
    onDismiss: () -> Unit,
    onApplyAll: (ClipTransition, Long) -> Unit,
    onOpeningEnding: (Long, Long) -> Unit,
) {
    var type by remember { mutableStateOf(ClipTransition.FADE) }
    var durationMs by remember { mutableLongStateOf(500L) }
    var fadeIn by remember { mutableStateOf(true) }
    var fadeOut by remember { mutableStateOf(true) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Transitions") },
        text = {
            Column {
                Text("Between every clip", style = MaterialTheme.typography.labelLarge)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(ClipTransition.entries) { t ->
                        FilterChip(selected = type == t, onClick = { type = t }, label = { Text(t.label) })
                    }
                }
                if (type != ClipTransition.CUT) {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(TransitionMath.DURATION_OPTIONS_MS) { ms ->
                            FilterChip(
                                selected = durationMs == ms,
                                onClick = { durationMs = ms },
                                label = { Text(String.format(Locale.ROOT, "%.1f s", ms / 1000f)) },
                            )
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Fade in from black at the start", modifier = Modifier.weight(1f))
                    Switch(checked = fadeIn, onCheckedChange = { fadeIn = it })
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Fade out to black at the end", modifier = Modifier.weight(1f))
                    Switch(checked = fadeOut, onCheckedChange = { fadeOut = it })
                }
                Text(
                    "Fades dip through black; true cross-dissolves need multi-track editing, which is coming later.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onApplyAll(type, durationMs)
                onOpeningEnding(if (fadeIn) 600L else 0L, if (fadeOut) 800L else 0L)
                onDismiss()
            }) { Text("Apply to all") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Main editor tools. Clip-specific tools appear in the inspector after tapping a clip. */
@Composable
internal fun EditorToolsBar(
    hasClips: Boolean,
    onSmartEdit: () -> Unit,
    onAddMedia: () -> Unit,
    onTransitions: () -> Unit,
    onMusic: () -> Unit,
    onExport: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Row(
            horizontalArrangement = Arrangement.SpaceEvenly,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
        ) {
            ToolButton(Icons.Default.AutoAwesome, "Smart edit", enabled = hasClips, onClick = onSmartEdit)
            ToolButton(Icons.Default.AddPhotoAlternate, "Add clips", enabled = true, onClick = onAddMedia)
            ToolButton(Icons.Default.Animation, "Transitions", enabled = hasClips, onClick = onTransitions)
            ToolButton(Icons.Default.MusicNote, "Music", enabled = hasClips, onClick = onMusic)
            ToolButton(Icons.Default.FileUpload, "Export", enabled = hasClips, onClick = onExport)
        }
    }
}

@Composable
private fun ToolButton(icon: ImageVector, label: String, enabled: Boolean, onClick: () -> Unit) {
    TextButton(onClick = onClick, enabled = enabled) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, contentDescription = null)
            Text(label, style = MaterialTheme.typography.labelSmall)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VideoTrim(item: PreviewClip, limits: ClipLimits, fps: Int, onTrim: (Long, Long) -> Unit) {
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
    val clip = item.clip
    val stored = (clip.sourceOutUs - clip.sourceInUs).toFloat()
    var length by remember(clip.id, stored) { mutableFloatStateOf(stored) }

    Slider(
        value = length,
        onValueChange = { length = it },
        valueRange = TimelineEditing.MIN_CLIP_US.toFloat()..TimelineEditing.MAX_STILL_US.toFloat(),
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
    Text(text, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** Background music: choose a song, its volume, where it starts, fades; plus the clips' own sound level. */
@Composable
internal fun MusicDialog(
    music: MusicUi?,
    originalVolume: Float,
    onChoose: () -> Unit,
    onUpdate: (MusicSettings) -> Unit,
    onRemove: () -> Unit,
    onOriginalVolume: (Float) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Music") },
        text = {
            Column {
                if (music == null) {
                    Text(
                        "Add a song from your phone (MP3, M4A, WAV…). It plays under the whole video, " +
                            "loops if it's shorter, and fades in and out.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                } else {
                    val s = music.settings
                    Text(music.name, style = MaterialTheme.typography.titleSmall, maxLines = 1)
                    Text(formatTimecode(music.durationUs, 30), style = MaterialTheme.typography.labelSmall)

                    var volume by remember(s.volume) { mutableFloatStateOf(s.volume) }
                    Label("Music volume ${(volume * 100).toInt()}%")
                    Slider(value = volume, onValueChange = { volume = it }, onValueChangeFinished = { onUpdate(s.copy(volume = volume)) })

                    val maxStart = (music.durationUs - 1_000_000).coerceAtLeast(0).toFloat()
                    var start by remember(s.startOffsetUs) { mutableFloatStateOf(s.startOffsetUs.toFloat().coerceAtMost(maxStart)) }
                    Label("Start the song at ${formatTimecode(start.toLong(), 30)}")
                    Slider(
                        value = start,
                        valueRange = 0f..maxOf(maxStart, 1f),
                        enabled = maxStart > 0f,
                        onValueChange = { start = it },
                        onValueChangeFinished = { onUpdate(s.copy(startOffsetUs = start.toLong())) },
                    )

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Fade in", modifier = Modifier.weight(1f))
                        Switch(checked = s.fadeInMs > 0, onCheckedChange = { onUpdate(s.copy(fadeInMs = if (it) 1_000 else 0)) })
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Fade out", modifier = Modifier.weight(1f))
                        Switch(checked = s.fadeOutMs > 0, onCheckedChange = { onUpdate(s.copy(fadeOutMs = if (it) 1_500 else 0)) })
                    }
                }

                var original by remember(originalVolume) { mutableFloatStateOf(originalVolume) }
                Label("Original sound of the clips ${(original * 100).toInt()}%")
                Slider(value = original, onValueChange = { original = it }, onValueChangeFinished = { onOriginalVolume(original) })
                Text(
                    "Use only music you're allowed to publish. Spotify songs can't be used in exported videos.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onChoose) { Text(if (music == null) "Choose a song" else "Replace song") }
        },
        dismissButton = {
            Row {
                if (music != null) TextButton(onClick = onRemove) { Text("Remove") }
                TextButton(onClick = onDismiss) { Text("Done") }
            }
        },
    )
}
