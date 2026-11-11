package com.cinemind.core.domain.captions

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.cinemind.core.domain.model.Project
import java.util.UUID

/**
 * One caption on the project's caption layer (schema v5). Times are timeline times, so a caption is
 * a real layer that can be moved and trimmed on its own; it stays editable until export, where it
 * is drawn into the video.
 *
 * [wordTimes] holds one start:end pair per whitespace-separated word of [text] (see [WordTimes]).
 * Empty means "not known" and the words are spread evenly when needed.
 */
@Entity(
    tableName = "captions",
    foreignKeys = [
        ForeignKey(entity = Project::class, parentColumns = ["id"], childColumns = ["projectId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index(value = ["projectId"])],
)
data class Caption(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val projectId: String,
    val startUs: Long,
    val endUs: Long,
    val text: String,
    val wordTimes: String = "",
    /** BCP-47-ish code of what was spoken, e.g. "hi", "en", "hi-en" (Hinglish), "" if unknown. */
    val language: String = "",
    /** How [text] is written: a [CaptionScript] name. */
    val script: String = CaptionScript.ROMAN.name,
    /** Where the caption came from: a [CaptionSource] name. */
    val source: String = CaptionSource.MANUAL.name,
    /** True when recognition was unsure (music, noise, overlapping voices): shown to the user. */
    val uncertain: Boolean = false,
) {
    val durationUs: Long get() = endUs - startUs
}

enum class CaptionSource { SPEECH, MANUAL, TRANSLATION }

/**
 * How captions are written. Transcription, transliteration and translation are different
 * operations: only [ENGLISH] changes the words.
 */
enum class CaptionScript(val label: String, val description: String) {
    NATIVE("Original script", "What was said, in its own script (e.g. Devanagari for Hindi)"),
    ROMAN("Roman script", "What was said, spelled in Latin letters"),
    HINGLISH("Hinglish", "Hindi and English words kept as spoken, in Latin letters"),
    ENGLISH("English translation", "Translated into English (changes the words)"),
    ;

    companion object {
        fun fromName(name: String?): CaptionScript = entries.firstOrNull { it.name == name } ?: ROMAN
    }
}

/** Languages offered for speech recognition. [AUTO] lets the model detect (and switch). */
enum class SpokenLanguage(val code: String, val label: String, val nativeScriptName: String?) {
    AUTO("", "Detect automatically", null),
    ENGLISH("en", "English", null),
    HINDI("hi", "Hindi", "Devanagari"),
    HINGLISH("hi-en", "Hinglish (Hindi + English)", "Devanagari"),
    PUNJABI("pa", "Punjabi", "Gurmukhi"),
    BENGALI("bn", "Bengali", "Bengali"),
    TAMIL("ta", "Tamil", "Tamil"),
    TELUGU("te", "Telugu", "Telugu"),
    URDU("ur", "Urdu", "Urdu (Nastaliq)"),
    MARATHI("mr", "Marathi", "Devanagari"),
    ;

    companion object {
        fun fromCode(code: String?): SpokenLanguage? = entries.firstOrNull { it.code.equals(code, ignoreCase = true) && it != AUTO }
    }
}

/** Encoding of per-word timings: "s0:e0 s1:e1 ..." in microseconds. Pure; unit-tested. */
object WordTimes {

    fun words(text: String): List<String> = text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }

    fun encode(times: List<LongRange>): String = times.joinToString(" ") { "${it.first}:${it.last}" }

    /** Decoded times, or null if missing or not matching [wordCount]. */
    fun decode(encoded: String, wordCount: Int): List<LongRange>? {
        if (encoded.isBlank()) return null
        val parts = encoded.trim().split(' ')
        if (parts.size != wordCount) return null
        return parts.map { p ->
            val s = p.substringBefore(':').toLongOrNull() ?: return null
            val e = p.substringAfter(':', "").toLongOrNull() ?: return null
            s..e
        }
    }

    /**
     * Spreads words over [startUs]..[endUs] in proportion to their length (longer words take longer
     * to say); a word ending a sentence or clause gets a small pause. This is an estimate and is
     * labelled as such in the UI.
     */
    fun estimate(text: String, startUs: Long, endUs: Long): List<LongRange> {
        val words = words(text)
        if (words.isEmpty() || endUs <= startUs) return emptyList()
        val weights = words.map { w ->
            val letters = w.count { it.isLetterOrDigit() }.coerceAtLeast(2).toDouble()
            letters + if (w.last() in ".,!?;:।") 2.0 else 0.0
        }
        val total = weights.sum()
        val span = (endUs - startUs).toDouble()
        var acc = 0.0
        return weights.map { wgt ->
            val s = startUs + (acc / total * span).toLong()
            acc += wgt
            val e = startUs + (acc / total * span).toLong()
            s..e
        }
    }

    /** Real times when stored and consistent, else an estimate. */
    fun resolve(caption: Caption): List<LongRange> {
        val count = words(caption.text).size
        return decode(caption.wordTimes, count) ?: estimate(caption.text, caption.startUs, caption.endUs)
    }
}
