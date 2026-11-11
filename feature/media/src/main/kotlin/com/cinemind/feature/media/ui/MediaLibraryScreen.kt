package com.cinemind.feature.media.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material3.Button
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import coil.request.ImageRequest
import coil.request.videoFrameMillis
import com.cinemind.core.domain.model.MediaAsset
import com.cinemind.core.ui.format.formatDuration

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
        // The app-level Scaffold already handles system-bar insets.
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            if (state.selection.isEmpty() && state.assets.isNotEmpty()) {
                ExtendedFloatingActionButton(
                    onClick = launchPicker,
                    icon = { Icon(Icons.Default.Add, contentDescription = null) },
                    text = { Text("Import") },
                )
            }
        },
        bottomBar = {
            if (state.selection.isNotEmpty()) {
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
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            if (state.assets.isEmpty()) {
                EmptyLibrary(onImport = launchPicker, importing = state.importing)
            } else {
                Text(
                    text = "Tap clips in the order you want them, then create a project.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 108.dp),
                    contentPadding = PaddingValues(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
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
private fun EmptyLibrary(onImport: () -> Unit, importing: Boolean) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = Icons.Default.Movie,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(56.dp),
        )
        Spacer(Modifier.height(16.dp))
        Text("Start with your footage", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))
        Text(
            "Pick videos and photos from your gallery. Originals are never moved or changed.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = onImport, enabled = !importing) {
            Icon(Icons.Default.Add, contentDescription = null)
            Spacer(Modifier.size(8.dp))
            Text("Import media")
        }
    }
}

@Composable
private fun AssetTile(
    asset: MediaAsset,
    selectionNumber: Int?,
    onClick: () -> Unit,
) {
    val selected = selectionNumber != null
    val shape = RoundedCornerShape(12.dp)
    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .then(
                if (selected) Modifier.border(3.dp, MaterialTheme.colorScheme.primary, shape) else Modifier,
            )
            .clickable(onClick = onClick),
    ) {
        AsyncImage(
            model = ImageRequest.Builder(LocalContext.current)
                .data(asset.uri)
                .videoFrameMillis(1_000)
                .build(),
            contentDescription = asset.displayName.ifEmpty { null },
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )

        Badge(
            text = if (asset.isVideo) formatDuration(asset.durationUs) else "Photo",
            icon = if (asset.isVideo) Icons.Default.Movie else Icons.Default.Image,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(6.dp),
        )
        Badge(
            text = qualityLabel(asset.width, asset.height),
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(6.dp),
        )

        if (selectionNumber != null) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp)
                    .size(26.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary),
            ) {
                Text(
                    text = selectionNumber.toString(),
                    color = MaterialTheme.colorScheme.onPrimary,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

@Composable
private fun Badge(
    text: String,
    modifier: Modifier = Modifier,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(Color.Black.copy(alpha = 0.6f))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(12.dp))
            Spacer(Modifier.size(4.dp))
        }
        Text(
            text = text,
            color = Color.White,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Clip,
        )
    }
}

@Composable
private fun SelectionBar(count: Int, busy: Boolean, onClear: () -> Unit, onCreate: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, tonalElevation = 3.dp) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Text("$count selected", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            TextButton(onClick = onClear, enabled = !busy) { Text("Clear") }
            Spacer(Modifier.size(8.dp))
            Button(onClick = onCreate, enabled = !busy) { Text(if (busy) "Creating…" else "Create project") }
        }
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
