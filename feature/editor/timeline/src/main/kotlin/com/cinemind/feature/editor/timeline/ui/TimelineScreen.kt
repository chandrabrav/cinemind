package com.cinemind.feature.editor.timeline.ui

import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.filled.PersonRemove
import androidx.compose.material.icons.filled.AutoAwesomeMotion
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.SettingsBackupRestore
import androidx.compose.material.icons.filled.Wallpaper
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.TravelExplore
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Title
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Animation
import androidx.compose.material.icons.filled.AcUnit
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.FlipToFront
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.ClosedCaption
import androidx.compose.material.icons.filled.PictureInPicture
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.runtime.mutableIntStateOf
import android.view.LayoutInflater
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import com.cinemind.core.domain.editing.FrameLook
import com.cinemind.core.domain.editing.TimelineEditing
import com.cinemind.core.domain.editing.TransitionMath
import com.cinemind.core.domain.model.Project
import com.cinemind.core.ui.components.StatusPill
import com.cinemind.core.ui.components.hapticClick
import com.cinemind.core.ui.components.pressScale
import com.cinemind.core.ui.theme.CineMind
import com.cinemind.core.ui.theme.Radius
import com.cinemind.core.ui.theme.Spacing
import com.cinemind.core.ui.theme.formatTimecode
import com.cinemind.feature.editor.timeline.R
import com.cinemind.feature.captions.speech.SpeechSource
import com.cinemind.feature.captions.ui.CaptionOverlay
import com.cinemind.feature.captions.ui.CaptionsSheet
import com.cinemind.feature.captions.ui.CaptionsViewModel
import com.cinemind.feature.editor.timeline.preview.PreviewClip
import com.cinemind.feature.editor.timeline.preview.PreviewState

