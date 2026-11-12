package com.cinemind.feature.editor.timeline.ui

import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.Player
import com.cinemind.core.common.CineMindLogger
import com.cinemind.core.domain.audio.MusicSettings
import com.cinemind.core.domain.audio.VoiceoverSettings
import com.cinemind.feature.editor.timeline.voiceover.VoiceTake
import com.cinemind.feature.editor.timeline.voiceover.VoiceoverRecorder
import com.cinemind.core.domain.audio.ProjectAudio
import com.cinemind.core.domain.editing.ClipLimits
import com.cinemind.core.domain.editing.EditHistory
import com.cinemind.core.domain.editing.OverlayEditing
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
import kotlinx.coroutines.flow.first
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
    /** Clips on the overlay (picture-in-picture) track. */
    val overlays: List<PreviewClip> = emptyList(),
    val selectedOverlayId: String? = null,
    /** Recordings on the voiceover track. */
    val voiceClips: List<PreviewClip> = emptyList(),
    val selectedVoiceId: String? = null,
    val voiceSettings: VoiceoverSettings = VoiceoverSettings(),
    /** Preview listening volume / mute (not part of the project). */
    val previewVolume: Float = 1f,
    val previewMuted: Boolean = false,
)

/** The voiceover recorder as shown in its sheet. */
data class RecordingUi(
    val phase: Phase = Phase.IDLE,
    val startUs: Long = 0,
    val elapsedMs: Long = 0,
    /** Recent input levels 0..1, oldest first (live waveform). */
    val levels: List<Float> = emptyList(),
    val listening: Boolean = false,
    val error: String? = null,
) {
    enum class Phase { IDLE, RECORDING, PAUSED, REVIEW }
}

