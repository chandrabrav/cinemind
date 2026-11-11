package com.cinemind.feature.export.engine

import android.content.Context
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.core.content.ContextCompat
import androidx.media3.common.C
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.FrameDropEffect
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import com.cinemind.core.common.CineMindLogger
import com.cinemind.core.domain.model.Clip
import com.cinemind.core.domain.model.MediaAsset
import com.cinemind.core.domain.model.TrackType
import com.cinemind.core.domain.repository.MediaAssetRepository
import com.cinemind.core.domain.repository.ProjectRepository
import com.cinemind.feature.export.plan.ExportPlanner
import com.cinemind.feature.export.plan.ExportSettings
import com.cinemind.feature.export.plan.OutputSize
import com.cinemind.feature.export.service.ExportService
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private class ExportFailure(message: String) : Exception(message)

/**
 * Renders a project's video track to an H.264/AAC MP4 with Media3 Transformer and publishes it to
 * Movies/CineMind. One export at a time; app-scoped so it survives leaving the export screen.
 *
 * Pipeline per clip: video → clipped to its source in/out (+ frame drop to the target fps if the
 * source is faster); photo → shown for its clip length at the target fps. Every clip is fitted
 * into the project frame (letterboxed, never cropped). HDR sources are tone-mapped to SDR.
 *
 * Must be driven from the main thread (Transformer is bound to the main looper here).
 */
@OptIn(UnstableApi::class)
@Singleton
class ExportManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val projectRepository: ProjectRepository,
    private val assetRepository: MediaAssetRepository,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mainHandler = Handler(Looper.getMainLooper())

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
        val pairs = clips.zip(assets.map { it!! })

        val project = timeline.project
        val size = ExportPlanner.outputSize(project.resolutionWidth, project.resolutionHeight, settings.resolution)
        val bitrate = ExportPlanner.videoBitrateBps(size, settings.frameRate, settings.quality)
        val durationUs = clips.maxOf { it.timelineOutUs }
        val hasAudio = pairs.any { (_, a) -> a.isVideo && a.hasAudio }
        val estimate = ExportPlanner.estimatedSizeBytes(durationUs, bitrate, hasAudio)

        val workDir = tempDir()
        // Temp file + the copy in Movies, plus headroom.
        if (workDir.usableSpace < estimate * 2 + 100L * 1024 * 1024) {
            throw ExportFailure("Not enough free storage for this export (about ${estimate / (1024 * 1024)} MB needed twice).")
        }
        val outFile = File(workDir, "export.mp4")

        val composition = buildComposition(pairs, size, settings.frameRate, hasAudio)
        _state.value = ExportState.Running(projectId, 0f)
        startForegroundService()

        transform(projectId, composition, outFile, bitrate)

        _state.value = ExportState.Saving(projectId)
        val actualDurationMs = verify(outFile)
        val name = ExportPlanner.fileName(project.name)
        val uri = withContext(Dispatchers.IO) { MediaStoreSaver.save(context, outFile, name) }
        _state.value = ExportState.Success(projectId, uri, name, outFile.length(), actualDurationMs)
    }

    private fun buildComposition(
        pairs: List<Pair<Clip, MediaAsset>>,
        size: OutputSize,
        fps: Int,
        hasAudio: Boolean,
    ): Composition {
        val fit = Presentation.createForWidthAndHeight(size.width, size.height, Presentation.LAYOUT_SCALE_TO_FIT)
        val items = pairs.map { (clip, asset) ->
            if (asset.isVideo) {
                val endMs = if (clip.sourceOutUs >= asset.durationUs) C.TIME_END_OF_SOURCE else clip.sourceOutUs / 1_000
                val mediaItem = MediaItem.Builder()
                    .setUri(asset.uri)
                    .setClippingConfiguration(
                        MediaItem.ClippingConfiguration.Builder()
                            .setStartPositionMs(clip.sourceInUs / 1_000)
                            .setEndPositionMs(endMs)
                            .build(),
                    )
                    .build()
                val videoEffects = buildList<Effect> {
                    if (asset.frameRate > fps + 1) add(FrameDropEffect.createDefaultFrameDropEffect(fps.toFloat()))
                    add(fit)
                }
                EditedMediaItem.Builder(mediaItem)
                    .setEffects(Effects(emptyList(), videoEffects))
                    .build()
            } else {
                val mediaItem = MediaItem.Builder()
                    .setUri(asset.uri)
                    .setImageDurationMs((clip.sourceOutUs - clip.sourceInUs) / 1_000)
                    .build()
                EditedMediaItem.Builder(mediaItem)
                    .setFrameRate(fps)
                    .setEffects(Effects(emptyList(), listOf<Effect>(fit)))
                    .build()
            }
        }
        return Composition.Builder(EditedMediaItemSequence(items))
            // Photos and silent clips get silence so the audio track stays continuous.
            .experimentalSetForceAudioTrack(hasAudio)
            .setHdrMode(Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_OPEN_GL)
            .build()
    }

    private suspend fun transform(projectId: String, composition: Composition, outFile: File, bitrate: Int): ExportResult {
        val encoderFactory = DefaultEncoderFactory.Builder(context)
            .setRequestedVideoEncoderSettings(VideoEncoderSettings.Builder().setBitrate(bitrate).build())
            .setEnableFallback(true)
            .build()

        return suspendCancellableCoroutine { cont ->
            val t = Transformer.Builder(context)
                .setVideoMimeType(MimeTypes.VIDEO_H264)
                .setAudioMimeType(MimeTypes.AUDIO_AAC)
                .setEncoderFactory(encoderFactory)
                .addListener(object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                        if (cont.isActive) cont.resume(exportResult)
                    }

                    override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                        if (cont.isActive) cont.resumeWithException(exportException)
                    }
                })
                .build()
            transformer = t
            t.start(composition, outFile.absolutePath)

            val progressJob = scope.launch {
                val holder = ProgressHolder()
                while (isActive && transformer === t) {
                    val available = t.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE
                    if (_state.value is ExportState.Running) {
                        _state.value = ExportState.Running(projectId, if (available) holder.progress / 100f else null)
                    }
                    delay(250)
                }
            }
            cont.invokeOnCancellation {
                progressJob.cancel()
                // Transformer must be touched on its own (main) thread.
                mainHandler.post { t.cancel() }
            }
        }.also { transformer = null }
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
