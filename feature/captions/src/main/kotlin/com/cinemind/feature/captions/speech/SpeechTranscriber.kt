package com.cinemind.feature.captions.speech

import android.content.Context
import android.net.Uri
import com.cinemind.core.common.CineMindLogger
import com.cinemind.core.domain.captions.Caption
import com.cinemind.core.domain.captions.CaptionScript
import com.cinemind.core.domain.captions.CaptionSource
import com.cinemind.core.domain.captions.SpokenLanguage
import com.cinemind.core.domain.captions.WordTimes
import com.cinemind.feature.ai.llm.ondevice.OnDeviceLlm
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.ensureActive
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext

/** One piece of the timeline whose sound should be transcribed. */
data class SpeechSource(
    val uri: String,
    val sourceInUs: Long,
    val sourceOutUs: Long,
    val timelineInUs: Long,
    /** Offset in the trimmed sound → offset on the timeline (differs when the clip's speed is changed). */
    val toTimelineOffset: (Long) -> Long = { it },
)

data class TranscriptionProgress(val fraction: Float, val message: String)

data class TranscriptionResult(
    val captions: List<Caption>,
    val segmentsHeard: Int,
    val clipsWithoutSound: Int,
    val loadMs: Long,
    val totalMs: Long,
)

/**
 * Speech-to-text for the caption layer, fully on the phone:
 * 1. decode each clip's sound (trimmed range only) to 16 kHz mono,
 * 2. find stretches of speech (gives each caption real start/end times),
 * 3. ask the on-device model (Gemma 4 audio encoder) to transcribe each stretch in the requested
 *    script, detecting the language (and Hindi-English switching),
 * 4. optionally translate to English as a separate step,
 * 5. place the result on the timeline as editable captions.
 *
 * Word timings inside a stretch are estimated from word length (the model gives no word times);
 * the UI says so.
 */
@Singleton
class SpeechTranscriber @Inject constructor(
    @ApplicationContext private val context: Context,
    private val llm: OnDeviceLlm,
) {
    private val extractor = AudioExtractor(context)

    suspend fun transcribe(
        projectId: String,
        sources: List<SpeechSource>,
        language: SpokenLanguage,
        script: CaptionScript,
        onProgress: (TranscriptionProgress) -> Unit,
    ): TranscriptionResult {
        val started = System.currentTimeMillis()
        // Whisper (when downloaded) gives real segment timings and a dedicated speech model;
        // otherwise Gemma 4's audio encoder transcribes, as before.
        llm.speechModel()?.let { spec ->
            runCatching { return transcribeWithWhisper(spec, projectId, sources, language, script, onProgress) }
                .onFailure { if (it is kotlinx.coroutines.CancellationException) throw it; CineMindLogger.w("Whisper failed, using Gemma: ${it.message}") }
        }
        onProgress(TranscriptionProgress(0f, "Loading the speech model…"))
        val loadMs = llm.ensureLoaded(withAudio = true)
        val tmpDir = File(context.cacheDir, "stt").apply { mkdirs() }
        val captions = mutableListOf<Caption>()
        var heard = 0
        var silentClips = 0
        val prompt = SpeechPrompts.transcribe(language, script)
        try {
            sources.forEachIndexed { index, src ->
                coroutineContext.ensureActive()
                val base = index.toFloat() / sources.size
                val span = 1f / sources.size
                onProgress(TranscriptionProgress(base, "Listening to clip ${index + 1} of ${sources.size}…"))
                val audio = try {
                    extractor.extract(Uri.parse(src.uri), src.sourceInUs, src.sourceOutUs)
                } catch (e: NoAudioException) {
                    silentClips++
                    return@forEachIndexed
                }
                val segments = SpeechSegmenter.segments(audio)
                if (segments.isEmpty()) silentClips++
                segments.forEachIndexed { s, seg ->
                    coroutineContext.ensureActive()
                    onProgress(TranscriptionProgress(base + span * s / segments.size, "Clip ${index + 1}: transcribing ${s + 1} of ${segments.size}…"))
                    val wav = File(tmpDir, "seg.wav")
                    Pcm.writeWav(wav, audio, seg.startSample, seg.endSample)
                    val reply = runCatching { llm.ask(prompt, audioFile = wav, maxOutputTokens = 256) }
                        .onFailure { CineMindLogger.w("Transcription failed: ${it.message}") }
                        .getOrThrow()
                    val t = SpeechPrompts.parse(reply, script, language) ?: return@forEachIndexed
                    heard++
                    var text = t.text
                    var source = CaptionSource.SPEECH
                    if (script == CaptionScript.ENGLISH && !t.language.startsWith("en")) {
                        text = llm.ask(SpeechPrompts.translateToEnglish(text), maxOutputTokens = 160).trim().removeSurrounding("\"").ifBlank { text }
                        source = CaptionSource.TRANSLATION
                    }
                    val start = src.timelineInUs + src.toTimelineOffset(seg.startUs)
                    val end = src.timelineInUs + src.toTimelineOffset(seg.endUs)
                    captions += Caption(
                        projectId = projectId,
                        startUs = start,
                        endUs = end,
                        text = text,
                        wordTimes = WordTimes.encode(WordTimes.estimate(text, start, end)),
                        language = t.language,
                        script = script.name,
                        source = source.name,
                        uncertain = t.uncertain,
                    )
                }
            }
        } finally {
            tmpDir.listFiles()?.forEach { it.delete() }
            // Free the model (several GB) as soon as the job ends, also when it failed or was cancelled.
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { llm.unload() }
        }
        onProgress(TranscriptionProgress(1f, "Done"))
        return TranscriptionResult(captions.sortedBy { it.startUs }, heard, silentClips, loadMs, System.currentTimeMillis() - started)
    }

    private suspend fun transcribeWithWhisper(
        spec: com.cinemind.feature.ai.llm.ondevice.ModelSpec,
        projectId: String,
        sources: List<SpeechSource>,
        language: SpokenLanguage,
        script: CaptionScript,
        onProgress: (TranscriptionProgress) -> Unit,
    ): TranscriptionResult = transcribeWithWhisperImpl(context, extractor, llm.fileOf(spec), projectId, sources, language, script, onProgress)

    /** Translates existing captions to English (a separate operation from transcription). */
    suspend fun translateToEnglish(captions: List<Caption>, onProgress: (TranscriptionProgress) -> Unit): List<Caption> = try {
        translateAll(captions, onProgress)
    } finally {
        kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { llm.unload() }
    }

    private suspend fun translateAll(captions: List<Caption>, onProgress: (TranscriptionProgress) -> Unit): List<Caption> =
        captions.mapIndexed { i, c ->
            coroutineContext.ensureActive()
            onProgress(TranscriptionProgress(i.toFloat() / captions.size, "Translating ${i + 1} of ${captions.size}…"))
            if (c.language.startsWith("en")) c
            else {
                val text = llm.ask(SpeechPrompts.translateToEnglish(c.text), maxOutputTokens = 160).trim().removeSurrounding("\"")
                if (text.isBlank()) c
                else c.copy(
                    text = text,
                    wordTimes = WordTimes.encode(WordTimes.estimate(text, c.startUs, c.endUs)),
                    script = CaptionScript.ENGLISH.name,
                    source = CaptionSource.TRANSLATION.name,
                )
            }
        }
}

