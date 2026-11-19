package com.cinemind.core.domain.text

import com.cinemind.core.domain.captions.CaptionFont
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TextLayersTest {

    private fun layer(id: String, start: Long, end: Long, lane: Int = 0, style: String = "") =
        TextLayer(id = id, projectId = "p", startUs = start, endUs = end, text = id, lane = lane, style = style)

    @Test
    fun styleRoundTripsAndToleratesJunk() {
        val s = TextStyle(font = CaptionFont.SERIF, weight = 300, color = 0xFF7DF9FF.toInt(), outline = 0.1f, glow = true, boxOpacity = 0.5f,
            uppercase = true, spacing = 0.2f, align = -1, animIn = TextAnim.TYPEWRITER, animOut = TextAnim.ZOOM)
        assertEquals(s, TextStyle.decode(s.encode()))
        assertEquals(TextStyle(), TextStyle.decode("w=zz;font=NOPE;c=xyz"))
        assertEquals(TextStyle(), TextStyle.decode(""))
    }

    @Test
    fun presetsEncodeTheirOwnLook() {
        TextPreset.entries.forEach { p -> assertEquals(p.name, p.style, TextStyle.decode(p.style.encode())) }
    }

    @Test
    fun framesOnlyWhileVisibleAndInLaneOrder() {
        val layers = listOf(layer("top", 0, 2_000_000, lane = 2), layer("bottom", 500_000, 3_000_000, lane = 1), layer("late", 5_000_000, 6_000_000))
        assertEquals(listOf("bottom", "top"), TextLayerEditing.framesAt(layers, 1_000_000).map { it.layer.id })
        assertTrue(TextLayerEditing.framesAt(layers, 4_000_000).isEmpty())
        assertTrue(TextLayerEditing.framesAt(layers, 2_000_000).none { it.layer.id == "top" })
    }

    @Test
    fun enterAndExitProgress() {
        val l = layer("a", 1_000_000, 4_000_000)
        val start = TextLayerEditing.framesAt(listOf(l), 1_000_000).single()
        assertEquals(0f, start.enter, 0f)
        val mid = TextLayerEditing.framesAt(listOf(l), 2_500_000).single()
        assertEquals(1f, mid.enter, 0f)
        assertEquals(0f, mid.exit, 0f)
        val end = TextLayerEditing.framesAt(listOf(l), 3_900_000).single()
        assertTrue(end.exit > 0.7f)
    }

    @Test
    fun typewriterTypesLonger() {
        val l = layer("t", 0, 4_000_000, style = TextStyle(animIn = TextAnim.TYPEWRITER).encode())
        assertEquals(0.5f, TextLayerEditing.framesAt(listOf(l), 600_000).single().enter, 1e-3f)
    }

    @Test
    fun createFitsTimelineAndGoesOnTop() {
        val existing = listOf(layer("x", 0, 1_000_000, lane = 3))
        val t = TextLayerEditing.create("p", existing, 9_000_000, 10_000_000, "Hi", TextKind.TEXT, TextPreset.NEON)
        assertEquals(4, t.lane)
        assertEquals(9_000_000L, t.startUs)
        assertEquals(10_000_000L, t.endUs)
        assertEquals(TextPreset.NEON.style, t.styleObj)
        val s = TextLayerEditing.create("p", emptyList(), 0, 10_000_000, "🔥", TextKind.STICKER, null)
        assertEquals(TextKind.STICKER, s.kindEnum)
        assertFalse(s.styleObj.shadow)
    }

    @Test
    fun moveAndTrimStayInside() {
        val l = listOf(layer("a", 1_000_000, 3_000_000))
        assertEquals(0L, TextLayerEditing.move(l, "a", -5_000_000, 10_000_000).single().startUs)
        assertEquals(8_000_000L, TextLayerEditing.move(l, "a", 50_000_000, 10_000_000).single().startUs)
        val shortest = TextLayerEditing.trim(l, "a", 0, -10_000_000, 10_000_000).single()
        assertEquals(TextLayerEditing.MIN_US, shortest.lengthUs)
        assertEquals(10_000_000L, TextLayerEditing.trim(l, "a", 0, 50_000_000, 10_000_000).single().endUs)
    }

    @Test
    fun duplicateAndFront() {
        val l = listOf(layer("a", 0, 1_000_000, lane = 0), layer("b", 0, 1_000_000, lane = 5))
        val d = TextLayerEditing.duplicate(l, "a", 10_000_000) { "c" }
        val c = d.single { it.id == "c" }
        assertEquals(1_000_000L, c.startUs)
        assertEquals(6, c.lane)
        assertEquals(6, TextLayerEditing.bringToFront(l, "a").single { it.id == "a" }.lane)
    }

    @Test
    fun fitToShorterTimeline() {
        val l = listOf(layer("in", 0, 1_000_000), layer("cut", 1_500_000, 5_000_000), layer("gone", 4_000_000, 5_000_000))
        val f = TextLayerEditing.fitTo(l, 3_000_000)
        assertEquals(listOf("in", "cut"), f.map { it.id })
        assertEquals(3_000_000L, f[1].endUs)
        assertNull(f.firstOrNull { it.id == "gone" })
    }
}

