package com.cinemind.feature.project.ui

import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.outlined.VideoLibrary
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.cinemind.core.domain.model.ProjectNames
import com.cinemind.core.domain.model.ProjectSummary
import com.cinemind.core.ui.components.EmptyState
import com.cinemind.core.ui.components.GlassCard
import com.cinemind.core.ui.components.GradientButton
import com.cinemind.core.ui.components.ScreenHeader
import com.cinemind.core.ui.components.StatusPill
import com.cinemind.core.ui.format.formatDuration
import com.cinemind.core.ui.theme.CineMind
import com.cinemind.core.ui.theme.Radius
import com.cinemind.core.ui.theme.Spacing

@Composable
fun ProjectListScreen(
    onNewProject: () -> Unit,
    onOpenProject: (String) -> Unit,
    viewModel: ProjectListViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var renaming by remember { mutableStateOf<ProjectSummary?>(null) }
    var deleting by remember { mutableStateOf<ProjectSummary?>(null) }

    LaunchedEffect(viewModel) {
        viewModel.messageFlow.collect { snackbar.showSnackbar(it) }
    }

    Scaffold(
        // The app-level Scaffold handles system-bar insets and draws the backdrop.
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        containerColor = Color.Transparent,
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        val projects = state.projects
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            when {
                projects == null -> CircularProgressIndicator(
                    color = CineMind.colors.accent,
                    modifier = Modifier.align(Alignment.Center),
                )
                projects.isEmpty() -> EmptyState(
                    icon = Icons.Outlined.VideoLibrary,
                    title = "Your first story starts here",
                    body = "Import videos and photos, describe the reel you want, and CineMind builds an editable project.",
                ) {
                    GradientButton("New project", onClick = onNewProject, icon = Icons.Default.Add)
                }
                else -> LazyColumn(
                    contentPadding = PaddingValues(bottom = Spacing.xxl),
                    verticalArrangement = Arrangement.spacedBy(Spacing.m),
                ) {
                    item {
                        ScreenHeader(
                            title = "Your stories",
                            subtitle = "${projects.size} project${if (projects.size == 1) "" else "s"}",
                        )
                    }
                    item { BetaCard() }
                    item { NewProjectCard(onNewProject) }
                    items(projects, key = { it.project.id }) { summary ->
                        ProjectCard(
                            summary = summary,
                            onOpen = { onOpenProject(summary.project.id) },
                            onRename = { renaming = summary },
                            onDuplicate = { viewModel.duplicate(summary) },
                            onDelete = { deleting = summary },
                        )
                    }
                }
            }
        }
    }

    renaming?.let { target ->
        RenameDialog(
            initial = target.project.name,
            onDismiss = { renaming = null },
            onConfirm = { name ->
                viewModel.rename(target.project.id, name)
                renaming = null
            },
        )
    }

    deleting?.let { target ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            containerColor = CineMind.colors.surfaceRaised,
            title = { Text("Delete project?") },
            text = {
                Text(
                    "“${target.project.name}” and its edits will be removed. " +
                        "Your original videos and photos are not deleted.",
                    color = CineMind.colors.textSecondary,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.delete(target)
                    deleting = null
                }) { Text("Delete", color = CineMind.colors.danger) }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } },
        )
    }
}

/** Prominent entry point for a new edit, styled with the accent gradient. */
@Composable
private fun NewProjectCard(onClick: () -> Unit) {
    val c = CineMind.colors
    GlassCard(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.gutter),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(52.dp)
                    .clip(RoundedCornerShape(Radius.m))
                    .background(Brush.linearGradient(listOf(c.accentGradientStart, c.accentGradientEnd))),
            ) {
                Icon(Icons.Default.Add, contentDescription = null, tint = c.onAccentGradient)
            }
            Spacer(Modifier.width(Spacing.m))
            Column(modifier = Modifier.weight(1f)) {
                Text("New project", style = MaterialTheme.typography.titleMedium, color = c.textPrimary)
                Text("Import footage, then edit by hand or with Smart edit", style = MaterialTheme.typography.bodySmall, color = c.textTertiary)
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = c.textTertiary)
        }
    }
}

@Composable
private fun ProjectCard(
    summary: ProjectSummary,
    onOpen: () -> Unit,
    onRename: () -> Unit,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit,
) {
    val c = CineMind.colors
    val project = summary.project
    var menuOpen by remember { mutableStateOf(false) }

    GlassCard(
        onClick = onOpen,
        contentPadding = PaddingValues(Spacing.s),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.gutter),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(84.dp)
                    .clip(RoundedCornerShape(Radius.m))
                    .background(c.surfaceSunken),
            ) {
                if (summary.coverUri != null) {
                    AsyncImage(
                        model = summary.coverUri,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Icon(Icons.Default.Movie, contentDescription = null, tint = c.textTertiary)
                }
            }

            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = Spacing.m),
                verticalArrangement = Arrangement.spacedBy(Spacing.xxs),
            ) {
                Text(
                    project.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = c.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "${summary.clipCount} clips · ${formatDuration(project.durationUs)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = c.textSecondary,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StatusPill(project.aspectRatio)
                    Spacer(Modifier.width(Spacing.s))
                    Text(
                        "Edited " + DateUtils.getRelativeTimeSpanString(project.updatedAt, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS),
                        style = MaterialTheme.typography.labelSmall,
                        color = c.textTertiary,
                        maxLines = 1,
                    )
                }
            }

            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Default.MoreVert, contentDescription = "Project options", tint = c.textSecondary)
                }
                DropdownMenu(
                    expanded = menuOpen,
                    onDismissRequest = { menuOpen = false },
                    containerColor = c.surfaceRaised,
                    shape = RoundedCornerShape(Radius.m),
                ) {
                    DropdownMenuItem(
                        text = { Text("Rename") },
                        leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) },
                        onClick = { menuOpen = false; onRename() },
                    )
                    DropdownMenuItem(
                        text = { Text("Duplicate") },
                        leadingIcon = { Icon(Icons.Default.ContentCopy, contentDescription = null) },
                        onClick = { menuOpen = false; onDuplicate() },
                    )
                    DropdownMenuItem(
                        text = { Text("Delete", color = c.danger) },
                        leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null, tint = c.danger) },
                        onClick = { menuOpen = false; onDelete() },
                    )
                }
            }
        }
    }
}

@Composable
private fun RenameDialog(initial: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var text by rememberSaveable { mutableStateOf(initial) }
    val valid = ProjectNames.sanitize(text) != null
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = CineMind.colors.surfaceRaised,
        title = { Text("Rename project") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { if (it.length <= ProjectNames.MAX_LENGTH) text = it },
                singleLine = true,
                isError = !valid,
                shape = RoundedCornerShape(Radius.m),
                supportingText = { if (!valid) Text("Enter a name") },
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(text) }, enabled = valid) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
