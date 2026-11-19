package com.cinemind.core.domain.editing

import com.cinemind.core.domain.assistant.AssistantActionType
import com.cinemind.core.domain.assistant.QuickCommands
import com.cinemind.core.domain.text.TextAnim
import com.cinemind.core.domain.text.TextLoop
import com.cinemind.core.domain.text.TextStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SubjectTest {

    private fun near(expected: Pair<Float, Float>, actual: Pair<Float, Float>?) {
        assertNotNull(actual)
        assertEquals(expected.first, actual!!.first, 1e-3f)
        assertEquals(expected.second, actual.second, 1e-3f)
    }

    @Test
    fun portraitClipFittedIntoPortraitFrameIsOneToOne() {
        val m = SubjectMapping.forClip(1080, 1920, ClipFraming(), 720, 1280)
        near(0.25f to 0.75f, m.toSource(0.25f, 0.75f))
    }

    @Test
    fun landscapeClipFittedIntoPortraitFrameHasBars() {
        val m = SubjectMapping.forClip(1920, 1080, ClipFraming(), 1080, 1920)
        // Picture is 1080×607.5 in the middle of a 1920-high frame: the top is a bar.
        assertNull(m.toSource(0.5f, 0.05f))
        near(0.5f to 0.5f, m.toSource(0.5f, 0.5f))
        near(0f to 0.5f, m.toSource(0f, 0.5f))
    }

    @Test
    fun fillCropsTheSides() {
        val m = SubjectMapping.forClip(1920, 1080, ClipFraming(fill = true), 1080, 1920)
        // Filling a portrait frame shows the middle 1080/1920*... of the width.
        val left = m.toSource(0f, 0.5f)!!
        assertTrue(left.first > 0.3f && left.first < 0.4f)
        near(0.5f to 0f, m.toSource(0.5f, 0f))
    }

    @Test
    fun quarterTurnAndFlipAreUndone() {
        // Clockwise quarter turn: the source's top-left ends up at the frame's top-right.
        val turned = SubjectMapping.forClip(1000, 1000, ClipFraming(quarterTurns = 1), 1000, 1000)
        near(0f to 0f, turned.toSource(1f, 0f))
        val flipped = SubjectMapping.forClip(1000, 1000, ClipFraming(flipH = true), 1000, 1000)
        near(0.1f to 0.5f, flipped.toSource(0.9f, 0.5f))
    }

    @Test
    fun cropIsUndone() {
        val f = ClipFraming(crop = CropRect(0.25f, 0f, 0.25f, 0f))
        val m = SubjectMapping.forClip(1000, 1000, f, 500, 1000)
        near(0.25f to 0f, m.toSource(0f, 0f))
        near(0.75f to 1f, m.toSource(1f, 1f))
    }

    @Test
    fun subjectLookSurvivesEncoding() {
        val f = ClipFraming(subject = SubjectLook.MONO, fill = true)
        assertEquals(SubjectLook.MONO, ClipFraming.decode(f.encode()).subject)
        assertEquals(SubjectLook.NONE, ClipFraming.decode("").subject)
        assertEquals(SubjectLook.NONE, ClipFraming.decode("subj=UNKNOWN").subject)
    }

    @Test
    fun newTextStyleFieldsSurviveEncodingAndOldStylesStillRead() {
        val s = TextStyle(color2 = 0xFFFF0000.toInt(), extrude = 0.1f, loop = TextLoop.WAVE, behindSubject = true, animIn = TextAnim.CASCADE)
        val back = TextStyle.decode(s.encode())
        assertEquals(s.color2, back.color2)
        assertEquals(0.1f, back.extrude, 1e-3f)
        assertEquals(TextLoop.WAVE, back.loop)
        assertTrue(back.behindSubject && back.drawnInPicture)
        assertEquals(TextAnim.CASCADE, back.animIn)
        // A style saved before these fields existed.
        val old = TextStyle.decode("font=SANS;w=800;c=ffffffff;in=POP;out=FADE")
        assertEquals(TextLoop.NONE, old.loop)
        assertTrue(!old.hasGradient && !old.behindSubject && old.extrude == 0f)
    }

    @Test
    fun quickCommandsUnderstandMaskingRequests() {
        fun first(s: String) = QuickCommands.parse(s, 3, 2.0)?.actions?.single()
        assertEquals(SubjectLook.CUTOUT.name, first("remove the background")!!.name)
        assertEquals(SubjectLook.BLUR.name, first("Blur the background of clip 2")!!.name)
        assertEquals(2, first("Blur the background of clip 2")!!.clip)
        assertEquals(SubjectLook.MONO.name, first("make it colour pop")!!.name)
        assertEquals(SubjectLook.SPOTLIGHT.name, first("darken the background")!!.name)
        assertEquals(AssistantActionType.CUT_OUT_SUBJECT, first("mask me out")!!.type)

        val behind = first("put GOA behind me")!!
        assertEquals(AssistantActionType.TEXT_BEHIND, behind.type)
        assertEquals("GOA", behind.text)
        assertEquals(2.0, behind.atSeconds, 1e-6)
        assertEquals("Day 1", first("add text \"Day 1\" behind the person")!!.text)

        assertNull(QuickCommands.parse("make it more cinematic", 3, 0.0))
        assertNull(QuickCommands.parse("add a title that says hello", 3, 0.0))
    }
}
