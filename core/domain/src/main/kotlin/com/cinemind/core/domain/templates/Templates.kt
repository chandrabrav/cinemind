package com.cinemind.core.domain.templates

import com.cinemind.core.domain.color.ColorGrade
import com.cinemind.core.domain.editing.ClipFraming
import com.cinemind.core.domain.editing.ClipTransition
import com.cinemind.core.domain.editing.MotionPreset
import com.cinemind.core.domain.editing.TimelineEditing
import com.cinemind.core.domain.model.Clip
import com.cinemind.core.domain.model.MediaAsset
import com.cinemind.core.domain.model.Project
import com.cinemind.core.domain.model.ProjectFactory
import com.cinemind.core.domain.model.ProjectTimeline
import com.cinemind.core.domain.model.Track
import com.cinemind.core.domain.model.TrackType
import com.cinemind.core.domain.text.TextKind
import com.cinemind.core.domain.text.TextLayer
import com.cinemind.core.domain.text.TextLayerEditing
import com.cinemind.core.domain.text.TextPreset
import kotlin.math.roundToLong

/** One shot of a template: how long it plays, how it arrives, optional speed/motion, and a hint. */
data class TemplateSlot(
    val lengthUs: Long,
    val hint: String,
    val transition: ClipTransition = ClipTransition.CUT,
    val transitionMs: Long = 0,
    val speed: Float = 1f,
    val motion: MotionPreset? = null,
)

/** A title or sticker of a template, at timeline time ([endUs] < 0 = until the end of the video). */
data class TemplateText(val text: String, val preset: TextPreset?, val startUs: Long, val endUs: Long, val sticker: Boolean = false, val y: Float? = null)

/**
 * A ready-made edit with slots for the user's own media (CineMind's own designs). Applying it makes
 * a normal, fully editable project: clips cut to the slot lengths (from the middle of each video),
 * transitions, speed, motion, a colour look and titles.
 */
data class Template(
    val id: String,
    val name: String,
    val description: String,
    val category: String,
    val slots: List<TemplateSlot>,
    val texts: List<TemplateText> = emptyList(),
    /** Built-in look key (see BuiltInLooks), applied to every clip. */
    val look: String? = null,
    /** Music mood to suggest (Audio > Find music). */
    val musicMood: String = "",
) {
    val durationUs: Long get() = slots.sumOf { it.lengthUs }
}

object Templates {

    private const val S = 1_000_000L
    private fun ms(v: Long) = v * 1_000

