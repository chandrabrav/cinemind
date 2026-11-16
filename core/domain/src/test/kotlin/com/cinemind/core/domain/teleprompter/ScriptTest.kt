package com.cinemind.core.domain.teleprompter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScriptTest {

    private val text = """
        Hello everyone, welcome back to my channel.
        Today we are going to Manali!

        First stop — the old town market, famous for its woollen shawls.
    """.trimIndent()

    @Test
    fun splitsSegmentsAndWords() {
        val s = Script(text)
        assertEquals(2, s.segments.size)
        assertEquals("hello", s.words.first().key)
        assertEquals("channel", s.words[6].key)
        assertEquals(1, s.words.last().segment)
        // The dash is kept with "stop" for display, not a word of its own.
        assertTrue(s.words.any { it.text == "stop —" })
        assertEquals(s.segments[1], s.segmentOf(s.segments[1].firstWord))
    }

    @Test
    fun followsReadingWordByWord() {
        val s = Script(text)
        val f = ScriptFollower(s)
        assertEquals(1, f.hear("hello"))
        assertEquals(2, f.hear("hello everyone"))
        assertEquals(5, f.hear("hello everyone welcome back to"))
    }

    @Test
    fun toleratesMisheardAndSkippedWords() {
        val s = Script(text)
        val f = ScriptFollower(s)
        f.hear("hello everyone welcome back to my")
        // "chanel" misheard; "today" skipped.
        val p = f.hear("hello everyone welcome back to my chanel we are going")
        assertEquals(s.words.indexOfFirst { it.key == "going" } + 1, p)
    }

    @Test
    fun doesNotJumpFarOnACommonWord() {
        val s = Script(text)
        val f = ScriptFollower(s)
        f.hear("hello")
        // "the" appears much later ("the old town"): one short word mustn't move the prompter there.
        assertEquals(1, f.hear("hello the"))
    }

    @Test
    fun handlesDevanagariAndHinglish() {
        val s = Script("नमस्ते दोस्तों आज हम मनाली जा रहे हैं\n\nBhai yeh jagah sach mein bahut beautiful hai")
        val f = ScriptFollower(s)
        assertEquals(3, f.hear("नमस्ते दोस्तों आज"))
        f.reset(s.segments[1].firstWord)
        assertEquals(s.segments[1].firstWord + 5, f.hear("bhai yeh jagah sach mein"))
    }

    @Test
    fun autoScrollAndDurations() {
        assertEquals(10, AutoScroll.positionAt(0, 4_000, 150, 100))
        assertEquals(100, AutoScroll.positionAt(90, 60_000, 150, 100))
        assertEquals(24_000L, Script.durationMs(60, 150))
    }

    @Test
    fun similarityPrefersCloseSpellings() {
        assertEquals(1.0, ScriptFollower.similarity("manali", "manali"), 0.0)
        assertTrue(ScriptFollower.similarity("chanel", "channel") > 0.8)
        assertTrue(ScriptFollower.similarity("beauti", "beautiful") >= 0.85)
        assertTrue(ScriptFollower.similarity("the", "town") < 0.6)
    }
}
