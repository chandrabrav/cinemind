package com.cinemind.feature.editor.timeline.ui

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.cinemind.core.domain.audio.SfxSynth
import com.cinemind.core.domain.audio.SoundEffect
import com.cinemind.core.ui.components.CineMindSheet
import com.cinemind.core.ui.components.GradientButton
import com.cinemind.core.ui.format.formatShortTime
import com.cinemind.core.ui.theme.CineMind
import com.cinemind.core.ui.theme.Radius
import com.cinemind.core.ui.theme.Spacing
import com.cinemind.feature.editor.timeline.preview.PreviewClip
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Plays a built-in sound once (for choosing); one at a time. */
private class SoundAuditioner {
    private var track: AudioTrack? = null

    suspend fun play(effect: SoundEffect) {
        val pcm = withContext(Dispatchers.Default) {
            val s = SfxSynth.render(effect)
            ShortArray(s.size) { (s[it].coerceIn(-1f, 1f) * 32767).toInt().toShort() }
        }
        stop()
        val t = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
            .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(SfxSynth.SAMPLE_RATE).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setTransferMode(AudioTrack.MODE_STATIC)
            .setBufferSizeInBytes(pcm.size * 2)
            .build()
        t.write(pcm, 0, pcm.size)
        t.play()
        track = t
    }

    fun stop() {
        track?.let { runCatching { it.stop() }; it.release() }
        track = null
    }
}

/**
 * Sound effects: CineMind's own sounds (made on the phone, free to use) grouped by kind — tap ▶
 * to hear one, + to put it at the playhead — or a sound file from the phone.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SoundsSheet(onAdd: (SoundEffect) -> Unit, onImport: () -> Unit, onDismiss: () -> Unit) {
    val c = CineMind.colors
    val scope = rememberCoroutineScope()
    val player = remember { SoundAuditioner() }
    DisposableEffect(Unit) { onDispose { player.stop() } }
    CineMindSheet(onDismiss = onDismiss, title = "Sound effects") {
        Text(
            "Made by CineMind on your phone — free to use in anything you publish. Added at the playhead on a sound track.",
            style = MaterialTheme.typography.bodySmall,
            color = c.textSecondary,
        )
        SoundEffect.entries.groupBy { it.category }.forEach { (category, items) ->
            Text(category, style = MaterialTheme.typography.labelLarge, color = c.textSecondary, modifier = Modifier.padding(top = Spacing.m, bottom = Spacing.xs))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.xs), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                items.forEach { e ->
                    val shape = RoundedCornerShape(Radius.m)
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .clip(shape)
                            .background(c.surfaceSunken)
                            .border(1.dp, c.hairline, shape),
                    ) {
                        IconButton(onClick = { scope.launch { player.play(e) } }, modifier = Modifier.semantics { contentDescription = "Play ${e.label}" }) {
                            Icon(Icons.Default.PlayArrow, contentDescription = null, tint = c.accent)
                        }
                        Text(e.label, style = MaterialTheme.typography.bodyMedium, color = c.textPrimary)
                        IconButton(onClick = { player.stop(); onAdd(e) }, modifier = Modifier.semantics { contentDescription = "Add ${e.label}" }) {
                            Icon(Icons.Default.Add, contentDescription = null, tint = c.textPrimary)
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(Spacing.l))
        GradientButton("Use a sound from my phone", onClick = onImport, icon = Icons.Default.FileOpen, modifier = Modifier.fillMaxWidth())
        Text(
            "Use only sounds you're allowed to publish.",
            style = MaterialTheme.typography.bodySmall,
            color = c.textTertiary,
            modifier = Modifier.padding(top = Spacing.xs),
        )
    }
}

/** Bar shown while a sound effect is selected: volume, nudge, fades, delete. */
@Composable
internal fun SoundInspectorBar(
    item: PreviewClip,
    onVolume: (Float) -> Unit,
    onMove: (Long) -> Unit,
    onFades: (Long, Long) -> Unit,
    onDelete: () -> Unit,
    onClose: () -> Unit,
) {
    val c = CineMind.colors
    val clip = item.clip
    val shape = RoundedCornerShape(Radius.l)
    var vol by remember(clip.opacity) { mutableFloatStateOf(clip.opacity) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.s, vertical = Spacing.xxs)
            .clip(shape)
            .background(c.surfaceGlass)
            .border(1.dp, c.hairline, shape)
            .padding(horizontal = Spacing.s, vertical = Spacing.xs),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                (item.asset?.displayName?.ifEmpty { null } ?: "Sound") + " · " + formatShortTime(clip.timelineOutUs - clip.timelineInUs),
                style = MaterialTheme.typography.bodyMedium,
                color = c.textPrimary,
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onDelete) { Text("Delete", color = c.danger) }
            TextButton(onClick = onClose) { Text("Done") }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Volume ${(vol * 100).toInt()}%", style = MaterialTheme.typography.labelSmall, color = c.textSecondary, modifier = Modifier.width(92.dp))
            Slider(
                value = vol,
                onValueChange = { vol = it },
                onValueChangeFinished = { onVolume(vol) },
                valueRange = 0f..2f,
                colors = SliderDefaults.colors(thumbColor = c.selection, activeTrackColor = c.selection, inactiveTrackColor = c.surfaceSunken),
                modifier = Modifier.weight(1f).semantics { contentDescription = "Sound volume" },
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            TextButton(onClick = { onMove(-100_000) }) { Text("◀ 0.1 s") }
            TextButton(onClick = { onMove(100_000) }) { Text("0.1 s ▶") }
            val faded = clip.transitionInMs > 0 || clip.transitionOutMs > 0
            TextButton(onClick = { if (faded) onFades(0, 0) else onFades(150, 300) }) { Text(if (faded) "No fades" else "Fade in/out") }
        }
    }
}
