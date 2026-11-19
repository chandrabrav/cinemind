package com.cinemind.core.domain.aiedit

import com.cinemind.core.domain.captions.Caption
import com.cinemind.core.domain.editing.ClipTransition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModernEditTest {

    private fun shot(i: Int, t: ClipTransition) = PlanShot(source = 1, fromSeconds = i * 2.0, toSeconds = i * 2.0 + 2, transitionIn = t.name, transitionSeconds = 0.5)

    private val dated = EditPlan(
        shots = listOf(ClipTransition.FADE, ClipTransition.SLIDE_LEFT, ClipTransition.SLIDE_RIGHT, ClipTransition.CUT, ClipTransition.CUT, ClipTransition.SPIN, ClipTransition.FADE)
            .mapIndexed { i, t -> shot(i, t) },
        openingSeconds = 1.5,
        texts = listOf(PlanText("STICKER", "😂", 1.0, 1.0), PlanText("TITLE", "Goa", 0.0, 2.0)),
    )

    @Test
    fun noSlidesSpinsBlackDipsOrStickers() {
        val p = ModernEdit.tidy(dated, "make a travel reel")
        val kinds = p.shots.map { ClipTransition.fromName(it.transitionIn) }
        assertTrue(kinds.toString(), kinds.all { it == ClipTransition.CUT || it == ClipTransition.ZOOM_IN || it == ClipTransition.DISSOLVE })
        // Accents only: never two transitions within two shots of each other.
        val at = kinds.withIndex().filter { it.value != ClipTransition.CUT }.map { it.index }
        at.zipWithNext().forEach { (a, b) -> assertTrue(b - a >= 3) }
        assertEquals(ClipTransition.CUT.name, p.shots.first().transitionIn)
        assertFalse(p.texts.any { it.role == "STICKER" })
        assertTrue(p.openingSeconds <= 0.4)
    }

    @Test
    fun whatTheUserAsksForIsKept() {
        val p = ModernEdit.tidy(dated, "slide transitions and some emoji stickers")
        assertTrue(p.shots.any { it.transitionIn == ClipTransition.SLIDE_LEFT.name })
        assertTrue(p.texts.any { it.role == "STICKER" })
    }

    private fun cap(s: Double, e: Double, t: String) = Caption(projectId = "p", startUs = (s * 1e6).toLong(), endUs = (e * 1e6).toLong(), text = t)

    @Test
    fun onlyTalkingVideosGetTextFromTheSound() {
        val talking = listOf(cap(0.0, 3.0, "today I will show you how"), cap(3.0, 6.0, "to edit videos on your phone"), cap(6.0, 9.0, "in under one minute"))
        assertTrue(SpeechGraphics.isTalking(talking, 12_000_000))
        // A travel clip: two shouted words in 30 seconds.
        assertFalse(SpeechGraphics.isTalking(listOf(cap(4.0, 5.0, "wow"), cap(20.0, 21.0, "look there")), 30_000_000))
        assertTrue(SpeechGraphics.asksForSpeechText("add captions please"))
        assertFalse(SpeechGraphics.asksForSpeechText("cinematic travel reel with music"))
    }
}

class JumpCutsTest {
    private fun clip(id: String, asset: String, at: Long) = com.cinemind.core.domain.model.Clip(
        id = id, trackId = "t", assetId = asset, timelineInUs = at, timelineOutUs = at + 2_000_000, sourceInUs = at, sourceOutUs = at + 2_000_000,
    )

    @Test
    fun sameRecordingAlternatesWideAndClose() {
        val out = JumpCuts.punchIns(listOf(clip("a", "x", 0), clip("b", "x", 2_000_000), clip("c", "x", 4_000_000), clip("d", "y", 6_000_000)))
        val scales = out.map { com.cinemind.core.domain.editing.ClipFraming.decode(it.framing).keys.firstOrNull()?.scale ?: 1f }
        assertEquals(listOf(1f, JumpCuts.PUNCH, 1f, 1f), scales)
    }
}

class ClipSoundTest {
    @Test
    fun volumeAndMuteRoundTripWithoutTouchingPicture() {
        val f = com.cinemind.core.domain.editing.ClipFraming(volume = 0.5f, muted = true)
        assertTrue(f.isNone)
        val back = com.cinemind.core.domain.editing.ClipFraming.decode(f.encode())
        assertEquals(0.5f, back.volume, 0.001f)
        assertTrue(back.muted)
        assertEquals(0f, back.gain, 0f)
        assertEquals("", com.cinemind.core.domain.editing.ClipFraming().encode())
    }
}
