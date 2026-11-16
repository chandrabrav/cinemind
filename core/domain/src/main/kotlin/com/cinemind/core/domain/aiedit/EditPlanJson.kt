package com.cinemind.core.domain.aiedit

import com.cinemind.core.domain.assistant.MiniJson
import com.cinemind.core.domain.audio.SoundEffect
import com.cinemind.core.domain.captions.CaptionPresets
import com.cinemind.core.domain.color.BuiltInLooks
import com.cinemind.core.domain.editing.ClipTransition
import com.cinemind.core.domain.editing.SpeedRampPreset
import com.cinemind.core.domain.text.TextAnim
import com.cinemind.core.domain.text.TextPreset
import java.util.Locale

/** One measured moment of a source (0..1 values; −1 = unknown). */
data class FootageMoment(val seconds: Double, val quality: Float, val brightness: Float, val motion: Float, val seen: String = "")

/** What a planner is told about one source: measurements and descriptions only, never the media. */
data class FootageSource(
    val isVideo: Boolean,
    val seconds: Double,
    val moments: List<FootageMoment>,
    val label: String = "",
)

/** Everything a planning model receives (text only). */
data class PlanRequest(
    val request: String,
    val style: String,
    val pacing: String,
    val targetSeconds: Int?,
    val aspect: String,
    val sources: List<FootageSource>,
    val speech: String,
    val hasMusic: Boolean,
    val filmedOn: String?,
    /** The on-device director's plan, offered as a starting point. */
    val baseline: EditPlan?,
)

/** The text contract between CineMind and any planning model: prompt, footage report, schema, parser. */
object EditPlanJson {

    val systemPrompt: String = """
You are the creative director inside CineMind AI, a phone video editor. You plan a complete, polished edit; CineMind's own engine executes your plan exactly, so use only the names listed here.
You receive the user's request and a footage report: for each source, measured moments (quality, brightness, motion on 0..1, -1 = unknown) and, when available, what an on-device model saw. You never see the video itself, so do not describe things the report does not support.

Plan like an editor, not a trimmer:
- Pick the strongest moments, open with a hook, build a progression and land a satisfying ending. Avoid repeating similar shots. Vary shot length with the pacing; do not cut everything to equal lengths.
- Use transitions selectively (where the scene changes or the mood shifts), never between every shot.
- Colour: one look for the whole video for consistency (look = a key below, intensity 0..1), gentle adjustments, and per-shot exposure (-1..1) to match dark or bright shots. Avoid clipping highlights, crushing shadows and over-saturation.
- Speed: speed (0.25..4) or a ramp (names below) only on moments that benefit (action, reveals). Not on every shot.
- Text: titles, section titles, lower thirds, labels, callouts, end card, stickers (one emoji). Keep words short. Match typography and animation to the style (restrained for cinematic, energetic for gym/social).
- Facts: set claim=true on any text that states a place, a number, a count, a date or a person's name. Only write such text when the user's request or the speech gives it; otherwise do not add it and ask in "questions". Never invent locations, rep counts, statistics or names.
- Captions: choose captionPreset to suit the video when there is speech; you cannot write captions yourself.
- Audio: music volume 0..1 (lower under speech), fades in seconds, original sound volume 0..1; -1 leaves a value unchanged. Sound effects only as accents. If there is no music, suggest a mood in musicMood (the user picks a track they own).
- decisions: short reasons for your main creative choices. summary: one or two sentences.
Names:
ramps: ${SpeedRampPreset.entries.joinToString { it.name }} (or "")
transitions: ${ClipTransition.entries.joinToString { it.name }}
looks: ${BuiltInLooks.ALL.joinToString { it.key }}
text roles: ${TextRole.entries.joinToString { it.name }}
text presets: ${TextPreset.entries.joinToString { it.name }} (or "" for the role's default)
text animations: ${TextAnim.entries.joinToString { it.name }} (or "")
caption presets: ${CaptionPresets.ALL.joinToString { it.id }} (or "")
sounds: ${SoundEffect.entries.joinToString { it.name }}
""".trim()

