package com.cinemind.core.domain.audio

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** How hard the voice enhancer works. */
enum class EnhanceLevel(val label: String, val hint: String) {
    OFF("Off", "Sound as recorded"),
    LIGHT("Light", "Removes hiss and hum, keeps the room natural"),
    STRONG("Strong", "Removes wind, traffic and fan noise; voice up front"),
    ;

    companion object {
        fun from(name: String?): EnhanceLevel = entries.firstOrNull { it.name == name } ?: OFF
    }
}

/** Per-project voice enhancer setting: level, and which sound it applies to. */
data class VoiceEnhance(
    val level: EnhanceLevel = EnhanceLevel.OFF,
    val onClips: Boolean = true,
    val onVoiceover: Boolean = true,
) {
    val active: Boolean get() = level != EnhanceLevel.OFF && (onClips || onVoiceover)

    fun encode(): String = "${level.name};${if (onClips) 1 else 0};${if (onVoiceover) 1 else 0}"

    companion object {
        fun decode(s: String?): VoiceEnhance {
            if (s.isNullOrBlank()) return VoiceEnhance()
            val p = s.split(';')
            return VoiceEnhance(EnhanceLevel.from(p.getOrNull(0)), p.getOrNull(1) != "0", p.getOrNull(2) != "0")
        }
    }
}

/**
 * Speech clean-up for one mono channel, entirely on the phone. Streaming and delay-free for the
 * caller: sample i of the output is sample i of the input, enhanced. [process] may return a little
 * less than it is given at first; [finish] returns the rest, so the total always matches and the
 * sound stays in sync with the picture.
 *
 * Per 1024-sample frame (50% overlap, √Hann windows, perfect reconstruction):
 *  1. low cut below ~90 Hz (rumble, handling noise, wind thumps);
 *  2. noise floor per frequency tracked continuously (falls fast, rises slowly), so steady noise
 *     like fans, traffic and hiss is learnt within a second or two, even while someone talks;
 *  3. power subtraction against that floor, opening instantly and closing gently (no "musical"
 *     noise), never below a floor so the room doesn't sound dead;
 *  4. a small presence lift (2–5 kHz) for clarity;
 * then a slow leveller and soft limiter on the result.
 */
class VoiceEnhancer(sampleRate: Int, private val level: EnhanceLevel) {

    private val n = FRAME
    private val hop = FRAME / 2
    private val bins = n / 2 + 1
    private val window = FloatArray(n) { sqrt(0.5f - 0.5f * cos(2.0 * PI * it / n).toFloat()) }
    private val binHz = sampleRate.toFloat() / n

    private val floor = if (level == EnhanceLevel.STRONG) 0.1f else 0.28f
    private val overSubtract = if (level == EnhanceLevel.STRONG) 2.6f else 1.8f
    private val presence = if (level == EnhanceLevel.STRONG) 1.25f else 1.12f
    private val maxBoost = if (level == EnhanceLevel.STRONG) 2.8f else 1.6f

    // Streaming state.
    private val inBuf = FloatArray(n)
    private var inFill = n - hop // primed with silence: output lags input by exactly n - hop
    private val outAcc = FloatArray(n)
    private var pending = FloatArray(4 * FRAME)
    private var pendingStart = 0
    private var pendingEnd = 0
    private var received = 0L
    private var emitted = 0L
    private var toSkip = n - hop

    private val re = FloatArray(n)
    private val im = FloatArray(n)
    private val noise = FloatArray(bins)
    private val prevGain = FloatArray(bins) { 1f }
    private val smooth = FloatArray(bins)
    private val gain = FloatArray(bins)
    private var frames = 0

    // Leveller.
    private var env = 0f
    private var agc = 1f

    /** Enhances [input] (−1..1); returns the enhanced samples that are ready, in order. */
    fun process(input: FloatArray): FloatArray {
        for (x in input) {
            inBuf[inFill++] = x
            if (inFill == n) frame()
        }
        received += input.size
        return takeReady()
    }

    /** The samples still held back after the last [process]; call once at the end of the stream. */
    fun finish(): FloatArray {
        while (emitted + (pendingEnd - pendingStart) < received) {
            inBuf[inFill++] = 0f
            if (inFill == n) frame()
        }
        return takeReady()
    }

    private fun takeReady(): FloatArray {
        val count = min((pendingEnd - pendingStart).toLong(), received - emitted).toInt()
        val out = pending.copyOfRange(pendingStart, pendingStart + count)
        pendingStart += count
        emitted += count
        if (pendingStart == pendingEnd) { pendingStart = 0; pendingEnd = 0 }
        return out
    }

    private fun push(y: Float) {
        if (pendingEnd == pending.size) {
            val live = pendingEnd - pendingStart
            if (pendingStart > 0 && live < pending.size / 2) {
                System.arraycopy(pending, pendingStart, pending, 0, live)
            } else {
                pending = pending.copyOf(pending.size * 2).also { System.arraycopy(pending, pendingStart, it, 0, live) }
            }
            pendingStart = 0
            pendingEnd = live
        }
        pending[pendingEnd++] = y
    }

