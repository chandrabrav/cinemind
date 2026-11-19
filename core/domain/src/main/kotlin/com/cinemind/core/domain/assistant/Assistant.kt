package com.cinemind.core.domain.assistant

import com.cinemind.core.domain.audio.SoundEffect
import com.cinemind.core.domain.color.BuiltInLooks
import com.cinemind.core.domain.editing.ClipTransition
import com.cinemind.core.domain.editing.SubjectLook
import com.cinemind.core.domain.text.TextPreset

/** What the assistant may do. Everything runs on the phone as a normal, undoable edit. */
enum class AssistantActionType(val label: String) {
    ADD_TITLE("Add a title"),
    ADD_STICKER("Add a sticker"),
    APPLY_LOOK("Apply a colour look"),
    SET_SPEED("Change speed"),
    SET_TRANSITIONS("Set transitions"),
    CUT_TO_BEATS("Cut to the beat"),
    ADD_SOUND("Add a sound effect"),
    FIND_MUSIC("Suggest music"),
    SUBJECT_LOOK("Change the background around the person"),
    CUT_OUT_SUBJECT("Cut out the person / subject"),
    TEXT_BEHIND("Put text behind the person"),
}

/**
 * One proposed edit. Flat on purpose (same fields for every type) so the model's output can be
 * constrained by a strict JSON schema: [name] = preset / look key / transition / sound / emoji,
 * [text] = title words or music mood, [value] = speed, seconds or beats, [clip] = 1-based clip
 * number (0 = all clips), [atSeconds] = when on the timeline.
 */
data class AssistantAction(
    val type: AssistantActionType,
    val name: String = "",
    val text: String = "",
    val value: Double = 0.0,
    val clip: Int = 0,
    val atSeconds: Double = 0.0,
)

data class AssistantPlan(val reply: String, val actions: List<AssistantAction>)

/** What the assistant is told about the project (text only: no pictures, video or sound). */
data class ProjectBrief(
    val name: String,
    val totalSeconds: Double,
    val clips: List<String>,
    val titles: List<String>,
    val captionsSample: String,
    val bpm: Float,
    val hasMusic: Boolean,
)

object Assistant {

    val looks: List<String> get() = BuiltInLooks.ALL.map { it.key }

    /** The text sent with each request. */
    fun describe(p: ProjectBrief): String = buildString {
        appendLine("Project: ${p.name}")
        appendLine("Length: %.1f s, %d clips".format(java.util.Locale.ROOT, p.totalSeconds, p.clips.size))
        p.clips.take(40).forEachIndexed { i, c -> appendLine("Clip ${i + 1}: $c") }
        if (p.titles.isNotEmpty()) appendLine("Titles on screen: " + p.titles.take(10).joinToString(" | "))
        if (p.captionsSample.isNotBlank()) appendLine("Captions (start): " + p.captionsSample.take(400))
        appendLine(if (p.hasMusic) "Music: yes" + (if (p.bpm > 0) ", about ${p.bpm.toInt()} BPM" else "") else "Music: none")
    }

    val systemPrompt: String = """
You are the editing assistant inside CineMind AI, a phone video editor. The user asks for changes to their short video. Reply briefly and propose edits the app can make.
Allowed action types and their fields:
- ADD_TITLE: text = the words (max 6), name = one of ${TextPreset.entries.joinToString { it.name }}, atSeconds = start time.
- ADD_STICKER: name = one emoji, atSeconds = start time.
- APPLY_LOOK: name = one of ${BuiltInLooks.ALL.joinToString { it.key }}; clip = clip number or 0 for every clip.
- SET_SPEED: value = speed 0.25 to 4 (1 = normal); clip = clip number or 0 for every clip.
- SET_TRANSITIONS: name = one of ${ClipTransition.entries.filter { it != ClipTransition.CUT }.joinToString { it.name }} or CUT; value = length in seconds (0.2 to 1.5).
- CUT_TO_BEATS: value = 1, 2 or 4 (cut on every beat, every 2nd, every bar). Only when the project has music.
- ADD_SOUND: name = one of ${SoundEffect.entries.joinToString { it.name }}; atSeconds = when.
- FIND_MUSIC: text = the mood to look for.
- SUBJECT_LOOK: name = one of ${SubjectLook.entries.joinToString { it.name }} (CUTOUT removes the background, BLUR blurs it, MONO keeps only the subject in colour, SPOTLIGHT darkens the background, OUTLINE draws a white outline, NONE removes the effect); clip = clip number or 0 for every clip. The app cuts out the subject on the phone first.
- CUT_OUT_SUBJECT: make the subject cutout only (for masking); clip = clip number or 0 for every clip.
- TEXT_BEHIND: text = the words (max 4, big headline placed behind the person), atSeconds = start time.
Use only these names. Unused fields: empty string or 0. Propose at most 6 actions; propose none if the request is a question. Never claim an edit is done: the user reviews and applies it.
""".trim()

