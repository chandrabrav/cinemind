package com.cinemind.feature.export.engine

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BitmapOverlay
import androidx.media3.effect.OverlayEffect
import com.cinemind.core.domain.captions.Caption
import com.cinemind.core.domain.captions.CaptionFrame
import com.cinemind.core.domain.captions.CaptionLayout
import com.cinemind.core.domain.captions.CaptionStyle
import com.cinemind.core.ui.captions.CaptionPainter
import com.cinemind.feature.export.plan.OutputSize

/** Captions to burn into an export. */
data class RenderCaptions(val captions: List<Caption>, val style: CaptionStyle)

/**
 * Draws the caption layer onto each exported frame with [CaptionPainter] — the same code the
 * editor preview uses. The overlay is a frame-sized transparent bitmap that is redrawn only when
 * what's on screen changes (Media3 re-uploads it when its generation id changes).
 *
 * Effects run in composition time (see [TimelineRenderer.localTimeUs]), so caption times can be
 * used directly.
 */
@OptIn(UnstableApi::class)
internal class CaptionBitmapOverlay(
    private val captions: List<Caption>,
    private val style: CaptionStyle,
    private val size: OutputSize,
) : BitmapOverlay() {

    // Allocated on first use and freed when Media3 releases the item (a 4K frame is ~33 MB).
    private var _bitmap: Bitmap? = null
    private var canvas: Canvas? = null
    private val painter = CaptionPainter()
    private var lastFrame: CaptionFrame? = null
    private var drawnOnce = false

    override fun getBitmap(presentationTimeUs: Long): Bitmap {
        val bitmap = _bitmap ?: Bitmap.createBitmap(size.width, size.height, Bitmap.Config.ARGB_8888).also {
            _bitmap = it
            canvas = Canvas(it)
        }
        val canvas = canvas!!
        val frame = CaptionLayout.frameAt(captions, style, presentationTimeUs)
        if (!drawnOnce || frame != lastFrame) {
            bitmap.eraseColor(Color.TRANSPARENT)
            if (frame != null) painter.draw(canvas, size.width, size.height, frame, style)
            lastFrame = frame
            drawnOnce = true
        }
        return bitmap
    }

    override fun release() {
        super.release()
        _bitmap?.recycle()
        _bitmap = null
        canvas = null
    }

    companion object {
        /** An [OverlayEffect] for one sequence item, or null when there is nothing to draw. */
        fun effectFor(captions: RenderCaptions?, size: OutputSize, itemStartUs: Long, itemEndUs: Long): OverlayEffect? {
            if (captions == null) return null
            val relevant = captions.captions.filter { it.endUs > itemStartUs && it.startUs < itemEndUs }
            if (relevant.isEmpty()) return null
            return OverlayEffect(listOf(CaptionBitmapOverlay(relevant, captions.style, size)))
        }
    }
}
