package com.cinemind.feature.editor.timeline.preview

import java.util.Locale
import kotlin.math.roundToLong

/**
 * Pure geometry for the multitrack timeline: converts time ↔ pixels at a zoom level and picks
 * ruler tick spacing. [pxPerSecond] is in device pixels so drawing stays exact at any density.
 */
object TimelineLayout {


    fun xForUs(us: Long, pxPerSecond: Float): Float = us / 1_000_000f * pxPerSecond

    fun usForX(x: Float, pxPerSecond: Float): Long =
        if (pxPerSecond <= 0f) 0L else (x / pxPerSecond * 1_000_000f).roundToLong().coerceAtLeast(0)

    fun widthForUs(durationUs: Long, pxPerSecond: Float, minPx: Float = 0f): Float =
        maxOf(minPx, xForUs(durationUs, pxPerSecond))

    /** Candidate ruler intervals (µs): ticks are spaced at least [minTickPx] apart. */
    private val INTERVALS_US = listOf(
        250_000L, 500_000L, 1_000_000L, 2_000_000L, 5_000_000L, 10_000_000L, 15_000_000L,
        30_000_000L, 60_000_000L, 120_000_000L, 300_000_000L, 600_000_000L,
    )

    fun tickIntervalUs(pxPerSecond: Float, minTickPx: Float): Long =
        INTERVALS_US.firstOrNull { xForUs(it, pxPerSecond) >= minTickPx } ?: INTERVALS_US.last()

    // ---------------------------------------------------------------- continuous zoom

    /** Most zoomed-out: the whole project fits, but never below this. */
    const val MIN_DP_PER_SECOND = 2f
    /** Starting zoom. */
    const val DEFAULT_DP_PER_SECOND = 54f
    /** Most zoomed-in: one frame is this wide. */
    const val MAX_DP_PER_FRAME = 28f
    /**
     * Compose can't lay out a row much wider than ~260k px, so very long projects can't zoom all the
     * way to single frames (a virtualised timeline in the multi-track rewrite removes this limit).
     */
    const val MAX_CONTENT_PX = 240_000f

    /** Zoom range in px per second for a project of [totalUs] at [fps]. */
    fun zoomRange(totalUs: Long, fps: Int, density: Float, viewportPx: Float, leadPx: Float): ClosedFloatingPointRange<Float> {
        val seconds = (totalUs / 1_000_000f).coerceAtLeast(0.5f)
        val min = minOf(MIN_DP_PER_SECOND * density, fitPxPerSecond(totalUs, viewportPx, leadPx))
        val maxByFrames = MAX_DP_PER_FRAME * density * fps.coerceAtLeast(1)
        val maxByLayout = (MAX_CONTENT_PX - viewportPx) / seconds
        val max = maxOf(min, minOf(maxByFrames, maxByLayout))
        return min..max
    }

    /** Zoom at which [durationUs] exactly fills the viewport between the side margins. */
    fun fitPxPerSecond(durationUs: Long, viewportPx: Float, leadPx: Float): Float {
        val seconds = (durationUs / 1_000_000f).coerceAtLeast(0.1f)
        return ((viewportPx - 2 * leadPx).coerceAtLeast(1f)) / seconds
    }

    /**
     * New scroll offset after zooming from [oldPps] to [newPps] so that the time under
     * [anchorX] (viewport pixels, e.g. the midpoint between two fingers) stays under it.
     */
    fun scrollAfterZoom(scrollPx: Float, anchorX: Float, leadPx: Float, oldPps: Float, newPps: Float): Float {
        if (oldPps <= 0f) return scrollPx
        val seconds = (scrollPx + anchorX - leadPx) / oldPps
        return (leadPx + seconds * newPps - anchorX).coerceAtLeast(0f)
    }

    /** Scroll offset that shows [startUs]..[endUs] fitted between the margins. */
    fun scrollToShow(startUs: Long, leadPx: Float, pps: Float): Float = xForUs(startUs, pps).coerceAtLeast(0f)

    // ---------------------------------------------------------------- frame-level ruler

    /** Ruler spacing: either whole frames ([frames] > 0) or a time interval. */
    data class RulerStep(val frames: Int, val us: Long) {
        val isFrames: Boolean get() = frames > 0
    }

    private val FRAME_STEPS = listOf(1, 2, 5, 10)

    /** Frame steps when zoomed in far enough, else a time interval. */
    fun rulerStep(pxPerSecond: Float, minTickPx: Float, fps: Int): RulerStep {
        val f = fps.coerceAtLeast(1)
        for (n in FRAME_STEPS) {
            if (n >= f) break
            val us = n * 1_000_000L / f
            if (xForUs(us, pxPerSecond) >= minTickPx) return RulerStep(n, us)
        }
        return RulerStep(0, tickIntervalUs(pxPerSecond, minTickPx))
    }

    /**
     * Tick times between [fromUs] and [toUs] (only the visible part is drawn, so long projects stay
     * cheap). Frame ticks are computed from frame numbers, so they never drift at 29.97/30 fps.
     */
    fun ticksIn(step: RulerStep, fps: Int, fromUs: Long, toUs: Long, totalUs: Long): List<Long> {
        val end = minOf(toUs, totalUs)
        if (end < fromUs) return emptyList()
        return if (step.isFrames) {
            val f = fps.coerceAtLeast(1)
            val firstFrame = (fromUs * f / 1_000_000L) / step.frames * step.frames
            generateSequence(firstFrame) { it + step.frames }
                .map { frame -> frame * 1_000_000L / f }
                .dropWhile { it < fromUs }
                .takeWhile { it <= end }
                .toList()
        } else {
            val first = fromUs / step.us * step.us
            generateSequence(first) { it + step.us }.dropWhile { it < fromUs }.takeWhile { it <= end }.toList()
        }
    }

    /** Label for a frame tick: whole seconds as "3s", other frames as "f12" (frame within the second). */
    fun frameLabel(us: Long, fps: Int): String {
        val f = fps.coerceAtLeast(1)
        val frame = ((us * f + 500_000) / 1_000_000L)
        val inSecond = frame % f
        return if (inSecond == 0L) label(frame / f * 1_000_000L) else "f$inSecond"
    }

    /** Tick positions from 0 through [totalUs] (inclusive of the last full interval). */
    fun ticks(totalUs: Long, intervalUs: Long): List<Long> {
        if (intervalUs <= 0) return emptyList()
        val count = (totalUs / intervalUs).toInt()
        return (0..count).map { it * intervalUs }
    }

    /** Ruler label: "0s", "1.5s", "1:05". */
    fun label(us: Long): String {
        val totalMs = us / 1_000
        val s = totalMs / 1_000
        return when {
            s >= 60 -> String.format(Locale.ROOT, "%d:%02d", s / 60, s % 60)
            totalMs % 1_000 == 0L -> "${s}s"
            else -> String.format(Locale.ROOT, "%.1fs", totalMs / 1_000f)
        }
    }

    /**
     * Scroll position that keeps the playhead at [anchor] (0..1) of the viewport while playing,
     * clamped to the content.
     */
    fun followScroll(playheadX: Float, viewportPx: Float, contentPx: Float, anchor: Float = 0.4f): Int {
        val target = playheadX - viewportPx * anchor
        return target.coerceIn(0f, maxOf(0f, contentPx - viewportPx)).toInt()
    }
}
