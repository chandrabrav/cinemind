package com.cinemind.core.ui.captions

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.SpannableString
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.ForegroundColorSpan
import com.cinemind.core.domain.captions.CaptionAlign
import com.cinemind.core.domain.captions.CaptionAnimation
import com.cinemind.core.domain.captions.CaptionFrame
import com.cinemind.core.domain.captions.CaptionStyle
import kotlin.math.max
import kotlin.math.min

/**
 * Draws one caption frame onto an Android [Canvas] the size of the video frame. The editor preview
 * and the export both call this, so what you see is what gets rendered. Sizes scale with the
 * frame's short side, so 720p and 4K look the same.
 *
 * Not thread-safe (keeps paints between frames); use one instance per drawing thread.
 */
class CaptionPainter {

    private val fill = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }
    private val box = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private var typefaceKey: String? = null

    /**
     * @param markUncertain draw a small amber dot when recognition was unsure (preview only; never
     *   burned into an export).
     * @return the area covered, in canvas pixels (empty when nothing was drawn).
     */
    fun draw(
        canvas: Canvas,
        width: Int,
        height: Int,
        frame: CaptionFrame,
        style: CaptionStyle,
        markUncertain: Boolean = false,
    ): RectF {
        val out = RectF()
        if (width <= 0 || height <= 0 || frame.words.isEmpty()) return out
        val short = min(width, height).toFloat()
        val textSize = (style.size * short).coerceAtLeast(6f)
        applyTypeface(style)
        fill.textSize = textSize
        stroke.textSize = textSize
        fill.color = style.textColor

        val text = frame.text
        val visible = if (frame.visibleChars in 0..text.length) frame.visibleChars else text.length
        if (visible == 0) return out
        val maxWidth = (width * 0.86f).toInt().coerceAtLeast(1)
        val alignment = when (style.alignEnum) {
            CaptionAlign.START -> Layout.Alignment.ALIGN_NORMAL
            CaptionAlign.CENTER -> Layout.Alignment.ALIGN_CENTER
            CaptionAlign.END -> Layout.Alignment.ALIGN_OPPOSITE
        }

        val fillText = SpannableString(text)
        val strokeText = SpannableString(text)
        if (frame.activeWord >= 0) {
            val range = wordRange(frame.words, frame.activeWord)
            fillText.setSpan(ForegroundColorSpan(style.highlightColor), range.first, range.last, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        if (visible < text.length) {
            fillText.setSpan(ForegroundColorSpan(Color.TRANSPARENT), visible, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            strokeText.setSpan(ForegroundColorSpan(Color.TRANSPARENT), visible, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }

        val fillLayout = layout(fillText, fill, maxWidth, alignment)
        val blockH = fillLayout.height.toFloat()
        val left = (width - maxWidth) / 2f
        val centreY = style.positionY.coerceIn(CaptionStyle.SAFE_TOP, CaptionStyle.SAFE_BOTTOM) * height
        val top = (centreY - blockH / 2f).coerceIn(CaptionStyle.SAFE_TOP * height, max(CaptionStyle.SAFE_TOP * height, CaptionStyle.SAFE_BOTTOM * height - blockH))

        // Animation.
        val enter = easeOut(frame.enter)
        val alpha = when (style.animationEnum) {
            CaptionAnimation.NONE, CaptionAnimation.TYPEWRITER -> 1f
            else -> enter
        } * (1f - frame.exit)
        if (alpha <= 0.01f) return out
        val scale = if (style.animationEnum == CaptionAnimation.POP) 0.82f + 0.18f * backOut(frame.enter) else 1f
        val slide = if (style.animationEnum == CaptionAnimation.SLIDE_UP) (1f - enter) * textSize * 0.7f else 0f

        // Bounds of the drawn text, for the background and the return value.
        var minX = Float.MAX_VALUE
        var maxX = 0f
        for (line in 0 until fillLayout.lineCount) {
            minX = min(minX, fillLayout.getLineLeft(line))
            maxX = max(maxX, fillLayout.getLineRight(line))
        }
        val padX = textSize * 0.35f
        val padY = textSize * 0.14f
        out.set(left + minX - padX, top - padY + slide, left + maxX + padX, top + blockH + padY + slide)

        val save = canvas.save()
        canvas.translate(0f, slide)
        canvas.scale(scale, scale, out.centerX(), out.centerY() - slide)
        if (alpha < 0.99f) canvas.saveLayerAlpha(out.left - textSize, out.top - textSize - slide, out.right + textSize, out.bottom + textSize - slide, (alpha * 255).toInt())

        if (style.backgroundOpacity > 0.01f) {
            box.color = withAlpha(style.backgroundColor, style.backgroundOpacity)
            val r = textSize * 0.28f
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
        val shadowRadius = textSize * 0.09f
        if (style.outline > 0.001f) {
            stroke.strokeWidth = style.outline * textSize * 2f
            stroke.color = style.outlineColor
            if (style.shadow) stroke.setShadowLayer(shadowRadius, 0f, textSize * 0.05f, 0x99000000.toInt()) else stroke.clearShadowLayer()
            fill.clearShadowLayer()
            layout(strokeText, stroke, maxWidth, alignment).draw(canvas)
        } else {
            if (style.shadow) fill.setShadowLayer(shadowRadius, 0f, textSize * 0.05f, 0xB3000000.toInt()) else fill.clearShadowLayer()
        }
        fillLayout.draw(canvas)
        canvas.restoreToCount(save)

        if (markUncertain && frame.uncertain) {
            box.color = 0xFFFFB547.toInt()
            canvas.drawCircle(out.right, out.top, textSize * 0.16f, box)
        }
        return out
    }

    private fun layout(text: CharSequence, paint: TextPaint, width: Int, alignment: Layout.Alignment): StaticLayout =
        StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
            .setAlignment(alignment)
            .setIncludePad(false)
            .setLineSpacing(0f, 1.04f)
            .setMaxLines(3)
            .build()

    private fun applyTypeface(style: CaptionStyle) {
        val key = "${style.font}:${style.weight}"
        if (key == typefaceKey) return
        typefaceKey = key
        val tf = com.cinemind.core.ui.text.FontBook.typeface(style.fontEnum, style.weight)
        fill.typeface = tf
        stroke.typeface = tf
    }

    companion object {
        /** Character range [first, last) of word [index] in the space-joined text. */
        fun wordRange(words: List<String>, index: Int): IntRange {
            var start = 0
            for (i in 0 until index) start += words[i].length + 1
            return start..(start + words[index].length)
        }

        private fun withAlpha(color: Int, alpha: Float): Int =
            (color and 0x00FFFFFF) or ((alpha.coerceIn(0f, 1f) * 255).toInt() shl 24)

        private fun easeOut(t: Float): Float = 1f - (1f - t) * (1f - t) * (1f - t)

        private fun backOut(t: Float): Float {
            val c1 = 1.70158f
            val c3 = c1 + 1f
            val x = t - 1f
            return 1f + c3 * x * x * x + c1 * x * x
        }
    }
}
