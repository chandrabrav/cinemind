package com.cinemind.core.domain.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AssistantTest {

    @Test
    fun parsesActionsAndDropsInvalidOnes() {
        val json = """
            {"reply": "Sure — a warmer look and a title.",
             "actions": [
               {"type": "APPLY_LOOK", "name": "golden_hour", "text": "", "value": 0, "clip": 0, "atSeconds": 0},
               {"type": "ADD_TITLE", "name": "CINEMA", "text": "Goa ☀", "value": 0, "clip": 0, "atSeconds": 0.5},
               {"type": "APPLY_LOOK", "name": "not_a_look", "text": "", "value": 0, "clip": 0, "atSeconds": 0},
               {"type": "SET_SPEED", "name": "", "text": "", "value": 9, "clip": 2, "atSeconds": 0},
               {"type": "DELETE_EVERYTHING", "name": "", "text": "", "value": 0, "clip": 0, "atSeconds": 0},
               {"type": "SET_SPEED", "name": "", "text": "", "value": 0.5, "clip": 99, "atSeconds": 0}
             ]}
        """.trimIndent()
        val plan = Assistant.parse(json, clipCount = 3)
        assertEquals("Sure — a warmer look and a title.", plan.reply)
        assertEquals(listOf(AssistantActionType.APPLY_LOOK, AssistantActionType.ADD_TITLE, AssistantActionType.SET_SPEED), plan.actions.map { it.type })
        assertEquals("Goa ☀", plan.actions[1].text)
        assertEquals(3, plan.actions[2].clip)
    }

    @Test
    fun toleratesTextAroundJsonAndPlainAnswers() {
        val plan = Assistant.parse("Here you go: {\"reply\":\"Hi\",\"actions\":[]} thanks", 2)
        assertEquals("Hi", plan.reply)
        assertTrue(plan.actions.isEmpty())
        val plain = Assistant.parse("Your video is 30 seconds long.", 2)
        assertEquals("Your video is 30 seconds long.", plain.reply)
    }

    @Test
    fun briefIsTextOnly() {
        val b = ProjectBrief("Goa trip", 31.5, listOf("video 4.0 s", "photo 3.0 s"), listOf("Goa"), "hello everyone", 120f, true)
        val text = Assistant.describe(b)
        assertTrue(text.contains("31.5 s, 2 clips"))
        assertTrue(text.contains("Clip 2: photo 3.0 s"))
        assertTrue(text.contains("120 BPM"))
    }

    @Test
    fun schemaListsEveryType() {
        @Suppress("UNCHECKED_CAST")
        val items = ((Assistant.schema["properties"] as Map<String, Any>)["actions"] as Map<String, Any>)["items"] as Map<String, Any>
        @Suppress("UNCHECKED_CAST")
        val enum = ((items["properties"] as Map<String, Any>)["type"] as Map<String, Any>)["enum"] as List<String>
        assertEquals(AssistantActionType.entries.size, enum.size)
    }

    @Test
    fun miniJsonHandlesEscapesAndNumbers() {
        val v = MiniJson.parse("""{"a":[1,-2.5,1e2,true,null],"b":"x\"y\\n"}""") as Map<*, *>
        assertEquals(listOf(1L, -2.5, 100.0, true, null), v["a"])
        assertEquals("x\"y\\n", (v["b"] as String).replace("\n", "\\n"))
    }
}
