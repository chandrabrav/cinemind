package com.cinemind.feature.editor.timeline.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import coil.compose.AsyncImage
import com.cinemind.core.domain.model.Clip
import com.cinemind.core.domain.model.MediaAsset
import com.cinemind.core.domain.model.Project
import com.cinemind.core.domain.model.TrackType
import com.cinemind.core.domain.repository.ClipRepository
import com.cinemind.core.domain.repository.MediaAssetRepository
import com.cinemind.core.domain.repository.ProjectRepository
import com.cinemind.core.domain.repository.TrackRepository
import com.cinemind.core.ui.theme.TrackVideoColor
import com.cinemind.core.ui.theme.formatTimecode
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class TimelineClip(val clip: Clip, val asset: MediaAsset?)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class TimelineViewModel @Inject constructor(
    private val projectRepository: ProjectRepository,
    trackRepository: TrackRepository,
    clipRepository: ClipRepository,
    assetRepository: MediaAssetRepository,
) : ViewModel() {
    private val projectId = MutableStateFlow<String?>(null)
    private val _project = MutableStateFlow<Project?>(null)
    val project: StateFlow<Project?> = _project.asStateFlow()

    /** Clips of the main video track, in timeline order, joined with their source media. */
    val clips: StateFlow<List<TimelineClip>> = projectId.filterNotNull()
        .flatMapLatest { trackRepository.getTracksForProject(it) }
        .flatMapLatest { tracks ->
            tracks.firstOrNull { it.type == TrackType.VIDEO }
                ?.let { clipRepository.getClipsForTrack(it.id) }
                ?: flowOf(emptyList())
        }
        .map { clips -> clips.map { TimelineClip(it, assetRepository.getAsset(it.assetId)) } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun loadProject(id: String) {
        if (projectId.value == id) return
        projectId.value = id
        viewModelScope.launch { _project.value = projectRepository.getProject(id) }
    }
}

// NOTE: read-only clip strip. Trim handles, playhead, reorder and undo/redo are the next tasks;
// the bottom-bar buttons do nothing yet.
@Composable
fun TimelineScreen(
    projectId: String,
    viewModel: TimelineViewModel = hiltViewModel(),
) {
    LaunchedEffect(projectId) { viewModel.loadProject(projectId) }
    val project by viewModel.project.collectAsStateWithLifecycle()
    val clips by viewModel.clips.collectAsStateWithLifecycle()

    Column(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = project?.name ?: "Timeline Editor",
                style = MaterialTheme.typography.headlineSmall,
            )
            project?.let {
                Text(
                    text = "${clips.size} clips · ${formatTimecode(it.durationUs, it.frameRate)} · " +
                        "${it.aspectRatio} · ${it.resolutionWidth}×${it.resolutionHeight} · ${it.frameRate} fps",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // Preview area placeholder (ExoPlayer preview is the next task).
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .background(Color.Black),
        ) {
            Text("Preview coming next", color = Color.White.copy(alpha = 0.6f))
        }

        ClipStrip(clips = clips, fps = project?.frameRate ?: 30)

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
private fun ClipStrip(clips: List<TimelineClip>, fps: Int) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(vertical = 12.dp),
    ) {
        Text(
            text = "Video 1",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 16.dp, bottom = 8.dp),
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
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            itemsIndexed(clips, key = { _, item -> item.clip.id }) { index, item ->
                ClipTile(index = index, item = item, fps = fps)
            }
        }
    }
}

@Composable
private fun ClipTile(index: Int, item: TimelineClip, fps: Int) {
    val shape = RoundedCornerShape(8.dp)
    Box(
        modifier = Modifier
            .width(96.dp)
            .height(64.dp)
            .clip(shape)
            .background(TrackVideoColor),
    ) {
        if (item.asset != null) {
            AsyncImage(
                model = item.asset.uri,
                contentDescription = item.asset.displayName.ifEmpty { null },
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Text("Missing media", color = Color.White, style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.align(Alignment.Center))
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
