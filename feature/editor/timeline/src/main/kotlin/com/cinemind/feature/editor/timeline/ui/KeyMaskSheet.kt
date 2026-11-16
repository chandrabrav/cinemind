package com.cinemind.feature.editor.timeline.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.cinemind.core.domain.editing.ChromaKey
import com.cinemind.core.domain.editing.ClipFraming
import com.cinemind.core.domain.editing.MaskShape
import com.cinemind.core.ui.components.CineMindSheet
import com.cinemind.core.ui.components.SettingRow
import com.cinemind.core.ui.theme.CineMind
import com.cinemind.core.ui.theme.Radius
import com.cinemind.core.ui.theme.Spacing

/**
 * Green screen and shape mask for a picture-in-picture overlay. Changes apply when a control is
 * released (one undo step each) and show in the preview after it refreshes.
 */
@Composable
internal fun KeyMaskSheet(framing: ClipFraming, startWithMask: Boolean, onChange: (ChromaKey?, MaskShape, Float) -> Unit, onDismiss: () -> Unit) {
    val c = CineMind.colors
    var chroma by remember { mutableStateOf(framing.chroma) }
    var mask by remember { mutableStateOf(framing.mask) }
    var feather by remember { mutableFloatStateOf(framing.maskFeather) }
    val apply = { onChange(chroma, mask, feather) }
    val sliders = SliderDefaults.colors(thumbColor = c.selection, activeTrackColor = c.selection, inactiveTrackColor = c.surfaceSunken)
    CineMindSheet(onDismiss = onDismiss, title = if (startWithMask) "Mask" else "Green screen") {
        if (!startWithMask) {
            SettingRow("Remove a background colour", subtitle = "For videos filmed in front of a green or blue screen") {
                Switch(checked = chroma != null, onCheckedChange = { on ->
                    chroma = if (on) ChromaKey() else null
                    apply()
                })
            }
            val k = chroma
            if (k != null) {
                Text("Colour to remove", style = MaterialTheme.typography.labelLarge, color = c.textSecondary)
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s), modifier = Modifier.padding(vertical = Spacing.xs)) {
                    listOf(ChromaKey.GREEN to "Green", ChromaKey.BLUE to "Blue", 0xFFFFFFFF.toInt() to "White", 0xFF000000.toInt() to "Black").forEach { (col, name) ->
                        Box(
                            Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(Color(col))
                                .border(if (k.color == col) 3.dp else 1.dp, if (k.color == col) c.selection else c.hairline, CircleShape)
                                .clickable { chroma = k.copy(color = col); apply() }
                                .semantics { contentDescription = "Remove $name" },
                        )
                    }
                }
                var strength by remember(k.color) { mutableFloatStateOf(k.strength) }
                Text("Strength ${(strength * 100).toInt()}%", style = MaterialTheme.typography.labelLarge, color = c.textSecondary)
                Slider(value = strength, onValueChange = { strength = it }, onValueChangeFinished = { chroma = chroma?.copy(strength = strength); apply() }, colors = sliders)
                var soft by remember(k.color) { mutableFloatStateOf(k.softness) }
                Text("Soft edge ${(soft * 100).toInt()}%", style = MaterialTheme.typography.labelLarge, color = c.textSecondary)
                Slider(value = soft, onValueChange = { soft = it }, onValueChangeFinished = { chroma = chroma?.copy(softness = soft); apply() }, colors = sliders)
                Text(
                    "Tip: raise the strength until the background is gone; add soft edge if hair looks cut out.",
                    style = MaterialTheme.typography.bodySmall,
                    color = c.textTertiary,
                )
            }
        } else {
            Text("Shape", style = MaterialTheme.typography.labelLarge, color = c.textSecondary)
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xs), modifier = Modifier.horizontalScroll(rememberScrollState()).padding(vertical = Spacing.xs)) {
                MaskShape.entries.forEach { s ->
                    val sel = s == mask
                    val shape = RoundedCornerShape(Radius.xl)
                    Text(
                        s.label,
                        style = MaterialTheme.typography.labelLarge,
                        color = if (sel) c.onAccent else c.textPrimary,
                        modifier = Modifier
                            .clip(shape)
                            .background(if (sel) c.accent else c.surfaceSunken)
                            .border(1.dp, if (sel) c.accent else c.hairline, shape)
                            .clickable { mask = s; apply() }
                            .padding(horizontal = Spacing.m, vertical = Spacing.xs),
                    )
                }
            }
            if (mask != MaskShape.NONE) {
                Text("Soft edge ${(feather * 100).toInt()}%", style = MaterialTheme.typography.labelLarge, color = c.textSecondary)
                Slider(value = feather, onValueChange = { feather = it }, onValueChangeFinished = apply, valueRange = 0f..0.3f, colors = sliders)
            }
        }
    }
}
