package com.cinemind.core.domain.audio

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * OpenAI Whisper (MIT / Apache-2.0 weights via litert-community) on the phone: the parts that
 * are plain maths, kept here so they are unit-tested. The model itself runs in the captions
 * module with Google's LiteRT. Constants are the multilingual Whisper tokenizer's.
 */
object WhisperTokens {
    const val END = 50257
    const val START = 50258
    const val FIRST_LANGUAGE = 50259
    const val TRANSLATE = 50358
    const val TRANSCRIBE = 50359
    const val NO_SPEECH = 50362
    const val NO_TIMESTAMPS = 50363
    const val FIRST_TIMESTAMP = 50364
    const val VOCAB = 51865
    /** Each timestamp token is 20 ms. */
    const val TIMESTAMP_US = 20_000L

    /** Language codes in token order (50259 = en …). */
    val LANGUAGES: List<String> = (
        "en,zh,de,es,ru,ko,fr,ja,pt,tr,pl,ca,nl,ar,sv,it,id,hi,fi,vi,he,uk,el,ms,cs,ro,da,hu,ta,no,th,ur,hr,bg,lt,la,mi,ml,cy,sk,te,fa,lv,bn,sr,az," +
            "sl,kn,et,mk,br,eu,is,hy,ne,mn,bs,kk,sq,sw,gl,mr,pa,si,km,sn,yo,so,af,oc,ka,be,tg,sd,gu,am,yi,lo,uz,fo,ht,ps,tk,nn,mt,sa,lb,my,bo,tl,mg," +
            "as,tt,haw,ln,ha,ba,jw,su"
        ).split(',')

    val LAST_LANGUAGE: Int get() = FIRST_LANGUAGE + LANGUAGES.size - 1

    fun languageToken(code: String): Int? = LANGUAGES.indexOf(code.lowercase().substringBefore('-')).takeIf { it >= 0 }?.let { FIRST_LANGUAGE + it }

    fun languageOf(token: Int): String? = LANGUAGES.getOrNull(token - FIRST_LANGUAGE)

    /**
     * Whisper often labels spoken Hindi as Urdu (the same spoken language, written in a different
     * script), which put Urdu script into Hinglish captions. CineMind writes Hindi in Devanagari
     * or Roman letters, so a detected Urdu is treated as Hindi.
     */
    fun spokenLanguage(detected: String): String = if (detected == "ur") "hi" else detected

    fun isTimestamp(token: Int) = token >= FIRST_TIMESTAMP

    /** The decoder prompt: start, language (null = detect first), task; timestamps on. */
    fun prompt(languageToken: Int, translate: Boolean): IntArray =
        intArrayOf(START, languageToken, if (translate) TRANSLATE else TRANSCRIBE)
}

/**
 * Whisper's audio front end exactly as the reference implementation: 16 kHz mono, 30 s window
 * (480 000 samples, zero-padded), 400-point Hann STFT with 160-sample hop (centred, reflect
 * padding), power spectrum, 80 Slaney mel filters up to 8 kHz, log10, clamp to max − 8, then
 * (x + 4) / 4. Output is mel-major: [80][3000] flattened.
 */
object WhisperMel {
    const val SAMPLE_RATE = 16_000
    const val N_FFT = 400
    const val HOP = 160
    const val N_MELS = 80
    const val FRAMES = 3_000
    const val SAMPLES = SAMPLE_RATE * 30

    private val window = FloatArray(N_FFT) { (0.5 - 0.5 * cos(2.0 * PI * it / N_FFT)).toFloat() } // periodic Hann
    private val bins = N_FFT / 2 + 1
    private val cosT = Array(bins) { k -> FloatArray(N_FFT) { n -> cos(2.0 * PI * k * n / N_FFT).toFloat() } }
    private val sinT = Array(bins) { k -> FloatArray(N_FFT) { n -> sin(2.0 * PI * k * n / N_FFT).toFloat() } }
    val filters: Array<FloatArray> by lazy { melFilters() }

