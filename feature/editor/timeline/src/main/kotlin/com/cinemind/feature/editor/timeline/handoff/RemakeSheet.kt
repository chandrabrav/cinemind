package com.cinemind.feature.editor.timeline.handoff

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cinemind.core.domain.aigen.GenerationJob
import com.cinemind.core.domain.aigen.JobStatus
import com.cinemind.core.ui.components.CineMindSheet
import com.cinemind.core.ui.components.GlassCard
import com.cinemind.core.ui.components.GradientButton
import com.cinemind.core.ui.components.StatusPill
import com.cinemind.core.ui.components.Tone
import com.cinemind.core.ui.theme.CineMind
import com.cinemind.core.ui.theme.Spacing
import java.text.DateFormat
import java.util.Date

/**
 * "Remake with KATANA": recreate a reference video with the user's own subject. CineMind prepares
 * the brief and media and opens Claude, where Higgsfield's KATANA skill runs in the user's own
 * account. When the video is ready the user imports it here and it joins the timeline.
 */
@Composable
fun RemakeSheet(
    projectId: String,
    aspect: String?,
    onImported: (Uri) -> Unit,
    onOpenSettings: () -> Unit,
    onDismiss: () -> Unit,
    viewModel: RemakeViewModel = hiltViewModel(),
) {
    val c = CineMind.colors
    val draft by viewModel.draft.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val allowed by viewModel.allowed.collectAsStateWithLifecycle()
    val jobs by viewModel.projectJobs.collectAsStateWithLifecycle()
    val provider = viewModel.provider
    var importFor by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(projectId, aspect) { viewModel.bind(projectId, aspect) }

    val referencePicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri?.let(viewModel::setReference)
    }
    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(provider.maxPhotos)) { uris ->
        if (uris.isNotEmpty()) viewModel.addPhotos(uris)
    }
    val resultPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        val job = importFor
        importFor = null
        if (uri != null && job != null) viewModel.importResult(job, uri, onImported)
    }

    CineMindSheet(onDismiss = onDismiss, title = "Remake with KATANA") {
        Column(
            modifier = Modifier.heightIn(max = 620.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(Spacing.m),
        ) {
            Text(
                "Recreate a reference video with your own person, product or outfit. It's made in ${provider.appName} by ${provider.name}, then you bring the result back here.",
                style = MaterialTheme.typography.bodyMedium,
                color = c.textSecondary,
            )
            GlassCard(modifier = Modifier.fillMaxWidth()) {
                Text(provider.costNote, style = MaterialTheme.typography.bodySmall, color = c.textTertiary)
            }

            if (!allowed) {
                GlassCard(modifier = Modifier.fillMaxWidth()) {
                    Column(verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
                        StatusPill("Offline mode", Tone.Warning)
                        Text("Nothing leaves this phone in Offline mode. Switch to Hybrid in AI models → Cloud to send a remake.", style = MaterialTheme.typography.bodySmall, color = c.textSecondary)
                        TextButton(onClick = onOpenSettings) { Text("Open AI models") }
                    }
                }
            }

            // 1. Reference
            Text("1  Reference video", style = MaterialTheme.typography.titleSmall, color = c.textPrimary)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
                OutlinedButton(onClick = { referencePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)) }) {
                    Text(if (draft.reference == null) "Pick video" else "Change")
                }
                if (draft.reference != null) {
                    Text(
                        draft.referenceName + (draft.referenceUs?.let { " · %.1f s".format(it / 1_000_000.0) } ?: ""),
                        style = MaterialTheme.typography.bodySmall,
                        color = c.textSecondary,
                        maxLines = 1,
                    )
                }
            }
            Text("Use a video you made or have permission to use.", style = MaterialTheme.typography.labelSmall, color = c.textTertiary)

            // 2. Photos
            Text("2  Subject photos (up to ${provider.maxPhotos})", style = MaterialTheme.typography.titleSmall, color = c.textPrimary)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
                OutlinedButton(
                    onClick = { photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                    enabled = draft.photos.size < provider.maxPhotos,
                ) { Text("Add photos") }
                Text("${draft.photos.size} added", style = MaterialTheme.typography.bodySmall, color = c.textSecondary)
                if (draft.photos.isNotEmpty()) TextButton(onClick = { draft.photos.forEach(viewModel::removePhoto) }) { Text("Clear") }
            }

            // 3. Instructions
            Text("3  What to keep or change", style = MaterialTheme.typography.titleSmall, color = c.textPrimary)
            OutlinedTextField(
                value = draft.instructions,
                onValueChange = viewModel::setInstructions,
                placeholder = { Text("e.g. Keep the camera moves and timing, put me in the red jacket, sunset light") },
                minLines = 2,
                maxLines = 5,
                modifier = Modifier.fillMaxWidth(),
            )

            val brief = viewModel.brief()
            GradientButton(
                text = if (busy) "Preparing…" else "Send to ${provider.appName}",
                onClick = viewModel::send,
                enabled = allowed && !busy && brief.ready,
                modifier = Modifier.fillMaxWidth(),
            )
            if (!brief.ready && draft.reference != null) {
                Text(brief.problems.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = c.textTertiary)
            }
            TextButton(onClick = viewModel::openPage, enabled = allowed) { Text("Or open the KATANA page in the browser") }
            Text(
                "CineMind copies only the files you picked and sends them to ${provider.appName}. It can't see what happens there. When your video is ready, download it and tap Import result below.",
                style = MaterialTheme.typography.labelSmall,
                color = c.textTertiary,
            )

            message?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = c.accent)
                LaunchedEffect(it) { kotlinx.coroutines.delay(6_000); viewModel.clearMessage() }
            }

            if (jobs.isNotEmpty()) {
                Spacer(Modifier.height(Spacing.s))
                Text("Remakes for this project", style = MaterialTheme.typography.titleSmall, color = c.textPrimary)
                jobs.take(10).forEach { job ->
                    JobRow(
                        job = job,
                        onImport = {
                            importFor = job.id
                            resultPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
                        },
                        onCancel = { viewModel.cancel(job.id) },
                    )
                }
            }
        }
    }
}

@Composable
private fun JobRow(job: GenerationJob, onImport: () -> Unit, onCancel: () -> Unit) {
    val c = CineMind.colors
    val sent = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(job.createdAt))
    GlassCard(modifier = Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(sent, style = MaterialTheme.typography.bodySmall, color = c.textSecondary, modifier = Modifier.weight(1f))
                when (job.statusEnum) {
                    JobStatus.HANDED_OFF, JobStatus.PREPARED -> StatusPill("Waiting for your result", Tone.Accent)
                    JobStatus.IMPORTED -> StatusPill("Imported", Tone.Success)
                    JobStatus.CANCELLED -> StatusPill("Discarded", Tone.Neutral)
                    JobStatus.FAILED -> StatusPill("Failed", Tone.Danger)
                }
            }
            if (job.instructions.isNotBlank()) {
                Text(job.instructions, style = MaterialTheme.typography.bodySmall, color = c.textTertiary, maxLines = 2)
            }
            if (job.statusEnum == JobStatus.HANDED_OFF) {
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
                    OutlinedButton(onClick = onImport) { Text("Import result") }
                    TextButton(onClick = onCancel) { Text("Discard") }
                }
            }
        }
    }
}
