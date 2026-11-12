package com.cinemind.core.ui.components

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.cinemind.core.ui.theme.CineMind
import com.cinemind.core.ui.theme.Spacing

/** Beta feedback: a quick rating, a note, and whether to attach Diagnostics; then the share sheet. */
@Composable
fun FeedbackSheet(onSend: (note: String, mood: String?, includeDiagnostics: Boolean) -> Unit, onDismiss: () -> Unit) {
    val c = CineMind.colors
    var note by remember { mutableStateOf("") }
    var mood by remember { mutableStateOf<String?>(null) }
    var diagnostics by remember { mutableStateOf(true) }
    CineMindSheet(onDismiss = onDismiss, title = "Send feedback") {
        Text("How is it so far?", style = MaterialTheme.typography.labelLarge, color = c.textSecondary)
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xs), modifier = Modifier.horizontalScroll(rememberScrollState())) {
            listOf("😍 Love it", "🙂 Good", "😐 Okay", "😕 Confusing", "🐞 Broken").forEach { m ->
                Pill(text = m, selected = mood == m, onClick = { mood = if (mood == m) null else m })
            }
        }
        Spacer(Modifier.height(Spacing.s))
        OutlinedTextField(
            value = note,
            onValueChange = { note = it.take(2_000) },
            label = { Text("What did you try? What was great or went wrong?") },
            modifier = Modifier.fillMaxWidth(),
            minLines = 3,
            maxLines = 8,
        )
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = Spacing.xs)) {
            Checkbox(checked = diagnostics, onCheckedChange = { diagnostics = it })
            Column(Modifier.weight(1f)) {
                Text("Attach Diagnostics", style = MaterialTheme.typography.bodyMedium, color = c.textPrimary)
                Text("Crash and export logs, phone model and memory. No videos or project content.", style = MaterialTheme.typography.bodySmall, color = c.textTertiary)
            }
        }
        Spacer(Modifier.height(Spacing.m))
        GradientButton("Choose how to send", onClick = { onSend(note, mood, diagnostics); onDismiss() }, modifier = Modifier.fillMaxWidth())
        TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text("Not now") }
    }
}
