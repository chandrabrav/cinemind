package com.cinemind.core.domain.aiedit

import com.cinemind.core.domain.audio.SoundEffect
import com.cinemind.core.domain.color.BuiltInLooks
import com.cinemind.core.domain.color.LutAdvisor
import com.cinemind.core.domain.color.LutCandidate
import com.cinemind.core.domain.color.SceneProfile
import com.cinemind.core.domain.editing.ClipTransition
import com.cinemind.core.domain.editing.SpeedRampPreset
import com.cinemind.core.domain.model.EditStyle
import com.cinemind.core.domain.model.Pacing
import com.cinemind.core.domain.text.GraphicsAdvisor
import com.cinemind.core.domain.text.TextAnim
import com.cinemind.core.domain.text.TextPreset
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** A chosen shot with what was measured about it (0..1 values; −1 = unknown). */
data class MeasuredShot(
    val shot: PlanShot,
    val score: Float,
    val brightness: Float = -1f,
    val motion: Float = -1f,
    /** What the on-device model saw, if it looked ("" otherwise). */
    val seen: String = "",
)

/** What the director knows besides the shots. */
data class DirectorBrief(
    val style: EditStyle,
    val pacing: Pacing,
    val prompt: String,
    val projectName: String,
    val transition: ClipTransition,
    val transitionMs: Long,
    val openingMs: Long,
    val endingMs: Long,
    val hasMusic: Boolean,
    val hasSpeech: Boolean,
    /** When the footage was filmed (epoch ms) from the files' own metadata, or null. */
    val filmedAt: Long? = null,
)

/**
 * The on-device creative director: turns chosen shots and a brief into a complete [EditPlan] —
 * colour look plus per-shot exposure matching, selective speed ramps, transitions where the
 * scene changes, titles and an end card in a style that suits the video, a caption style, sound
 * accents and music levels. Rule-based, offline and deterministic; a cloud model can replace or
 * refine the plan, and the same validator and executor run either way.
 *
 * It never invents facts: place names, exercise names and counts are asked for, not guessed.
 */
object CreativeDirector {

