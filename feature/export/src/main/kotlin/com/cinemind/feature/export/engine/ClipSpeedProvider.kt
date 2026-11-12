package com.cinemind.feature.export.engine

import androidx.media3.common.C
import androidx.media3.common.audio.SpeedProvider
import com.cinemind.core.domain.editing.SpeedMath
import com.cinemind.core.domain.editing.SpeedSegment
import com.cinemind.core.domain.model.Clip

/**
 * A clip's speed for Media3 (preview and export). Times are offsets in the clipped media, which is
 * what [SpeedMath.segments] returns, so Media3's resulting length equals the timeline length.
 */
internal class ClipSpeedProvider(private val segments: List<SpeedSegment>) : SpeedProvider {

    override fun getSpeed(timeUs: Long): Float =
        (segments.lastOrNull { timeUs >= it.startUs } ?: segments.first()).speed

    override fun getNextSpeedChangeTimeUs(timeUs: Long): Long =
        segments.firstOrNull { it.startUs > timeUs }?.startUs ?: C.TIME_UNSET

    companion object {
        /** null when the clip plays at normal speed. */
        fun forClip(clip: Clip): SpeedProvider? =
            if (!SpeedMath.isChanged(clip)) null
            else SpeedMath.segments(clip).takeIf { it.isNotEmpty() }?.let { ClipSpeedProvider(it) }
    }
}
