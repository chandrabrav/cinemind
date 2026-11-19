package com.cinemind.feature.editor.timeline.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cinemind.core.ui.components.CineMindSheet
import com.cinemind.core.ui.components.GlassCard
import com.cinemind.core.ui.components.GradientButton
import com.cinemind.core.ui.components.SettingRow
import com.cinemind.core.ui.theme.CineMind
import com.cinemind.core.ui.theme.Spacing

/**
 * One-tap creator edits: cut the pauses out of talking clips (jump cuts), zoom in on emphatic
 * moments, and reframe every clip around its subject. Each is one undo step.
 */
@Composable
internal fun CreatorToolsSheet(viewModel: TimelineViewModel, onDismiss: () -> Unit) {
    val c = CineMind.colors
    val busy by viewModel.autoBusy.collectAsStateWithLifecycle()
    var pause by remember { mutableFloatStateOf(0.5f) }
    var zoom by remember { mutableStateOf(true) }

    CineMindSheet(onDismiss = onDismiss, title = "Auto edit") {
        Column(
            modifier = Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(Spacing.m),
        ) {
            busy?.let {
                GlassCard(modifier = Modifier.fillMaxWidth()) {
                    Column(verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
                        Text(it, style = MaterialTheme.typography.bodyMedium, color = c.textPrimary)
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        Text("You can leave the app; it keeps going.", style = MaterialTheme.typography.labelSmall, color = c.textTertiary)
                    }
                }
            }

            Text("Talking videos", style = MaterialTheme.typography.titleSmall, color = c.textPrimary)
            Text(
                "Cuts out the pauses between sentences so the video moves fast, like a pro talking-head edit. Words are never clipped; music-only clips are left alone.",
                style = MaterialTheme.typography.bodySmall,
                color = c.textSecondary,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Pauses longer than", style = MaterialTheme.typography.bodySmall, color = c.textSecondary, modifier = Modifier.width(120.dp))
                Slider(value = pause, onValueChange = { pause = it }, valueRange = 0.3f..1.5f, modifier = Modifier.weight(1f))
                Text("%.1f s".format(java.util.Locale.ROOT, pause), style = MaterialTheme.typography.labelSmall, color = c.textTertiary, modifier = Modifier.width(44.dp))
            }
            SettingRow("Zoom between cuts", subtitle = "Every other piece slightly closer, so jump cuts feel deliberate") {
                Switch(checked = zoom, onCheckedChange = { zoom = it })
            }
            GradientButton(
                "Remove pauses",
                onClick = { viewModel.removeSilences((pause * 1000).toLong(), zoom) },
                enabled = busy == null,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedButton(onClick = viewModel::addPunchIns, enabled = busy == null, modifier = Modifier.fillMaxWidth()) {
                Text("Punch in on emphasis")
            }
            Text(
                "Quick zoom-ins where your voice is loudest and most emphatic (clips without their own motion).",
                style = MaterialTheme.typography.labelSmall,
                color = c.textTertiary,
            )

            Text("Reframe", style = MaterialTheme.typography.titleSmall, color = c.textPrimary)
            Text(
                "Fits every clip to this project's shape around the person or subject: a landscape clip becomes a sharp vertical shot that keeps them in frame. If they move a lot, the clip is split into shots. The AI cutout is made on your phone first.",
                style = MaterialTheme.typography.bodySmall,
                color = c.textSecondary,
            )
            GradientButton("Auto-reframe", onClick = viewModel::autoReframe, enabled = busy == null, modifier = Modifier.fillMaxWidth())
        }
    }
}