    fun direct(shots: List<MeasuredShot>, brief: DirectorBrief): EditPlan {
        require(shots.isNotEmpty())
        val style = brief.style
        val text = (brief.prompt + " " + brief.projectName).lowercase(Locale.ROOT)
        val decisions = mutableListOf<String>()
        val questions = mutableListOf<String>()

        // ---- colour: one look for consistency, exposure matched per shot
        val known = shots.mapNotNull { it.brightness.takeIf { b -> b >= 0f } }
        val avgBrightness = if (known.isEmpty()) null else known.average().toFloat()
        val look = chooseLook(style, brief.prompt.lowercase(Locale.ROOT), avgBrightness)
        val warmer = listOf("warm", "golden", "sunset", "cozy", "cosy").any { it in text }
        val cooler = listOf("cool", "cold", "blue", "winter", "icy").any { it in text }
        val grade = PlanGrade(
            look = look,
            intensity = when (style) { EditStyle.GYM -> 0.85; EditStyle.CINEMATIC -> 0.75; EditStyle.VLOG -> 0.45; else -> 0.65 },
            contrast = when (style) { EditStyle.GYM -> 0.15; EditStyle.CINEMATIC -> 0.08; else -> 0.0 },
            saturation = when (style) { EditStyle.CINEMATIC -> -0.05; EditStyle.TRAVEL -> 0.05; else -> 0.0 },
            temperature = when { warmer -> 0.12; cooler -> -0.1; else -> 0.0 },
            highlights = if ((avgBrightness ?: 0.5f) > 0.62f) -0.15 else 0.0,
            shadows = if ((avgBrightness ?: 0.5f) < 0.35f) 0.15 else 0.0,
        )
        decisions += "Colour: “${BuiltInLooks.byKey(look)?.name ?: look}” for a consistent ${style.label.lowercase(Locale.ROOT)} look" +
            (avgBrightness?.let { if (it < 0.35f) ", lifting dark footage" else if (it > 0.62f) ", protecting bright highlights" else "" } ?: "")

        // ---- per-shot: exposure match, ramps, transitions
        val rampFor = rampShots(shots, style, brief.pacing)
        var lastSource = -1
        val planShots = shots.mapIndexed { i, m ->
            val b = m.brightness
            val exposure = if (b >= 0f && kotlin.math.abs(b - 0.48f) > 0.08f) ((0.48f - b) * 1.6f).coerceIn(-0.5f, 0.6f).toDouble() else 0.0
            val changesScene = i > 0 && m.shot.source != lastSource
            lastSource = m.shot.source
            val tr = if (i > 0 && changesScene && brief.transition != ClipTransition.CUT) brief.transition else ClipTransition.CUT
            m.shot.copy(
                ramp = rampFor[i]?.name ?: "",
                speed = 1.0,
                transitionIn = tr.name,
                transitionSeconds = if (tr == ClipTransition.CUT) 0.0 else brief.transitionMs / 1000.0,
                exposure = round2(exposure),
                note = m.seen.take(80),
            )
        }
        if (rampFor.isNotEmpty()) decisions += "Speed ramp on the ${if (rampFor.size == 1) "strongest moment" else "${rampFor.size} strongest moments"} (${rampFor.values.first().label.lowercase(Locale.ROOT)}), normal speed elsewhere"
        if (planShots.count { it.exposure != 0.0 } > 0) decisions += "Matched brightness on ${planShots.count { it.exposure != 0.0 }} shot(s)"

        // ---- timing estimate of the cut (before ramps; good enough for placing text)
        val totalS = planShots.sumOf { (it.toSeconds - it.fromSeconds) / it.speed }
        val starts = planShots.runningFold(0.0) { acc, s -> acc + (s.toSeconds - s.fromSeconds) / s.speed }

        // ---- text: title, date, end card — all grounded in what the user wrote or the files' metadata
        val texts = mutableListOf<PlanText>()
        val titlePreset = when (style) {
            EditStyle.CINEMATIC -> TextPreset.CINEMA
            EditStyle.GYM -> TextPreset.IMPACT
            EditStyle.VLOG -> TextPreset.HANDWRITTEN
            EditStyle.LIFESTYLE -> TextPreset.MINIMAL
            EditStyle.TRAVEL, EditStyle.AUTO -> TextPreset.BOLD_TITLE
        }
        val (animIn, animOut) = when (style) {
            EditStyle.CINEMATIC, EditStyle.LIFESTYLE -> TextAnim.FADE to TextAnim.FADE
            EditStyle.GYM -> TextAnim.BOUNCE to TextAnim.ZOOM
            EditStyle.TRAVEL -> TextAnim.SLIDE_UP to TextAnim.FADE
            EditStyle.VLOG -> TextAnim.SLIDE_UP to TextAnim.FADE
            EditStyle.AUTO -> TextAnim.POP to TextAnim.FADE
        }
        val title = GraphicsAdvisor.titleFrom(brief.projectName, brief.prompt)
        if (totalS >= 3.0) {
            val at = if (brief.openingMs > 0) brief.openingMs / 1000.0 * 0.5 else 0.2
            texts += PlanText(TextRole.TITLE.name, title, at, (totalS / 3).coerceIn(1.5, 3.0), titlePreset.name, animIn.name, animOut.name)
        }
        val date = brief.filmedAt?.let { SimpleDateFormat("d MMM yyyy", Locale.ENGLISH).format(Date(it)) }
        if (date != null && totalS >= 8.0 && style in setOf(EditStyle.TRAVEL, EditStyle.VLOG, EditStyle.LIFESTYLE)) {
            val at = (starts.getOrElse(1) { 3.0 }).coerceAtMost(totalS - 3.0)
            texts += PlanText(TextRole.LOWER_THIRD.name, date, at, 2.5, TextPreset.LOWER_THIRD.name, TextAnim.SLIDE_LEFT.name, TextAnim.FADE.name)
        }
        if (totalS >= 6.0) {
            val endLine = when (style) {
                EditStyle.GYM -> "No days off"
                EditStyle.TRAVEL -> "Until the next trip"
                EditStyle.CINEMATIC -> "Fin"
                EditStyle.VLOG -> "See you tomorrow"
                else -> null
            }
            if (endLine != null) texts += PlanText(TextRole.END_CARD.name, endLine, totalS - minOf(2.5, totalS / 4), minOf(2.5, totalS / 4),
                if (style == EditStyle.CINEMATIC) TextPreset.CINEMA.name else titlePreset.name, TextAnim.FADE.name, TextAnim.FADE.name)
        }
        decisions += "Text in the “${titlePreset.label}” style with ${animIn.label.lowercase(Locale.ROOT)} animation" + if (date != null && texts.any { it.role == TextRole.LOWER_THIRD.name }) ", and the filming date" else ""
        when (style) {
            EditStyle.TRAVEL -> questions += "Which places are in this video? Tell me (for example “Manali, Solang Valley”) and I'll add location titles — I won't guess them."
            EditStyle.GYM -> questions += "Which exercises (and reps or sets, if you want them shown) are in this video? I'll add labels — I won't guess counts."
            else -> Unit
        }

        // ---- captions
        val captionPreset = when {
            listOf("podcast", "interview").any { it in text } -> "podcast"
            listOf("tutorial", "education", "explain", "lesson").any { it in text } -> "education"
            style == EditStyle.CINEMATIC -> "cinematic"
            style == EditStyle.TRAVEL -> "travel"
            style == EditStyle.VLOG || style == EditStyle.LIFESTYLE -> "vlog"
            else -> "reels"
        }
        if (brief.hasSpeech) decisions += "Captions styled for ${style.label.lowercase(Locale.ROOT)}"

        // ---- sound
        val sounds = mutableListOf<PlanSound>()
        if (style == EditStyle.GYM) {
            sounds += PlanSound(SoundEffect.IMPACT.name, 0.0)
            planShots.indices.filter { it > 0 && planShots[it].transitionIn != ClipTransition.CUT.name }.take(3).forEach { i ->
                sounds += PlanSound(SoundEffect.WHOOSH.name, (starts[i] - 0.3).coerceAtLeast(0.0))
            }
            decisions += "Impact on the opening and whooshes on a few transitions"
        }
        val audio = PlanAudio(
            musicVolume = if (brief.hasMusic) (if (brief.hasSpeech) 0.35 else 0.8) else -1.0,
            musicFadeInSeconds = when (style) { EditStyle.GYM -> 0.3; EditStyle.CINEMATIC -> 1.5; else -> 1.0 },
            musicFadeOutSeconds = when (style) { EditStyle.GYM -> 1.0; EditStyle.CINEMATIC -> 2.5; else -> 2.0 },
            originalVolume = if (brief.hasMusic && !brief.hasSpeech) 0.3 else -1.0,
            musicMood = if (brief.hasMusic) "" else when (style) {
                EditStyle.GYM -> "energetic workout beats"
                EditStyle.CINEMATIC -> "cinematic ambient score"
                EditStyle.TRAVEL -> "uplifting travel"
                EditStyle.VLOG, EditStyle.LIFESTYLE -> "light lo-fi"
                EditStyle.AUTO -> "upbeat"
            },
        )
        if (brief.hasMusic && brief.hasSpeech) decisions += "Music kept under the speech"

        return EditPlan(
            summary = "${style.label} edit, ${brief.pacing.label.lowercase(Locale.ROOT)} pace, about ${totalS.toInt()} s",
            shots = planShots,
            openingSeconds = brief.openingMs / 1000.0,
            endingSeconds = brief.endingMs / 1000.0,
            grade = grade,
            texts = texts,
            captionPreset = captionPreset,
            audio = audio,
            sounds = sounds,
            questions = questions,
            decisions = decisions,
        )
    }

