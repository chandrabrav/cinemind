package com.cinemind.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.cinemind.core.domain.agents.AgentRegistry
import com.cinemind.core.domain.agents.RuntimeKind
import com.cinemind.core.domain.agents.TaskStatus
import com.cinemind.core.ui.components.GlassCard
import com.cinemind.core.ui.components.ScreenHeader
import com.cinemind.core.ui.components.SectionHeader
import com.cinemind.core.ui.components.StatusPill
import com.cinemind.core.ui.components.Tone
import com.cinemind.core.ui.theme.CineMind
import com.cinemind.core.ui.theme.Spacing
import com.cinemind.feature.ai.llm.ondevice.AgentSwitches
import com.cinemind.feature.ai.llm.ondevice.TraceStore
import java.text.DateFormat
import java.util.Date

/**
 * Developer diagnostics for the multi-agent system: the agent registry (with on/off switches)
 * and the execution traces of recent AI edits. Model names appear only here.
 */
@Composable
fun AgentDiagnosticsScreen() {
    val context = LocalContext.current
    val c = CineMind.colors
    var refresh by remember { mutableIntStateOf(0) }
    val disabled = remember(refresh) { AgentSwitches.disabled(context) }
    val traces = remember(refresh) { TraceStore.traces(context) }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = Spacing.xl),
        verticalArrangement = Arrangement.spacedBy(Spacing.s),
    ) {
        ScreenHeader(title = "Diagnostics", subtitle = "For developers · agents, models and recent AI runs")

        SectionHeader("Agents")
        AgentRegistry.ALL.forEach { a ->
            GlassCard(modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.gutter)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("${a.id.label} · v${a.version}", style = MaterialTheme.typography.titleSmall, color = c.textPrimary)
                        Text(
                            a.capabilities.joinToString { it.name.lowercase().replace('_', ' ') } +
                                (if (a.needs.isEmpty()) " · no model" else " · needs " + a.needs.joinToString { it.name.lowercase().replace('_', ' ') }) +
                                (if (a.cloudSafe) " · cloud allowed" else ""),
                            style = MaterialTheme.typography.labelSmall,
                            color = c.textTertiary,
                        )
                    }
                    Switch(checked = a.id !in disabled, onCheckedChange = { on -> AgentSwitches.set(context, a.id, on); refresh++ })
                }
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = Spacing.gutter)) {
            Text("Recent AI runs", style = MaterialTheme.typography.titleSmall, color = c.textPrimary, modifier = Modifier.weight(1f))
            if (traces.isNotEmpty()) TextButton(onClick = { TraceStore.clear(context); refresh++ }) { Text("Clear") }
        }
        if (traces.isEmpty()) {
            Text("Run a Smart edit to see which agents and models it used.", style = MaterialTheme.typography.bodySmall, color = c.textTertiary, modifier = Modifier.padding(horizontal = Spacing.gutter))
        }
        traces.forEach { t ->
            GlassCard(modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.gutter)) {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.xxs)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(t.startedMs)), style = MaterialTheme.typography.labelMedium, color = c.textSecondary, modifier = Modifier.weight(1f))
                        StatusPill(if (t.succeeded) "OK" else "Had failures", if (t.succeeded) Tone.Success else Tone.Warning)
                    }
                    if (t.request.isNotBlank()) Text("“${t.request}”", style = MaterialTheme.typography.bodySmall, color = c.textPrimary, maxLines = 2)
                    t.entries.forEach { e ->
                        val where = when (e.runtime) {
                            RuntimeKind.DETERMINISTIC -> "rules"
                            RuntimeKind.ON_DEVICE -> "phone · ${e.modelId}"
                            RuntimeKind.CLOUD -> "cloud · ${e.modelId}"
                        }
                        val mark = when (e.status) {
                            TaskStatus.SUCCEEDED -> "✓"
                            TaskStatus.SKIPPED -> "–"
                            else -> "✕"
                        }
                        Text(
                            "$mark ${e.agent.label} · $where · ${e.durationMs} ms" + (if (e.attempt > 1) " · try ${e.attempt}" else "") +
                                (if (e.memoryMb >= 0) " · ${e.memoryMb} MB free" else "") + (if (e.note.isNotBlank()) " · ${e.note}" else ""),
                            style = MaterialTheme.typography.labelSmall,
                            color = if (e.status == TaskStatus.FAILED || e.status == TaskStatus.TIMED_OUT) c.danger else c.textSecondary,
                        )
                    }
                    if (t.planned.isNotEmpty()) {
                        Text("Plan: " + t.planned.joinToString(" | "), style = MaterialTheme.typography.labelSmall, color = c.textTertiary, maxLines = 6)
                    }
                }
            }
        }
    }
}
