package com.cinemind.core.domain.editing

import com.cinemind.core.domain.model.Clip

/** What a clip's source allows: how long the source is (null = unknown) and whether it's a still. */
data class ClipLimits(val sourceDurationUs: Long?, val isStill: Boolean)

/**
 * Pure editing operations on a single track's clips. Every operation returns a new list laid out
 * back to back from 0 (ripple edit: no gaps, no overlaps), so the result is always a valid timeline.
 * Operations that would change nothing return a list equal to the input.
 *
 * Playback speed is 1× everywhere for now, so a clip's timeline length equals its source range.
 */
object TimelineEditing {

    /** Shortest clip a trim can produce (shorter sources keep their full length). */
    const val MIN_CLIP_US = 500_000L

    /** Longest a still image can be stretched. */
    const val MAX_STILL_US = 60_000_000L

    /** Places clips sequentially from 0 in list order. */
    fun relayout(clips: List<Clip>): List<Clip> {
        var cursor = 0L
        return clips.map { clip ->
            val length = (clip.sourceOutUs - clip.sourceInUs).coerceAtLeast(0)
            clip.copy(timelineInUs = cursor, timelineOutUs = cursor + length).also { cursor += length }
        }
    }

    fun durationUs(clips: List<Clip>): Long = clips.lastOrNull()?.timelineOutUs ?: 0L

    /**
     * Sets a clip's source range to [newInUs]..[newOutUs], clamped to what the source allows:
     *  - video: within 0..source duration, at least [MIN_CLIP_US] long (or the whole source if shorter);
     *    with an unknown source duration the clip can be shortened but not extended past its current end;
     *  - still: starts at 0, length between [MIN_CLIP_US] and [MAX_STILL_US].
     */
    fun trim(clips: List<Clip>, clipId: String, newInUs: Long, newOutUs: Long, limits: ClipLimits): List<Clip> {
        val index = clips.indexOfFirst { it.id == clipId }
        if (index == -1) return clips
        val clip = clips[index]

        val (inUs, outUs) = if (limits.isStill) {
            0L to (newOutUs - newInUs).coerceIn(MIN_CLIP_US, MAX_STILL_US)
        } else {
            val sourceEnd = limits.sourceDurationUs?.takeIf { it > 0 } ?: clip.sourceOutUs
            if (sourceEnd <= 0) return clips
            val minLength = minOf(MIN_CLIP_US, sourceEnd)
            val start = newInUs.coerceIn(0L, sourceEnd - minLength)
            val end = newOutUs.coerceIn(start + minLength, sourceEnd)
            start to end
        }
        if (inUs == clip.sourceInUs && outUs == clip.sourceOutUs) return clips

        val updated = clips.toMutableList()
        updated[index] = clip.copy(sourceInUs = inUs, sourceOutUs = outUs)
        return relayout(updated)
    }

    /** Moves the clip at [from] to position [to] (both clamped to valid indices). */
    fun move(clips: List<Clip>, from: Int, to: Int): List<Clip> {
        if (clips.isEmpty() || from !in clips.indices) return clips
        val target = to.coerceIn(0, clips.lastIndex)
        if (target == from) return clips
        val updated = clips.toMutableList()
        updated.add(target, updated.removeAt(from))
        return relayout(updated)
    }

    /** Removes a clip from the timeline. The media asset and the source file are untouched. */
    fun delete(clips: List<Clip>, clipId: String): List<Clip> {
        if (clips.none { it.id == clipId }) return clips
        return relayout(clips.filterNot { it.id == clipId })
    }
}

/** Adds clips to the end of a track. */
fun TimelineEditing.append(clips: List<Clip>, newClips: List<Clip>): List<Clip> =
    if (newClips.isEmpty()) clips else relayout(clips + newClips)

/** Shortest piece a split may leave on either side. */
const val MIN_SPLIT_PART_US = 200_000L

/**
 * Splits clip [clipId] at timeline position [atTimelineUs] into two clips that play back exactly as
 * before. The first part keeps the incoming transition; the second part starts with a cut and takes
 * over any ending fade. Returns the input unchanged if either part would be shorter than
 * [MIN_SPLIT_PART_US] or the position is outside the clip.
 */
fun TimelineEditing.split(
    clips: List<Clip>,
    clipId: String,
    atTimelineUs: Long,
    newId: () -> String = { java.util.UUID.randomUUID().toString() },
): List<Clip> {
    val index = clips.indexOfFirst { it.id == clipId }
    if (index == -1) return clips
    val clip = clips[index]
    val offset = atTimelineUs - clip.timelineInUs
    val length = clip.timelineOutUs - clip.timelineInUs
    if (offset < MIN_SPLIT_PART_US || length - offset < MIN_SPLIT_PART_US) return clips

    val cutSource = clip.sourceInUs + offset
    val first = clip.copy(sourceOutUs = cutSource, transitionOutMs = 0)
    val second = clip.copy(
        id = newId(),
        sourceInUs = cutSource,
        transitionInType = ClipTransition.CUT.name,
        transitionInMs = 0,
    )
    val updated = clips.toMutableList()
    updated[index] = first
    updated.add(index + 1, second)
    return relayout(updated)
}