@Composable
fun TimelineScreen(
    projectId: String,
    onOpenBrief: () -> Unit,
    onExport: () -> Unit,
    onOpenAiModels: () -> Unit = {},
    onOpenCloudAi: () -> Unit = {},
    /** Leaves the editor (the editor has its own header; the app bar is hidden here). */
    onBack: () -> Unit = {},
    viewModel: TimelineViewModel = hiltViewModel(),
    captionsViewModel: CaptionsViewModel = hiltViewModel(),
) {
    LaunchedEffect(projectId) {
        viewModel.loadProject(projectId)
        captionsViewModel.load(projectId)
    }
    // Leaving the editor (export, Smart edit, AI models…) frees the preview's decoders and GPU
    // pipeline; coming back restores them at the same position.
    DisposableEffect(viewModel) {
        viewModel.onEditorShown()
        onDispose { viewModel.onEditorHidden() }
    }
    val captionsUi by captionsViewModel.ui.collectAsStateWithLifecycle()
    var showCaptions by remember { mutableStateOf(false) }
    var fullscreen by rememberSaveable { mutableStateOf(false) }
    val project by viewModel.project.collectAsStateWithLifecycle()
    val editor by viewModel.editor.collectAsStateWithLifecycle()
    val previewPlayer by viewModel.playerFlow.collectAsStateWithLifecycle()
    val previewBuilding by viewModel.previewBuilding.collectAsStateWithLifecycle()
    val reversing by viewModel.reversing.collectAsStateWithLifecycle()
    val layerOrder by viewModel.layerOrder.collectAsStateWithLifecycle()
    var showLayers by remember { mutableStateOf(false) }
    LaunchedEffect(captionsUi.captions, captionsUi.style) { viewModel.setPreviewCaptions(captionsUi.captions, captionsUi.style) }
    val clips = editor.clips
    val preview by viewModel.previewState.collectAsStateWithLifecycle()
    val selected = clips.firstOrNull { it.clip.id == editor.selectedClipId }

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
    var showTransitions by remember { mutableStateOf(false) }
    var showMusic by remember { mutableStateOf(false) }
    val musicPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.setMusicFromUri(uri)
    }
    val soundPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.importSound(uri)
    }
    var showSounds by remember { mutableStateOf(false) }
    val addMediaPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(maxItems = 50),
    ) { uris -> viewModel.addMedia(uris) }
    val overlayPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) viewModel.addOverlay(uri)
    }
    val pickOverlay = { overlayPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)) }
    val selectedOverlay = editor.overlays.firstOrNull { it.clip.id == editor.selectedOverlayId }
    val selectedVoice = editor.voiceClips.firstOrNull { it.clip.id == editor.selectedVoiceId }
    val recording by viewModel.recording.collectAsStateWithLifecycle()
    var showVoice by remember { mutableStateOf(false) }
    var showColor by remember { mutableStateOf(false) }
    var showSpeed by remember { mutableStateOf(false) }
    var showFraming by remember { mutableStateOf(false) }
    var showAddText by remember { mutableStateOf(false) }
    var showGraphics by remember { mutableStateOf(false) }
    var editTextOpen by remember { mutableStateOf(false) }
    LaunchedEffect(editor.selectedTextId) { if (editor.selectedTextId == null) editTextOpen = false }
    var colorAllClips by remember { mutableStateOf(false) }
    var colorContext by remember { mutableStateOf<TimelineViewModel.ColorContext?>(null) }
    LaunchedEffect(showColor) { colorContext = if (showColor) viewModel.colorContext() else null }
    val context = androidx.compose.ui.platform.LocalContext.current
    // The microphone permission is asked only when the user wants to record.
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) showVoice = true
        else android.widget.Toast.makeText(context, "Allow the microphone in Settings to record a voiceover", android.widget.Toast.LENGTH_LONG).show()
    }
    val openVoice = {
        if (androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.RECORD_AUDIO) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            showVoice = true
        } else {
            micPermission.launch(android.Manifest.permission.RECORD_AUDIO)
        }
    }

    // Transitions are rendered inside the preview video by the export's own effects
    // (CompositionPlayer), so Compose no longer imitates them.
    val look = FrameLook.IDENTITY
    val totalUs = TimelineEditing.durationUs(clips.map { it.clip })

    // ---- Layout: header · preview · playback bar · timeline · one contextual bottom bar ----------
    // Full screen grows the same preview (same video view, nothing handed over) and hides the rest.
    androidx.activity.compose.BackHandler(enabled = fullscreen) { fullscreen = false }
    if (fullscreen) ImmersiveBars()
    var controlsVisible by remember { mutableStateOf(true) }
    var controlTouches by remember { mutableIntStateOf(0) }
    LaunchedEffect(fullscreen, controlsVisible, preview.isPlaying, controlTouches) {
        if (fullscreen && controlsVisible && preview.isPlaying) {
            kotlinx.coroutines.delay(3_000)
            controlsVisible = false
        }
    }
    var liveText by remember { mutableStateOf<com.cinemind.core.domain.text.TextLayer?>(null) }
    var textBounds by remember { mutableStateOf<Map<String, android.graphics.RectF>>(emptyMap()) }
    var showAudio by remember { mutableStateOf(false) }
    var keyMaskSheet by remember { mutableStateOf<Boolean?>(null) }
    var showMusicIdeas by remember { mutableStateOf(false) }
    var showTts by remember { mutableStateOf(false) }
    var showAssistant by remember { mutableStateOf(false) }
    var showRemake by remember { mutableStateOf(false) }
    var showCutout by remember { mutableStateOf(false) }
    var showEnhance by remember { mutableStateOf(false) }
    var showCreator by remember { mutableStateOf(false) }
    var toolCategory by remember { mutableStateOf<ToolCategory?>(null) }
    var musicMood by remember { mutableStateOf<String?>(null) }
    var musicTheme by remember { mutableStateOf("") }
    LaunchedEffect(showMusicIdeas) { if (showMusicIdeas) musicTheme = musicMood ?: viewModel.colorContext().themeText }
    var adjustOpen by remember { mutableStateOf(false) }
    val selectionKey = listOf(editor.selectedClipId, editor.selectedOverlayId, editor.selectedVoiceId, editor.selectedSfxId, editor.selectedTextId)
    LaunchedEffect(selectionKey) { adjustOpen = false }
    val selectedSfx = editor.sfx.flatten().firstOrNull { it.clip.id == editor.selectedSfxId }
    val selectedText = editor.texts.firstOrNull { it.id == editor.selectedTextId }
    val shownTexts = liveText?.let { l -> editor.texts.map { if (it.id == l.id) l else it } } ?: editor.texts
    val overlaysNow = editor.overlays.filter { preview.positionUs >= it.clip.timelineInUs && preview.positionUs < it.clip.timelineOutUs }

    Box(modifier = Modifier.fillMaxSize().then(if (fullscreen) Modifier.background(androidx.compose.ui.graphics.Color.Black) else Modifier)) {
        Column(modifier = Modifier.fillMaxSize()) {
            if (!fullscreen) {
                EditorHeader(
                    project = project,
                    clipCount = clips.size,
                    canUndo = editor.canUndo,
                    canRedo = editor.canRedo,
                    onUndo = viewModel::undo,
                    onRedo = viewModel::redo,
                    onExport = onExport,
                    onBack = onBack,
                    onAssistant = { showAssistant = true },
                )
            }

            PreviewStage(
                player = previewPlayer,
                project = project,
                currentClip = clips.getOrNull(preview.currentIndex),
                look = look,
                state = preview,
                fps = fps,
                enabled = clips.isNotEmpty(),
                onTogglePlay = {
                    if (fullscreen) { controlsVisible = !controlsVisible; controlTouches++ } else viewModel.togglePlayPause()
                },
                captionLayer = {
                    // Above the picture-in-picture, in the chosen order (layers set "below" are drawn
                    // into the video by the composition instead).
                    val drawCaptions = layerOrder.captionsAboveOverlays
                    if (drawCaptions && !layerOrder.captionsAboveTitles) CaptionOverlay(captionsUi.captions, captionsUi.style, preview.positionUs, Modifier.fillMaxSize())
                    TextLayersOverlay(
                        layers = shownTexts.filter { !it.styleObj.drawnInPicture },
                        positionUs = preview.positionUs,
                        selectedId = if (fullscreen) null else editor.selectedTextId,
                        modifier = Modifier.fillMaxSize(),
                        interactive = !fullscreen && !preview.isPlaying,
                        handleGestures = false,
                        onBounds = { textBounds = it },
                    )
                    if (drawCaptions && layerOrder.captionsAboveTitles) CaptionOverlay(captionsUi.captions, captionsUi.style, preview.positionUs, Modifier.fillMaxSize())
                    if (!fullscreen) {
                        StageGestures(
                            enabled = !preview.isPlaying,
                            texts = editor.texts,
                            textBounds = textBounds,
                            overlays = overlaysNow,
                            selectedTextId = editor.selectedTextId,
                            selectedOverlayId = editor.selectedOverlayId,
                            onSelectText = viewModel::selectText,
                            onSelectOverlay = viewModel::selectOverlay,
                            onEmptyTap = viewModel::togglePlayPause,
                            onLiveText = { liveText = it },
                            onTextDone = viewModel::updateText,
                            onOverlayDone = viewModel::setOverlayTransform,
                            clipLook = selected?.let { s -> remember(s.clip, preview.positionUs / 100_000) { viewModel.lookAtPlayhead(s.clip) } },
                            onClipLookDone = { look -> selected?.let { s -> viewModel.setClipLook(s.clip.id, look) } },
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .then(if (fullscreen) Modifier else Modifier.padding(horizontal = Spacing.gutter)),
                showPlayer = true,
                building = previewBuilding || reversing != null,
                onFullscreen = if (fullscreen) null else ({ fullscreen = true; controlsVisible = true }),
            )

            if (!fullscreen) {
                PlaybackBar(
                    state = preview,
                    fps = fps,
                    enabled = clips.isNotEmpty(),
                    muted = editor.previewMuted,
                    volume = editor.previewVolume,
                    onTogglePlay = viewModel::togglePlayPause,
                    onToggleMute = viewModel::togglePreviewMute,
                    onVolume = viewModel::setPreviewVolume,
                    onFullscreen = { fullscreen = true; controlsVisible = true },
                )

                MultiTrackTimeline(
                    clips = clips,
                    music = editor.music,
                    musicPeaks = editor.musicPeaks,
                    beatsUs = editor.beatsUs,
                    waves = editor.waves,
                    sfx = editor.sfx,
                    selectedSfxId = editor.selectedSfxId,
                    onSfxTap = viewModel::onSfxTapped,
                    onAddSfx = { showSounds = true },
                    onDragSelect = { kind, id ->
                        when (kind) {
                            DragKind.OVERLAY -> viewModel.selectOverlay(id)
                            DragKind.VOICE -> viewModel.selectVoice(id)
                            DragKind.SFX -> viewModel.selectSfx(id)
                            DragKind.TEXT -> viewModel.selectText(id)
                        }
                    },
                    onDragMove = { kind, id, d ->
                        when (kind) {
                            DragKind.OVERLAY -> viewModel.moveOverlay(d)
                            DragKind.VOICE -> viewModel.moveVoice(d)
                            DragKind.SFX -> viewModel.moveSfx(d)
                            DragKind.TEXT -> viewModel.moveText(id, d)
                        }
                    },
                    onClipReorder = viewModel::moveClip,
                    totalUs = totalUs,
                    positionUs = preview.positionUs,
                    isPlaying = preview.isPlaying,
                    selectedClipId = editor.selectedClipId,
                    limitsFor = viewModel::limitsFor,
                    onSeek = viewModel::scrubFinished,
                    onScrub = viewModel::scrubTo,
                    onClipTap = viewModel::onClipTapped,
                    onTrim = viewModel::trimSelected,
                    onMusicTap = { showMusic = true },
                    // As tall as its lanes (two for a new project), at most TIMELINE_HEIGHT: more lanes
                    // scroll inside it, so the preview keeps its room and nothing is pushed off screen.
                    modifier = Modifier.fillMaxWidth().heightIn(max = TIMELINE_HEIGHT),
                    layers = remember(editor.texts, layerOrder, captionsUi.captions, editor.overlays) {
                        com.cinemind.core.domain.text.LayerStack.entries(editor.texts, layerOrder, captionsUi.captions.isNotEmpty(), editor.overlays.isNotEmpty())
                            .filter { it != com.cinemind.core.domain.text.LayerEntry.MainVideo }
                    },
                    onLayerMove = viewModel::moveLayerBy,
                    captions = captionsUi.captions,
                    onCaptionsTap = { showCaptions = true },
                    fps = fps,
                    overlays = editor.overlays,
                    selectedOverlayId = editor.selectedOverlayId,
                    onOverlayTap = viewModel::onOverlayTapped,
                    onAddOverlay = pickOverlay,
                    voiceClips = editor.voiceClips,
                    selectedVoiceId = editor.selectedVoiceId,
                    onVoiceTap = viewModel::onVoiceTapped,
                    onAddVoice = openVoice,
                    texts = editor.texts,
                    selectedTextId = editor.selectedTextId,
                    onTextTap = viewModel::selectText,
                    onAddText = { showAddText = true },
                )

                // One bottom bar: the selected item's actions, or the main tools.
                when {
                    selected != null -> EditorBottomBar(
                        title = "Clip ${clips.indexOf(selected) + 1}",
                        onClose = viewModel::clearSelection,
                        actions = listOf(
                            EditorAction(Icons.Default.Tune, "Trim") { adjustOpen = true },
                            EditorAction(Icons.Default.ContentCut, "Split") { viewModel.splitSelectedAtPlayhead() },
                            EditorAction(Icons.Default.Speed, "Speed") { showSpeed = true },
                            EditorAction(Icons.Default.Crop, "Crop & move") { showFraming = true },
                            EditorAction(Icons.Default.Palette, "Colour") { showColor = true },
                            EditorAction(Icons.Default.Animation, "Transition") { adjustOpen = true },
                            EditorAction(Icons.Default.AcUnit, "Freeze", enabled = selected.asset?.isVideo == true) { viewModel.freezeFrameAtPlayhead() },
                            EditorAction(Icons.Default.SettingsBackupRestore, "Reverse", enabled = selected.asset?.isVideo == true && reversing == null) { viewModel.reverseSelected() },
                            EditorAction(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Move left", enabled = clips.indexOf(selected) > 0) { viewModel.moveSelected(-1) },
                            EditorAction(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Move right", enabled = clips.indexOf(selected) < clips.lastIndex) { viewModel.moveSelected(1) },
                            EditorAction(Icons.Default.Delete, "Delete") { viewModel.deleteSelected() },
                        ),
                    )
                    selectedOverlay != null -> EditorBottomBar(
                        title = "Overlay",
                        onClose = viewModel::clearOverlaySelection,
                        actions = listOf(
                            EditorAction(Icons.Default.Tune, "Adjust") { adjustOpen = true },
                            EditorAction(Icons.Default.Wallpaper, "Green screen") { keyMaskSheet = false },
                            EditorAction(Icons.Default.Crop, "Mask") { keyMaskSheet = true },
                            EditorAction(Icons.Default.PersonRemove, "Cutout") { showCutout = true },
                            EditorAction(Icons.Default.Delete, "Delete") { viewModel.deleteOverlay() },
                        ),
                    )
                    selectedVoice != null -> EditorBottomBar(
                        title = "Voiceover",
                        onClose = viewModel::clearVoiceSelection,
                        actions = listOf(
                            EditorAction(Icons.Default.Tune, "Adjust") { adjustOpen = true },
                            EditorAction(Icons.Default.Mic, "Record") { openVoice() },
                            EditorAction(Icons.Default.Delete, "Delete") { viewModel.deleteVoice() },
                        ),
                    )
                    selectedSfx != null -> EditorBottomBar(
                        title = "Sound",
                        onClose = viewModel::clearSfxSelection,
                        actions = listOf(
                            EditorAction(Icons.Default.Tune, "Adjust") { adjustOpen = true },
                            EditorAction(Icons.Default.GraphicEq, "Add sound") { showSounds = true },
                            EditorAction(Icons.Default.Delete, "Delete") { viewModel.deleteSfx() },
                        ),
                    )
                    selectedText != null -> EditorBottomBar(
                        title = if (selectedText.kindEnum == com.cinemind.core.domain.text.TextKind.STICKER) "Sticker" else "Text",
                        onClose = { viewModel.selectText(null) },
                        actions = listOf(
                            EditorAction(Icons.Default.Edit, "Edit") { editTextOpen = true },
                            EditorAction(Icons.Default.ContentCopy, "Copy") { viewModel.duplicateText(selectedText.id) },
                            EditorAction(Icons.Default.FlipToFront, "Front") { viewModel.bringTextToFront(selectedText.id) },
                            EditorAction(Icons.Default.Delete, "Delete") { viewModel.deleteText(selectedText.id) },
                        ),
                    )
                    else -> {
                        val addClips = { addMediaPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)) }
                        // Clip tools work on the selected clip, else on the clip under the playhead.
                        fun atPlayhead(block: () -> Unit) {
                            if (clips.isEmpty()) return
                            if (editor.selectedClipId == null) viewModel.onClipTapped(preview.currentIndex.coerceIn(0, clips.lastIndex))
                            block()
                        }
                        // A tool closes its category row (its sheet / the selection bar takes over).
                        fun tool(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, enabled: Boolean = true, run: () -> Unit) =
                            EditorAction(icon, label, enabled) { toolCategory = null; run() }
                        val has = clips.isNotEmpty()
                        when (toolCategory) {
                            ToolCategory.EDIT -> EditorBottomBar(
                                title = "Edit",
                                onClose = { toolCategory = null },
                                actions = listOf(
                                    tool(Icons.Default.AddPhotoAlternate, "Add clips") { addClips() },
                                    tool(Icons.Default.ContentCut, "Split", has) { atPlayhead { viewModel.splitSelectedAtPlayhead() } },
                                    tool(Icons.Default.Tune, "Trim", has) { atPlayhead { adjustOpen = true } },
                                    tool(Icons.Default.PersonRemove, "Cutout", has) { showCutout = true },
                                    tool(Icons.Default.Crop, "Crop & move", has) { atPlayhead { showFraming = true } },
                                    tool(Icons.Default.AcUnit, "Freeze", has) { atPlayhead { viewModel.freezeFrameAtPlayhead() } },
                                    tool(Icons.Default.SettingsBackupRestore, "Reverse", has && reversing == null) { atPlayhead { viewModel.reverseSelected() } },
                                    tool(Icons.Default.Layers, "Layers", has) { showLayers = true },
                                    tool(Icons.Default.Delete, "Delete", has) { atPlayhead { viewModel.deleteSelected() } },
                                ),
                            )
                            ToolCategory.AUDIO -> EditorBottomBar(
                                title = "Audio",
                                onClose = { toolCategory = null },
                                actions = listOf(
                                    tool(Icons.Default.MusicNote, if (editor.music != null) "Music" else "Add music") { showMusic = true },
                                    tool(Icons.Default.GraphicEq, "Sounds") { showSounds = true },
                                    tool(Icons.Default.Mic, "Voiceover") { openVoice() },
                                    tool(Icons.Default.GraphicEq, "Enhance voice") { showEnhance = true },
                                    tool(Icons.Default.RecordVoiceOver, "Voice from text") { showTts = true },
                                    tool(Icons.Default.TravelExplore, "Find music") { showMusicIdeas = true },
                                ),
                            )
                            ToolCategory.TEXT -> EditorBottomBar(
                                title = "Text",
                                onClose = { toolCategory = null },
                                actions = listOf(
                                    tool(Icons.Default.TextFields, "Text & stickers") { showAddText = true },
                                    tool(Icons.Default.AutoAwesome, "Smart graphics") { showGraphics = true },
                                    tool(Icons.Default.Layers, "Layers") { showLayers = true },
                                ),
                            )
                            ToolCategory.OVERLAY -> EditorBottomBar(
                                title = "Overlay",
                                onClose = { toolCategory = null },
                                actions = listOf(
                                    tool(Icons.Default.PictureInPicture, "Add photo or video") { pickOverlay() },
                                    tool(Icons.Default.Layers, "Layers") { showLayers = true },
                                ),
                            )
                            ToolCategory.AI -> EditorBottomBar(
                                title = "AI",
                                onClose = { toolCategory = null },
                                actions = listOf(
                                    tool(Icons.Default.AutoAwesome, "Assistant") { showAssistant = true },
                                    tool(Icons.Default.AutoFixHigh, "Smart edit") { onOpenBrief() },
                                    tool(Icons.Default.ClosedCaption, "Auto captions") { showCaptions = true },
                                    tool(Icons.Default.Title, "Smart graphics") { showGraphics = true },
                                    tool(Icons.Default.TravelExplore, "Music ideas") { showMusicIdeas = true },
                                    tool(Icons.Default.ContentCut, "Auto edit") { showCreator = true },
                                    tool(Icons.Default.PersonRemove, "Cut out subject") { showCutout = true },
                                    tool(Icons.Default.AutoAwesomeMotion, "Remake (KATANA)") { showRemake = true },
                                ),
                            )
                            null -> EditorBottomBar(
                                title = null,
                                actions = listOf(
                                    EditorAction(Icons.Default.ContentCut, "Edit") { toolCategory = ToolCategory.EDIT },
                                    EditorAction(Icons.Default.MusicNote, "Audio", enabled = has) { toolCategory = ToolCategory.AUDIO },
                                    EditorAction(Icons.Default.TextFields, "Text", enabled = has) { toolCategory = ToolCategory.TEXT },
                                    EditorAction(Icons.Default.ClosedCaption, "Captions", enabled = has) { showCaptions = true },
                                    EditorAction(Icons.Default.PictureInPicture, "Overlay", enabled = has) { toolCategory = ToolCategory.OVERLAY },
                                    EditorAction(Icons.Default.Palette, "Colour", enabled = has) { showColor = true },
                                    EditorAction(Icons.Default.Speed, "Speed", enabled = has) { atPlayhead { showSpeed = true } },
                                    EditorAction(Icons.Default.Animation, "Transitions", enabled = has) { showTransitions = true },
                                    EditorAction(Icons.Default.AutoAwesome, "AI", enabled = has) { toolCategory = ToolCategory.AI },
                                ),
                            )
                        }
                    }
                }
            }
        }
        if (fullscreen) {
            FullscreenControls(
                state = preview,
                fps = fps,
                onTogglePlay = viewModel::togglePlayPause,
                onScrub = viewModel::scrubTo,
                onScrubFinished = viewModel::scrubFinished,
                onExit = { fullscreen = false },
                visible = controlsVisible,
                onInteract = { controlTouches++ },
            )
        }
    }

    if (showTts) {
        TtsSheet(
            loadVoices = { viewModel.ttsVoices() },
            onAdd = { t, v, r, p, done -> viewModel.addSpeech(t, v, r, p, done) },
            onDismiss = { showTts = false },
        )
    }

    if (showCreator) {
        CreatorToolsSheet(viewModel = viewModel, onDismiss = { showCreator = false })
    }

    if (showEnhance) {
        VoiceEnhanceSheet(value = editor.voiceEnhance, onChange = viewModel::setVoiceEnhance, onDismiss = { showEnhance = false })
    }

    if (showCutout) {
        CutoutSheet(viewModel = viewModel, onDismiss = { showCutout = false })
    }

    if (showRemake) {
        com.cinemind.feature.editor.timeline.handoff.RemakeSheet(
            projectId = projectId,
            aspect = project?.aspectRatio,
            onImported = { uri -> viewModel.addMedia(listOf(uri)) },
            onOpenSettings = { showRemake = false; onOpenCloudAi() },
            onDismiss = { showRemake = false },
        )
    }

    if (showAssistant) {
        AssistantSheet(
            viewModel = viewModel,
            onOpenSettings = { showAssistant = false; onOpenCloudAi() },
            onMusic = { mood -> musicMood = mood; showAssistant = false; showMusicIdeas = true },
            onDismiss = { showAssistant = false },
        )
    }

    if (showMusicIdeas) {
        MusicIdeasSheet(
            projectId = projectId,
            themeText = musicTheme,
            bpm = editor.bpm,
            onChooseFile = { musicPicker.launch(arrayOf("audio/*")) },
            onDismiss = { showMusicIdeas = false; musicMood = null },
        )
    }

    if (showLayers) {
        LayersSheet(
            entries = remember(editor.texts, layerOrder, captionsUi.captions, editor.overlays) { viewModel.layerEntries() },
            onMove = viewModel::moveLayer,
            onDismiss = { showLayers = false },
        )
    }

    keyMaskSheet?.let { maskMode ->
        val o = selectedOverlay
        if (o == null) LaunchedEffect(Unit) { keyMaskSheet = null } else KeyMaskSheet(
            framing = com.cinemind.core.domain.editing.ClipFraming.decode(o.clip.framing),
            startWithMask = maskMode,
            onChange = viewModel::setOverlayKeyMask,
            onDismiss = { keyMaskSheet = null },
        )
    }

    if (showAudio) {
        AudioMenuSheet(
            hasMusic = editor.music != null,
            onMusic = { showMusic = true },
            onSounds = { showSounds = true },
            onVoice = openVoice,
            onDismiss = { showAudio = false },
            onFindMusic = { showMusicIdeas = true },
            onTts = { showTts = true },
        )
    }

    // Detailed controls for the selected item, as a sheet (never pushes the timeline away).
    if (adjustOpen) {
        when {
            selected != null -> com.cinemind.core.ui.components.CineMindSheet(onDismiss = { adjustOpen = false }, title = "Clip ${clips.indexOf(selected) + 1}") {
                ClipInspector(
                    item = selected,
                    index = clips.indexOf(selected),
                    clipCount = clips.size,
                    limits = viewModel.limitsFor(selected.clip),
                    fps = fps,
                    onTrim = viewModel::trimSelected,
                    onMove = viewModel::moveSelected,
                    onDelete = { adjustOpen = false; viewModel.deleteSelected() },
                    onSplit = viewModel::splitSelectedAtPlayhead,
                    onClose = { adjustOpen = false },
                    onTransition = viewModel::setTransitionSelected,
                )
            }
            selectedOverlay != null -> com.cinemind.core.ui.components.CineMindSheet(onDismiss = { adjustOpen = false }, title = "Overlay") {
                OverlayInspector(
                    item = selectedOverlay,
                    onTransform = viewModel::setOverlayTransform,
                    onMove = viewModel::moveOverlay,
                    onTrim = viewModel::trimOverlay,
                    onDelete = { adjustOpen = false; viewModel.deleteOverlay() },
                    onClose = { adjustOpen = false },
                )
            }
            selectedVoice != null -> com.cinemind.core.ui.components.CineMindSheet(onDismiss = { adjustOpen = false }, title = "Voiceover") {
                VoiceInspector(
                    item = selectedVoice,
                    settings = editor.voiceSettings,
                    onMove = viewModel::moveVoice,
                    onTrim = viewModel::trimVoice,
                    onFades = viewModel::setVoiceFades,
                    onSettings = viewModel::setVoiceSettings,
                    onDelete = { adjustOpen = false; viewModel.deleteVoice() },
                    onClose = { adjustOpen = false },
                )
            }
            selectedSfx != null -> com.cinemind.core.ui.components.CineMindSheet(onDismiss = { adjustOpen = false }, title = "Sound") {
                SoundInspectorBar(
                    item = selectedSfx,
                    onVolume = viewModel::setSfxVolume,
                    onMove = viewModel::moveSfx,
                    onFades = viewModel::setSfxFades,
                    onDelete = { adjustOpen = false; viewModel.deleteSfx() },
                    onClose = { adjustOpen = false },
                )
            }
            else -> LaunchedEffect(Unit) { adjustOpen = false }
        }
    }

    if (showSounds) {
        SoundsSheet(
            onAdd = { e -> viewModel.addSoundEffect(e); showSounds = false },
            onImport = { showSounds = false; soundPicker.launch(arrayOf("audio/*")) },
            onDismiss = { showSounds = false },
        )
    }

    if (showGraphics) {
        GraphicsSheet(
            loadContext = { viewModel.graphicsContext() },
            aiTitles = { ctx -> viewModel.aiTitles(ctx) },
            onAdd = viewModel::addGraphics,
            onDismiss = { showGraphics = false },
        )
    }

    if (showAddText) {
        AddTextSheet(
            onAddText = { t, p -> viewModel.addText(t, p); showAddText = false },
            onAddSticker = { s -> viewModel.addSticker(s); showAddText = false },
            onDismiss = { showAddText = false },
            onSuggest = { showAddText = false; showGraphics = true },
        )
    }

    if (editTextOpen) editor.texts.firstOrNull { it.id == editor.selectedTextId }?.let { layer ->
        EditTextSheet(
            layer = layer,
            onChange = viewModel::updateText,
            onDuplicate = { viewModel.duplicateText(layer.id) },
            onFront = { viewModel.bringTextToFront(layer.id) },
            onDelete = { viewModel.deleteText(layer.id) },
            onMove = { d -> viewModel.moveText(layer.id, d) },
            onTrim = { a, b -> viewModel.trimText(layer.id, a, b) },
            onDismiss = { editTextOpen = false },
        )
    }

    if (showFraming) {
        val target = remember(editor.clips, editor.selectedClipId) { viewModel.framingTarget() }
        if (target == null) {
            LaunchedEffect(Unit) { showFraming = false }
        } else {
            FramingSheet(
                target = target,
                playheadOffsetUs = remember(target.clip.id) { viewModel.playheadOffsetIn(target.clip) },
                onSeek = { off, exact -> viewModel.seekInClip(target.clip, off, exact) },
                onFraming = { f -> viewModel.setFraming(target.clip.id, f) },
                onDismiss = { showFraming = false },
            )
        }
    }

    if (showSpeed) {
        val target = remember(editor.clips, editor.selectedClipId) { viewModel.speedTarget() }
        if (target == null) {
            LaunchedEffect(Unit) {
                android.widget.Toast.makeText(context, "Select a video clip (or move the playhead onto one) to change its speed", android.widget.Toast.LENGTH_SHORT).show()
                showSpeed = false
            }
        } else {
            SpeedSheet(
                target = target,
                onConstant = { s, all -> viewModel.setClipSpeed(target.clip.id, s, all) },
                onRamp = { pts -> viewModel.setClipRamp(target.clip.id, pts) },
                onKeepPitch = { on -> viewModel.setKeepPitch(target.clip.id, on) },
                onFreeze = {
                    viewModel.freezeFrameAtPlayhead()
                    showSpeed = false
                },
                onDismiss = { showSpeed = false },
            )
        }
    }

    if (showColor) {
        val ctx = colorContext
        if (ctx != null) {
            val selectedIndex = clips.indexOfFirst { it.clip.id == editor.selectedClipId }
            com.cinemind.feature.color.ui.ColorSheet(
                grade = remember(editor.clips, editor.selectedClipId) { viewModel.currentGrade() },
                targetLabel = if (colorAllClips || selectedIndex < 0) "Every clip in the video" else "Only clip ${selectedIndex + 1} (selected)",
                allClips = colorAllClips || selectedIndex < 0,
                onAllClipsChange = { colorAllClips = it },
                frameUri = ctx.uri,
                frameIsVideo = ctx.isVideo,
                frameTimeUs = ctx.timeUs,
                themeText = ctx.themeText,
                onGrade = { g -> viewModel.setGrade(g, colorAllClips || selectedIndex < 0) },
                onCompare = viewModel::compareOriginal,
                onDismiss = { viewModel.compareOriginal(false); showColor = false },
            )
        }
    }

    if (showVoice) {
        VoiceoverSheet(
            state = recording,
            onStart = { viewModel.startRecording() },
            onPause = viewModel::pauseRecording,
            onResume = viewModel::resumeRecording,
            onStop = viewModel::stopRecording,
            onListen = viewModel::listenTake,
            onStopListening = viewModel::stopListening,
            onAccept = {
                viewModel.acceptTake()
                showVoice = false
            },
            onDiscard = viewModel::discardTake,
            onDismiss = {
                // Closing the sheet never keeps a half-done recording.
                viewModel.cancelRecording()
                showVoice = false
            },
        )
    }

    if (showCaptions) {
        LaunchedEffect(Unit) { captionsViewModel.refreshModelState() }
        val sources = remember(clips) {
            clips.mapNotNull { pc ->
                val a = pc.asset ?: return@mapNotNull null
                if (!a.isVideo || !a.hasAudio) return@mapNotNull null
                SpeechSource(a.uri, pc.clip.sourceInUs, pc.clip.sourceOutUs, pc.clip.timelineInUs) { off -> com.cinemind.core.domain.editing.SpeedMath.sourceToTimeline(pc.clip, off) }
            }
        }
        CaptionsSheet(
            state = captionsUi,
            sources = sources,
            positionUs = preview.positionUs,
            totalUs = totalUs,
            frameRatio = project?.let { it.resolutionWidth.toFloat() / it.resolutionHeight } ?: (9f / 16f),
            onSeek = viewModel::scrubFinished,
            viewModel = captionsViewModel,
            onOpenAiModels = {
                showCaptions = false
                onOpenAiModels()
            },
            onDismiss = { showCaptions = false },
        )
    }

    if (showMusic) {
        MusicSheet(
            music = editor.music,
            originalVolume = editor.originalVolume,
            onChoose = { musicPicker.launch(arrayOf("audio/*")) },
            onUpdate = viewModel::updateMusic,
            onRemove = viewModel::removeMusic,
            onOriginalVolume = viewModel::setOriginalVolume,
            onDismiss = { showMusic = false },
            bpm = editor.bpm,
            beatCount = editor.beatsUs.size,
            analysingBeats = editor.analysingBeats,
            onCutToBeats = { every -> viewModel.cutToBeats(every); showMusic = false },
        )
    }

    if (showTransitions) {
        TransitionsSheet(
            onDismiss = { showTransitions = false },
            onApplyAll = viewModel::applyTransitionToAll,
            onOpeningEnding = viewModel::setOpeningAndEnding,
        )
    }
}

@Composable
private fun EditorHeader(
    project: Project?,
    clipCount: Int,
    canUndo: Boolean,
    canRedo: Boolean,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onExport: (() -> Unit)? = null,
    onBack: () -> Unit = {},
    onAssistant: () -> Unit = {},
) {
    val c = CineMind.colors
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(start = Spacing.xxs, end = Spacing.xs, bottom = Spacing.xxs),
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to projects", tint = c.textPrimary)
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = project?.name ?: "Editor",
                style = MaterialTheme.typography.titleMedium,
                color = c.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            project?.let {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StatusPill(it.aspectRatio)
                    Spacer(Modifier.width(Spacing.xs))
                    Text("$clipCount clips · ${it.frameRate} fps", style = MaterialTheme.typography.labelSmall, color = c.textTertiary)
                }
            }
        }
        if (clipCount > 0) {
            IconButton(onClick = hapticClick(onClick = onAssistant)) {
                Icon(androidx.compose.material.icons.Icons.Default.AutoAwesome, contentDescription = "Assistant", tint = c.accent)
            }
        }
        IconButton(onClick = hapticClick(onClick = onUndo), enabled = canUndo) {
            Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = "Undo", tint = if (canUndo) c.textPrimary else c.textTertiary.copy(alpha = 0.4f))
        }
        IconButton(onClick = hapticClick(onClick = onRedo), enabled = canRedo) {
            Icon(Icons.AutoMirrored.Filled.Redo, contentDescription = "Redo", tint = if (canRedo) c.textPrimary else c.textTertiary.copy(alpha = 0.4f))
        }
        // Always reachable, even when the tools bar scrolls on narrow phones.
        if (onExport != null && clipCount > 0) {
            com.cinemind.core.ui.components.Pill(text = "Export", selected = true, onClick = onExport)
        }
    }
}