    private fun frame() {
        for (i in 0 until n) { re[i] = inBuf[i] * window[i]; im[i] = 0f }
        fft(re, im, inverse = false)

        // The first frames (one is half silence from priming) just seed the estimates.
        val seeding = frames < 3
        for (k in 0 until bins) {
            val p = re[k] * re[k] + im[k] * im[k] + 1e-12f
            // Power smoothed over a few frames: steadier than single frames.
            smooth[k] = if (seeding) p else 0.75f * smooth[k] + 0.25f * p
            // Noise floor = lower envelope of the smoothed power: quick to fall, slow to rise
            // (~+3 dB per 1.5 s), so speech pauses keep it accurate even while someone talks.
            noise[k] = when {
                seeding -> smooth[k]
                smooth[k] < noise[k] -> 0.8f * noise[k] + 0.2f * smooth[k]
                else -> noise[k] * 1.0045f
            }
            // Power subtraction (with headroom for the floor sitting below the mean noise level).
            val sub = 1f - overSubtract * noise[k] / smooth[k]
            var g = if (sub > 0f) sqrt(sub) else 0f
            val hz = k * binHz
            if (hz < 90f) g *= (hz / 90f) * (hz / 90f)
            g = max(g, if (hz < 90f) 0f else floor)
            // Open instantly, close gently: keeps word endings and avoids "musical" noise.
            g = max(g, 0.65f * prevGain[k])
            gain[k] = g
            prevGain[k] = g
        }
        // Smooth across neighbouring frequencies to avoid warbling artefacts.
        var prev = gain[0]
        for (k in 1 until bins - 1) {
            val cur = gain[k]
            gain[k] = 0.25f * prev + 0.5f * cur + 0.25f * gain[k + 1]
            prev = cur
        }
        for (k in 0 until bins) {
            val hz = k * binHz
            var g = gain[k]
            if (hz in 2_000f..5_000f) g *= presence
            re[k] *= g; im[k] *= g
            if (k in 1 until n / 2) { re[n - k] = re[k]; im[n - k] = -im[k] } // keep the signal real
        }
        fft(re, im, inverse = true)

        for (i in 0 until n) outAcc[i] += re[i] * window[i]
        // The first hop of the accumulator is complete: level, limit and emit it.
        for (i in 0 until hop) {
            val y = level(outAcc[i])
            if (toSkip > 0) toSkip-- else push(y)
        }
        System.arraycopy(outAcc, hop, outAcc, 0, n - hop)
        java.util.Arrays.fill(outAcc, n - hop, n, 0f)
        System.arraycopy(inBuf, hop, inBuf, 0, n - hop)
        inFill = n - hop
        frames++
    }

    /** Slow leveller toward a speech level, then a soft limiter. */
    private fun level(x: Float): Float {
        val a = abs(x)
        env = if (a > env) env + 0.01f * (a - env) else env + 0.0002f * (a - env)
        // Only steer while someone is audible (above ~-40 dBFS); hold the gain in pauses so the
        // cleaned-up background isn't pulled back up. ~1 s time constant: no pumping.
        // Lifts quiet speech only; loud parts are left to the limiter.
        if (env > 0.01f) agc += 0.00002f * (min(maxBoost, max(1f, TARGET / env)) - agc)
        val y = x * max(agc, 0.5f)
        return if (abs(y) <= 0.8f) y else (0.8f + 0.2f * kotlin.math.tanh((abs(y) - 0.8f) / 0.2f)) * kotlin.math.sign(y)
    }

    companion object {
        const val FRAME = 1024
        private const val TARGET = 0.18f

        /** In-place radix-2 complex FFT (size must be a power of two). */
        fun fft(re: FloatArray, im: FloatArray, inverse: Boolean) {
            val n = re.size
            var j = 0
            for (i in 1 until n) {
                var bit = n shr 1
                while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
                j = j xor bit
                if (i < j) {
                    var t = re[i]; re[i] = re[j]; re[j] = t
                    t = im[i]; im[i] = im[j]; im[j] = t
                }
            }
            var len = 2
            while (len <= n) {
                val ang = 2.0 * PI / len * (if (inverse) 1 else -1)
                val wr = cos(ang).toFloat()
                val wi = sin(ang).toFloat()
                var i = 0
                while (i < n) {
                    var cr = 1f
                    var ci = 0f
                    for (k in 0 until len / 2) {
                        val a = i + k
                        val b = a + len / 2
                        val xr = re[b] * cr - im[b] * ci
                        val xi = re[b] * ci + im[b] * cr
                        re[b] = re[a] - xr; im[b] = im[a] - xi
                        re[a] += xr; im[a] += xi
                        val ncr = cr * wr - ci * wi
                        ci = cr * wi + ci * wr
                        cr = ncr
                    }
                    i += len
                }
                len = len shl 1
            }
            if (inverse) for (i in 0 until n) { re[i] /= n; im[i] /= n }
        }
    }
}
