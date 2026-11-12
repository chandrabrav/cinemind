package com.cinemind.feature.editor.timeline.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ClosedCaption
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.PictureInPicture
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cinemind.core.domain.text.LayerEntry
import com.cinemind.core.domain.text.TextKind
import com.cinemind.core.ui.components.CineMindSheet
import com.cinemind.core.ui.theme.CineMind
import com.cinemind.core.ui.theme.Radius
import com.cinemind.core.ui.theme.Spacing

/**
 * What shows above what, top first: titles and stickers, captions, picture-in-picture, the main
 * video (always at the bottom). Arrows move an item up or down; titles and captions can go above
 * or below the picture-in-picture. Preview and export follow this order.
 */
@Composable
internal fun LayersSheet(entries: List<LayerEntry>, onMove: (LayerEntry, Boolean) -> Unit, onDismiss: () -> Unit) {
    val c = CineMind.colors
    CineMindSheet(onDismiss = onDismiss, title = "Layers") {
        Text("Top of the picture first. Use the arrows to bring something forward or send it back.", style = MaterialTheme.typography.bodySmall, color = c.textSecondary)
        entries.forEachIndexed { i, e ->
            val (icon, label) = when (e) {
                is LayerEntry.Title -> Icons.Default.TextFields to ((if (e.layer.kindEnum == TextKind.STICKER) "Sticker " else "Title ") + "“" + e.layer.text.replace('\n', ' ') + "”")
                LayerEntry.Captions -> Icons.Default.ClosedCaption to "Captions"
                LayerEntry.PictureInPicture -> Icons.Default.PictureInPicture to "Picture-in-picture"
                LayerEntry.MainVideo -> Icons.Default.Movie to "Main video"
            }
            val fixed = e == LayerEntry.MainVideo || e == LayerEntry.PictureInPicture
            val shape = RoundedCornerShape(Radius.m)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .padding(top = Spacing.xs)
                    .fillMaxWidth()
                    .clip(shape)
                    .background(if (fixed) c.surface else c.surfaceSunken)
                    .border(1.dp, c.hairline, shape)
                    .padding(horizontal = Spacing.s),
            ) {
                Icon(icon, contentDescription = null, tint = if (fixed) c.textTertiary else c.accent)
                Spacer(Modifier.width(Spacing.s))
                Text(label, style = MaterialTheme.typography.bodyMedium, color = c.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                if (!fixed) {
                    IconButton(onClick = { onMove(e, true) }, enabled = i > 0) {
                        Icon(Icons.Default.KeyboardArrowUp, contentDescription = "Bring $label forward")
                    }
                    IconButton(onClick = { onMove(e, false) }, enabled = i < entries.lastIndex - 1) {
                        Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Send $label back")
                    }
                } else {
                    Text(if (e == LayerEntry.MainVideo) "always at the bottom" else "", style = MaterialTheme.typography.labelSmall, color = c.textTertiary, modifier = Modifier.padding(vertical = Spacing.m))
                }
            }
        }
    }
}