@OptIn(UnstableApi::class)
@Composable
private fun PreviewStage(
    player: Player,
    project: Project?,
    currentClip: PreviewClip?,
    look: FrameLook,
    state: PreviewState,
    fps: Int,
    enabled: Boolean,
    onTogglePlay: () -> Unit,
    modifier: Modifier = Modifier,
    captionLayer: @Composable () -> Unit = {},
    /** False while full screen shows the video (one view per player). */
    showPlayer: Boolean = true,
    /** Shows a small loader while the preview rebuilds after an edit. */
    building: Boolean = false,
    onFullscreen: (() -> Unit)? = null,
) {
    val c = CineMind.colors
    val ratio = project?.let { it.resolutionWidth.toFloat() / it.resolutionHeight } ?: (9f / 16f)
    val stageShape = RoundedCornerShape(Radius.l)
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .clip(stageShape)
            .background(c.stage),
    ) {
        // The frame shows the project's output shape; clips are fitted inside it.
        Box(
            modifier = Modifier
                .aspectRatio(ratio, matchHeightConstraintsFirst = true)
                .clipToBounds()
                .background(c.stage),
        ) {
            // Picture (video or photo) moves as one layer, like the exported frame does.
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = look.scale
                        scaleY = look.scale
                        translationX = look.translateX * size.width
                        translationY = look.translateY * size.height
                    },
            ) {
                if (showPlayer) {
                    AndroidView(
                        factory = { ctx ->
                            (LayoutInflater.from(ctx).inflate(R.layout.preview_player_view, null) as PlayerView)
                                .apply { this.player = player }
                        },
                        update = { it.player = player },
                        onRelease = { view -> runCatching { view.player = null } },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                val asset = currentClip?.asset
                when {
                    // Photos are rendered by the composition like videos; only a missing file needs a label.
                    currentClip != null && asset == null -> Box(Modifier.fillMaxSize().background(c.stage), contentAlignment = Alignment.Center) {
                        Text("Missing media", color = c.onStage.copy(alpha = 0.7f))
                    }
                }
            }
            // Fades dip through black.
            if (look.brightness < 1f) {
                Box(Modifier.fillMaxSize().background(c.stage.copy(alpha = (1f - look.brightness).coerceIn(0f, 1f))))
            }
            // Captions sit above the picture and its transitions, as in the export.
            captionLayer()
            if (building) {
                androidx.compose.material3.CircularProgressIndicator(
                    color = c.onStage,
                    strokeWidth = 2.dp,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(Spacing.s)
                        .size(18.dp)
                        .semantics { contentDescription = "Updating the preview" },
                )
            }
        }

        if (!enabled) {
            Text("Add clips to start editing", color = c.onStage.copy(alpha = 0.7f), style = MaterialTheme.typography.bodyMedium)
        }

        // Playback controls live in the bar under the preview (PlaybackBar), so they never cover the video.

        state.error?.let { error ->
            Text(
                text = error,
                color = c.onStage,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(Spacing.s)
                    .clip(RoundedCornerShape(Radius.s))
                    .background(c.danger.copy(alpha = 0.9f))
                    .padding(horizontal = Spacing.s, vertical = Spacing.xs),
            )
        }
    }
}

