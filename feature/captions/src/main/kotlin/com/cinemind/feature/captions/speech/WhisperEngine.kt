package com.cinemind.feature.captions.speech

import android.content.Context
import com.cinemind.core.domain.audio.SpeechSegment
import com.cinemind.core.domain.audio.WhisperMel
import com.cinemind.core.domain.audio.WhisperSegments
import com.cinemind.core.domain.audio.WhisperTokens
import com.cinemind.core.domain.audio.WhisperVocab
import kotlinx.coroutines.ensureActive
import org.tensorflow.lite.Interpreter
import java.io.Closeable
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.coroutines.coroutineContext

/**
 * OpenAI Whisper run on the phone with Google's LiteRT (litert-community's `encode` / `decode`
 * export; approach adapted from Google's Apache-2.0 litert-samples speech_recognition app).
 * One 30-second window at a time: log-mel → encoder → greedy decoder with timestamp tokens.
 * Times come from the model's own timestamp tokens, clamped to the real audio length.
 */
// Medium (hundreds of MB) gets more CPU threads; Tiny and Base are quick with 4.
class WhisperEngine(context: Context, model: File, threads: Int = if (model.length() > 300_000_000L) 6 else 4) : Closeable {

    private val interpreter = Interpreter(model, Interpreter.Options().setNumThreads(threads))
    private val vocab = WhisperVocab(context.assets.open("whisper/vocab.txt").bufferedReader(Charsets.UTF_8).readLines())

    private val encIn = interpreter.getSignatureInputs(ENCODE).single()
    private val encOut = interpreter.getSignatureOutputs(ENCODE).single()
    private val decIn = interpreter.getSignatureInputs(DECODE).toList()
    private val decOut = interpreter.getSignatureOutputs(DECODE).single()

    private val encShape = interpreter.getOutputTensorFromSignature(encOut, ENCODE).shape()
    private val maxTokens = interpreter.getInputTensorFromSignature(tokenInputName(), DECODE).shape().last()
    private val vocabSize = interpreter.getOutputTensorFromSignature(decOut, DECODE).shape().last()

    private val melBuf = direct(WhisperMel.N_MELS * WhisperMel.FRAMES * 4)
    private val encBuf = direct(encShape.fold(1, Int::times) * 4)
    private val tokenBuf = direct(maxTokens * 4)
    private val maskBuf = direct(maxTokens * maxTokens * 4).apply {
        // Causal mask: each position sees itself and earlier ones.
        val f = asFloatBuffer()
        for (r in 0 until maxTokens) for (c in 0 until maxTokens) f.put(if (c <= r) 0f else MASKED)
    }
    private val logitsBuf = direct(maxTokens * vocabSize * 4)
    private val logits = FloatArray(maxTokens * vocabSize)
    @Volatile private var lastNoSpeech = 0f
    @Volatile private var lastAvgLogProb = 0f

    /** Language code Whisper hears in [audio] (16 kHz mono, first 30 s used). */
    suspend fun detectLanguage(audio: FloatArray, among: Set<String> = WhisperTokens.likelyLanguages()): String {
        encode(audio)
        val tokens = IntArray(maxTokens).also { it[0] = WhisperTokens.START }
        decodeStep(tokens)
        // Only languages this phone's user plausibly speaks: short or noisy clips otherwise come out as Japanese or Urdu.
        val candidates = among.mapNotNull { WhisperTokens.languageToken(it) }.ifEmpty { (WhisperTokens.FIRST_LANGUAGE..WhisperTokens.LAST_LANGUAGE).toList() }
        var best = candidates.first()
        for (t in candidates) if (logits[t] > logits[best]) best = t
        return WhisperTokens.languageOf(best) ?: "en"
    }

    /**
     * Transcribes [audio] (16 kHz mono) in 30-second windows. [language] = a Whisper language
     * code; [translate] = English translation instead of transcription.
     */
    suspend fun transcribe(audio: FloatArray, language: String, translate: Boolean): List<SpeechSegment> {
        val langToken = WhisperTokens.languageToken(language) ?: WhisperTokens.languageToken("en")!!
        val out = mutableListOf<SpeechSegment>()
        var start = 0
        while (start < audio.size) {
            coroutineContext.ensureActive()
            val end = minOf(audio.size, start + WhisperMel.SAMPLES)
            val window = audio.copyOfRange(start, end)
            val offsetUs = start * 1_000_000L / WhisperMel.SAMPLE_RATE
            val limitUs = end * 1_000_000L / WhisperMel.SAMPLE_RATE
            encode(window)
            val tokens = decode(WhisperTokens.prompt(langToken, translate))
            // Music, wind or silence: Whisper says so itself (no-speech chance) and is unsure of its words.
            if (lastNoSpeech > NO_SPEECH_CHANCE && lastAvgLogProb < UNSURE_LOGPROB) { start = end; continue }
            if (lastAvgLogProb < GARBAGE_LOGPROB) { start = end; continue }
            val written = if (translate) "en" else language
            out += WhisperSegments.parse(tokens, vocab, offsetUs, limitUs).mapNotNull { s ->
                com.cinemind.core.domain.audio.SpeechCleanup.clean(s.text, written)?.let { s.copy(text = it) }
            }
            start = end
        }
        return out
    }

