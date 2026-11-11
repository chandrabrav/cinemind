package com.cinemind.feature.ai.llm.ondevice

/** What the vision model saw in one frame. Scores are 1..10. */
data class ShotInsight(
    val subject: String,
    val scene: String,
    val shot: ShotSize,
    val people: Int,
    val quality: Int,
    val interest: Int,
)

enum class ShotSize { WIDE, MEDIUM, CLOSE, UNKNOWN }

/** Structured editing choices the language model extracted from a brief (null = not stated). */
data class BriefAnswer(
    val style: String?,
    val pacing: String?,
    val targetSeconds: Int?,
    val order: String?,
    val transition: String?,
    val strictQuality: Boolean?,
)

/** Prompts and tolerant parsing of the model's JSON replies (pure; unit-tested). */
object Prompts {

    const val FRAME =
        "You are helping edit a short social video. Look at this frame and reply with ONLY one JSON object, " +
            "no other text, using exactly these keys: " +
            "{\"subject\": \"2-6 words: the main thing in the frame\", " +
            "\"scene\": \"1-3 words, e.g. beach, mountains, gym, city street, indoor, food\", " +
            "\"shot\": \"wide\" or \"medium\" or \"close\", " +
            "\"people\": number of people visible, " +
            "\"quality\": 1-10 (10 = sharp, well lit, well framed), " +
            "\"interest\": 1-10 (how eye-catching this moment is for a reel)}"

    fun brief(userText: String): String =
        "Turn this video editing request into ONLY one JSON object, no other text, with keys: " +
            "\"style\" (one of auto, cinematic, travel, gym, lifestyle, vlog), " +
            "\"pacing\" (calm, balanced or fast), " +
            "\"target_seconds\" (number, or null if not mentioned), " +
            "\"order\" (chronological or hook_first), " +
            "\"transition\" (cut, fade, zoom_in, zoom_out, slide_left, slide_right or slide_up), " +
            "\"strict_quality\" (true if the user wants only sharp/steady/best shots). " +
            "Use null for anything the request doesn't imply. Request: \"" + userText.replace("\"", "'") + "\""

    /** Extracts the first {...} block; tolerates code fences and chatter around it. */
    fun jsonBlock(reply: String): String? {
        val start = reply.indexOf('{')
        val end = reply.lastIndexOf('}')
        return if (start >= 0 && end > start) reply.substring(start, end + 1) else null
    }

    private fun string(json: String, key: String): String? =
        Regex("\"" + Regex.escape(key) + "\"\\s*:\\s*\"([^\"]*)\"").find(json)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }

    private fun number(json: String, key: String): Double? =
        Regex("\"" + Regex.escape(key) + "\"\\s*:\\s*\"?(-?\\d+(?:\\.\\d+)?)").find(json)?.groupValues?.get(1)?.toDoubleOrNull()

    private fun bool(json: String, key: String): Boolean? =
        Regex("\"" + Regex.escape(key) + "\"\\s*:\\s*\"?(true|false)", RegexOption.IGNORE_CASE).find(json)
            ?.groupValues?.get(1)?.lowercase()?.toBooleanStrictOrNull()

    fun parseInsight(reply: String): ShotInsight? {
        val json = jsonBlock(reply) ?: return null
        val subject = string(json, "subject") ?: return null
        val shot = when (string(json, "shot")?.lowercase()) {
            "wide", "establishing", "long" -> ShotSize.WIDE
            "medium", "mid" -> ShotSize.MEDIUM
            "close", "close-up", "closeup", "close up" -> ShotSize.CLOSE
            else -> ShotSize.UNKNOWN
        }
        fun score(key: String) = (number(json, key)?.toInt() ?: 5).coerceIn(1, 10)
        return ShotInsight(
            subject = subject.take(60),
            scene = (string(json, "scene") ?: "unknown").take(30).lowercase(),
            shot = shot,
            people = (number(json, "people")?.toInt() ?: 0).coerceIn(0, 50),
            quality = score("quality"),
            interest = score("interest"),
        )
    }

    fun parseBrief(reply: String): BriefAnswer? {
        val json = jsonBlock(reply) ?: return null
        fun pick(key: String, allowed: Set<String>) = string(json, key)?.lowercase()?.replace(' ', '_')?.takeIf { it in allowed }
        return BriefAnswer(
            style = pick("style", setOf("auto", "cinematic", "travel", "gym", "lifestyle", "vlog")),
            pacing = pick("pacing", setOf("calm", "balanced", "fast")),
            targetSeconds = number(json, "target_seconds")?.toInt()?.takeIf { it in 5..600 },
            order = pick("order", setOf("chronological", "hook_first")),
            transition = pick("transition", setOf("cut", "fade", "zoom_in", "zoom_out", "slide_left", "slide_right", "slide_up")),
            strictQuality = bool(json, "strict_quality"),
        )
    }

    /** Word overlap 0..1 between two short descriptions (to spot repeated scenes). */
    private val STOP_WORDS = setOf("the", "and", "over", "with", "from", "into", "near", "for", "while", "some", "this", "that")

    fun similarity(a: String, b: String): Float {
        fun words(s: String) = s.lowercase().split(Regex("[^a-z0-9]+")).filter { it.length > 2 && it !in STOP_WORDS }.toSet()
        val wa = words(a)
        val wb = words(b)
        if (wa.isEmpty() || wb.isEmpty()) return 0f
        return (wa intersect wb).size.toFloat() / (wa union wb).size
    }
}
