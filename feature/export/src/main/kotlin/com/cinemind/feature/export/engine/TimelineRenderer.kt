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
import com.cinemind.core.domain.audio.VoiceMix
import com.cinemind.core.domain.audio.VoiceoverSettings
import com.cinemind.core.domain.editing.ClipAnimation
import com.cinemind.core.domain.editing.ClipFraming
import com.cinemind.core.domain.editing.DissolveMath
import com.cinemind.core.domain.editing.DissolvePiece
import com.cinemind.core.domain.editing.OverlayEditing
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
        /** Overlay tracks, bottom to top; each a list of clips on that track. */
        overlays: List<List<RenderOverlay>> = emptyList(),
        /** Recorded narration (one track), mixed under the video; can duck the music. */
        voiceover: RenderVoiceover? = null,
        /** Baked colour grade per clip id (from the LUT library). */
        grades: Map<String, com.cinemind.core.domain.color.CubeLut> = emptyMap(),
        /** Text / title / sticker layers, drawn over everything (export only; the preview draws them itself). */
        texts: List<com.cinemind.core.domain.text.TextLayer> = emptyList(),
        /** Sound-effect tracks (each clip's volume is its opacity field). */
        sfx: List<RenderVoiceover> = emptyList(),
        /** What is drawn above/below the picture-in-picture (captions, titles). */
        layerOrder: com.cinemind.core.domain.text.LayerOrder = com.cinemind.core.domain.text.LayerOrder(),
    ): Composition {
        val textsAbove = texts.filter { !it.styleObj.belowOverlays }
        val textsBelow = texts.filter { it.styleObj.belowOverlays }
        val fit = Presentation.createForWidthAndHeight(size.width, size.height, Presentation.LAYOUT_SCALE_TO_FIT)
        val fill = Presentation.createForWidthAndHeight(size.width, size.height, Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP)
        val animations = TransitionMath.animations(clips.map { it.first })
        val totalUs = clips.maxOfOrNull { it.first.timelineOutUs } ?: 0L
        val voiceClips = voiceover?.clips?.map { it.clip }.orEmpty()
        val voiceSettings = voiceover?.settings ?: VoiceoverSettings()
        val musicItems = music?.let { buildMusicItems(it, totalUs, voiceClips, voiceSettings) }.orEmpty()
        val voiceSequence = voiceover?.takeIf { it.clips.isNotEmpty() && !it.settings.muted }?.let { buildVoiceSequence(it, totalUs) }
        val sfxSequences = sfx.filter { it.clips.isNotEmpty() && !it.settings.muted }.map { buildVoiceSequence(it, totalUs, perClipVolume = true) }
        val hasAudio = voiceSequence != null || sfxSequences.isNotEmpty() || clips.any { (_, s) -> s.isVideo && s.hasAudio } || musicItems.isNotEmpty()
        val clipAudio = if (originalVolume < 0.999f) listOf(GainAudioProcessor { originalVolume.coerceIn(0f, 1f) }) else emptyList()

        // How a clip's picture is prepared (also used by the dissolve layer, so both match).
        val pictureEffects = { clip: Clip, source: RenderSource ->
            buildList<Effect> {
                if (source.isVideo && source.frameRate > fps + 1) add(FrameDropEffect.createDefaultFrameDropEffect(fps.toFloat()))
                val framing = ClipFraming.decode(clip.framing)
                ClipFramingEffects.sourceEffects(framing).forEach { add(it) }
                add(if (framing.fill) fill else fit)
                grades[clip.id]?.let { add(ColorLutEffect.create(it)) }
            }
        }

        val items = clips.mapIndexed { i, (clip, source) ->
            val lengthUs = clip.timelineOutUs - clip.timelineInUs
            val effects = buildList<Effect> {
                if (source.isVideo && source.frameRate > fps + 1) add(FrameDropEffect.createDefaultFrameDropEffect(fps.toFloat()))
                val framing = ClipFraming.decode(clip.framing)
                // Crop, quarter turns and flips act on the source picture; then it is fitted (or filled) to the frame.
                ClipFramingEffects.sourceEffects(framing).forEach { add(it) }
                add(if (framing.fill) fill else fit)
                // Colour grade (look + adjustments baked into one LUT), before transitions so fades dim the graded picture.
                grades[clip.id]?.let { add(ColorLutEffect.create(it)) }
                // Keyframed position / zoom / rotation / opacity, before transitions so those act on the moved picture.
                if (framing.keys.any { !it.isIdentity }) {
                    add(ClipMotion(clip, framing, clip.timelineInUs, size.width.toFloat() / size.height))
                    if (framing.keys.any { it.opacity < 1f }) add(ClipOpacity(clip, framing, clip.timelineInUs))
                }
                val anim = animations[i]
                if (!anim.isNone) {
                    add(TransitionMotion(clip.timelineInUs, lengthUs, anim, size.width.toFloat() / size.height))
                    add(TransitionBrightness(clip.timelineInUs, lengthUs, anim))
                }
                // Captions last: drawn on top of the picture and not moved/dimmed by transitions.
                // Under the picture-in-picture (drawn into this clip before compositing): captions if so
                // ordered, and titles marked "below", in the chosen order.
                val under = buildList<androidx.media3.effect.TextureOverlay> {
                    val cap = if (captions != null && !layerOrder.captionsAboveOverlays) CaptionBitmapOverlay.overlayFor(captions, size, clip.timelineInUs, clip.timelineOutUs) else null
                    val txt = TextBitmapOverlay.overlay(textsBelow.filter { it.endUs > clip.timelineInUs && it.startUs < clip.timelineOutUs }, size)
                    if (layerOrder.captionsAboveTitles) { txt?.let { add(it) }; cap?.let { add(it) } } else { cap?.let { add(it) }; txt?.let { add(it) } }
                }
                if (under.isNotEmpty()) add(androidx.media3.effect.OverlayEffect(under))
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
                EditedMediaItem.Builder(mediaItem)
                    .setDurationUs(knownDurationUs(source.durationUs, clip.sourceOutUs))
                    .setEffects(Effects(clipAudio, effects))
                    .apply { ClipSpeedProvider.forClip(clip)?.let { setSpeed(it) } }
                    .build()
            } else {
                val mediaItem = MediaItem.Builder()
                    .setUri(source.uri)
                    .setImageDurationMs(lengthUs / 1_000)
                    .build()
                EditedMediaItem.Builder(mediaItem)
                    .setDurationUs(lengthUs / 1_000 * 1_000)
                    .setFrameRate(fps)
                    .setEffects(Effects(emptyList(), effects))
                    .build()
            }
        }
        // Declaring the audio track type makes Media3 fill photos and silent clips with silence, so
        // the output audio track stays continuous (replaces experimentalSetForceAudioTrack).
        val mainTracks = if (hasAudio) setOf(C.TRACK_TYPE_AUDIO, C.TRACK_TYPE_VIDEO) else setOf(C.TRACK_TYPE_VIDEO)
        // Overlay tracks (picture-in-picture). Media3's compositor draws the FIRST video sequence on
        // top and takes its timing from it, so: overlay tracks first (top-most first), main track
        // last; empty time on an overlay track is a transparent still drawn at alpha 0.
        val overlayTracks = overlays.filter { it.isNotEmpty() }.reversed()
        val overlaySequences = overlayTracks.map { track -> buildOverlaySequence(track, totalUs, fps) }
        // Cross-dissolves: a layer above the main track with the neighbouring clip's footage
        // beyond its trim point, fading over each dissolve cut (see DissolveMath).
        val dissolvePieces = DissolveMath.pieces(
            clips.map { it.first },
            { i -> clips[i].second.durationUs.takeIf { it > 0 } },
            { i -> !clips[i].second.isVideo },
        )
        // Two layers: the incoming halves (next clip, before the cut) and the outgoing halves (previous
        // clip, after it), so pieces never touch inside one layer (back-to-back image + video items in
        // one sequence cut the image short on the emulator).
        val dissolveLayers = dissolvePieces.partition { it.alphaFrom < it.alphaTo }.toList().filter { it.isNotEmpty() }
        val dissolveSequences = dissolveLayers.map { buildDissolveSequence(it, clips, totalUs, fps, pictureEffects) }
        val mainIndex = overlaySequences.size + dissolveSequences.size
        val sequences = buildList {
            addAll(overlaySequences)
            addAll(dissolveSequences)
            add(EditedMediaItemSequence.Builder(mainTracks).addItems(items).build())
            if (musicItems.isNotEmpty()) add(EditedMediaItemSequence.Builder(setOf(C.TRACK_TYPE_AUDIO)).addItems(musicItems).build())
            if (voiceSequence != null) add(voiceSequence)
            addAll(sfxSequences)
        }
        val builder = Composition.Builder(sequences)
            .setHdrMode(Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_OPEN_GL)
        if (overlayTracks.isNotEmpty() || dissolveSequences.isNotEmpty()) {
            builder.setVideoCompositorSettings(OverlayCompositorSettings(overlayTracks, mainIndex, size, dissolveLayers))
        }
        // Titles and stickers after compositing: on top of overlay tracks too.
        val over = buildList<androidx.media3.effect.TextureOverlay> {
            val cap = if (captions != null && layerOrder.captionsAboveOverlays) CaptionBitmapOverlay.overlayFor(captions, size, 0, Long.MAX_VALUE) else null
            val txt = TextBitmapOverlay.overlay(textsAbove, size)
            // Later overlays are drawn on top.
            if (layerOrder.captionsAboveTitles) { txt?.let { add(it) }; cap?.let { add(it) } } else { cap?.let { add(it) }; txt?.let { add(it) } }
        }
        if (over.isNotEmpty()) builder.setEffects(Effects(emptyList(), listOf(androidx.media3.effect.OverlayEffect(over))))
        return builder.build()
    }

    /** The dissolve layer: each piece at its timeline time, empty time as gaps (drawn at alpha 0). */
    private fun buildDissolveSequence(
        pieces: List<DissolvePiece>,
        clips: List<Pair<Clip, RenderSource>>,
        totalUs: Long,
        fps: Int,
        pictureEffects: (Clip, RenderSource) -> List<Effect>,
    ): EditedMediaItemSequence {
        val builder = EditedMediaItemSequence.Builder(setOf(C.TRACK_TYPE_VIDEO))
        var cursor = 0L
        for ((index, raw) in pieces.withIndex()) {
            // Very short clips can make two cuts' pieces overlap: start this one where the last ended.
            val skip = (cursor - raw.timelineStartUs).coerceAtLeast(0)
            val length = raw.lengthUs - skip
            if (length < DissolveMath.MIN_PIECE_US) continue
            val start = raw.timelineStartUs + skip
            if (start > cursor) builder.addGap(start - cursor)
            val (clip, source) = clips[raw.clipIndex]
            // Pad a few frames past the piece (hidden by the compositor after the piece ends): on the
            // emulator the last part of a short item never reached the compositor.
            val nextStart = pieces.getOrNull(index + 1)?.timelineStartUs ?: totalUs
            val sourceLeft = if (source.isVideo && source.durationUs > 0) source.durationUs - (raw.sourceStartUs + skip + length) else Long.MAX_VALUE
            val pad = minOf(250_000L, nextStart - (start + length), sourceLeft).coerceAtLeast(0)
            val playLength = length + pad
            val item = if (source.isVideo) {
                val from = raw.sourceStartUs + skip
                val mediaItem = MediaItem.Builder()
                    .setUri(source.uri)
                    .setClippingConfiguration(
                        MediaItem.ClippingConfiguration.Builder()
                            .setStartPositionMs(from / 1_000)
                            .setEndPositionMs((from + playLength) / 1_000)
                            .build(),
                    )
                    .build()
                EditedMediaItem.Builder(mediaItem)
                    .setDurationUs(knownDurationUs(source.durationUs, from + playLength))
                    .setRemoveAudio(true)
                    .setEffects(Effects(emptyList(), pictureEffects(clip, source)))
                    .build()
            } else {
                EditedMediaItem.Builder(MediaItem.Builder().setUri(source.uri).setImageDurationMs(playLength / 1_000).build())
                    .setDurationUs(playLength / 1_000 * 1_000)
                    .setFrameRate(fps)
                    .setEffects(Effects(emptyList(), pictureEffects(clip, source)))
                    .build()
            }
            builder.addItem(item)
            cursor = start + playLength
        }
        if (totalUs > cursor) builder.addGap(totalUs - cursor)
        return builder.build()
    }

    private fun buildOverlaySequence(track: List<RenderOverlay>, totalUs: Long, fps: Int): EditedMediaItemSequence {
        val byId = track.associateBy { it.clip.id }
        val items = OverlayEditing.pieces(track.map { it.clip }, totalUs).toMutableList()
        val covered = items.sumOf { it.durationUs }
        if (covered < totalUs) items += OverlayEditing.Piece.Gap(totalUs - covered)
        // Empty time is a real gap (as in Media3's own picture-in-picture demo), shown at alpha 0.
        val builder = EditedMediaItemSequence.Builder(setOf(C.TRACK_TYPE_VIDEO))
        items.forEach { piece ->
            when (piece) {
                is OverlayEditing.Piece.Gap -> builder.addGap(piece.durationUs)
                is OverlayEditing.Piece.Item -> builder.addItem(run {
                    val o = byId.getValue(piece.clip.id)
                    val c = piece.clip
                    if (o.source.isVideo) {
                        val endMs = if (c.sourceOutUs >= o.source.durationUs) C.TIME_END_OF_SOURCE else c.sourceOutUs / 1_000
                        val mediaItem = MediaItem.Builder()
                            .setUri(o.source.uri)
                            .setClippingConfiguration(
                                MediaItem.ClippingConfiguration.Builder()
                                    .setStartPositionMs(c.sourceInUs / 1_000)
                                    .setEndPositionMs(endMs)
                                    .build(),
                            )
                            .build()
                        val effects = buildList<Effect> {
                            if (o.source.frameRate > fps + 1) add(FrameDropEffect.createDefaultFrameDropEffect(fps.toFloat()))
                        }
                        EditedMediaItem.Builder(mediaItem)
                            .setDurationUs(knownDurationUs(o.source.durationUs, c.sourceOutUs))
                            .setEffects(Effects(emptyList(), effects))
                            .build()
                    } else {
                        EditedMediaItem.Builder(MediaItem.Builder().setUri(o.source.uri).setImageDurationMs(piece.durationUs / 1_000).build())
                            .setDurationUs(piece.durationUs / 1_000 * 1_000)
                            .setFrameRate(fps)
                            .build()
                    }
                })
            }
        }
        return builder.build()
    }

    private fun transparentStill(durationUs: Long, fps: Int): EditedMediaItem {
        val f = File(context.cacheDir, "transparent_16.png")
        if (!f.exists()) {
            val bmp = android.graphics.Bitmap.createBitmap(16, 16, android.graphics.Bitmap.Config.ARGB_8888)
            bmp.eraseColor(android.graphics.Color.TRANSPARENT)
            f.outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        }
        return EditedMediaItem.Builder(
            MediaItem.Builder().setUri(android.net.Uri.fromFile(f)).setImageDurationMs((durationUs / 1_000).coerceAtLeast(1)).build(),
        ).setDurationUs((durationUs / 1_000).coerceAtLeast(1) * 1_000).setFrameRate(fps).build()
    }

    /**
     * Media3's CompositionPlayer (the editor preview) needs each item's full, unclipped duration up
     * front (export works it out itself). Metadata can be missing or slightly short, so never less
     * than the clipping end.
     */
    private fun knownDurationUs(sourceDurationUs: Long, clipEndUs: Long): Long =
        maxOf(sourceDurationUs, clipEndUs, 1L)

    /**
     * Narration as an audio-only sequence: recordings at their timeline times with silent gaps in
     * between, each with its volume and fades ([VoiceMix.voiceGain]).
     */
    private fun buildVoiceSequence(voice: RenderVoiceover, totalUs: Long, perClipVolume: Boolean = false): EditedMediaItemSequence {
        val byId = voice.clips.associateBy { it.clip.id }
        // Declaring the audio track type makes gaps (also a leading one) play as silence.
        val builder = EditedMediaItemSequence.Builder(setOf(C.TRACK_TYPE_AUDIO))
        for (piece in OverlayEditing.pieces(voice.clips.map { it.clip }, totalUs)) {
            when (piece) {
                is OverlayEditing.Piece.Gap -> builder.addGap(piece.durationUs)
                is OverlayEditing.Piece.Item -> {
                    val c = piece.clip
                    val src = byId.getValue(c.id)
                    val mediaItem = MediaItem.Builder()
                        .setUri(src.uri)
                        .setClippingConfiguration(
                            MediaItem.ClippingConfiguration.Builder()
                                .setStartPositionMs(c.sourceInUs / 1_000)
                                .setEndPositionMs(c.sourceOutUs / 1_000)
                                .build(),
                        )
                        .build()
                    val settings = if (perClipVolume) voice.settings.copy(volume = voice.settings.volume * c.opacity.coerceIn(0f, 2f)) else voice.settings
                    val gain = GainAudioProcessor { localUs -> VoiceMix.voiceGain(c, localUs, settings) }
                    builder.addItem(
                        EditedMediaItem.Builder(mediaItem)
                            .setDurationUs(knownDurationUs(src.durationUs, c.sourceOutUs))
                            .setRemoveVideo(true)
                            .setEffects(Effects(listOf(gain), emptyList()))
                            .build(),
                    )
                }
            }
        }
        return builder.build()
    }

    private fun buildMusicItems(music: RenderMusic, totalUs: Long, voice: List<Clip>, voiceSettings: VoiceoverSettings): List<EditedMediaItem> =
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
                val t = seg.timelineStartUs + localUs
                MusicLayout.gainAt(t, totalUs, music.volume, music.fadeInUs, music.fadeOutUs) * VoiceMix.musicDuck(t, voice, voiceSettings)
            }
            EditedMediaItem.Builder(mediaItem)
                .setDurationUs(knownDurationUs(music.durationUs, seg.sourceOutUs))
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
    private val aspect: Float,
) : MatrixTransformation {
    override fun getMatrix(presentationTimeUs: Long): Matrix {
        val look = TransitionMath.lookAt(TimelineRenderer.localTimeUs(presentationTimeUs, clipStartUs), lengthUs, anim)
        return Matrix().apply {
            postScale(look.scale, look.scale)
            if (look.rotation != 0f) {
                postScale(aspect, 1f)
                postRotate(-look.rotation)
                postScale(1f / aspect, 1f)
            }
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
