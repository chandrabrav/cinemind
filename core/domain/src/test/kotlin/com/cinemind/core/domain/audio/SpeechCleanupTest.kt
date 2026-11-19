package com.cinemind.core.domain.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Real failures from the owner's test videos (travel clip and Hinglish talking clip). */
class SpeechCleanupTest {

    @Test
    fun soundLabelsAreNotSpeech() {
        listOf("[MUSIC]", "(crowd cheering)", "[BLANK_AUDIO]", "BLANK_AUDIO", "Mooing", "Ahhh!", "♪ ♪", "*laughs*", "Music", "Thanks for watching!")
            .forEach { assertNull(it, SpeechCleanup.clean(it, "en")) }
        assertEquals("let's go", SpeechCleanup.clean("(wind) let's go", "en"))
    }

    @Test
    fun loopsAreDropped() {
        assertNull(SpeechCleanup.clean("LISHAASHAASHAASHAASHAASHAASHAASHAASHAAS", "hi"))
        assertNull(SpeechCleanup.clean("aapavaavaavaavaavaavaavaa", "hi"))
        assertNull(SpeechCleanup.clean("lo lo lo lo", "hi"))
        assertNull(SpeechCleanup.clean("lig lig lig lig", "hi"))
        assertNull(SpeechCleanup.clean("I want to I want to I want to", "en"))
        assertTrue(SpeechCleanup.looped("le le le le le le"))
        assertFalse(SpeechCleanup.looped("It takes just 30 seconds to edit this video"))
        assertEquals("We got 1000000 views", SpeechCleanup.clean("We got 1000000 views", "en"))
    }

    @Test
    fun wrongScriptIsDropped() {
        assertNull(SpeechCleanup.clean("فکوسکروں とき", "hi"))
        assertNull(SpeechCleanup.clean("پہلے کیا؟", "hi"))
        assertEquals("नमस्ते दोस्तों", SpeechCleanup.clean("नमस्ते दोस्तों", "hi"))
        assertEquals("in English as well.", SpeechCleanup.clean("in English as well.", "hi"))
        assertEquals("All of good side", SpeechCleanup.clean("All of good side", "en"))
    }

    @Test
    fun detectionStaysAmongLikelyLanguages() {
        val l = WhisperTokens.likelyLanguages(listOf("en-IN"))
        assertEquals(setOf("en", "hi"), l)
        assertTrue("ta" in WhisperTokens.likelyLanguages(listOf("ta_IN")))
    }
}
