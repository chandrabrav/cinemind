package com.cinemind.feature.ai.llm.ondevice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PromptsTest {

    @Test
    fun parsesACleanInsight() {
        val i = Prompts.parseInsight(
            """{"subject": "sunset over the sea", "scene": "Beach", "shot": "wide", "people": 0, "quality": 8, "interest": 9}""",
        )!!
        assertEquals("sunset over the sea", i.subject)
        assertEquals("beach", i.scene)
        assertEquals(ShotSize.WIDE, i.shot)
        assertEquals(0, i.people)
        assertEquals(8, i.quality)
        assertEquals(9, i.interest)
    }

    @Test
    fun toleratesChatterCodeFencesQuotedNumbersAndOutOfRangeScores() {
        val reply = "Sure! Here it is:\n```json\n{ \"subject\":\"two friends laughing\",\"shot\":\"close-up\",\"people\":\"2\",\"quality\": 14, \"interest\": 0 }\n```"
        val i = Prompts.parseInsight(reply)!!
        assertEquals(ShotSize.CLOSE, i.shot)
        assertEquals(2, i.people)
        assertEquals(10, i.quality)
        assertEquals(1, i.interest)
        assertEquals("unknown", i.scene)
    }

    @Test
    fun rejectsRepliesWithoutJsonOrSubject() {
        assertNull(Prompts.parseInsight("I can't see the image."))
        assertNull(Prompts.parseInsight("""{"scene": "gym"}"""))
    }

    @Test
    fun parsesBriefAndDropsUnknownValues() {
        val b = Prompts.parseBrief(
            """{"style":"Travel","pacing":"fast","target_seconds":30,"order":"hook first","transition":"spin","strict_quality":true}""",
        )!!
        assertEquals("travel", b.style)
        assertEquals("fast", b.pacing)
        assertEquals(30, b.targetSeconds)
        assertEquals("hook_first", b.order)
        assertNull(b.transition)
        assertEquals(true, b.strictQuality)
    }

    @Test
    fun briefNullsAndBadLengthsAreIgnored() {
        val b = Prompts.parseBrief("""{"style": null, "target_seconds": 2, "strict_quality": null}""")!!
        assertNull(b.style)
        assertNull(b.targetSeconds)
        assertNull(b.strictQuality)
    }

    @Test
    fun briefPromptEscapesQuotes() {
        assertTrue(Prompts.brief("my \"best\" trip").contains("my 'best' trip"))
    }

    @Test
    fun similarityFindsRepeatedScenes() {
        assertTrue(Prompts.similarity("sunset over the beach", "beach at sunset") > 0.5f)
        assertTrue(Prompts.similarity("man lifting weights", "plate of food") < 0.1f)
        assertEquals(0f, Prompts.similarity("", "x"), 0f)
    }

    @Test
    fun downloadMath() {
        assertEquals(2_588_147_712L, DownloadMath.totalFromContentRange("bytes 100-2588147711/2588147712"))
        assertNull(DownloadMath.totalFromContentRange(null))
        assertEquals(0.5f, DownloadMath.fraction(50, 100), 0f)
        assertEquals(100L + 500L * 1024 * 1024, DownloadMath.requiredFreeBytes(200, 100))
        assertEquals("2.41 GB", DownloadMath.formatBytes(2_588_147_712L))
        assertEquals("0a0f", DownloadMath.hex(byteArrayOf(10, 15)))
    }

    @Test
    fun catalogFilesMatchTheirUrls() {
        ModelCatalog.ALL.forEach {
            assertTrue(it.url.endsWith("/" + it.fileName))
            assertEquals(64, it.sha256.length)
        }
    }
}
