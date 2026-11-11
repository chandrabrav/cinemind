package com.cinemind.feature.editor.timeline.preview

/** A clip's place on the timeline, in microseconds. [endUs] is exclusive. */
data class ClipSpan(val startUs: Long, val endUs: Long) {
    val durationUs: Long get() = (endUs - startUs).coerceAtLeast(0)
}

/** Where a timeline position falls: which clip, and how far into it. */
data class ClipLocation(val index: Int, val offsetUs: Long)

/**
 * Converts between whole-timeline positions and (clip index, offset) pairs used by the player's
 * playlist. Pure logic so it can be unit-tested without a device.
 */
object TimelineMath {

    fun totalDurationUs(spans: List<ClipSpan>): Long = spans.maxOfOrNull { it.endUs } ?: 0L

    /**
     * Finds the clip playing at [positionUs]. Positions before the start clamp to the first clip;
     * positions at or past the end clamp to the end of the last clip. Returns null for an empty timeline.
     */
    fun locate(spans: List<ClipSpan>, positionUs: Long): ClipLocation? {
        if (spans.isEmpty()) return null
        val t = positionUs.coerceIn(0L, totalDurationUs(spans))
        val index = spans.indexOfFirst { t < it.endUs }
        if (index == -1) {
            val last = spans.lastIndex
            return ClipLocation(last, spans[last].durationUs)
        }
        return ClipLocation(index, (t - spans[index].startUs).coerceAtLeast(0L))
    }

    /** Whole-timeline position for an offset inside clip [index] (offset clamped to the clip). */
    fun absolutePositionUs(spans: List<ClipSpan>, index: Int, offsetUs: Long): Long {
        if (spans.isEmpty()) return 0L
        val span = spans[index.coerceIn(0, spans.lastIndex)]
        return span.startUs + offsetUs.coerceIn(0L, span.durationUs)
    }
}
