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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.cinemind.core.domain.editing.ClipLimits
import com.cinemind.core.domain.editing.TimelineEditing
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
    onClose: () -> Unit,
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
