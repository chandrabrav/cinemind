package com.cinemind.feature.editor.timeline.ui

import android.graphics.Color as AndroidColor
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import com.cinemind.core.domain.model.Project
import com.cinemind.core.ui.theme.TrackVideoColor
import com.cinemind.core.ui.theme.formatTimecode
import com.cinemind.feature.editor.timeline.preview.PreviewClip
import com.cinemind.feature.editor.timeline.preview.PreviewState

@Composable
fun TimelineScreen(
    projectId: String,
    viewModel: TimelineViewModel = hiltViewModel(),
) {
    LaunchedEffect(projectId) { viewModel.loadProject(projectId) }
    val project by viewModel.project.collectAsStateWithLifecycle()
    val clips by viewModel.clips.collectAsStateWithLifecycle()
    val preview by viewModel.previewState.collectAsStateWithLifecycle()

    // Stop playback when the app goes to the background.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) viewModel.pause()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val fps = project?.frameRate ?: 30

    Column(modifier = Modifier.fillMaxSize()) {
        ProjectHeader(project = project, clipCount = clips.size)

        PreviewArea(
            player = viewModel.player,
            project = project,
            currentClip = clips.getOrNull(preview.currentIndex),
            error = preview.error,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        )

        TransportControls(
            state = preview,
            fps = fps,
            enabled = clips.isNotEmpty(),
            onTogglePlay = viewModel::togglePlayPause,
            onScrub = viewModel::scrubTo,
            onScrubFinished = viewModel::scrubFinished,
        )

        ClipStrip(
            clips = clips,
            fps = fps,
            currentIndex = preview.currentIndex,
            onClipClick = viewModel::seekToClip,
        )

        BottomAppBar(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                TextButton(onClick = { /* Add clip: next tasks */ }) { Text("Add Clip") }
                TextButton(onClick = { /* Export: next tasks */ }) { Text("Export") }
            }
        }
    }
}

@Composable
private fun ProjectHeader(project: Project?, clipCount: Int) {
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(
            text = project?.name ?: "Timeline Editor",
            style = MaterialTheme.typography.titleLarge,
        )
        project?.let {
            Text(
                text = "$clipCount clips · ${it.aspectRatio} · ${it.resolutionWidth}×${it.resolutionHeight} · ${it.frameRate} fps",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@OptIn(UnstableApi::class)
@Composable
private fun PreviewArea(
    player: Player,
    project: Project?,
    currentClip: PreviewClip?,
    error: String?,
    modifier: Modifier = Modifier,
) {
    val ratio = project?.let { it.resolutionWidth.toFloat() / it.resolutionHeight } ?: (9f / 16f)
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier.background(Color.Black),
    ) {
        // The frame shows the project's output shape; clips are fitted inside it.
        Box(modifier = Modifier.aspectRatio(ratio, matchHeightConstraintsFirst = true)) {
            AndroidView(
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        useController = false
                        resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                        setShutterBackgroundColor(AndroidColor.BLACK)
                        setKeepContentOnPlayerReset(true)
                        this.player = player
                    }
                },
                update = { it.player = player },
                onRelease = { it.player = null },
                modifier = Modifier.fillMaxSize(),
            )

            val asset = currentClip?.asset
            when {
                currentClip != null && asset == null -> CenterLabel("Missing media")
                asset != null && asset.isImage -> AsyncImage(
                    model = asset.uri,
                    contentDescription = asset.displayName.ifEmpty { null },
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black),
                )
            }
        }

        if (error != null) {
            Text(
                text = error,
                color = Color.White,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(8.dp)
                    .background(MaterialTheme.colorScheme.error.copy(alpha = 0.85f), RoundedCornerShape(6.dp))
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
    }
}

@Composable
private fun CenterLabel(text: String) {
    Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize().background(Color.Black)) {
        Text(text, color = Color.White.copy(alpha = 0.7f))
    }
}

@Composable
private fun TransportControls(
    state: PreviewState,
    fps: Int,
    enabled: Boolean,
    onTogglePlay: () -> Unit,
    onScrub: (Long) -> Unit,
    onScrubFinished: (Long) -> Unit,
) {
    // While dragging, the slider shows the finger position rather than the (lagging) player position.
    var dragPositionUs by remember { mutableStateOf<Long?>(null) }
    val shownUs = dragPositionUs ?: state.positionUs
    val durationUs = state.durationUs.coerceAtLeast(1)

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 12.dp, vertical = 4.dp),
    ) {
        FilledIconButton(onClick = onTogglePlay, enabled = enabled) {
            Icon(
                imageVector = if (state.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                contentDescription = if (state.isPlaying) "Pause" else "Play",
            )
        }
        Column(modifier = Modifier.weight(1f).padding(start = 8.dp)) {
            Slider(
                value = shownUs.coerceIn(0, durationUs).toFloat(),
                valueRange = 0f..durationUs.toFloat(),
                enabled = enabled,
                onValueChange = { v ->
                    val us = v.toLong()
                    dragPositionUs = us
                    onScrub(us)
                },
                onValueChangeFinished = {
                    dragPositionUs?.let(onScrubFinished)
                    dragPositionUs = null
                },
            )
            Text(
                text = "${formatTimecode(shownUs, fps)} / ${formatTimecode(state.durationUs, fps)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ClipStrip(
    clips: List<PreviewClip>,
    fps: Int,
    currentIndex: Int,
    onClipClick: (Int) -> Unit,
) {
    val listState = rememberLazyListState()
    // Keep the playing clip in view as playback moves along the timeline.
    LaunchedEffect(currentIndex, clips.size) {
        if (currentIndex in clips.indices) listState.animateScrollToItem(currentIndex)
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(vertical = 10.dp),
    ) {
        Text(
            text = "Video 1",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 16.dp, bottom = 6.dp),
        )
        if (clips.isEmpty()) {
            Text(
                text = "No clips on this track",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp),
            )
            return@Column
        }
        LazyRow(
            state = listState,
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            itemsIndexed(clips, key = { _, item -> item.clip.id }) { index, item ->
                ClipTile(
                    index = index,
                    item = item,
                    fps = fps,
                    isCurrent = index == currentIndex,
                    onClick = { onClipClick(index) },
                )
            }
        }
    }
}

@Composable
private fun ClipTile(index: Int, item: PreviewClip, fps: Int, isCurrent: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(8.dp)
    Box(
        modifier = Modifier
            .width(96.dp)
            .height(64.dp)
            .clip(shape)
            .background(TrackVideoColor)
            .then(if (isCurrent) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, shape) else Modifier)
            .clickable(onClick = onClick),
    ) {
        val asset = item.asset
        if (asset != null) {
            AsyncImage(
                model = asset.uri,
                contentDescription = asset.displayName.ifEmpty { null },
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Text(
                "Missing media",
                color = Color.White,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.align(Alignment.Center),
            )
        }
        Text(
            text = "${index + 1} · ${formatTimecode(item.clip.timelineOutUs - item.clip.timelineInUs, fps)}",
            color = Color.White,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .background(Color.Black.copy(alpha = 0.6f))
                .padding(horizontal = 4.dp, vertical = 1.dp),
        )
    }
}
