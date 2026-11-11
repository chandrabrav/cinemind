package com.cinemind.feature.editor.timeline.ui

import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.Player
import com.cinemind.core.common.CineMindLogger
import com.cinemind.core.domain.audio.MusicSettings
import com.cinemind.core.domain.audio.ProjectAudio
import com.cinemind.core.domain.editing.ClipLimits
import com.cinemind.core.domain.editing.EditHistory
import com.cinemind.core.domain.editing.ClipTransition
import com.cinemind.core.domain.editing.TimelineEditing
import com.cinemind.core.domain.editing.TransitionEditing
import com.cinemind.core.domain.editing.append
import com.cinemind.core.domain.editing.split
import com.cinemind.core.domain.model.ProjectFactory
import com.cinemind.feature.media.importer.MediaImporter
import com.cinemind.core.domain.model.Clip
import com.cinemind.core.domain.model.MediaAsset
import com.cinemind.core.domain.model.Project
import com.cinemind.core.domain.model.TrackType
import com.cinemind.core.domain.repository.MediaAssetRepository
import com.cinemind.core.domain.repository.ProjectRepository
import com.cinemind.feature.editor.timeline.preview.MusicPlayback
import com.cinemind.feature.editor.timeline.preview.PreviewClip
import com.cinemind.feature.editor.timeline.preview.PreviewPlayer
import com.cinemind.feature.editor.timeline.preview.PreviewState
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject

/** Editing state shown by the UI. */
data class EditorState(
    val loaded: Boolean = false,
    val clips: List<PreviewClip> = emptyList(),
    val selectedClipId: String? = null,
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    val music: MusicUi? = null,
    val originalVolume: Float = 1f,
)

