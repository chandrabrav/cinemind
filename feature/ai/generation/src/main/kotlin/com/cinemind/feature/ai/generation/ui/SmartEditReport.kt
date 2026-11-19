package com.cinemind.feature.ai.generation.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.cinemind.core.domain.aiedit.StepStatus
import com.cinemind.core.ui.theme.CineMind
import com.cinemind.feature.ai.generation.smartedit.SmartEditResult

/**
 * What the smart edit actually did, step by step: applied (✓), adjusted to fit the engine or
 * the footage (~) and skipped (✕) — plus questions the AI couldn't answer without guessing.
 */
@Composable
internal fun SmartEditReport(result: SmartEditResult, onOpen: () -> Unit) {
    AlertDialog(
        onDismissRequest = onOpen,
        confirmButton = { TextButton(onClick = onOpen) { Text("Open the edit") } },
        title = { Text(if (result.usedAi) "AI edit ready" else "Smart edit ready") },
        text = {
            Column(
                Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text("Planned by ${result.engine}. Your original project is unchanged; everything here stays editable.",
                    style = MaterialTheme.typography.bodySmall, color = CineMind.colors.textSecondary)
                if (result.summary.isNotBlank()) Text(result.summary, style = MaterialTheme.typography.bodyMedium)
                result.steps.groupBy { it.area }.forEach { (area, steps) ->
                    Text(area, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 4.dp))
                    steps.forEach { s ->
                        Row {
                            Text(
                                when (s.status) { StepStatus.APPLIED -> "✓"; StepStatus.ADJUSTED -> "~"; StepStatus.SKIPPED -> "✕" },
                                color = when (s.status) {
                                    StepStatus.APPLIED -> CineMind.colors.accent
                                    StepStatus.ADJUSTED -> CineMind.colors.textSecondary
                                    StepStatus.SKIPPED -> MaterialTheme.colorScheme.error
                                },
                                modifier = Modifier.width(18.dp),
                            )
                            Text(s.text, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                val why = result.decisions.drop(if (result.summary.isNotBlank()) 1 else 0)
                if (why.isNotEmpty()) {
                    Text("Why", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 4.dp))
                    why.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
                }
                if (result.questions.isNotEmpty()) {
                    Text("Needs your input", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 4.dp))
                    result.questions.forEach { Text("? $it", style = MaterialTheme.typography.bodySmall) }
                }
                if (result.howMade.isNotEmpty()) {
                    Text("How this was made", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 4.dp))
                    result.howMade.forEach { Text("· $it", style = MaterialTheme.typography.bodySmall, color = CineMind.colors.textSecondary) }
                }
            }
        },
    )
}
