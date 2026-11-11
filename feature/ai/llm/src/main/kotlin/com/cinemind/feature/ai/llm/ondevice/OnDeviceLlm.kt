package com.cinemind.feature.ai.llm.ondevice

import android.content.Context
import com.cinemind.core.common.CineMindLogger
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.SamplerConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

class ModelNotInstalledException : Exception("No AI model is installed")

/** The installed model can't take audio (no audio encoder, or it failed to load on this phone). */
class AudioNotSupportedException(message: String) : Exception(message)

/**
 * The on-device language/vision/audio model, run with Google's LiteRT-LM. Loads lazily, prefers the
 * GPU and falls back to the CPU, and keeps one engine in memory until [unload]. Every request uses a
 * fresh conversation so prompts never leak into each other. Thread-safe (one request at a time).
 *
 * The audio encoder is loaded only when a request needs it (speech-to-text), so image/text work
 * keeps its smaller memory footprint; the engine is reloaded once when switching.
 */
@Singleton
class OnDeviceLlm @Inject constructor(
    @ApplicationContext private val context: Context,
    private val store: ModelStore,
    private val prefs: AiPreferences,
) {
    private val lock = Mutex()
    private var engine: Engine? = null
    private var loadedId: String? = null
    private var loadedWithAudio = false

    /** "GPU" or "CPU" once loaded. */
    var backendName: String? = null
        private set

    fun installedModel(): ModelSpec? {
        val preferred = ModelCatalog.byId(prefs.selectedModelId)
        return preferred?.takeIf { store.isInstalled(it) } ?: ModelCatalog.ALL.firstOrNull { store.isInstalled(it) }
    }

    val isAvailable: Boolean get() = installedModel() != null

    /** True if the installed model has an audio encoder (it may still fail to load on a device). */
    val supportsAudio: Boolean get() = installedModel()?.supportsAudio == true

    /** Loads the model if needed; returns load time in ms (0 if it was already loaded). */
    suspend fun ensureLoaded(withAudio: Boolean = false): Long = lock.withLock { loadLocked(withAudio) }

    private suspend fun loadLocked(withAudio: Boolean): Long = withContext(Dispatchers.Default) {
        val spec = installedModel() ?: throw ModelNotInstalledException()
        if (withAudio && !spec.supportsAudio) throw AudioNotSupportedException("${spec.name} can't listen to audio")
        if (engine != null && loadedId == spec.id && (loadedWithAudio || !withAudio)) return@withContext 0L
        unloadLocked()
        val start = System.nanoTime()
        val path = store.file(spec).absolutePath
        val cache = File(context.cacheDir, "litertlm").apply { mkdirs() }.absolutePath
        val backends = if (prefs.preferGpu) listOf<() -> Backend>({ Backend.GPU() }, { Backend.CPU() }) else listOf({ Backend.CPU() })
        var lastError: Throwable? = null
        for (make in backends) {
            try {
                val e = Engine(
                    EngineConfig(
                        modelPath = path,
                        backend = make(),
                        visionBackend = if (spec.supportsImages) make() else null,
                        // The audio encoder runs on the CPU (as in Google's reference app).
                        audioBackend = if (withAudio) Backend.CPU() else null,
                        maxNumTokens = 4096,
                        maxNumImages = 1,
                        cacheDir = cache,
                    ),
                )
                e.initialize()
                engine = e
                loadedId = spec.id
                loadedWithAudio = withAudio
                backendName = make().name
                return@withContext (System.nanoTime() - start) / 1_000_000
            } catch (t: Throwable) {
                CineMindLogger.w("Model load on ${make().name} (audio=$withAudio) failed: ${t.message}")
                lastError = t
            }
        }
        if (withAudio) throw AudioNotSupportedException("Couldn't load the speech part of the AI model: ${lastError?.message}")
        throw IllegalStateException("Couldn't load the AI model: ${lastError?.message}", lastError)
    }

    /**
     * Asks the model one question, optionally about one image (JPEG/PNG file) or one audio clip
     * (16 kHz mono WAV file, at most ~30 s), and returns its text.
     */
    suspend fun ask(
        prompt: String,
        imageFile: File? = null,
        maxOutputTokens: Int = 256,
        audioFile: File? = null,
    ): String = lock.withLock {
        loadLocked(withAudio = audioFile != null)
        withContext(Dispatchers.Default) {
            val e = engine ?: throw ModelNotInstalledException()
            e.createConversation(
                ConversationConfig(
                    // Near-greedy decoding: stable, parseable answers.
                    samplerConfig = SamplerConfig(topK = 1, topP = 0.95, temperature = 0.1),
                    maxOutputToken = maxOutputTokens,
                ),
            ).use { conversation ->
                val parts = buildList {
                    if (imageFile != null) add(Content.ImageFile(imageFile.absolutePath))
                    if (audioFile != null) add(Content.AudioFile(audioFile.absolutePath))
                    add(Content.Text(prompt))
                }
                conversation.sendMessage(Contents.of(*parts.toTypedArray())).toString()
            }
        }
    }

    suspend fun unload() = lock.withLock { unloadLocked() }

    private fun unloadLocked() {
        runCatching { engine?.close() }
        engine = null
        loadedId = null
        loadedWithAudio = false
        backendName = null
    }
}