    fun userPrompt(r: PlanRequest): String = buildString {
        appendLine("Request: " + r.request.ifBlank { "Make a polished edit of this footage." }.take(4_000))
        appendLine("Style: ${r.style}; pacing: ${r.pacing}; target length: ${r.targetSeconds?.let { "$it s" } ?: "your choice (about 60% of the footage, 8-45 s)"}; frame: ${r.aspect}")
        appendLine("Music on the project: ${if (r.hasMusic) "yes" else "no"}")
        r.filmedOn?.let { appendLine("Filmed on (from file metadata): $it") }
        appendLine()
        appendLine("Footage (${r.sources.size} sources; refer to them by number):")
        r.sources.forEachIndexed { i, s ->
            append("Source ${i + 1}: ${if (s.isVideo) "video" else "photo"}, ${f1(s.seconds)} s")
            if (s.label.isNotBlank()) append(" (${s.label.take(40)})")
            appendLine()
            downsample(s.moments, 24).forEach { m ->
                append("  ${f1(m.seconds)} s: quality ${f2(m.quality)}, brightness ${f2(m.brightness)}, motion ${f2(m.motion)}")
                if (m.seen.isNotBlank()) append(", seen: ${m.seen.take(80)}")
                appendLine()
            }
        }
        appendLine()
        if (r.speech.isNotBlank()) appendLine("Speech (from captions, timed on the source project): " + r.speech.take(2_000))
        else appendLine("Speech: none transcribed")
        r.baseline?.let { b ->
            appendLine()
            appendLine("A measurement-based starting cut you may improve or replace:")
            b.shots.forEach { s -> appendLine("  source ${s.source} ${f1(s.fromSeconds)}-${f1(s.toSeconds)} s" + if (s.ramp.isNotEmpty()) " ramp ${s.ramp}" else "") }
            b.grade?.let { appendLine("  look ${it.look}") }
        }
    }

    private fun downsample(m: List<FootageMoment>, max: Int): List<FootageMoment> =
        if (m.size <= max) m else List(max) { i -> m[i * m.size / max] }

    private fun str() = mapOf("type" to "string")
    private fun num() = mapOf("type" to "number")
    private fun enumOf(values: List<String>) = mapOf("type" to "string", "enum" to values)
    private fun obj(props: Map<String, Any>) = mapOf(
        "type" to "object",
        "additionalProperties" to false,
        "required" to props.keys.toList(),
        "properties" to props,
    )
    private fun arr(items: Any) = mapOf("type" to "array", "items" to items)

    /** Strict JSON schema of an [EditPlan]: every field required, names limited to what the engine supports. */
    val schema: Map<String, Any> = obj(
        linkedMapOf(
            "summary" to str(),
            "shots" to arr(obj(linkedMapOf(
                "source" to mapOf("type" to "integer"),
                "fromSeconds" to num(),
                "toSeconds" to num(),
                "speed" to num(),
                "ramp" to enumOf(listOf("") + SpeedRampPreset.entries.map { it.name }),
                "transitionIn" to enumOf(ClipTransition.entries.map { it.name }),
                "transitionSeconds" to num(),
                "exposure" to num(),
                "note" to str(),
            ))),
            "openingSeconds" to num(),
            "endingSeconds" to num(),
            "grade" to obj(linkedMapOf(
                "look" to enumOf(listOf("") + BuiltInLooks.ALL.map { it.key }),
                "intensity" to num(), "exposure" to num(), "contrast" to num(), "saturation" to num(),
                "temperature" to num(), "tint" to num(), "highlights" to num(), "shadows" to num(),
            )),
            "texts" to arr(obj(linkedMapOf(
                "role" to enumOf(TextRole.entries.map { it.name }),
                "text" to str(),
                "atSeconds" to num(),
                "seconds" to num(),
                "preset" to enumOf(listOf("") + TextPreset.entries.map { it.name }),
                "animIn" to enumOf(listOf("") + TextAnim.entries.map { it.name }),
                "animOut" to enumOf(listOf("") + TextAnim.entries.map { it.name }),
                "claim" to mapOf("type" to "boolean"),
            ))),
            "captionPreset" to enumOf(listOf("") + CaptionPresets.ALL.map { it.id }),
            "audio" to obj(linkedMapOf(
                "musicVolume" to num(), "musicFadeInSeconds" to num(), "musicFadeOutSeconds" to num(),
                "originalVolume" to num(), "musicMood" to str(),
            )),
            "sounds" to arr(obj(linkedMapOf("name" to enumOf(SoundEffect.entries.map { it.name }), "atSeconds" to num()))),
            "questions" to arr(str()),
            "decisions" to arr(str()),
        ),
    )