/** Whisper path of [SpeechTranscriber] (kept apart so the Gemma path stays exactly as it was). */
private suspend fun SpeechTranscriber.transcribeWithWhisperImpl(
    context: Context,
    extractor: AudioExtractor,
    model: File,
    projectId: String,
    sources: List<SpeechSource>,
    language: SpokenLanguage,
    script: CaptionScript,
    onProgress: (TranscriptionProgress) -> Unit,
): TranscriptionResult {
    val started = System.currentTimeMillis()
    onProgress(TranscriptionProgress(0f, "Loading Whisper…"))
    val engine = WhisperEngine(context, model)
    val loadMs = System.currentTimeMillis() - started
    val captions = mutableListOf<Caption>()
    var heard = 0
    var silentClips = 0
    var lang: String? = when (language) {
        SpokenLanguage.AUTO -> null
        SpokenLanguage.HINGLISH -> "hi"
        else -> language.code
    }
    try {
        sources.forEachIndexed { index, src ->
            coroutineContext.ensureActive()
            val base = index.toFloat() / sources.size
            val span = 1f / sources.size
            onProgress(TranscriptionProgress(base, "Listening to clip ${index + 1} of ${sources.size}…"))
            val audio = try {
                extractor.extract(Uri.parse(src.uri), src.sourceInUs, src.sourceOutUs)
            } catch (e: NoAudioException) {
                silentClips++
                return@forEachIndexed
            }
            val stretches = SpeechSegmenter.segments(audio)
            if (stretches.isEmpty()) silentClips++
            stretches.forEachIndexed { s, seg ->
                coroutineContext.ensureActive()
                onProgress(TranscriptionProgress(base + span * s / stretches.size, "Clip ${index + 1}: Whisper is transcribing ${s + 1} of ${stretches.size}…"))
                val slice = audio.copyOfRange(seg.startSample, seg.endSample)
                val l = lang ?: engine.detectLanguage(slice).also { lang = it }
                val translate = script == CaptionScript.ENGLISH && l != "en"
                engine.transcribe(slice, l, translate).forEach { w ->
                    var text = w.text
                    if ((script == CaptionScript.ROMAN || script == CaptionScript.HINGLISH) && com.cinemind.core.domain.captions.Transliterator.containsDevanagari(text)) {
                        text = com.cinemind.core.domain.captions.Transliterator.devanagariToRoman(text)
                    }
                    if (text.isBlank()) return@forEach
                    heard++
                    val start = src.timelineInUs + src.toTimelineOffset(seg.startUs + w.startUs)
                    val end = src.timelineInUs + src.toTimelineOffset(seg.startUs + w.endUs)
                    captions += Caption(
                        projectId = projectId,
                        startUs = start,
                        endUs = end,
                        text = text,
                        wordTimes = WordTimes.encode(WordTimes.estimate(text, start, end)),
                        language = l,
                        script = script.name,
                        source = (if (translate) CaptionSource.TRANSLATION else CaptionSource.SPEECH).name,
                        uncertain = false,
                    )
                }
            }
        }
    } finally {
        engine.close()
    }
    onProgress(TranscriptionProgress(1f, "Done"))
    CineMindLogger.i("Whisper transcribed ${captions.size} captions in ${System.currentTimeMillis() - started} ms")
    return TranscriptionResult(captions.sortedBy { it.startUs }, heard, silentClips, loadMs, System.currentTimeMillis() - started)
}
