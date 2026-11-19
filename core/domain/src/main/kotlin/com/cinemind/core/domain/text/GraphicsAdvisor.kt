package com.cinemind.core.domain.text

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Where a suggested graphic goes. */
enum class GraphicSlot(val label: String) { OPENING_TITLE("Opening title"), LOWER_THIRD("Lower third"), END_CARD("End card"), STICKER("Sticker") }

/** One graphic the editor can add in one tap: words (or an emoji), look and when it shows. */
data class GraphicSuggestion(
    val slot: GraphicSlot,
    val text: String,
    val preset: TextPreset?,
    val startUs: Long,
    val endUs: Long,
    val reason: String,
)

/**
 * Context-aware graphics: reads what the video is about (project name, brief, style words, the
 * date it was filmed) and proposes titles, a lower third, an end card and stickers in a look that
 * suits it. Rule-based and offline; the on-device AI may rewrite the title wording (see
 * [titlePrompt] / [parseTitles]). Everything stays editable as normal text layers.
 */
object GraphicsAdvisor {

    private data class Theme(val key: String, val words: List<String>, val preset: TextPreset, val stickers: List<String>, val endLine: String)

    private val themes = listOf(
        Theme("wedding", listOf("wedding", "shaadi", "sangeet", "mehendi", "haldi", "engagement", "bride", "groom"), TextPreset.CINEMA, listOf("💍", "❤️", "✨"), "Forever starts here"),
        Theme("festival", listOf("diwali", "holi", "festival", "puja", "navratri", "eid", "christmas", "party", "celebration"), TextPreset.NEON, listOf("🪔", "🎆", "🎉"), "Happy celebrations"),
        Theme("travel", listOf("travel", "trip", "mountain", "beach", "road", "himalaya", "goa", "ladakh", "manali", "vacation", "journey"), TextPreset.BOLD_TITLE, listOf("✈️", "📍", "🏔️"), "Until the next trip"),
        Theme("fitness", listOf("gym", "workout", "fitness", "run", "training", "sports", "cricket", "football"), TextPreset.IMPACT, listOf("💪", "🔥", "🏆"), "No days off"),
        Theme("food", listOf("food", "recipe", "cooking", "cafe", "restaurant", "kitchen", "chai"), TextPreset.HANDWRITTEN, listOf("😋", "🍜", "☕"), "Bon appétit"),
        Theme("cinematic", listOf("cinematic", "film", "short film", "story", "moody", "night", "rain"), TextPreset.CINEMA, listOf("🎬", "✨", "🌙"), "Fin"),
        Theme("vlog", listOf("vlog", "day in my life", "routine", "morning", "lifestyle"), TextPreset.MINIMAL, listOf("☀️", "✨", "👋"), "See you tomorrow"),
        Theme("birthday", listOf("birthday", "bday", "cake"), TextPreset.BOLD_TITLE, listOf("🎂", "🎈", "🥳"), "Happy birthday!"),
    )
    private val default = Theme("default", emptyList(), TextPreset.BOLD_TITLE, listOf("✨", "🔥", "❤️"), "Thanks for watching")

    fun themeOf(text: String): String = pick(text).key

    private fun pick(text: String): Theme {
        val t = " " + text.lowercase() + " "
        return themes.maxByOrNull { th -> th.words.count { t.contains(it) } }?.takeIf { th -> th.words.any { t.contains(it) } } ?: default
    }