    fun userPrompt(brief: ProjectBrief, request: String): String =
        describe(brief) + "\nRequest: " + request.take(1_000)

    /** JSON schema for the reply (strict: every field required, no extras). */
    val schema: Map<String, Any> = mapOf(
        "type" to "object",
        "additionalProperties" to false,
        "required" to listOf("reply", "actions"),
        "properties" to mapOf(
            "reply" to mapOf("type" to "string"),
            "actions" to mapOf(
                "type" to "array",
                "items" to mapOf(
                    "type" to "object",
                    "additionalProperties" to false,
                    "required" to listOf("type", "name", "text", "value", "clip", "atSeconds"),
                    "properties" to mapOf(
                        "type" to mapOf("type" to "string", "enum" to AssistantActionType.entries.map { it.name }),
                        "name" to mapOf("type" to "string"),
                        "text" to mapOf("type" to "string"),
                        "value" to mapOf("type" to "number"),
                        "clip" to mapOf("type" to "integer"),
                        "atSeconds" to mapOf("type" to "number"),
                    ),
                ),
            ),
        ),
    )

    /**
     * Reads a reply ({"reply": …, "actions": […]}); unknown or invalid actions are dropped, values
     * are clamped. Tolerates text around the JSON (on-device models sometimes add some).
     */
    fun parse(text: String, clipCount: Int): AssistantPlan {
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start < 0 || end <= start) return AssistantPlan(text.trim().take(500), emptyList())
        val root = runCatching { MiniJson.parse(text.substring(start, end + 1)) }.getOrNull() as? Map<*, *>
            ?: return AssistantPlan(text.trim().take(500), emptyList())
        val reply = (root["reply"] as? String).orEmpty().trim()
        val actions = (root["actions"] as? List<*>).orEmpty().mapNotNull { a ->
            val m = a as? Map<*, *> ?: return@mapNotNull null
            val type = AssistantActionType.entries.firstOrNull { it.name == m["type"] } ?: return@mapNotNull null
            val name = (m["name"] as? String).orEmpty().trim()
            val action = AssistantAction(
                type = type,
                name = name,
                text = (m["text"] as? String).orEmpty().trim().take(80),
                value = (m["value"] as? Number)?.toDouble() ?: 0.0,
                clip = ((m["clip"] as? Number)?.toInt() ?: 0).coerceIn(0, clipCount),
                atSeconds = ((m["atSeconds"] as? Number)?.toDouble() ?: 0.0).coerceAtLeast(0.0),
            )
            action.takeIf { valid(it) }
        }.take(6)
        return AssistantPlan(reply.ifBlank { if (actions.isEmpty()) "I couldn't turn that into an edit." else "Here's what I'd do:" }, actions)
    }

    private fun valid(a: AssistantAction): Boolean = when (a.type) {
        AssistantActionType.ADD_TITLE -> a.text.isNotBlank()
        AssistantActionType.ADD_STICKER -> a.name.isNotBlank() && a.name.length <= 8
        AssistantActionType.APPLY_LOOK -> BuiltInLooks.byKey(a.name) != null
        AssistantActionType.SET_SPEED -> a.value in 0.25..4.0
        AssistantActionType.SET_TRANSITIONS -> ClipTransition.entries.any { it.name == a.name }
        AssistantActionType.CUT_TO_BEATS -> a.value.toInt() in listOf(1, 2, 4)
        AssistantActionType.ADD_SOUND -> SoundEffect.entries.any { it.name == a.name }
        AssistantActionType.FIND_MUSIC -> true
        AssistantActionType.SUBJECT_LOOK -> SubjectLook.entries.any { it.name == a.name }
        AssistantActionType.CUT_OUT_SUBJECT -> true
        AssistantActionType.TEXT_BEHIND -> a.text.isNotBlank()
    }

    /** One line for the review list. */
    fun summary(a: AssistantAction): String = when (a.type) {
        AssistantActionType.ADD_TITLE -> "Title “${a.text}” at ${"%.1f".format(java.util.Locale.ROOT, a.atSeconds)} s"
        AssistantActionType.ADD_STICKER -> "Sticker ${a.name} at ${"%.1f".format(java.util.Locale.ROOT, a.atSeconds)} s"
        AssistantActionType.APPLY_LOOK -> "Look “${BuiltInLooks.byKey(a.name)?.name ?: a.name}” on " + if (a.clip == 0) "every clip" else "clip ${a.clip}"
        AssistantActionType.SET_SPEED -> "Speed ${a.value}× on " + if (a.clip == 0) "every clip" else "clip ${a.clip}"
        AssistantActionType.SET_TRANSITIONS -> "${ClipTransition.fromName(a.name).label} between clips (${"%.1f".format(java.util.Locale.ROOT, a.value)} s)"
        AssistantActionType.CUT_TO_BEATS -> "Cut on " + when (a.value.toInt()) { 1 -> "every beat"; 2 -> "every 2nd beat"; else -> "every bar" }
        AssistantActionType.ADD_SOUND -> "Sound “${SoundEffect.entries.first { it.name == a.name }.label}” at ${"%.1f".format(java.util.Locale.ROOT, a.atSeconds)} s"
        AssistantActionType.FIND_MUSIC -> "Find music: ${a.text}"
        AssistantActionType.SUBJECT_LOOK -> "${SubjectLook.from(a.name).label} on " + if (a.clip == 0) "every clip" else "clip ${a.clip}"
        AssistantActionType.CUT_OUT_SUBJECT -> "Cut out the subject of " + if (a.clip == 0) "every clip" else "clip ${a.clip}"
        AssistantActionType.TEXT_BEHIND -> "“${a.text}” behind the person at ${"%.1f".format(java.util.Locale.ROOT, a.atSeconds)} s"
    }
}

