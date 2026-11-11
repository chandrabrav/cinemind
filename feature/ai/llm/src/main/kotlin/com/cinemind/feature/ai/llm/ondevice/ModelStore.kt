package com.cinemind.feature.ai.llm.ondevice

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Where models live on the phone (app-private storage, never in the APK) and which are verified.
 * A model counts as installed only after its SHA-256 matched the published value.
 */
@Singleton
class ModelStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val dir: File get() = File(context.filesDir, "models").apply { mkdirs() }
    private val prefs: SharedPreferences = context.getSharedPreferences("ai_models", Context.MODE_PRIVATE)

    fun file(spec: ModelSpec): File = File(dir, spec.fileName)
    fun partFile(spec: ModelSpec): File = File(dir, spec.fileName + ".part")

    fun isInstalled(spec: ModelSpec): Boolean {
        val f = file(spec)
        return f.exists() && f.length() == spec.sizeBytes && prefs.getString(verifiedKey(spec), null) == spec.sha256
    }

    fun markVerified(spec: ModelSpec) = prefs.edit().putString(verifiedKey(spec), spec.sha256).apply()

    fun partialBytes(spec: ModelSpec): Long = partFile(spec).takeIf { it.exists() }?.length() ?: 0L

    fun freeBytes(): Long = dir.usableSpace

    /** Removes the model and any partial download. */
    fun delete(spec: ModelSpec) {
        file(spec).delete()
        partFile(spec).delete()
        prefs.edit().remove(verifiedKey(spec)).apply()
    }

    private fun verifiedKey(spec: ModelSpec) = "verified_${spec.id}"
}

/** User choices for on-device AI (non-secret, so plain preferences are fine). */
@Singleton
class AiPreferences @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val prefs = context.getSharedPreferences("ai_settings", Context.MODE_PRIVATE)

    var useAiInSmartEdit: Boolean
        get() = prefs.getBoolean("use_ai", true)
        set(value) = prefs.edit().putBoolean("use_ai", value).apply()

    var wifiOnly: Boolean
        get() = prefs.getBoolean("wifi_only", true)
        set(value) = prefs.edit().putBoolean("wifi_only", value).apply()

    var selectedModelId: String
        get() = prefs.getString("model", ModelCatalog.GEMMA_4_E2B.id) ?: ModelCatalog.GEMMA_4_E2B.id
        set(value) = prefs.edit().putString("model", value).apply()

    var preferGpu: Boolean
        get() = prefs.getBoolean("prefer_gpu", true)
        set(value) = prefs.edit().putBoolean("prefer_gpu", value).apply()
}
