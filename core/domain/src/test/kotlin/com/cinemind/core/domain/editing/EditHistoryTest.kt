package com.cinemind.core.domain.editing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EditHistoryTest {

    @Test
    fun undoAndRedoWalkThroughStates() {
        val h = EditHistory("a")
        h.push("b"); h.push("c")
        assertTrue(h.undo()); assertEquals("b", h.present)
        assertTrue(h.undo()); assertEquals("a", h.present)
        assertFalse(h.undo())
        assertTrue(h.redo()); assertEquals("b", h.present)
        assertTrue(h.redo()); assertEquals("c", h.present)
        assertFalse(h.redo())
    }

    @Test
    fun newEditClearsRedo() {
        val h = EditHistory(1)
        h.push(2); h.undo()
        assertTrue(h.canRedo)
        h.push(3)
        assertFalse(h.canRedo)
        assertEquals(3, h.present)
    }

    @Test
    fun pushingTheSameStateIsIgnored() {
        val h = EditHistory("x")
        assertFalse(h.push("x"))
        assertFalse(h.canUndo)
    }

    @Test
    fun oldestStepsAreDroppedBeyondLimit() {
        val h = EditHistory(0, limit = 3)
        (1..5).forEach { h.push(it) }
        var undos = 0
        while (h.undo()) undos++
        assertEquals(3, undos)
        assertEquals(2, h.present)
    }
}
