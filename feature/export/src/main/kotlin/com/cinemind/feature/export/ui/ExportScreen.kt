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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import com.cinemind.core.ui.components.ChoicePills
import com.cinemind.core.ui.components.GlassCard
import com.cinemind.core.ui.components.GradientButton
import com.cinemind.core.ui.components.ScreenHeader
import com.cinemind.core.ui.components.SectionHeader
import com.cinemind.core.ui.components.StatusPill
import com.cinemind.core.ui.components.Tone
import com.cinemind.core.ui.format.formatDuration
import com.cinemind.core.ui.theme.CineMind
import com.cinemind.core.ui.theme.Spacing
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
            .padding(bottom = Spacing.xxl),
    ) {
        ScreenHeader(
            title = "Export video",
            subtitle = "${p.name} · ${formatDuration(p.durationUs)} · ${p.aspectRatio}",
        )

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
    val c = CineMind.colors
    SectionHeader("Resolution")
    ChoicePills(ExportResolution.entries, settings.resolution, { it.label }, onResolution)
    SectionHeader("Frame rate")
    ChoicePills(ExportPlanner.FRAME_RATES, settings.frameRate, { "$it fps" }, onFrameRate)
    SectionHeader("Quality")
    ChoicePills(ExportQuality.entries, settings.quality, { it.label }, onQuality)

    GlassCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.gutter, vertical = Spacing.l),
    ) {
        Text(estimateText, style = MaterialTheme.typography.titleSmall, color = c.textPrimary)
        Spacer(Modifier.height(Spacing.xs))
        Text(
            "Saved to Movies/CineMind as MP4 (H.264 + AAC, SDR). Clips are fitted into the " +
                "${project.aspectRatio} frame. Higher frame rates than the source don't add smoothness.",
            style = MaterialTheme.typography.bodySmall,
            color = c.textTertiary,
        )
        if (settings.resolution == ExportResolution.P2160) {
            Spacer(Modifier.height(Spacing.s))
            StatusPill("4K takes longer and may fall back to a lower resolution", Tone.Warning)
        }
        Spacer(Modifier.height(Spacing.l))
        GradientButton(
            "Export",
            onClick = onExport,
            icon = Icons.Default.FileUpload,
            enabled = !busy && project.durationUs > 0,
            modifier = Modifier.fillMaxWidth(),
        )
        if (busy) {
            Spacer(Modifier.height(Spacing.s))
            Text("Another project is exporting. Wait for it to finish.", style = MaterialTheme.typography.bodySmall, color = c.textTertiary)
        }
    }
}

@Composable
private fun ProgressCard(state: ExportState, onCancel: () -> Unit) {
    val c = CineMind.colors
    val (label, progress) = when (state) {
        is ExportState.Running -> "Rendering" to state.progress
        is ExportState.Saving -> "Saving to Movies" to null
        else -> "Preparing" to null
    }
    GlassCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.gutter),
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.size(132.dp)) {
                if (progress != null) {
                    CircularProgressIndicator(
                        progress = { progress },
                        color = c.accent,
                        trackColor = c.surfaceSunken,
                        strokeWidth = 8.dp,
                        modifier = Modifier.fillMaxSize(),
                    )
                    Text("${(progress * 100).toInt()}%", style = MaterialTheme.typography.headlineMedium, color = c.textPrimary)
                } else {
                    CircularProgressIndicator(color = c.accent, trackColor = c.surfaceSunken, strokeWidth = 8.dp, modifier = Modifier.fillMaxSize())
                }
            }
            Spacer(Modifier.height(Spacing.l))
            Text("$label…", style = MaterialTheme.typography.titleMedium, color = c.textPrimary)
            Spacer(Modifier.height(Spacing.xs))
            Text(
                "You can leave this screen; the export continues and shows progress in a notification.",
                style = MaterialTheme.typography.bodySmall,
                color = c.textTertiary,
            )
            Spacer(Modifier.height(Spacing.l))
            OutlinedButton(onClick = onCancel, enabled = state !is ExportState.Saving) { Text("Cancel", color = c.textSecondary) }
        }
    }
}

@Composable
private fun SuccessCard(state: ExportState.Success, context: Context, onDone: () -> Unit) {
    val c = CineMind.colors
    GlassCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.gutter),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.CheckCircle, contentDescription = null, tint = c.success, modifier = Modifier.size(28.dp))
            Spacer(Modifier.width(Spacing.s))
            Text("Video exported", style = MaterialTheme.typography.titleLarge, color = c.textPrimary)
        }
        Spacer(Modifier.height(Spacing.s))
        Text(state.displayName, style = MaterialTheme.typography.bodyMedium, color = c.textPrimary)
        Text(
            "Movies/CineMind · ${formatDuration(state.durationMs * 1_000)} · " +
                String.format(Locale.ROOT, "%.1f MB", state.sizeBytes / (1024.0 * 1024.0)),
            style = MaterialTheme.typography.bodySmall,
            color = c.textTertiary,
        )
        Spacer(Modifier.height(Spacing.l))
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s), verticalAlignment = Alignment.CenterVertically) {
            GradientButton("Play", onClick = { play(context, state.uri) }, icon = Icons.Default.PlayArrow)
            OutlinedButton(onClick = { share(context, state.uri) }) {
                Icon(Icons.Default.Share, contentDescription = null, tint = c.accent)
                Spacer(Modifier.size(6.dp))
                Text("Share", color = c.accent)
            }
            TextButton(onClick = onDone) { Text("Done", color = c.textSecondary) }
        }
    }
}

@Composable
private fun MessageCard(title: String, text: String, isError: Boolean, actionLabel: String, onAction: () -> Unit) {
    val c = CineMind.colors
    GlassCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.gutter),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (isError) {
                Icon(Icons.Default.ErrorOutline, contentDescription = null, tint = c.danger)
                Spacer(Modifier.width(Spacing.s))
            }
            Text(title, style = MaterialTheme.typography.titleMedium, color = c.textPrimary)
        }
        Spacer(Modifier.height(Spacing.s))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = c.textSecondary)
        Spacer(Modifier.height(Spacing.m))
        OutlinedButton(onClick = onAction) { Text(actionLabel, color = c.textSecondary) }
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
