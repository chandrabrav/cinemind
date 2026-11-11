package com.cinemind.core.domain.captions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TransliteratorTest {

    @Test fun `hinglish sentence keeps the words, only changes the script`() {
        assertEquals(
            "bhai, yeh jagah sach mein bahut byooteephul hai!",
            Transliterator.devanagariToRoman("भाई, यह जगह सच में बहुत ब्यूटीफुल है!"),
        )
    }

    @Test fun `schwa deletion follows Hindi pronunciation`() {
        assertEquals("karna", Transliterator.devanagariToRoman("करना"))
        assertEquals("samajhna", Transliterator.devanagariToRoman("समझना"))
        assertEquals("dikhta", Transliterator.devanagariToRoman("दिखता"))
        assertEquals("kamal", Transliterator.devanagariToRoman("कमल"))
        assertEquals("zindagi", Transliterator.devanagariToRoman("ज़िंदगी"))
    }

    @Test fun `precomposed and decomposed nukta letters agree`() {
        assertEquals(Transliterator.devanagariToRoman("ज़रा"), Transliterator.devanagariToRoman("ज़रा"))
        assertEquals("zara", Transliterator.devanagariToRoman("ज़रा"))
    }

    @Test fun `latin text, digits and danda`() {
        assertEquals("Hello 2026", Transliterator.devanagariToRoman("Hello 2026"))
        assertEquals("ghar 12.", Transliterator.devanagariToRoman("घर १२।"))
        assertTrue(Transliterator.containsDevanagari("ok है"))
        assertFalse(Transliterator.containsDevanagari("bahut beautiful"))
    }

    @Test fun `anusvara before labial is m`() {
        assertEquals("sambhav", Transliterator.devanagariToRoman("संभव", casual = false))
    }
}

class WordTimesTest {

    @Test fun `encode and decode round trip`() {
        val times = listOf(0L..100L, 100L..250L)
        assertEquals(times, WordTimes.decode(WordTimes.encode(times), 2))
        assertNull(WordTimes.decode(WordTimes.encode(times), 3))
        assertNull(WordTimes.decode("", 2))
        assertNull(WordTimes.decode("1:x 2:3", 2))
    }

    @Test fun `estimate spreads words over the span in order`() {
        val t = WordTimes.estimate("hi there everyone", 1_000_000, 2_000_000)
        assertEquals(3, t.size)
        assertEquals(1_000_000, t.first().first)
        assertEquals(2_000_000, t.last().last)
        for (i in 1 until t.size) assertEquals(t[i - 1].last, t[i].first)
        assertTrue("longer word lasts longer", (t[2].last - t[2].first) > (t[0].last - t[0].first))
    }
}

class CaptionLayoutTest {

    private val style = CaptionStyle(projectId = "p", mode = CaptionMode.SENTENCE.name, karaoke = true, animation = CaptionAnimation.NONE.name)
    private val cap = Caption(id = "c", projectId = "p", startUs = 1_000_000, endUs = 3_000_000, text = "one two three four five six")

    @Test fun `nothing outside captions`() {
        assertNull(CaptionLayout.frameAt(listOf(cap), style, 500_000))
        assertNull(CaptionLayout.frameAt(listOf(cap), style, 3_000_000))
    }

    @Test fun `sentence mode shows all words and highlights the spoken one`() {
        val f = CaptionLayout.frameAt(listOf(cap), style, 1_050_000)!!
        assertEquals(6, f.words.size)
        assertEquals(0, f.activeWord)
        val late = CaptionLayout.frameAt(listOf(cap), style, 2_990_000)!!
        assertEquals(5, late.activeWord)
    }

    @Test fun `phrase mode splits into at most four words and covers the caption`() {
        val chunks = CaptionLayout.chunks(cap, CaptionMode.PHRASE)
        assertEquals(2, chunks.size)
        assertEquals(cap.startUs, chunks.first().startUs)
        assertEquals(cap.endUs, chunks.last().endUs)
        assertTrue(chunks.all { it.toWordExclusive - it.fromWord <= CaptionLayout.PHRASE_MAX_WORDS })
        assertEquals(chunks[0].endUs, chunks[1].startUs)
    }

    @Test fun `phrase breaks after punctuation`() {
        val c = cap.copy(text = "Bhai, yeh jagah beautiful hai")
        val chunks = CaptionLayout.chunks(c, CaptionMode.PHRASE)
        assertEquals(1, chunks[0].toWordExclusive)
    }

    @Test fun `word mode shows one word and uppercase applies`() {
        val f = CaptionLayout.frameAt(listOf(cap), style.copy(mode = CaptionMode.WORD.name, uppercase = true), 2_990_000)!!
        assertEquals(listOf("SIX"), f.words)
        assertEquals(-1, f.activeWord)
    }

