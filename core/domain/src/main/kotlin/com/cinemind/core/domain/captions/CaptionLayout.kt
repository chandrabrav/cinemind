package com.cinemind.core.domain.captions

/**
 * What the caption layer shows at one instant. Drawn identically by the preview and the export,
 * so this is the single source of truth for caption timing. Pure; unit-tested.
 */
data class CaptionFrame(
    val captionId: String,
    /** Words currently on screen (a sentence, a phrase or a single word, depending on the mode). */
    val words: List<String>,
    /** Index into [words] of the word being spoken, or -1. */
    val activeWord: Int,
    /** 0..1 entrance progress (1 = fully in). */
    val enter: Float,
    /** 0..1 exit progress (0 = fully visible, 1 = gone). */
    val exit: Float,
    /** For typewriter: how many characters of the joined text are visible; -1 = all. */
    val visibleChars: Int = -1,
    val uncertain: Boolean = false,
) {
    val text: String get() = words.joinToString(" ")
}

object CaptionLayout {

    const val ENTER_US = 180_000L
    const val EXIT_US = 140_000L
    /** Phrase mode shows at most this many words at once. */
    const val PHRASE_MAX_WORDS = 4
    /** Phrase mode breaks early after a pause this long between words. */
    const val PHRASE_GAP_US = 350_000L

    /** A group of consecutive words shown together. */
    data class Chunk(val fromWord: Int, val toWordExclusive: Int, val startUs: Long, val endUs: Long)

    /** How a caption's words are split into screens for [mode]. */
    fun chunks(caption: Caption, mode: CaptionMode): List<Chunk> {
        val times = WordTimes.resolve(caption)
        if (times.isEmpty()) return emptyList()
        return when (mode) {
            CaptionMode.SENTENCE -> listOf(Chunk(0, times.size, caption.startUs, caption.endUs))
            CaptionMode.WORD -> times.mapIndexed { i, t ->
                Chunk(i, i + 1, t.first, if (i == times.lastIndex) caption.endUs else times[i + 1].first)
            }
            CaptionMode.PHRASE -> {
                val words = WordTimes.words(caption.text)
                val out = mutableListOf<Chunk>()
                var from = 0
                for (i in times.indices) {
                    val count = i - from + 1
                    val last = i == times.lastIndex
                    val clauseEnd = words[i].last() in ".,!?;:।"
                    val gapAfter = !last && times[i + 1].first - times[i].last >= PHRASE_GAP_US
                    if (last || count >= PHRASE_MAX_WORDS || clauseEnd || gapAfter) {
                        val start = if (out.isEmpty()) caption.startUs else times[from].first
                        val end = if (last) caption.endUs else times[i + 1].first
                        out += Chunk(from, i + 1, start, end)
                        from = i + 1
                    }
                }
                out
            }
        }
    }

    /** The frame to draw at timeline time [tUs], or null when no caption is visible. */
    fun frameAt(captions: List<Caption>, style: CaptionStyle, tUs: Long): CaptionFrame? {
        val caption = captions.firstOrNull { tUs >= it.startUs && tUs < it.endUs } ?: return null
        val chunk = chunks(caption, style.modeEnum).firstOrNull { tUs >= it.startUs && tUs < it.endUs }
            ?: return null
        val allWords = WordTimes.words(caption.text)
        val words = allWords.subList(chunk.fromWord, chunk.toWordExclusive)
            .map { if (style.uppercase) it.uppercase() else it }
        val times = WordTimes.resolve(caption)
        val active = if (style.karaoke && style.modeEnum != CaptionMode.WORD) {
            (chunk.fromWord until chunk.toWordExclusive).indexOfFirst { tUs >= times[it].first && tUs < times[it].last }
                .let { if (it < 0) lastStartedBefore(times, chunk, tUs) else it }
        } else {
            -1
        }
        val animation = style.animationEnum
        val enter = if (animation == CaptionAnimation.NONE || animation == CaptionAnimation.TYPEWRITER) 1f
        else ((tUs - chunk.startUs).toFloat() / ENTER_US).coerceIn(0f, 1f)
        // Only the last chunk of a caption animates out; inner chunks swap directly.
        val exit = if (animation == CaptionAnimation.NONE || chunk.endUs != caption.endUs) 0f
        else (1f - (caption.endUs - tUs).toFloat() / EXIT_US).coerceIn(0f, 1f)
        val visible = if (animation == CaptionAnimation.TYPEWRITER) {
            val joined = words.joinToString(" ").length
            // Text types out over the first 40% of the chunk (at most 1.2 s).
            val typeUs = ((chunk.endUs - chunk.startUs) * 0.4).toLong().coerceIn(1, 1_200_000)
            (joined * ((tUs - chunk.startUs).toFloat() / typeUs)).toInt().coerceIn(0, joined)
        } else {
            -1
        }
        return CaptionFrame(caption.id, words, active, enter, exit, visible, caption.uncertain)
    }

