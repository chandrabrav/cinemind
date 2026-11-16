package com.cinemind.core.domain.aiedit

import com.cinemind.core.domain.audio.SoundEffect
import com.cinemind.core.domain.captions.CaptionPresets
import com.cinemind.core.domain.color.BuiltInLooks
import com.cinemind.core.domain.editing.ClipTransition
import com.cinemind.core.domain.editing.SpeedRampPreset
import com.cinemind.core.domain.text.TextAnim
import com.cinemind.core.domain.text.TextPreset

/** Bumped whenever the plan format changes; stored with each plan so old plans can still be read. */
const val EDIT_PLAN_VERSION = 1

/**
 * A complete creative edit, produced by a planner (the on-device creative director or a cloud
 * model) and executed by [EditPlanExecutor] into real, editable timeline objects. Times are in
 * seconds: shot ranges in the source, everything else on the finished edit's timeline.
 *
 * Flat, string-keyed fields on purpose, so a model's output can be constrained by a strict JSON
 * schema ([EditPlanJson.schema]) and every value is checked by [EditPlanValidator].
 */
data class EditPlan(
    val version: Int = EDIT_PLAN_VERSION,
    /** One or two sentences on the creative direction. */
    val summary: String = "",
    /** Main-track shots in the order they play. */
    val shots: List<PlanShot> = emptyList(),
    /** Fade from / to black at the start and end (0 = none). */
    val openingSeconds: Double = 0.0,
    val endingSeconds: Double = 0.0,
    /** A look over the whole video (consistency), with per-shot corrections in [PlanShot.exposure]. */
    val grade: PlanGrade? = null,
    val texts: List<PlanText> = emptyList(),
    /** Caption look preset id ([CaptionPresets]); "" leaves captions as they are. */
    val captionPreset: String = "",
    val audio: PlanAudio = PlanAudio(),
    val sounds: List<PlanSound> = emptyList(),
    /** Things the planner could not decide or verify; shown to the user, never guessed. */
    val questions: List<String> = emptyList(),
    /** Short reasons for the main creative decisions. */
    val decisions: List<String> = emptyList(),
)

/**
 * One shot: [source] is the 1-based source number from the footage report; [fromSeconds]..[toSeconds]
 * the range in that source. [transitionIn] is a [ClipTransition] name into this shot (ignored on the
 * first shot, see [EditPlan.openingSeconds]); [ramp] a [SpeedRampPreset] name or "".
 * [exposure] is a per-shot correction (−1..1 stops) on top of the plan's grade.
 */
data class PlanShot(
    val source: Int,
    val fromSeconds: Double,
    val toSeconds: Double,
    val speed: Double = 1.0,
    val ramp: String = "",
    val transitionIn: String = ClipTransition.CUT.name,
    val transitionSeconds: Double = 0.0,
    val exposure: Double = 0.0,
    val note: String = "",
)

/** [look] is a built-in look key ([BuiltInLooks]) or an imported LUT id; "" = adjustments only. */
data class PlanGrade(
    val look: String = "",
    val intensity: Double = 0.8,
    val exposure: Double = 0.0,
    val contrast: Double = 0.0,
    val saturation: Double = 0.0,
    val temperature: Double = 0.0,
    val tint: Double = 0.0,
    val highlights: Double = 0.0,
    val shadows: Double = 0.0,
)

/** What a text layer is for; decides placement and default look. */
enum class TextRole(val label: String) {
    TITLE("Title"),
    SECTION("Section title"),
    LOWER_THIRD("Lower third"),
    LABEL("Label"),
    CALLOUT("Callout"),
    END_CARD("End card"),
    STICKER("Sticker"),
}

/**
 * A title, label, callout or sticker. [preset] is a [TextPreset] name ("" = by role); [animIn] /
 * [animOut] are [TextAnim] names ("" = the preset's). [claim] = the text states a fact (a place,
 * a number, a person's name, a count); such text is only added when it is grounded in what the
 * user wrote or said (see [EditPlanValidator]).
 */
data class PlanText(
    val role: String,
    val text: String,
    val atSeconds: Double,
    val seconds: Double,
    val preset: String = "",
    val animIn: String = "",
    val animOut: String = "",
    val claim: Boolean = false,
)

/** Negative values leave the current setting unchanged. */
data class PlanAudio(
    val musicVolume: Double = -1.0,
    val musicFadeInSeconds: Double = -1.0,
    val musicFadeOutSeconds: Double = -1.0,
    val originalVolume: Double = -1.0,
    /** Mood to search for when the project has no music yet ("" = none); suggestion only. */
    val musicMood: String = "",
)

/** [name] is a [SoundEffect] name. */
data class PlanSound(val name: String, val atSeconds: Double)

/** What the planner knows about one piece of footage. */
data class PlanSource(
    val assetId: String,
    val isVideo: Boolean,
    val durationUs: Long,
    val label: String = "",
)

/** What happened to one part of a plan. */
enum class StepStatus { APPLIED, ADJUSTED, SKIPPED }

data class PlanStep(val area: String, val status: StepStatus, val text: String)

/** Names the planners may use, in one place. */
object PlanVocabulary {
    val transitions: List<String> get() = ClipTransition.entries.map { it.name }
    val ramps: List<String> get() = SpeedRampPreset.entries.map { it.name }
    val looks: List<String> get() = BuiltInLooks.ALL.map { it.key }
    val textPresets: List<String> get() = TextPreset.entries.map { it.name }
    val textAnims: List<String> get() = TextAnim.entries.map { it.name }
    val roles: List<String> get() = TextRole.entries.map { it.name }
    val captionPresets: List<String> get() = CaptionPresets.ALL.map { it.id }
    val sounds: List<String> get() = SoundEffect.entries.map { it.name }
}
