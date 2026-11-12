package com.cinemind.feature.editor.timeline.ui

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
    // Only one PlayerView may hold the player: the old view is detached a couple of frames before the
    // new one attaches (Media3 rejects clearing a surface it no longer uses: the "Back from full
    // screen closes the app" crash on the owner's phone).
    var stageViewAttached by remember { mutableStateOf(!fullscreen) }
    var fullscreenShown by remember { mutableStateOf(fullscreen) }
    LaunchedEffect(fullscreen) {
        if (fullscreen) {
            stageViewAttached = false
            androidx.compose.runtime.withFrameNanos { }
            androidx.compose.runtime.withFrameNanos { }
            fullscreenShown = true
        } else {
            fullscreenShown = false
            androidx.compose.runtime.withFrameNanos { }
            androidx.compose.runtime.withFrameNanos { }
            stageViewAttached = true
        }
    }
    val project by viewModel.project.collectAsStateWithLifecycle()
    val editor by viewModel.editor.collectAsStateWithLifecycle()
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

    Column(modifier = Modifier.fillMaxSize()) {
        EditorHeader(
            project = project,
            clipCount = clips.size,
            canUndo = editor.canUndo,
            canRedo = editor.canRedo,
            onUndo = viewModel::undo,
            onRedo = viewModel::redo,
            onExport = onExport,
        )

        PreviewStage(
            player = viewModel.player,
            project = project,
            currentClip = clips.getOrNull(preview.currentIndex),
            look = look,
            state = preview,
            fps = fps,
            enabled = clips.isNotEmpty(),
            onTogglePlay = viewModel::togglePlayPause,
            captionLayer = {
                CaptionOverlay(captionsUi.captions, captionsUi.style, preview.positionUs, Modifier.fillMaxSize())
                TextLayersOverlay(
                    layers = editor.texts,
                    positionUs = preview.positionUs,
                    selectedId = editor.selectedTextId,
                    modifier = Modifier.fillMaxSize(),
                    interactive = !preview.isPlaying,
                    onSelect = viewModel::selectText,
                    onTransform = viewModel::updateText,
                    onEmptyTap = viewModel::togglePlayPause,
                )
            },
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(horizontal = Spacing.gutter),
            showPlayer = stageViewAttached,
            onFullscreen = { fullscreen = true },
        )

        if (fullscreenShown) {
            FullscreenPreview(
                player = viewModel.player,
                state = preview,
                ratio = project?.let { it.resolutionWidth.toFloat() / it.resolutionHeight } ?: (9f / 16f),
                fps = fps,
                onTogglePlay = viewModel::togglePlayPause,
                onScrub = viewModel::scrubTo,
                onScrubFinished = viewModel::scrubFinished,
                onExit = { fullscreen = false },
                captionLayer = {
                    CaptionOverlay(captionsUi.captions, captionsUi.style, preview.positionUs, Modifier.fillMaxSize())
                    TextLayersOverlay(editor.texts, preview.positionUs, null, Modifier.fillMaxSize())
                },
            )
        }

        PlaybackBar(
            state = preview,
            fps = fps,
            enabled = clips.isNotEmpty(),
            muted = editor.previewMuted,
            volume = editor.previewVolume,
            onTogglePlay = viewModel::togglePlayPause,
            onToggleMute = viewModel::togglePreviewMute,
            onVolume = viewModel::setPreviewVolume,
            onFullscreen = { fullscreen = true },
        )

        Scrubber(
            state = preview,
            enabled = clips.isNotEmpty(),
            onScrub = viewModel::scrubTo,
            onScrubFinished = viewModel::scrubFinished,
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
            totalUs = totalUs,
            positionUs = preview.positionUs,
            isPlaying = preview.isPlaying,
            selectedClipId = editor.selectedClipId,
            limitsFor = viewModel::limitsFor,
            onSeek = viewModel::scrubFinished,
            onClipTap = viewModel::onClipTapped,
            onTrim = viewModel::trimSelected,
            onMusicTap = { showMusic = true },
            // The tracks scroll vertically inside a bounded area, so the timeline never pushes the controls off screen.
            modifier = Modifier.fillMaxWidth().heightIn(max = 236.dp),
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

        AnimatedVisibility(
            visible = selected != null,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically(),
        ) {
            val item = selected ?: return@AnimatedVisibility
            ClipInspector(
                item = item,
                index = clips.indexOf(item),
                clipCount = clips.size,
                limits = viewModel.limitsFor(item.clip),
                fps = fps,
                onTrim = viewModel::trimSelected,
                onMove = viewModel::moveSelected,
                onDelete = viewModel::deleteSelected,
                onSplit = viewModel::splitSelectedAtPlayhead,
                onClose = viewModel::clearSelection,
                onTransition = viewModel::setTransitionSelected,
            )
        }

        AnimatedVisibility(
            visible = selectedVoice != null,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically(),
        ) {
            val item = selectedVoice ?: return@AnimatedVisibility
            VoiceInspector(
                item = item,
                settings = editor.voiceSettings,
                onMove = viewModel::moveVoice,
                onTrim = viewModel::trimVoice,
                onFades = viewModel::setVoiceFades,
                onSettings = viewModel::setVoiceSettings,
                onDelete = viewModel::deleteVoice,
                onClose = viewModel::clearVoiceSelection,
            )
        }

        AnimatedVisibility(
            visible = selectedOverlay != null,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically(),
        ) {
            val item = selectedOverlay ?: return@AnimatedVisibility
            OverlayInspector(
                item = item,
                onTransform = viewModel::setOverlayTransform,
                onMove = viewModel::moveOverlay,
                onTrim = viewModel::trimOverlay,
                onDelete = viewModel::deleteOverlay,
                onClose = viewModel::clearOverlaySelection,
            )
        }

        editor.sfx.flatten().firstOrNull { it.clip.id == editor.selectedSfxId }?.let { item ->
            SoundInspectorBar(
                item = item,
                onVolume = viewModel::setSfxVolume,
                onMove = viewModel::moveSfx,
                onFades = viewModel::setSfxFades,
                onDelete = viewModel::deleteSfx,
                onClose = viewModel::clearSfxSelection,
            )
        }

        editor.texts.firstOrNull { it.id == editor.selectedTextId }?.let { layer ->
            TextInspectorBar(
                layer = layer,
                onEdit = { editTextOpen = true },
                onDuplicate = { viewModel.duplicateText(layer.id) },
                onDelete = { viewModel.deleteText(layer.id) },
                onClose = { viewModel.selectText(null) },
            )
        }

        EditorToolsBar(
            hasClips = clips.isNotEmpty(),
            onSmartEdit = onOpenBrief,
            onAddMedia = {
                addMediaPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
            },
            onTransitions = { showTransitions = true },
            onMusic = { showMusic = true },
            onExport = onExport,
            onCaptions = { showCaptions = true },
            onText = { showAddText = true },
            onSounds = { showSounds = true },
            onOverlay = pickOverlay,
            onVoice = openVoice,
            onColor = { showColor = true },
            onSpeed = { showSpeed = true },
            onFraming = { showFraming = true },
        )
    }

    if (showSounds) {
        SoundsSheet(
            onAdd = { e -> viewModel.addSoundEffect(e); showSounds = false },
            onImport = { showSounds = false; soundPicker.launch(arrayOf("audio/*")) },
            onDismiss = { showSounds = false },
        )
    }

    if (showAddText) {
        AddTextSheet(
            onAddText = { t, p -> viewModel.addText(t, p); showAddText = false },
            onAddSticker = { s -> viewModel.addSticker(s); showAddText = false },
            onDismiss = { showAddText = false },
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
) {
    val c = CineMind.colors
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = Spacing.gutter, end = Spacing.xs, bottom = Spacing.xs),
    ) {
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
