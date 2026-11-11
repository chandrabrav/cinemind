package com.cinemind.feature.editor.timeline.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.cinemind.core.domain.audio.VoiceoverSettings
import com.cinemind.core.ui.components.CineMindSheet
import com.cinemind.core.ui.components.GradientButton
import com.cinemind.core.ui.components.SettingRow
import com.cinemind.core.ui.format.formatShortTime
import com.cinemind.core.ui.theme.CineMind
import com.cinemind.core.ui.theme.Radius
import com.cinemind.core.ui.theme.Spacing
import com.cinemind.feature.editor.timeline.preview.PreviewClip
import kotlinx.coroutines.delay

/**
 * Records narration over the video: 3-2-1 countdown, the video plays silently from the playhead,
 * live input waveform, pause/resume, stop; then listen, keep (goes on the Voiceover track where
 * recording began) or discard. Nothing is added until the take is kept.
 */
@Composable
internal fun VoiceoverSheet(
    state: RecordingUi,
    onStart: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onStop: () -> Unit,
    onListen: () -> Unit,
    onStopListening: () -> Unit,
    onAccept: () -> Unit,
    onDiscard: () -> Unit,
    onDismiss: () -> Unit,
) {
    val c = CineMind.colors
    var countdown by remember { mutableIntStateOf(0) }
    LaunchedEffect(countdown) {
        if (countdown > 0) {
            delay(1_000)
            if (countdown == 1) onStart()
            countdown -= 1
        }
    }
    CineMindSheet(onDismiss = onDismiss, title = "Voiceover") {
        Text(
            when (state.phase) {
                RecordingUi.Phase.IDLE -> if (countdown > 0) "Get ready…" else "Records from the playhead while the video plays without sound. Use headphones for the best result."
                RecordingUi.Phase.RECORDING -> "Recording at ${formatShortTime(state.startUs)} on the timeline"
                RecordingUi.Phase.PAUSED -> "Paused"
                RecordingUi.Phase.REVIEW -> "Listen to the take, then keep it or try again."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = c.textSecondary,
        )
        Spacer(Modifier.height(Spacing.m))
        Waveform(state.levels, active = state.phase == RecordingUi.Phase.RECORDING)
        Spacer(Modifier.height(Spacing.s))
        Text(
            if (countdown > 0) "$countdown" else formatShortTime(state.elapsedMs * 1_000),
            style = MaterialTheme.typography.headlineMedium,
            color = if (state.phase == RecordingUi.Phase.RECORDING) c.danger else c.textPrimary,
            modifier = Modifier.align(Alignment.CenterHorizontally),
        )
        state.error?.let { Text(it, color = c.danger, style = MaterialTheme.typography.bodySmall) }
        Spacer(Modifier.height(Spacing.m))
        Row(
            horizontalArrangement = Arrangement.spacedBy(Spacing.m, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            when (state.phase) {
                RecordingUi.Phase.IDLE -> RoundAction(Icons.Default.Mic, "Start recording", c.danger, enabled = countdown == 0) { countdown = 3 }
                RecordingUi.Phase.RECORDING -> {
                    RoundAction(Icons.Default.Pause, "Pause recording", c.surfaceSunken, iconTint = c.textPrimary) { onPause() }
                    RoundAction(Icons.Default.Stop, "Stop recording", c.danger) { onStop() }
                }
                RecordingUi.Phase.PAUSED -> {
                    RoundAction(Icons.Default.Mic, "Resume recording", c.danger) { onResume() }
                    RoundAction(Icons.Default.Stop, "Stop recording", c.surfaceSunken, iconTint = c.textPrimary) { onStop() }
                }
                RecordingUi.Phase.REVIEW -> {
                    RoundAction(if (state.listening) Icons.Default.Stop else Icons.Default.PlayArrow, if (state.listening) "Stop listening" else "Listen to the take", c.surfaceSunken, iconTint = c.textPrimary) {
                        if (state.listening) onStopListening() else onListen()
                    }
                    RoundAction(Icons.Default.Close, "Discard the take", c.surfaceSunken, iconTint = c.danger) { onDiscard() }
                    RoundAction(Icons.Default.Check, "Keep the take", c.success) { onAccept() }
                }
            }
        }
        if (state.phase == RecordingUi.Phase.REVIEW) {
            Spacer(Modifier.height(Spacing.s))
            GradientButton("Keep and add to the timeline", onClick = onAccept, modifier = Modifier.fillMaxWidth())
        }
        Text(
            "The recording starts slightly after you tap (the phone needs a moment); nudge it on the timeline if your words land late.",
            style = MaterialTheme.typography.bodySmall,
            color = c.textTertiary,
            modifier = Modifier.padding(top = Spacing.m),
        )
    }
}

@Composable
private fun RoundAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    background: androidx.compose.ui.graphics.Color,
    iconTint: androidx.compose.ui.graphics.Color = CineMind.colors.onAccent,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .size(64.dp)
            .clip(CircleShape)
            .background(background),
    ) {
        Icon(icon, contentDescription = label, tint = iconTint, modifier = Modifier.size(30.dp))
    }
}

