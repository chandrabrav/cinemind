package com.cinemind.feature.editor.timeline.ui

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.cinemind.core.ui.components.CineMindSheet
import com.cinemind.core.ui.components.GradientButton
import com.cinemind.core.ui.theme.CineMind
import com.cinemind.core.ui.theme.Radius
import com.cinemind.core.ui.theme.Spacing
import com.cinemind.feature.editor.timeline.voiceover.TtsVoice
import java.util.Locale

/**
 * Voice from text: the phone's own text-to-speech reads what you type; the result goes on the
 * voiceover track at the playhead. Works offline with the phone's installed voices.
 */
@Composable
internal fun TtsSheet(
    loadVoices: suspend () -> List<TtsVoice>,
    onAdd: (text: String, voice: String?, rate: Float, pitch: Float, done: (String?) -> Unit) -> Unit,
    onDismiss: () -> Unit,
) {
    val c = CineMind.colors
    val context = LocalContext.current
    var voices by remember { mutableStateOf<List<TtsVoice>?>(null) }
    var voice by remember { mutableStateOf<String?>(null) }
    var text by remember { mutableStateOf("") }
    var rate by remember { mutableFloatStateOf(1f) }
    var pitch by remember { mutableFloatStateOf(1f) }
    var busy by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        val v = loadVoices()
        voices = v
        voice = v.firstOrNull { it.locale.country == "IN" && it.offline }?.name ?: v.firstOrNull()?.name
    }
    CineMindSheet(onDismiss = onDismiss, title = "Voice from text") {
        Text("Your phone reads the text aloud; it's added as a voiceover at the playhead.", style = MaterialTheme.typography.bodySmall, color = c.textSecondary)
        Spacer(Modifier.height(Spacing.s))
        OutlinedTextField(
            value = text,
            onValueChange = { text = it.take(3_900) },
            label = { Text("Text to speak") },
            minLines = 3,
            maxLines = 8,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(Spacing.s))
        Text("Voice", style = MaterialTheme.typography.labelLarge, color = c.textSecondary)
        val vs = voices
        when {
            vs == null -> Text("Loading the phone's voices…", style = MaterialTheme.typography.bodySmall, color = c.textTertiary)
            vs.isEmpty() -> Text(
                "No voices found. Install voices in Android Settings → Accessibility → Text-to-speech.",
                style = MaterialTheme.typography.bodySmall,
                color = c.textTertiary,
            )
            else -> Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xs), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                vs.forEach { v ->
                    val sel = v.name == voice
                    val shape = RoundedCornerShape(Radius.xl)
                    Text(
                        v.label,
                        style = MaterialTheme.typography.labelLarge,
                        color = if (sel) c.onAccent else c.textPrimary,
                        modifier = Modifier
                            .clip(shape)
                            .background(if (sel) c.accent else c.surfaceSunken)
                            .border(1.dp, if (sel) c.accent else c.hairline, shape)
                            .clickable { voice = v.name }
                            .padding(horizontal = Spacing.m, vertical = Spacing.xs),
                    )
                }
            }
        }
        val sliders = SliderDefaults.colors(thumbColor = c.selection, activeTrackColor = c.selection, inactiveTrackColor = c.surfaceSunken)
        Text("Speed ${String.format(Locale.ROOT, "%.2f", rate)}×", style = MaterialTheme.typography.labelLarge, color = c.textSecondary, modifier = Modifier.padding(top = Spacing.s))
        Slider(value = rate, onValueChange = { rate = it }, valueRange = 0.5f..2f, colors = sliders)
        Text("Pitch ${String.format(Locale.ROOT, "%.2f", pitch)}", style = MaterialTheme.typography.labelLarge, color = c.textSecondary)
        Slider(value = pitch, onValueChange = { pitch = it }, valueRange = 0.5f..2f, colors = sliders)
        Spacer(Modifier.height(Spacing.m))
        GradientButton(
            if (busy) "Making the voice…" else "Add at the playhead",
            onClick = {
                busy = true
                onAdd(text, voice, rate, pitch) { err ->
                    busy = false
                    if (err == null) onDismiss() else Toast.makeText(context, err, Toast.LENGTH_LONG).show()
                }
            },
            enabled = !busy && text.isNotBlank(),
            icon = Icons.Default.RecordVoiceOver,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
