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
import com.cinemind.core.domain.editing.insertFreeze
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
    /** Titles, labels and stickers. */
    val texts: List<com.cinemind.core.domain.text.TextLayer> = emptyList(),
    val selectedTextId: String? = null,
    /** Song waveform peaks, beat times on the timeline and tempo (empty until analysed). */
    val musicPeaks: FloatArray? = null,
    val beatsUs: List<Long> = emptyList(),
    val bpm: Float = 0f,
    val analysingBeats: Boolean = false,
    /** Waveform peaks of recordings by asset id. */
    val waves: Map<String, FloatArray> = emptyMap(),
    /** Sound-effect tracks, top first, each with its sounds. */
    val sfx: List<List<PreviewClip>> = emptyList(),
    val selectedSfxId: String? = null,
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
    private val textRepository: com.cinemind.core.domain.repository.TextLayerRepository,
    private val llm: com.cinemind.feature.ai.llm.ondevice.OnDeviceLlm,
    private val assistant: com.cinemind.feature.ai.cloud.EditAssistant,
) : ViewModel() {

    private val preview = PreviewPlayer(appContext)

    /** The player the preview surface attaches to. Owned (and released) by this ViewModel. */
    val player: Player get() = preview.player
    /** The preview player (a new one after each rebuild; the video view follows it). */
    val playerFlow: StateFlow<Player> = preview.playerFlow
    /** True while the preview is being rebuilt after an edit (small loader). */
    val previewBuilding: StateFlow<Boolean> = preview.building

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
            textHistory = EditHistory(runCatching { textRepository.get(id) }.getOrDefault(emptyList()))
            _layerOrder.value = com.cinemind.feature.export.engine.LayerOrderStore.get(appContext, id)
            orderHistory = EditHistory(_layerOrder.value)
            sfxTracks.clear()
            timeline?.tracks.orEmpty().filter { it.type == TrackType.SFX }.sortedBy { it.index }.forEach { sfxTracks += it }
            val sfxClips = timeline?.clips.orEmpty().filter { c -> sfxTracks.any { it.id == c.trackId } }
            sfxClips.map { it.assetId }.distinct().forEach { assetId -> assets[assetId] = assetRepository.getAsset(assetId) }
            sfxHistory = EditHistory(sfxClips)
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
        _editor.value = _editor.value.copy(selectedClipId = if (alreadySelected) null else clip.id, selectedOverlayId = null, selectedTextId = null, selectedSfxId = null, selectedVoiceId = null)
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

    // ---- Sound effects (one or more SFX tracks) -----------------------------------------------

    private var sfxHistory = EditHistory<List<Clip>>(emptyList())
    private val sfxTracks = mutableListOf<com.cinemind.core.domain.model.Track>()
    private val maxSfxTracks = 4

    private fun sfxOf(trackId: String, clips: List<Clip> = sfxHistory.present) = clips.filter { it.trackId == trackId }

    /** Adds a built-in sound at the playhead (generated once into files/sfx). */
    fun addSoundEffect(effect: com.cinemind.core.domain.audio.SoundEffect) {
        viewModelScope.launch { addSoundEffectNow(effect, null) }
    }

    private suspend fun addSoundEffectNow(effect: com.cinemind.core.domain.audio.SoundEffect, atUs: Long?) {
        run {
            val asset = withContext(kotlinx.coroutines.Dispatchers.Default) {
                val dir = java.io.File(appContext.filesDir, "sfx").apply { mkdirs() }
                val file = java.io.File(dir, effect.name.lowercase() + ".wav")
                val samples = com.cinemind.core.domain.audio.SfxSynth.render(effect)
                if (!file.exists() || file.length() < 44) file.writeBytes(com.cinemind.core.domain.audio.SfxSynth.wav(samples))
                MediaAsset(
                    uri = Uri.fromFile(file).toString(),
                    mimeType = "audio/wav",
                    width = 0,
                    height = 0,
                    durationUs = com.cinemind.core.domain.audio.SfxSynth.durationUs(samples.size),
                    hasAudio = true,
                    displayName = effect.label,
                    sizeBytes = file.length(),
                )
            }
            assetRepository.createAsset(asset)
            placeSound(asset, atUs)
        }
    }

    /** Imports an audio file from the phone as a sound effect at the playhead. */
    fun importSound(uri: Uri) {
        viewModelScope.launch {
            val asset = runCatching { importer.import(uri) }.getOrNull()
            if (asset == null || !asset.isAudioFile || asset.durationUs <= 0) {
                Toast.makeText(appContext, "That file can't be used as a sound", Toast.LENGTH_SHORT).show()
                return@launch
            }
            placeSound(asset)
        }
    }

    /** Puts a sound at the playhead on the first sound track with room (a new track if none has). */
    private suspend fun placeSound(asset: MediaAsset, atUs: Long? = null) {
        val project = projectId.value ?: return
        assets[asset.id] = asset
        val total = mainLengthUs()
        if (total <= 0) return
        val at = (atUs ?: preview.currentPositionUs()).coerceIn(0, (total - 100_000).coerceAtLeast(0))
        val length = minOf(asset.durationUs, total - at)
        for (t in sfxTracks) {
            val base = Clip(trackId = t.id, assetId = asset.id, timelineInUs = 0, timelineOutUs = 0, sourceInUs = 0, sourceOutUs = length, opacity = 1f)
            val placed = OverlayEditing.place(sfxOf(t.id), base, at, length, total)
            if (placed != null) {
                commitSfx(sfxHistory.present.filter { it.trackId != t.id } + placed, select = base.id)
                return
            }
        }
        if (sfxTracks.size >= maxSfxTracks) {
            Toast.makeText(appContext, "All $maxSfxTracks sound tracks are busy here. Move the playhead or remove a sound.", Toast.LENGTH_LONG).show()
            return
        }
        val track = projectRepository.addSfxTrack(project)
        sfxTracks += track
        val base = Clip(trackId = track.id, assetId = asset.id, timelineInUs = 0, timelineOutUs = 0, sourceInUs = 0, sourceOutUs = length, opacity = 1f)
        val placed = OverlayEditing.place(emptyList(), base, at, length, total) ?: return
        commitSfx(sfxHistory.present + placed, select = base.id)
    }

    fun onSfxTapped(id: String) {
        val current = _editor.value.selectedSfxId
        _editor.value = _editor.value.copy(selectedSfxId = if (current == id) null else id, selectedClipId = null, selectedOverlayId = null, selectedVoiceId = null)
        sfxHistory.present.firstOrNull { it.id == id }?.let { preview.seekToUs(it.timelineInUs) }
        refreshPreview()
    }

    fun clearSfxSelection() {
        _editor.value = _editor.value.copy(selectedSfxId = null)
    }

    private fun editSfx(op: (List<Clip>, Clip) -> List<Clip>) {
        val c = sfxHistory.present.firstOrNull { it.id == _editor.value.selectedSfxId } ?: return
        val next = op(sfxOf(c.trackId), c)
        commitSfx(sfxHistory.present.filter { it.trackId != c.trackId } + next, select = c.id)
    }

    fun moveSfx(deltaUs: Long) = editSfx { clips, c -> OverlayEditing.move(clips, c.id, c.timelineInUs + deltaUs, mainLengthUs()) }

    fun trimSfx(startDeltaUs: Long, endDeltaUs: Long) = editSfx { clips, c ->
        OverlayEditing.trim(clips, c.id, c.timelineInUs + startDeltaUs, c.timelineOutUs + endDeltaUs, assets[c.assetId]?.durationUs, mainLengthUs())
    }

    /** Volume of one sound, 0..2 (kept in the clip's opacity field). */
    fun setSfxVolume(volume: Float) = editSfx { clips, c -> clips.map { if (it.id == c.id) it.copy(opacity = volume.coerceIn(0f, 2f)) else it } }

    fun setSfxFades(fadeInMs: Long, fadeOutMs: Long) = editSfx { clips, c ->
        clips.map { if (it.id == c.id) it.copy(transitionInMs = fadeInMs.coerceAtLeast(0), transitionOutMs = fadeOutMs.coerceAtLeast(0)) else it }
    }

    fun deleteSfx() {
        val id = _editor.value.selectedSfxId ?: return
        commitSfx(sfxHistory.present.filterNot { it.id == id }, select = null)
    }

    private fun commitSfx(next: List<Clip>, select: String?) {
        if (!sfxHistory.push(next)) return
        undoKinds.addLast(Layer.SFX)
        redoKinds.clear()
        _editor.value = _editor.value.copy(selectedSfxId = select, selectedClipId = if (select != null) null else _editor.value.selectedClipId)
        afterSfxChange()
    }

    private fun afterSfxChange() {
        if (sfxHistory.present.none { it.id == _editor.value.selectedSfxId }) {
            _editor.value = _editor.value.copy(selectedSfxId = null)
        }
        publishSfx()
        val project = projectId.value ?: return
        val present = sfxHistory.present
        val tracks = sfxTracks.toList()
        viewModelScope.launch(NonCancellable) {
            writeLock.withLock {
                tracks.forEach { t ->
                    runCatching { projectRepository.saveTrackClips(project, t.id, present.filter { it.trackId == t.id }) }
                        .onFailure { CineMindLogger.e(it, "Saving sound effects failed") }
                }
            }
        }
    }

    private fun publishSfx() {
        val tracks = sfxTracks.map { t -> sfxOf(t.id).sortedBy { it.timelineInUs }.map { PreviewClip(it, assets[it.assetId]) } }
        _editor.value = _editor.value.copy(sfx = tracks, canUndo = undoKinds.isNotEmpty(), canRedo = redoKinds.isNotEmpty())
        preview.setSfx(tracks)
        analyseWaves(tracks.flatten().map { it.asset })
    }

    // ---- Context-aware graphics -----------------------------------------------------------------

    data class GraphicsContext(val projectName: String, val brief: String, val totalUs: Long, val filmedAt: Long?, val aiAvailable: Boolean)

    suspend fun graphicsContext(): GraphicsContext {
        val brief = projectId.value?.let { id -> runCatching { briefRepository.observeBrief(id).first() }.getOrNull() }
        val briefText = listOfNotNull(brief?.prompt, brief?.style?.label).joinToString(" ")
        val firstDate = history?.present.orEmpty().firstNotNullOfOrNull { assets[it.assetId]?.dateTaken }
        return GraphicsContext(project.value?.name.orEmpty(), briefText, mainLengthUs(), firstDate, llm.installedModel() != null)
    }

    /** Title options written by the on-device AI (empty if no model or it fails). */
    suspend fun aiTitles(ctx: GraphicsContext): List<String> = runCatching {
        com.cinemind.core.common.BackgroundWork.run(appContext, "Writing titles") {
            com.cinemind.core.domain.text.GraphicsAdvisor.parseTitles(
                llm.ask(com.cinemind.core.domain.text.GraphicsAdvisor.titlePrompt(ctx.projectName, ctx.brief), maxOutputTokens = 64),
            )
        }
    }.getOrElse {
        CineMindLogger.w("AI titles failed: ${it.message}")
        emptyList()
    }

    /** Adds the chosen suggestions as normal text layers (one undo step). */
    fun addGraphics(items: List<com.cinemind.core.domain.text.GraphicSuggestion>) {
        val project = projectId.value ?: return
        if (items.isEmpty()) return
        var layers = textHistory.present
        items.forEach { s -> layers = layers + com.cinemind.core.domain.text.GraphicsAdvisor.toLayer(project, s, layers) }
        commitText(layers, select = null)
        Toast.makeText(appContext, "Added ${items.size} graphic(s); tap one on the preview to edit it", Toast.LENGTH_SHORT).show()
    }

    // ---- Layer order (what shows above / below) -------------------------------------------------

    private val _layerOrder = MutableStateFlow(com.cinemind.core.domain.text.LayerOrder())
    val layerOrder: StateFlow<com.cinemind.core.domain.text.LayerOrder> = _layerOrder.asStateFlow()
    private var previewCaptions: com.cinemind.feature.export.engine.RenderCaptions? = null

    /** The editor's captions (owned by the captions screen model), for layers drawn under the PiP. */
    fun setPreviewCaptions(captions: List<com.cinemind.core.domain.captions.Caption>, style: com.cinemind.core.domain.captions.CaptionStyle?) {
        previewCaptions = if (captions.isEmpty() || style == null) null else com.cinemind.feature.export.engine.RenderCaptions(captions, style)
        pushUnderLayers()
    }

    private fun pushUnderLayers() {
        val order = _layerOrder.value
        preview.setUnderLayers(
            textHistory.present.filter { it.styleObj.belowOverlays },
            previewCaptions.takeIf { !order.captionsAboveOverlays },
            order,
        )
    }

    /** The visual stack, top first (for the Layers sheet). */
    fun layerEntries(): List<com.cinemind.core.domain.text.LayerEntry> =
        com.cinemind.core.domain.text.LayerStack.entries(textHistory.present, _layerOrder.value, previewCaptions != null, overlayHistory.present.isNotEmpty())

    /**
    /**
     * Timeline drag up/down: moves the stack entry with [key] by [steps] places (negative = to the
     * front) in one go. One undo step, whether it changed titles, the caption order, or both.
     */
    fun moveLayerBy(key: String, steps: Int) {
        if (steps == 0) return
        val (texts, order) = com.cinemind.core.domain.text.LayerStack.moveBy(
            textHistory.present, orderHistory.present, previewCaptions != null, overlayHistory.present.isNotEmpty(), key, steps,
        )
        commitStack(texts, order)
    }

    /** Moves one entry of the stack up or down (Layers sheet). One undo step. */
    fun moveLayer(entry: com.cinemind.core.domain.text.LayerEntry, up: Boolean) {
        val (texts, order) = com.cinemind.core.domain.text.LayerStack.move(
            textHistory.present, orderHistory.present, previewCaptions != null, overlayHistory.present.isNotEmpty(), entry, up,
        )
        commitStack(texts, order)
    }

    /** Caption order (above/below titles and the PiP), undoable like any edit; saved per project. */
    private var orderHistory = EditHistory(com.cinemind.core.domain.text.LayerOrder())

    private fun commitStack(texts: List<com.cinemind.core.domain.text.TextLayer>, order: com.cinemind.core.domain.text.LayerOrder) {
        val textChanged = texts != textHistory.present
        val orderChanged = order != orderHistory.present
        if (!textChanged && !orderChanged) return
        if (textChanged) textHistory.push(texts)
        if (orderChanged) orderHistory.push(order)
        undoKinds.addLast(
            when {
                textChanged && orderChanged -> Layer.TEXT_ORDER
                textChanged -> Layer.TEXT
                else -> Layer.ORDER
            },
        )
        redoKinds.clear()
        if (textChanged) afterTextChange()
        if (orderChanged) afterOrderChange() else publishTexts()
    }

    private fun afterOrderChange() {
        _layerOrder.value = orderHistory.present
        projectId.value?.let { com.cinemind.feature.export.engine.LayerOrderStore.set(appContext, it, orderHistory.present) }
        publishTexts()
    }


    // ---- Text to speech ----------------------------------------------------------------------

    private val speechSynth by lazy { com.cinemind.feature.editor.timeline.voiceover.SpeechSynth(appContext) }

    suspend fun ttsVoices(): List<com.cinemind.feature.editor.timeline.voiceover.TtsVoice> =
        runCatching { speechSynth.voices() }.getOrElse {
            CineMindLogger.w("No text-to-speech voices: ${it.message}")
            emptyList()
        }

    /**
     * Speaks [text] with the phone's own voice and puts it on the voiceover track at the playhead
     * (shortened if it would run past the end or into another recording). One undo step.
     * [onDone] gets null on success, else a message.
     */
    fun addSpeech(text: String, voice: String?, rate: Float, pitch: Float, onDone: (String?) -> Unit) {
        val project = projectId.value ?: return
        val at = preview.currentPositionUs()
        viewModelScope.launch {
            val result = runCatching {
                val file = com.cinemind.core.common.BackgroundWork.run(appContext, "Making the voice") { speechSynth.synthesize(text, voice, rate, pitch) }
                val durationUs = withContext(kotlinx.coroutines.Dispatchers.IO) {
                    android.media.MediaMetadataRetriever().use { r ->
                        r.setDataSource(file.absolutePath)
                        (r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L) * 1_000
                    }
                }
                require(durationUs > 0) { "The speech came out empty" }
                val asset = MediaAsset(
                    uri = Uri.fromFile(file).toString(),
                    mimeType = "audio/wav",
                    width = 0,
                    height = 0,
                    durationUs = durationUs,
                    hasAudio = true,
                    displayName = "Voice: " + text.take(24),
                    sizeBytes = file.length(),
                )
                assetRepository.createAsset(asset)
                assets[asset.id] = asset
                val track = voiceTrack ?: projectRepository.ensureVoiceoverTrack(project).also { voiceTrack = it }
                val base = Clip(trackId = track.id, assetId = asset.id, timelineInUs = 0, timelineOutUs = 0, sourceInUs = 0, sourceOutUs = durationUs)
                val placed = OverlayEditing.place(voiceHistory.present, base, at, durationUs, mainLengthUs())
                    ?: error("There's already a voiceover at the playhead. Move the playhead to an empty spot.")
                commitVoice(placed, select = base.id)
            }
            onDone(result.exceptionOrNull()?.message)
        }
    }

    // ---- Reverse ------------------------------------------------------------------------------

    private val _reversing = MutableStateFlow<Float?>(null)
    /** Progress 0..1 while a reversed copy is being made (null otherwise). */
    val reversing: StateFlow<Float?> = _reversing.asStateFlow()

    /**
     * Plays the selected video clip backwards: makes a reversed, silent copy of its range (kept in
     * app storage) and swaps it in. One undo step (undo brings the original back).
     */
    fun reverseSelected() {
        val clips = history?.present ?: return
        val clip = clips.firstOrNull { it.id == _editor.value.selectedClipId } ?: return
        val asset = assets[clip.assetId]?.takeIf { it.isVideo } ?: run {
            Toast.makeText(appContext, "Reverse works on video clips", Toast.LENGTH_SHORT).show()
            return
        }
        if (_reversing.value != null) return
        _reversing.value = 0f
        viewModelScope.launch {
            val result = runCatching {
                com.cinemind.core.common.BackgroundWork.run(appContext, "Reversing a clip") {
                    com.cinemind.feature.editor.timeline.reverse.ReverseMaker(appContext)
                        .reverse(Uri.parse(asset.uri), clip.sourceInUs, clip.sourceOutUs) { _reversing.value = it }
                }
            }
            _reversing.value = null
            result.onFailure {
                CineMindLogger.e(it, "Reverse failed")
                Toast.makeText(appContext, it.message ?: "Couldn't reverse this clip", Toast.LENGTH_LONG).show()
            }
            val r = result.getOrNull() ?: return@launch
            val reversed = MediaAsset(
                uri = Uri.fromFile(r.file).toString(),
                mimeType = "video/mp4",
                width = r.width,
                height = r.height,
                durationUs = r.durationUs,
                frameRate = r.fps,
                hasAudio = false,
                displayName = "Reversed " + asset.displayName,
                sizeBytes = r.file.length(),
            )
            assetRepository.createAsset(reversed)
            assets[reversed.id] = reversed
            val current = history?.present ?: return@launch
            // Ramps and motion keyframes refer to the original footage's times: they're dropped.
            val framing = com.cinemind.core.domain.editing.ClipFraming.decode(clip.framing).copy(keys = emptyList())
            val next = current.map {
                if (it.id != clip.id) it
                else it.copy(
                    assetId = reversed.id,
                    sourceInUs = 0,
                    sourceOutUs = r.durationUs,
                    speedCurve = com.cinemind.core.domain.editing.SpeedMath.withKeepPitch("", com.cinemind.core.domain.editing.SpeedMath.keepsPitch(it)),
                    framing = framing.encode(),
                )
            }
            commit(TimelineEditing.relayout(next), selectAfter = clip.id)
            Toast.makeText(appContext, "Reversed (no sound). Undo to get the original back.", Toast.LENGTH_SHORT).show()
        }
    }

    // ---- Assistant ---------------------------------------------------------------------------

    data class AssistantTurn(val request: String, val reply: String? = null, val actions: List<com.cinemind.core.domain.assistant.AssistantAction> = emptyList(), val error: String? = null, val applied: Boolean = false)

    private val _assistant = MutableStateFlow<List<AssistantTurn>>(emptyList())
    /** The assistant conversation for this editing session (not saved). */
    val assistantTurns: StateFlow<List<AssistantTurn>> = _assistant.asStateFlow()
    private val _assistantBusy = MutableStateFlow(false)
    val assistantBusy: StateFlow<Boolean> = _assistantBusy.asStateFlow()
    val assistantEngine: String get() = assistant.engineName

    private fun projectBrief(): com.cinemind.core.domain.assistant.ProjectBrief {
        val clips = history?.present.orEmpty()
        return com.cinemind.core.domain.assistant.ProjectBrief(
            name = project.value?.name.orEmpty(),
            totalSeconds = mainLengthUs() / 1e6,
            clips = clips.map { c ->
                val a = assets[c.assetId]
                val kind = if (a?.isVideo == true) "video" else "photo"
                val look = com.cinemind.core.domain.color.ColorGrade.decode(c.grade).lutId?.removePrefix("builtin:")
                buildString {
                    append(kind).append(" %.1f s".format(java.util.Locale.ROOT, (c.timelineOutUs - c.timelineInUs) / 1e6))
                    if (c.speed != 1f) append(", speed ").append(c.speed).append("×")
                    if (look != null) append(", look ").append(look)
                    if (c.transitionInType != "CUT") append(", transition in ").append(c.transitionInType)
                }
            },
            titles = textHistory.present.filter { it.kind == com.cinemind.core.domain.text.TextKind.TEXT.name }.map { it.text },
            captionsSample = previewCaptions?.captions?.joinToString(" ") { it.text }.orEmpty().take(400),
            bpm = _editor.value.bpm,
            hasMusic = _editor.value.music != null,
        )
    }

    fun askAssistant(request: String) {
        val text = request.trim()
        if (text.isEmpty() || _assistantBusy.value) return
        _assistant.value = _assistant.value + AssistantTurn(text)
        _assistantBusy.value = true
        viewModelScope.launch {
            val result = runCatching { assistant.plan(projectBrief(), text) }
            result.onFailure { CineMindLogger.e(it, "Assistant failed") }
            val turn = result.fold(
                onSuccess = { AssistantTurn(text, it.reply, it.actions) },
                onFailure = { AssistantTurn(text, error = it.message ?: "Something went wrong") },
            )
            _assistant.value = _assistant.value.dropLast(1) + turn
            _assistantBusy.value = false
        }
    }

    /**
     * Applies the proposed edits of turn [index] (each one is a normal undo step). Returns a music
     * mood when one of them asks to find music (the screen opens music ideas).
     */
    fun applyAssistant(index: Int, onMusic: (String) -> Unit) {
        val turn = _assistant.value.getOrNull(index) ?: return
        if (turn.applied || turn.actions.isEmpty()) return
        _assistant.value = _assistant.value.mapIndexed { i, t -> if (i == index) t.copy(applied = true) else t }
        viewModelScope.launch {
            val total = mainLengthUs()
            fun at(seconds: Double) = (seconds * 1e6).toLong().coerceIn(0, (total - 100_000).coerceAtLeast(0))
            for (a in turn.actions) {
                val clips = history?.present.orEmpty()
                val clipId = if (a.clip in 1..clips.size) clips[a.clip - 1].id else null
                when (a.type) {
                    com.cinemind.core.domain.assistant.AssistantActionType.ADD_TITLE ->
                        addLayer(a.text, com.cinemind.core.domain.text.TextKind.TEXT, com.cinemind.core.domain.text.TextPreset.entries.firstOrNull { it.name == a.name } ?: com.cinemind.core.domain.text.TextPreset.BOLD_TITLE, at(a.atSeconds))
                    com.cinemind.core.domain.assistant.AssistantActionType.ADD_STICKER ->
                        addLayer(a.name, com.cinemind.core.domain.text.TextKind.STICKER, null, at(a.atSeconds))
                    com.cinemind.core.domain.assistant.AssistantActionType.APPLY_LOOK -> {
                        val look = com.cinemind.core.domain.color.BuiltInLooks.byKey(a.name) ?: continue
                        val grade = com.cinemind.core.domain.color.ColorGrade(lutId = look.id)
                        val encoded = grade.encode()
                        commit(clips.map { c -> if (clipId == null || c.id == clipId) c.copy(grade = encoded) else c }, selectAfter = _editor.value.selectedClipId)
                    }
                    com.cinemind.core.domain.assistant.AssistantActionType.SET_SPEED ->
                        setClipSpeed(clipId ?: clips.firstOrNull()?.id ?: continue, a.value.toFloat(), allClips = clipId == null)
                    com.cinemind.core.domain.assistant.AssistantActionType.SET_TRANSITIONS ->
                        applyTransitionToAll(ClipTransition.fromName(a.name), (a.value.coerceIn(0.2, 1.5) * 1000).toLong())
                    com.cinemind.core.domain.assistant.AssistantActionType.CUT_TO_BEATS -> cutToBeats(a.value.toInt())
                    com.cinemind.core.domain.assistant.AssistantActionType.ADD_SOUND -> {
                        val effect = com.cinemind.core.domain.audio.SoundEffect.entries.firstOrNull { it.name == a.name } ?: continue
                        addSoundEffectNow(effect, at(a.atSeconds))
                    }
                    com.cinemind.core.domain.assistant.AssistantActionType.FIND_MUSIC -> onMusic(a.text)
                }
            }
        }
    }

    fun clearAssistant() {
        if (!_assistantBusy.value) _assistant.value = emptyList()
    }

    // ---- Text, titles & stickers -------------------------------------------------------------

    private var textHistory = EditHistory<List<com.cinemind.core.domain.text.TextLayer>>(emptyList())

    /** Adds a title at the playhead in the [preset] look (one undo step) and selects it. */
    fun addText(text: String, preset: com.cinemind.core.domain.text.TextPreset?) =
        addLayer(text, com.cinemind.core.domain.text.TextKind.TEXT, preset)

    /** Adds an emoji / symbol sticker at the playhead. */
    fun addSticker(symbol: String) = addLayer(symbol, com.cinemind.core.domain.text.TextKind.STICKER, null)

    private fun addLayer(text: String, kind: com.cinemind.core.domain.text.TextKind, preset: com.cinemind.core.domain.text.TextPreset?, atUs: Long? = null) {
        val project = projectId.value ?: return
        val total = mainLengthUs()
        if (total <= 0) return
        val layer = com.cinemind.core.domain.text.TextLayerEditing.create(project, textHistory.present, atUs ?: preview.currentPositionUs(), total, text, kind, preset)
        commitText(textHistory.present + layer, select = layer.id)
    }

    /** Replaces one layer (text, look, position…), one undo step. */
    fun updateText(layer: com.cinemind.core.domain.text.TextLayer) {
        if (textHistory.present.none { it.id == layer.id }) return
        commitText(textHistory.present.map { if (it.id == layer.id) layer else it }, select = layer.id)
    }

    fun selectText(id: String?) {
        _editor.value = _editor.value.copy(selectedTextId = id, selectedClipId = if (id != null) null else _editor.value.selectedClipId, selectedOverlayId = if (id != null) null else _editor.value.selectedOverlayId)
        textHistory.present.firstOrNull { it.id == id }?.let { l ->
            val pos = preview.currentPositionUs()
            if (pos < l.startUs || pos >= l.endUs) preview.seekToUs(l.startUs + minOf(500_000L, l.lengthUs / 2))
        }
        refreshPreview()
    }

    fun deleteText(id: String) = commitText(textHistory.present.filterNot { it.id == id }, select = null)

    fun duplicateText(id: String) {
        val next = com.cinemind.core.domain.text.TextLayerEditing.duplicate(textHistory.present, id, mainLengthUs())
        commitText(next, select = next.lastOrNull()?.id)
    }

    fun moveText(id: String, deltaUs: Long) =
        commitText(com.cinemind.core.domain.text.TextLayerEditing.move(textHistory.present, id, deltaUs, mainLengthUs()), select = id)

    fun trimText(id: String, startDeltaUs: Long, endDeltaUs: Long) =
        commitText(com.cinemind.core.domain.text.TextLayerEditing.trim(textHistory.present, id, startDeltaUs, endDeltaUs, mainLengthUs()), select = id)

    fun bringTextToFront(id: String) = commitText(com.cinemind.core.domain.text.TextLayerEditing.bringToFront(textHistory.present, id), select = id)

    private fun commitText(next: List<com.cinemind.core.domain.text.TextLayer>, select: String?) {
        if (!textHistory.push(next)) return
        undoKinds.addLast(Layer.TEXT)
        redoKinds.clear()
        _editor.value = _editor.value.copy(selectedTextId = select)
        afterTextChange()
    }

    private fun afterTextChange() {
        if (textHistory.present.none { it.id == _editor.value.selectedTextId }) {
            _editor.value = _editor.value.copy(selectedTextId = null)
        }
        publishTexts()
        val project = projectId.value ?: return
        val layers = textHistory.present
        viewModelScope.launch(NonCancellable) {
            writeLock.withLock {
                runCatching { textRepository.save(project, layers) }
                    .onFailure { CineMindLogger.e(it, "Saving text layers failed") }
            }
        }
    }

    private fun publishTexts() {
        _editor.value = _editor.value.copy(texts = textHistory.present, canUndo = undoKinds.isNotEmpty(), canRedo = redoKinds.isNotEmpty())
        pushUnderLayers()
    }

    // ---- Framing (crop, rotate, flip, position, keyframes) -----------------------------------

    /** What the framing tool edits: the selected clip (or the one under the playhead) and sizes. */
    data class FramingTarget(val clip: Clip, val sourceWidth: Int, val sourceHeight: Int, val frameWidth: Int, val frameHeight: Int)

    fun framingTarget(): FramingTarget? {
        val clips = history?.present.orEmpty()
        val pos = preview.currentPositionUs()
        val clip = clips.firstOrNull { it.id == _editor.value.selectedClipId }
            ?: clips.firstOrNull { pos >= it.timelineInUs && pos < it.timelineOutUs }
            ?: return null
        val asset = assets[clip.assetId]
        val p = project.value
        return FramingTarget(clip, asset?.width ?: 0, asset?.height ?: 0, p?.resolutionWidth ?: 1080, p?.resolutionHeight ?: 1920)
    }

    /** Playhead as an offset into [clip] (0 when the playhead is outside it). */
    fun playheadOffsetIn(clip: Clip): Long =
        (preview.currentPositionUs() - clip.timelineInUs).coerceIn(0, (clip.timelineOutUs - clip.timelineInUs - 1).coerceAtLeast(0))

    /** Moves the playhead to [offsetUs] inside [clip] (keyframe editing). */
    fun seekInClip(clip: Clip, offsetUs: Long, exact: Boolean) {
        val at = clip.timelineInUs + offsetUs.coerceIn(0, (clip.timelineOutUs - clip.timelineInUs - 1).coerceAtLeast(0))
        if (exact) scrubFinished(at) else scrubTo(at)
    }

    /** Look of [clip] at the playhead (for dragging it on the preview). */
    fun lookAtPlayhead(clip: Clip): com.cinemind.core.domain.editing.MotionLook =
        com.cinemind.core.domain.editing.TransformMath.lookAtTimeline(clip, com.cinemind.core.domain.editing.ClipFraming.decode(clip.framing), playheadOffsetIn(clip))

    /** Sets the clip's position/zoom/angle from a gesture on the preview: keyframe at the playhead if it already animates. */
    fun setClipLook(clipId: String, look: com.cinemind.core.domain.editing.MotionLook) {
        val clip = history?.present?.firstOrNull { it.id == clipId } ?: return
        val framing = com.cinemind.core.domain.editing.ClipFraming.decode(clip.framing)
        val sourceUs = clip.sourceInUs + com.cinemind.core.domain.editing.SpeedMath.timelineToSource(clip, playheadOffsetIn(clip))
        setFraming(clipId, com.cinemind.core.domain.editing.TransformMath.setLook(framing, sourceUs, look, animate = framing.keys.size >= 2))
    }

    /** Framing for one clip (one undo step). */
    fun setFraming(clipId: String, framing: com.cinemind.core.domain.editing.ClipFraming) {
        val clips = history?.present ?: return
        val encoded = framing.encode()
        if (clips.none { it.id == clipId && it.framing != encoded }) return
        commit(clips.map { if (it.id == clipId) it.copy(framing = encoded) else it }, selectAfter = clipId)
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
            if (isVideo && (allClips || c.id == clipId)) c.copy(speed = s, speedCurve = com.cinemind.core.domain.editing.SpeedMath.withKeepPitch("", com.cinemind.core.domain.editing.SpeedMath.keepsPitch(c))) else c
        }
        commit(TimelineEditing.relayout(next), selectAfter = clipId)
    }

    /** Keep the sound's pitch when this clip is sped up or slowed (one undo step). */
    fun setKeepPitch(clipId: String, on: Boolean) {
        val clips = history?.present ?: return
        commit(clips.map { if (it.id == clipId) it.copy(speedCurve = com.cinemind.core.domain.editing.SpeedMath.withKeepPitch(it.speedCurve, on)) else it }, selectAfter = clipId)
    }

    /** Speed ramp for one clip (one undo step); fewer than two points means back to constant speed. */
    fun setClipRamp(clipId: String, points: List<com.cinemind.core.domain.editing.SpeedPoint>) {
        val clips = history?.present ?: return
        val next = clips.map { c ->
            if (c.id != clipId) c
            else if (points.size < 2) c.copy(speed = points.firstOrNull()?.speed ?: 1f, speedCurve = com.cinemind.core.domain.editing.SpeedMath.withKeepPitch("", com.cinemind.core.domain.editing.SpeedMath.keepsPitch(c)))
            else c.copy(speed = 1f, speedCurve = com.cinemind.core.domain.editing.SpeedMath.withKeepPitch(com.cinemind.core.domain.editing.SpeedMath.encode(points), com.cinemind.core.domain.editing.SpeedMath.keepsPitch(c)))
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
        analyseMusic()
    }

    // ---- Waveforms & beats --------------------------------------------------------------------

    private val audioAnalysis = com.cinemind.feature.editor.timeline.audio.AudioAnalysis(appContext)
    private var musicBeats: com.cinemind.core.domain.audio.BeatInfo? = null
    private var analysedMusicId: String? = null

    /** Decodes the song once (cached) for its waveform and beats; shows "finding beats…" meanwhile. */
    private fun analyseMusic() {
        val a = musicAsset
        if (a == null) {
            musicBeats = null
            analysedMusicId = null
            _editor.value = _editor.value.copy(musicPeaks = null, beatsUs = emptyList(), bpm = 0f, analysingBeats = false)
            return
        }
        if (analysedMusicId == a.id) { refreshBeats(); return }
        analysedMusicId = a.id
        _editor.value = _editor.value.copy(analysingBeats = true)
        viewModelScope.launch {
            val peaks = audioAnalysis.peaks(a)
            _editor.value = _editor.value.copy(musicPeaks = peaks)
            musicBeats = audioAnalysis.beats(a)
            _editor.value = _editor.value.copy(analysingBeats = false)
            refreshBeats()
        }
    }

    /** Beat times on the timeline (they move with the song's start point and loop). */
    private fun refreshBeats() {
        val b = musicBeats
        val a = musicAsset
        val m = audio.music
        val total = mainLengthUs()
        val beats = if (b == null || a == null || m == null || total <= 0) emptyList()
        else com.cinemind.core.domain.audio.BeatSync.beatsOnTimeline(b, a.durationUs, m.startOffsetUs, total)
        _editor.value = _editor.value.copy(beatsUs = beats, bpm = b?.bpm ?: 0f)
    }

    /** Waveforms of voiceover / sound-effect recordings (decoded once each). */
    private fun analyseWaves(items: List<MediaAsset?>) {
        items.filterNotNull().filter { it.id !in _editor.value.waves }.distinctBy { it.id }.forEach { a ->
            audioAnalysis.cachedPeaks(a)?.let { p -> _editor.value = _editor.value.copy(waves = _editor.value.waves + (a.id to p)); return@forEach }
            viewModelScope.launch {
                audioAnalysis.peaks(a)?.let { p -> _editor.value = _editor.value.copy(waves = _editor.value.waves + (a.id to p)) }
            }
        }
    }

    /**
     * Shortens clips so each cut lands on the music's beat (every [every]-th beat), one undo step.
     * Videos only get shorter; photos may grow to the next beat.
     */
    fun cutToBeats(every: Int) {
        val clips = history?.present ?: return
        val beats = _editor.value.beatsUs
        if (beats.isEmpty()) {
            Toast.makeText(appContext, "No beats found in this song yet", Toast.LENGTH_SHORT).show()
            return
        }
        val next = com.cinemind.core.domain.audio.BeatSync.cutToBeats(clips, beats, every, isStill = { limitsFor(it).isStill })
        val off = com.cinemind.core.domain.audio.BeatSync.offBeatUs(next, beats)
        val onBeat = off.count { it <= 40_000 }
        commit(next, selectAfter = _editor.value.selectedClipId)
        Toast.makeText(appContext, "$onBeat of ${off.size} cuts are on the beat", Toast.LENGTH_SHORT).show()
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
    private enum class Layer { MAIN, OVERLAY, VOICE, TEXT, SFX, ORDER, TEXT_ORDER }
    private val undoKinds = ArrayDeque<Layer>()
    private val redoKinds = ArrayDeque<Layer>()

    private fun historyOf(layer: Layer): EditHistory<List<Clip>>? = when (layer) {
        Layer.MAIN -> history
        Layer.OVERLAY -> overlayHistory
        Layer.VOICE -> voiceHistory
        Layer.TEXT, Layer.ORDER, Layer.TEXT_ORDER -> null
        Layer.SFX -> sfxHistory
    }

    private fun afterChange(layer: Layer) = when (layer) {
        Layer.MAIN -> afterHistoryChange()
        Layer.OVERLAY -> afterOverlayChange()
        Layer.VOICE -> afterVoiceChange()
        Layer.TEXT -> afterTextChange()
        Layer.SFX -> afterSfxChange()
        Layer.ORDER -> afterOrderChange()
        Layer.TEXT_ORDER -> { afterTextChange(); afterOrderChange() }
    }

    fun undo() {
        val layer = undoKinds.removeLastOrNull() ?: return
        val ok = when (layer) {
            Layer.TEXT -> textHistory.undo()
            Layer.ORDER -> orderHistory.undo()
            Layer.TEXT_ORDER -> textHistory.undo() and orderHistory.undo()
            else -> historyOf(layer)?.undo() == true
        }
        if (!ok) return
        redoKinds.addLast(layer)
        afterChange(layer)
    }

    fun redo() {
        val layer = redoKinds.removeLastOrNull() ?: return
        val ok = when (layer) {
            Layer.TEXT -> textHistory.redo()
            Layer.ORDER -> orderHistory.redo()
            Layer.TEXT_ORDER -> textHistory.redo() and orderHistory.redo()
            else -> historyOf(layer)?.redo() == true
        }
        if (!ok) return
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
        analyseWaves(voice.map { it.asset })
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

    /** Selection without toggling or seeking (timeline drag start). */
    fun selectVoice(id: String) {
        _editor.value = _editor.value.copy(selectedVoiceId = id, selectedClipId = null, selectedOverlayId = null, selectedSfxId = null, selectedTextId = null)
    }

    fun selectSfx(id: String) {
        _editor.value = _editor.value.copy(selectedSfxId = id, selectedClipId = null, selectedOverlayId = null, selectedVoiceId = null, selectedTextId = null)
    }

    /** Moves a main-track clip to another place in the order (one undo step). */
    fun moveClip(from: Int, to: Int) {
        val clips = history?.present ?: return
        val id = clips.getOrNull(from)?.id ?: return
        commit(TimelineEditing.move(clips, from, to), selectAfter = id)
    }

    /** Selects an overlay tapped on the preview (no seeking); null clears. */
    fun selectOverlay(id: String?) {
        _editor.value = _editor.value.copy(selectedOverlayId = id, selectedClipId = if (id != null) null else _editor.value.selectedClipId, selectedTextId = if (id != null) null else _editor.value.selectedTextId)
    }

    fun clearOverlaySelection() {
        _editor.value = _editor.value.copy(selectedOverlayId = null)
    }

    fun moveOverlay(deltaUs: Long) = editOverlay { clips, c -> OverlayEditing.move(clips, c.id, c.timelineInUs + deltaUs, mainLengthUs()) }

    fun trimOverlay(startDeltaUs: Long, endDeltaUs: Long) = editOverlay { clips, c ->
        val src = assets[c.assetId]?.takeIf { it.isVideo }?.durationUs
        OverlayEditing.trim(clips, c.id, c.timelineInUs + startDeltaUs, c.timelineOutUs + endDeltaUs, src, mainLengthUs())
    }

    /** Green screen / mask of the selected overlay (one undo step). */
    fun setOverlayKeyMask(chroma: com.cinemind.core.domain.editing.ChromaKey?, mask: com.cinemind.core.domain.editing.MaskShape, feather: Float) = editOverlay { clips, c ->
        clips.map {
            if (it.id != c.id) it
            else it.copy(framing = com.cinemind.core.domain.editing.ClipFraming.decode(it.framing).copy(chroma = chroma, mask = mask, maskFeather = feather.coerceIn(0f, 0.3f)).encode())
        }
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
        publishTexts()
        refreshBeats()
        publishSfx()
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
