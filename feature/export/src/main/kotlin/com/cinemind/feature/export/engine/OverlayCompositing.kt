package com.cinemind.feature.export.engine

import androidx.annotation.OptIn
import androidx.media3.common.OverlaySettings
import androidx.media3.common.VideoCompositorSettings
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.StaticOverlaySettings
import com.cinemind.core.domain.model.Clip
import com.cinemind.feature.export.plan.OutputSize

/** One clip on an overlay track with its source and its displayed pixel size (rotation applied). */
data class RenderOverlay(val clip: Clip, val source: RenderSource, val width: Int, val height: Int)

/** Pure placement maths for overlays (unit-tested). */
object OverlayPlacement {

    /** Where an overlay sits, in Media3 compositor terms. */
    data class Placement(val anchorX: Float, val anchorY: Float, val scaleX: Float, val scaleY: Float, val rotationCcw: Float, val alpha: Float)

    /**
     * The compositor first sizes an overlay to its own pixels relative to the output frame, then
     * applies the scale. We want the overlay to be [Clip.scale] × the frame width wide, keeping its
     * aspect ratio, centred at ([Clip.transformX], [Clip.transformY]) with y going down.
     */
    fun of(clip: Clip, overlayWidth: Int, overlayHeight: Int, output: OutputSize): Placement {
        val w = overlayWidth.coerceAtLeast(1).toFloat()
        val s = clip.scale.coerceIn(0.05f, 1f) * output.width / w
        return Placement(
            anchorX = clip.transformX.coerceIn(0f, 1f) * 2f - 1f,
            anchorY = 1f - clip.transformY.coerceIn(0f, 1f) * 2f,
            scaleX = s,
            scaleY = s,
            // Our rotation is clockwise (as users drag it); the compositor's is counter-clockwise.
            rotationCcw = (360f - clip.rotation) % 360f,
            alpha = clip.opacity.coerceIn(0f, 1f),
        )
    }

    /** Overlay on track at composition time [tUs], or null in empty time. */
    fun clipAt(track: List<RenderOverlay>, tUs: Long): RenderOverlay? =
        track.firstOrNull { tUs >= it.clip.timelineInUs && tUs < it.clip.timelineOutUs }
}

/**
 * Positions overlay tracks for Media3's video compositor. Input ids follow the sequence order: the
 * overlay tracks (top-most first) and then the main track at [mainIndex], whose size is the output.
 */
@OptIn(UnstableApi::class)
internal class OverlayCompositorSettings(
    private val tracksTopFirst: List<List<RenderOverlay>>,
    private val mainIndex: Int,
    private val output: OutputSize,
) : VideoCompositorSettings {

    private val hidden: OverlaySettings = StaticOverlaySettings.Builder().setAlphaScale(0f).build()

    override fun getOutputSize(inputSizes: List<Size>): Size =
        inputSizes.getOrNull(mainIndex) ?: Size(output.width, output.height)

    override fun getOverlaySettings(inputId: Int, presentationTimeUs: Long): OverlaySettings {
        if (inputId >= tracksTopFirst.size) return StaticOverlaySettings.Builder().build() // main track: full frame
        val o = OverlayPlacement.clipAt(tracksTopFirst[inputId], presentationTimeUs) ?: return hidden
        val p = OverlayPlacement.of(o.clip, o.width, o.height, output)
        return StaticOverlaySettings.Builder()
            .setBackgroundFrameAnchor(p.anchorX, p.anchorY)
            .setScale(p.scaleX, p.scaleY)
            .setRotationDegrees(p.rotationCcw)
            .setAlphaScale(p.alpha)
            .build()
    }
}

/** One recording on the voiceover track. */
data class RenderVoiceClip(val clip: Clip, val uri: String, val durationUs: Long)

/** The voiceover track for a render. */
data class RenderVoiceover(val clips: List<RenderVoiceClip>, val settings: com.cinemind.core.domain.audio.VoiceoverSettings)