/**
 * Playback bar under the preview: play/pause, time, preview mute + volume (listening only; the
 * project's own volumes are set in Music/Voice) and full screen.
 */
@Composable
private fun PlaybackBar(
    state: PreviewState,
    fps: Int,
    enabled: Boolean,
    muted: Boolean,
    volume: Float,
    onTogglePlay: () -> Unit,
    onToggleMute: () -> Unit,
    onVolume: (Float) -> Unit,
    onFullscreen: () -> Unit,
) {
    val c = CineMind.colors
    val interaction = remember { MutableInteractionSource() }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.gutter, vertical = Spacing.xs),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(44.dp)
                .pressScale(interaction, 0.9f)
                .clip(CircleShape)
                .background(c.accent)
                .clickable(interactionSource = interaction, indication = null, enabled = enabled, role = Role.Button, onClick = hapticClick(HapticFeedbackType.LongPress, onTogglePlay)),
        ) {
            Icon(
                imageVector = if (state.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                contentDescription = if (state.isPlaying) "Pause" else "Play",
                tint = c.onAccent,
            )
        }
        Spacer(Modifier.width(Spacing.s))
        Text(
            "${formatTimecode(state.positionUs, fps)} / ${formatTimecode(state.durationUs, fps)}",
            color = c.textPrimary,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onToggleMute) {
            Icon(
                if (muted) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp,
                contentDescription = if (muted) "Unmute preview" else "Mute preview",
                tint = c.textSecondary,
            )
        }
        androidx.compose.material3.Slider(
            value = if (muted) 0f else volume,
            onValueChange = onVolume,
            colors = androidx.compose.material3.SliderDefaults.colors(thumbColor = c.accent, activeTrackColor = c.accent, inactiveTrackColor = c.surfaceSunken),
            modifier = Modifier
                .width(84.dp)
                .semantics { contentDescription = "Preview volume" },
        )
        IconButton(onClick = hapticClick(onClick = onFullscreen), enabled = enabled) {
            Icon(Icons.Default.Fullscreen, contentDescription = "Full screen preview", tint = c.textSecondary)
        }
    }
}

