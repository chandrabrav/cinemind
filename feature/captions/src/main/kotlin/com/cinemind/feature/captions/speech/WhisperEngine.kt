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
class WhisperEngine(context: Context, model: File, threads: Int = 4) : Closeable {

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

    /** Language code Whisper hears in [audio] (16 kHz mono, first 30 s used). */
    suspend fun detectLanguage(audio: FloatArray): String {
        encode(audio)
        val tokens = IntArray(maxTokens).also { it[0] = WhisperTokens.START }
        decodeStep(tokens)
        var best = WhisperTokens.FIRST_LANGUAGE
        for (t in WhisperTokens.FIRST_LANGUAGE..WhisperTokens.LAST_LANGUAGE) if (logits[t] > logits[best]) best = t
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
            out += WhisperSegments.parse(decode(WhisperTokens.prompt(langToken, translate)), vocab, offsetUs, limitUs)
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
        while (n < maxTokens) {
            coroutineContext.ensureActive()
            decodeStep(tokens)
            val step = produced.size
            val prevTs = step >= 1 && WhisperTokens.isTimestamp(produced[step - 1])
            // Whisper counts "nothing before" as a timestamp, so the first text can follow the first stamp.
            val beforePrevTs = step < 2 || WhisperTokens.isTimestamp(produced[step - 2])
            val next = WhisperSegments.pick(logits, (n - 1) * vocabSize, step, lastTimestamp, prevTs, beforePrevTs)
            if (next == WhisperTokens.END) break
            if (WhisperTokens.isTimestamp(next)) lastTimestamp = next
            produced += next
            tokens[n++] = next
        }
        return produced
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

        fun direct(bytes: Int): ByteBuffer = ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder())
    }
}
