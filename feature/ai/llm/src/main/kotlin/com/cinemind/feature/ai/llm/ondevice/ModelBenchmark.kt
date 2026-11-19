package com.cinemind.feature.ai.llm.ondevice

import android.app.ActivityManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.os.PowerManager
import com.cinemind.core.domain.aiedit.EditPlanJson
import com.cinemind.core.domain.aiedit.EditPlanValidator
import com.cinemind.core.domain.aiedit.FootageMoment
import com.cinemind.core.domain.aiedit.FootageSource
import com.cinemind.core.domain.aiedit.PlanRequest
import com.cinemind.core.domain.aiedit.PlanSource
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

data class BenchmarkResult(
    val model: String,
    val backend: String,
    val loadMs: Long,
    val textMs: Long,
    val textReply: String,
    val imageMs: Long,
    val imageReply: String,
    val imageParsed: Boolean,
    val availRamBeforeMb: Long,
    val availRamAfterMb: Long,
    val thermalStatus: String,
    /** CineMind edit-plan task: time, valid JSON, and whether the validator found usable shots. */
    val planMs: Long = -1,
    val planJson: Boolean = false,
    val planUsable: Boolean = false,
    val planShots: Int = 0,
    val planChars: Int = 0,
) {
    /** Plain-text report the user can copy and send back. */
    fun report(): String = buildString {
        appendLine("CineMind AI benchmark")
        appendLine("Model: $model on $backend")
        appendLine("Load: $loadMs ms")
        appendLine("Text answer: $textMs ms")
        if (imageMs >= 0) appendLine("Image understanding: $imageMs ms (JSON ${if (imageParsed) "ok" else "NOT parsed"})") else appendLine("Image understanding: not supported (planning model)")
        if (planMs >= 0) appendLine("Edit plan: $planMs ms, $planChars chars, JSON ${if (planJson) "ok" else "NOT parsed"}, validator ${if (planUsable) "usable ($planShots shots)" else "NOT usable"}")
        appendLine("Free RAM: ${availRamBeforeMb} MB before, ${availRamAfterMb} MB after")
        appendLine("Thermal: $thermalStatus")
        appendLine("Image reply: ${imageReply.take(300)}")
    }
}

/**
 * Measures the installed model on this phone: cold load, a short text answer and one image
 * analysis (a synthetic beach scene, so no user media is needed). Used to decide E2B vs E4B and
 * GPU vs CPU from real numbers, as the master spec requires.
 */