@Composable
private fun PlayControl(isPlaying: Boolean, enabled: Boolean, timecode: String, onToggle: () -> Unit, modifier: Modifier = Modifier) {
    val c = CineMind.colors
    val interaction = remember { MutableInteractionSource() }
    val click = hapticClick(HapticFeedbackType.LongPress, onToggle)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .clip(RoundedCornerShape(Radius.pill))
            .background(c.stageControl)
            .border(1.dp, c.onStage.copy(alpha = 0.12f), RoundedCornerShape(Radius.pill))
            .padding(start = Spacing.xs, end = Spacing.l, top = Spacing.xs, bottom = Spacing.xs),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(44.dp)
                .pressScale(interaction, 0.9f)
                .clip(CircleShape)
                .background(c.onStage)
                .clickable(interactionSource = interaction, indication = null, enabled = enabled, role = Role.Button, onClick = click),
        ) {
            Icon(
                imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                contentDescription = if (isPlaying) "Pause" else "Play",
                tint = c.stage,
            )
        }
        Spacer(Modifier.width(Spacing.m))
        Text(timecode, color = c.onStage, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun Scrubber(
    state: PreviewState,
    enabled: Boolean,
    onScrub: (Long) -> Unit,
    onScrubFinished: (Long) -> Unit,
) {
    val c = CineMind.colors
    // While dragging, the slider shows the finger position rather than the (lagging) player position.
    var dragPositionUs by remember { mutableStateOf<Long?>(null) }
    val shownUs = dragPositionUs ?: state.positionUs
    val durationUs = state.durationUs.coerceAtLeast(1)
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
        colors = SliderDefaults.colors(
            thumbColor = c.accent,
            activeTrackColor = c.accent,
            inactiveTrackColor = c.surfaceSunken,
        ),
        modifier = Modifier
            .fillMaxWidth()
            .height(36.dp)
            .padding(horizontal = Spacing.gutter),
    )
}

/** The timeline keeps this height; its lanes scroll inside it. */
private val TIMELINE_HEIGHT = 236.dp

/** Bottom-bar categories that open a row of their own tools (others open their sheet directly). */
internal enum class ToolCategory { EDIT, AUDIO, TEXT, OVERLAY, AI }
