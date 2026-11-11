package com.cinemind.core.domain.captions

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey
import com.cinemind.core.domain.model.Project

/** How captions are grouped on screen. */
enum class CaptionMode(val label: String) {
    SENTENCE("Sentence"),
    PHRASE("Phrase"),
    WORD("Word by word"),
}

enum class CaptionAnimation(val label: String) {
    NONE("None"),
    FADE("Fade"),
    POP("Pop"),
    SLIDE_UP("Slide up"),
    TYPEWRITER("Typewriter"),
}

/** System font families (no bundled fonts: they cover Latin, Devanagari and other Indic scripts). */
enum class CaptionFont(val label: String, val family: String) {
    SANS("Sans", "sans-serif"),
    CONDENSED("Condensed", "sans-serif-condensed"),
    SERIF("Serif", "serif"),
    MONO("Mono", "monospace"),
    CASUAL("Casual", "casual"),
}

enum class CaptionAlign { START, CENTER, END }

/**
 * The project's caption look (one row per project, schema v5). Sizes are fractions of the video's
 * short side so captions look the same at 720p and 4K, in preview and in export.
 * Colours are ARGB ints.
 */
@Entity(
    tableName = "caption_styles",
    foreignKeys = [
        ForeignKey(entity = Project::class, parentColumns = ["id"], childColumns = ["projectId"], onDelete = ForeignKey.CASCADE),
    ],
)
data class CaptionStyle(
    @PrimaryKey val projectId: String,
    val presetId: String = CaptionPresets.REELS.id,
    val font: String = CaptionFont.SANS.name,
    /** Text height as a fraction of the video's short side. */
    val size: Float = 0.062f,
    /** 100..900. */
    val weight: Int = 800,
    val uppercase: Boolean = false,
    val textColor: Int = WHITE,
    val highlightColor: Int = 0xFFFFD43B.toInt(),
    /** Outline width as a fraction of the text size; 0 = none. */
    val outline: Float = 0.12f,
    val outlineColor: Int = BLACK,
    val shadow: Boolean = true,
    val backgroundColor: Int = BLACK,
    /** 0 = no box behind the text. */
    val backgroundOpacity: Float = 0f,
    /** Vertical centre of the caption block, 0 = top, 1 = bottom; clamped to the safe area. */
    val positionY: Float = 0.72f,
    val align: String = CaptionAlign.CENTER.name,
    val mode: String = CaptionMode.PHRASE.name,
    /** Highlight the word being spoken. */
    val karaoke: Boolean = true,
    val animation: String = CaptionAnimation.POP.name,
) {
    val fontEnum: CaptionFont get() = CaptionFont.entries.firstOrNull { it.name == font } ?: CaptionFont.SANS
    val modeEnum: CaptionMode get() = CaptionMode.entries.firstOrNull { it.name == mode } ?: CaptionMode.PHRASE
    val animationEnum: CaptionAnimation get() = CaptionAnimation.entries.firstOrNull { it.name == animation } ?: CaptionAnimation.NONE
    val alignEnum: CaptionAlign get() = CaptionAlign.entries.firstOrNull { it.name == align } ?: CaptionAlign.CENTER

    companion object {
        const val WHITE = 0xFFFFFFFF.toInt()
        const val BLACK = 0xFF000000.toInt()

        /** Captions never go into the outer 8% (cropped by players / covered by app UI). */
        const val SAFE_TOP = 0.08f
        const val SAFE_BOTTOM = 0.92f

        fun default(projectId: String) = CaptionPresets.REELS.applyTo(projectId)
    }
}

/** A named starting point; applying it keeps nothing of the old style except the project. */
data class CaptionPreset(val id: String, val label: String, val hint: String, val style: CaptionStyle) {
    fun applyTo(projectId: String): CaptionStyle = style.copy(projectId = projectId, presetId = id)
}

object CaptionPresets {
    private val base = CaptionStyle(projectId = "")

    val REELS = CaptionPreset(
        "reels", "Reels & Shorts", "Big, bold, word pop with highlight",
        base.copy(size = 0.07f, weight = 900, uppercase = true, mode = CaptionMode.PHRASE.name, karaoke = true, animation = CaptionAnimation.POP.name, positionY = 0.68f),
    )
    val TRAVEL = CaptionPreset(
        "travel", "Travel", "Airy serif, soft fade",
        base.copy(font = CaptionFont.SERIF.name, size = 0.055f, weight = 600, outline = 0f, shadow = true, karaoke = false, mode = CaptionMode.SENTENCE.name, animation = CaptionAnimation.FADE.name, positionY = 0.8f),
    )
    val CINEMATIC = CaptionPreset(
        "cinematic", "Cinematic", "Small, elegant subtitles",
        base.copy(font = CaptionFont.SANS.name, size = 0.042f, weight = 500, outline = 0f, shadow = true, karaoke = false, mode = CaptionMode.SENTENCE.name, animation = CaptionAnimation.FADE.name, positionY = 0.86f, textColor = 0xFFF2EFE8.toInt()),
    )
    val EDUCATION = CaptionPreset(
        "education", "Education", "Clear text on a box",
        base.copy(size = 0.048f, weight = 600, outline = 0f, shadow = false, backgroundOpacity = 0.72f, karaoke = false, mode = CaptionMode.SENTENCE.name, animation = CaptionAnimation.NONE.name, positionY = 0.82f),
    )
    val PODCAST = CaptionPreset(
        "podcast", "Podcast & interview", "Word-by-word highlight on a box",
        base.copy(size = 0.05f, weight = 700, outline = 0f, backgroundOpacity = 0.55f, karaoke = true, highlightColor = 0xFF7DD3FC.toInt(), mode = CaptionMode.PHRASE.name, animation = CaptionAnimation.FADE.name, positionY = 0.78f),
    )
    val VLOG = CaptionPreset(
        "vlog", "Vlog", "Friendly rounded look, slide up",
        base.copy(font = CaptionFont.CASUAL.name, size = 0.058f, weight = 700, karaoke = true, highlightColor = 0xFFFF8FAB.toInt(), mode = CaptionMode.PHRASE.name, animation = CaptionAnimation.SLIDE_UP.name, positionY = 0.7f),
    )
    val MUSIC = CaptionPreset(
        "music", "Music video", "One word at a time, centred",
        base.copy(font = CaptionFont.CONDENSED.name, size = 0.085f, weight = 900, uppercase = true, karaoke = false, mode = CaptionMode.WORD.name, animation = CaptionAnimation.POP.name, positionY = 0.5f),
    )

    val ALL: List<CaptionPreset> = listOf(REELS, TRAVEL, CINEMATIC, EDUCATION, PODCAST, VLOG, MUSIC)

    fun byId(id: String?): CaptionPreset? = ALL.firstOrNull { it.id == id }
}
