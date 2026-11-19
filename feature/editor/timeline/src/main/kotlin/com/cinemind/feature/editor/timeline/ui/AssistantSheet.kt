package com.cinemind.feature.editor.timeline.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cinemind.core.domain.assistant.Assistant
import com.cinemind.core.ui.components.CineMindSheet
import com.cinemind.core.ui.components.GradientButton
import com.cinemind.core.ui.theme.CineMind
import com.cinemind.core.ui.theme.Radius
import com.cinemind.core.ui.theme.Spacing

private val IDEAS = listOf(
    "Make it feel cinematic",
    "Add a title for the start",
    "Speed it up to fit the music",
    "Warm sunset colours",
    "Add a whoosh between clips",
)

/**
 * The editing assistant: describe a change; it proposes edits that you review and apply (each a
 * normal undo step). Runs on a cloud AI with your own key (AI models → Cloud) or on this phone.
 */
@Composable
internal fun AssistantSheet(
    viewModel: TimelineViewModel,
    onOpenSettings: () -> Unit,
    onMusic: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val c = CineMind.colors
    val turns by viewModel.assistantTurns.collectAsStateWithLifecycle()
    val busy by viewModel.assistantBusy.collectAsStateWithLifecycle()
    var text by remember { mutableStateOf("") }
    val list = rememberLazyListState()
    LaunchedEffect(turns.size, busy) { if (turns.isNotEmpty()) list.animateScrollToItem(turns.size - 1) }

    fun send(s: String) {
        viewModel.askAssistant(s)
        text = ""
    }

    CineMindSheet(onDismiss = onDismiss, title = "Assistant") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Using ${viewModel.assistantEngine}", style = MaterialTheme.typography.labelMedium, color = c.textTertiary, modifier = Modifier.weight(1f))
            TextButton(onClick = onOpenSettings) { Text("AI models") }
        }
        if (turns.isEmpty()) {
            Text(
                "Tell me what you'd like, e.g. “make it punchier with a title”. I'll suggest edits; nothing changes until you tap Apply.",
                style = MaterialTheme.typography.bodySmall,
                color = c.textSecondary,
            )
            Spacer(Modifier.height(Spacing.s))
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xs), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                IDEAS.forEach { idea ->
                    Text(
                        idea,
                        style = MaterialTheme.typography.labelLarge,
                        color = c.textPrimary,
                        modifier = Modifier
                            .clip(RoundedCornerShape(Radius.xl))
                            .background(c.surfaceRaised)
                            .clickable(enabled = !busy) { send(idea) }
                            .padding(horizontal = Spacing.m, vertical = Spacing.s),
                    )
                }
            }
        } else {
            LazyColumn(state = list, modifier = Modifier.fillMaxWidth().heightIn(max = 380.dp), verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
                itemsIndexed(turns) { i, t ->
                    Column(verticalArrangement = Arrangement.spacedBy(Spacing.xxs)) {
                        Text(
                            t.request,
                            style = MaterialTheme.typography.bodyMedium,
                            color = c.textPrimary,
                            modifier = Modifier
                                .align(Alignment.End)
                                .clip(RoundedCornerShape(Radius.l))
                                .background(c.accent.copy(alpha = 0.18f))
                                .padding(horizontal = Spacing.m, vertical = Spacing.s),
                        )
                        when {
                            t.error != null -> Text(t.error, style = MaterialTheme.typography.bodySmall, color = c.danger)
                            t.reply == null -> Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                Spacer(Modifier.size(Spacing.s))
                                Text("Thinking…", style = MaterialTheme.typography.bodySmall, color = c.textTertiary)
                            }
                            else -> {
                                Text(t.reply, style = MaterialTheme.typography.bodyMedium, color = c.textSecondary)
                                t.actions.forEach { a ->
                                    Text("• " + Assistant.summary(a), style = MaterialTheme.typography.bodySmall, color = c.textPrimary)
                                }
                                if (t.actions.isNotEmpty()) {
                                    if (t.applied) {
                                        Text("Applied — use Undo to take any of it back.", style = MaterialTheme.typography.labelSmall, color = c.success)
                                    } else {
                                        GradientButton(
                                            text = "Apply ${t.actions.size} edit" + if (t.actions.size == 1) "" else "s",
                                            onClick = { viewModel.applyAssistant(i, onMusic) },
                                            modifier = Modifier.fillMaxWidth(),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(Spacing.s))
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it.take(1_000) },
                placeholder = { Text("Ask for a change…") },
                maxLines = 3,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { send(text) }, enabled = text.isNotBlank() && !busy) {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send", tint = if (text.isNotBlank() && !busy) c.accent else c.textTertiary)
            }
        }
    }
}
