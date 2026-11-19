package com.cinemind.core.domain.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FreesoundTest {

    @Test
    fun searchAsksOnlyForPublishableShortSounds() {
        val url = Freesound.searchUrl("vine boom", page = 2)
        assertTrue(url.startsWith(Freesound.SEARCH + "?query=vine+boom"))
        val decoded = java.net.URLDecoder.decode(url, "UTF-8")
        assertTrue(decoded.contains("license:(\"Creative Commons 0\" OR \"Attribution\")"))
        assertTrue(decoded.contains("duration:[0 TO 12]"))
        assertTrue(url.contains("&page=2"))
        assertFalse("never puts a key in the URL", url.contains("token="))
    }

    @Test
    fun parseKeepsCc0AndCcByOnly() {
        val json = """
            {"count": 3, "next": "https://freesound.org/apiv2/search/?page=2", "previous": null, "results": [
              {"id": 1, "name": "Whoosh fast.wav", "username": "anna", "license": "http://creativecommons.org/publicdomain/zero/1.0/", "duration": 0.8,
               "previews": {"preview-hq-mp3": "https://cdn.freesound.org/previews/0/1_1-hq.mp3", "preview-lq-mp3": "https://cdn.freesound.org/previews/0/1_1-lq.mp3"}, "tags": ["whoosh", "fast"]},
              {"id": 2, "name": "Boom", "username": "ben", "license": "https://creativecommons.org/licenses/by/4.0/", "duration": 2.5,
               "previews": {"preview-hq-mp3": "https://cdn.freesound.org/previews/0/2_2-hq.mp3"}},
              {"id": 3, "name": "Song", "username": "cat", "license": "http://creativecommons.org/licenses/by-nc/3.0/", "duration": 4,
               "previews": {"preview-hq-mp3": "https://cdn.freesound.org/previews/0/3_3-hq.mp3"}}
            ]}
        """.trimIndent()
        val page = Freesound.parse(json)
        assertEquals(listOf(1L, 2L), page.sounds.map { it.id })
        assertTrue(page.hasMore)
        assertEquals("Whoosh fast", page.sounds[0].name)
        assertEquals(SoundLicense.CC0, page.sounds[0].license)
        assertEquals(SoundLicense.CC_BY, page.sounds[1].license)
        assertTrue(page.sounds[1].credit.contains("by ben") && page.sounds[1].credit.endsWith("CC BY"))
    }
}
