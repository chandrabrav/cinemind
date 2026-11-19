package com.cinemind.feature.editor.timeline.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cinemind.core.domain.editing.SubjectLook
import com.cinemind.core.ui.components.CineMindSheet
import com.cinemind.core.ui.components.GlassCard
import com.cinemind.core.ui.components.GradientButton
import com.cinemind.core.ui.components.StatusPill
import com.cinemind.core.ui.components.Tone
import com.cinemind.core.ui.theme.CineMind
import com.cinemind.core.ui.theme.Spacing

/**
 * AI subject cutout for the selected clip: made on the phone, then used for background looks
 * (remove, blur, colour pop, spotlight, outline) and for text behind the person.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun CutoutSheet(viewModel: TimelineViewModel, onDismiss: () -> Unit) {
    val c = CineMind.colors
    val progress by viewModel.cutout.collectAsStateWithLifecycle()
    // Re-read the target whenever a cutout finishes or a look changes.
    var tick by remember { mutableIntStateOf(0) }
    val target = remember(progress, tick) { viewModel.cutoutTarget() }
    var behindText by remember { mutableStateOf("") }

    CineMindSheet(onDismiss = onDismiss, title = "Cutout") {
        Column(
            modifier = Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(Spacing.m),
        ) {
            if (target == null) {
                Text("Select a video or photo clip first.", style = MaterialTheme.typography.bodyMedium, color = c.textSecondary)
                return@Column
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
                Text(
                    (if (target.overlay) "Overlay: " else "Clip: ") + target.asset.displayName.ifBlank { if (target.asset.isVideo) "video" else "photo" },
                    style = MaterialTheme.typography.bodyMedium,
                    color = c.textPrimary,
                    maxLines = 1,
                    modifier = Modifier.weight(1f),
                )
                StatusPill(if (target.ready) "Cutout ready" else "No cutout yet", if (target.ready) Tone.Success else Tone.Neutral)
            }

            val p = progress
            if (p != null) {
                GlassCard(modifier = Modifier.fillMaxWidth()) {
                    Column(verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
                        Text(p.label, style = MaterialTheme.typography.bodyMedium, color = c.textPrimary)
                        LinearProgressIndicator(progress = { p.fraction }, modifier = Modifier.fillMaxWidth())
                        Text("You can leave the app; it keeps going.", style = MaterialTheme.typography.labelSmall, color = c.textTertiary)
                        TextButton(onClick = viewModel::cancelCutout) { Text("Stop") }
                    }
                }
            } else if (!target.ready) {
                Text(
                    "AI finds the person or main subject in every frame, right here on your phone. Nothing is uploaded. A minute of video takes about a minute.",
                    style = MaterialTheme.typography.bodySmall,
                    color = c.textSecondary,
                )
                GradientButton("Cut out subject", onClick = { viewModel.makeCutout(target) }, modifier = Modifier.fillMaxWidth())
            }

            Text("Background", style = MaterialTheme.typography.titleSmall, color = c.textPrimary)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.xs), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                SubjectLook.entries.forEach { look ->
                    LookChip(look.label, selected = target.look == look, enabled = p == null) {
                        viewModel.setSubjectLook(target, look)
                        tick++
                    }
                }
            }
            Text(
                SubjectLook.entries.first { it == target.look }.hint +
                    if (target.look == SubjectLook.CUTOUT && !target.overlay) ". On the main track the background turns black; put this clip on an overlay to place the person over another video." else "",
                style = MaterialTheme.typography.bodySmall,
                color = c.textTertiary,
            )

            if (!target.overlay) {
                Text("Text behind the person", style = MaterialTheme.typography.titleSmall, color = c.textPrimary)
                OutlinedTextField(
                    value = behindText,
                    onValueChange = { behindText = it.take(60) },
                    placeholder = { Text("e.g. GOA") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                GradientButton(
                    "Add behind the person",
                    onClick = {
                        viewModel.addTextBehindSubject(behindText.ifBlank { "YOUR TEXT" })
                        behindText = ""
                        onDismiss()
                    },
                    enabled = p == null,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "Any title can go behind the person: Text → Colour & effects → Behind the person.",
                    style = MaterialTheme.typography.labelSmall,
                    color = c.textTertiary,
                )
            }

            if (target.ready && p == null) {
                TextButton(onClick = { viewModel.makeCutout(target); tick++ }) { Text("Redo cutout") }
            }
        }
    }
}

@Composable
private fun LookChip(label: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val c = CineMind.colors
    androidx.compose.material3.FilterChip(
        selected = selected,
        onClick = onClick,
        enabled = enabled,
        label = { Text(label) },
        colors = androidx.compose.material3.FilterChipDefaults.filterChipColors(selectedContainerColor = c.accent, selectedLabelColor = c.onAccent),
    )
}
