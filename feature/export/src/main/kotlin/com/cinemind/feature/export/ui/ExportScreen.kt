package com.cinemind.feature.export.ui

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cinemind.core.domain.model.Project
import com.cinemind.core.ui.format.formatDuration
import com.cinemind.feature.export.engine.ExportState
import com.cinemind.feature.export.plan.ExportPlanner
import com.cinemind.feature.export.plan.ExportQuality
import com.cinemind.feature.export.plan.ExportResolution
import com.cinemind.feature.export.plan.ExportSettings
import java.util.Locale

@Composable
fun ExportScreen(
    projectId: String,
    viewModel: ExportViewModel = hiltViewModel(),
) {
    LaunchedEffect(projectId) { viewModel.load(projectId) }
    val project by viewModel.project.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val exportState by viewModel.exportState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // Ask for notification permission (Android 13+) so progress shows while the app is in the
    // background. Export starts either way.
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        viewModel.startExport()
    }
    val startExport = {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            viewModel.startExport()
        }
    }

    val p = project ?: return
    val state = exportState
    val otherProjectBusy = state.isActive && state.projectId != projectId
    val mine = if (state.projectId == projectId) state else ExportState.Idle

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 24.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Export video", style = MaterialTheme.typography.headlineSmall)
            Text(
                "${p.name} · ${formatDuration(p.durationUs)} · ${p.aspectRatio}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        when (mine) {
            is ExportState.Preparing, is ExportState.Running, is ExportState.Saving ->
                ProgressCard(mine, onCancel = viewModel::cancel)
            is ExportState.Success -> SuccessCard(mine, context, onDone = viewModel::dismissResult)
            is ExportState.Failed -> MessageCard(
                title = "Export failed",
                text = mine.message,
                isError = true,
                actionLabel = "Back to settings",
                onAction = viewModel::dismissResult,
            )
            is ExportState.Cancelled -> MessageCard(
                title = "Export cancelled",
                text = "Nothing was saved.",
                isError = false,
                actionLabel = "Back to settings",
                onAction = viewModel::dismissResult,
            )
            ExportState.Idle -> SettingsPanel(
                project = p,
                settings = settings,
                estimateText = estimateText(viewModel, p, settings),
                busy = otherProjectBusy,
                onResolution = viewModel::setResolution,
                onFrameRate = viewModel::setFrameRate,
                onQuality = viewModel::setQuality,
                onExport = startExport,
            )
        }
    }
}

private fun estimateText(viewModel: ExportViewModel, project: Project, settings: ExportSettings): String {
    val e = viewModel.estimate(project, settings)
    val mb = e.sizeBytes / (1024.0 * 1024.0)
    val mbps = e.videoBitrateBps / 1_000_000.0
    return String.format(Locale.ROOT, "%d×%d · H.264 · %.1f Mbps · about %.0f MB", e.size.width, e.size.height, mbps, mb)
}

@Composable
private fun SettingsPanel(
    project: Project,
    settings: ExportSettings,
    estimateText: String,
    busy: Boolean,
    onResolution: (ExportResolution) -> Unit,
    onFrameRate: (Int) -> Unit,
    onQuality: (ExportQuality) -> Unit,
    onExport: () -> Unit,
) {
    Section("Resolution") {
        ChipRow(ExportResolution.entries) { r ->
            FilterChip(selected = settings.resolution == r, onClick = { onResolution(r) }, label = { Text(r.label) })
        }
    }
    Section("Frame rate") {
        ChipRow(ExportPlanner.FRAME_RATES) { fps ->
            FilterChip(selected = settings.frameRate == fps, onClick = { onFrameRate(fps) }, label = { Text("$fps fps") })
        }
    }
    Section("Quality") {
        ChipRow(ExportQuality.entries) { q ->
            FilterChip(selected = settings.quality == q, onClick = { onQuality(q) }, label = { Text(q.label) })
        }
    }

    Column(modifier = Modifier.padding(16.dp)) {
        Text(estimateText, style = MaterialTheme.typography.bodyMedium)
        Text(
            "Saved to Movies/CineMind as MP4 (H.264 + AAC, SDR). Clips are fitted into the " +
                "${project.aspectRatio} frame. Higher frame rates than the source don't add smoothness.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (settings.resolution == ExportResolution.P2160) {
            Text(
                "4K exports take much longer and fall back to a lower resolution if the phone's encoder can't handle it.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.secondary,
            )
        }
        Spacer(Modifier.height(16.dp))
        Button(
            onClick = onExport,
            enabled = !busy && project.durationUs > 0,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(Icons.Default.FileUpload, contentDescription = null)
            Spacer(Modifier.size(8.dp))
            Text("Export")
        }
        if (busy) {
            Text(
                "Another project is exporting. Wait for it to finish.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ProgressCard(state: ExportState, onCancel: () -> Unit) {
    val (label, progress) = when (state) {
        is ExportState.Running -> "Rendering" to state.progress
        is ExportState.Saving -> "Saving to Movies" to null
        else -> "Preparing" to null
    }
    Card {
        Text(
            if (progress != null) "$label… ${(progress * 100).toInt()}%" else "$label…",
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(Modifier.height(12.dp))
        if (progress != null) {
            LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
        } else {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "You can leave this screen; the export continues and shows progress in a notification.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        OutlinedButton(onClick = onCancel, enabled = state !is ExportState.Saving) { Text("Cancel") }
    }
}

@Composable
private fun SuccessCard(state: ExportState.Success, context: Context, onDone: () -> Unit) {
    Card {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.size(8.dp))
            Text("Video exported", style = MaterialTheme.typography.titleMedium)
        }
        Spacer(Modifier.height(8.dp))
        Text(state.displayName, style = MaterialTheme.typography.bodyMedium)
        Text(
            "Movies/CineMind · ${formatDuration(state.durationMs * 1_000)} · " +
                String.format(Locale.ROOT, "%.1f MB", state.sizeBytes / (1024.0 * 1024.0)),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { play(context, state.uri) }) {
                Icon(Icons.Default.PlayArrow, contentDescription = null)
                Spacer(Modifier.size(6.dp))
                Text("Play")
            }
            OutlinedButton(onClick = { share(context, state.uri) }) {
                Icon(Icons.Default.Share, contentDescription = null)
                Spacer(Modifier.size(6.dp))
                Text("Share")
            }
            TextButton(onClick = onDone) { Text("Done") }
        }
    }
}

@Composable
private fun MessageCard(title: String, text: String, isError: Boolean, actionLabel: String, onAction: () -> Unit) {
    Card {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (isError) {
                Icon(Icons.Default.ErrorOutline, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                Spacer(Modifier.size(8.dp))
            }
            Text(title, style = MaterialTheme.typography.titleMedium)
        }
        Spacer(Modifier.height(8.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(12.dp))
        OutlinedButton(onClick = onAction) { Text(actionLabel) }
    }
}

@Composable
private fun Card(content: @Composable () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = MaterialTheme.shapes.large,
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) { content() }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(modifier = Modifier.padding(top = 12.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(horizontal = 16.dp))
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

private fun play(context: Context, uri: Uri) {
    val intent = Intent(Intent.ACTION_VIEW)
        .setDataAndType(uri, "video/mp4")
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    try {
        context.startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        // No video player installed; the file is still in Movies/CineMind.
    }
}

private fun share(context: Context, uri: Uri) {
    val send = Intent(Intent.ACTION_SEND)
        .setType("video/mp4")
        .putExtra(Intent.EXTRA_STREAM, uri)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    context.startActivity(Intent.createChooser(send, "Share video"))
}
