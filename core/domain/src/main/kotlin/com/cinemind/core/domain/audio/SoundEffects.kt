package com.cinemind.core.domain.audio

import java.io.ByteArrayOutputStream
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random

/**
 * CineMind's built-in sound effects. Every sound is generated on the phone from these recipes
 * (noise, tones and envelopes), so they are original, need no download and have no licence
 * conditions. Users can also import their own sounds.
 */
enum class SoundEffect(val label: String, val category: String, val seconds: Double) {
    WHOOSH("Whoosh", "Transitions", 0.9),
    SWISH("Quick swish", "Transitions", 0.35),
    RISER("Riser", "Transitions", 2.2),
    REVERSE_SWELL("Reverse swell", "Transitions", 1.4),
    IMPACT("Impact boom", "Hits", 1.6),
    PUNCH("Punch", "Hits", 0.35),
    THUD("Soft thud", "Hits", 0.5),
    POP("Pop", "UI & text", 0.18),
    CLICK("Click", "UI & text", 0.06),
    DING("Ding", "UI & text", 1.4),
    NOTIFY("Notification", "UI & text", 0.7),
    TYPING("Typing", "UI & text", 1.2),
    SPARKLE("Sparkle", "Magic", 1.3),
    SHUTTER("Camera shutter", "Everyday", 0.3),
    HEARTBEAT("Heartbeat", "Everyday", 1.8),
    GLITCH("Glitch", "Effects", 0.7),
    LASER("Zap", "Effects", 0.45),
    ;

    val id: String get() = "sfx:" + name.lowercase()
}

object SfxSynth {

    const val SAMPLE_RATE = 44_100

