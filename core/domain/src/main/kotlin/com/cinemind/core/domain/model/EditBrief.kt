package com.cinemind.core.domain.model

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey

/**
 * What the user wants the AI edit to be, stored per project. This is the *input* to the future
 * AI pipeline (Stage 4–5); it never changes the timeline by itself.
 *
 * [schemaVersion] lets later AI stages migrate old briefs when new fields are added.
 */
@Entity(
    tableName = "edit_briefs",
    foreignKeys = [
        ForeignKey(
            entity = Project::class,
            parentColumns = ["id"],
            childColumns = ["projectId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class EditBrief(
    @PrimaryKey val projectId: String,
    val prompt: String = "",
    val style: EditStyle = EditStyle.AUTO,
    /** Desired length of the finished video; null = let the AI decide. */
    val targetDurationSec: Int? = null,
    val pacing: Pacing = Pacing.BALANCED,
    val updatedAt: Long = System.currentTimeMillis(),
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
) {
    companion object {
        const val CURRENT_SCHEMA_VERSION = 1
    }
}

enum class EditStyle(val label: String) {
    AUTO("Auto"),
    CINEMATIC("Cinematic"),
    TRAVEL("Travel"),
    GYM("Gym / energetic"),
    LIFESTYLE("Lifestyle"),
    VLOG("Vlog"),
}

enum class Pacing(val label: String) {
    CALM("Calm"),
    BALANCED("Balanced"),
    FAST("Fast"),
}

/** Output shapes a project can be edited and exported in. Dimensions are even (encoder-safe). */
enum class ProjectFormat(val ratio: String, val width: Int, val height: Int, val label: String) {
    VERTICAL_9_16("9:16", 1080, 1920, "Reels / Shorts"),
    LANDSCAPE_16_9("16:9", 1920, 1080, "Widescreen"),
    SQUARE_1_1("1:1", 1080, 1080, "Square"),
    PORTRAIT_4_5("4:5", 1080, 1350, "Feed post"),
    ;

    companion object {
        fun fromRatio(ratio: String): ProjectFormat? = entries.firstOrNull { it.ratio == ratio }
    }
}

/** Validation and guidance for an [EditBrief]. Pure logic. */
object EditBriefRules {
    const val MAX_PROMPT_LENGTH = 6000
    val DURATION_OPTIONS_SEC = listOf(15, 30, 60, 90)

    /** Starter phrases the user can tap to add to their prompt. */
    val SUGGESTIONS = listOf(
        "Cinematic travel reel with slow, dreamy shots",
        "High-energy gym edit, cut on the beat",
        "Keep only the best moments, skip shaky or blurry shots",
        "Tell the day as a story, from morning to night",
        "Open with a wide establishing shot",
    )

    fun clampPrompt(text: String): String = text.take(MAX_PROMPT_LENGTH)

    /** Adds [suggestion] to [prompt] as a new sentence, without duplicating it. */
    fun appendSuggestion(prompt: String, suggestion: String): String {
        if (prompt.contains(suggestion, ignoreCase = true)) return prompt
        val base = prompt.trimEnd()
        val joined = when {
            base.isEmpty() -> suggestion
            base.last() in ".!?" -> "$base $suggestion"
            else -> "$base. $suggestion"
        }
        return clampPrompt(joined)
    }

    /** Human-readable problems or hints for the current brief; empty when everything looks fine. */
    fun notices(brief: EditBrief, footageUs: Long): List<String> = buildList {
        if (brief.prompt.length > MAX_PROMPT_LENGTH) {
            add("Description is too long (max $MAX_PROMPT_LENGTH characters).")
        }
        if (brief.prompt.isBlank() && brief.style == EditStyle.AUTO) {
            add("Describe the video or pick a style so the AI knows what you want.")
        }
        val target = brief.targetDurationSec
        if (target != null && footageUs > 0 && target * 1_000_000L > footageUs) {
            add(
                "Your clips total ${footageUs / 1_000_000} s, shorter than a $target s edit. " +
                    "Pick a shorter length or add clips.",
            )
        }
    }
}
