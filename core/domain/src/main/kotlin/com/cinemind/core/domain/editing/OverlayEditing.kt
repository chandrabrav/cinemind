package com.cinemind.core.domain.editing

import com.cinemind.core.domain.model.Clip

/**
 * Editing rules for **overlay tracks** (picture-in-picture, B-roll on top, stickers made from
 * media): unlike the main track, clips sit at free positions in time, there can be empty space
 * between them, and nothing ripples. Within one track clips never overlap. Pure; unit-tested.
 *
 * Position/size use the clip's transform fields: [Clip.transformX]/[Clip.transformY] = centre of
 * the overlay in the frame (0..1), [Clip.scale] = overlay width as a fraction of the frame width,
 * [Clip.rotation] in degrees, [Clip.opacity] 0..1.
 */
object OverlayEditing {

    const val MIN_US = 200_000L
    /** New overlays start at 40 % of the frame width, centred, slightly above the middle. */
    const val DEFAULT_SCALE = 0.4f

    fun sorted(clips: List<Clip>): List<Clip> = clips.sortedBy { it.timelineInUs }

    /** Free time on the track at [atUs]: the gap containing it, or null if a clip covers it. */
    fun gapAt(clips: List<Clip>, atUs: Long, totalUs: Long): LongRange? {
        val list = sorted(clips)
        if (list.any { atUs >= it.timelineInUs && atUs < it.timelineOutUs }) return null
        val start = list.lastOrNull { it.timelineOutUs <= atUs }?.timelineOutUs ?: 0L
        val end = list.firstOrNull { it.timelineInUs > atUs }?.timelineInUs ?: totalUs
        return if (end - start >= MIN_US) start..end else null
    }

    /**
     * Places a new overlay of natural length [lengthUs] at [atUs], shortened to fit the free gap
     * there; null when there is no room.
     */
    fun place(clips: List<Clip>, clip: Clip, atUs: Long, lengthUs: Long, totalUs: Long): List<Clip>? {
        val at = atUs.coerceIn(0, (totalUs - MIN_US).coerceAtLeast(0))
        val gap = gapAt(clips, at, totalUs) ?: return null
        val end = minOf(at + lengthUs, gap.last)
        if (end - at < MIN_US) return null
        val placed = clip.copy(
            timelineInUs = at,
            timelineOutUs = end,
            sourceOutUs = clip.sourceInUs + (end - at),
        )
        return sorted(clips + placed)
    }

    /** Moves a clip to start at [newStartUs], clamped so it stays inside its neighbours and the video. */
    fun move(clips: List<Clip>, id: String, newStartUs: Long, totalUs: Long): List<Clip> {
        val list = sorted(clips)
        val i = list.indexOfFirst { it.id == id }
        if (i < 0) return list
        val c = list[i]
        val len = c.timelineOutUs - c.timelineInUs
        val others = list.filterIndexed { k, _ -> k != i }
        // The free span around the requested position (between the neighbours it would sit between).
        val before = others.lastOrNull { it.timelineInUs <= newStartUs }?.timelineOutUs ?: 0L
        val after = others.firstOrNull { it.timelineInUs > newStartUs }?.timelineInUs ?: totalUs
        if (after - before < len) return list // doesn't fit there: stay
        val start = newStartUs.coerceIn(before, after - len)
        return sorted(others + c.copy(timelineInUs = start, timelineOutUs = start + len))
    }

    /**
     * Trims a clip on the track: [newInUs]/[newOutUs] are timeline times. Limited by its source
     * (video length; stills are unlimited), its neighbours and [MIN_US].
     */
    fun trim(clips: List<Clip>, id: String, newInUs: Long, newOutUs: Long, sourceDurationUs: Long?, totalUs: Long): List<Clip> {
        val list = sorted(clips)
        val i = list.indexOfFirst { it.id == id }
        if (i < 0) return list
        val c = list[i]
        val lo = if (i > 0) list[i - 1].timelineOutUs else 0L
        val hi = if (i < list.lastIndex) list[i + 1].timelineInUs else totalUs
        // How far the start may move left: by the source material before sourceIn (videos) and the neighbour.
        val minIn = maxOf(lo, if (sourceDurationUs != null) c.timelineInUs - c.sourceInUs else lo)
        val maxOutBySource = if (sourceDurationUs != null) c.timelineInUs + (sourceDurationUs - c.sourceInUs) else Long.MAX_VALUE
        val start = newInUs.coerceIn(minIn, c.timelineOutUs - MIN_US)
        val end = newOutUs.coerceIn(start + MIN_US, minOf(hi, maxOutBySource))
        val newSourceIn = c.sourceInUs + (start - c.timelineInUs)
        val out = c.copy(
            timelineInUs = start,
            timelineOutUs = end,
            sourceInUs = newSourceIn,
            sourceOutUs = newSourceIn + (end - start),
        )
        return list.toMutableList().apply { this[i] = out }
    }

    fun setTransform(clips: List<Clip>, id: String, x: Float, y: Float, scale: Float, rotation: Float, opacity: Float): List<Clip> =
        clips.map {
            if (it.id != id) it
            else it.copy(
                transformX = x.coerceIn(0f, 1f),
                transformY = y.coerceIn(0f, 1f),
                scale = scale.coerceIn(0.05f, 1f),
                rotation = ((rotation % 360f) + 360f) % 360f,
                opacity = opacity.coerceIn(0f, 1f),
            )
        }

    /** One piece of an overlay track as Media3 sees it: a clip, or empty time. */
    sealed interface Piece {
        val durationUs: Long
        data class Item(val clip: Clip) : Piece {
            override val durationUs: Long get() = clip.timelineOutUs - clip.timelineInUs
        }
        data class Gap(override val durationUs: Long) : Piece
    }

    /**
     * The track as a back-to-back sequence of clips and gaps from 0 to the end of its last clip
     * (clips past [totalUs] are cut there), which is how it is fed to the compositor.
     */
    fun pieces(clips: List<Clip>, totalUs: Long): List<Piece> {
        val out = mutableListOf<Piece>()
        var t = 0L
        for (c in sorted(clips)) {
            if (c.timelineInUs >= totalUs) break
            if (c.timelineInUs > t) out += Piece.Gap(c.timelineInUs - t)
            val end = minOf(c.timelineOutUs, totalUs)
            out += Piece.Item(if (end < c.timelineOutUs) c.copy(timelineOutUs = end, sourceOutUs = c.sourceInUs + (end - c.timelineInUs)) else c)
            t = end
        }
        return out
    }
}
