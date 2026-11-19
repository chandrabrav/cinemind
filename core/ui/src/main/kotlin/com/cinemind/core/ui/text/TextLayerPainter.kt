package com.cinemind.core.ui.text

import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Shader
import android.icu.text.BreakIterator
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import com.cinemind.core.domain.text.TextAnim
import com.cinemind.core.domain.text.TextLoop
import com.cinemind.core.domain.text.TextFrame
import com.cinemind.core.domain.text.TextKind
import com.cinemind.core.domain.text.TextStyle
import com.cinemind.core.domain.text.placedAt
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.sin
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
        // Motion path: drawn where it is along its glide at this moment.
        val layer = frame.layer.placedAt(frame.localUs)
        if (width <= 0 || height <= 0 || layer.text.isBlank()) return out
        val st = layer.styleObj
        if (layer.kindEnum == TextKind.PROGRESS) return drawProgress(canvas, width, height, layer, st, frame)
        val sticker = layer.kindEnum == TextKind.STICKER
        val short = min(width, height).toFloat()
        val textSize = (layer.size * short).coerceAtLeast(6f)
        val tf = typeface(st)
        val e = easeOut(frame.enter)
        val x = frame.exit
        val t = frame.localUs / 1_000_000f
        val track = if (st.animIn == TextAnim.TRACK && !sticker) (1f - e) * 0.6f else 0f
        listOf(fill, stroke).forEach {
            it.textSize = textSize
            it.typeface = tf
            it.letterSpacing = if (sticker) 0f else st.spacing + track
            it.shader = null
            it.alpha = 255
        }
        fill.color = st.color

        var text: String = if (st.uppercase && !sticker) layer.text.uppercase() else layer.text
        if (st.animIn == TextAnim.TYPEWRITER && frame.enter < 1f) {
            // Typewriter takes longer than the other entrances: whole text by ~1.2 s.
            val n = ceil(text.length * frame.enter).toInt().coerceIn(0, text.length)
            text = text.take(n)
            if (text.isEmpty()) return out
        }

        // ---- Entrance ----
        var alpha = when (st.animIn) {
            TextAnim.NONE, TextAnim.TYPEWRITER, TextAnim.CASCADE, TextAnim.WORDS, TextAnim.WIPE -> 1f
            TextAnim.STAMP -> (frame.enter * 4f).coerceAtMost(1f)
            TextAnim.GLITCH -> if (frame.enter >= 0.85f || rnd((frame.enter * 14f).toInt() + 7) > 0.35f) 1f else 0f
            else -> e
        }
        if (st.animOut != TextAnim.NONE) alpha *= (1f - x)
        var scale = when (st.animIn) {
            TextAnim.POP -> 0.6f + 0.4f * backOut(frame.enter)
            TextAnim.ZOOM -> 1.6f - 0.6f * e
            TextAnim.BOUNCE -> bounceOut(frame.enter).coerceAtLeast(0.01f)
            TextAnim.SPIN -> 0.3f + 0.7f * backOut(frame.enter)
            TextAnim.STAMP -> 1f + 1.6f * (1f - frame.enter).let { it * it * it }
            else -> 1f
        }
        var rotation = layer.rotation + if (st.animIn == TextAnim.SPIN) -(1f - e) * 200f else 0f
        if (st.animOut == TextAnim.ZOOM || st.animOut == TextAnim.POP) scale *= 1f - 0.25f * x
        var dx = if (st.animIn == TextAnim.SLIDE_LEFT) -(1f - e) * width * 0.18f else 0f
        var dy = if (st.animIn == TextAnim.SLIDE_UP) (1f - e) * textSize * 1.2f else 0f
        if (st.animOut == TextAnim.SLIDE_UP) dy -= x * textSize * 1.2f
        if (st.animOut == TextAnim.SLIDE_LEFT) dx += x * width * 0.18f
        // A stamp lands with a short shudder.
        if (st.animIn == TextAnim.STAMP && frame.enter in 0.55f..0.99f) {
            val k = (1f - frame.enter) * textSize * 0.12f
            val step = (frame.localUs / 30_000L).toInt()
            dx += (rnd(step) - 0.5f) * 2f * k
            dy += (rnd(step + 99) - 0.5f) * 2f * k
        }
        // RGB split while glitching in, and in short bursts for the glitch loop.
        var split = if (st.animIn == TextAnim.GLITCH && frame.enter < 1f) (1f - frame.enter) * textSize * 0.12f else 0f
        if (st.animIn == TextAnim.GLITCH && frame.enter < 1f) dx += (rnd((frame.enter * 20f).toInt()) - 0.5f) * textSize * 0.4f * (1f - frame.enter)

        // ---- Looping emphasis (grows in with the entrance) ----
        val lp = if (sticker && st.loop == TextLoop.WAVE) TextLoop.NONE else st.loop
        when (lp) {
            TextLoop.PULSE -> scale *= 1f + 0.05f * e * sin(2f * PI.toFloat() * t * 1.6f)
            TextLoop.SHAKE -> {
                val step = (frame.localUs / 50_000L).toInt()
                dx += (rnd(step) - 0.5f) * textSize * 0.06f * e
                dy += (rnd(step + 31) - 0.5f) * textSize * 0.06f * e
                rotation += (rnd(step + 57) - 0.5f) * 3f * e
            }
            TextLoop.FLOAT -> dy += sin(2f * PI.toFloat() * t * 0.45f) * textSize * 0.1f * e
            TextLoop.FLICKER -> {
                val step = (frame.localUs / 70_000L).toInt()
                if (rnd(step) > 0.9f) alpha *= 0.25f + 0.4f * rnd(step + 3)
            }
            TextLoop.GLITCH -> {
                if (frame.localUs % 1_700_000L < 180_000L && frame.enter >= 1f) {
                    val step = (frame.localUs / 40_000L).toInt()
                    split = max(split, textSize * 0.08f)
                    dx += (rnd(step) - 0.5f) * textSize * 0.25f
                }
            }
            TextLoop.WAVE, TextLoop.NONE -> Unit
        }
        if (alpha <= 0.01f) return out

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
        val depth = if (sticker) 0f else st.extrude * textSize
        out.set(left - padX, top - padY, left + blockW + padX + depth, top + blockH + padY + depth)

        // Letter / word units: only built when an animation moves them separately.
        val staggering = (st.animIn == TextAnim.CASCADE || st.animIn == TextAnim.WORDS) && frame.enter < 1f
        val perUnit = !sticker && (staggering || lp == TextLoop.WAVE)
        val units = if (perUnit) units(text, words = st.animIn == TextAnim.WORDS) else emptyList()
        val looks = units.indices.map { i -> unitLook(i, units.size, st, frame, t, textSize, e) }

        val save = canvas.save()
        canvas.rotate(rotation, cx, cy)
        canvas.scale(scale, scale, cx, cy)
        if (st.animIn == TextAnim.WIPE && frame.enter < 1f) {
            canvas.clipRect(out.left - textSize, out.top - textSize, out.left + (out.width() + textSize) * e, out.bottom + textSize)
        }
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

        // 3D extrusion: the text repeated down-right in the extrusion colour, back to front.
        if (depth > 0.5f) {
            val steps = (depth / 1.5f).toInt().coerceIn(4, 28)
            fill.clearShadowLayer()
            fill.color = st.extrudeColor
            for (k in steps downTo 1) {
                val o = depth * k / steps
                canvas.translate(o, o)
                paintText(canvas, fill, text, fillLayout, units, looks)
                canvas.translate(-o, -o)
            }
            fill.color = st.color
        }
        // Glitch colour split behind the main text.
        if (split > 0.5f) {
            fill.clearShadowLayer()
            fill.color = 0xD9FF1F5A.toInt()
            canvas.translate(-split, 0f)
            paintText(canvas, fill, text, fillLayout, units, looks)
            fill.color = 0xD900E5FF.toInt()
            canvas.translate(split * 2f, 0f)
            paintText(canvas, fill, text, fillLayout, units, looks)
            canvas.translate(-split, 0f)
            fill.color = st.color
        }
        if (st.outline > 0.001f && !sticker) {
            stroke.strokeWidth = st.outline * textSize * 2f
            stroke.color = st.outlineColor
            if (st.shadow && depth <= 0.5f) stroke.setShadowLayer(textSize * 0.09f, 0f, textSize * 0.05f, 0x99000000.toInt()) else stroke.clearShadowLayer()
            paintText(canvas, stroke, text, layout(text, stroke, layoutWidth, alignment), units, looks)
            fill.clearShadowLayer()
        } else if (st.glow) {
            fill.setShadowLayer(textSize * 0.4f, 0f, 0f, st.color)
            paintText(canvas, fill, text, fillLayout, units, looks) // first pass: wide glow
            fill.setShadowLayer(textSize * 0.12f, 0f, 0f, st.color)
        } else if (st.shadow && depth <= 0.5f) {
            fill.setShadowLayer(textSize * 0.09f, 0f, textSize * 0.05f, 0xB3000000.toInt())
        } else {
            fill.clearShadowLayer()
        }
        if (st.hasGradient && !sticker) {
            fill.shader = LinearGradient(0f, 0f, 0f, blockH, st.color, st.color2, Shader.TileMode.CLAMP)
        }
        paintText(canvas, fill, text, fillLayout, units, looks)
        fill.shader = null
        canvas.restoreToCount(save)
        return out
    }

    /** A bar across the frame that fills from left to right over the layer's time. */
    private fun drawProgress(canvas: Canvas, width: Int, height: Int, layer: com.cinemind.core.domain.text.TextLayer, st: TextStyle, frame: TextFrame): RectF {
        val thick = (layer.size * min(width, height) * 0.35f).coerceAtLeast(3f)
        val margin = width * 0.06f
        val cy = layer.y * height
        val p = (frame.localUs.toFloat() / layer.lengthUs.coerceAtLeast(1)).coerceIn(0f, 1f)
        val r = thick / 2f
        rect.set(margin, cy - r, width - margin, cy + r)
        box.shader = null
        box.color = withAlpha(st.color, 0.3f)
        canvas.drawRoundRect(rect, r, r, box)
        val filled = RectF(margin, cy - r, margin + (width - 2 * margin) * p, cy + r)
        box.color = st.color
        if (st.hasGradient) box.shader = LinearGradient(margin, 0f, width - margin, 0f, st.color, st.color2, Shader.TileMode.CLAMP)
        if (filled.width() > 0.5f) canvas.drawRoundRect(filled, r, r, box)
        box.shader = null
        return RectF(rect.left, rect.top - thick, rect.right, rect.bottom + thick)
    }

    /** Offset / scale / opacity of one letter or word. */
    private class UnitLook(val dy: Float, val scale: Float, val alpha: Float)

    private fun unitLook(i: Int, n: Int, st: TextStyle, frame: TextFrame, t: Float, textSize: Float, e: Float): UnitLook {
        val window = 3f
        val p = ((frame.enter * (n + window)) - i).div(window).coerceIn(0f, 1f)
        var dy = 0f
        var scale = 1f
        var alpha = 1f
        if (frame.enter < 1f) {
            when (st.animIn) {
                TextAnim.CASCADE -> { dy = -(1f - easeOut(p)) * textSize * 0.9f; alpha = p }
                TextAnim.WORDS -> { scale = if (p <= 0f) 0f else 0.5f + 0.5f * backOut(p); alpha = (p * 3f).coerceAtMost(1f) }
                else -> Unit
            }
        }
        if (st.loop == TextLoop.WAVE) dy += sin(2f * PI.toFloat() * t * 1.1f - i * 0.45f) * textSize * 0.1f * e
        return UnitLook(dy, scale, alpha)
    }

    /** Draws [text] whole, or unit by unit (positions from the layout) when [units] is set. */
    private fun paintText(canvas: Canvas, paint: TextPaint, text: String, layout: StaticLayout, units: List<IntRange>, looks: List<UnitLook>) {
        if (units.isEmpty()) {
            layout.draw(canvas)
            return
        }
        val baseAlpha = paint.alpha
        units.forEachIndexed { i, u ->
            val look = looks[i]
            if (look.alpha <= 0.01f || look.scale <= 0.01f) return@forEachIndexed
            val line = layout.getLineForOffset(u.first)
            val ux = layout.getPrimaryHorizontal(u.first)
            val base = layout.getLineBaseline(line).toFloat()
            val w = paint.measureText(text, u.first, u.last + 1)
            paint.alpha = (baseAlpha * look.alpha).toInt().coerceIn(0, 255)
            val s = canvas.save()
            canvas.translate(0f, look.dy)
            if (look.scale != 1f) canvas.scale(look.scale, look.scale, ux + w / 2f, base - paint.textSize * 0.35f)
            canvas.drawText(text, u.first, u.last + 1, ux, base, paint)
            canvas.restoreToCount(s)
        }
        paint.alpha = baseAlpha
    }

    /**
     * Letters (grapheme clusters) or words of [text], skipping spaces. Scripts that join letters
     * (Devanagari and other Indic scripts) always move word by word so they stay correctly shaped.
     */
    private fun units(text: String, words: Boolean): List<IntRange> {
        val byWord = words || text.any { it.code in 0x0900..0x0DFF }
        val out = ArrayList<IntRange>()
        if (byWord) {
            Regex("\\S+").findAll(text).forEach { out += it.range }
        } else {
            val it = BreakIterator.getCharacterInstance()
            it.setText(text)
            var start = it.first()
            var end = it.next()
            while (end != BreakIterator.DONE) {
                if (text.substring(start, end).isNotBlank()) out += start until end
                start = end
                end = it.next()
            }
        }
        return out
    }

    private fun typeface(st: TextStyle): Typeface {
        val key = "${st.font.name}:${st.weight}"
        return typefaces.getOrPut(key) {
            FontBook.typeface(st.font, st.weight)
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
        /** Repeatable pseudo-random 0..1 for a step number, so preview and export jitter identically. */
        fun rnd(seed: Int): Float {
            var h = seed * -0x61c88647 + 0x2545F491
            h = h xor (h ushr 15)
            h *= 0x2C1B3C6D
            h = h xor (h ushr 12)
            return (h and 0xFFFF) / 65535f
        }
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