    fun compute(audio: FloatArray): FloatArray {
        val x = FloatArray(SAMPLES)
        System.arraycopy(audio, 0, x, 0, min(audio.size, SAMPLES))
        // Centre with reflect padding of N_FFT / 2 on both sides.
        val pad = N_FFT / 2
        val padded = FloatArray(SAMPLES + 2 * pad)
        for (i in 0 until pad) padded[i] = x[pad - i]
        System.arraycopy(x, 0, padded, pad, SAMPLES)
        for (i in 0 until pad) padded[pad + SAMPLES + i] = x[SAMPLES - 2 - i]

        val mel = Array(N_MELS) { FloatArray(FRAMES) }
        val power = FloatArray(bins)
        val frame = FloatArray(N_FFT)
        for (f in 0 until FRAMES) {
            val start = f * HOP
            for (n in 0 until N_FFT) frame[n] = padded[start + n] * window[n]
            for (k in 0 until bins) {
                var re = 0f
                var im = 0f
                val c = cosT[k]
                val s = sinT[k]
                for (n in 0 until N_FFT) { re += frame[n] * c[n]; im -= frame[n] * s[n] }
                power[k] = re * re + im * im
            }
            for (m in 0 until N_MELS) {
                val w = filters[m]
                var sum = 0f
                for (k in 0 until bins) sum += w[k] * power[k]
                mel[m][f] = log10(max(sum, 1e-10f))
            }
        }
        var top = Float.NEGATIVE_INFINITY
        for (m in 0 until N_MELS) for (f in 0 until FRAMES) top = max(top, mel[m][f])
        val out = FloatArray(N_MELS * FRAMES)
        for (m in 0 until N_MELS) for (f in 0 until FRAMES) out[m * FRAMES + f] = (max(mel[m][f], top - 8f) + 4f) / 4f
        return out
    }

    /** librosa.filters.mel(sr=16000, n_fft=400, n_mels=80, htk=False, norm="slaney"). */
    private fun melFilters(): Array<FloatArray> {
        val fMax = SAMPLE_RATE / 2.0
        val fftFreqs = DoubleArray(bins) { it * SAMPLE_RATE.toDouble() / N_FFT }
        val minMel = hzToMel(0.0)
        val maxMel = hzToMel(fMax)
        val melPts = DoubleArray(N_MELS + 2) { minMel + (maxMel - minMel) * it / (N_MELS + 1) }
        val hz = DoubleArray(N_MELS + 2) { melToHz(melPts[it]) }
        return Array(N_MELS) { m ->
            val lower = hz[m]
            val centre = hz[m + 1]
            val upper = hz[m + 2]
            val enorm = 2.0 / (upper - lower)
            FloatArray(bins) { k ->
                val f = fftFreqs[k]
                val up = (f - lower) / (centre - lower)
                val down = (upper - f) / (upper - centre)
                (max(0.0, min(up, down)) * enorm).toFloat()
            }
        }
    }

    // Slaney mel scale (linear below 1 kHz, logarithmic above), as librosa's default.
    private const val F_SP = 200.0 / 3
    private const val MIN_LOG_HZ = 1000.0
    private const val MIN_LOG_MEL = MIN_LOG_HZ / F_SP
    private val LOG_STEP = ln(6.4) / 27.0

    private fun hzToMel(f: Double): Double = if (f < MIN_LOG_HZ) f / F_SP else MIN_LOG_MEL + ln(f / MIN_LOG_HZ) / LOG_STEP
    private fun melToHz(m: Double): Double = if (m < MIN_LOG_MEL) F_SP * m else MIN_LOG_HZ * kotlin.math.exp(LOG_STEP * (m - MIN_LOG_MEL))
}

/** GPT-2 style byte-level BPE decoding for Whisper's vocabulary (id → text). */
class WhisperVocab(private val tokens: List<String>) {

    fun text(ids: List<Int>): String {
        val bytes = java.io.ByteArrayOutputStream()
        for (id in ids) {
            if (id < 0 || id >= tokens.size) continue // special tokens and timestamps carry no text
            for (ch in tokens[id]) {
                val b = BYTE_OF[ch] ?: continue
                bytes.write(b)
            }
        }
        return String(bytes.toByteArray(), Charsets.UTF_8)
    }

