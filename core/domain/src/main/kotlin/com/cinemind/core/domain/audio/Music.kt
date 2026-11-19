package com.cinemind.core.domain.audio

/**
 * Background music for a project. Stored as an AUDIO track with one clip (no extra tables):
 * clip.assetId = the song, clip.sourceInUs = [startOffsetUs], clip.transitionInMs/OutMs = fades,
 * track.volume = [volume]. The original sound of the videos is the VIDEO track's volume.
 */
data class MusicSettings(
    val assetId: String,
    /** Where in the song the video starts. */
    val startOffsetUs: Long = 0,
    val volume: Float = 0.8f,
    val fadeInMs: Long = 1_000,
    val fadeOutMs: Long = 1_500,
    /** Silenced without losing [volume] (the speaker button on the timeline). */
    val muted: Boolean = false,
) {
    /** The level actually played. */
    val playedVolume: Float get() = if (muted) 0f else volume
}

data class ProjectAudio(
    val music: MusicSettings?,
    /** Volume of the clips' own sound, 0..1. */
    val originalVolume: Float = 1f,
)

/** A piece of the song placed on the timeline. */
data class MusicSegment(val sourceInUs: Long, val sourceOutUs: Long, val timelineStartUs: Long) {
    val lengthUs: Long get() = sourceOutUs - sourceInUs
}

/** How music fills a video of a given length: starts at the offset, loops from the top, ends with the video. */
object MusicLayout {

    const val MIN_SEGMENT_US = 50_000L

    fun segments(musicDurationUs: Long, startOffsetUs: Long, totalUs: Long): List<MusicSegment> {
        if (musicDurationUs <= 0 || totalUs <= 0) return emptyList()
        val start = startOffsetUs.coerceIn(0, (musicDurationUs - MIN_SEGMENT_US).coerceAtLeast(0))
        val out = mutableListOf<MusicSegment>()
        var placed = 0L
        var from = start
        while (placed < totalUs) {
            val available = musicDurationUs - from
            val take = minOf(available, totalUs - placed)
            if (take < MIN_SEGMENT_US) break
            out += MusicSegment(from, from + take, placed)
            placed += take
            from = 0
        }
        return out
    }

    /** Gain 0..[volume] at timeline position [tUs]: linear fade in at the start, fade out at the end. */
    fun gainAt(tUs: Long, totalUs: Long, volume: Float, fadeInUs: Long, fadeOutUs: Long): Float {
        if (tUs < 0 || tUs >= totalUs) return 0f
        val fadeIn = if (fadeInUs > 0) (tUs.toFloat() / fadeInUs).coerceAtMost(1f) else 1f
        val fadeOut = if (fadeOutUs > 0) ((totalUs - tUs).toFloat() / fadeOutUs).coerceAtMost(1f) else 1f
        return (volume * fadeIn * fadeOut).coerceIn(0f, 1f)
    }
}