/** The project's background music as shown in the editor. */
data class MusicUi(val name: String, val durationUs: Long, val settings: MusicSettings)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class TimelineViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val projectRepository: ProjectRepository,
    private val assetRepository: MediaAssetRepository,
    private val importer: MediaImporter,
) : ViewModel() {

    private val preview = PreviewPlayer(appContext)

    /** The player the preview surface attaches to. Owned (and released) by this ViewModel. */
    val player: Player get() = preview.player

    private val projectId = MutableStateFlow<String?>(null)

    /** Live project row, so format changes made elsewhere (e.g. the AI brief) reshape the preview. */
    val project: StateFlow<Project?> = projectId.filterNotNull()
        .flatMapLatest { projectRepository.observeProject(it) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val _previewState = MutableStateFlow(PreviewState())
    val previewState: StateFlow<PreviewState> = _previewState.asStateFlow()

    private val _editor = MutableStateFlow(EditorState())
    val editor: StateFlow<EditorState> = _editor.asStateFlow()

    // Edit session. The history (not the database) is the source of truth while the editor is open;
    // every change is written through to Room so nothing is lost if the app is killed.
    private var history: EditHistory<List<Clip>>? = null
    private var trackId: String? = null
    private val assets = mutableMapOf<String, MediaAsset?>()
    private val writeLock = Mutex()

    init {
        // Poll the player for the UI: smooth while playing, cheap while paused.
        viewModelScope.launch {
            while (isActive) {
                val snapshot = preview.snapshot()
                _previewState.value = snapshot
                preview.music.sync(snapshot.positionUs, snapshot.isPlaying)
                delay(if (snapshot.isPlaying) 33 else 250)
            }
        }
    }

    fun loadProject(id: String) {
        if (projectId.value == id) return
        projectId.value = id
        viewModelScope.launch {
            val timeline = projectRepository.getProjectTimeline(id)
            val track = timeline?.tracks?.firstOrNull { it.type == TrackType.VIDEO }
            val clips = if (timeline != null && track != null) {
                timeline.clips.filter { it.trackId == track.id }.sortedBy { it.timelineInUs }
            } else {
                emptyList()
            }
            clips.map { it.assetId }.distinct().forEach { assetId -> assets[assetId] = assetRepository.getAsset(assetId) }
            trackId = track?.id
            history = EditHistory(clips)
            loadAudio(id)
            publish(seekToSelection = false)
        }
    }

    // ---- Playback -------------------------------------------------------------------------

    fun togglePlayPause() {
        preview.togglePlayPause()
        refreshPreview()
    }

    fun pause() {
        preview.pause()
        refreshPreview()
    }

    /** Called continuously while the user drags the scrubber. */
    fun scrubTo(positionUs: Long) {
        preview.seekToUs(positionUs, fast = true)
        _previewState.value = _previewState.value.copy(positionUs = positionUs)
    }

    /** Called when the drag ends: lands on the exact frame. */
    fun scrubFinished(positionUs: Long) {
        preview.seekToUs(positionUs, fast = false)
        refreshPreview()
    }

    // ---- Selection & editing ----------------------------------------------------------------

    /** Tapping a clip selects it and jumps the preview to its start; tapping it again deselects. */
    fun onClipTapped(index: Int) {
        val clip = history?.present?.getOrNull(index) ?: return
        val alreadySelected = _editor.value.selectedClipId == clip.id
        _editor.value = _editor.value.copy(selectedClipId = if (alreadySelected) null else clip.id)
        preview.seekToClip(index)
        refreshPreview()
    }

    fun clearSelection() {
        _editor.value = _editor.value.copy(selectedClipId = null)
    }

    fun limitsFor(clip: Clip): ClipLimits {
        val asset = assets[clip.assetId]
        return ClipLimits(
            sourceDurationUs = asset?.takeIf { it.isVideo }?.durationUs,
            isStill = asset == null || !asset.isVideo,
        )
    }

    fun trimSelected(newInUs: Long, newOutUs: Long) = editSelected { clips, clip ->
        TimelineEditing.trim(clips, clip.id, newInUs, newOutUs, limitsFor(clip))
    }

    fun moveSelected(delta: Int) = editSelected { clips, clip ->
        val from = clips.indexOfFirst { it.id == clip.id }
        TimelineEditing.move(clips, from, from + delta)
    }

    fun deleteSelected() {
        val id = _editor.value.selectedClipId ?: return
        commit(TimelineEditing.delete(history?.present ?: return, id), selectAfter = null)
    }

    /** Splits the selected clip where the playhead is (one undo step). */
    fun splitSelectedAtPlayhead() {
        val clips = history?.present ?: return
        val clip = clips.firstOrNull { it.id == _editor.value.selectedClipId } ?: return
        val at = preview.currentPositionUs()
        val next = TimelineEditing.split(clips, clip.id, at)
        if (next === clips) {
            Toast.makeText(appContext, "Move the playhead inside the selected clip (not at its edges) to split", Toast.LENGTH_SHORT).show()
            return
        }
        commit(next, selectAfter = clip.id)
    }

    // ---- Music & volume --------------------------------------------------------------------

    private var audio = ProjectAudio(music = null)
    private var musicAsset: MediaAsset? = null

    private suspend fun loadAudio(projectId: String) {
        audio = runCatching { projectRepository.getProjectAudio(projectId) }.getOrDefault(ProjectAudio(null))
        musicAsset = audio.music?.let { assetRepository.getAsset(it.assetId) }
        preview.setOriginalVolume(audio.originalVolume)
        val m = audio.music
        val a = musicAsset
        _editor.value = _editor.value.copy(
            music = if (m != null && a != null) MusicUi(a.displayName.ifEmpty { "Music" }, a.durationUs, m) else null,
            originalVolume = audio.originalVolume,
        )
        refreshMusicPlayback()
    }

    private fun refreshMusicPlayback() {
        val m = audio.music
        val a = musicAsset
        val total = TimelineEditing.durationUs(history?.present.orEmpty())
        preview.music.set(
            if (m == null || a == null || total <= 0) {
                null
            } else {
                MusicPlayback(a.uri, a.durationUs, m.startOffsetUs, m.volume, m.fadeInMs * 1_000, m.fadeOutMs * 1_000, total)
            },
        )
    }

    /** Imports a picked audio file and makes it the project's music. */
    fun setMusicFromUri(uri: Uri) {
        val id = projectId.value ?: return
        viewModelScope.launch {
            val asset = runCatching { importer.import(uri) }.getOrNull()
            if (asset == null || !asset.isAudioFile) {
                Toast.makeText(appContext, "That file can't be used as music", Toast.LENGTH_SHORT).show()
                return@launch
            }
            saveMusic(id, MusicSettings(assetId = asset.id))
        }
    }

    fun updateMusic(settings: MusicSettings) {
        val id = projectId.value ?: return
        viewModelScope.launch { saveMusic(id, settings) }
    }

    fun removeMusic() {
        val id = projectId.value ?: return
        viewModelScope.launch { saveMusic(id, null) }
    }

    fun setOriginalVolume(volume: Float) {
        val id = projectId.value ?: return
        preview.setOriginalVolume(volume)
        viewModelScope.launch(NonCancellable) {
            runCatching { projectRepository.setOriginalAudioVolume(id, volume) }
            loadAudio(id)
        }
    }

    private suspend fun saveMusic(projectId: String, settings: MusicSettings?) {
        withContext(NonCancellable) {
            runCatching { projectRepository.saveMusic(projectId, settings) }
                .onFailure { CineMindLogger.e(it, "Saving music failed") }
            loadAudio(projectId)
        }
    }

    // ---- Transitions ----------------------------------------------------------------------

    /** Transition into the selected clip (for the first clip: the opening). */
    fun setTransitionSelected(type: ClipTransition, durationMs: Long) = editSelected { clips, clip ->
        TransitionEditing.setTransition(clips, clip.id, type, durationMs)
    }

    fun applyTransitionToAll(type: ClipTransition, durationMs: Long) {
        val clips = history?.present ?: return
        commit(TransitionEditing.setAll(clips, type, durationMs), selectAfter = _editor.value.selectedClipId)
    }

    fun setOpeningAndEnding(openingMs: Long, endingMs: Long) {
        val clips = history?.present ?: return
        commit(TransitionEditing.setOpeningAndEnding(clips, openingMs, endingMs), selectAfter = _editor.value.selectedClipId)
    }

    // ---- Adding media ----------------------------------------------------------------------

    /** Imports picked media and appends it to the end of the track (one undo step). */
    fun addMedia(uris: List<Uri>) {
        val track = trackId ?: return
        if (uris.isEmpty()) return
        viewModelScope.launch {
            val added = mutableListOf<Clip>()
            var failed = 0
            for (uri in uris) {
                runCatching { importer.import(uri) }
                    .onSuccess { asset ->
                        assets[asset.id] = asset
                        val length = if (asset.isVideo && asset.durationUs > 0) asset.durationUs else ProjectFactory.DEFAULT_IMAGE_DURATION_US
                        added += Clip(trackId = track, assetId = asset.id, timelineInUs = 0, timelineOutUs = 0, sourceInUs = 0, sourceOutUs = length)
                    }
                    .onFailure { failed++ }
            }
            val clips = history?.present ?: return@launch
            commit(TimelineEditing.append(clips, added), selectAfter = null)
            val msg = when {
                added.isEmpty() -> "Couldn't add the selected media"
                failed > 0 -> "Added ${added.size} clip(s); $failed couldn't be read"
                else -> "Added ${added.size} clip(s) at the end"
            }
            Toast.makeText(appContext, msg, Toast.LENGTH_SHORT).show()
        }
    }

    fun undo() {
        val h = history ?: return
        if (h.undo()) afterHistoryChange()
    }

    fun redo() {
        val h = history ?: return
        if (h.redo()) afterHistoryChange()
    }

    private fun editSelected(op: (List<Clip>, Clip) -> List<Clip>) {
        val clips = history?.present ?: return
        val clip = clips.firstOrNull { it.id == _editor.value.selectedClipId } ?: return
        commit(op(clips, clip), selectAfter = clip.id)
    }

    private fun commit(next: List<Clip>, selectAfter: String?) {
        val h = history ?: return
        if (!h.push(next)) return
        _editor.value = _editor.value.copy(selectedClipId = selectAfter)
        afterHistoryChange()
    }

    private fun afterHistoryChange() {
        // Keep the selection only if that clip still exists in the current state.
        val present = history?.present.orEmpty()
        if (present.none { it.id == _editor.value.selectedClipId }) {
            _editor.value = _editor.value.copy(selectedClipId = null)
        }
        publish(seekToSelection = true)
        persist()
    }

    private fun publish(seekToSelection: Boolean) {
        val h = history ?: return
        val clips = h.present.map { PreviewClip(it, assets[it.assetId]) }
        _editor.value = _editor.value.copy(
            loaded = true,
            clips = clips,
            canUndo = h.canUndo,
            canRedo = h.canRedo,
        )
        preview.setClips(clips)
        refreshMusicPlayback()
        if (seekToSelection) {
            val index = h.present.indexOfFirst { it.id == _editor.value.selectedClipId }
            if (index >= 0) preview.seekToClip(index)
        }
        refreshPreview()
    }

    /** Writes the latest state. Serialized and non-cancellable so the last edit always lands. */
    private fun persist() {
        val project = projectId.value ?: return
        val track = trackId ?: return
        viewModelScope.launch(NonCancellable) {
            writeLock.withLock {
                val latest = history?.present ?: return@withLock
                runCatching { projectRepository.saveTrackClips(project, track, latest) }
                    .onFailure { CineMindLogger.e(it, "Saving timeline failed") }
            }
        }
    }

    private fun refreshPreview() {
        _previewState.value = preview.snapshot()
    }

    override fun onCleared() {
        preview.release()
        super.onCleared()
    }
}
