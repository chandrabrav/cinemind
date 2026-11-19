package com.cinemind.feature.editor.timeline.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import com.cinemind.core.domain.audio.EnhanceLevel
import com.cinemind.core.domain.audio.VoiceEnhance
import com.cinemind.core.ui.components.CineMindSheet
import com.cinemind.core.ui.components.SettingRow
import com.cinemind.core.ui.theme.CineMind
import com.cinemind.core.ui.theme.Spacing

/** Voice enhancer: removes background noise from speech, on the phone, in preview and export. */
@Composable
internal fun VoiceEnhanceSheet(value: VoiceEnhance, onChange: (VoiceEnhance) -> Unit, onDismiss: () -> Unit) {
    val c = CineMind.colors
    CineMindSheet(onDismiss = onDismiss, title = "Enhance voice") {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
            Text(
                "Cleans up speech: removes hiss, hum, fans, traffic and wind rumble, and lifts quiet voices. Runs on your phone; play the preview to hear it.",
                style = MaterialTheme.typography.bodySmall,
                color = c.textSecondary,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                EnhanceLevel.entries.forEach { level ->
                    FilterChip(
                        selected = value.level == level,
                        onClick = { onChange(value.copy(level = level)) },
                        label = { Text(level.label) },
                        colors = FilterChipDefaults.filterChipColors(selectedContainerColor = c.accent, selectedLabelColor = c.onAccent),
                    )
                }
            }
            Text(value.level.hint, style = MaterialTheme.typography.bodySmall, color = c.textTertiary)
            if (value.level != EnhanceLevel.OFF) {
                SettingRow("Clips' own sound") { Switch(checked = value.onClips, onCheckedChange = { onChange(value.copy(onClips = it)) }) }
                SettingRow("Voiceover") { Switch(checked = value.onVoiceover, onCheckedChange = { onChange(value.copy(onVoiceover = it)) }) }
                Text(
                    "Music and sound effects are never changed. Strong works best on talking; for music performances keep it Off or Light.",
                    style = MaterialTheme.typography.labelSmall,
                    color = c.textTertiary,
                )
            }
        }
    }
}
