package com.cinemind.core.domain.assistant

import com.cinemind.core.domain.editing.SubjectLook
import java.util.Locale

/**
 * Plain-language commands the app understands instantly, without any AI model (so they work
 * offline and never cost anything): background and cutout looks, and text behind the person.
 * Anything else goes to the assistant. Returns null when the request isn't one of these.
 */
object QuickCommands {

    fun parse(request: String, clipCount: Int, playheadSeconds: Double): AssistantPlan? {
        val raw = request.trim()
        if (raw.isEmpty()) return null
        val s = raw.lowercase(Locale.ROOT)
        val clip = clipNumber(s, clipCount)
        val where = if (clip == 0) "every clip" else "clip $clip"

        textBehind(raw, s)?.let { words ->
            return AssistantPlan(
                "I'll cut you out and put “$words” behind you.",
                listOf(AssistantAction(AssistantActionType.TEXT_BEHIND, text = words, atSeconds = playheadSeconds)),
            )
        }

        val look = when {
            has(s, "remove", "erase", "delete", "no", "transparent") && has(s, "background", "bg", "backdrop") -> SubjectLook.CUTOUT
            has(s, "blur") && has(s, "background", "bg", "behind", "backdrop") -> SubjectLook.BLUR
            has(s, "portrait mode", "bokeh") -> SubjectLook.BLUR
            has(s, "colour pop", "color pop", "colour splash", "color splash") -> SubjectLook.MONO
            has(s, "black and white", "black & white", "b&w", "grayscale", "greyscale") && has(s, "background", "bg") -> SubjectLook.MONO
            has(s, "spotlight") || (has(s, "darken", "dim", "dark") && has(s, "background", "bg")) -> SubjectLook.SPOTLIGHT
            has(s, "outline", "sticker") && has(s, "me", "person", "subject", "him", "her", "around") -> SubjectLook.OUTLINE
            has(s, "remove", "undo", "turn off", "clear") && has(s, "cutout", "background effect", "mask") -> SubjectLook.NONE
            else -> null
        }
        if (look != null) {
            return AssistantPlan(
                if (look == SubjectLook.NONE) "I'll turn the background effect off on $where." else "I'll cut out the subject on the phone and apply ${look.label.lowercase(Locale.ROOT)} on $where.",
                listOf(AssistantAction(AssistantActionType.SUBJECT_LOOK, name = look.name, clip = clip)),
            )
        }

        if (has(s, "cut out", "cutout", "mask", "segment", "isolate") && has(s, "me", "person", "subject", "him", "her", "people", "man", "woman", "dog", "cat", "product")) {
            return AssistantPlan(
                "I'll cut out the subject of $where on the phone. Then pick a background look or add text behind.",
                listOf(AssistantAction(AssistantActionType.CUT_OUT_SUBJECT, clip = clip)),
            )
        }
        return null
    }

    /** "put GOA behind me", "text 'Day 1' behind the person", "add SUMMER behind her". */
    private fun textBehind(raw: String, s: String): String? {
        if (!s.contains("behind")) return null
        if (!has(s, "text", "title", "word", "put", "add", "write")) return null
        Regex("[\"“”'‘’](.+?)[\"“”'‘’]").find(raw)?.let { return it.groupValues[1].trim().take(40).ifBlank { null } }
        val m = Regex("(?i)(?:put|add|write|place)\\s+(?:the\\s+)?(?:text|title|word|words)?\\s*(.+?)\\s+behind").find(raw)
        val words = m?.groupValues?.get(1)?.trim()?.trim(':', '-', ',')
        return words?.takeIf { it.isNotBlank() && it.lowercase(Locale.ROOT) !in listOf("text", "a title", "some text", "title") }?.take(40)
    }

    /** "clip 2" / "second clip" → 2; "all clips" / nothing → 0 (every clip). */
    private fun clipNumber(s: String, clipCount: Int): Int {
        Regex("clip\\s*(\\d+)").find(s)?.groupValues?.get(1)?.toIntOrNull()?.let { return it.coerceIn(0, clipCount) }
        val ordinals = listOf("first", "second", "third", "fourth", "fifth")
        ordinals.forEachIndexed { i, w -> if (s.contains("$w clip")) return (i + 1).coerceAtMost(clipCount) }
        return 0
    }

    private fun has(s: String, vararg words: String): Boolean = words.any { w ->
        if (w.contains(' ') || !w.all { it.isLetter() }) s.contains(w) else Regex("\\b${Regex.escape(w)}\\b").containsMatchIn(s)
    }
}