    private fun encode(window: FloatArray) {
        val mel = WhisperMel.compute(window)
        melBuf.clear()
        melBuf.asFloatBuffer().put(mel)
        encBuf.clear()
        interpreter.runSignature(mapOf(encIn to melBuf.apply { rewind() }), mapOf(encOut to encBuf), ENCODE)
    }

    /** Greedy decoding with Whisper's timestamp rules; returns the tokens after the prompt. */
    private suspend fun decode(prompt: IntArray): List<Int> {
        val tokens = IntArray(maxTokens)
        prompt.copyInto(tokens)
        var n = prompt.size
        val produced = mutableListOf<Int>()
        var lastTimestamp = WhisperTokens.FIRST_TIMESTAMP
        var logProbSum = 0.0
        lastNoSpeech = 0f
        while (n < maxTokens) {
            coroutineContext.ensureActive()
            decodeStep(tokens)
            // The chance of "no speech" is read right after the start token (row 0), as Whisper does.
            if (produced.isEmpty()) lastNoSpeech = softmaxAt(0, WhisperTokens.NO_SPEECH)
            val step = produced.size
            val prevTs = step >= 1 && WhisperTokens.isTimestamp(produced[step - 1])
            // Whisper counts "nothing before" as a timestamp, so the first text can follow the first stamp.
            val beforePrevTs = step < 2 || WhisperTokens.isTimestamp(produced[step - 2])
            val next = WhisperSegments.pick(logits, (n - 1) * vocabSize, step, lastTimestamp, prevTs, beforePrevTs)
            if (next == WhisperTokens.END) break
            logProbSum += kotlin.math.ln(softmaxAt(n - 1, next).toDouble().coerceAtLeast(1e-12))
            if (WhisperTokens.isTimestamp(next)) lastTimestamp = next
            produced += next
            tokens[n++] = next
            // Stuck in a loop ("lo lo lo…"): stop rather than fill the window with it.
            if (looping(produced)) break
        }
        lastAvgLogProb = if (produced.isEmpty()) 0f else (logProbSum / produced.size).toFloat()
        return produced
    }

    /** Chance of [token] at decoder position [row] (softmax over the vocabulary). */
    private fun softmaxAt(row: Int, token: Int): Float {
        val from = row * vocabSize
        var max = Float.NEGATIVE_INFINITY
        for (i in 0 until vocabSize) if (logits[from + i] > max) max = logits[from + i]
        var sum = 0.0
        for (i in 0 until vocabSize) sum += kotlin.math.exp((logits[from + i] - max).toDouble())
        return (kotlin.math.exp((logits[from + token] - max).toDouble()) / sum).toFloat()
    }

    /** The last 12 text tokens use 3 or fewer different tokens. */
    private fun looping(produced: List<Int>): Boolean {
        val text = produced.asReversed().asSequence().filter { !WhisperTokens.isTimestamp(it) }.take(12).toList()
        return text.size == 12 && text.toSet().size <= 3
    }

    private fun decodeStep(tokens: IntArray) {
        tokenBuf.clear()
        tokenBuf.asIntBuffer().put(tokens)
        logitsBuf.clear()
        val inputs = mutableMapOf<String, Any>()
        decIn.forEach { name ->
            inputs[name] = when (name) {
                decIn[0] -> encBuf.apply { rewind() }
                tokenInputName() -> tokenBuf.apply { rewind() }
                else -> maskBuf.apply { rewind() }
            }
        }
        interpreter.runSignature(inputs, mapOf(decOut to logitsBuf), DECODE)
        logitsBuf.rewind()
        logitsBuf.asFloatBuffer().get(logits)
    }

    /** The decoder's token input is its second input (encoder states, tokens, mask). */
    private fun tokenInputName() = interpreter.getSignatureInputs(DECODE)[1]

    override fun close() = interpreter.close()

    private companion object {
        const val ENCODE = "encode"
        const val DECODE = "decode"
        const val MASKED = -0.7f * Float.MAX_VALUE
        const val NO_SPEECH_CHANCE = 0.6f
        const val UNSURE_LOGPROB = -1.0f
        const val GARBAGE_LOGPROB = -1.6f

        fun direct(bytes: Int): ByteBuffer = ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder())
    }
}