    /** A short title from the project's name (drops file-ish names like "VID_2024…" or "Project 3"). */
    fun titleFrom(projectName: String, brief: String): String {
        val name = projectName.trim()
        // "Project 19 Nov, 18:03", "VID_2026…": automatic names, never a title.
        val generic = com.cinemind.core.domain.aiedit.SpeechGraphics.isGenericName(name)
        if (!generic) return name.take(40)
        val words = brief.split(Regex("""[^\p{L}\p{N}']+""")).filter { it.length > 2 }.take(3)
        return if (words.isEmpty()) "My story" else words.joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }
    }

    /**
     * Suggestions for a video of [totalUs]. [filmedAt] (epoch ms, null if unknown) gives the lower
     * third's date; [place] a place name if known. Titles never cover more than ~1/3 of a short video.
     */
    fun suggest(projectName: String, brief: String, totalUs: Long, filmedAt: Long? = null, place: String? = null, aiTitle: String? = null): List<GraphicSuggestion> {
        if (totalUs <= 0) return emptyList()
        val th = pick("$projectName $brief")
        val title = aiTitle?.takeIf { it.isNotBlank() } ?: titleFrom(projectName, brief)
        val titleLen = minOf(3_000_000L, totalUs / 3).coerceAtLeast(800_000L).coerceAtMost(totalUs)
        val out = mutableListOf(
            GraphicSuggestion(GraphicSlot.OPENING_TITLE, title, th.preset, 0, titleLen,
                if (th == default) "A clean bold title to open with" else "${th.preset.label} look suits a ${th.key} video"),
        )
        val date = filmedAt?.let { SimpleDateFormat("d MMM yyyy", Locale.ENGLISH).format(Date(it)) }
        val lower = listOfNotNull(place, date).joinToString(" · ")
        if (lower.isNotBlank() && totalUs > 4_000_000) {
            val start = (titleLen + 300_000).coerceAtMost(totalUs - 2_000_000)
            out += GraphicSuggestion(GraphicSlot.LOWER_THIRD, lower, TextPreset.LOWER_THIRD, start, (start + 3_000_000).coerceAtMost(totalUs),
                "Where and when it was filmed")
        }
        if (totalUs > 5_000_000) {
            val endLen = minOf(2_500_000L, totalUs / 4)
            out += GraphicSuggestion(GraphicSlot.END_CARD, th.endLine, th.preset, totalUs - endLen, totalUs, "A closing line for the last shot")
        }
        th.stickers.take(2).forEachIndexed { i, s ->
            val at = (totalUs * (i + 1) / 3).coerceAtMost(totalUs - 1_000_000).coerceAtLeast(0)
            out += GraphicSuggestion(GraphicSlot.STICKER, s, null, at, (at + 1_500_000).coerceAtMost(totalUs), "Fits the ${if (th == default) "mood" else th.key}")
        }
        return out
    }

    /** Prompt asking the on-device AI for short title options. */
    fun titlePrompt(projectName: String, brief: String): String =
        "Write 3 short, catchy on-screen titles (max 5 words each) for a short video. " +
            "Video name: \"$projectName\". What it is about: \"${brief.take(300)}\". " +
            "Reply with one title per line, no numbering, no quotes, no hashtags, no emojis."

    /** Cleans the model's reply into up to 3 titles. */
    fun parseTitles(reply: String): List<String> =
        reply.lines()
            .map { it.trim().trimStart('-', '*', '•', ' ').replace(Regex("""^\d+[.)]\s*"""), "").trim('"', '\'', ' ') }
            .filter { it.isNotBlank() && it.length <= 48 && it.split(' ').size <= 7 && !it.endsWith(":") }
            .distinct()
            .take(3)

    /** Turns a suggestion into a text layer for project [projectId] on top of [existing]. */
    fun toLayer(projectId: String, s: GraphicSuggestion, existing: List<TextLayer>): TextLayer {
        val lane = (existing.maxOfOrNull { it.lane } ?: -1) + 1
        return if (s.slot == GraphicSlot.STICKER) {
            TextLayer(projectId = projectId, startUs = s.startUs, endUs = s.endUs, kind = TextKind.STICKER.name, text = s.text, x = 0.78f, y = 0.22f, size = 0.12f,
                style = TextStyle(shadow = false, animIn = TextAnim.POP).encode(), lane = lane)
        } else {
            val p = s.preset ?: TextPreset.BOLD_TITLE
            val y = when (s.slot) { GraphicSlot.LOWER_THIRD -> 0.8f; GraphicSlot.END_CARD -> 0.5f; else -> p.y }
            val x = if (s.slot == GraphicSlot.LOWER_THIRD) 0.36f else 0.5f
            TextLayer(projectId = projectId, startUs = s.startUs, endUs = s.endUs, text = s.text, x = x, y = y, size = p.size, style = p.style.encode(), lane = lane)
        }
    }
}
