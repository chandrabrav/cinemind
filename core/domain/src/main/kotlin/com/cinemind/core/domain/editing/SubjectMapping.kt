package com.cinemind.core.domain.editing

import kotlin.math.max
import kotlin.math.min

/**
 * Where a point of the finished frame comes from in the source picture, so a subject cutout made on
 * the source lines up after crop, quarter turns, flips and fit / fill. Coordinates are 0..1 with
 * the origin at the top left, in both spaces. The GPU effect evaluates exactly these formulas.
 *
 * @param kx,ky how much larger the frame is than the shown picture, per axis (1 = picture fills it)
 */
data class SubjectMapping(
    val kx: Float,
    val ky: Float,
    val quarterTurns: Int,
    val flipX: Boolean,
    val flipY: Boolean,
    val cropLeft: Float,
    val cropTop: Float,
    val cropWidth: Float,
    val cropHeight: Float,
) {
    /** Source point for frame point (ox, oy), or null where the frame shows bars, not the picture. */
    fun toSource(ox: Float, oy: Float): Pair<Float, Float>? {
        // 1. Undo fit / fill: position inside the shown (cropped, turned) picture.
        val ix = (ox - 0.5f) * kx
        val iy = (oy - 0.5f) * ky
        if (ix < -0.5f || ix > 0.5f || iy < -0.5f || iy > 0.5f) return null
        // 2. Undo the clockwise quarter turns (centred coordinates, y down), then the flips.
        var x = ix
        var y = iy
        repeat(quarterTurns.mod(4)) {
            val nx = y
            val ny = -x
            x = nx
            y = ny
        }
        if (flipX) x = -x
        if (flipY) y = -y
        // 3. Undo the crop.
        return (cropLeft + (x + 0.5f) * cropWidth) to (cropTop + (y + 0.5f) * cropHeight)
    }

    companion object {
        val IDENTITY = SubjectMapping(1f, 1f, 0, false, false, 0f, 0f, 1f, 1f)

        /**
         * For a main-track clip: source [srcW]×[srcH] (display orientation) framed by [framing] into
         * an [outW]×[outH] frame.
         */
        fun forClip(srcW: Int, srcH: Int, framing: ClipFraming, outW: Int, outH: Int): SubjectMapping {
            if (srcW <= 0 || srcH <= 0 || outW <= 0 || outH <= 0) return IDENTITY
            val c = framing.crop
            val cw = (1f - c.left - c.right).coerceIn(0.01f, 1f)
            val ch = (1f - c.top - c.bottom).coerceIn(0.01f, 1f)
            var w = srcW * cw
            var h = srcH * ch
            val turns = framing.quarterTurns.mod(4)
            if (turns % 2 == 1) { val t = w; w = h; h = t }
            val s = if (framing.fill) max(outW / w, outH / h) else min(outW / w, outH / h)
            return SubjectMapping(
                kx = outW / (w * s),
                ky = outH / (h * s),
                quarterTurns = turns,
                flipX = framing.flipH,
                flipY = framing.flipV,
                cropLeft = c.left,
                cropTop = c.top,
                cropWidth = cw,
                cropHeight = ch,
            )
        }
    }
}
