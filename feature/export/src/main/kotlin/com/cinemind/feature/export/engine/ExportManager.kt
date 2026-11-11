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
            } finally {
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

        val project = timeline.project
        val size = ExportPlanner.outputSize(project.resolutionWidth, project.resolutionHeight, settings.resolution)
        val bitrate = ExportPlanner.videoBitrateBps(size, settings.frameRate, settings.quality)
        val durationUs = clips.maxOf { it.timelineOutUs }
        val hasAudio = pairs.any { (_, s) -> s.isVideo && s.hasAudio }
        val estimate = ExportPlanner.estimatedSizeBytes(durationUs, bitrate, hasAudio)

        val workDir = tempDir()
        // Temp file + the copy in Movies, plus headroom.
        if (workDir.usableSpace < estimate * 2 + 100L * 1024 * 1024) {
            throw ExportFailure("Not enough free storage for this export (about ${estimate / (1024 * 1024)} MB needed twice).")
        }
        val outFile = File(workDir, "export.mp4")

        val composition = renderer.buildComposition(pairs, size, settings.frameRate)
        _state.value = ExportState.Running(projectId, 0f)
        startForegroundService()

        renderer.render(
            composition = composition,
            outFile = outFile,
            videoBitrateBps = bitrate,
            progressScope = scope,
            onProgress = { p -> if (_state.value is ExportState.Running) _state.value = ExportState.Running(projectId, p) },
            onTransformer = { transformer = it },
        )

        _state.value = ExportState.Saving(projectId)
        val actualDurationMs = verify(outFile)
        val name = ExportPlanner.fileName(project.name)
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
