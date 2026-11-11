package com.cinemind.feature.editor.timeline.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.Player
import com.cinemind.core.domain.model.Project
import com.cinemind.core.domain.model.TrackType
import com.cinemind.core.domain.repository.ClipRepository
import com.cinemind.core.domain.repository.MediaAssetRepository
import com.cinemind.core.domain.repository.ProjectRepository
import com.cinemind.core.domain.repository.TrackRepository
import com.cinemind.feature.editor.timeline.preview.PreviewClip
import com.cinemind.feature.editor.timeline.preview.PreviewPlayer
import com.cinemind.feature.editor.timeline.preview.PreviewState
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class TimelineViewModel @Inject constructor(
    @ApplicationContext context: Context,
    private val projectRepository: ProjectRepository,
    trackRepository: TrackRepository,
    clipRepository: ClipRepository,
    assetRepository: MediaAssetRepository,
) : ViewModel() {

    private val preview = PreviewPlayer(context)

    /** The player the preview surface attaches to. Owned (and released) by this ViewModel. */
    val player: Player get() = preview.player

    private val projectId = MutableStateFlow<String?>(null)
    /** Live project row, so format changes made elsewhere (e.g. the AI brief) reshape the preview. */
    val project: StateFlow<Project?> = projectId.filterNotNull()
        .flatMapLatest { projectRepository.observeProject(it) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val _previewState = MutableStateFlow(PreviewState())
    val previewState: StateFlow<PreviewState> = _previewState.asStateFlow()

    /** Clips of the main video track, in timeline order, joined with their source media. */
    val clips: StateFlow<List<PreviewClip>> = projectId.filterNotNull()
        .flatMapLatest { trackRepository.getTracksForProject(it) }
        .flatMapLatest { tracks ->
            tracks.firstOrNull { it.type == TrackType.VIDEO }
                ?.let { clipRepository.getClipsForTrack(it.id) }
                ?: flowOf(emptyList())
        }
        .map { clips -> clips.map { PreviewClip(it, assetRepository.getAsset(it.assetId)) } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    init {
        // Keep the player's playlist in sync with the stored timeline.
        viewModelScope.launch { clips.collect { preview.setClips(it) } }
        // Poll the player for the UI: smooth while playing, cheap while paused.
        viewModelScope.launch {
            while (isActive) {
                val snapshot = preview.snapshot()
                _previewState.value = snapshot
                delay(if (snapshot.isPlaying) 33 else 250)
            }
        }
    }

    fun loadProject(id: String) {
        if (projectId.value == id) return
        projectId.value = id
    }

    fun togglePlayPause() {
        preview.togglePlayPause()
        refresh()
    }

    fun pause() {
        preview.pause()
        refresh()
    }

    /** Called continuously while the user drags the scrubber. */
    fun scrubTo(positionUs: Long) {
        preview.seekToUs(positionUs, fast = true)
        _previewState.value = _previewState.value.copy(positionUs = positionUs)
    }

    /** Called when the drag ends: lands on the exact frame. */
    fun scrubFinished(positionUs: Long) {
        preview.seekToUs(positionUs, fast = false)
        refresh()
    }

    fun seekToClip(index: Int) {
        preview.seekToClip(index)
        refresh()
    }

    private fun refresh() {
        _previewState.value = preview.snapshot()
    }

    override fun onCleared() {
        preview.release()
        super.onCleared()
    }
}
