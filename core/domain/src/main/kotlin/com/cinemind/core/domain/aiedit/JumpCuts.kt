package com.cinemind.core.domain.aiedit

import com.cinemind.core.domain.editing.ClipFraming
import com.cinemind.core.domain.editing.MotionKey
import com.cinemind.core.domain.model.Clip

/**
 * Talking-video jump cuts the way creators edit them: when the next shot comes from the same
 * recording, the framing changes (wide, then a punch-in close-up, then wide again), so a cut in
 * the middle of someone talking reads as a deliberate camera change instead of a jump.
 */
object JumpCuts {

    /** How far the close-up punches in. */
    const val PUNCH = 1.22f

    /** Clips (main track, in order) with alternating punch-ins on cuts within the same recording. */
    fun punchIns(clips: List<Clip>): List<Clip> {
        var close = false
        val ordered = clips.sortedBy { it.timelineInUs }
        return ordered.mapIndexed { i, c ->
            val prev = ordered.getOrNull(i - 1)
            close = if (prev != null && prev.assetId == c.assetId) !close else false
            val f = ClipFraming.decode(c.framing)
            // Clips the user (or the planner) already framed keep their framing.
            if (!close || f.keys.isNotEmpty()) return@mapIndexed c
            // A little above centre: faces sit in the upper half of a talking shot.
            val key = MotionKey(sourceUs = c.sourceInUs, x = 0f, y = 0.06f, scale = PUNCH)
            c.copy(framing = f.copy(keys = listOf(key)).encode())
        }
    }
}
