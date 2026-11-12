package com.cinemind.core.domain.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MusicDiscoveryTest {

    @Test
    fun themeWordsPickTheMood() {
        assertEquals("Romantic, celebratory", MusicAdvisor.suggest("Riya's shaadi highlights").first().mood)
        assertEquals("Powerful, driving", MusicAdvisor.suggest("Leg day at the gym").first().mood)
        assertEquals("Adventurous, uplifting", MusicAdvisor.suggest("Ladakh road trip").first().mood)
    }

    @Test
    fun unknownThemeGivesASafeDefault() {
        val ideas = MusicAdvisor.suggest("asdf qwerty")
        assertEquals(1, ideas.size)
        assertEquals("Upbeat, modern", ideas.first().mood)
    }

    @Test
    fun tempoNoteMentionsTheCurrentSong() {
        val fits = MusicAdvisor.suggest("gym workout", bpm = 128f).first()
        assertTrue(fits.reason, fits.reason.contains("fits"))
        val slow = MusicAdvisor.suggest("gym workout", bpm = 100f).first()
        assertTrue(slow.reason, slow.reason.contains("would suit better"))
    }

    @Test
    fun sourcesBuildSearchLinks() {
        val pixabay = MusicAdvisor.sources.first { it.name.startsWith("Pixabay") }
        assertEquals("https://pixabay.com/music/search/lofi+chill/", pixabay.searchUrl("lofi chill"))
    }

    @Test
    fun parsesInstagramLinks() {
        val a = InstagramSound.parse("https://www.instagram.com/reels/audio/1234567890123/?igsh=abc")!!
        assertEquals(InstagramSound.Kind.AUDIO_PAGE, a.kind)
        assertEquals("1234567890123", a.audioId)
        assertEquals("https://www.instagram.com/reels/audio/1234567890123/", a.url)
        val r = InstagramSound.parse("instagram.com/reel/C9xYz_Ab12/ check this")!!
        assertEquals(InstagramSound.Kind.REEL, r.kind)
        assertEquals("https://instagram.com/reel/C9xYz_Ab12/", r.url)
        assertNull(InstagramSound.parse("https://youtube.com/watch?v=1"))
    }
}
