package com.cinemind.core.domain.aiedit

import com.cinemind.core.domain.model.EditStyle
import com.cinemind.core.domain.model.Pacing
import java.util.Locale

enum class Genre(val label: String) {
    GYM("Gym & fitness"),
    CINEMATIC("Cinematic"),
    TRAVEL("Travel"),
    VLOG("Vlog"),
    PODCAST("Podcast & talking"),
    EDUCATION("Education"),
    PROMO("Promo & product"),
    SOCIAL("Social & trends"),
}

/** A ready-made brief: the words plus the style, pacing and length that suit it. */
data class PromptTemplate(
    val id: String,
    val genre: Genre,
    val title: String,
    val prompt: String,
    val style: EditStyle = EditStyle.AUTO,
    val pacing: Pacing = Pacing.BALANCED,
    val seconds: Int? = null,
    /** Written by the user (editable, deletable) rather than built in. */
    val custom: Boolean = false,
)

/**
 * Built-in briefs by genre, plus search. All are original wording; facts (places, names,
 * exercises, numbers) are left for the user to add so nothing is ever guessed.
 */
object PromptLibrary {

    private fun t(id: String, g: Genre, title: String, prompt: String, style: EditStyle, pacing: Pacing, seconds: Int?) =
        PromptTemplate(id, g, title, prompt, style, pacing, seconds)

    val BUILT_IN: List<PromptTemplate> = listOf(
        t("gym-hype", Genre.GYM, "Hype workout reel", "Energetic gym motivation reel: punchy cuts on the beat, speed ramps on the big lifts, bold titles, end on the strongest rep.", EditStyle.GYM, Pacing.FAST, 30),
        t("gym-progress", Genre.GYM, "Progress journey", "Show my training progress from the first clip to the last, steady build-up, finish with a confident close-up.", EditStyle.GYM, Pacing.BALANCED, 45),
        t("gym-form", Genre.GYM, "Form breakdown", "Clear exercise breakdown: keep each movement whole, slow motion on the key part, labels for each exercise.", EditStyle.GYM, Pacing.CALM, 60),
        t("gym-pr", Genre.GYM, "PR moment", "Build tension before the lift, slow motion at the top, explosive cut to the celebration.", EditStyle.GYM, Pacing.FAST, 15),

        t("cine-mood", Genre.CINEMATIC, "Moody short film", "Cinematic and emotional: slow, wide shots first, soft dissolves, teal-and-orange look, quiet title at the start.", EditStyle.CINEMATIC, Pacing.CALM, 45),
        t("cine-trailer", Genre.CINEMATIC, "Trailer", "Movie-trailer feel: slow open, rising tension, faster cuts towards the end, big title card and a dramatic final shot.", EditStyle.CINEMATIC, Pacing.FAST, 30),
        t("cine-golden", Genre.CINEMATIC, "Golden hour", "Warm golden-hour film look, gentle camera moves, linger on the best light, fade to black at the end.", EditStyle.CINEMATIC, Pacing.CALM, 30),
        t("cine-bw", Genre.CINEMATIC, "Black & white story", "Timeless black-and-white story with strong contrast, simple cuts and an elegant title.", EditStyle.CINEMATIC, Pacing.CALM, 40),

        t("travel-dreamy", Genre.TRAVEL, "Dreamy travel reel", "Dreamy travel reel: sunsets, landscapes and candid moments, warm colours, smooth transitions.", EditStyle.TRAVEL, Pacing.CALM, 30),
        t("travel-fast", Genre.TRAVEL, "Fast trip recap", "Fast trip recap: quick cuts on the beat, the best moment from every place, upbeat and fun.", EditStyle.TRAVEL, Pacing.FAST, 20),
        t("travel-day", Genre.TRAVEL, "A day in…", "One day from morning to night, in the order it happened, with the date as a small title.", EditStyle.TRAVEL, Pacing.BALANCED, 60),
        t("travel-food", Genre.TRAVEL, "Food trail", "Food trail: close-ups of every dish, quick cuts, bright and appetising colours.", EditStyle.TRAVEL, Pacing.FAST, 30),

        t("vlog-day", Genre.VLOG, "Day in my life", "Relaxed day-in-my-life vlog, natural colours, keep my talking parts, captions for speech.", EditStyle.VLOG, Pacing.BALANCED, 60),
        t("vlog-grwm", Genre.VLOG, "Get ready with me", "Get-ready-with-me: keep what I say, jump cuts to remove pauses, friendly captions.", EditStyle.VLOG, Pacing.BALANCED, 60),
        t("vlog-weekend", Genre.VLOG, "Weekend recap", "Fun weekend recap with the best moments, upbeat pacing, playful stickers.", EditStyle.VLOG, Pacing.FAST, 30),

        t("pod-clip", Genre.PODCAST, "Podcast clip", "Short podcast clip: keep the full sentences, bold word-by-word captions, cut the pauses.", EditStyle.AUTO, Pacing.BALANCED, 45),
        t("pod-quote", Genre.PODCAST, "Quote highlight", "One strong quote, big captions, punch in on the key words.", EditStyle.AUTO, Pacing.FAST, 20),
        t("pod-interview", Genre.PODCAST, "Interview highlights", "Interview highlights: the clearest answers, lower-third names, clean captions.", EditStyle.AUTO, Pacing.BALANCED, 90),

        t("edu-explainer", Genre.EDUCATION, "Explainer", "Clear explainer: keep each step in order, readable captions, short titles for each step.", EditStyle.AUTO, Pacing.CALM, 90),
        t("edu-tips", Genre.EDUCATION, "3 quick tips", "Three quick tips, each with a numbered title, fast and to the point.", EditStyle.AUTO, Pacing.FAST, 30),
        t("edu-tutorial", Genre.EDUCATION, "Tutorial", "Step-by-step tutorial: keep every step, slow down the important parts, captions on.", EditStyle.AUTO, Pacing.CALM, 120),

        t("promo-product", Genre.PROMO, "Product showcase", "Clean product showcase: close-ups, bright look, bold title, call to action at the end.", EditStyle.LIFESTYLE, Pacing.BALANCED, 20),
        t("promo-launch", Genre.PROMO, "Launch teaser", "Teaser for a launch: mystery first, fast reveal, big title card and the date at the end.", EditStyle.CINEMATIC, Pacing.FAST, 15),
        t("promo-shop", Genre.PROMO, "Shop / café reel", "Inviting reel of my shop: warm colours, the best details, location as a small title.", EditStyle.LIFESTYLE, Pacing.BALANCED, 30),

        t("social-trend", Genre.SOCIAL, "Trend edit", "Trendy edit cut tightly on the beat, flash transitions, bold captions.", EditStyle.AUTO, Pacing.FAST, 15),
        t("social-before", Genre.SOCIAL, "Before & after", "Before and after: show the before, a quick transition, then the after with a title.", EditStyle.AUTO, Pacing.FAST, 15),
        t("social-pov", Genre.SOCIAL, "POV", "POV-style reel: first-person shots, text at the top describing the moment, relaxed pace.", EditStyle.VLOG, Pacing.BALANCED, 20),
        t("social-friends", Genre.SOCIAL, "Friends montage", "Montage of the best moments with friends: laughs first, upbeat music, fun stickers.", EditStyle.LIFESTYLE, Pacing.FAST, 30),
    )

