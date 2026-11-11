package com.cinemind.feature.ai.generation.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cinemind.core.domain.model.EditBrief
import com.cinemind.core.domain.model.EditBriefRules
import com.cinemind.core.domain.model.EditStyle
import com.cinemind.core.domain.model.Pacing
import com.cinemind.core.domain.model.Project
import com.cinemind.core.domain.model.ProjectFormat
import com.cinemind.core.ui.format.formatDuration

/**
 * Where the user describes the edit they want. The brief is saved with the project and is the
 * input for the on-device AI pipeline (Stage 4–5). Format changes apply to the project right away.
 */
@Composable
fun EditBriefScreen(
    projectId: String,
    viewModel: EditBriefViewModel = hiltViewModel(),
) {
    LaunchedEffect(projectId) { viewModel.load(projectId) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val project by viewModel.project.collectAsStateWithLifecycle()

    // Save pending edits when leaving the screen or when the app goes to the background.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) viewModel.flush()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            viewModel.flush()
        }
    }

    val brief = state.brief
    if (!state.loaded || brief == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 24.dp),
    ) {
        Header(project = project, saved = state.saved)

        Section("What should this video be?") {
            OutlinedTextField(
                value = brief.prompt,
                onValueChange = viewModel::setPrompt,
                placeholder = { Text("e.g. A dreamy travel reel of our Goa trip — sunsets, beach, friends laughing") },
                supportingText = {
                    Text("${brief.prompt.length} / ${EditBriefRules.MAX_PROMPT_LENGTH}")
                },
                minLines = 4,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .heightIn(min = 120.dp),
            )
            ChipRow(EditBriefRules.SUGGESTIONS) { text ->
                AssistChip(onClick = { viewModel.addSuggestion(text) }, label = { Text(text) })
            }
        }

        Section("Style") {
            ChipRow(EditStyle.entries) { style ->
                FilterChip(
                    selected = brief.style == style,
                    onClick = { viewModel.setStyle(style) },
                    label = { Text(style.label) },
                )
            }
        }

        Section("Length") {
            val options: List<Int?> = listOf(null) + EditBriefRules.DURATION_OPTIONS_SEC
            ChipRow(options) { seconds ->
                FilterChip(
                    selected = brief.targetDurationSec == seconds,
                    onClick = { viewModel.setTargetDuration(seconds) },
                    label = { Text(if (seconds == null) "Auto" else "$seconds s") },
                )
            }
        }

        Section("Pacing") {
            ChipRow(Pacing.entries) { pacing ->
                FilterChip(
                    selected = brief.pacing == pacing,
                    onClick = { viewModel.setPacing(pacing) },
                    label = { Text(pacing.label) },
                )
            }
        }

        Section("Format", hint = "Changes the project's frame now — preview and export follow it.") {
            ChipRow(ProjectFormat.entries) { format ->
                FilterChip(
                    selected = project?.aspectRatio == format.ratio,
                    onClick = { viewModel.setFormat(format) },
                    label = { Text("${format.ratio} · ${format.label}") },
                )
            }
        }

        Notices(brief = brief, footageUs = project?.durationUs ?: 0)

        GenerateCard()
    }
}

@Composable
private fun Header(project: Project?, saved: Boolean) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text("Describe your edit", style = MaterialTheme.typography.headlineSmall)
            if (project != null) {
                Text(
                    "${project.name} · ${formatDuration(project.durationUs)} of footage",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (saved) {
            Icon(Icons.Default.Check, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
            Spacer(Modifier.size(4.dp))
            Text("Saved", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            Text("Saving…", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun Section(title: String, hint: String? = null, content: @Composable () -> Unit) {
    Column(modifier = Modifier.padding(top = 12.dp)) {
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        if (hint != null) {
            Text(
                hint,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
        Spacer(Modifier.height(6.dp))
        content()
    }
}

@Composable
private fun <T> ChipRow(items: List<T>, chip: @Composable (T) -> Unit) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(items) { chip(it) }
    }
}

@Composable
private fun Notices(brief: EditBrief, footageUs: Long) {
    val notices = EditBriefRules.notices(brief, footageUs)
    if (notices.isEmpty()) return
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
        notices.forEach { text ->
            Row(verticalAlignment = Alignment.Top, modifier = Modifier.padding(vertical = 2.dp)) {
                Icon(Icons.Outlined.Info, contentDescription = null, tint = MaterialTheme.colorScheme.secondary, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(6.dp))
                Text(text, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun GenerateCard() {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = MaterialTheme.shapes.large,
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Button(onClick = {}, enabled = false, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.AutoAwesome, contentDescription = null)
                Spacer(Modifier.size(8.dp))
                Text("Create AI edit")
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "AI editing isn't available yet. Your brief is saved with this project and will be " +
                    "used once on-device AI is added. You can keep editing manually meanwhile.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
