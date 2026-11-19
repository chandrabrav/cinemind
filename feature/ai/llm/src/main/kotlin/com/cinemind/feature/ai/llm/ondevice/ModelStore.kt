package com.cinemind.feature.ai.llm.ondevice

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.Properties
import javax.inject.Inject
import javax.inject.Singleton

/**
 * What we know about one model file on disk (a small `.properties` record next to the weights).
 * Survives app updates together with the file (both live in `filesDir/models`, which Android keeps
 * on update; only uninstalling or "Clear storage" removes it).
 */
data class ModelRecord(
    val id: String,
    val fileName: String,
    val sizeBytes: Long,
    val sha256: String,
    /** Runtime file format the model was verified for (see [ModelSpec.format]). */
    val format: String,
    val verifiedAtMs: Long,
    /** versionCode of the app that verified it (diagnostics only). */
    val verifiedByAppVersion: Long,
    val lastUsedMs: Long = 0,
) {
    fun toProperties(): Properties = Properties().apply {
        setProperty("id", id)
        setProperty("fileName", fileName)
        setProperty("sizeBytes", sizeBytes.toString())
        setProperty("sha256", sha256)
        setProperty("format", format)
        setProperty("verifiedAtMs", verifiedAtMs.toString())
        setProperty("verifiedByAppVersion", verifiedByAppVersion.toString())
        setProperty("lastUsedMs", lastUsedMs.toString())
    }

    companion object {
        fun fromProperties(p: Properties): ModelRecord? = runCatching {
            ModelRecord(
                id = p.getProperty("id")!!,
                fileName = p.getProperty("fileName")!!,
                sizeBytes = p.getProperty("sizeBytes")!!.toLong(),
                sha256 = p.getProperty("sha256")!!,
                format = p.getProperty("format") ?: "",
                verifiedAtMs = p.getProperty("verifiedAtMs")?.toLongOrNull() ?: 0,
                verifiedByAppVersion = p.getProperty("verifiedByAppVersion")?.toLongOrNull() ?: 0,
                lastUsedMs = p.getProperty("lastUsedMs")?.toLongOrNull() ?: 0,
            )
        }.getOrNull()
    }
}

/** What a model's files on disk mean for the app. */
enum class DiskStatus {
    /** Verified, matching and compatible: use it, never download it again. */
    READY,
    /** The right file is there but its verification record is missing: re-check locally (no download). */
    NEEDS_CHECK,
    /** A verified file for an older runtime format: keep it working until a replacement is verified. */
    INCOMPATIBLE,
    /** File has the wrong size (damaged or replaced): must be downloaded again. */
    DAMAGED,
    /** Only a partial download: resume it. */
    PARTIAL,
    ABSENT,
}

/** Pure decision from what is on disk (unit-tested). */
object ModelDiskRules {
    fun status(
        spec: ModelSpec,
        fileSize: Long?,
        record: ModelRecord?,
        legacyVerifiedSha: String?,
        partialBytes: Long,
    ): DiskStatus {
        if (fileSize == null) return if (partialBytes > 0) DiskStatus.PARTIAL else DiskStatus.ABSENT
        if (fileSize != spec.sizeBytes) return DiskStatus.DAMAGED
        val verifiedSha = record?.sha256 ?: legacyVerifiedSha
        if (verifiedSha == null || !verifiedSha.equals(spec.sha256, ignoreCase = true)) return DiskStatus.NEEDS_CHECK
        // Records written before formats were tracked (legacy marker) count as the current format.
        val format = record?.format?.ifEmpty { null } ?: spec.format
        return if (format == spec.format) DiskStatus.READY else DiskStatus.INCOMPATIBLE
    }
}

/**
 * Where models live on the phone (app-private permanent storage `filesDir/models`, never the cache,
 * never in the APK) and which are verified. A model counts as installed only after its SHA-256
 * matched the published value; that fact is stored in a record next to the file (and, for builds
 * before the record existed, in preferences).
 */
