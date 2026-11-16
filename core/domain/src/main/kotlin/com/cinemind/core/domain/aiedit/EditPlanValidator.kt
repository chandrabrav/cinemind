package com.cinemind.core.domain.aiedit

import com.cinemind.core.domain.audio.SoundEffect
import com.cinemind.core.domain.captions.CaptionPresets
import com.cinemind.core.domain.color.BuiltInLooks
import com.cinemind.core.domain.editing.ClipTransition
import com.cinemind.core.domain.editing.SpeedRampPreset
import com.cinemind.core.domain.text.TextAnim
import com.cinemind.core.domain.text.TextPreset
import java.util.Locale

/** A plan after validation: every value is one the editing engine supports. */
data class ValidatedPlan(val plan: EditPlan, val steps: List<PlanStep>) {
    val usable: Boolean get() = plan.shots.isNotEmpty()
}

/**
 * Checks a plan against the project and the engine's capabilities before anything is changed:
 * unknown sources are dropped, ranges are clamped to the footage, unknown names (looks,
 * transitions, presets, sounds) are removed, colour moves are kept within safe limits, and text
 * that states a fact the user never gave (a place, a number, a name) is not added — a question
 * is asked instead. Every change is reported as a [PlanStep], so nothing is silently altered.
 */
object EditPlanValidator {

    const val MIN_SHOT_S = 0.6
    const val MAX_SHOTS = 80
    const val MAX_TEXTS = 12
    const val MAX_SOUNDS = 10
    private const val STILL_MIN_S = 0.8
    private const val STILL_MAX_S = 6.0

