package com.cinemind.feature.export.engine

import android.content.Context
import android.graphics.Matrix
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.FrameDropEffect
import androidx.media3.effect.MatrixTransformation
import androidx.media3.effect.Presentation
import androidx.media3.effect.RgbMatrix
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
import com.cinemind.core.domain.audio.MusicLayout
import com.cinemind.core.domain.editing.ClipAnimation
import com.cinemind.core.domain.editing.TransitionMath
import com.cinemind.core.domain.model.Clip
import com.cinemind.feature.export.plan.OutputSize
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** What the renderer needs to know about one clip's source. */
data class RenderSource(
    val uri: String,
    val isVideo: Boolean,
    val hasAudio: Boolean,
    val frameRate: Float,
    val durationUs: Long,
)

/** Background music for a render. */
data class RenderMusic(
    val uri: String,
    val durationUs: Long,
    val startOffsetUs: Long,
    val volume: Float,
    val fadeInUs: Long,
    val fadeOutUs: Long,
)

/**
 * Turns a laid-out track into a Media3 Transformer [Composition] and renders it to an MP4.
 * Independent of storage/DI so it can be exercised directly by instrumented tests.
 * Call from the main thread.
 */
@OptIn(UnstableApi::class)
class TimelineRenderer(private val context: Context) {

    /**
     * @param music optional background music: a second, audio-only sequence (looped / cut to the
     *   video length by [MusicLayout]) that Media3 mixes with the clips' own sound.
     * @param originalVolume volume of the clips' own sound, 0..1.
     */
    fun buildComposition(
        clips: List<Pair<Clip, RenderSource>>,
        size: OutputSize,
        fps: Int,
        music: RenderMusic? = null,
        originalVolume: Float = 1f,
        captions: RenderCaptions? = null,
    ): Composition {
        val fit = Presentation.createForWidthAndHeight(size.width, size.height, Presentation.LAYOUT_SCALE_TO_FIT)
        val animations = TransitionMath.animations(clips.map { it.first })
        val totalUs = clips.maxOfOrNull { it.first.timelineOutUs } ?: 0L
        val musicItems = music?.let { buildMusicItems(it, totalUs) }.orEmpty()
        val hasAudio = clips.any { (_, s) -> s.isVideo && s.hasAudio } || musicItems.isNotEmpty()
        val clipAudio = if (originalVolume < 0.999f) listOf(GainAudioProcessor { originalVolume.coerceIn(0f, 1f) }) else emptyList()

        val items = clips.mapIndexed { i, (clip, source) ->
            val lengthUs = clip.timelineOutUs - clip.timelineInUs
            val effects = buildList<Effect> {
                if (source.isVideo && source.frameRate > fps + 1) add(FrameDropEffect.createDefaultFrameDropEffect(fps.toFloat()))
                add(fit)
                val anim = animations[i]
                if (!anim.isNone) {
                    add(TransitionMotion(clip.timelineInUs, lengthUs, anim))
                    add(TransitionBrightness(clip.timelineInUs, lengthUs, anim))
                }
                // Captions last: drawn on top of the picture and not moved/dimmed by transitions.
                CaptionBitmapOverlay.effectFor(captions, size, clip.timelineInUs, clip.timelineOutUs)?.let { add(it) }
            }
            if (source.isVideo) {
                val endMs = if (clip.sourceOutUs >= source.durationUs) C.TIME_END_OF_SOURCE else clip.sourceOutUs / 1_000
                val mediaItem = MediaItem.Builder()
                    .setUri(source.uri)
                    .setClippingConfiguration(
                        MediaItem.ClippingConfiguration.Builder()
                            .setStartPositionMs(clip.sourceInUs / 1_000)
                            .setEndPositionMs(endMs)
                            .build(),
                    )
                    .build()
                EditedMediaItem.Builder(mediaItem).setEffects(Effects(clipAudio, effects)).build()
            } else {
                val mediaItem = MediaItem.Builder()
                    .setUri(source.uri)
                    .setImageDurationMs(lengthUs / 1_000)
                    .build()
                EditedMediaItem.Builder(mediaItem)
                    .setFrameRate(fps)
                    .setEffects(Effects(emptyList(), effects))
                    .build()
            }
        }
        val sequences = buildList {
            add(EditedMediaItemSequence(items))
            if (musicItems.isNotEmpty()) add(EditedMediaItemSequence(musicItems))
        }
        return Composition.Builder(sequences)
            // Photos and silent clips get silence so the audio track stays continuous.
            .experimentalSetForceAudioTrack(hasAudio)
            .setHdrMode(Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_OPEN_GL)
            .build()
    }

