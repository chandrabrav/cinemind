package com.cinemind.feature.editor.timeline.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.cinemind.core.domain.model.Project
import com.cinemind.core.domain.repository.ProjectRepository
import com.cinemind.core.ui.theme.TrackAudioColor
import com.cinemind.core.ui.theme.TrackEffectColor
import com.cinemind.core.ui.theme.TrackVideoColor
import com.cinemind.core.ui.theme.formatTimecode
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class TimelineViewModel @Inject constructor(
    private val projectRepository: ProjectRepository,
) : ViewModel() {
    private val _project = MutableStateFlow<Project?>(null)
    val project: StateFlow<Project?> = _project.asStateFlow()

    fun loadProject(id: String) {
        viewModelScope.launch {
            _project.value = projectRepository.getProject(id)
        }
    }
}

// NOTE: placeholder workspace. The real timeline engine (clips, trim handles, playhead,
// reorder, undo/redo) is a Stage 2 task; the bottom-bar buttons do nothing yet.
@Composable
fun TimelineScreen(
    projectId: String,
    viewModel: TimelineViewModel = hiltViewModel(),
) {
    LaunchedEffect(projectId) { viewModel.loadProject(projectId) }
    val project by viewModel.project.collectAsStateWithLifecycle()

    Column(modifier = Modifier.fillMaxSize()) {
        Text(
            text = project?.name ?: "Timeline Editor",
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.padding(16.dp),
        )

        TimelineCanvas(modifier = Modifier.weight(1f))

        BottomAppBar(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                TextButton(onClick = { /* Add clip: Stage 2 */ }) { Text("Add Clip") }
                TextButton(onClick = { /* Export: Stage 2 */ }) { Text("Export") }
            }
        }
    }
}

@Composable
fun TimelineCanvas(modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TrackLabel("Video", TrackVideoColor, Modifier.weight(1f))
            TrackLabel("Audio", TrackAudioColor, Modifier.weight(1f))
            TrackLabel("Effects", TrackEffectColor, Modifier.weight(1f))
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(32.dp)
                .background(MaterialTheme.colorScheme.surfaceContainer),
        ) {
            Text(
                text = formatTimecode(0),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(8.dp),
            )
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surface),
        ) {
            Text(
                text = "No clips added yet",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.Center),
            )
        }
    }
}

@Composable
private fun TrackLabel(text: String, color: Color, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        modifier = modifier
            .padding(8.dp)
            .background(color, RoundedCornerShape(4.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp),
    )
}
