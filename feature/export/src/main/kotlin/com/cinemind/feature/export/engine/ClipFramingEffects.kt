package com.cinemind.feature.export.engine

import android.graphics.Matrix
import androidx.annotation.OptIn
import androidx.media3.common.Effect
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Crop
import androidx.media3.effect.MatrixTransformation
import androidx.media3.effect.RgbMatrix
import androidx.media3.effect.ScaleAndRotateTransformation
import com.cinemind.core.domain.editing.ClipFraming
import com.cinemind.core.domain.editing.TransformMath
import com.cinemind.core.domain.model.Clip

/** Media3 effects for a clip's [ClipFraming] (shared by preview and export). */
@OptIn(UnstableApi::class)
internal object ClipFramingEffects {

    /** Crop, then quarter turns and flips, on the source picture (before it is fitted to the frame). */
    fun sourceEffects(f: ClipFraming): List<Effect> = buildList {
        if (!f.crop.isNone) {
            val c = f.crop
            // Media3 Crop takes NDC edges: x −1..1 left→right, y −1..1 bottom→top.
            add(Crop(-1f + 2f * c.left, 1f - 2f * c.right, -1f + 2f * c.bottom, 1f - 2f * c.top))
        }
        val turns = f.quarterTurns.mod(4)
        if (turns != 0 || f.flipH || f.flipV) {
            add(
                ScaleAndRotateTransformation.Builder()
                    .setScale(if (f.flipH) -1f else 1f, if (f.flipV) -1f else 1f)
                    // Media3 rotates counter-clockwise; CineMind's turns are clockwise.
                    .setRotationDegrees((360 - 90 * turns).toFloat() % 360f)
                    .build(),
            )
        }
    }
}

/**
 * Keyframed position / zoom / rotation in the output frame. Works in NDC, so rotation is done in
 * aspect-corrected space (otherwise a non-square frame would shear the picture).
 */
@OptIn(UnstableApi::class)
internal class ClipMotion(
    private val clip: Clip,
    private val framing: ClipFraming,
    private val clipStartUs: Long,
    private val aspect: Float,
) : MatrixTransformation {
    override fun getMatrix(presentationTimeUs: Long): Matrix {
        val look = TransformMath.lookAtTimeline(clip, framing, TimelineRenderer.localTimeUs(presentationTimeUs, clipStartUs))
        return Matrix().apply {
            setScale(look.scale, look.scale)
            postScale(aspect, 1f)
            postRotate(-look.rotation)
            postScale(1f / aspect, 1f)
            // NDC spans 2 units; +y is up in NDC but down in CineMind's model.
            postTranslate(2f * look.x, -2f * look.y)
        }
    }
}

/** Keyframed opacity on the main track = fading the picture toward black. */
@OptIn(UnstableApi::class)
internal class ClipOpacity(
    private val clip: Clip,
    private val framing: ClipFraming,
    private val clipStartUs: Long,
) : RgbMatrix {
    override fun getMatrix(presentationTimeUs: Long, useHdr: Boolean): FloatArray {
        val o = TransformMath.lookAtTimeline(clip, framing, TimelineRenderer.localTimeUs(presentationTimeUs, clipStartUs)).opacity
        return floatArrayOf(
            o, 0f, 0f, 0f,
            0f, o, 0f, 0f,
            0f, 0f, o, 0f,
            0f, 0f, 0f, 1f,
        )
    }
}
