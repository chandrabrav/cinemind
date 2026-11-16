package com.cinemind.feature.editor.timeline.voiceover

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import java.io.File
import java.util.Locale
import java.util.UUID

/** A voice the phone can speak with (from its own text-to-speech engine). */
data class TtsVoice(val name: String, val label: String, val locale: Locale, val offline: Boolean)

/**
 * Text-to-speech with the phone's own engine (Google / Samsung / vivo…): no account, no upload;
 * offline voices are preferred. Writes WAV files that go on the voiceover track.
 */
class SpeechSynth(private val context: Context) {

    private var tts: TextToSpeech? = null
    private var started = false

    private suspend fun engine(): TextToSpeech {
        tts?.let { if (started) return it }
        val done = CompletableDeferred<Boolean>()
        val t = TextToSpeech(context.applicationContext) { status -> done.complete(status == TextToSpeech.SUCCESS) }
        tts = t
        val ok = withTimeout(8_000) { done.await() }
        check(ok) { "This phone's text-to-speech engine didn't start" }
        started = true
        return t
    }

    /** Voices for the languages people here use most, offline first. */
    suspend fun voices(): List<TtsVoice> {
        val t = engine()
        val wanted = listOf("en-IN", "hi-IN", "en-US", "en-GB", "bn-IN", "ta-IN", "te-IN", "mr-IN", "pa-IN", "ur-IN")
        val all = runCatching { t.voices.orEmpty() }.getOrDefault(emptySet())
            .filter { !it.features.orEmpty().contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) }
        val picked = all
            .filter { v -> wanted.any { w -> v.locale.toLanguageTag().equals(w, ignoreCase = true) } }
            .sortedWith(compareBy<Voice>({ wanted.indexOfFirst { w -> it.locale.toLanguageTag().equals(w, true) } }, { it.isNetworkConnectionRequired }, { it.name }))
        val list = (picked.ifEmpty { all.sortedBy { it.isNetworkConnectionRequired }.take(12) })
        // Number the voices per language ("English (India) 1, 2…") so similar names are distinguishable.
        val counts = HashMap<String, Int>()
        return list.map { v ->
            val lang = v.locale.getDisplayName(Locale.ENGLISH)
            val n = (counts[lang] ?: 0) + 1
            counts[lang] = n
            TtsVoice(v.name, "$lang $n" + if (v.isNetworkConnectionRequired) " (online)" else "", v.locale, !v.isNetworkConnectionRequired)
        }
    }

    /** Speaks [text] into a WAV file in app storage. [rate] and [pitch]: 1 = normal. */
    suspend fun synthesize(text: String, voiceName: String?, rate: Float, pitch: Float): File {
        val t = engine()
        val max = TextToSpeech.getMaxSpeechInputLength()
        require(text.isNotBlank()) { "Type something to say" }
        require(text.length <= max) { "That's too long for one go (max $max characters); split it into parts" }
        voiceName?.let { n -> t.voices?.firstOrNull { it.name == n }?.let { t.voice = it } }
        t.setSpeechRate(rate.coerceIn(0.5f, 2f))
        t.setPitch(pitch.coerceIn(0.5f, 2f))
        val dir = File(context.filesDir, "tts").apply { mkdirs() }
        val out = File(dir, "speech_" + System.currentTimeMillis() + ".wav")
        val id = UUID.randomUUID().toString()
        val done = CompletableDeferred<Boolean>()
        t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit
            override fun onDone(utteranceId: String?) { if (utteranceId == id) done.complete(true) }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) { if (utteranceId == id) done.complete(false) }
            override fun onError(utteranceId: String?, errorCode: Int) { if (utteranceId == id) done.complete(false) }
        })
        check(t.synthesizeToFile(text, null, out, id) == TextToSpeech.SUCCESS) { "Text-to-speech refused the text" }
        val ok = withTimeout(120_000) { done.await() }
        check(ok && out.exists() && out.length() > 44) { "Text-to-speech couldn't make the audio (is the voice installed?)" }
        return out
    }

    fun release() {
        runCatching { tts?.shutdown() }
        tts = null
        started = false
    }
}