@Singleton
class ModelStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val dir: File get() = File(context.filesDir, "models").apply { mkdirs() }
    private val prefs: SharedPreferences = context.getSharedPreferences("ai_models", Context.MODE_PRIVATE)

    fun file(spec: ModelSpec): File = File(dir, spec.fileName)
    fun partFile(spec: ModelSpec): File = File(dir, spec.fileName + ".part")
    private fun recordFile(spec: ModelSpec): File = File(dir, spec.fileName + ".properties")

    fun record(spec: ModelSpec): ModelRecord? = recordFile(spec).takeIf { it.exists() }?.let { f ->
        runCatching { f.inputStream().use { Properties().apply { load(it) } } }.getOrNull()?.let(ModelRecord::fromProperties)
    }

    fun status(spec: ModelSpec): DiskStatus {
        val f = file(spec)
        return ModelDiskRules.status(
            spec = spec,
            fileSize = if (f.exists()) f.length() else null,
            record = record(spec),
            legacyVerifiedSha = prefs.getString(verifiedKey(spec), null),
            partialBytes = partialBytes(spec),
        )
    }

    fun isInstalled(spec: ModelSpec): Boolean = status(spec) == DiskStatus.READY

    /** Writes the record atomically (temp file + rename), so a crash never leaves half a record. */
    fun markVerified(spec: ModelSpec) {
        val record = ModelRecord(
            id = spec.id,
            fileName = spec.fileName,
            sizeBytes = spec.sizeBytes,
            sha256 = spec.sha256,
            format = spec.format,
            verifiedAtMs = System.currentTimeMillis(),
            verifiedByAppVersion = appVersionCode(),
        )
        writeRecord(spec, record)
        prefs.edit().putString(verifiedKey(spec), spec.sha256).apply()
    }

    fun touch(spec: ModelSpec) {
        record(spec)?.let { writeRecord(spec, it.copy(lastUsedMs = System.currentTimeMillis())) }
    }

    private fun writeRecord(spec: ModelSpec, record: ModelRecord) {
        val tmp = File(dir, spec.fileName + ".properties.tmp")
        tmp.outputStream().use { record.toProperties().store(it, "CineMind AI model record") }
        if (!tmp.renameTo(recordFile(spec))) {
            recordFile(spec).delete()
            tmp.renameTo(recordFile(spec))
        }
    }

    fun partialBytes(spec: ModelSpec): Long = partFile(spec).takeIf { it.exists() }?.length() ?: 0L

    fun freeBytes(): Long = dir.usableSpace

    fun usedBytes(spec: ModelSpec): Long = file(spec).takeIf { it.exists() }?.length() ?: 0L

    /** Moves a verified download into place in one atomic rename (same directory). */
    fun promotePart(spec: ModelSpec): Boolean {
        val target = file(spec)
        if (partFile(spec).renameTo(target)) return true
        // Some file systems refuse to rename over an existing file; only then remove the old one.
        target.delete()
        return partFile(spec).renameTo(target)
    }

    /** Removes the model, its record and any partial download (only when the user asks). */
    fun delete(spec: ModelSpec) {
        file(spec).delete()
        partFile(spec).delete()
        recordFile(spec).delete()
        prefs.edit().remove(verifiedKey(spec)).apply()
    }

    fun deletePart(spec: ModelSpec) {
        partFile(spec).delete()
    }

    private fun appVersionCode(): Long = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode
    }.getOrDefault(0L)

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

    /** Planning model id; "" = automatic (any installed one), [PLANNER_OFF] = don't use one. */
    var plannerModelId: String
        get() = prefs.getString("planner", "") ?: ""
        set(value) = prefs.edit().putString("planner", value).apply()

    /**
     * Crash guard: the model being asked right now. Written synchronously before a request and
     * cleared after it, so if the runtime kills the app (a native crash can't be caught) the next
     * start knows which model did it.
     */
    var inFlightModel: String
        get() = prefs.getString("in_flight", "") ?: ""
        set(value) { prefs.edit().putString("in_flight", value).commit() }

    /** Speech model id (Whisper); "" = automatic, [PLANNER_OFF] = use Gemma's audio instead. */
    var speechModelId: String
        get() = prefs.getString("speech", "") ?: ""
        set(value) = prefs.edit().putString("speech", value).apply()

    /** Planning models switched off after they crashed the app on this phone. */
    var crashedModels: Set<String>
        get() = prefs.getStringSet("crashed", emptySet()).orEmpty()
        set(value) = prefs.edit().putStringSet("crashed", value).apply()

    /**
     * Builds before 214 also blamed a planning model for freezes (ANRs) that the app itself caused
     * by closing the model on the main thread. Clears those once so Spark is used again.
     */
    fun forgiveFreezesOnce() {
        if (prefs.getBoolean("crash_guard_v2", false)) return
        prefs.edit().remove("crashed").putBoolean("crash_guard_v2", true).apply()
    }

    companion object {
        const val PLANNER_OFF = "off"
    }
}
