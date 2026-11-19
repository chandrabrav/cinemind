package com.cinemind.feature.ai.generation.smartedit

import com.cinemind.core.domain.editing.ClipTransition
import com.cinemind.core.domain.model.EditBrief
import com.cinemind.core.domain.model.EditStyle
import com.cinemind.core.domain.model.Pacing

enum class ShotOrder { CHRONOLOGICAL, HOOK_FIRST }

/** The concrete editing choices derived from a brief. */
data class EditIntent(
    val style: EditStyle,
    val pacing: Pacing,
    /** Desired length; null = decide from the footage. */
    val targetUs: Long?,
    val order: ShotOrder,
    /** Favour sharp, well-exposed shots more strongly and drop weak ones. */
    val strictQuality: Boolean,
    val transition: ClipTransition,
    val transitionMs: Long,
    val openingMs: Long,
    val endingMs: Long,
    /** Typical shot length before trimming the last shot to the target. */
    val shotUs: Long,
)

/**
 * Reads the brief: explicit choices (style, pacing, length) win; otherwise keywords in the
 * free-text description fill them in. Deterministic and offline — a rule-based stand-in for the
 * on-device language model planned for Stage 4, producing the same structured intent.
 */
object BriefInterpreter {

    private val STYLE_WORDS = listOf(
        EditStyle.CINEMATIC to listOf("cinematic", "film", "movie", "dreamy", "epic"),
        EditStyle.GYM to listOf("gym", "workout", "fitness", "energetic", "hype", "sport", "training"),
        EditStyle.TRAVEL to listOf("travel", "trip", "vacation", "holiday", "journey", "beach", "mountain", "explore"),
        EditStyle.VLOG to listOf("vlog", "day in", "talking"),
        EditStyle.LIFESTYLE to listOf("lifestyle", "aesthetic", "cozy", "cosy", "calm", "minimal"),
    )
    private val SLOW_WORDS = listOf("slow", "dreamy", "calm", "relaxed", "gentle", "chill")
    private val FAST_WORDS = listOf("fast", "energetic", "quick", "hype", "beat", "punchy", "rapid")
    private val STRICT_WORDS = listOf("shaky", "blurry", "best moment", "best shot", "only the best", "highlight")
    private val STORY_WORDS = listOf("story", "morning to night", "chronolog", "in order")
    private val HOOK_WORDS = listOf("hook", "start strong", "best first", "open with the best")

    private val SECONDS = Regex("""(\d{1,3})\s*(s|sec|secs|second|seconds)\b""")
    private val MINUTES = Regex("""(\d{1,2})\s*(m|min|mins|minute|minutes)\b""")

    fun interpret(brief: EditBrief): EditIntent {
        val text = brief.prompt.lowercase()
        fun has(words: List<String>) = words.any { it in text }

        val style = if (brief.style != EditStyle.AUTO) {
            brief.style
        } else {
            STYLE_WORDS.firstOrNull { (_, words) -> has(words) }?.first ?: EditStyle.AUTO
        }

        val pacing = when {
            brief.pacing != Pacing.BALANCED -> brief.pacing
            has(FAST_WORDS) -> Pacing.FAST
            has(SLOW_WORDS) -> Pacing.CALM
            style == EditStyle.GYM -> Pacing.FAST
            else -> Pacing.BALANCED
        }

        val targetUs = brief.targetDurationSec?.let { it * 1_000_000L } ?: parseDurationUs(text)

        val order = when {
            has(STORY_WORDS) -> ShotOrder.CHRONOLOGICAL
            has(HOOK_WORDS) -> ShotOrder.HOOK_FIRST
            style == EditStyle.GYM -> ShotOrder.HOOK_FIRST
            else -> ShotOrder.CHRONOLOGICAL
        }

        var (transition, transitionMs, openingMs, endingMs) = styleTransitions(style)
        when {
            listOf("no transition", "hard cut", "only cuts", "just cuts").any { it in text } -> transition = ClipTransition.CUT
            "fade to black" in text || "dip to black" in text -> { transition = ClipTransition.FADE; transitionMs = maxOf(transitionMs, 500L) }
            "fade" in text || "dissolve" in text -> { transition = ClipTransition.DISSOLVE; transitionMs = maxOf(transitionMs, 500L) }
            "zoom" in text -> { transition = ClipTransition.ZOOM_IN; transitionMs = 400 }
            "slide" in text || "swipe" in text -> { transition = ClipTransition.SLIDE_LEFT; transitionMs = 500 }
        }

        return EditIntent(
            style = style,
            pacing = pacing,
            targetUs = targetUs,
            order = order,
            strictQuality = has(STRICT_WORDS),
            transition = transition,
            transitionMs = if (transition == ClipTransition.CUT) 0 else transitionMs,
            openingMs = openingMs,
            endingMs = endingMs,
            shotUs = shotLengthUs(style, pacing),
        )
    }

    /** "30 seconds", "45s", "1 minute" → microseconds (5 s .. 10 min), or null. */
    fun parseDurationUs(text: String): Long? {
        MINUTES.find(text)?.let { m ->
            val v = m.groupValues[1].toLong()
            if (v in 1..10) return v * 60_000_000L
        }
        SECONDS.find(text)?.let { m ->
            val v = m.groupValues[1].toLong()
            if (v in 5..600) return v * 1_000_000L
        }
        return null
    }

    fun shotLengthUs(style: EditStyle, pacing: Pacing): Long {
        val base = when (pacing) {
            Pacing.CALM -> 3_500_000.0
            Pacing.BALANCED -> 2_200_000.0
            Pacing.FAST -> 1_200_000.0
        }
        val mult = when (style) {
            EditStyle.CINEMATIC -> 1.25
            EditStyle.GYM -> 0.75
            EditStyle.VLOG -> 1.4
            EditStyle.LIFESTYLE -> 1.1
            EditStyle.TRAVEL, EditStyle.AUTO -> 1.0
        }
        return (base * mult).toLong()
    }

    private data class StyleTransitions(val type: ClipTransition, val ms: Long, val openingMs: Long, val endingMs: Long)

    private fun styleTransitions(style: EditStyle): StyleTransitions = when (style) {
        EditStyle.CINEMATIC -> StyleTransitions(ClipTransition.DISSOLVE, 600, 400, 1_000)
        // Modern travel reels: hard cuts with a quick zoom punch where the place changes (slides showed black).
        EditStyle.TRAVEL -> StyleTransitions(ClipTransition.ZOOM_IN, 300, 300, 800)
        EditStyle.GYM -> StyleTransitions(ClipTransition.ZOOM_IN, 300, 0, 400)
        EditStyle.LIFESTYLE -> StyleTransitions(ClipTransition.DISSOLVE, 400, 300, 800)
        EditStyle.VLOG -> StyleTransitions(ClipTransition.CUT, 0, 0, 500)
        EditStyle.AUTO -> StyleTransitions(ClipTransition.DISSOLVE, 400, 300, 800)
    }
}
