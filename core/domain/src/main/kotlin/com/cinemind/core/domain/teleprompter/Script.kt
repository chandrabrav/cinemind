package com.cinemind.core.domain.teleprompter

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** One word of the script: as written ([text]) and in the form used for matching ([key]). */
data class ScriptWord(val text: String, val key: String, val segment: Int)

/** A part of the script recorded as one take (paragraphs separated by a blank line). */
data class ScriptSegment(val index: Int, val firstWord: Int, val lastWord: Int, val text: String) {
    val wordCount: Int get() = lastWord - firstWord + 1
}

/** A script split into words and segments. Pure; unit-tested. */
class Script(val raw: String) {

    val segments: List<ScriptSegment>
    val words: List<ScriptWord>

    init {
        val paragraphs = raw.replace("\r\n", "\n").split(Regex("""\n\s*\n""")).map { it.trim() }.filter { it.isNotEmpty() }
        val w = ArrayList<ScriptWord>()
        val s = ArrayList<ScriptSegment>()
        paragraphs.forEachIndexed { i, p ->
            val first = w.size
            p.split(Regex("""\s+""")).filter { it.isNotBlank() }.forEach { token ->
                val k = normalise(token)
                if (k.isNotEmpty()) w += ScriptWord(token, k, i) else if (w.size > first) {
                    // Pure punctuation ("—", "…"): keep it attached to the previous word for display.
                    w[w.lastIndex] = w.last().copy(text = w.last().text + " " + token)
                }
            }
            if (w.size > first) s += ScriptSegment(s.size, first, w.size - 1, p)
        }
        words = w
        segments = s
    }

    fun segmentOf(wordIndex: Int): ScriptSegment? = segments.firstOrNull { wordIndex in it.firstWord..it.lastWord }

    companion object {
        /** Lower-case letters and digits of any script (Latin, Devanagari…); everything else dropped. */
        fun normalise(word: String): String = buildString {
            for (ch in word.lowercase()) if (Character.isLetterOrDigit(ch) || Character.getType(ch) == Character.NON_SPACING_MARK.toInt() || Character.getType(ch) == Character.COMBINING_SPACING_MARK.toInt()) append(ch)
        }

        /** Speaking time in ms for [words] words at [wpm] words per minute. */
        fun durationMs(words: Int, wpm: Int): Long = if (wpm <= 0) 0 else words * 60_000L / wpm
    }
}

/**
 * Keeps the teleprompter on the word being spoken, from speech-recognition text (partial results
 * arrive several times a second, often with mistakes). Matches the last few heard words against a
 * window of the script ahead of the current position, tolerating misheard words (small spelling
 * differences, prefixes) and skipped words; never jumps backwards by more than a couple of words,
 * and never jumps far ahead on a single common word ("the", "hai").
 */
class ScriptFollower(private val script: Script, start: Int = 0) {

    /** Index of the next word to be spoken. */
    var position: Int = start.coerceIn(0, script.words.size)
        private set

    fun reset(to: Int) {
        position = to.coerceIn(0, script.words.size)
    }

    /** Feeds recognised text (the whole utterance so far); returns the new [position]. */
    fun hear(text: String): Int {
        val heard = text.split(Regex("""\s+""")).map { Script.normalise(it) }.filter { it.isNotEmpty() }
        if (heard.isEmpty() || script.words.isEmpty()) return position
        val tail = heard.takeLast(TAIL)
        var bestEnd = -1
        var bestScore = 0.0
        val from = max(0, position - BACK)
        val to = min(script.words.size - 1, position + AHEAD)
        for (end in from..to) {
            val score = alignScore(tail, end)
            // Prefer the earliest strong match (closest to where we are), break ties toward the position.
            val adjusted = score - abs(end - position) * DISTANCE_PENALTY
            if (adjusted > bestScore) {
                bestScore = adjusted
                bestEnd = end
            }
        }
        if (bestEnd < 0) return position
        val raw = alignScore(tail, bestEnd)
        // A lone short word is too ambiguous to move far on.
        val jump = bestEnd + 1 - position
        if (raw < MIN_SCORE) return position
        if (jump > 3 && raw < STRONG_SCORE) return position
        if (jump < 0 && jump < -BACK) return position
        position = (bestEnd + 1).coerceIn(0, script.words.size)
        return position
    }

    /** How well the heard words [tail] end at script word [end] (walking backwards, allowing one skip). */
    private fun alignScore(tail: List<String>, end: Int): Double {
        var s = 0.0
        var i = tail.lastIndex
        var j = end
        var skips = 0
        var matched = 0
        while (i >= 0 && j >= 0) {
            val sim = similarity(tail[i], script.words[j].key)
            if (sim >= 0.6) {
                // Longer words are more telling than "a", "the", "hai".
                s += sim * min(1.0, 0.4 + script.words[j].key.length / 6.0)
                matched++
                i--
                j--
            } else if (skips < 2) {
                skips++
                // Either the reader skipped a script word or the recogniser inserted one.
                if (j > 0 && similarity(tail[i], script.words[j - 1].key) >= 0.6) j-- else i--
            } else break
        }
        if (matched == 0) return 0.0
        // The last heard word must match the end word, otherwise this isn't where they are.
        if (similarity(tail.last(), script.words[end].key) < 0.6) return s * 0.3
        return s
    }

    companion object {
        const val TAIL = 5
        const val AHEAD = 30
        const val BACK = 2
        const val MIN_SCORE = 0.55
        const val STRONG_SCORE = 1.6
        const val DISTANCE_PENALTY = 0.01

        /** 1 = same; based on edit distance, with prefix credit (recognisers cut words short). */
        fun similarity(a: String, b: String): Double {
            if (a == b) return 1.0
            if (a.isEmpty() || b.isEmpty()) return 0.0
            if (min(a.length, b.length) >= 4 && (a.startsWith(b) || b.startsWith(a))) return 0.85
            val d = levenshtein(a, b)
            val len = max(a.length, b.length)
            return (1.0 - d.toDouble() / len).coerceAtLeast(0.0)
        }

        private fun levenshtein(a: String, b: String): Int {
            val prev = IntArray(b.length + 1) { it }
            val cur = IntArray(b.length + 1)
            for (i in 1..a.length) {
                cur[0] = i
                for (j in 1..b.length) {
                    cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
                }
                System.arraycopy(cur, 0, prev, 0, cur.size)
            }
            return prev[b.length]
        }
    }
}

/** Time-based scrolling for when voice-follow isn't available or is off. */
object AutoScroll {
    /** Word index reached after [elapsedMs] at [wpm], starting from [startWord]. */
    fun positionAt(startWord: Int, elapsedMs: Long, wpm: Int, total: Int): Int =
        (startWord + (elapsedMs * wpm / 60_000L).toInt()).coerceIn(0, total)
}
