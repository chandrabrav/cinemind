package com.cinemind.core.domain.aiedit

import com.cinemind.core.domain.agents.Aspect
import com.cinemind.core.domain.agents.Goal
import com.cinemind.core.domain.agents.RequestIntent
import com.cinemind.core.domain.editing.ClipTransition
import com.cinemind.core.domain.model.EditBrief
import com.cinemind.core.domain.model.EditBriefRules
import com.cinemind.core.domain.model.EditStyle
import com.cinemind.core.domain.model.Pacing
import java.util.Locale
import kotlin.math.roundToInt

/**
 * A follow-up instruction on an AI edit, as structured changes. Only what the user asked for
 * changes; everything they said to keep (music, look, …) is kept until they say otherwise.
 */
data class Revision(
    val intent: RequestIntent,
    /** The brief for the new version (the old one with the requested changes). */
    val brief: EditBrief,
    /** Constraints applied to whatever plan comes back. */
    val noSpeedEffects: Boolean = false,
    val transition: ClipTransition? = null,
    val transitionSeconds: Double? = null,
    val dropCaptions: Boolean = false,
    /** What the app understood, in plain words (shown before and after the revision). */
    val understood: List<String>,
)

object RevisionReader {

    /**
     * Reads [text] against the current [brief] and the current edit's length. Unrecognised
     * wording still counts: it is added to the brief for the planner, and the shots are re-planned.
     */
    fun read(text: String, brief: EditBrief, currentSeconds: Double): Revision {
        val s = text.lowercase(Locale.ROOT).trim()
        val change = linkedSetOf<Aspect>()
        val keep = linkedSetOf<Aspect>()
        val said = mutableListOf<String>()
        var b = brief
        var target: Int? = null
        var noSpeed = false
        var transition: ClipTransition? = null
        var transitionSeconds: Double? = null
        var dropCaptions = false
        var creative = false

        // Length.
        Regex("(\\d{1,3})\\s*(s|sec|secs|second|seconds)\\b").find(s)?.let { target = it.groupValues[1].toInt() }
        Regex("(\\d{1,2})\\s*(m|min|mins|minute|minutes)\\b").find(s)?.let { target = it.groupValues[1].toInt() * 60 }
        if (target == null && currentSeconds > 0) {
            if (has(s, "shorter", "cut it down", "trim it", "tighter")) target = (currentSeconds * 0.7).roundToInt().coerceAtLeast(5)
            if (has(s, "longer", "extend")) target = (currentSeconds * 1.3).roundToInt()
        }
        target?.let { t ->
            change += Aspect.DURATION
            b = b.copy(targetDurationSec = t.coerceIn(5, 600))
            said += "Length ${b.targetDurationSec} s"
        }

        // Keep / change music.
        if (Regex("keep (the )?(same )?(music|song|track|audio)").containsMatchIn(s) || has(s, "same music", "same song")) {
            keep += Aspect.MUSIC
            said += "Keeping the music"
        } else if (has(s, "different music", "change the music", "new music", "another song", "different song")) {
            change += Aspect.MUSIC
            said += "New music direction"
        }

        // Style and look.
        if (has(s, "cinematic", "film look", "movie")) { b = b.copy(style = EditStyle.CINEMATIC); change += Aspect.LOOK; creative = true; said += "More cinematic" }
        if (has(s, "warmer", "warm look")) { change += Aspect.LOOK; said += "Warmer colours" }
        if (has(s, "cooler", "colder")) { change += Aspect.LOOK; said += "Cooler colours" }
        if (has(s, "brighter")) { change += Aspect.LOOK; said += "Brighter" }
        if (has(s, "darker", "moodier", "moody")) { change += Aspect.LOOK; said += "Moodier" }
        if (Regex("keep (the )?(same )?(look|colou?rs?|grade)").containsMatchIn(s)) { keep += Aspect.LOOK; change -= Aspect.LOOK; said += "Keeping the look" }

        // Pacing.
        if (has(s, "faster", "more energetic", "punchier", "snappier", "quicker")) { b = b.copy(pacing = Pacing.FAST); change += Aspect.PACING; said += "Faster pacing" }
        if (has(s, "slower", "calmer", "more relaxed", "slow it down")) { b = b.copy(pacing = Pacing.CALM); change += Aspect.PACING; said += "Calmer pacing" }

        // Transitions.
        if (has(s, "smoother transitions", "smooth transitions", "softer transitions", "crossfade", "dissolve")) {
            transition = ClipTransition.DISSOLVE; transitionSeconds = 0.6; change += Aspect.TRANSITIONS; said += "Smoother transitions"
        }
        if (has(s, "no transitions", "hard cuts", "just cuts", "straight cuts")) {
            transition = ClipTransition.CUT; transitionSeconds = 0.0; change += Aspect.TRANSITIONS; said += "Straight cuts"
        }

        // Speed effects.
        if (has(s, "slow motion", "slow-motion", "slowmo", "slow mo", "speed ramp", "ramps") && has(s, "remove", "no ", "without", "drop", "get rid", "less")) {
            noSpeed = true; change += Aspect.SPEED; said += "No slow motion or speed ramps"
        }

        // Captions and titles.
        if (has(s, "captions", "subtitles") && has(s, "remove", "no ", "without", "drop", "hide")) {
            dropCaptions = true; change += Aspect.CAPTIONS; said += "No captions"
        }
        if (has(s, "title", "text") && has(s, "remove", "no ", "without", "change", "different", "bigger")) { change += Aspect.TITLES; said += "Titles changed" }

        // Shots.
        if (has(s, "different shots", "other shots", "best shots", "better shots", "more of", "less of", "fewer", "only the")) {
            change += Aspect.SHOTS; said += "Different shot choice"
        }

        // Anything else the user wrote is passed to the planner as an extra instruction.
        if (change.isEmpty() && keep.isEmpty()) {
            change += setOf(Aspect.SHOTS, Aspect.PACING)
            said += "Re-planned with your note"
        }
        val note = text.trim().take(400)
        if (note.isNotEmpty()) b = b.copy(prompt = EditBriefRules.clampPrompt(listOf(b.prompt.trim(), "Revision: $note").filter { it.isNotEmpty() }.joinToString("\n")))

        return Revision(
            intent = RequestIntent(goals = setOf(Goal.REVISE_EDIT), creative = creative, change = change, keep = keep, targetSeconds = target),
            brief = b,
            noSpeedEffects = noSpeed,
            transition = transition,
            transitionSeconds = transitionSeconds,
            dropCaptions = dropCaptions,
            understood = said,
        )
    }

    /** Applies the revision's hard constraints to whatever plan the planner produced. */
    fun constrain(plan: EditPlan, r: Revision): EditPlan {
        var p = plan
        if (r.noSpeedEffects) p = p.copy(shots = p.shots.map { it.copy(speed = 1.0, ramp = "") })
        r.transition?.let { t ->
            p = p.copy(shots = p.shots.mapIndexed { i, s ->
                if (i == 0) s else s.copy(transitionIn = t.name, transitionSeconds = r.transitionSeconds ?: s.transitionSeconds)
            })
        }
        if (r.dropCaptions) p = p.copy(captionPreset = "")
        return p
    }

    private fun has(s: String, vararg words: String): Boolean = words.any { s.contains(it) }
}
