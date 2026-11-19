package com.cinemind.core.common

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.Properties

/**
 * On-device diagnostics so real failures (e.g. the app closing during export on a phone) can be
 * diagnosed without a computer: Java crashes are written to files, Android's own record of why the
 * process ended (crash, native crash with tombstone, low memory, ANR) is read on the next start,
 * and long jobs (export) keep a small journal of the stage they reached. Nothing leaves the phone;
 * the user copies the report if they want to share it. No media, no personal data.
 */
object Diagnostics {

    private const val MAX_CRASH_FILES = 5
    private val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT)

    private fun dir(context: Context) = File(context.filesDir, "diagnostics").apply { mkdirs() }

    /** Records uncaught Java/Kotlin crashes (incl. OutOfMemoryError), then lets the app crash as before. */
    fun install(context: Context) {
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                val sw = StringWriter()
                error.printStackTrace(PrintWriter(sw))
                val text = buildString {
                    appendLine("Crash at ${stamp.format(Date())} on thread \"${thread.name}\"")
                    appendLine(appInfo(app))
                    appendLine("Export journal at crash: ${journalText(app) ?: "none"}")
                    append(sw.toString().take(20_000))
                }
                File(dir(app), "crash-${System.currentTimeMillis()}.txt").writeText(text)
                dir(app).listFiles { f -> f.name.startsWith("crash-") }?.sortedByDescending { it.name }
                    ?.drop(MAX_CRASH_FILES)?.forEach { it.delete() }
            }
            previous?.uncaughtException(thread, error)
        }
    }

    // ---------------------------------------------------------------- job journal (export)

    private fun journal(context: Context) = File(dir(context), "export-journal.properties")

    /** Records the stage a long job reached (written synchronously so it survives a crash). */
    fun journalStage(context: Context, stage: String, details: Map<String, String> = emptyMap()) {
        runCatching {
            val p = readJournal(context) ?: Properties()
            if (p.getProperty("startedAt") == null) p.setProperty("startedAt", stamp.format(Date()))
            p.setProperty("stage", stage)
            p.setProperty("stageAt", stamp.format(Date()))
            details.forEach { (k, v) -> p.setProperty(k, v.take(500)) }
            journal(context).outputStream().use { p.store(it, "CineMind export journal") }
        }
        memoryNote(context, "export: $stage")
    }

    /** Starts a fresh journal for a new job. */
    fun journalStart(context: Context, details: Map<String, String>) {
        runCatching { journal(context).delete() }
        journalStage(context, "starting", details)
    }

    /** The job ended normally (success, handled failure or cancel): no "interrupted" warning next time. */
    fun journalEnd(context: Context, outcome: String) {
        journalStage(context, "ended: $outcome")
        runCatching { journal(context).renameTo(File(dir(context), "export-journal-last.properties")) }
    }

    /** A journal that never reached its end means the process died during the job. */
    fun interruptedJob(context: Context): Properties? = readJournal(context)

    fun clearInterrupted(context: Context) {
        runCatching { journal(context).renameTo(File(dir(context), "export-journal-interrupted.properties")) }
    }

    private fun readJournal(context: Context): Properties? = journal(context).takeIf { it.exists() }?.let { f ->
        runCatching { f.inputStream().use { Properties().apply { load(it) } } }.getOrNull()
    }

    private fun journalText(context: Context): String? = readJournal(context)?.let { p ->
        p.stringPropertyNames().sorted().joinToString(", ") { "$it=${p.getProperty(it)}" }
    }

    // ---------------------------------------------------------------- report

    // ---------------------------------------------------------------- memory log

    private const val MAX_MEMORY_LINES = 60

    private fun memoryLog(context: Context) = File(dir(context), "memory-log.txt")

    /**
     * Appends one line with the app's memory use (total PSS incl. GPU/native, Java heap, native heap)
     * and the phone's free RAM, at moments that matter (AI model load/unload, export stages, the
     * system asking the app to free memory). Keeps the last [MAX_MEMORY_LINES] lines.
     */
    // Reading the app's memory (Debug.getPss) takes long for a process holding a multi-GB model,
    // and notes are written from the main thread (trim-memory, lifecycle): do it in the background.
    private val noteExecutor = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "diagnostics").apply { isDaemon = true } }

    fun memoryNote(context: Context, label: String) {
        val app = context.applicationContext
        val at = stamp.format(Date())
        runCatching { noteExecutor.execute { writeMemoryNote(app, label, at) } }
    }

    private fun writeMemoryNote(context: Context, label: String, at: String) {
        runCatching {
            val rt = Runtime.getRuntime()
            val am = context.getSystemService(ActivityManager::class.java)
            val mem = ActivityManager.MemoryInfo().also { am?.getMemoryInfo(it) }
            val line = "$at · $label · app PSS ${android.os.Debug.getPss() / 1024} MB · " +
                "Java ${(rt.totalMemory() - rt.freeMemory()) / (1 shl 20)} MB · native ${android.os.Debug.getNativeHeapAllocatedSize() / (1 shl 20)} MB · " +
                "phone free ${mem.availMem / (1 shl 20)} MB${if (mem.lowMemory) " (LOW)" else ""}"
            val f = memoryLog(context)
            val lines = (if (f.exists()) f.readLines() else emptyList()) + line
            f.writeText(lines.takeLast(MAX_MEMORY_LINES).joinToString("\n", postfix = "\n"))
        }
    }

    /** Deletes recorded crashes and job journals (after they were sent), so the next report is clean. */
    fun clear(context: Context) {
        dir(context).listFiles()?.forEach { it.delete() }
    }

    private fun prefs(c: Context) = c.getSharedPreferences("diagnostics", Context.MODE_PRIVATE)

    /**
     * When the app last crashed (Java or native crash, or "not responding") and the user has not
     * yet been asked about it, else null. Drives the "closed unexpectedly" card on the home screen.
     */
    fun unseenCrash(context: Context): Long? = runCatching {
        val seen = prefs(context).getLong("crash_seen", 0)
        val am = context.getSystemService(ActivityManager::class.java)
        val fromAndroid = am?.getHistoricalProcessExitReasons(context.packageName, 0, 5).orEmpty()
            .filter { it.reason == ApplicationExitInfo.REASON_CRASH || it.reason == ApplicationExitInfo.REASON_CRASH_NATIVE || it.reason == ApplicationExitInfo.REASON_ANR }
            .maxOfOrNull { it.timestamp } ?: 0L
        val fromFiles = dir(context).listFiles { f -> f.name.startsWith("crash-") }?.maxOfOrNull { it.lastModified() } ?: 0L
        maxOf(fromAndroid, fromFiles).takeIf { it > seen }
    }.getOrNull()

    /** The user was asked about the last crash (sent or dismissed). */
    fun markCrashSeen(context: Context) {
        prefs(context).edit().putLong("crash_seen", System.currentTimeMillis()).apply()
    }

    /** Why the app's process ended recently, from Android itself (API 30+). */
    fun exitReasons(context: Context, max: Int = 5): List<String> = runCatching {
        val am = context.getSystemService(ActivityManager::class.java) ?: return emptyList()
        am.getHistoricalProcessExitReasons(context.packageName, 0, max).map { info ->
            buildString {
                append("${stamp.format(Date(info.timestamp))} · ${reasonName(info.reason)}")
                if (info.description != null) append(" · ${info.description}")
                append(" · importance ${info.importance} · status ${info.status}")
                append(" · pss ${info.pss / 1024} MB · rss ${info.rss / 1024} MB")
                if (info.reason == ApplicationExitInfo.REASON_CRASH_NATIVE || info.reason == ApplicationExitInfo.REASON_ANR) {
                    val trace = runCatching { info.traceInputStream?.bufferedReader()?.use { r -> r.lineSequence().take(40).joinToString("\n") } }.getOrNull()
                    if (!trace.isNullOrBlank()) append("\n    ").append(trace.replace("\n", "\n    "))
                }
            }
        }
    }.getOrDefault(emptyList())

    fun report(context: Context): String = buildString {
        appendLine("CineMind AI diagnostics · ${stamp.format(Date())}")
        appendLine(appInfo(context))
        appendLine()
        appendLine("Interrupted job: ${readJournal(context)?.let { p -> p.stringPropertyNames().sorted().joinToString(", ") { "$it=${p.getProperty(it)}" } } ?: "none"}")
        File(dir(context), "export-journal-interrupted.properties").takeIf { it.exists() }?.let { appendLine("Previous interrupted job: ${it.readText().lines().filterNot { l -> l.startsWith("#") }.joinToString(", ")}") }
        File(dir(context), "export-journal-last.properties").takeIf { it.exists() }?.let { appendLine("Last finished job: ${it.readText().lines().filterNot { l -> l.startsWith("#") }.joinToString(", ")}") }
        appendLine()
        appendLine("Memory log (oldest first):")
        memoryLog(context).takeIf { it.exists() }?.readLines()?.takeLast(25)?.forEach { appendLine("  $it") } ?: appendLine("  none")
        appendLine()
        appendLine("How the app last closed (Android):")
        exitReasons(context).ifEmpty { listOf("no records") }.forEach { appendLine("- $it") }
        appendLine()
        val crashes = dir(context).listFiles { f -> f.name.startsWith("crash-") }?.sortedByDescending { it.name }.orEmpty()
        appendLine("Recorded crashes: ${crashes.size}")
        // Every recorded crash (up to 5), newest first; long traces are shortened.
        crashes.forEach { appendLine(); appendLine(it.readText().take(3_500)) }
    }

    private fun appInfo(context: Context): String {
        val v = runCatching { context.packageManager.getPackageInfo(context.packageName, 0) }.getOrNull()
        val am = context.getSystemService(ActivityManager::class.java)
        val mem = ActivityManager.MemoryInfo().also { am?.getMemoryInfo(it) }
        return "App ${v?.versionName} (${v?.longVersionCode}) · ${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) · " +
            "SoC ${if (Build.VERSION.SDK_INT >= 31) Build.SOC_MODEL else "?"} · RAM ${mem.totalMem / (1 shl 20)} MB, free ${mem.availMem / (1 shl 20)} MB · " +
            "heap max ${Runtime.getRuntime().maxMemory() / (1 shl 20)} MB"
    }

    private fun reasonName(reason: Int): String = when (reason) {
        ApplicationExitInfo.REASON_CRASH -> "Java crash"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "native crash"
        ApplicationExitInfo.REASON_ANR -> "not responding (ANR)"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "killed: low memory"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "killed: excessive resource use"
        ApplicationExitInfo.REASON_SIGNALED -> "killed by signal"
        ApplicationExitInfo.REASON_EXIT_SELF -> "exited normally"
        ApplicationExitInfo.REASON_USER_REQUESTED -> "closed by user / force stop"
        ApplicationExitInfo.REASON_USER_STOPPED -> "stopped by user"
        ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "permission changed"
        ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "dependency died"
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "failed to start"
        ApplicationExitInfo.REASON_FREEZER -> "frozen"
        ApplicationExitInfo.REASON_PACKAGE_UPDATED -> "app updated"
        ApplicationExitInfo.REASON_OTHER -> "other (system)"
        else -> "reason $reason"
    }
}