    /**
     * @param ground what the user said or wrote (brief, project name, caption text): facts in
     *   on-screen text must come from here.
     * @param lutIds looks the user imported (ids as stored in a grade), besides the built-in ones.
     */
    fun validate(
        plan: EditPlan,
        sources: List<PlanSource>,
        ground: String,
        lutIds: Set<String> = emptySet(),
        hasCaptions: Boolean = false,
    ): ValidatedPlan {
        val steps = mutableListOf<PlanStep>()
        fun note(area: String, status: StepStatus, text: String) { steps += PlanStep(area, status, text) }

        if (plan.version > EDIT_PLAN_VERSION) note("Plan", StepStatus.ADJUSTED, "Plan format ${plan.version} is newer than this app; read what it understands")

        // ---- shots
        val shots = mutableListOf<PlanShot>()
        plan.shots.take(MAX_SHOTS).forEachIndexed { i, s ->
            val n = i + 1
            val src = sources.getOrNull(s.source - 1)
            if (src == null) { note("Shots", StepStatus.SKIPPED, "Shot $n used source ${s.source}, which doesn't exist"); return@forEachIndexed }
            var from = s.fromSeconds.finite(0.0)
            var to = s.toSeconds.finite(0.0)
            if (src.isVideo) {
                val dur = src.durationUs / 1e6
                val f0 = from; val t0 = to
                from = from.coerceIn(0.0, dur)
                to = to.coerceIn(0.0, dur)
                if (to - from < MIN_SHOT_S) {
                    note("Shots", StepStatus.SKIPPED, "Shot $n was too short or outside the footage (${fmt(f0)}–${fmt(t0)} s of a ${fmt(dur)} s clip)")
                    return@forEachIndexed
                }
                if (from != f0 || to != t0) note("Shots", StepStatus.ADJUSTED, "Shot $n trimmed to the footage (${fmt(from)}–${fmt(to)} s)")
            } else {
                val len = (to - from).coerceIn(STILL_MIN_S, STILL_MAX_S)
                from = 0.0; to = len
            }
            val speed = s.speed.finite(1.0).coerceIn(0.25, 4.0)
            if (speed != s.speed) note("Shots", StepStatus.ADJUSTED, "Shot $n speed kept within 0.25–4×")
            val ramp = s.ramp.trim().uppercase(Locale.ROOT).takeIf { it.isNotEmpty() }
            val rampOk = ramp == null || SpeedRampPreset.entries.any { it.name == ramp }
            if (!rampOk) note("Shots", StepStatus.ADJUSTED, "Shot $n: unknown speed ramp “${s.ramp}” left out")
            val trName = s.transitionIn.trim().uppercase(Locale.ROOT)
            val transition = ClipTransition.entries.firstOrNull { it.name == trName }
            if (transition == null && trName.isNotEmpty()) note("Shots", StepStatus.ADJUSTED, "Shot $n: unknown transition “${s.transitionIn}”, used a cut")
            shots += s.copy(
                fromSeconds = from,
                toSeconds = to,
                speed = speed,
                ramp = if (rampOk && ramp != null && src.isVideo) ramp else "",
                transitionIn = (transition ?: ClipTransition.CUT).name,
                transitionSeconds = if (transition == null || transition == ClipTransition.CUT) 0.0 else s.transitionSeconds.finite(0.5).coerceIn(0.2, 1.5),
                exposure = s.exposure.finite(0.0).coerceIn(-1.0, 1.0),
                note = s.note.take(120),
            )
        }
        if (plan.shots.size > MAX_SHOTS) note("Shots", StepStatus.ADJUSTED, "Used the first $MAX_SHOTS shots")
        if (shots.isEmpty()) note("Shots", StepStatus.SKIPPED, "The plan had no usable shots")

        // ---- grade
        val grade = plan.grade?.let { g ->
            val key = g.look.trim()
            val lookOk = key.isEmpty() || BuiltInLooks.byKey(key) != null || BuiltInLooks.byId(key) != null || key in lutIds
            if (!lookOk) note("Colour", StepStatus.ADJUSTED, "Look “$key” isn't available; kept the colour adjustments only")
            val safe = PlanGrade(
                look = if (lookOk) key else "",
                intensity = g.intensity.finite(0.8).coerceIn(0.0, 1.0),
                exposure = g.exposure.finite(0.0).coerceIn(-1.0, 1.0),
                contrast = g.contrast.finite(0.0).coerceIn(-0.5, 0.5),
                saturation = g.saturation.finite(0.0).coerceIn(-0.6, 0.4),
                temperature = g.temperature.finite(0.0).coerceIn(-0.5, 0.5),
                tint = g.tint.finite(0.0).coerceIn(-0.3, 0.3),
                highlights = g.highlights.finite(0.0).coerceIn(-0.6, 0.6),
                shadows = g.shadows.finite(0.0).coerceIn(-0.6, 0.6),
            )
            if (safe.copy(look = g.look.trim()) != g.copy(look = g.look.trim())) note("Colour", StepStatus.ADJUSTED, "Softened extreme colour values to avoid clipped highlights, crushed shadows or over-saturation")
            safe
        }

        // ---- texts
        val questions = plan.questions.map { it.trim().take(200) }.filter { it.isNotEmpty() }.toMutableList()
        val texts = mutableListOf<PlanText>()
        for (t in plan.texts) {
            if (texts.size >= MAX_TEXTS) { note("Text", StepStatus.SKIPPED, "Kept the first $MAX_TEXTS text layers"); break }
            val role = TextRole.entries.firstOrNull { it.name == t.role.trim().uppercase(Locale.ROOT) }
            val words = t.text.trim()
            if (words.isEmpty()) continue
            if (role == null) { note("Text", StepStatus.SKIPPED, "“${words.take(30)}”: unknown kind “${t.role}”"); continue }
            if (role == TextRole.STICKER && words.length > 8) { note("Text", StepStatus.SKIPPED, "Sticker “${words.take(12)}” isn't a single emoji"); continue }
            if (t.claim && !isGrounded(words, ground)) {
                note("Text", StepStatus.SKIPPED, "Didn't add “${words.take(40)}” — it states a fact (place, number or name) you haven't given")
                questions += "I didn't add “${words.take(40)}” because I can't verify it. Tell me the correct details and I'll add it."
                continue
            }
            val preset = t.preset.trim().uppercase(Locale.ROOT).takeIf { p -> TextPreset.entries.any { it.name == p } }.orEmpty()
            val animIn = t.animIn.trim().uppercase(Locale.ROOT).takeIf { a -> TextAnim.entries.any { it.name == a } }.orEmpty()
            val animOut = t.animOut.trim().uppercase(Locale.ROOT).takeIf { a -> TextAnim.entries.any { it.name == a } }.orEmpty()
            texts += t.copy(
                role = role.name,
                text = words.take(60),
                atSeconds = t.atSeconds.finite(0.0).coerceAtLeast(0.0),
                seconds = t.seconds.finite(2.5).coerceIn(0.8, 8.0),
                preset = preset,
                animIn = animIn,
                animOut = animOut,
            )
        }

        // ---- captions
        val captionPreset = plan.captionPreset.trim().lowercase(Locale.ROOT).let { id ->
            when {
                id.isEmpty() -> ""
                CaptionPresets.byId(id) == null -> { note("Captions", StepStatus.ADJUSTED, "Caption style “$id” isn't available; captions left as they were"); "" }
                else -> id
            }
        }
        if (captionPreset.isNotEmpty() && !hasCaptions) note("Captions", StepStatus.ADJUSTED, "No captions yet: the style is saved and used when you add captions (Captions → Auto captions)")

        // ---- audio
        val a = plan.audio
        val audio = PlanAudio(
            musicVolume = if (a.musicVolume < 0) -1.0 else a.musicVolume.finite(0.8).coerceIn(0.0, 1.0),
            musicFadeInSeconds = if (a.musicFadeInSeconds < 0) -1.0 else a.musicFadeInSeconds.finite(1.0).coerceIn(0.0, 5.0),
            musicFadeOutSeconds = if (a.musicFadeOutSeconds < 0) -1.0 else a.musicFadeOutSeconds.finite(1.5).coerceIn(0.0, 5.0),
            originalVolume = if (a.originalVolume < 0) -1.0 else a.originalVolume.finite(1.0).coerceIn(0.0, 1.0),
            musicMood = a.musicMood.trim().take(60),
        )
        val sounds = plan.sounds.mapNotNull { s ->
            val name = s.name.trim().uppercase(Locale.ROOT)
            if (SoundEffect.entries.none { it.name == name }) { note("Sound", StepStatus.SKIPPED, "Unknown sound “${s.name}”"); null }
            else PlanSound(name, s.atSeconds.finite(0.0).coerceAtLeast(0.0))
        }.take(MAX_SOUNDS)

        val clean = plan.copy(
            summary = plan.summary.trim().take(400),
            shots = shots,
            openingSeconds = plan.openingSeconds.finite(0.0).coerceIn(0.0, 2.0),
            endingSeconds = plan.endingSeconds.finite(0.0).coerceIn(0.0, 2.5),
            grade = grade,
            texts = texts,
            captionPreset = captionPreset,
            audio = audio,
            sounds = sounds,
            questions = questions.distinct().take(6),
            decisions = plan.decisions.map { it.trim().take(160) }.filter { it.isNotEmpty() }.take(10),
        )
        return ValidatedPlan(clean, steps)
    }

    private val STOP = setOf(
        "the", "and", "for", "with", "from", "into", "our", "your", "my", "a", "an", "of", "in", "on", "at", "to", "is", "day",
        "trip", "part", "welcome", "this", "that", "here", "there", "best", "first", "last", "final", "set", "reps", "rep",
    )

    /**
     * True when the factual parts of [text] — every number and every significant word — appear in
     * [ground]. Deliberately strict: a wrong place name or rep count is worse than none.
     */
    fun isGrounded(text: String, ground: String): Boolean {
        val g = ground.lowercase(Locale.ROOT)
        val gWords = g.split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }.toSet()
        val tokens = text.lowercase(Locale.ROOT).split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }
        val factual = tokens.filter { t -> t.any { it.isDigit() } || (t.length >= 3 && t !in STOP) }
        if (factual.isEmpty()) return true
        return factual.all { it in gWords }
    }

    private fun Double.finite(def: Double) = if (isNaN() || isInfinite()) def else this
    private fun fmt(v: Double) = String.format(Locale.ROOT, "%.1f", v)
}
