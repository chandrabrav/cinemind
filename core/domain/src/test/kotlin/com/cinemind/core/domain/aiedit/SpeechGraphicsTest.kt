package com.cinemind.core.domain.aiedit

import com.cinemind.core.domain.captions.Caption
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechGraphicsTest {

    private fun cap(start: Double, end: Double, text: String) =
        Caption(projectId = "p", startUs = (start * 1e6).toLong(), endUs = (end * 1e6).toLong(), text = text)

    private val talk = listOf(
        cap(0.0, 2.0, "Bhai aaj main aapko batata hoon"),
        cap(2.0, 4.0, "how to edit videos with AI"),
        cap(4.5, 6.5, "it takes just 30 seconds"),
        cap(7.0, 9.0, "and the captions follow your voice"),
        cap(9.5, 12.0, "subscribe for more editing tricks"),
    )

    @Test
    fun hookComesFromWhatIsSaidNotTheProjectName() {
        val hook = SpeechGraphics.hook(talk, setOf("edit"))
        assertTrue(hook, hook == "EDIT VIDEOS" || hook!!.contains("VIDEOS") || hook.contains("30"))
        assertNull(SpeechGraphics.hook(listOf(cap(0.0, 1.0, "haan to bas")), emptySet()))
    }

    @Test
    fun planPutsHookBehindSpeakerAndCalloutsAtTheirWords() {
        val layers = SpeechGraphics.plan("p", talk, 12_000_000, "make an edit tutorial", behindSpeaker = true)
        val hook = layers.first()
        assertEquals(0L, hook.startUs)
        assertTrue(hook.styleObj.behindSubject)
        val callouts = layers.drop(1)
        assertTrue("expected callouts", callouts.isNotEmpty())
        // Never during the hook, at least 2.5 s apart, inside the video, in different places.
        assertTrue(callouts.all { it.startUs >= hook.endUs })
        callouts.zipWithNext().forEach { (a, b) -> assertTrue(b.startUs - a.startUs >= SpeechGraphics.MIN_GAP_US) }
        assertTrue(callouts.all { it.endUs <= 12_000_000 })
        if (callouts.size > 1) assertTrue(callouts[0].x != callouts[1].x)
        // "30" is said at 4.5–6.5 s and numbers win.
        assertTrue(callouts.map { it.text }.toString(), callouts.any { it.text == "30" })
    }

    @Test
    fun noSpeechNoGraphicsAndGenericNames() {
        assertTrue(SpeechGraphics.plan("p", emptyList(), 10_000_000, "", true).isEmpty())
        assertTrue(SpeechGraphics.isGenericName("Project 19 Nov, 18:03"))
        assertTrue(SpeechGraphics.isGenericName("VID_20261119_181735"))
        assertTrue(SpeechGraphics.isGenericName(""))
        assertFalse(SpeechGraphics.isGenericName("Goa Trip"))
        assertFalse(SpeechGraphics.isGenericName("Project Alpha"))
        assertTrue(SpeechGraphics.isGenericName("Project 3"))
        assertEquals(0, SpeechGraphics.score("hai", emptySet()))
        assertTrue(SpeechGraphics.score("2026", emptySet()) > SpeechGraphics.score("videos", emptySet()))
    }
}
