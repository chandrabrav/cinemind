package com.cinemind.feature.export.engine

import android.content.Context
import android.content.Intent
import android.media.MediaMetadataRetriever
import androidx.annotation.OptIn
import androidx.core.content.ContextCompat
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.Transformer
import com.cinemind.core.common.CineMindLogger
import com.cinemind.core.common.Diagnostics
import com.cinemind.core.domain.model.TrackType
import com.cinemind.core.domain.repository.MediaAssetRepository
import com.cinemind.core.domain.repository.ProjectRepository
import com.cinemind.feature.export.plan.ExportPlanner
import com.cinemind.feature.export.plan.ExportSettings
import com.cinemind.feature.export.service.ExportService
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

private class ExportFailure(message: String) : Exception(message)

/**
 * Renders a project's video track to an H.264/AAC MP4 (via [TimelineRenderer]) and publishes it to
 * Movies/CineMind. One export at a time; app-scoped so it survives leaving the export screen.
 * Must be driven from the main thread.
 */
@OptIn(UnstableApi::class)
@Singleton
class ExportManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val projectRepository: ProjectRepository,
    private val assetRepository: MediaAssetRepository,
    private val captionRepository: com.cinemind.core.domain.repository.CaptionRepository,
    private val textLayerRepository: com.cinemind.core.domain.repository.TextLayerRepository,
    private val lutLibrary: com.cinemind.feature.color.library.LutLibrary,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val renderer = TimelineRenderer(context)

    private val _state = MutableStateFlow<ExportState>(ExportState.Idle)
    val state: StateFlow<ExportState> = _state.asStateFlow()

    private var job: Job? = null
    private var transformer: Transformer? = null

    fun start(projectId: String, settings: ExportSettings) {
        if (_state.value.isActive) return
        _state.value = ExportState.Preparing(projectId)
        Diagnostics.journalStart(
            context,
            mapOf(
                "projectId" to projectId,
                "resolution" to settings.resolution.label,
                "fps" to settings.frameRate.toString(),
                "quality" to settings.quality.label,
            ),
        )
        job = scope.launch {
            try {
                run(projectId, settings)
            } catch (e: CancellationException) {
                throw e
            } catch (e: ExportFailure) {
                _state.value = ExportState.Failed(projectId, e.message ?: "Export failed")
            } catch (e: ExportException) {
                CineMindLogger.e(e, "Transformer failed: ${e.errorCodeName}")
                _state.value = ExportState.Failed(projectId, "The video couldn't be rendered (${e.errorCodeName}).")
            } catch (e: Exception) {
                CineMindLogger.e(e, "Export failed")
                _state.value = ExportState.Failed(projectId, e.message ?: "Export failed")
            } catch (e: OutOfMemoryError) {
                // An Error, not an Exception: without this the whole app would close.
                CineMindLogger.e(e, "Export ran out of memory")
                _state.value = ExportState.Failed(projectId, "The phone ran out of memory while exporting. Try a lower resolution, or close other apps.")
            } finally {
                val outcome = when (val s = _state.value) {
                    is ExportState.Success -> "success"
                    is ExportState.Failed -> "failed: ${s.message}"
                    is ExportState.Cancelled -> "cancelled"
                    else -> "stopped"
                }
                Diagnostics.journalEnd(context, outcome)
                transformer = null
                cleanupTemp()
            }
        }
    }

    fun cancel() {
        val current = _state.value
        if (!current.isActive) return
        transformer?.cancel()
        transformer = null
        job?.cancel()
        _state.value = ExportState.Cancelled(current.projectId ?: return)
    }

    /** Clears a finished/failed/cancelled state so the screen returns to its settings. */
    fun dismiss() {
        if (!_state.value.isActive) _state.value = ExportState.Idle
    }

    private suspend fun run(projectId: String, settings: ExportSettings) {
        val timeline = projectRepository.getProjectTimeline(projectId) ?: throw ExportFailure("Project not found")
        val track = timeline.tracks.firstOrNull { it.type == TrackType.VIDEO }
        val clips = timeline.clips.filter { it.trackId == track?.id }.sortedBy { it.timelineInUs }
        if (clips.isEmpty()) throw ExportFailure("This project has no clips to export.")

        val assets = clips.map { assetRepository.getAsset(it.assetId) }
        val missing = assets.count { it == null }
        if (missing > 0) throw ExportFailure("$missing clip(s) refer to media that is no longer available.")
        val pairs = clips.zip(
            assets.map { a -> RenderSource(a!!.uri, a.isVideo, a.hasAudio, a.frameRate, a.durationUs) },
        )
        // Overlay (picture-in-picture) tracks, bottom to top. Overlays whose media is gone are left out.
        val overlays = timeline.tracks.filter { it.type == TrackType.OVERLAY && it.isEnabled }.sortedBy { it.index }.map { t ->
            timeline.clips.filter { it.trackId == t.id }.mapNotNull { c ->
                val a = assetRepository.getAsset(c.assetId) ?: return@mapNotNull null
                RenderOverlay(c, RenderSource(a.uri, a.isVideo, false, a.frameRate, a.durationUs), a.width, a.height)
            }
        }

        // Recorded narration (voiceover track): volume, mute and music ducking live in the track's fields.
        val voiceTrack = timeline.tracks.firstOrNull { it.type == TrackType.VOICEOVER }
        val voiceover = voiceTrack?.let { t ->
            val recordings = timeline.clips.filter { it.trackId == t.id }.mapNotNull { c ->
                assetRepository.getAsset(c.assetId)?.let { a -> RenderVoiceClip(c, a.uri, a.durationUs) }
            }
            RenderVoiceover(recordings, com.cinemind.core.domain.audio.VoiceoverSettings(t.volume, t.opacity, muted = !t.isEnabled))
        }
        // Sound-effect tracks: track volume / mute in the track, per-sound volume in each clip.
        val sfx = timeline.tracks.filter { it.type == TrackType.SFX }.sortedBy { it.index }.map { t ->
            val sounds = timeline.clips.filter { it.trackId == t.id }.mapNotNull { c ->
                assetRepository.getAsset(c.assetId)?.let { a -> RenderVoiceClip(c, a.uri, a.durationUs) }
            }
            RenderVoiceover(sounds, com.cinemind.core.domain.audio.VoiceoverSettings(t.volume, 0f, muted = !t.isEnabled))
        }
        val sourceInfo = withContext(Dispatchers.IO) { SourceProbe.describe(context, assets.filterNotNull()) }
        Diagnostics.journalStage(context, "loading media", mapOf("clips" to clips.size.toString(), "sources" to sourceInfo))
        val project = timeline.project
        val size = ExportPlanner.outputSize(project.resolutionWidth, project.resolutionHeight, settings.resolution)
        val bitrate = ExportPlanner.videoBitrateBps(size, settings.frameRate, settings.quality)
        val durationUs = clips.maxOf { it.timelineOutUs }
        val audio = projectRepository.getProjectAudio(projectId)
        val music = audio.music?.let { m ->
            val song = assetRepository.getAsset(m.assetId)
            if (song == null) {
                CineMindLogger.w("Music file is no longer available; exporting without it")
                null
            } else {
                RenderMusic(song.uri, song.durationUs, m.startOffsetUs, m.volume, m.fadeInMs * 1_000, m.fadeOutMs * 1_000)
            }
        }
        val hasAudio = pairs.any { (_, s) -> s.isVideo && s.hasAudio } || music != null
        val estimate = ExportPlanner.estimatedSizeBytes(durationUs, bitrate, hasAudio)

        val workDir = tempDir()
        // Temp file + the copy in Movies, plus headroom.
        if (workDir.usableSpace < estimate * 2 + 100L * 1024 * 1024) {
            throw ExportFailure("Not enough free storage for this export (about ${estimate / (1024 * 1024)} MB needed twice).")
        }
        val outFile = File(workDir, "export.mp4")

        val captionList = captionRepository.getCaptions(projectId)
        val texts = com.cinemind.core.domain.text.TextLayerEditing.fitTo(textLayerRepository.get(projectId), durationUs)
        val captions = if (captionList.isEmpty()) null else RenderCaptions(captionList, captionRepository.getStyle(projectId))
        Diagnostics.journalStage(context, "building composition", mapOf("output" to "${size.width}x${size.height}", "bitrate" to bitrate.toString(), "overlays" to overlays.sumOf { it.size }.toString(), "captions" to captionList.size.toString(), "texts" to texts.size.toString(), "music" to (music != null).toString()))
        // Colour grades: each clip's look + adjustments baked into one LUT (same as the preview).
        val grades = withContext(Dispatchers.Default) { lutLibrary.bakedForClips(clips.associate { it.id to it.grade }) }
        val composition = renderer.buildComposition(pairs, size, settings.frameRate, music, audio.originalVolume, captions, overlays, voiceover, grades, texts, sfx, layerOrder = LayerOrderStore.get(context, projectId))
        _state.value = ExportState.Running(projectId, 0f)
        startForegroundService()

        Diagnostics.journalStage(context, "rendering (decode, effects, encode)")
        renderer.render(
            composition = composition,
            outFile = outFile,
            videoBitrateBps = bitrate,
            progressScope = scope,
            onProgress = { p -> if (_state.value is ExportState.Running) _state.value = ExportState.Running(projectId, p) },
            onTransformer = { transformer = it },
        )

        _state.value = ExportState.Saving(projectId)
        Diagnostics.journalStage(context, "verifying output", mapOf("outputBytes" to outFile.length().toString()))
        val actualDurationMs = verify(outFile)
        val name = ExportPlanner.fileName(project.name)
        Diagnostics.journalStage(context, "saving to Movies/CineMind")
        val uri = withContext(Dispatchers.IO) { MediaStoreSaver.save(context, outFile, name) }
        _state.value = ExportState.Success(projectId, uri, name, outFile.length(), actualDurationMs)
    }

    /** Confirms the file is a readable video and returns its duration in ms. */
    private suspend fun verify(file: File): Long = withContext(Dispatchers.IO) {
        if (!file.exists() || file.length() == 0L) throw ExportFailure("The exported file is empty.")
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(file.absolutePath)
            val hasVideo = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO) == "yes"
            val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            if (!hasVideo || durationMs <= 0) throw ExportFailure("The exported file isn't a playable video.")
            durationMs
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun startForegroundService() {
        // Keeps the process alive and shows progress while the user leaves the app.
        runCatching { ContextCompat.startForegroundService(context, Intent(context, ExportService::class.java)) }
            .onFailure { CineMindLogger.w("Export service not started: ${it.message}") }
    }

    private fun tempDir(): File = File(context.cacheDir, "exports").apply { mkdirs() }

    private fun cleanupTemp() {
        tempDir().listFiles()?.forEach { it.delete() }
    }
}