    val all: List<Template> = listOf(
        Template(
            "travel_montage", "Travel montage", "Fast cuts that build into a wide finale", "Travel",
            slots = listOf(
                TemplateSlot(ms(2_000), "Wide opening view", motion = MotionPreset.ZOOM_IN),
                TemplateSlot(ms(900), "On the road", ClipTransition.WHIP_LEFT, 300),
                TemplateSlot(ms(900), "Detail shot", ClipTransition.WHIP_LEFT, 300),
                TemplateSlot(ms(900), "You in the place", ClipTransition.WHIP_RIGHT, 300),
                TemplateSlot(ms(900), "Food or street", ClipTransition.WHIP_LEFT, 300),
                TemplateSlot(ms(1_200), "Activity", ClipTransition.ZOOM_IN, 300),
                TemplateSlot(ms(1_200), "Sunset or night", ClipTransition.DISSOLVE, 500),
                TemplateSlot(ms(3_000), "Big final view", ClipTransition.DISSOLVE, 800, motion = MotionPreset.ZOOM_OUT),
            ),
            texts = listOf(TemplateText("Your destination", TextPreset.BOLD_TITLE, 0, ms(1_900)), TemplateText("Until next time", TextPreset.CINEMA, ms(8_600), -1)),
            look = "teal_orange", musicMood = "Adventurous, uplifting",
        ),
        Template(
            "wedding_highlight", "Wedding highlight", "Slow, warm and romantic, with soft dissolves", "Wedding",
            slots = listOf(
                TemplateSlot(ms(3_000), "Venue or decor", motion = MotionPreset.ZOOM_IN),
                TemplateSlot(ms(2_500), "Bride / groom getting ready", ClipTransition.DISSOLVE, 800, speed = 0.75f),
                TemplateSlot(ms(2_500), "Family moment", ClipTransition.DISSOLVE, 800),
                TemplateSlot(ms(2_500), "Rings or hands", ClipTransition.DISSOLVE, 800, motion = MotionPreset.ZOOM_IN),
                TemplateSlot(ms(3_000), "Main ceremony", ClipTransition.DISSOLVE, 800, speed = 0.75f),
                TemplateSlot(ms(2_500), "Dance or celebration", ClipTransition.DISSOLVE, 800),
                TemplateSlot(ms(3_500), "The couple together", ClipTransition.DISSOLVE, 1_000, motion = MotionPreset.ZOOM_OUT),
            ),
            texts = listOf(TemplateText("Names & date", TextPreset.CINEMA, ms(300), ms(2_800)), TemplateText("Forever starts here", TextPreset.CINEMA, ms(16_500), -1)),
            look = "golden_hour", musicMood = "Romantic, celebratory",
        ),
        Template(
            "gym_hype", "Gym hype", "Hard cuts, punch-ins and speed ramps", "Fitness",
            slots = listOf(
                TemplateSlot(ms(1_200), "Walk-in / warm-up", motion = MotionPreset.PUNCH_IN),
                TemplateSlot(ms(700), "Lift 1", ClipTransition.ZOOM_IN, 200, speed = 1.5f),
                TemplateSlot(ms(700), "Lift 2", ClipTransition.ZOOM_IN, 200, speed = 1.5f),
                TemplateSlot(ms(1_500), "Heavy rep (slow)", speed = 0.5f),
                TemplateSlot(ms(700), "Detail: chalk, hands", ClipTransition.WHIP_LEFT, 200),
                TemplateSlot(ms(700), "Lift 3", ClipTransition.ZOOM_IN, 200, speed = 1.5f),
                TemplateSlot(ms(2_000), "Finish / flex", ClipTransition.SPIN, 300, motion = MotionPreset.PUNCH_IN),
            ),
            texts = listOf(TemplateText("NO DAYS OFF", TextPreset.IMPACT, 0, ms(1_200)), TemplateText("💪", null, ms(5_500), -1, sticker = true)),
            look = "bleach_bypass", musicMood = "Powerful, driving",
        ),
        Template(
            "food_reel", "Food reel", "Close-ups, a reveal and the first bite", "Food",
            slots = listOf(
                TemplateSlot(ms(1_500), "Place or kitchen", motion = MotionPreset.PAN_RIGHT),
                TemplateSlot(ms(1_000), "Ingredients", ClipTransition.SLIDE_LEFT, 300),
                TemplateSlot(ms(1_200), "Cooking / sizzle", ClipTransition.SLIDE_LEFT, 300, speed = 1.5f),
                TemplateSlot(ms(1_500), "The dish reveal", ClipTransition.ZOOM_IN, 300, motion = MotionPreset.ZOOM_IN),
                TemplateSlot(ms(2_000), "First bite / reaction", ClipTransition.DISSOLVE, 400),
            ),
            texts = listOf(TemplateText("Dish name", TextPreset.HANDWRITTEN, ms(3_700), ms(5_200)), TemplateText("😋", null, ms(5_400), -1, sticker = true)),
            look = "appetizing", musicMood = "Light, playful",
        ),
        Template(
            "day_in_my_life", "Day in my life", "Morning to night in easy, even beats", "Vlog",
            slots = listOf(
                TemplateSlot(ms(1_800), "Morning", motion = MotionPreset.ZOOM_IN),
                TemplateSlot(ms(1_500), "Coffee / breakfast", ClipTransition.SLIDE_LEFT, 300),
                TemplateSlot(ms(1_500), "Work or study", ClipTransition.SLIDE_LEFT, 300),
                TemplateSlot(ms(1_500), "Out and about", ClipTransition.SLIDE_LEFT, 300),
                TemplateSlot(ms(1_500), "Friends / hobby", ClipTransition.SLIDE_LEFT, 300),
                TemplateSlot(ms(2_000), "Night", ClipTransition.DISSOLVE, 600, motion = MotionPreset.ZOOM_OUT),
            ),
            texts = listOf(TemplateText("a day in my life", TextPreset.MINIMAL, ms(200), ms(1_700)), TemplateText("see you tomorrow", TextPreset.MINIMAL, ms(8_300), -1)),
            look = "clean_bright", musicMood = "Easy-going",
        ),
        Template(
            "festival_lights", "Festival lights", "Glowing, quick and joyful", "Celebration",
            slots = listOf(
                TemplateSlot(ms(1_500), "Lights / decorations", motion = MotionPreset.ZOOM_IN),
                TemplateSlot(ms(900), "Family", ClipTransition.ZOOM_IN, 300),
                TemplateSlot(ms(900), "Sweets / food", ClipTransition.ZOOM_IN, 300),
                TemplateSlot(ms(900), "Rangoli / diyas", ClipTransition.ZOOM_IN, 300),
                TemplateSlot(ms(1_500), "Fireworks / celebration", ClipTransition.DISSOLVE, 500, speed = 0.75f),
                TemplateSlot(ms(2_000), "Group photo", ClipTransition.DISSOLVE, 600, motion = MotionPreset.ZOOM_OUT),
            ),
            texts = listOf(TemplateText("Happy Diwali", TextPreset.NEON, ms(200), ms(1_500)), TemplateText("🪔", null, ms(3_500), ms(6_000), sticker = true)),
            look = "neon_night", musicMood = "Festive, energetic",
        ),
        Template(
            "cinematic_story", "Cinematic story", "Moody, slow and wide, with letterbox-style titles", "Cinematic",
            slots = listOf(
                TemplateSlot(ms(3_500), "Establishing shot", motion = MotionPreset.PAN_RIGHT),
                TemplateSlot(ms(3_000), "The character", ClipTransition.DISSOLVE, 1_000, speed = 0.75f),
                TemplateSlot(ms(2_500), "A detail", ClipTransition.DISSOLVE, 800, motion = MotionPreset.ZOOM_IN),
                TemplateSlot(ms(3_000), "Movement", ClipTransition.DISSOLVE, 800),
                TemplateSlot(ms(4_000), "Ending shot", ClipTransition.DISSOLVE, 1_200, motion = MotionPreset.ZOOM_OUT),
            ),
            texts = listOf(TemplateText("A SHORT FILM", TextPreset.CINEMA, ms(800), ms(3_200)), TemplateText("FIN", TextPreset.CINEMA, ms(13_000), -1)),
            look = "moody_blue", musicMood = "Cinematic, emotional",
        ),
        Template(
            "product_showcase", "Product showcase", "Clean reveals for shops and creators", "Business",
            slots = listOf(
                TemplateSlot(ms(1_500), "Product hero shot", motion = MotionPreset.ZOOM_IN),
                TemplateSlot(ms(1_500), "Detail 1", ClipTransition.SLIDE_UP, 300),
                TemplateSlot(ms(1_500), "Detail 2", ClipTransition.SLIDE_UP, 300),
                TemplateSlot(ms(1_500), "In use", ClipTransition.SLIDE_UP, 300),
                TemplateSlot(ms(2_500), "Price / call to action", ClipTransition.DISSOLVE, 400, motion = MotionPreset.ZOOM_OUT),
            ),
            texts = listOf(TemplateText("NEW", TextPreset.LABEL, ms(200), ms(1_500), y = 0.15f), TemplateText("Shop now · link in bio", TextPreset.LOWER_THIRD, ms(6_200), -1)),
            look = "punch", musicMood = "Stylish, confident",
        ),
    )

