package com.cinemind.core.domain.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

class VoiceEnhanceTest {

    private val rate = 48_000

    private fun rms(a: FloatArray, from: Int, to: Int): Double {
        var s = 0.0
        for (i in from until to) s += a[i] * a[i].toDouble()
        return sqrt(s / (to - from))
    }

    private fun db(a: Double, b: Double) = 20 * log10(a / b)

    /** Runs [input] through the enhancer in uneven chunks, like a real audio pipeline. */
    private fun run(input: FloatArray, level: EnhanceLevel): FloatArray {
        val e = VoiceEnhancer(rate, level)
        val out = ArrayList<Float>(input.size)
        var i = 0
        var chunk = 777
        while (i < input.size) {
            val end = minOf(input.size, i + chunk)
            e.process(input.copyOfRange(i, end)).forEach { out += it }
            i = end
            chunk = if (chunk == 777) 1500 else 777
        }
        e.finish().forEach { out += it }
        return out.toFloatArray()
    }

    @Test
    fun fftRoundTrips() {
        val re = FloatArray(16) { sin(it * 0.7f) }
        val im = FloatArray(16)
        val orig = re.copyOf()
        VoiceEnhancer.fft(re, im, inverse = false)
        VoiceEnhancer.fft(re, im, inverse = true)
        for (i in orig.indices) assertEquals(orig[i], re[i], 1e-4f)
    }

    @Test
    fun outputHasTheSameLengthAndTiming() {
        val input = FloatArray(rate) // one second, with a click at 0.5 s
        input[rate / 2] = 0.9f
        val out = run(input, EnhanceLevel.LIGHT)
        assertEquals(input.size, out.size)
        // The click stays where it was (no added delay).
        val peak = out.indices.maxBy { kotlin.math.abs(out[it]) }
        assertTrue("click moved to $peak", kotlin.math.abs(peak - rate / 2) <= 2)
    }

    @Test
    fun steadyNoiseIsReducedAndTheVoiceKept() {
        val rnd = Random(7)
        val seconds = 6
        val input = FloatArray(rate * seconds) { i ->
            val noise = (rnd.nextFloat() * 2f - 1f) * 0.03f
            // "Speech" (a 700 Hz tone) only in the second half.
            val voice = if (i >= rate * 3) 0.25f * sin(2 * PI * 700 * i / rate).toFloat() else 0f
            noise + voice
        }
        val out = run(input, EnhanceLevel.STRONG)
        // Noise-only stretch after the enhancer has learnt it (2–3 s).
        val noiseBefore = rms(input, rate * 2, rate * 3)
        val noiseAfter = rms(out, rate * 2, rate * 3)
        assertTrue("noise only ${db(noiseBefore, noiseAfter)} dB lower", db(noiseBefore, noiseAfter) > 8)
        // The tone survives (the leveller may lift it a little).
        val voiceBefore = rms(input, rate * 4, rate * 6)
        val voiceAfter = rms(out, rate * 4, rate * 6)
        assertTrue("voice changed ${db(voiceAfter, voiceBefore)} dB", db(voiceAfter, voiceBefore) > -3)
    }

    @Test
    fun settingSurvivesEncoding() {
        val v = VoiceEnhance(EnhanceLevel.STRONG, onClips = true, onVoiceover = false)
        assertEquals(v, VoiceEnhance.decode(v.encode()))
        assertEquals(VoiceEnhance(), VoiceEnhance.decode(null))
        assertTrue(!VoiceEnhance().active)
    }
}
