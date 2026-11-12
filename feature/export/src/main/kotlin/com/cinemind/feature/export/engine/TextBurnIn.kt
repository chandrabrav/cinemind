package com.cinemind.feature.export.engine

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BitmapOverlay
import androidx.media3.effect.OverlayEffect
import com.cinemind.core.domain.text.TextFrame
import com.cinemind.core.domain.text.TextLayer
import com.cinemind.core.domain.text.TextLayerEditing
import com.cinemind.core.ui.text.TextLayerPainter
import com.cinemind.feature.export.plan.OutputSize

/**
 * Draws text / title / sticker layers onto every exported frame with [TextLayerPainter] (the same
 * code as the editor preview). Used as a composition-level effect, so it runs after overlay tracks
 * are composited and titles are never hidden under picture-in-picture. The frame-sized bitmap is
 * redrawn only when what's on screen changes.
 */
@OptIn(UnstableApi::class)
internal class TextBitmapOverlay(
    private val layers: List<TextLayer>,
    private val size: OutputSize,
) : BitmapOverlay() {

    private var _bitmap: Bitmap? = null
    private var canvas: Canvas? = null
    private val painter = TextLayerPainter()
    private var last: List<TextFrame>? = null

    override fun getBitmap(presentationTimeUs: Long): Bitmap {
        val bitmap = _bitmap ?: Bitmap.createBitmap(size.width, size.height, Bitmap.Config.ARGB_8888).also {
            _bitmap = it
            canvas = Canvas(it)
        }
        val frames = TextLayerEditing.framesAt(layers, presentationTimeUs)
        if (frames != last) {
            bitmap.eraseColor(Color.TRANSPARENT)
            painter.drawAll(canvas!!, size.width, size.height, frames)
            last = frames
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
        fun effectFor(layers: List<TextLayer>, size: OutputSize): OverlayEffect? =
            layers.filter { it.text.isNotBlank() }.takeIf { it.isNotEmpty() }?.let { OverlayEffect(listOf(TextBitmapOverlay(it, size))) }
    }
}
