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
import kotlinx.coroutines.launch
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

    init {
        // The last request never finished: the runtime took the app down with it. Stop using that
        // planning model on this phone (the user can switch it back on in AI models).
        val crashed = prefs.inFlightModel
        if (crashed.isNotEmpty()) {
            prefs.inFlightModel = ""
            if (ModelCatalog.byId(crashed)?.reasoning == true) {
                prefs.crashedModels = prefs.crashedModels + crashed
                com.cinemind.core.common.Diagnostics.memoryNote(context, "Planning model $crashed crashed the app; switched off")
            }
        }
    }
    private var engine: Engine? = null
    private var loadedId: String? = null
    private var loadedWithAudio = false

    // A loaded model holds several GB (owner's phone: app killed for low memory at 5.1 GB PSS while
    // the model stayed loaded after speech-to-text). So it is unloaded after a short idle time,
    // and immediately when the app goes to the background.
    private val idleScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.Default)
    private var idleJob: kotlinx.coroutines.Job? = null

    private fun scheduleIdleUnload() {
        idleJob?.cancel()
        idleJob = idleScope.launch {
            kotlinx.coroutines.delay(IDLE_UNLOAD_MS)
            // Waits for a running request: the lock is held for its whole duration.
            lock.withLock {
                if (engine != null) {
                    unloadLocked()
                    com.cinemind.core.common.Diagnostics.memoryNote(context, "AI model unloaded (idle)")
                }
            }
        }
    }

    /** Frees the model now unless a request is running (then it is freed when that ends + idle). */
    fun releaseIfIdle() {
        idleScope.launch {
            if (lock.tryLock()) {
                try {
                    if (engine != null) {
                        unloadLocked()
                        com.cinemind.core.common.Diagnostics.memoryNote(context, "AI model unloaded (app in background)")
                    }
                } finally {
                    lock.unlock()
                }
            }
        }
    }

    /** "GPU" or "CPU" once loaded. */
    var backendName: String? = null
        private set

    /** The model that sees and hears (Gemma): the chosen one if installed, else any installed. */
    fun installedModel(): ModelSpec? {
        val preferred = ModelCatalog.byId(prefs.selectedModelId)?.takeIf { !it.reasoning }
        return preferred?.takeIf { store.isInstalled(it) } ?: ModelCatalog.VISUAL.firstOrNull { store.isInstalled(it) }
    }

    /**
     * The planning model (Spark) if one is installed and not turned off: the chosen one, else the
     * first installed. Null = text reasoning uses the picture model instead.
     */
    fun reasoningModel(): ModelSpec? {
        val choice = prefs.plannerModelId
        if (choice == AiPreferences.PLANNER_OFF) return null
        val crashed = prefs.crashedModels
        val preferred = ModelCatalog.byId(choice)?.takeIf { it.reasoning && it.id !in crashed }
        return preferred?.takeIf { store.isInstalled(it) } ?: ModelCatalog.PLANNERS.firstOrNull { store.isInstalled(it) && it.id !in crashed }
    }

    /** Every model whose file is installed and verified. */
    fun installedModels(): List<ModelSpec> = ModelCatalog.ALL.filter { store.isInstalled(it) }

    /** The model a text-only reasoning request goes to, or null when none is installed. */
    fun textModel(): ModelSpec? = reasoningModel() ?: installedModel()

    /** Id of the model in memory right now (one at a time), or null. */
    val loadedModelId: String? get() = loadedId

    val isAvailable: Boolean get() = installedModel() != null

    /** True if the installed model has an audio encoder (it may still fail to load on a device). */
    val supportsAudio: Boolean get() = installedModel()?.supportsAudio == true

    /** Loads the model if needed; returns load time in ms (0 if it was already loaded). */
    suspend fun ensureLoaded(withAudio: Boolean = false, spec: ModelSpec? = null): Long =
        try {
            lock.withLock { loadLocked(spec ?: installedModel() ?: throw ModelNotInstalledException(), withAudio) }
        } finally {
            scheduleIdleUnload()
        }

    private suspend fun loadLocked(spec: ModelSpec, withAudio: Boolean): Long = withContext(Dispatchers.Default) {
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
                        // Reasoning models think before answering, so they get a larger window.
                        maxNumTokens = if (spec.reasoning) 8192 else 4096,
                        maxNumImages = if (spec.supportsImages) 1 else null,
                        cacheDir = cache,
                    ),
                )
                e.initialize()
                engine = e
                loadedId = spec.id
                loadedWithAudio = withAudio
                backendName = make().name
                com.cinemind.core.common.Diagnostics.memoryNote(context, "AI model loaded: ${spec.id} on ${backendName}${if (withAudio) " + audio" else ""}")
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
     *
     * [reasoning] = a text-only planning / decision question: it goes to the planning model (Spark)
     * when one is installed, with room to think first; the thinking is never part of the answer.
     * [spec] forces one installed model (benchmark).
     */
    suspend fun ask(
        prompt: String,
        imageFile: File? = null,
        maxOutputTokens: Int = 256,
        audioFile: File? = null,
        reasoning: Boolean = false,
        spec: ModelSpec? = null,
    ): String = try {
        val model = spec ?: when {
            imageFile == null && audioFile == null && reasoning -> textModel()
            else -> installedModel()
        } ?: throw ModelNotInstalledException()
        // Room to think first; a slow model (Spark X2.5 4B, ~5 tok/s) gets a tighter cap so a plan
        // takes minutes, not a quarter of an hour.
        val budget = when {
            !model.reasoning -> maxOutputTokens
            model.tokensPerSecond < 10f -> (maxOutputTokens + 500).coerceAtMost(2_400)
            else -> (maxOutputTokens * 3).coerceIn(maxOutputTokens + 768, 6_000)
        }
        val raw = askLocked(model, prompt, imageFile, budget, audioFile)
        if (model.reasoning) stripThinking(raw) else raw
    } finally {
        // Also after a failure: a failed request must not leave the model in memory.
        scheduleIdleUnload()
    }

    private suspend fun askLocked(spec: ModelSpec, prompt: String, imageFile: File?, maxOutputTokens: Int, audioFile: File?): String = lock.withLock {
        loadLocked(spec, withAudio = audioFile != null)
        withContext(Dispatchers.Default) {
            val e = engine ?: throw ModelNotInstalledException()
            if (spec.reasoning) prefs.inFlightModel = spec.id
            try { e.createConversation(
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
            } } finally {
                // Finished (or failed normally): not a crash.
                if (spec.reasoning) prefs.inFlightModel = ""
            }
        }
    }

    suspend fun unload() = lock.withLock {
        idleJob?.cancel()
        if (engine != null) com.cinemind.core.common.Diagnostics.memoryNote(context, "AI model unloaded")
        unloadLocked()
    }

    private fun unloadLocked() {
        runCatching { engine?.close() }
        engine = null
        loadedId = null
        loadedWithAudio = false
        backendName = null
    }

    companion object {
        /** Removes a reasoning model's <think>…</think> part if the runtime left it in the answer. */
        fun stripThinking(text: String): String {
            val end = text.lastIndexOf("</think>")
            val answer = if (end >= 0) text.substring(end + "</think>".length) else text.replace("<think>", "")
            return answer.trim()
        }

        /** Unload after this long without requests (a reload takes a few seconds). */
        const val IDLE_UNLOAD_MS = 60_000L
    }
}