    fun render(effect: SoundEffect, seed: Int = 1): FloatArray {
        val n = (effect.seconds * SAMPLE_RATE).toInt()
        val out = FloatArray(n)
        val rnd = Random(seed + effect.ordinal * 31)
        val sr = SAMPLE_RATE.toDouble()
        fun t(i: Int) = i / sr
        fun noise() = rnd.nextFloat() * 2f - 1f
        when (effect) {
            SoundEffect.WHOOSH, SoundEffect.SWISH -> {
                // Band-passed noise whose centre sweeps up then down, with a swell envelope.
                val bp = Biquad()
                for (i in 0 until n) {
                    val p = i.toDouble() / n
                    val f = 300 + 2600 * sin(PI * p)
                    bp.bandpass(f, 1.2)
                    val env = sin(PI * p).pow(if (effect == SoundEffect.SWISH) 1.5 else 2.0)
                    out[i] = (bp.process(noise().toDouble()) * env * 2.2).toFloat()
                }
            }
            SoundEffect.RISER -> {
                val bp = Biquad()
                var ph = 0.0
                for (i in 0 until n) {
                    val p = i.toDouble() / n
                    bp.bandpass(400 + 5000 * p * p, 2.0)
                    val f = 200 * 2.0.pow(3 * p)
                    ph += 2 * PI * f / sr
                    val env = p.pow(1.6) * (1 - max(0.0, (p - 0.97) / 0.03))
                    out[i] = ((bp.process(noise().toDouble()) * 1.6 + 0.25 * sin(ph)) * env).toFloat()
                }
            }
            SoundEffect.REVERSE_SWELL -> {
                val bp = Biquad()
                for (i in 0 until n) {
                    val p = i.toDouble() / n
                    bp.bandpass(800 + 3000 * p, 0.8)
                    val env = exp(5 * (p - 1)) * (if (p > 0.98) (1 - p) / 0.02 else 1.0)
                    out[i] = (bp.process(noise().toDouble()) * env * 2.5).toFloat()
                }
            }
            SoundEffect.IMPACT, SoundEffect.THUD, SoundEffect.PUNCH -> {
                // Pitch-dropping sine body + noise crack.
                val (f0, f1, decay, crack) = when (effect) {
                    SoundEffect.IMPACT -> listOf(110.0, 38.0, 0.45, 0.5)
                    SoundEffect.PUNCH -> listOf(180.0, 60.0, 0.08, 0.7)
                    else -> listOf(90.0, 50.0, 0.12, 0.15)
                }
                var ph = 0.0
                val lp = Biquad().also { it.lowpass(2500.0, 0.7) }
                for (i in 0 until n) {
                    val s = t(i)
                    val f = f1 + (f0 - f1) * exp(-s / 0.07)
                    ph += 2 * PI * f / sr
                    val body = sin(ph) * exp(-s / decay)
                    val hit = lp.process(noise().toDouble()) * exp(-s / 0.02) * crack
                    out[i] = (body + hit).toFloat()
                }
            }
            SoundEffect.POP -> {
                var ph = 0.0
                for (i in 0 until n) {
                    val s = t(i)
                    ph += 2 * PI * (900 * exp(-s / 0.03) + 300) / sr
                    out[i] = (sin(ph) * exp(-s / 0.03)).toFloat()
                }
            }
            SoundEffect.CLICK -> for (i in 0 until n) out[i] = (noise() * exp(-t(i) / 0.004)).toFloat()
            SoundEffect.DING, SoundEffect.NOTIFY -> {
                // Bell: inharmonic partials with different decays; notification = two notes.
                val notes = if (effect == SoundEffect.DING) listOf(0.0 to 1318.5) else listOf(0.0 to 987.8, 0.14 to 1318.5)
                val partials = listOf(1.0 to 1.0, 2.76 to 0.4, 5.4 to 0.2, 8.93 to 0.1)
                for ((start, f) in notes) {
                    for (i in (start * sr).toInt() until n) {
                        val s = t(i) - start
                        var v = 0.0
                        for ((m, a) in partials) v += a * sin(2 * PI * f * m * s) * exp(-s * (1.8 + m))
                        out[i] += (v * 0.45 * min(1.0, s / 0.002)).toFloat()
                    }
                }
            }
            SoundEffect.TYPING -> {
                var at = 0.02
                while (at < effect.seconds - 0.05) {
                    val start = (at * sr).toInt()
                    val f = 1800 + rnd.nextInt(1200)
                    for (j in 0 until (0.03 * sr).toInt()) {
                        val i = start + j
                        if (i >= n) break
                        val s = j / sr
                        out[i] += ((noise() * 0.6 + sin(2 * PI * f * s) * 0.4) * exp(-s / 0.006)).toFloat()
                    }
                    at += 0.09 + rnd.nextDouble() * 0.08
                }
            }
            SoundEffect.SPARKLE -> {
                repeat(14) { k ->
                    val start = (rnd.nextDouble() * (effect.seconds - 0.4) * sr).toInt()
                    val f = 2500.0 + rnd.nextDouble() * 4500
                    for (j in 0 until (0.35 * sr).toInt()) {
                        val i = start + j
                        if (i >= n) break
                        val s = j / sr
                        out[i] += (sin(2 * PI * f * s) * exp(-s / 0.07) * 0.22 * (1 - k / 20.0)).toFloat()
                    }
                }
            }
            SoundEffect.SHUTTER -> {
                val hp = Biquad().also { it.highpass(1500.0, 0.7) }
                for (i in 0 until n) {
                    val s = t(i)
                    val e = exp(-s / 0.012) + 0.8 * (if (s > 0.11) exp(-(s - 0.11) / 0.015) else 0.0)
                    out[i] = (hp.process(noise().toDouble()) * e * 1.4).toFloat()
                }
            }
            SoundEffect.HEARTBEAT -> {
                listOf(0.05, 0.3, 0.95, 1.2).forEachIndexed { k, start ->
                    var ph = 0.0
                    for (i in (start * sr).toInt() until min(n, ((start + 0.25) * sr).toInt())) {
                        val s = t(i) - start
                        ph += 2 * PI * (60 - 20 * s) / sr
                        out[i] += (sin(ph) * exp(-s / 0.06) * (if (k % 2 == 0) 1.0 else 0.7)).toFloat()
                    }
                }
            }
            SoundEffect.GLITCH -> {
                var i = 0
                while (i < n) {
                    val len = (0.015 + rnd.nextDouble() * 0.06) * sr
                    val f = listOf(110.0, 220.0, 440.0, 880.0, 1760.0)[rnd.nextInt(5)]
                    val bits = 2 + rnd.nextInt(5)
                    val on = rnd.nextDouble() > 0.25
                    for (j in 0 until len.toInt()) {
                        if (i >= n) break
                        val sq = if (sin(2 * PI * f * j / sr) >= 0) 1.0 else -1.0
                        val q = Math.round(sq * bits) / bits.toDouble()
                        out[i] = if (on) (q * 0.5 + noise() * 0.15).toFloat() else 0f
                        i++
                    }
                }
            }
            SoundEffect.LASER -> {
                var ph = 0.0
                for (i in 0 until n) {
                    val s = t(i)
                    ph += 2 * PI * (2400 * exp(-s / 0.09) + 180) / sr
                    out[i] = (sin(ph) * exp(-s / 0.18)).toFloat()
                }
            }
        }
        return finish(out)
    }