/** A small JSON reader (objects, arrays, strings, numbers, true/false/null) for model replies. */
object MiniJson {
    fun parse(s: String): Any? = Reader(s).run { val v = value(); skipWs(); require(i == s.length) { "Trailing text" }; v }

    private class Reader(val s: String) {
        var i = 0
        fun skipWs() { while (i < s.length && s[i].isWhitespace()) i++ }
        fun value(): Any? {
            skipWs()
            require(i < s.length) { "Unexpected end" }
            return when (val c = s[i]) {
                '{' -> obj()
                '[' -> arr()
                '"' -> str()
                't' -> lit("true", true)
                'f' -> lit("false", false)
                'n' -> lit("null", null)
                else -> if (c == '-' || c.isDigit()) num() else error("Unexpected '$c'")
            }
        }
        fun lit(word: String, v: Any?): Any? { require(s.startsWith(word, i)) { "Bad literal" }; i += word.length; return v }
        fun obj(): Map<String, Any?> {
            val m = LinkedHashMap<String, Any?>()
            i++; skipWs()
            if (s[i] == '}') { i++; return m }
            while (true) {
                skipWs()
                val k = str()
                skipWs(); require(s[i] == ':') { "Expected :" }; i++
                m[k] = value()
                skipWs()
                when (s[i]) { ',' -> i++; '}' -> { i++; return m }; else -> error("Expected , or }") }
            }
        }
        fun arr(): List<Any?> {
            val l = ArrayList<Any?>()
            i++; skipWs()
            if (s[i] == ']') { i++; return l }
            while (true) {
                l += value()
                skipWs()
                when (s[i]) { ',' -> i++; ']' -> { i++; return l }; else -> error("Expected , or ]") }
            }
        }
        fun str(): String {
            require(s[i] == '"') { "Expected string" }
            i++
            val b = StringBuilder()
            while (true) {
                val c = s[i++]
                when (c) {
                    '"' -> return b.toString()
                    '\\' -> when (val e = s[i++]) {
                        'n' -> b.append('\n'); 't' -> b.append('\t'); 'r' -> b.append('\r'); 'b' -> b.append('\b'); 'f' -> b.append('\u000C')
                        'u' -> { b.append(s.substring(i, i + 4).toInt(16).toChar()); i += 4 }
                        else -> b.append(e)
                    }
                    else -> b.append(c)
                }
            }
        }
        fun num(): Number {
            val st = i
            while (i < s.length && (s[i].isDigit() || s[i] in "+-.eE")) i++
            val t = s.substring(st, i)
            return if (t.any { it in ".eE" }) t.toDouble() else t.toLong()
        }
    }
}
