package com.cinemind.core.domain.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TextMotionTest {

    private fun layer(style: TextStyle, kind: TextKind = TextKind.TEXT) =
        TextLayer(projectId = "p", startUs = 1_000_000, endUs = 3_000_000, kind = kind.name, text = "Hi", x = 0.8f, y = 0.2f, size = 0.1f, style = style.encode())

    @Test
    fun motionPathGlidesFromStartToItsPlace() {
        val l = layer(TextStyle(from = TextFrom(0.2f, 0.8f, 0.05f)))
        assertEquals(TextFrom(0.2f, 0.8f, 0.05f), l.styleObj.from)
        val start = l.placedAt(0)
        assertEquals(0.2f, start.x, 1e-4f)
        assertEquals(0.05f, start.size, 1e-4f)
        val mid = l.placedAt(1_000_000)
        assertEquals(0.5f, mid.x, 1e-3f)
        val end = l.placedAt(2_000_000)
        assertEquals(0.8f, end.x, 1e-4f)
        assertEquals(0.2f, end.y, 1e-4f)
    }

    @Test
    fun movingLayersGetTheirRunningTime() {
        val glide = layer(TextStyle(animIn = TextAnim.NONE, from = TextFrom(0f, 0f, 0.1f)))
        assertEquals(500_000L, TextLayerEditing.framesAt(listOf(glide), 1_500_000).single().localUs)
        val still = layer(TextStyle(animIn = TextAnim.NONE))
        assertEquals(0L, TextLayerEditing.framesAt(listOf(still), 2_500_000).single().localUs)
        val bar = layer(TextStyle(animIn = TextAnim.NONE), TextKind.PROGRESS)
        assertEquals(TextKind.PROGRESS, bar.kindEnum)
        assertEquals(1_500_000L, TextLayerEditing.framesAt(listOf(bar), 2_500_000).single().localUs)
    }

    @Test
    fun oldStylesHaveNoPath() {
        assertNull(TextStyle.decode("font=SANS;w=800").from)
        assertTrue(TextStyle(from = null).encode().contains("bs=0"))
    }
}
