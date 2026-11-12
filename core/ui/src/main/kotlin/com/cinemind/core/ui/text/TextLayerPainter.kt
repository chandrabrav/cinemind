package com.cinemind.core.ui.text

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import com.cinemind.core.domain.text.TextAnim
import com.cinemind.core.domain.text.TextFrame
import com.cinemind.core.domain.text.TextKind
import com.cinemind.core.domain.text.TextStyle
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * Draws text / title / sticker layers onto an Android [Canvas] the size of the video frame. The
 * editor preview and the export both call this, so the preview matches the file. Sizes scale with
 * the frame's short side. Not thread-safe; one instance per drawing thread.
 */
class TextLayerPainter {

    private val fill = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }
    private val box = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val typefaces = HashMap<String, Typeface>()

    /** Draws every frame in order (bottom first). Returns each layer's covered area by id. */
    fun drawAll(canvas: Canvas, width: Int, height: Int, frames: List<TextFrame>): Map<String, RectF> =
        frames.associate { it.layer.id to draw(canvas, width, height, it) }

    fun draw(canvas: Canvas, width: Int, height: Int, frame: TextFrame): RectF {
        val out = RectF()
        val layer = frame.layer
        if (width <= 0 || height <= 0 || layer.text.isBlank()) return out
        val st = layer.styleObj
        val sticker = layer.kindEnum == TextKind.STICKER
        val short = min(width, height).toFloat()
        val textSize = (layer.size * short).coerceAtLeast(6f)
        val tf = typeface(st)
        listOf(fill, stroke).forEach {
            it.textSize = textSize
            it.typeface = tf
            it.letterSpacing = if (sticker) 0f else st.spacing
        }
        fill.color = st.color

        var text: String = if (st.uppercase && !sticker) layer.text.uppercase() else layer.text
        // Entrance / exit.
        val e = easeOut(frame.enter)
        val x = frame.exit
        if (st.animIn == TextAnim.TYPEWRITER && frame.enter < 1f) {
            // Typewriter takes longer than the other entrances: whole text by ~1.2 s.
            val n = ceil(text.length * frame.enter).toInt().coerceIn(0, text.length)
            text = text.take(n)
            if (text.isEmpty()) return out
        }
        var alpha = when (st.animIn) {
            TextAnim.NONE, TextAnim.TYPEWRITER -> 1f
            else -> e
        }
        if (st.animOut != TextAnim.NONE) alpha *= (1f - x)
        if (alpha <= 0.01f) return out
        var scale = when (st.animIn) {
            TextAnim.POP -> 0.6f + 0.4f * backOut(frame.enter)
            TextAnim.ZOOM -> 1.6f - 0.6f * e
            TextAnim.BOUNCE -> bounceOut(frame.enter).coerceAtLeast(0.01f)
            else -> 1f
        }
        if (st.animOut == TextAnim.ZOOM || st.animOut == TextAnim.POP) scale *= 1f - 0.25f * x
        var dx = if (st.animIn == TextAnim.SLIDE_LEFT) -(1f - e) * width * 0.18f else 0f
        var dy = if (st.animIn == TextAnim.SLIDE_UP) (1f - e) * textSize * 1.2f else 0f
        if (st.animOut == TextAnim.SLIDE_UP) dy -= x * textSize * 1.2f
        if (st.animOut == TextAnim.SLIDE_LEFT) dx += x * width * 0.18f

        val alignment = when (st.align) {
            -1 -> Layout.Alignment.ALIGN_NORMAL
            1 -> Layout.Alignment.ALIGN_OPPOSITE
            else -> Layout.Alignment.ALIGN_CENTER
        }
        val maxWidth = (width * 0.9f).toInt().coerceAtLeast(1)
        val measured = (text.split('\n').maxOfOrNull { fill.measureText(it) } ?: 0f).toInt() + 2
        val layoutWidth = min(maxWidth, max(measured, 1))
        val fillLayout = layout(text, fill, layoutWidth, alignment)
        val blockW = layoutWidth.toFloat()
        val blockH = fillLayout.height.toFloat()
        val cx = layer.x * width + dx
        val cy = layer.y * height + dy
        val left = cx - blockW / 2f
        val top = cy - blockH / 2f
        val padX = textSize * 0.32f
        val padY = textSize * 0.12f
        out.set(left - padX, top - padY, left + blockW + padX, top + blockH + padY)

        val save = canvas.save()
        canvas.rotate(layer.rotation, cx, cy)
        canvas.scale(scale, scale, cx, cy)
        if (alpha < 0.99f) {
            val m = textSize * 1.5f
            canvas.saveLayerAlpha(out.left - m, out.top - m, out.right + m, out.bottom + m, (alpha * 255).toInt())
        }
        if (st.boxOpacity > 0.01f && !sticker) {
            box.color = withAlpha(st.boxColor, st.boxOpacity)
            val r = textSize * 0.24f
            for (line in 0 until fillLayout.lineCount) {
                rect.set(
                    left + fillLayout.getLineLeft(line) - padX,
                    top + fillLayout.getLineTop(line) - padY,
                    left + fillLayout.getLineRight(line) + padX,
                    top + fillLayout.getLineBottom(line) + padY,
                )
                canvas.drawRoundRect(rect, r, r, box)
            }
        }
        canvas.translate(left, top)
        if (st.outline > 0.001f && !sticker) {
            stroke.strokeWidth = st.outline * textSize * 2f
            stroke.color = st.outlineColor
            if (st.shadow) stroke.setShadowLayer(textSize * 0.09f, 0f, textSize * 0.05f, 0x99000000.toInt()) else stroke.clearShadowLayer()
            layout(text, stroke, layoutWidth, alignment).draw(canvas)
            fill.clearShadowLayer()
        } else if (st.glow) {
            fill.setShadowLayer(textSize * 0.4f, 0f, 0f, st.color)
            fillLayout.draw(canvas) // first pass: wide glow
            fill.setShadowLayer(textSize * 0.12f, 0f, 0f, st.color)
        } else if (st.shadow) {
            fill.setShadowLayer(textSize * 0.09f, 0f, textSize * 0.05f, 0xB3000000.toInt())
        } else {
            fill.clearShadowLayer()
        }
        fillLayout.draw(canvas)
        canvas.restoreToCount(save)
        return out
    }

    private fun typeface(st: TextStyle): Typeface {
        val key = "${st.font.name}:${st.weight}"
        return typefaces.getOrPut(key) {
            Typeface.create(Typeface.create(st.font.family, Typeface.NORMAL), st.weight.coerceIn(100, 1000), false)
        }
    }

    private fun layout(text: CharSequence, paint: TextPaint, width: Int, alignment: Layout.Alignment): StaticLayout =
        StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
            .setAlignment(alignment)
            .setIncludePad(true)
            .setLineSpacing(0f, 1.02f)
            .setMaxLines(6)
            .build()

    private companion object {
        fun withAlpha(color: Int, alpha: Float): Int = (color and 0x00FFFFFF) or ((alpha.coerceIn(0f, 1f) * 255).toInt() shl 24)
        fun easeOut(t: Float): Float = 1f - (1f - t) * (1f - t) * (1f - t)
        fun backOut(t: Float): Float {
            val c1 = 1.70158f
            val c3 = c1 + 1f
            val x = t - 1f
            return 1f + c3 * x * x * x + c1 * x * x
        }
        fun bounceOut(t: Float): Float {
            val n = 7.5625f
            val d = 2.75f
            return when {
                t < 1f / d -> n * t * t
                t < 2f / d -> { val u = t - 1.5f / d; n * u * u + 0.75f }
                t < 2.5f / d -> { val u = t - 2.25f / d; n * u * u + 0.9375f }
                else -> { val u = t - 2.625f / d; n * u * u + 0.984375f }
            }
        }
    }
}