    /**
     * Reads a model's reply into an [EditPlan]. Tolerates text around the JSON and missing fields
     * (defaults); returns null when there is no readable plan at all. Values are checked later by
     * [EditPlanValidator].
     */
    fun parse(text: String): EditPlan? {
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        val root = runCatching { MiniJson.parse(text.substring(start, end + 1)) }.getOrNull() as? Map<*, *> ?: return null
        fun Map<*, *>.s(k: String) = (this[k] as? String).orEmpty()
        fun Map<*, *>.d(k: String, def: Double) = (this[k] as? Number)?.toDouble() ?: def
        fun Map<*, *>.list(k: String) = (this[k] as? List<*>).orEmpty().filterIsInstance<Map<*, *>>()
        val shots = root.list("shots").map { m ->
            PlanShot(
                source = (m["source"] as? Number)?.toInt() ?: 0,
                fromSeconds = m.d("fromSeconds", 0.0),
                toSeconds = m.d("toSeconds", 0.0),
                speed = m.d("speed", 1.0),
                ramp = m.s("ramp"),
                transitionIn = m.s("transitionIn").ifEmpty { ClipTransition.CUT.name },
                transitionSeconds = m.d("transitionSeconds", 0.0),
                exposure = m.d("exposure", 0.0),
                note = m.s("note"),
            )
        }
        val grade = (root["grade"] as? Map<*, *>)?.let { g ->
            PlanGrade(g.s("look"), g.d("intensity", 0.8), g.d("exposure", 0.0), g.d("contrast", 0.0), g.d("saturation", 0.0),
                g.d("temperature", 0.0), g.d("tint", 0.0), g.d("highlights", 0.0), g.d("shadows", 0.0))
        }
        val texts = root.list("texts").map { m ->
            PlanText(m.s("role"), m.s("text"), m.d("atSeconds", 0.0), m.d("seconds", 2.5), m.s("preset"), m.s("animIn"), m.s("animOut"), m["claim"] == true)
        }
        val audio = (root["audio"] as? Map<*, *>)?.let { a ->
            PlanAudio(a.d("musicVolume", -1.0), a.d("musicFadeInSeconds", -1.0), a.d("musicFadeOutSeconds", -1.0), a.d("originalVolume", -1.0), a.s("musicMood"))
        } ?: PlanAudio()
        val sounds = root.list("sounds").map { PlanSound(it.s("name"), it.d("atSeconds", 0.0)) }
        fun strings(k: String) = (root[k] as? List<*>).orEmpty().filterIsInstance<String>()
        return EditPlan(
            summary = root.s("summary"),
            shots = shots,
            openingSeconds = root.d("openingSeconds", 0.0),
            endingSeconds = root.d("endingSeconds", 0.0),
            grade = grade,
            texts = texts,
            captionPreset = root.s("captionPreset"),
            audio = audio,
            sounds = sounds,
            questions = strings("questions"),
            decisions = strings("decisions"),
        )
    }

    private fun f1(v: Double) = String.format(Locale.ROOT, "%.1f", v)
    private fun f2(v: Float) = if (v < 0f) "?" else String.format(Locale.ROOT, "%.2f", v)
}