/** The project's background music as shown in the editor. */
data class MusicUi(val name: String, val durationUs: Long, val settings: MusicSettings)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class TimelineViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val projectRepository: ProjectRepository,
    private val assetRepository: MediaAssetRepository,
    private val importer: MediaImporter,
    private val lutLibrary: com.cinemind.feature.color.library.LutLibrary,
    private val briefRepository: com.cinemind.core.domain.repository.EditBriefRepository,
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
        // The preview renders the project's own shape and frame rate (as the export does).
        viewModelScope.launch {
            project.filterNotNull().collect { preview.setFormat(it.resolutionWidth, it.resolutionHeight, it.frameRate) }
        }
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
            val overlayTrack = timeline?.tracks?.firstOrNull { it.type == TrackType.OVERLAY }
            val overlayClips = timeline?.clips.orEmpty().filter { it.trackId == overlayTrack?.id }.sortedBy { it.timelineInUs }
            overlayClips.map { it.assetId }.distinct().forEach { assetId -> assets[assetId] = assetRepository.getAsset(assetId) }
            overlayTrackId = overlayTrack?.id
            overlayHistory = EditHistory(overlayClips)
            val voice = timeline?.tracks?.firstOrNull { it.type == TrackType.VOICEOVER }
            val voiceClips = timeline?.clips.orEmpty().filter { it.trackId == voice?.id }.sortedBy { it.timelineInUs }
            voiceClips.map { it.assetId }.distinct().forEach { assetId -> assets[assetId] = assetRepository.getAsset(assetId) }
            voiceTrack = voice
            voiceHistory = EditHistory(voiceClips)
            undoKinds.clear()
            redoKinds.clear()
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

    // ---- Colour -----------------------------------------------------------------------------

    /** The grade shown in the colour tool: the selected clip's, else the first clip's. */
    fun currentGrade(): com.cinemind.core.domain.color.ColorGrade {
        val clips = history?.present.orEmpty()
        val clip = clips.firstOrNull { it.id == _editor.value.selectedClipId } ?: clips.firstOrNull()
        return com.cinemind.core.domain.color.ColorGrade.decode(clip?.grade)
    }

    /**
     * Applies a grade to the selected clip, or to every clip when [allClips] (or nothing is
     * selected). One undo step; saved with the project; used identically by preview and export.
     */
    fun setGrade(grade: com.cinemind.core.domain.color.ColorGrade, allClips: Boolean) {
        val clips = history?.present ?: return
        val selected = _editor.value.selectedClipId
        val encoded = grade.encode()
        val next = clips.map { c -> if (allClips || selected == null || c.id == selected) c.copy(grade = encoded) else c }
        commit(next, selectAfter = selected)
    }

    /** While the user holds "compare": show the original colour. */
    fun compareOriginal(showOriginal: Boolean) = preview.setGradesEnabled(!showOriginal)

    /** Frame for the colour tool (current clip at the playhead) and words describing the project. */
    data class ColorContext(val uri: String?, val isVideo: Boolean, val timeUs: Long, val themeText: String)

    suspend fun colorContext(): ColorContext {
        val clips = _editor.value.clips
        val pos = preview.currentPositionUs()
        val item = clips.firstOrNull { it.clip.id == _editor.value.selectedClipId }
            ?: clips.firstOrNull { pos >= it.clip.timelineInUs && pos < it.clip.timelineOutUs }
            ?: clips.firstOrNull()
        val asset = item?.asset
        val timeUs = item?.let { it.clip.sourceInUs + com.cinemind.core.domain.editing.SpeedMath.timelineToSource(it.clip, (pos - it.clip.timelineInUs).coerceIn(0, it.clip.timelineOutUs - it.clip.timelineInUs)) } ?: 0L
        val brief = projectId.value?.let { id -> runCatching { briefRepository.observeBrief(id).first() }.getOrNull() }
        val theme = listOfNotNull(project.value?.name, brief?.prompt, brief?.style?.label).joinToString(" ")
        return ColorContext(asset?.uri, asset?.isVideo == true, timeUs, theme)
    }

    fun togglePreviewMute() {
        val muted = !_editor.value.previewMuted
        _editor.value = _editor.value.copy(previewMuted = muted)
        preview.setListeningVolume(if (muted) 0f else _editor.value.previewVolume)
    }

    fun setPreviewVolume(volume: Float) {
        _editor.value = _editor.value.copy(previewVolume = volume, previewMuted = volume <= 0.001f)
        preview.setListeningVolume(volume)
    }

    /** The editor left the screen (e.g. export opened): free decoders so export has them. */
    fun onEditorHidden() {
        preview.pause()
        preview.releaseDecoders()
    }

    /** The editor is back on screen: rebuild the preview where it was. */
    fun onEditorShown() {
        preview.restore()
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
        _editor.value = _editor.value.copy(selectedClipId = if (alreadySelected) null else clip.id, selectedOverlayId = null)
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

    // ---- Speed ------------------------------------------------------------------------------

    /** What the speed tool edits: the selected video clip (or the one under the playhead). */
    data class SpeedTarget(val clip: Clip, val sourceFps: Float, val projectFps: Int)

    fun speedTarget(): SpeedTarget? {
        val clips = history?.present.orEmpty()
        val pos = preview.currentPositionUs()
        val clip = clips.firstOrNull { it.id == _editor.value.selectedClipId }
            ?: clips.firstOrNull { pos >= it.timelineInUs && pos < it.timelineOutUs }
            ?: return null
        val asset = assets[clip.assetId]?.takeIf { it.isVideo } ?: return null
        return SpeedTarget(clip, asset.frameRate, project.value?.frameRate ?: 30)
    }

    /** Constant speed for one clip, or for every video clip (one undo step). Clears any ramp. */
    fun setClipSpeed(clipId: String, speed: Float, allClips: Boolean) {
        val clips = history?.present ?: return
        val s = com.cinemind.core.domain.editing.SpeedMath.clampSpeed(speed)
        val next = clips.map { c ->
            val isVideo = assets[c.assetId]?.isVideo == true
            if (isVideo && (allClips || c.id == clipId)) c.copy(speed = s, speedCurve = "") else c
        }
        commit(TimelineEditing.relayout(next), selectAfter = clipId)
    }

    /** Speed ramp for one clip (one undo step); fewer than two points means back to constant speed. */
    fun setClipRamp(clipId: String, points: List<com.cinemind.core.domain.editing.SpeedPoint>) {
        val clips = history?.present ?: return
        val next = clips.map { c ->
            if (c.id != clipId) c
            else if (points.size < 2) c.copy(speed = points.firstOrNull()?.speed ?: 1f, speedCurve = "")
            else c.copy(speed = 1f, speedCurve = com.cinemind.core.domain.editing.SpeedMath.encode(points))
        }
        commit(TimelineEditing.relayout(next), selectAfter = clipId)
    }

    /**
     * Freeze frame: grabs the video frame under the playhead as a full-size picture and holds it
     * for [holdUs], splitting the clip there (one undo step). The picture keeps the clip's grade.
     */
    fun freezeFrameAtPlayhead(holdUs: Long = 2_000_000L) {
        val clips = history?.present ?: return
        val track = trackId ?: return
        val pos = preview.currentPositionUs()
        val clip = clips.firstOrNull { it.id == _editor.value.selectedClipId && pos >= it.timelineInUs && pos <= it.timelineOutUs }
            ?: clips.firstOrNull { pos >= it.timelineInUs && pos < it.timelineOutUs }
            ?: return
        val asset = assets[clip.assetId]?.takeIf { it.isVideo } ?: run {
            Toast.makeText(appContext, "Freeze frame works on video clips", Toast.LENGTH_SHORT).show()
            return
        }
        val offset = (pos - clip.timelineInUs).coerceIn(0, clip.timelineOutUs - clip.timelineInUs)
        val sourceUs = clip.sourceInUs + com.cinemind.core.domain.editing.SpeedMath.timelineToSource(clip, offset)
        viewModelScope.launch {
            val still = withContext(kotlinx.coroutines.Dispatchers.IO) { grabFrame(asset, sourceUs) }
            if (still == null) {
                Toast.makeText(appContext, "Couldn't read that frame", Toast.LENGTH_SHORT).show()
                return@launch
            }
            assetRepository.createAsset(still)
            assets[still.id] = still
            val freeze = Clip(trackId = track, assetId = still.id, timelineInUs = 0, timelineOutUs = 0, sourceInUs = 0, sourceOutUs = holdUs, grade = clip.grade)
            val current = history?.present ?: return@launch
            commit(TimelineEditing.insertFreeze(current, clip.id, pos, freeze), selectAfter = freeze.id)
        }
    }

    private fun grabFrame(asset: MediaAsset, sourceUs: Long): MediaAsset? {
        val retriever = android.media.MediaMetadataRetriever()
        return try {
            retriever.setDataSource(appContext, Uri.parse(asset.uri))
            val frame = retriever.getFrameAtTime(sourceUs, android.media.MediaMetadataRetriever.OPTION_CLOSEST) ?: return null
            val dir = java.io.File(appContext.filesDir, "freeze").apply { mkdirs() }
            val file = java.io.File(dir, java.util.UUID.randomUUID().toString() + ".jpg")
            file.outputStream().use { frame.compress(android.graphics.Bitmap.CompressFormat.JPEG, 95, it) }
            val w = frame.width
            val h = frame.height
            frame.recycle()
            MediaAsset(
                uri = Uri.fromFile(file).toString(),
                mimeType = "image/jpeg",
                width = w,
                height = h,
                durationUs = 0,
                displayName = "Freeze frame",
                sizeBytes = file.length(),
            )
        } catch (e: Exception) {
            CineMindLogger.w("Freeze frame failed: ${e.message}")
            null
        } finally {
            runCatching { retriever.release() }
        }
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

    // Each track keeps its own history; this records which one each step changed, so undo/redo
    // walk all edits in the order they were made.
    private enum class Layer { MAIN, OVERLAY, VOICE }
    private val undoKinds = ArrayDeque<Layer>()
    private val redoKinds = ArrayDeque<Layer>()

    private fun historyOf(layer: Layer): EditHistory<List<Clip>>? = when (layer) {
        Layer.MAIN -> history
        Layer.OVERLAY -> overlayHistory
        Layer.VOICE -> voiceHistory
    }

    private fun afterChange(layer: Layer) = when (layer) {
        Layer.MAIN -> afterHistoryChange()
        Layer.OVERLAY -> afterOverlayChange()
        Layer.VOICE -> afterVoiceChange()
    }

    fun undo() {
        val layer = undoKinds.removeLastOrNull() ?: return
        if (historyOf(layer)?.undo() != true) return
        redoKinds.addLast(layer)
        afterChange(layer)
    }

    fun redo() {
        val layer = redoKinds.removeLastOrNull() ?: return
        if (historyOf(layer)?.redo() != true) return
        undoKinds.addLast(layer)
        afterChange(layer)
    }

    // ---- Voiceover track (recorded narration) ------------------------------------------------

    private var voiceHistory = EditHistory<List<Clip>>(emptyList())
    private var voiceTrack: com.cinemind.core.domain.model.Track? = null
    private val recorder = VoiceoverRecorder(appContext)
    private val _recording = MutableStateFlow(RecordingUi())
    val recording: StateFlow<RecordingUi> = _recording.asStateFlow()
    private var recordStartUs = 0L
    private var pendingTake: VoiceTake? = null
    private var recordingJob: kotlinx.coroutines.Job? = null
    private var listenPlayer: android.media.MediaPlayer? = null

    private fun voiceSettings(): VoiceoverSettings = voiceTrack?.let { VoiceoverSettings(it.volume, it.opacity, muted = !it.isEnabled) } ?: VoiceoverSettings()

    /** Starts recording at the playhead while the video plays silently. Returns false if the mic is unavailable. */
    fun startRecording(): Boolean {
        if (recorder.isRecording) return true
        discardTake()
        preview.pause()
        recordStartUs = preview.currentPositionUs()
        if (!recorder.start()) {
            _recording.value = RecordingUi(error = "The microphone isn't available (another app may be using it).")
            return false
        }
        // The video plays muted so the microphone doesn't pick up the speaker.
        preview.setMuted(true)
        preview.play()
        _recording.value = RecordingUi(phase = RecordingUi.Phase.RECORDING, startUs = recordStartUs)
        recordingJob = viewModelScope.launch {
            val levels = ArrayDeque<Float>()
            while (isActive && recorder.isRecording) {
                levels.addLast(recorder.level())
                while (levels.size > 90) levels.removeFirst()
                _recording.value = _recording.value.copy(elapsedMs = recorder.elapsedMs(), levels = levels.toList())
                delay(60)
            }
        }
        return true
    }

    fun pauseRecording() {
        recorder.pause()
        preview.pause()
        _recording.value = _recording.value.copy(phase = RecordingUi.Phase.PAUSED)
    }

    fun resumeRecording() {
        recorder.resume()
        preview.play()
        _recording.value = _recording.value.copy(phase = RecordingUi.Phase.RECORDING)
    }

    fun stopRecording() {
        recordingJob?.cancel()
        val take = recorder.stop()
        preview.pause()
        preview.setMuted(false)
        preview.seekToUs(recordStartUs)
        refreshPreview()
        pendingTake = take
        _recording.value = if (take == null) {
            RecordingUi(error = "Nothing was recorded (keep recording for at least half a second).")
        } else {
            RecordingUi(phase = RecordingUi.Phase.REVIEW, startUs = recordStartUs, elapsedMs = take.durationUs / 1_000, levels = _recording.value.levels)
        }
    }

    /** Plays the take on its own before deciding. */
    fun listenTake() {
        val take = pendingTake ?: return
        stopListening()
        listenPlayer = runCatching {
            android.media.MediaPlayer().apply {
                setDataSource(take.file.absolutePath)
                setOnCompletionListener { stopListening() }
                prepare()
                start()
            }
        }.getOrNull()
        _recording.value = _recording.value.copy(listening = listenPlayer != null)
    }

    fun stopListening() {
        runCatching { listenPlayer?.stop() }
        runCatching { listenPlayer?.release() }
        listenPlayer = null
        _recording.value = _recording.value.copy(listening = false)
    }

    /** Puts the take on the voiceover track where recording started (one undo step). */
    fun acceptTake() {
        val take = pendingTake ?: return
        val project = projectId.value ?: return
        stopListening()
        pendingTake = null
        viewModelScope.launch {
            val count = voiceHistory.present.size + 1
            val asset = MediaAsset(
                uri = Uri.fromFile(take.file).toString(),
                mimeType = "audio/mp4",
                width = 0,
                height = 0,
                durationUs = take.durationUs,
                hasAudio = true,
                displayName = "Voiceover $count",
                sizeBytes = take.file.length(),
            )
            assetRepository.createAsset(asset)
            assets[asset.id] = asset
            val track = voiceTrack ?: projectRepository.ensureVoiceoverTrack(project).also { voiceTrack = it }
            val base = Clip(trackId = track.id, assetId = asset.id, timelineInUs = 0, timelineOutUs = 0, sourceInUs = 0, sourceOutUs = take.durationUs)
            // Placed where recording began; if that spot is taken (or it runs past the end), it is shortened to fit.
            val placed = OverlayEditing.place(voiceHistory.present, base, recordStartUs, take.durationUs, mainLengthUs())
            if (placed == null) {
                Toast.makeText(appContext, "There's already a voiceover at that spot. Move the playhead to an empty place and record again.", Toast.LENGTH_LONG).show()
                take.file.delete()
                _recording.value = RecordingUi()
                return@launch
            }
            _recording.value = RecordingUi()
            commitVoice(placed, select = base.id)
        }
    }

    fun discardTake() {
        stopListening()
        pendingTake?.file?.delete()
        pendingTake = null
        if (_recording.value.phase == RecordingUi.Phase.REVIEW || _recording.value.error != null) _recording.value = RecordingUi()
    }

    /** Closes the recorder without keeping anything. */
    fun cancelRecording() {
        recordingJob?.cancel()
        if (recorder.isRecording) {
            recorder.cancel()
            preview.pause()
            preview.setMuted(false)
        }
        discardTake()
        _recording.value = RecordingUi()
    }

    fun onVoiceTapped(id: String) {
        val current = _editor.value.selectedVoiceId
        _editor.value = _editor.value.copy(selectedVoiceId = if (current == id) null else id, selectedClipId = null, selectedOverlayId = null)
        voiceHistory.present.firstOrNull { it.id == id }?.let { preview.seekToUs(it.timelineInUs) }
        refreshPreview()
    }

    fun clearVoiceSelection() {
        _editor.value = _editor.value.copy(selectedVoiceId = null)
    }

    fun moveVoice(deltaUs: Long) = editVoice { clips, c -> OverlayEditing.move(clips, c.id, c.timelineInUs + deltaUs, mainLengthUs()) }

    fun trimVoice(startDeltaUs: Long, endDeltaUs: Long) = editVoice { clips, c ->
        OverlayEditing.trim(clips, c.id, c.timelineInUs + startDeltaUs, c.timelineOutUs + endDeltaUs, assets[c.assetId]?.durationUs, mainLengthUs())
    }

    fun setVoiceFades(fadeInMs: Long, fadeOutMs: Long) = editVoice { clips, c ->
        clips.map { if (it.id == c.id) it.copy(transitionInMs = fadeInMs.coerceAtLeast(0), transitionOutMs = fadeOutMs.coerceAtLeast(0)) else it }
    }

    fun deleteVoice() {
        val id = _editor.value.selectedVoiceId ?: return
        commitVoice(voiceHistory.present.filterNot { it.id == id }, select = null)
    }

    /** Narration volume, music ducking and mute for the whole voiceover track. */
    fun setVoiceSettings(settings: VoiceoverSettings) {
        val t = voiceTrack ?: return
        val updated = t.copy(volume = settings.volume.coerceIn(0f, 2f), opacity = settings.duckMusic.coerceIn(0f, 1f), isEnabled = !settings.muted)
        voiceTrack = updated
        _editor.value = _editor.value.copy(voiceSettings = settings)
        publishVoice()
        viewModelScope.launch(NonCancellable) { runCatching { projectRepository.updateTrack(updated) } }
    }

    private fun editVoice(op: (List<Clip>, Clip) -> List<Clip>) {
        val clips = voiceHistory.present
        val c = clips.firstOrNull { it.id == _editor.value.selectedVoiceId } ?: return
        commitVoice(op(clips, c), select = c.id)
    }

    private fun commitVoice(next: List<Clip>, select: String?) {
        if (!voiceHistory.push(next)) return
        undoKinds.addLast(Layer.VOICE)
        redoKinds.clear()
        _editor.value = _editor.value.copy(selectedVoiceId = select)
        afterVoiceChange()
    }

    private fun afterVoiceChange() {
        if (voiceHistory.present.none { it.id == _editor.value.selectedVoiceId }) {
            _editor.value = _editor.value.copy(selectedVoiceId = null)
        }
        publishVoice()
        val project = projectId.value ?: return
        val track = voiceTrack?.id ?: return
        viewModelScope.launch(NonCancellable) {
            writeLock.withLock {
                runCatching { projectRepository.saveTrackClips(project, track, voiceHistory.present) }
                    .onFailure { CineMindLogger.e(it, "Saving voiceover failed") }
            }
        }
    }

    private fun publishVoice() {
        val voice = voiceHistory.present.map { PreviewClip(it, assets[it.assetId]) }
        _editor.value = _editor.value.copy(
            voiceClips = voice,
            voiceSettings = voiceSettings(),
            canUndo = undoKinds.isNotEmpty(),
            canRedo = redoKinds.isNotEmpty(),
        )
        preview.setVoiceover(voice, voiceSettings())
    }

    // ---- Overlay track (picture-in-picture) ------------------------------------------------

    private var overlayHistory = EditHistory<List<Clip>>(emptyList())
    private var overlayTrackId: String? = null

    private fun mainLengthUs(): Long = TimelineEditing.durationUs(history?.present.orEmpty())

    /** Imports picked media and places it on the overlay track at the playhead (one undo step). */
    fun addOverlay(uri: Uri) {
        val project = projectId.value ?: return
        viewModelScope.launch {
            val asset = runCatching { importer.import(uri) }.getOrElse {
                Toast.makeText(appContext, "Couldn't read that file", Toast.LENGTH_SHORT).show()
                return@launch
            }
            assets[asset.id] = asset
            val track = overlayTrackId ?: projectRepository.ensureOverlayTrack(project).id.also { overlayTrackId = it }
            val length = if (asset.isVideo && asset.durationUs > 0) asset.durationUs else ProjectFactory.DEFAULT_IMAGE_DURATION_US
            val base = Clip(
                trackId = track, assetId = asset.id, timelineInUs = 0, timelineOutUs = 0, sourceInUs = 0, sourceOutUs = length,
                transformX = 0.5f, transformY = 0.32f, scale = OverlayEditing.DEFAULT_SCALE,
            )
            val placed = OverlayEditing.place(overlayHistory.present, base, preview.currentPositionUs(), length, mainLengthUs())
            if (placed == null) {
                Toast.makeText(appContext, "No room at the playhead: move the playhead to an empty spot on the overlay track", Toast.LENGTH_LONG).show()
                return@launch
            }
            val added = placed.first { it.id == base.id }
            commitOverlay(placed, select = added.id)
        }
    }

    fun onOverlayTapped(id: String) {
        val current = _editor.value.selectedOverlayId
        _editor.value = _editor.value.copy(selectedOverlayId = if (current == id) null else id, selectedClipId = null)
        overlayHistory.present.firstOrNull { it.id == id }?.let { preview.seekToUs(it.timelineInUs) }
        refreshPreview()
    }

    fun clearOverlaySelection() {
        _editor.value = _editor.value.copy(selectedOverlayId = null)
    }

    fun moveOverlay(deltaUs: Long) = editOverlay { clips, c -> OverlayEditing.move(clips, c.id, c.timelineInUs + deltaUs, mainLengthUs()) }

    fun trimOverlay(startDeltaUs: Long, endDeltaUs: Long) = editOverlay { clips, c ->
        val src = assets[c.assetId]?.takeIf { it.isVideo }?.durationUs
        OverlayEditing.trim(clips, c.id, c.timelineInUs + startDeltaUs, c.timelineOutUs + endDeltaUs, src, mainLengthUs())
    }

    fun setOverlayTransform(x: Float, y: Float, scale: Float, rotation: Float, opacity: Float) = editOverlay { clips, c ->
        OverlayEditing.setTransform(clips, c.id, x, y, scale, rotation, opacity)
    }

    fun deleteOverlay() {
        val id = _editor.value.selectedOverlayId ?: return
        commitOverlay(overlayHistory.present.filterNot { it.id == id }, select = null)
    }

    private fun editOverlay(op: (List<Clip>, Clip) -> List<Clip>) {
        val clips = overlayHistory.present
        val c = clips.firstOrNull { it.id == _editor.value.selectedOverlayId } ?: return
        commitOverlay(op(clips, c), select = c.id)
    }

    private fun commitOverlay(next: List<Clip>, select: String?) {
        if (!overlayHistory.push(next)) return
        undoKinds.addLast(Layer.OVERLAY)
        redoKinds.clear()
        _editor.value = _editor.value.copy(selectedOverlayId = select, selectedClipId = if (select != null) null else _editor.value.selectedClipId)
        afterOverlayChange()
    }

    private fun afterOverlayChange() {
        val present = overlayHistory.present
        if (present.none { it.id == _editor.value.selectedOverlayId }) {
            _editor.value = _editor.value.copy(selectedOverlayId = null)
        }
        publishOverlays()
        val project = projectId.value ?: return
        val track = overlayTrackId ?: return
        viewModelScope.launch(NonCancellable) {
            writeLock.withLock {
                runCatching { projectRepository.saveTrackClips(project, track, overlayHistory.present) }
                    .onFailure { CineMindLogger.e(it, "Saving overlays failed") }
            }
        }
    }

    private fun publishOverlays() {
        val overlays = overlayHistory.present.map { PreviewClip(it, assets[it.assetId]) }
        _editor.value = _editor.value.copy(overlays = overlays, canUndo = undoKinds.isNotEmpty(), canRedo = redoKinds.isNotEmpty())
        preview.setOverlays(overlays)
    }

    private fun editSelected(op: (List<Clip>, Clip) -> List<Clip>) {
        val clips = history?.present ?: return
        val clip = clips.firstOrNull { it.id == _editor.value.selectedClipId } ?: return
        commit(op(clips, clip), selectAfter = clip.id)
    }

    private fun commit(next: List<Clip>, selectAfter: String?) {
        val h = history ?: return
        if (!h.push(next)) return
        undoKinds.addLast(Layer.MAIN)
        redoKinds.clear()
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
            canUndo = undoKinds.isNotEmpty(),
            canRedo = redoKinds.isNotEmpty(),
        )
        preview.setClips(clips)
        // Colour grades for the preview (the same baked LUTs the export uses; cached per grade).
        preview.setGrades(lutLibrary.bakedForClips(h.present.associate { it.id to it.grade }))
        publishOverlays()
        publishVoice()
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
        recordingJob?.cancel()
        recorder.release()
        stopListening()
        preview.release()
        super.onCleared()
    }
}