    @Test fun `animations enter and exit`() {
        val pop = style.copy(animation = CaptionAnimation.POP.name)
        assertEquals(0f, CaptionLayout.frameAt(listOf(cap), pop, cap.startUs)!!.enter, 0.001f)
        assertEquals(1f, CaptionLayout.frameAt(listOf(cap), pop, 2_000_000)!!.enter, 0.001f)
        assertTrue(CaptionLayout.frameAt(listOf(cap), pop, cap.endUs - 10_000)!!.exit > 0.9f)
        val type = style.copy(animation = CaptionAnimation.TYPEWRITER.name)
        val early = CaptionLayout.frameAt(listOf(cap), type, cap.startUs + 50_000)!!
        assertTrue(early.visibleChars in 0 until early.text.length)
        assertEquals(early.text.length, CaptionLayout.frameAt(listOf(cap), type, 2_900_000)!!.visibleChars)
    }
}

class CaptionEditingTest {

    private val a = Caption(id = "a", projectId = "p", startUs = 0, endUs = 1_000_000, text = "first caption")
    private val b = Caption(id = "b", projectId = "p", startUs = 2_000_000, endUs = 3_000_000, text = "second one here")

    @Test fun `retime is clamped between neighbours and to the minimum length`() {
        val out = CaptionEditing.retime(listOf(a, b), "a", 500_000, 2_500_000, 10_000_000)
        val na = out.first { it.id == "a" }
        assertEquals(500_000, na.startUs)
        assertEquals(2_000_000, na.endUs)
        val tiny = CaptionEditing.retime(listOf(a, b), "b", 2_900_000, 2_900_100, 10_000_000).first { it.id == "b" }
        assertTrue(tiny.durationUs >= CaptionEditing.MIN_CAPTION_US)
        val atEnd = CaptionEditing.retime(listOf(a, b), "b", 2_000_000, 99_000_000, 4_000_000).first { it.id == "b" }
        assertEquals(4_000_000, atEnd.endUs)
    }

    @Test fun `retime rescales stored word times`() {
        val timed = a.copy(wordTimes = WordTimes.encode(listOf(0L..400_000L, 400_000L..1_000_000L)))
        val out = CaptionEditing.retime(listOf(timed), "a", 0, 2_000_000, 5_000_000).single()
        assertEquals(listOf(0L..800_000L, 800_000L..2_000_000L), WordTimes.decode(out.wordTimes, 2))
    }

    @Test fun `edit text resets timings when word count changes`() {
        val timed = a.copy(wordTimes = "0:1 1:2", source = CaptionSource.SPEECH.name, uncertain = true)
        val same = CaptionEditing.editText(listOf(timed), "a", "  First   Caption ").single()
        assertEquals("First Caption", same.text)
        assertEquals("0:1 1:2", same.wordTimes)
        assertFalse(same.uncertain)
        val changed = CaptionEditing.editText(listOf(timed), "a", "just three words").single()
        assertEquals("", changed.wordTimes)
        assertTrue(CaptionEditing.editText(listOf(timed), "a", "   ").isEmpty())
    }

    @Test fun `insert fills the gap, not over another caption`() {
        assertNull(CaptionEditing.insertAt(listOf(a, b), "p", 500_000, 10_000_000))
        val c = CaptionEditing.insertAt(listOf(a, b), "p", 1_200_000, 10_000_000)
        assertNotNull(c)
        assertEquals(2_000_000, c!!.endUs)
        assertNull(CaptionEditing.insertAt(listOf(a, b), "p", 1_950_000, 10_000_000))
    }

    @Test fun `split divides words and time`() {
        val (x, y) = CaptionEditing.split(b, 2_500_000)!!
        assertEquals(b.startUs, x.startUs)
        assertEquals(x.endUs, y.startUs)
        assertEquals(b.endUs, y.endUs)
        assertEquals("second one here", x.text + " " + y.text)
        assertNull(CaptionEditing.split(b.copy(text = "single"), 2_500_000))
    }

    @Test fun `presets carry their id and every preset stays in the safe area`() {
        CaptionPresets.ALL.forEach {
            val s = it.applyTo("p")
            assertEquals(it.id, s.presetId)
            assertEquals("p", s.projectId)
            assertTrue(s.positionY in CaptionStyle.SAFE_TOP..CaptionStyle.SAFE_BOTTOM)
        }
        assertEquals(CaptionPresets.ALL.size, CaptionPresets.ALL.map { it.id }.toSet().size)
    }
}
