package com.cinemind.core.domain.editing

import com.cinemind.core.domain.model.Clip
import java.util.Locale
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToLong

/** How speed changes between two ramp points. */
enum class SpeedEase(val label: String) { LINEAR("Linear"), EASE_IN("Ease in"), EASE_OUT("Ease out"), EASE_IN_OUT("Smooth") }

/**
 * One speed keyframe. [sourceUs] is a time in the **source video** (not the timeline), so a ramp
 * stays on the same moment of the footage when the clip is trimmed or split. [ease] shapes the
 * change from this point to the next.
 */
data class SpeedPoint(val sourceUs: Long, val speed: Float, val ease: SpeedEase = SpeedEase.EASE_IN_OUT)

/** A piece of the clip played at one constant speed (offsets from the clip's source in-point). */
data class SpeedSegment(val startUs: Long, val endUs: Long, val speed: Float)

/**
 * Speed of a clip: constant ([Clip.speed]) or a ramp of keyframes ([Clip.speedCurve], schema v7).
 * Ramps are played as many short constant-speed steps — exactly what Media3's SpeedProvider does —
 * so the timeline length computed here equals the exported length. Pure; unit-tested.
 */
object SpeedMath {

    const val MIN_SPEED = 0.1f
    const val MAX_SPEED = 8f
    /** Length of one step inside a ramp. */
    const val STEP_US = 40_000L

    val PRESETS = listOf(0.25f, 0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f, 3f, 4f)

    fun clampSpeed(s: Float) = s.coerceIn(MIN_SPEED, MAX_SPEED)

    // ------------------------------------------------------------------ encoding

    fun encode(points: List<SpeedPoint>): String =
        points.sortedBy { it.sourceUs }.joinToString(";") { String.format(Locale.ROOT, "%d:%.3f:%s", it.sourceUs, it.speed, it.ease.name) }

    fun decode(text: String?): List<SpeedPoint> {
        if (text.isNullOrBlank()) return emptyList()
        return text.split(';').mapNotNull { p ->
            val parts = p.split(':')
            val t = parts.getOrNull(0)?.toLongOrNull() ?: return@mapNotNull null
            val s = parts.getOrNull(1)?.toFloatOrNull() ?: return@mapNotNull null
            val e = SpeedEase.entries.firstOrNull { it.name == parts.getOrNull(2) } ?: SpeedEase.EASE_IN_OUT
            SpeedPoint(t, clampSpeed(s), e)
        }.sortedBy { it.sourceUs }
    }

    // ------------------------------------------------------------------ evaluation

    private fun ease(t: Float, e: SpeedEase): Float = when (e) {
        SpeedEase.LINEAR -> t
        SpeedEase.EASE_IN -> t * t
        SpeedEase.EASE_OUT -> 1f - (1f - t) * (1f - t)
        SpeedEase.EASE_IN_OUT -> (0.5f - 0.5f * cos(PI * t).toFloat())
    }

    /** Speed at absolute source time [sourceUs] for a ramp (held flat before the first / after the last point). */
    fun speedAt(points: List<SpeedPoint>, sourceUs: Long): Float {
        if (points.isEmpty()) return 1f
        if (sourceUs <= points.first().sourceUs) return points.first().speed
        if (sourceUs >= points.last().sourceUs) return points.last().speed
        val i = points.indexOfLast { it.sourceUs <= sourceUs }
        val a = points[i]
        val b = points[i + 1]
        val span = (b.sourceUs - a.sourceUs).coerceAtLeast(1)
        val t = ((sourceUs - a.sourceUs).toFloat() / span).coerceIn(0f, 1f)
        return a.speed + (b.speed - a.speed) * ease(t, a.ease)
    }

    /**
     * The clip as constant-speed pieces, offsets from its source in-point. Constant clips are one
     * piece; ramps are [STEP_US] steps (on a grid anchored to the source, so splits keep the exact
     * same steps), each at the speed of its midpoint.
     */
    fun segments(clip: Clip): List<SpeedSegment> {
        val len = (clip.sourceOutUs - clip.sourceInUs).coerceAtLeast(0)
        if (len == 0L) return emptyList()
        val points = decode(clip.speedCurve)
        if (points.size < 2) {
            val s = clampSpeed(points.firstOrNull()?.speed ?: clip.speed)
            return listOf(SpeedSegment(0, len, s))
        }
        val out = ArrayList<SpeedSegment>()
        var t = clip.sourceInUs
        while (t < clip.sourceOutUs) {
            val next = minOf((t / STEP_US + 1) * STEP_US, clip.sourceOutUs)
            val s = clampSpeed(speedAt(points, (t + next) / 2))
            val last = out.lastOrNull()
            if (last != null && last.speed == s) {
                out[out.lastIndex] = last.copy(endUs = next - clip.sourceInUs)
            } else {
                out += SpeedSegment(t - clip.sourceInUs, next - clip.sourceInUs, s)
            }
            t = next
        }
        return out
    }

