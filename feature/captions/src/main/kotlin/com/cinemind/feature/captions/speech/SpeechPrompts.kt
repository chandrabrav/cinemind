package com.cinemind.feature.captions.speech

import com.cinemind.core.domain.captions.CaptionScript
import com.cinemind.core.domain.captions.SpokenLanguage
import com.cinemind.core.domain.captions.Transliterator

/** One recognised stretch of speech. */
data class Transcript(val language: String, val text: String, val uncertain: Boolean)

/**
 * Prompts and answer parsing for speech-to-text with the on-device model. Transcription
 * (what was said), transliteration (which alphabet) and translation (which language) are kept
 * separate: the transcription prompt never translates. Pure; unit-tested.
 */
object SpeechPrompts {

    const val NO_SPEECH = "NO_SPEECH"
    const val UNSURE_MARK = "[?]"

    fun transcribe(language: SpokenLanguage, script: CaptionScript): String = buildString {
        appendLine("You are a careful speech-to-text transcriber. Transcribe the speech in this audio exactly as spoken, word for word.")
        appendLine("Rules:")
        appendLine("- Do not translate. Do not summarise. Do not add words that were not spoken. Do not describe sounds.")
        appendLine("- " + scriptRule(script))
        appendLine("- Keep the speaker's own mix of languages. If they switch between Hindi and English, keep each word in the language it was spoken in.")
        appendLine("- If a word is unclear, write your best guess followed by $UNSURE_MARK.")
        appendLine("- If there is no clear speech (silence, only music, only noise), answer exactly: $NO_SPEECH")
        if (language != SpokenLanguage.AUTO) appendLine("- The speaker is expected to speak ${language.label}.")
        appendLine("Answer in exactly this format:")
        appendLine("LANG: <language code: en, hi, hi-en (Hindi-English mix), pa, bn, ta, te, ur, mr, or another ISO 639-1 code>")
        append("TEXT: <the transcript>")
    }

    private fun scriptRule(script: CaptionScript): String = when (script) {
        CaptionScript.NATIVE ->
            "Write each language in its own script: Hindi and Marathi in Devanagari, Punjabi in Gurmukhi, Bengali in Bengali script, " +
                "Tamil in Tamil script, Telugu in Telugu script, Urdu in Urdu script. English words stay in English letters."
        CaptionScript.ROMAN, CaptionScript.ENGLISH ->
            "Write everything in Latin (Roman) letters. Spell Hindi and other Indian-language words phonetically, the way people " +
                "write them in chat (for example: kya haal hai). Keep English words in normal English spelling."
        CaptionScript.HINGLISH ->
            "Write everything in Latin (Roman) letters, Hinglish style: Hindi words spelled as people write them in chat, English words " +
                "in normal English spelling, exactly as spoken. Example: if the speaker says \"Bhai, yeh jagah sach mein bahut beautiful hai!\" " +
                "write exactly that, never \"Brother, this place is truly very beautiful.\""
    }

    /** Translation is a separate, explicit step on finished text. */
    fun translateToEnglish(text: String): String =
        "Translate this caption into natural, short English. Keep names and places as they are. " +
            "Answer with only the translation, nothing else.\nCaption: $text"

    /** Parses the model's answer; null when it heard no speech. */
    fun parse(raw: String, requested: CaptionScript, hint: SpokenLanguage): Transcript? {
        val cleaned = raw.trim().removeSurrounding("```").trim()
        if (cleaned.isEmpty() || cleaned.contains(NO_SPEECH)) return null
        val lines = cleaned.lines()
        val langLine = lines.firstOrNull { it.trim().startsWith("LANG:", ignoreCase = true) }
        val textIndex = lines.indexOfFirst { it.trim().startsWith("TEXT:", ignoreCase = true) }
        var text = if (textIndex >= 0) {
            (listOf(lines[textIndex].trim().substringAfter(':')) + lines.drop(textIndex + 1)).joinToString(" ")
        } else {
            lines.filterNot { it.trim().startsWith("LANG:", ignoreCase = true) }.joinToString(" ")
        }
        text = text.trim().removeSurrounding("\"").removeSurrounding("“", "”").trim()
        val uncertain = text.contains(UNSURE_MARK)
        text = text.replace(UNSURE_MARK, "").replace(Regex("\\s+"), " ").trim()
        if (text.isEmpty()) return null
        if ((requested == CaptionScript.ROMAN || requested == CaptionScript.HINGLISH || requested == CaptionScript.ENGLISH) &&
            Transliterator.containsDevanagari(text)
        ) {
            // The model answered in Devanagari although Roman letters were asked for: transliterate (not translate).
            text = Transliterator.devanagariToRoman(text, casual = true)
        }
        val language = langLine?.substringAfter(':')?.trim()?.lowercase()?.takeWhile { it.isLetter() || it == '-' }
            ?.takeIf { it.isNotEmpty() } ?: hint.code
        return Transcript(language, collapseRepeats(text), uncertain || looksRepetitive(text))
    }

    /** Models sometimes loop ("the the the ..."); collapse a phrase repeated 3+ times. */
    internal fun collapseRepeats(text: String): String {
        val words = text.split(' ')
        for (n in 1..4) {
            if (words.size < n * 3) continue
            val out = mutableListOf<String>()
            var i = 0
            while (i < words.size) {
                out += words[i]
                if (out.size >= n * 3) {
                    val a = out.takeLast(n)
                    val b = out.subList(out.size - 2 * n, out.size - n)
                    val c = out.subList(out.size - 3 * n, out.size - 2 * n)
                    if (a == b && b == c) repeat(n) { out.removeAt(out.lastIndex) }
                }
                i++
            }
            if (out.size < words.size) return collapseRepeats(out.joinToString(" "))
        }
        return text
    }

    private fun looksRepetitive(text: String): Boolean {
        val words = text.lowercase().split(' ').filter { it.isNotBlank() }
        return words.size >= 8 && words.toSet().size * 3 < words.size
    }
}