class ModelBenchmark @Inject constructor(
    @ApplicationContext private val context: Context,
    private val llm: OnDeviceLlm,
) {
    /** Measures the picture model, then frees it again (it holds several GB). */
    suspend fun run(): BenchmarkResult = runAll().first()

    /**
     * Measures every installed model, one after another (only one is ever in memory), on the same
     * CineMind tasks, so Gemma and Spark are compared on this phone rather than by public scores.
     */
    suspend fun runAll(): List<BenchmarkResult> {
        val specs = llm.installedModels().ifEmpty { throw ModelNotInstalledException() }
        return specs.map { spec ->
            try {
                measure(spec)
            } finally {
                kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { llm.unload() }
            }
        }
    }

    private suspend fun measure(spec: ModelSpec): BenchmarkResult {
        val before = availRamMb()
        llm.unload()

        val loadMs = llm.ensureLoaded(spec = spec)

        var t = System.nanoTime()
        val text = llm.ask("In one sentence, describe a sunny beach.", maxOutputTokens = 64, reasoning = spec.reasoning, spec = spec)
        val textMs = (System.nanoTime() - t) / 1_000_000

        var imageMs = -1L
        var imageReply = ""
        if (spec.supportsImages) {
            val image = syntheticFrame()
            t = System.nanoTime()
            imageReply = llm.ask(Prompts.FRAME, imageFile = image, maxOutputTokens = 160, spec = spec)
            imageMs = (System.nanoTime() - t) / 1_000_000
            image.delete()
        }

        // The real job: an edit plan for a small made-up project, checked by the app's validator.
        val (sources, request) = PlanBenchmark.project()
        t = System.nanoTime()
        val planText = runCatching {
            llm.ask(
                EditPlanJson.systemPrompt + "\nAnswer with one JSON object only, matching this JSON schema:\n" +
                    org.json.JSONObject(EditPlanJson.schema).toString() + "\n\n" + EditPlanJson.userPrompt(request),
                maxOutputTokens = 2_000,
                reasoning = spec.reasoning,
                spec = spec,
            )
        }.getOrDefault("")
        val planMs = (System.nanoTime() - t) / 1_000_000
        val parsed = EditPlanJson.parse(planText)
        val checked = parsed?.let { EditPlanValidator.validate(it, sources, request.request) }

        return BenchmarkResult(
            model = spec.name,
            backend = llm.backendName ?: "?",
            loadMs = loadMs,
            textMs = textMs,
            textReply = text.trim(),
            imageMs = imageMs,
            imageReply = imageReply.trim(),
            imageParsed = spec.supportsImages && Prompts.parseInsight(imageReply) != null,
            availRamBeforeMb = before,
            availRamAfterMb = availRamMb(),
            thermalStatus = thermal(),
            planMs = planMs,
            planJson = parsed != null,
            planUsable = checked?.usable == true,
            planShots = checked?.plan?.shots?.size ?: 0,
            planChars = planText.length,
        )
    }

    private fun availRamMb(): Long {
        val info = ActivityManager.MemoryInfo()
        context.getSystemService(ActivityManager::class.java)?.getMemoryInfo(info)
        return info.availMem / (1024 * 1024)
    }

    private fun thermal(): String = when (context.getSystemService(PowerManager::class.java)?.currentThermalStatus) {
        PowerManager.THERMAL_STATUS_NONE -> "normal"
        PowerManager.THERMAL_STATUS_LIGHT -> "light"
        PowerManager.THERMAL_STATUS_MODERATE -> "moderate"
        PowerManager.THERMAL_STATUS_SEVERE -> "severe"
        PowerManager.THERMAL_STATUS_CRITICAL, PowerManager.THERMAL_STATUS_EMERGENCY, PowerManager.THERMAL_STATUS_SHUTDOWN -> "critical"
        else -> "unknown"
    }

    /** A simple sunset-over-the-sea picture drawn in code. */
    private suspend fun syntheticFrame(): File = withContext(Dispatchers.Default) {
        val w = 768
        val h = 432
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val sky = Paint().apply { shader = LinearGradient(0f, 0f, 0f, h * 0.6f, Color.rgb(255, 140, 60), Color.rgb(255, 210, 150), Shader.TileMode.CLAMP) }
        c.drawRect(0f, 0f, w.toFloat(), h * 0.6f, sky)
        c.drawCircle(w * 0.5f, h * 0.55f, h * 0.12f, Paint().apply { color = Color.rgb(255, 235, 120); isAntiAlias = true })
        c.drawRect(0f, h * 0.6f, w.toFloat(), h * 0.8f, Paint().apply { color = Color.rgb(30, 90, 160) })
        c.drawRect(0f, h * 0.8f, w.toFloat(), h.toFloat(), Paint().apply { color = Color.rgb(230, 200, 140) })
        val f = File(context.cacheDir, "benchmark_frame.jpg")
        f.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        bmp.recycle()
        f
    }
}

/** A fixed, made-up travel project for comparing planners (same input for every model). */
internal object PlanBenchmark {
    fun project(): Pair<List<PlanSource>, PlanRequest> {
        val labels = listOf("beach walk", "sunset over the sea", "street food market")
        val sources = labels.mapIndexed { i, l -> PlanSource("bench-$i", isVideo = true, durationUs = 12_000_000L, label = l) }
        val request = PlanRequest(
            request = "Make a 20 second cinematic travel reel of my Goa trip, calm and warm",
            style = "Travel",
            pacing = "Calm",
            targetSeconds = 20,
            aspect = "9:16",
            sources = labels.mapIndexed { i, l ->
                FootageSource(
                    isVideo = true,
                    seconds = 12.0,
                    moments = (0 until 8).map { k -> FootageMoment(k * 1.5, quality = 0.5f + 0.05f * ((k + i) % 5), brightness = 0.45f + 0.02f * k, motion = 0.2f + 0.05f * i, seen = if (k == 3) l else "") },
                    label = l,
                )
            },
            speech = "",
            hasMusic = true,
            filmedOn = null,
            baseline = null,
        )
        return sources to request
    }
}
