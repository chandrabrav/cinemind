package com.cinemind.feature.ai.generation.smartedit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameMetricsTest {

    @Test
    fun lumaUsesRec601Weights() {
        val px = intArrayOf(0xFFFFFFFF.toInt(), 0xFF000000.toInt(), 0xFFFF0000.toInt())
        assertEquals(listOf(255, 0, 76), FrameMetrics.luma(px).toList())
    }

    @Test
    fun checkerboardIsSharperThanFlatGrey() {
        val w = 16
        val h = 16
        val checker = IntArray(w * h) { i -> if ((i % w + i / w) % 2 == 0) 255 else 0 }
        val flat = IntArray(w * h) { 128 }
        assertTrue(FrameMetrics.laplacianVariance(checker, w, h) > 1000f)
        assertEquals(0f, FrameMetrics.laplacianVariance(flat, w, h), 0.0001f)
    }

    @Test
    fun brightnessAndDifference() {
        assertEquals(0.5f, FrameMetrics.meanBrightness(intArrayOf(0, 255)), 0.001f)
        assertEquals(1f, FrameMetrics.meanAbsDiff(intArrayOf(0, 0), intArrayOf(255, 255)), 0.0001f)
        assertEquals(0f, FrameMetrics.meanAbsDiff(intArrayOf(1), intArrayOf(1, 2)), 0.0001f)
    }

    @Test
    fun scoringPrefersSharpWellExposedModerateMotion() {
        val good = SamplePoint(0, sharpness = 900f, brightness = 0.5f, motion = 0.08f)
        val dark = good.copy(brightness = 0.05f)
        val blurry = good.copy(sharpness = 50f)
        val shaky = good.copy(motion = 0.6f)
        val ref = 900f
        val g = MomentScoring.score(good, ref, strict = false)
        assertTrue(g > MomentScoring.score(dark, ref, false))
        assertTrue(g > MomentScoring.score(blurry, ref, false))
        assertTrue(g > MomentScoring.score(shaky, ref, false))
        assertEquals(1f, g, 0.0001f)
    }

    @Test
    fun sharpnessReferenceIsNinetiethPercentile() {
        assertEquals(9f, MomentScoring.sharpnessReference((1..10).map { it.toFloat() }), 0.0001f)
        assertEquals(1f, MomentScoring.sharpnessReference(emptyList()), 0.0001f)
    }
}