    /** Short fades at both ends (no clicks) and normalise to −1 dBFS. */
    private fun finish(a: FloatArray): FloatArray {
        val fade = min(a.size / 4, (0.004 * SAMPLE_RATE).toInt())
        for (i in 0 until fade) {
            val g = i.toFloat() / fade
            a[i] *= g
            a[a.size - 1 - i] *= g
        }
        val peak = a.maxOfOrNull { abs(it) } ?: 0f
        if (peak > 1e-6f) {
            val g = 0.89f / peak
            for (i in a.indices) a[i] *= g
        }
        return a
    }

    /** 16-bit PCM mono WAV. */
    fun wav(samples: FloatArray, sampleRate: Int = SAMPLE_RATE): ByteArray {
        val data = samples.size * 2
        val out = ByteArrayOutputStream(44 + data)
        fun str(s: String) = out.write(s.toByteArray(Charsets.US_ASCII))
        fun i32(v: Int) { out.write(v and 0xFF); out.write((v shr 8) and 0xFF); out.write((v shr 16) and 0xFF); out.write((v shr 24) and 0xFF) }
        fun i16(v: Int) { out.write(v and 0xFF); out.write((v shr 8) and 0xFF) }
        str("RIFF"); i32(36 + data); str("WAVE")
        str("fmt "); i32(16); i16(1); i16(1); i32(sampleRate); i32(sampleRate * 2); i16(2); i16(16)
        str("data"); i32(data)
        for (s in samples) i16((s.coerceIn(-1f, 1f) * 32767).toInt())
        return out.toByteArray()
    }

    /** Durations of a WAV made by [wav], in µs. */
    fun durationUs(sampleCount: Int, sampleRate: Int = SAMPLE_RATE): Long = sampleCount * 1_000_000L / sampleRate

    /** RBJ biquad filter (one channel). */
    private class Biquad {
        private var b0 = 1.0; private var b1 = 0.0; private var b2 = 0.0; private var a1 = 0.0; private var a2 = 0.0
        private var x1 = 0.0; private var x2 = 0.0; private var y1 = 0.0; private var y2 = 0.0

        private fun set(nb0: Double, nb1: Double, nb2: Double, na0: Double, na1: Double, na2: Double) {
            b0 = nb0 / na0; b1 = nb1 / na0; b2 = nb2 / na0; a1 = na1 / na0; a2 = na2 / na0
        }

        fun bandpass(f: Double, q: Double) {
            val w = 2 * PI * f / SAMPLE_RATE
            val alpha = sin(w) / (2 * q)
            set(alpha, 0.0, -alpha, 1 + alpha, -2 * kotlin.math.cos(w), 1 - alpha)
        }

        fun lowpass(f: Double, q: Double) {
            val w = 2 * PI * f / SAMPLE_RATE
            val alpha = sin(w) / (2 * q)
            val c = kotlin.math.cos(w)
            set((1 - c) / 2, 1 - c, (1 - c) / 2, 1 + alpha, -2 * c, 1 - alpha)
        }

        fun highpass(f: Double, q: Double) {
            val w = 2 * PI * f / SAMPLE_RATE
            val alpha = sin(w) / (2 * q)
            val c = kotlin.math.cos(w)
            set((1 + c) / 2, -(1 + c), (1 + c) / 2, 1 + alpha, -2 * c, 1 - alpha)
        }

        fun process(x: Double): Double {
            val y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
            x2 = x1; x1 = x; y2 = y1; y1 = y
            return y
        }
    }
}