    companion object {
        /** The inverse of GPT-2's bytes_to_unicode table. */
        val BYTE_OF: Map<Char, Int> by lazy {
            val bs = ((33..126) + (161..172) + (174..255)).toMutableList()
            val cs = bs.toMutableList()
            var n = 0
            for (b in 0..255) if (b !in bs) { bs += b; cs += 256 + n; n++ }
            bs.indices.associate { cs[it].toChar() to bs[it] }
        }
    }
}

/** A stretch of speech with real start/end times from Whisper's timestamp tokens. */
data class SpeechSegment(val startUs: Long, val endUs: Long, val text: String)

object WhisperSegments {

    /**
     * Splits decoded tokens (after the prompt) into segments by their timestamp tokens.
     * [offsetUs] = where the 30 s window starts in the clip; [limitUs] = where its audio ends,
     * so a timestamp past the real audio is clamped rather than invented.
     */
    fun parse(tokens: List<Int>, vocab: WhisperVocab, offsetUs: Long, limitUs: Long): List<SpeechSegment> {
        val out = mutableListOf<SpeechSegment>()
        var start: Long? = null
        val text = mutableListOf<Int>()
        fun ts(t: Int) = (t - WhisperTokens.FIRST_TIMESTAMP) * WhisperTokens.TIMESTAMP_US
        for (t in tokens) {
            if (t == WhisperTokens.END) break
            if (WhisperTokens.isTimestamp(t)) {
                if (start == null || text.isEmpty()) {
                    start = ts(t)
                } else {
                    val words = vocab.text(text).trim()
                    if (words.isNotEmpty()) out += SpeechSegment(offsetUs + start, offsetUs + max(ts(t), start + 100_000), words)
                    text.clear()
                    start = null
                }
            } else if (t < WhisperTokens.END) {
                if (start == null) start = 0
                text += t
            }
        }
        // Text left without a closing timestamp runs to the end of the audio.
        if (text.isNotEmpty() && start != null) {
            val words = vocab.text(text).trim()
            if (words.isNotEmpty()) out += SpeechSegment(offsetUs + start, limitUs, words)
        }
        return out.map { it.copy(startUs = it.startUs.coerceAtMost(limitUs), endUs = it.endUs.coerceIn(it.startUs, limitUs)) }
            .filter { it.endUs > it.startUs }
    }

    /**
     * Greedy choice at one decoding step with Whisper's basic timestamp rules: the first token
     * is a timestamp, timestamps never go backwards, and two timestamps in a row end a segment.
     */
    fun pick(logits: FloatArray, from: Int, step: Int, lastTimestamp: Int, previousWasTimestamp: Boolean, beforePreviousWasTimestamp: Boolean): Int {
        fun best(range: IntRange): Int {
            var b = range.first
            var v = Float.NEGATIVE_INFINITY
            for (i in range) { val x = logits[from + i]; if (x > v) { v = x; b = i } }
            return b
        }
        val text = 0..WhisperTokens.END
        val stamps = max(WhisperTokens.FIRST_TIMESTAMP, lastTimestamp)..(WhisperTokens.VOCAB - 1)
        if (step == 0) return best(stamps)
        // After "…text <ts>" the next token must be a timestamp or the end; after "<ts><ts>" it must be text.
        if (previousWasTimestamp && !beforePreviousWasTimestamp) return best(stamps).let { s -> if (logits[from + WhisperTokens.END] > logits[from + s]) WhisperTokens.END else s }
        if (previousWasTimestamp && beforePreviousWasTimestamp) return best(text)
        val t = best(text)
        val s = best(stamps)
        return if (logits[from + s] > logits[from + t]) s else t
    }

    /** Rounds down to whole 20 ms steps (helper for tests and offsets). */
    fun steps(us: Long): Int = floor(us.toDouble() / WhisperTokens.TIMESTAMP_US).toInt()
}
