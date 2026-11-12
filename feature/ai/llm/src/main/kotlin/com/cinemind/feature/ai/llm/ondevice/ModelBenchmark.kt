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
) {
    /** Plain-text report the user can copy and send back. */
    fun report(): String = buildString {
        appendLine("CineMind AI benchmark")
        appendLine("Model: $model on $backend")
        appendLine("Load: $loadMs ms")
        appendLine("Text answer: $textMs ms")
        appendLine("Image understanding: $imageMs ms (JSON ${if (imageParsed) "ok" else "NOT parsed"})")
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
    /** Measures, then frees the model again (it holds several GB). */
    suspend fun run(): BenchmarkResult = try {
        measure()
    } finally {
        kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { llm.unload() }
    }

    private suspend fun measure(): BenchmarkResult {
        val spec = llm.installedModel() ?: throw ModelNotInstalledException()
        val before = availRamMb()
        llm.unload()

        val loadMs = llm.ensureLoaded()

        var t = System.nanoTime()
        val text = llm.ask("In one sentence, describe a sunny beach.", maxOutputTokens = 64)
        val textMs = (System.nanoTime() - t) / 1_000_000

        val image = syntheticFrame()
        t = System.nanoTime()
        val imageReply = llm.ask(Prompts.FRAME, imageFile = image, maxOutputTokens = 160)
        val imageMs = (System.nanoTime() - t) / 1_000_000
        image.delete()

        return BenchmarkResult(
            model = spec.name,
            backend = llm.backendName ?: "?",
            loadMs = loadMs,
            textMs = textMs,
            textReply = text.trim(),
            imageMs = imageMs,
            imageReply = imageReply.trim(),
            imageParsed = Prompts.parseInsight(imageReply) != null,
            availRamBeforeMb = before,
            availRamAfterMb = availRamMb(),
            thermalStatus = thermal(),
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
