package com.cinemind.feature.media.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.outlined.AddPhotoAlternate
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import coil.request.ImageRequest
import coil.request.videoFrameMillis
import com.cinemind.core.domain.model.MediaAsset
import com.cinemind.core.ui.components.EmptyState
import com.cinemind.core.ui.components.GradientButton
import com.cinemind.core.ui.components.ScreenHeader
import com.cinemind.core.ui.components.hapticClick
import com.cinemind.core.ui.components.pressScale
import com.cinemind.core.ui.format.formatDuration
import com.cinemind.core.ui.theme.CineMind
import com.cinemind.core.ui.theme.Radius
import com.cinemind.core.ui.theme.Spacing

private const val MAX_PICK = 50

@Composable
fun MediaLibraryScreen(
    onProjectCreated: (String) -> Unit,
    viewModel: MediaLibraryViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }

    // System Photo Picker: no storage permission needed; falls back to the document picker on old devices.
    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(maxItems = MAX_PICK),
    ) { uris -> viewModel.importMedia(uris) }
    val launchPicker = {
        picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
    }

    LaunchedEffect(viewModel) {
        viewModel.eventFlow.collect { event ->
            when (event) {
                is MediaLibraryEvent.ProjectCreated -> onProjectCreated(event.projectId)
                is MediaLibraryEvent.Message -> snackbar.showSnackbar(event.text)
            }
        }
    }

    Scaffold(
        // The app-level Scaffold handles system-bar insets and draws the backdrop.
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        containerColor = Color.Transparent,
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            AnimatedVisibility(
                visible = state.selection.isNotEmpty(),
                enter = fadeIn() + slideInVertically { it / 2 },
                exit = fadeOut() + slideOutVertically { it / 2 },
            ) {
                SelectionBar(
                    count = state.selection.size,
                    busy = state.creatingProject,
                    onClear = viewModel::clearSelection,
                    onCreate = viewModel::createProject,
                )
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            if (state.importing) {
                LinearProgressIndicator(
                    color = CineMind.colors.accent,
                    trackColor = CineMind.colors.surfaceSunken,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (state.assets.isEmpty()) {
                EmptyState(
                    icon = Icons.Outlined.AddPhotoAlternate,
                    title = "Start with your footage",
                    body = "Pick videos and photos from your gallery. Originals are never moved or changed.",
                ) {
                    GradientButton("Import media", onClick = launchPicker, icon = Icons.Default.Add, enabled = !state.importing)
                }
            } else {
                ScreenHeader(
                    title = "Choose clips",
                    subtitle = "Tap them in the order you want them",
                ) {
                    TextButton(onClick = launchPicker, enabled = !state.importing) {
                        Icon(Icons.Default.Add, contentDescription = null, tint = CineMind.colors.accent)
                        Spacer(Modifier.width(Spacing.xs))
                        Text("Import", color = CineMind.colors.accent)
                    }
                }
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 108.dp),
                    contentPadding = PaddingValues(start = Spacing.m, end = Spacing.m, bottom = Spacing.xl),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.s),
                    verticalArrangement = Arrangement.spacedBy(Spacing.s),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(state.assets, key = { it.id }) { asset ->
                        val order = state.selection.indexOf(asset.id)
                        AssetTile(
                            asset = asset,
                            selectionNumber = if (order >= 0) order + 1 else null,
                            onClick = { viewModel.toggleSelection(asset.id) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AssetTile(
    asset: MediaAsset,
    selectionNumber: Int?,
    onClick: () -> Unit,
) {
    val c = CineMind.colors
    val selected = selectionNumber != null
    val shape = RoundedCornerShape(Radius.m)
    val interaction = remember { MutableInteractionSource() }
    val click = hapticClick(onClick = onClick)
    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .pressScale(interaction, 0.95f)
            .clip(shape)
            .background(c.surfaceSunken)
            .then(if (selected) Modifier.border(3.dp, c.accent, shape) else Modifier.border(1.dp, c.hairline, shape))
            .semantics {
                this.selected = selected
                contentDescription = (if (asset.isVideo) "Video " + formatDuration(asset.durationUs) else "Photo") +
                    (selectionNumber?.let { ", selected number $it" } ?: "")
            }
            .clickable(interactionSource = interaction, indication = null, role = Role.Checkbox, onClick = click),
    ) {
        AsyncImage(
            model = ImageRequest.Builder(LocalContext.current)
                .data(asset.uri)
                .videoFrameMillis(1_000)
                .build(),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )

        MediaBadge(
            text = if (asset.isVideo) formatDuration(asset.durationUs) else "Photo",
            icon = if (asset.isVideo) Icons.Default.Movie else Icons.Default.Image,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(Spacing.xs + 2.dp),
        )
        MediaBadge(
            text = qualityLabel(asset.width, asset.height),
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(Spacing.xs + 2.dp),
        )

        if (selectionNumber != null) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(Spacing.xs + 2.dp)
                    .size(26.dp)
                    .clip(CircleShape)
                    .background(c.accent),
            ) {
                Text(
                    text = selectionNumber.toString(),
                    color = c.onAccent,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

/** Label drawn over media; uses the stage tokens so it reads on any picture in any theme. */
@Composable
private fun MediaBadge(
    text: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
) {
    val c = CineMind.colors
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .clip(RoundedCornerShape(Radius.xs))
            .background(c.stageControl)
            .padding(horizontal = Spacing.xs + 2.dp, vertical = Spacing.xxs),
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = c.onStage, modifier = Modifier.size(12.dp))
            Spacer(Modifier.size(Spacing.xs))
        }
        Text(text = text, color = c.onStage, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Clip)
    }
}

/** Floating glass bar for the current selection. */
@Composable
private fun SelectionBar(count: Int, busy: Boolean, onClear: () -> Unit, onCreate: () -> Unit) {
    val c = CineMind.colors
    val shape = RoundedCornerShape(Radius.xl)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(Spacing.m)
            .clip(shape)
            .background(c.surfaceGlass)
            .border(1.dp, c.hairline, shape)
            .padding(start = Spacing.l, end = Spacing.s, top = Spacing.s, bottom = Spacing.s),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text("$count selected", style = MaterialTheme.typography.titleMedium, color = c.textPrimary)
            Text("In this order", style = MaterialTheme.typography.labelSmall, color = c.textTertiary)
        }
        TextButton(onClick = onClear, enabled = !busy) { Text("Clear", color = c.textSecondary) }
        GradientButton(if (busy) "Creating…" else "Create project", onClick = onCreate, enabled = !busy)
    }
}

/** Short resolution label based on the shorter side (works for portrait and landscape). */
internal fun qualityLabel(width: Int, height: Int): String {
    val shortSide = minOf(width, height)
    return when {
        shortSide >= 2160 -> "4K"
        shortSide >= 1440 -> "1440p"
        shortSide >= 1080 -> "1080p"
        shortSide >= 720 -> "720p"
        shortSide > 0 -> "${shortSide}p"
        else -> "?"
    }
}
