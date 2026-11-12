package com.cinemind.core.domain.audio

import com.cinemind.core.domain.editing.TimelineEditing
import com.cinemind.core.domain.model.Clip
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random

class BeatsTest {

    /** Click track: short decaying 1 kHz bursts every beat, plus quiet noise; [accentEvery] beats louder. */
    private fun clicks(bpm: Double, seconds: Double, offsetS: Double = 0.1, accentEvery: Int = 4, noise: Float = 0.01f): FloatArray {
        val sr = BeatDetector.SAMPLE_RATE
        val out = FloatArray((seconds * sr).toInt())
        val rnd = Random(7)
        for (i in out.indices) out[i] = (rnd.nextFloat() * 2 - 1) * noise
        val period = 60.0 / bpm
        var t = offsetS
        var k = 0
        while (t < seconds) {
            val start = (t * sr).toInt()
            val amp = if (k % accentEvery == 0) 0.9f else 0.6f
            for (j in 0 until (0.05 * sr).toInt()) {
                val idx = start + j
                if (idx >= out.size) break
                out[idx] += amp * exp(-j / (0.008 * sr)).toFloat() * sin(2 * PI * 1000 * j / sr).toFloat()
            }
            t += period
            k++
        }
        return out
    }

    @Test
    fun findsTempoOfClickTracks() {
        for (bpm in listOf(90.0, 120.0, 128.0, 150.0)) {
            val info = assertNotNullAndGet(BeatDetector.detect(clicks(bpm, 20.0)))
            assertEquals("bpm $bpm", bpm, info.bpm.toDouble(), bpm * 0.03)
        }
    }

    @Test
    fun beatsLandOnTheClicks() {
        val info = BeatDetector.detect(clicks(120.0, 15.0, offsetS = 0.23))!!
        // Clicks at 0.23 + 0.5 k s; every detected beat must be within 30 ms of one.
        info.beatsUs.forEach { b ->
            val s = b / 1e6 - 0.23
            val off = abs(s - Math.round(s / 0.5) * 0.5)
            assertTrue("beat at ${b / 1000} ms is ${off * 1000} ms off", off < 0.03)
        }
        assertTrue(info.beatsUs.size in 27..31)
        assertTrue(info.confidence > 0.2f)
    }

    @Test
    fun silenceAndShortAudioGiveNothing() {
        assertNull(BeatDetector.detect(FloatArray(16_000)))
        val silence = BeatDetector.detect(FloatArray(16_000 * 10))
        assertTrue(silence == null || silence.beatsUs.isEmpty() || silence.confidence < 0.2f)
    }

    @Test
    fun beatInfoRoundTrip() {
        val b = BeatInfo(122.5f, listOf(100_000, 590_000), 0.7f)
        assertEquals(b, BeatInfo.decode(b.encode()))
        assertNull(BeatInfo.decode("nonsense"))
    }

    @Test
    fun peaksAreNormalisedAndSliced() {
        val s = FloatArray(1000) { if (it in 500 until 600) 0.5f else 0.1f }
        val p = Waveform.peaks(s, 100)
        assertEquals(10, p.size)
        assertEquals(1f, p[5], 1e-6f)
        assertEquals(0.2f, p[0], 1e-6f)
        val sl = Waveform.slice(FloatArray(100) { it / 100f }, 0, 100 * Waveform.BUCKET_US, 10)
        assertEquals(10, sl.size)
        assertTrue(sl.last() > sl.first())
    }

    @Test
    fun beatsFollowMusicStartAndLoop() {
        val beats = BeatInfo(120f, listOf(0, 500_000, 1_000_000, 1_500_000, 2_000_000, 2_500_000), 1f)
        // Song 3 s, video starts 1 s into it, video 4 s → plays 1..3 s then loops 0..2 s.
        val t = BeatSync.beatsOnTimeline(beats, 3_000_000, 1_000_000, 4_000_000)
        assertEquals(listOf(0L, 500_000L, 1_000_000L, 1_500_000L, 2_000_000L, 2_500_000L, 3_000_000L, 3_500_000L), t)
    }

    private fun clip(id: String, len: Long) = Clip(id = id, trackId = "t", assetId = id, timelineInUs = 0, timelineOutUs = 0, sourceInUs = 0, sourceOutUs = len)

    @Test
    fun cutsLandOnBeatsAndOnlyShortenVideos() {
        val clips = TimelineEditing.relayout(listOf(clip("a", 2_300_000), clip("b", 1_700_000), clip("c", 3_000_000)))
        val beats = (0..20).map { it * 500_000L }
        val out = BeatSync.cutToBeats(clips, beats, every = 1, isStill = { false })
        assertEquals(2_000_000L, out[0].timelineOutUs)
        assertEquals(3_500_000L, out[1].timelineOutUs)
        out.zip(clips).forEach { (n, o) -> assertTrue(n.sourceOutUs - n.sourceInUs <= o.sourceOutUs - o.sourceInUs) }
        assertTrue(BeatSync.offBeatUs(out, beats).all { it == 0L })
    }

    @Test
    fun everySecondBeatAndConstantSpeed() {
        val clips = TimelineEditing.relayout(listOf(clip("a", 4_600_000).copy(speed = 2f), clip("b", 1_000_000)))
        val beats = (0..20).map { it * 500_000L }
        val out = BeatSync.cutToBeats(clips, beats, every = 2, isStill = { false })
        // 4.6 s at 2× = 2.3 s on the timeline → nearest 1 s grid point not after it: 2 s → 4 s of source.
        assertEquals(2_000_000L, out[0].timelineOutUs)
        assertEquals(4_000_000L, out[0].sourceOutUs)
    }

    private fun <T> assertNotNullAndGet(v: T?): T { assertNotNull(v); return v!! }
}
