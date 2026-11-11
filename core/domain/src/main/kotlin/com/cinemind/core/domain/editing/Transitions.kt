package com.cinemind.core.domain.editing

import com.cinemind.core.domain.model.Clip

/**
 * Transitions between consecutive clips. The timeline has no overlapping clips (yet), so each
 * transition is split in two halves: the outgoing clip animates *out* during its last half, the
 * incoming clip animates *in* during its first half. FADE is a dip through black.
 * True overlapping cross-dissolves need multi-track compositing (planned for Stage 3).
 */
enum class ClipTransition(val label: String) {
    CUT("Cut"),
    FADE("Fade"),
    ZOOM_IN("Zoom in"),
    ZOOM_OUT("Zoom out"),
    SLIDE_LEFT("Slide left"),
    SLIDE_RIGHT("Slide right"),
    SLIDE_UP("Slide up"),
    ;

    companion object {
        fun fromName(name: String?): ClipTransition = entries.firstOrNull { it.name == name } ?: CUT
    }
}

/**
 * How a frame should be drawn at a moment in time.
 * [translateX]/[translateY] are fractions of the frame size (+x = right, +y = down);
 * [brightness] 1 = normal, 0 = black.
 */
data class FrameLook(
    val scale: Float = 1f,
    val translateX: Float = 0f,
    val translateY: Float = 0f,
    val brightness: Float = 1f,
) {
    val isIdentity: Boolean get() = scale == 1f && translateX == 0f && translateY == 0f && brightness == 1f

    companion object {
        val IDENTITY = FrameLook()
    }
}

/** The animation a clip plays at its start ([inType] over [inUs]) and end ([outType] over [outUs]). */
data class ClipAnimation(
    val inType: ClipTransition,
    val inUs: Long,
    val outType: ClipTransition,
    val outUs: Long,
) {
    val isNone: Boolean get() = (inType == ClipTransition.CUT || inUs <= 0) && (outType == ClipTransition.CUT || outUs <= 0)
}

object TransitionMath {

    const val MIN_TRANSITION_MS = 200L
    const val MAX_TRANSITION_MS = 2_000L
    val DURATION_OPTIONS_MS = listOf(300L, 500L, 800L, 1_200L)

    private const val ZOOM_AMOUNT = 0.3f
    private const val ZOOM_OUT_AMOUNT = 0.15f

    /**
     * The in/out animation for each clip of a laid-out track.
     * - clip i animates in over half of its own transition (the full length for the opening clip);
     * - clip i animates out over half of clip i+1's transition (the last clip: its closing fade).
     * Each half is capped at half the clip's length so in and out never collide.
     */
    fun animations(clips: List<Clip>): List<ClipAnimation> = clips.mapIndexed { i, clip ->
        val length = clip.timelineOutUs - clip.timelineInUs
        val cap = (length / 2).coerceAtLeast(0)
        val inType = ClipTransition.fromName(clip.transitionInType)
        val inUs = if (inType == ClipTransition.CUT) 0 else {
            val full = clip.transitionInMs * 1_000
            (if (i == 0) full else full / 2).coerceAtMost(cap)
        }
        val next = clips.getOrNull(i + 1)
        val (outType, outUs) = if (next != null) {
            val t = ClipTransition.fromName(next.transitionInType)
            t to if (t == ClipTransition.CUT) 0L else (next.transitionInMs * 1_000 / 2).coerceAtMost(cap)
        } else {
            val fade = clip.transitionOutMs * 1_000
            if (fade > 0) ClipTransition.FADE to fade.coerceAtMost(cap) else ClipTransition.CUT to 0L
        }
        ClipAnimation(inType, inUs, outType, outUs)
    }

    /** The look of a clip [localUs] after its start, given its length and animation. */
    fun lookAt(localUs: Long, lengthUs: Long, anim: ClipAnimation): FrameLook {
        if (anim.isNone || lengthUs <= 0) return FrameLook.IDENTITY
        var look = FrameLook.IDENTITY
        if (anim.inUs > 0 && localUs < anim.inUs) {
            val p = ease(localUs.toFloat() / anim.inUs)
            look = combine(look, arriving(anim.inType, p))
        }
        val outStart = lengthUs - anim.outUs
        if (anim.outUs > 0 && localUs >= outStart) {
            val q = ease(((localUs - outStart).toFloat() / anim.outUs).coerceIn(0f, 1f))
            look = combine(look, leaving(anim.outType, q))
        }
        return look
    }