    private fun lastStartedBefore(times: List<LongRange>, chunk: Chunk, tUs: Long): Int {
        var best = -1
        for (i in chunk.fromWord until chunk.toWordExclusive) if (times[i].first <= tUs) best = i - chunk.fromWord
        return best
    }
}

/** Editing operations on the caption layer. Pure; unit-tested. */
object CaptionEditing {

    const val MIN_CAPTION_US = 200_000L

    fun sorted(captions: List<Caption>): List<Caption> = captions.sortedBy { it.startUs }

    /**
     * Sets new times, kept inside 0..[totalUs], at least [MIN_CAPTION_US] long, and not overlapping
     * the neighbours (captions are one layer: one caption on screen at a time).
     */
    fun retime(captions: List<Caption>, id: String, startUs: Long, endUs: Long, totalUs: Long): List<Caption> {
        val list = sorted(captions)
        val i = list.indexOfFirst { it.id == id }
        if (i < 0) return list
        val lo = if (i > 0) list[i - 1].endUs else 0L
        val hi = if (i < list.lastIndex) list[i + 1].startUs else totalUs.coerceAtLeast(lo + MIN_CAPTION_US)
        var s = startUs.coerceIn(lo, (hi - MIN_CAPTION_US).coerceAtLeast(lo))
        var e = endUs.coerceIn(s + MIN_CAPTION_US, hi.coerceAtLeast(s + MIN_CAPTION_US))
        if (e > hi) { e = hi; s = (e - MIN_CAPTION_US).coerceAtLeast(lo) }
        val old = list[i]
        val keepWords = old.startUs == s && old.endUs == e
        return list.toMutableList().apply {
            this[i] = old.copy(startUs = s, endUs = e, wordTimes = if (keepWords) old.wordTimes else rescale(old, s, e))
        }
    }

    /** New text; word timings are re-estimated when the number of words changes. */
    fun editText(captions: List<Caption>, id: String, text: String): List<Caption> = captions.map {
        if (it.id != id) it
        else {
            val clean = text.replace(Regex("\\s+"), " ").trim()
            val same = WordTimes.words(clean).size == WordTimes.words(it.text).size
            it.copy(text = clean, wordTimes = if (same) it.wordTimes else "", source = if (it.source == CaptionSource.SPEECH.name) it.source else CaptionSource.MANUAL.name, uncertain = false)
        }
    }.filter { it.text.isNotEmpty() || it.id != id }

    /**
     * Adds an empty caption at [atUs] filling the gap there (up to 2 s), or null if another caption
     * already covers [atUs] or there is no room.
     */
    fun insertAt(captions: List<Caption>, projectId: String, atUs: Long, totalUs: Long, text: String = "New caption"): Caption? {
        val list = sorted(captions)
        if (list.any { atUs >= it.startUs && atUs < it.endUs }) return null
        val next = list.firstOrNull { it.startUs > atUs }?.startUs ?: totalUs
        val end = minOf(atUs + 2_000_000, next)
        if (end - atUs < MIN_CAPTION_US) return null
        return Caption(projectId = projectId, startUs = atUs, endUs = end, text = text, script = CaptionScript.ROMAN.name)
    }

    /** Splits a caption's words in two at the word nearest [atUs]; null if it can't be split. */
    fun split(caption: Caption, atUs: Long): Pair<Caption, Caption>? {
        val words = WordTimes.words(caption.text)
        if (words.size < 2) return null
        val times = WordTimes.resolve(caption)
        val k = times.indexOfFirst { it.first >= atUs }.let { if (it <= 0) words.size / 2 else it }.coerceIn(1, words.size - 1)
        val cut = times[k].first
        val a = caption.copy(endUs = cut, text = words.take(k).joinToString(" "), wordTimes = WordTimes.encode(times.take(k)))
        val b = caption.copy(
            id = java.util.UUID.randomUUID().toString(),
            startUs = cut,
            text = words.drop(k).joinToString(" "),
            wordTimes = WordTimes.encode(times.drop(k)),
        )
        return a to b
    }

    private fun rescale(c: Caption, s: Long, e: Long): String {
        val times = WordTimes.decode(c.wordTimes, WordTimes.words(c.text).size) ?: return ""
        val oldSpan = (c.endUs - c.startUs).coerceAtLeast(1).toDouble()
        val k = (e - s) / oldSpan
        return WordTimes.encode(times.map { (s + (it.first - c.startUs) * k).toLong()..(s + (it.last - c.startUs) * k).toLong() })
    }
}