/** Recent input levels as centred bars (newest on the right). */
@Composable
private fun Waveform(levels: List<Float>, active: Boolean) {
    val c = CineMind.colors
    val color = if (active) c.danger else c.accent
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(72.dp)
            .clip(RoundedCornerShape(Radius.m))
            .background(c.surfaceSunken)
            .semantics { contentDescription = "Microphone level" },
    ) {
        val bars = 90
        val step = size.width / bars
        val mid = size.height / 2
        val offset = bars - levels.size
        levels.forEachIndexed { i, level ->
            val h = (0.06f + level * 0.94f) * size.height * 0.9f
            val x = (offset + i) * step + step / 2
            drawLine(color, Offset(x, mid - h / 2), Offset(x, mid + h / 2), strokeWidth = step * 0.55f, cap = StrokeCap.Round)
        }
    }
}

/** Selected voiceover clip: timing, fades, track volume / music ducking / mute, delete. */
@Composable
internal fun VoiceInspector(
    item: PreviewClip,
    settings: VoiceoverSettings,
    onMove: (Long) -> Unit,
    onTrim: (Long, Long) -> Unit,
    onFades: (Long, Long) -> Unit,
    onSettings: (VoiceoverSettings) -> Unit,
    onDelete: () -> Unit,
    onClose: () -> Unit,
) {
    val c = CineMind.colors
    val clip = item.clip
    val shape = RoundedCornerShape(Radius.l)
    var volume by remember(settings) { mutableFloatStateOf(settings.volume) }
    var duck by remember(settings) { mutableFloatStateOf(settings.duckMusic) }
    val sliderColors = SliderDefaults.colors(thumbColor = c.selection, activeTrackColor = c.selection, inactiveTrackColor = c.surfaceSunken)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.s)
            .clip(shape)
            .background(c.surfaceGlass)
            .border(1.dp, c.hairline, shape)
            .padding(horizontal = Spacing.m, vertical = Spacing.s),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "${item.asset?.displayName?.ifEmpty { null } ?: "Voiceover"} · ${formatShortTime(clip.timelineInUs)}–${formatShortTime(clip.timelineOutUs)}",
                style = MaterialTheme.typography.titleSmall,
                color = c.textPrimary,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, contentDescription = "Delete voiceover", tint = c.danger) }
            IconButton(onClick = onClose) { Icon(Icons.Default.Close, contentDescription = "Close voiceover settings", tint = c.textSecondary) }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { onMove(-100_000) }) { Text("◀ 0.1 s", color = c.textPrimary) }
            TextButton(onClick = { onMove(100_000) }) { Text("0.1 s ▶", color = c.textPrimary) }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { onTrim(100_000, 0) }) { Text("Trim start", color = c.textPrimary) }
            TextButton(onClick = { onTrim(0, -100_000) }) { Text("Trim end", color = c.textPrimary) }
        }
        SettingRow("Fade in") { Switch(checked = clip.transitionInMs > 0, onCheckedChange = { onFades(if (it) 300 else 0, clip.transitionOutMs) }) }
        SettingRow("Fade out") { Switch(checked = clip.transitionOutMs > 0, onCheckedChange = { onFades(clip.transitionInMs, if (it) 300 else 0) }) }
        Text("Voice volume ${(volume * 100).toInt()}%", style = MaterialTheme.typography.labelMedium, color = c.textSecondary)
        Slider(value = volume, valueRange = 0f..2f, onValueChange = { volume = it }, onValueChangeFinished = { onSettings(settings.copy(volume = volume)) }, colors = sliderColors)
        Text("Lower the music while you speak: ${(duck * 100).toInt()}%", style = MaterialTheme.typography.labelMedium, color = c.textSecondary)
        Slider(value = duck, onValueChange = { duck = it }, onValueChangeFinished = { onSettings(settings.copy(duckMusic = duck)) }, colors = sliderColors)
        SettingRow("Mute all voiceovers") { Switch(checked = settings.muted, onCheckedChange = { onSettings(settings.copy(muted = it)) }) }
        Box(Modifier.width(1.dp))
    }
}