    /** [p] goes 0 → 1 as the clip finishes arriving. */
    private fun arriving(type: ClipTransition, p: Float): FrameLook = when (type) {
        ClipTransition.CUT -> FrameLook.IDENTITY
        ClipTransition.FADE -> FrameLook(brightness = p)
        ClipTransition.ZOOM_IN -> FrameLook(scale = 1f + ZOOM_AMOUNT * (1f - p))
        ClipTransition.ZOOM_OUT -> FrameLook(scale = 1f - ZOOM_OUT_AMOUNT * (1f - p), brightness = p)
        ClipTransition.SLIDE_LEFT -> FrameLook(translateX = 1f - p)
        ClipTransition.SLIDE_RIGHT -> FrameLook(translateX = -(1f - p))
        ClipTransition.SLIDE_UP -> FrameLook(translateY = 1f - p)
    }

    /** [q] goes 0 → 1 as the clip finishes leaving. */
    private fun leaving(type: ClipTransition, q: Float): FrameLook = when (type) {
        ClipTransition.CUT -> FrameLook.IDENTITY
        ClipTransition.FADE -> FrameLook(brightness = 1f - q)
        ClipTransition.ZOOM_IN -> FrameLook(scale = 1f + ZOOM_AMOUNT * q)
        ClipTransition.ZOOM_OUT -> FrameLook(scale = 1f - ZOOM_OUT_AMOUNT * q, brightness = 1f - q)
        ClipTransition.SLIDE_LEFT -> FrameLook(translateX = -q)
        ClipTransition.SLIDE_RIGHT -> FrameLook(translateX = q)
        ClipTransition.SLIDE_UP -> FrameLook(translateY = -q)
    }

    private fun combine(a: FrameLook, b: FrameLook) = FrameLook(
        scale = a.scale * b.scale,
        translateX = a.translateX + b.translateX,
        translateY = a.translateY + b.translateY,
        brightness = a.brightness * b.brightness,
    )

    /** Smoothstep easing: gentle start and end. */
    private fun ease(t: Float): Float {
        val x = t.coerceIn(0f, 1f)
        return x * x * (3f - 2f * x)
    }

    /** Longest transition allowed between two clips of the given lengths. */
    fun maxTransitionMs(previousLengthUs: Long, nextLengthUs: Long): Long =
        (minOf(previousLengthUs, nextLengthUs) / 1_000).coerceIn(0, MAX_TRANSITION_MS)
}

/** Transition edits on a track (pure; return the input when nothing changes). */
object TransitionEditing {

    /** Sets the transition into clip [clipId]. CUT clears it; durations are clamped to what both clips allow. */
    fun setTransition(clips: List<Clip>, clipId: String, type: ClipTransition, durationMs: Long): List<Clip> {
        val index = clips.indexOfFirst { it.id == clipId }
        if (index == -1) return clips
        val updated = clips.toMutableList()
        updated[index] = withTransition(clips, index, type, durationMs)
        return if (updated == clips) clips else updated
    }

    /** Applies the same transition to every boundary between clips (not the opening). */
    fun setAll(clips: List<Clip>, type: ClipTransition, durationMs: Long): List<Clip> {
        val updated = clips.mapIndexed { i, c -> if (i == 0) c else withTransition(clips, i, type, durationMs) }
        return if (updated == clips) clips else updated
    }

    /** Fade from black at the start ([openingMs]) and to black at the end ([endingMs]); 0 = none. */
    fun setOpeningAndEnding(clips: List<Clip>, openingMs: Long, endingMs: Long): List<Clip> {
        if (clips.isEmpty()) return clips
        val updated = clips.toMutableList()
        val first = updated.first()
        val firstLenMs = (first.timelineOutUs - first.timelineInUs) / 1_000
        updated[0] = if (openingMs <= 0) {
            first.copy(transitionInType = ClipTransition.CUT.name, transitionInMs = 0)
        } else {
            first.copy(transitionInType = ClipTransition.FADE.name, transitionInMs = openingMs.coerceAtMost(firstLenMs / 2))
        }
        val last = updated.last()
        val lastLenMs = (last.timelineOutUs - last.timelineInUs) / 1_000
        updated[updated.lastIndex] = last.copy(transitionOutMs = endingMs.coerceIn(0, lastLenMs / 2))
        return if (updated == clips) clips else updated
    }

    private fun withTransition(clips: List<Clip>, index: Int, type: ClipTransition, durationMs: Long): Clip {
        val clip = clips[index]
        if (type == ClipTransition.CUT) return clip.copy(transitionInType = type.name, transitionInMs = 0)
        val thisLen = clip.timelineOutUs - clip.timelineInUs
        val max = if (index == 0) {
            (thisLen / 2 / 1_000).coerceAtMost(TransitionMath.MAX_TRANSITION_MS)
        } else {
            val prev = clips[index - 1]
            TransitionMath.maxTransitionMs(prev.timelineOutUs - prev.timelineInUs, thisLen)
        }
        val duration = durationMs.coerceIn(minOf(TransitionMath.MIN_TRANSITION_MS, max), max)
        return clip.copy(transitionInType = type.name, transitionInMs = duration)
    }
}