    fun byId(id: String, custom: List<PromptTemplate> = emptyList()): PromptTemplate? =
        (custom + BUILT_IN).firstOrNull { it.id == id }

    /** Templates matching [query] (title, words or genre) in [genre], custom ones first. */
    fun search(query: String, genre: Genre? = null, custom: List<PromptTemplate> = emptyList()): List<PromptTemplate> {
        val words = query.lowercase(Locale.ROOT).split(Regex("\\s+")).filter { it.isNotBlank() }
        return (custom + BUILT_IN)
            .filter { genre == null || it.genre == genre }
            .filter { t ->
                val text = (t.title + " " + t.prompt + " " + t.genre.label).lowercase(Locale.ROOT)
                words.all { it in text }
            }
    }

    // ---- Storage of the user's own templates, favourites and recents (one string each) ----

    private const val SEP = '\u001F'
    private const val ROW = '\u001E'

    fun encodeCustom(list: List<PromptTemplate>): String = list.joinToString(ROW.toString()) {
        listOf(it.id, it.genre.name, it.title, it.prompt, it.style.name, it.pacing.name, it.seconds?.toString() ?: "").joinToString(SEP.toString())
    }

    fun decodeCustom(s: String?): List<PromptTemplate> = s.orEmpty().split(ROW).mapNotNull { row ->
        val p = row.split(SEP)
        if (p.size < 7 || p[3].isBlank()) return@mapNotNull null
        PromptTemplate(
            id = p[0],
            genre = Genre.entries.firstOrNull { it.name == p[1] } ?: Genre.SOCIAL,
            title = p[2].ifBlank { p[3].take(30) },
            prompt = p[3],
            style = EditStyle.entries.firstOrNull { it.name == p[4] } ?: EditStyle.AUTO,
            pacing = Pacing.entries.firstOrNull { it.name == p[5] } ?: Pacing.BALANCED,
            seconds = p[6].toIntOrNull(),
            custom = true,
        )
    }

    /** Most recent first, no duplicates, at most [max]. */
    fun pushRecent(recents: List<String>, id: String, max: Int = 10): List<String> = (listOf(id) + recents.filter { it != id }).take(max)
}
