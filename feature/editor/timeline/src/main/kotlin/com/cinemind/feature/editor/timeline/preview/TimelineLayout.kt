package com.cinemind.feature.editor.timeline.preview

import java.util.Locale
import kotlin.math.roundToLong

/**
 * Pure geometry for the multitrack timeline: converts time ↔ pixels at a zoom level and picks
 * ruler tick spacing. [pxPerSecond] is in device pixels so drawing stays exact at any density.
 */
object TimelineLayout {

    /** Zoom steps in dp per second (user taps − / +). */
    val ZOOM_DP_PER_SECOND = listOf(16f, 24f, 36f, 54f, 80f, 120f, 180f, 260f)
    const val DEFAULT_ZOOM_INDEX = 3

    fun xForUs(us: Long, pxPerSecond: Float): Float = us / 1_000_000f * pxPerSecond

    fun usForX(x: Float, pxPerSecond: Float): Long =
        if (pxPerSecond <= 0f) 0L else (x / pxPerSecond * 1_000_000f).roundToLong().coerceAtLeast(0)

    fun widthForUs(durationUs: Long, pxPerSecond: Float, minPx: Float = 0f): Float =
        maxOf(minPx, xForUs(durationUs, pxPerSecond))

    /** Candidate ruler intervals (µs): ticks are spaced at least [minTickPx] apart. */
    private val INTERVALS_US = listOf(250_000L, 500_000L, 1_000_000L, 2_000_000L, 5_000_000L, 10_000_000L, 15_000_000L, 30_000_000L, 60_000_000L)

    fun tickIntervalUs(pxPerSecond: Float, minTickPx: Float): Long =
        INTERVALS_US.firstOrNull { xForUs(it, pxPerSecond) >= minTickPx } ?: INTERVALS_US.last()

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