    /** How long the clip plays on the timeline. */
    fun timelineDurationUs(clip: Clip): Long =
        segments(clip).sumOf { ((it.endUs - it.startUs) / it.speed.toDouble()).roundToLong() }

    /** Timeline offset inside the clip → source offset (both from the clip's start / in-point). */
    fun timelineToSource(clip: Clip, timelineOffsetUs: Long): Long {
        var tl = 0L
        for (s in segments(clip)) {
            val segTl = ((s.endUs - s.startUs) / s.speed.toDouble()).roundToLong()
            if (timelineOffsetUs <= tl + segTl) {
                return s.startUs + ((timelineOffsetUs - tl) * s.speed.toDouble()).roundToLong().coerceAtMost(s.endUs - s.startUs)
            }
            tl += segTl
        }
        return clip.sourceOutUs - clip.sourceInUs
    }

    /** Source offset → timeline offset (inverse of [timelineToSource]). */
    fun sourceToTimeline(clip: Clip, sourceOffsetUs: Long): Long {
        var tl = 0L
        for (s in segments(clip)) {
            if (sourceOffsetUs <= s.endUs) return tl + ((sourceOffsetUs - s.startUs).coerceAtLeast(0) / s.speed.toDouble()).roundToLong()
            tl += ((s.endUs - s.startUs) / s.speed.toDouble()).roundToLong()
        }
        return tl
    }

    /** Speed at the clip's first or last frame (turns a trim-handle drag on the timeline into source time). */
    fun edgeSpeed(clip: Clip, atStart: Boolean): Float =
        segments(clip).let { if (atStart) it.firstOrNull() else it.lastOrNull() }?.speed ?: clampSpeed(clip.speed)

    fun isChanged(clip: Clip): Boolean = clip.speed != 1f || decode(clip.speedCurve).size >= 2

    /** Slowest speed anywhere in the clip (for the slow-motion smoothness warning). */
    fun slowest(clip: Clip): Float = segments(clip).minOfOrNull { it.speed } ?: 1f

    /**
     * Below this speed a clip shows repeated frames at the project's frame rate: a 30 fps video
     * slowed to 0.5× in a 30 fps project repeats every frame. (CineMind doesn't invent frames.)
     */
    fun smoothSlowestSpeed(sourceFps: Float, projectFps: Int): Float =
        if (sourceFps <= 0f) 1f else (projectFps / sourceFps).coerceIn(0.05f, 1f)
}

/** Ready-made ramps; positions are fractions of the clip's source range. All remain editable. */
enum class SpeedRampPreset(val label: String, val shape: List<Pair<Float, Float>>) {
    CINEMATIC("Cinematic slow", listOf(0f to 1f, 0.35f to 0.5f, 0.65f to 0.5f, 1f to 1f)),
    HERO("Hero moment", listOf(0f to 1.5f, 0.42f to 0.3f, 0.58f to 0.3f, 1f to 1.5f)),
    MONTAGE("Montage", listOf(0f to 2.5f, 0.5f to 1.5f, 1f to 2.5f)),
    SMOOTH_SLOW("Smooth slow-mo", listOf(0f to 0.6f, 1f to 0.6f)),
    FAST_IN_SLOW_OUT("Fast in, slow out", listOf(0f to 2.5f, 0.6f to 1f, 1f to 0.4f)),
    SLOW_IN_FAST_OUT("Slow in, fast out", listOf(0f to 0.4f, 0.4f to 1f, 1f to 2.5f)),
    VELOCITY("Velocity", listOf(0f to 1f, 0.25f to 3f, 0.45f to 0.3f, 0.6f to 0.3f, 0.8f to 3f, 1f to 1f)),
    ;

    fun pointsFor(clip: Clip): List<SpeedPoint> {
        val len = clip.sourceOutUs - clip.sourceInUs
        return shape.map { (f, s) -> SpeedPoint(clip.sourceInUs + (len * f).roundToLong(), s) }
    }
}