    /** Look key for the style, refined by the brief's words and how bright the footage is. */
    fun chooseLook(style: EditStyle, text: String, brightness: Float?): String {
        val night = listOf("night", "neon", "city lights").any { it in text } || (brightness != null && brightness < 0.3f)
        val fallback = when (style) {
            EditStyle.CINEMATIC -> if (night) "moody_blue" else "teal_orange"
            EditStyle.GYM -> "punch"
            EditStyle.TRAVEL -> "golden_hour"
            EditStyle.VLOG -> "skin_warm"
            EditStyle.LIFESTYLE -> "clean_bright"
            EditStyle.AUTO -> if (night) "urban_cool" else "clean_bright"
        }
        val words = LutAdvisor.keywords(text)
        val specific = words.any { it in setOf("sunset", "golden hour", "beach", "mountain", "snow", "forest", "ocean", "food", "wedding", "night", "vintage", "black and white", "neon", "desert", "monsoon", "rain") }
        if (!specific) return fallback
        val candidates = BuiltInLooks.ALL.map { LutCandidate(it.id, it.name, it.category, it.tags) }
        val best = LutAdvisor.suggest(SceneProfile(words = words, brightness = brightness), candidates, limit = 1).firstOrNull() ?: return fallback
        return BuiltInLooks.byId(best.id)?.key ?: fallback
    }

    /** At most one or two ramps, on long enough, high-scoring (gym: high-motion) shots. */
    private fun rampShots(shots: List<MeasuredShot>, style: EditStyle, pacing: Pacing): Map<Int, SpeedRampPreset> {
        val (preset, count, minLen) = when (style) {
            EditStyle.GYM -> Triple(SpeedRampPreset.HERO, 2, 1.5)
            EditStyle.CINEMATIC -> Triple(SpeedRampPreset.CINEMATIC, 1, 2.0)
            EditStyle.TRAVEL -> Triple(SpeedRampPreset.CINEMATIC, 1, 3.0)
            else -> return emptyMap()
        }
        if (shots.size < 3 || pacing == Pacing.CALM && style == EditStyle.GYM) return emptyMap()
        val candidates = shots.withIndex().filter { (i, m) ->
            i > 0 && m.shot.toSeconds - m.shot.fromSeconds >= minLen && m.shot.source > 0 && m.motion != 0f
        }
        val ranked = if (style == EditStyle.GYM) candidates.sortedByDescending { it.value.motion * 0.6f + it.value.score * 0.4f }
        else candidates.sortedByDescending { it.value.score }
        return ranked.take(count).associate { it.index to preset }
    }

    private fun round2(v: Double) = Math.round(v * 100) / 100.0
}