class LayerStackTest {

    private fun t(id: String, lane: Int, below: Boolean = false) =
        TextLayer(id = id, projectId = "p", startUs = 0, endUs = 1, text = id, lane = lane, style = TextStyle(belowOverlays = below).encode())

    private fun names(e: List<LayerEntry>) = e.map { if (it is LayerEntry.Title) it.layer.id else it::class.simpleName!! }

    @Test
    fun defaultStackHasCaptionsOnTopAndVideoAtTheBottom() {
        val e = LayerStack.entries(listOf(t("a", 0), t("b", 1), t("c", 2, below = true)), LayerOrder(), hasCaptions = true, hasOverlays = true)
        assertEquals(listOf("Captions", "b", "a", "PictureInPicture", "c", "MainVideo"), names(e))
    }

    @Test
    fun movingATitlePastThePipPutsItBelow() {
        val texts = listOf(t("a", 0), t("b", 1))
        val (moved, _) = LayerStack.move(texts, LayerOrder(), false, true, LayerEntry.Title(texts[0]), up = false)
        assertTrue(moved.first { it.id == "a" }.styleObj.belowOverlays)
        assertEquals(listOf("b", "PictureInPicture", "a", "MainVideo"), names(LayerStack.entries(moved, LayerOrder(), false, true)))
    }

    @Test
    fun swappingTitlesSwapsLanes() {
        val texts = listOf(t("a", 0), t("b", 1))
        val (moved, _) = LayerStack.move(texts, LayerOrder(), false, false, LayerEntry.Title(texts[0]), up = true)
        assertEquals(listOf("a", "b", "MainVideo"), names(LayerStack.entries(moved, LayerOrder(), false, false)))
    }

    @Test
    fun moveByMovesSeveralStepsAtOnce() {
        val texts = listOf(t("a", 0), t("b", 1), t("c", 2))
        // Stack: c, b, a, PiP, MainVideo. Drag "c" three places down → below the PiP.
        val (moved, _) = LayerStack.moveBy(texts, LayerOrder(), false, true, "title:c", 3)
        assertEquals(listOf("b", "a", "PictureInPicture", "c", "MainVideo"), names(LayerStack.entries(moved, LayerOrder(), false, true)))
        // Further than possible stops above the main video.
        val (far, _) = LayerStack.moveBy(texts, LayerOrder(), false, true, "title:c", 10)
        assertEquals("MainVideo", names(LayerStack.entries(far, LayerOrder(), false, true)).last())
    }

    @Test
    fun pipMovesByPassingItsNeighbours() {
        val texts = listOf(t("a", 0), t("b", 1))
        // b, a, PiP, MainVideo → PiP two up → PiP, b, a.
        val (moved, _) = LayerStack.moveBy(texts, LayerOrder(), false, true, "pip", -2)
        assertEquals(listOf("PictureInPicture", "b", "a", "MainVideo"), names(LayerStack.entries(moved, LayerOrder(), false, true)))
        // The main video never moves.
        val (same, _) = LayerStack.moveBy(texts, LayerOrder(), false, true, "video", -1)
        assertEquals(texts, same)
    }

    @Test
    fun captionsCanGoUnderThePip() {
        val (_, order) = LayerStack.move(emptyList(), LayerOrder(), true, true, LayerEntry.Captions, up = false)
        assertFalse(order.captionsAboveOverlays)
        assertEquals(order, LayerOrder.decode(order.encode()))
        assertEquals(listOf("PictureInPicture", "Captions", "MainVideo"), names(LayerStack.entries(emptyList(), order, true, true)))
    }

    @Test
    fun mainVideoNeverMoves() {
        val texts = listOf(t("a", 0, below = true))
        val (moved, order) = LayerStack.move(texts, LayerOrder(), false, false, LayerEntry.Title(texts[0]), up = false)
        assertEquals(texts, moved)
        assertEquals(LayerOrder(), order)
    }

    @Test
    fun titleCanSitBehindSomeOverlaysOnly() {
        val s = TextStyle(belowClips = setOf("pip-b", "pip-a"))
        assertEquals(s, TextStyle.decode(s.encode()))
        assertTrue(s.partlyBelow)
        assertTrue(s.isBelow("pip-a"))
        assertFalse(s.isBelow("pip-c"))
        assertFalse(s.isBelow(null))
        // Behind all overlays wins over the per-overlay list.
        assertTrue(s.copy(belowOverlays = true).isBelow("pip-c"))
        assertFalse(s.copy(belowOverlays = true).partlyBelow)
        // Moving the title past the picture-in-picture in the layer stack resets the per-overlay choice.
        val t = TextLayer(id = "t", projectId = "p", startUs = 0, endUs = 5_000_000, text = "t", style = s.encode())
        val (moved, _) = LayerStack.move(listOf(t), LayerOrder(), false, true, LayerEntry.Title(t), up = false)
        assertTrue(moved[0].styleObj.belowOverlays)
        assertTrue(moved[0].styleObj.belowClips.isEmpty())
    }
}