    private fun buildMusicItems(music: RenderMusic, totalUs: Long): List<EditedMediaItem> =
        MusicLayout.segments(music.durationUs, music.startOffsetUs, totalUs).map { seg ->
            val mediaItem = MediaItem.Builder()
                .setUri(music.uri)
                .setClippingConfiguration(
                    MediaItem.ClippingConfiguration.Builder()
                        .setStartPositionMs(seg.sourceInUs / 1_000)
                        .setEndPositionMs(seg.sourceOutUs / 1_000)
                        .build(),
                )
                .build()
            val gain = GainAudioProcessor { localUs ->
                MusicLayout.gainAt(seg.timelineStartUs + localUs, totalUs, music.volume, music.fadeInUs, music.fadeOutUs)
            }
            EditedMediaItem.Builder(mediaItem)
                .setRemoveVideo(true)
                .setEffects(Effects(listOf(gain), emptyList()))
                .build()
        }

    /**
     * Renders [composition] to [outFile] (H.264 + AAC). [onProgress] receives 0..1 or null.
     * Cancelling the calling coroutine cancels the render.
     */
    suspend fun render(
        composition: Composition,
        outFile: File,
        videoBitrateBps: Int,
        progressScope: CoroutineScope,
        onProgress: (Float?) -> Unit = {},
        onTransformer: (Transformer?) -> Unit = {},
    ): ExportResult {
        val encoderFactory = DefaultEncoderFactory.Builder(context)
            .setRequestedVideoEncoderSettings(VideoEncoderSettings.Builder().setBitrate(videoBitrateBps).build())
            .setEnableFallback(true)
            .build()
        val mainHandler = Handler(Looper.getMainLooper())

        return suspendCancellableCoroutine { cont ->
            var done = false
            val transformer = Transformer.Builder(context)
                .setVideoMimeType(MimeTypes.VIDEO_H264)
                .setAudioMimeType(MimeTypes.AUDIO_AAC)
                .setEncoderFactory(encoderFactory)
                .addListener(object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                        done = true
                        if (cont.isActive) cont.resume(exportResult)
                    }

                    override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                        done = true
                        if (cont.isActive) cont.resumeWithException(exportException)
                    }
                })
                .build()
            onTransformer(transformer)
            transformer.start(composition, outFile.absolutePath)

            val progressJob = progressScope.launch {
                val holder = ProgressHolder()
                while (isActive && !done) {
                    val available = transformer.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE
                    onProgress(if (available) holder.progress / 100f else null)
                    delay(250)
                }
            }
            cont.invokeOnCancellation {
                progressJob.cancel()
                mainHandler.post { transformer.cancel() }
            }
        }.also { onTransformer(null) }
    }

    companion object {
        /**
         * Media3 adds each sequence item's start offset to frame timestamps before effects run,
         * so effects see composition time. Verified by the emulator export test
         * (ExportEndToEndTest): fades must land exactly on clip boundaries.
         */
        internal fun localTimeUs(presentationTimeUs: Long, clipStartUs: Long): Long = presentationTimeUs - clipStartUs
    }
}

/** Scale/slide part of a transition, applied in normalized device coordinates. */
@OptIn(UnstableApi::class)
private class TransitionMotion(
    private val clipStartUs: Long,
    private val lengthUs: Long,
    private val anim: ClipAnimation,
) : MatrixTransformation {
    override fun getMatrix(presentationTimeUs: Long): Matrix {
        val look = TransitionMath.lookAt(TimelineRenderer.localTimeUs(presentationTimeUs, clipStartUs), lengthUs, anim)
        return Matrix().apply {
            postScale(look.scale, look.scale)
            // NDC spans 2 units across; +y is up in NDC but down in FrameLook.
            postTranslate(2f * look.translateX, -2f * look.translateY)
        }
    }
}

/** Fade part of a transition: scales RGB toward black. */
@OptIn(UnstableApi::class)
private class TransitionBrightness(
    private val clipStartUs: Long,
    private val lengthUs: Long,
    private val anim: ClipAnimation,
) : RgbMatrix {
    override fun getMatrix(presentationTimeUs: Long, useHdr: Boolean): FloatArray {
        val b = TransitionMath.lookAt(TimelineRenderer.localTimeUs(presentationTimeUs, clipStartUs), lengthUs, anim).brightness
        return floatArrayOf(
            b, 0f, 0f, 0f,
            0f, b, 0f, 0f,
            0f, 0f, b, 0f,
            0f, 0f, 0f, 1f,
        )
    }
}