    fun byId(id: String): Template? = all.firstOrNull { it.id == id }

    /**
     * Builds the project. Media fill the slots in order; extra media repeat the slot pattern, fewer
     * media use the first slots. Videos are cut from their middle part; a video shorter than its
     * slot keeps its full length. Photos take the slot length.
     */
    fun apply(template: Template, assets: List<MediaAsset>, name: String, now: Long = System.currentTimeMillis()): Pair<ProjectTimeline, List<TextLayer>> {
        require(assets.isNotEmpty()) { "Pick at least one photo or video" }
        val base = ProjectFactory.fromAssets(name, assets, now)
        // Templates are vertical (Reels / Shorts).
        val project: Project = base.project.copy(aspectRatio = "9:16", resolutionWidth = 1080, resolutionHeight = 1920)
        val track: Track = base.tracks.first { it.type == TrackType.VIDEO }
        val grade = template.look?.let { ColorGrade(lutId = "builtin:$it").encode() }.orEmpty()
        val clips = assets.mapIndexed { i, asset ->
            val slot = template.slots[i % template.slots.size]
            val speed = if (asset.isVideo) slot.speed else 1f
            val need = (slot.lengthUs * speed).roundToLong()
            val (inUs, outUs) = when {
                !asset.isVideo -> 0L to slot.lengthUs
                asset.durationUs <= need -> 0L to asset.durationUs
                else -> {
                    val start = ((asset.durationUs - need) / 2).coerceAtLeast(0)
                    start to start + need
                }
            }
            val clip = Clip(
                trackId = track.id,
                assetId = asset.id,
                timelineInUs = 0,
                timelineOutUs = 0,
                sourceInUs = inUs,
                sourceOutUs = outUs,
                speed = speed,
                transitionInType = if (i == 0) ClipTransition.CUT.name else slot.transition.name,
                transitionInMs = if (i == 0) 0 else slot.transitionMs,
                grade = grade,
            )
            slot.motion?.let { m -> clip.copy(framing = ClipFraming(keys = m.keysFor(clip)).encode()) } ?: clip
        }
        val laid = TimelineEditing.relayout(clips)
        val total = TimelineEditing.durationUs(laid)
        var texts = emptyList<TextLayer>()
        template.texts.forEach { t ->
            val start = t.startUs.coerceIn(0, (total - 300_000).coerceAtLeast(0))
            val end = (if (t.endUs < 0) total else t.endUs).coerceIn(start + 300_000, maxOf(total, start + 300_000))
            val layer = TextLayerEditing.create(
                project.id, texts, start, total, t.text,
                if (t.sticker) TextKind.STICKER else TextKind.TEXT, t.preset,
            ).copy(startUs = start, endUs = end).let { l -> t.y?.let { l.copy(y = it) } ?: l }
            texts = texts + layer
        }
        return ProjectTimeline(project.copy(durationUs = total), listOf(track.copy(projectId = project.id)), laid) to texts
    }
}
