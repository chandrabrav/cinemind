package com.cinemind.feature.editor.timeline.handoff

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import com.cinemind.core.domain.aigen.GenerationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Hands a brief and its media to the app that runs the provider (Claude for KATANA). The files are
 * copied into a private share folder and offered through this app's FileProvider, so the other app
 * gets read access to exactly these copies and nothing else. Nothing is uploaded by CineMind.
 */
internal class HandoffSender(private val context: Context) {

    enum class Target { PROVIDER_APP, CHOOSER }

    class HandoffException(message: String) : Exception(message)

    /** Length of a picked video in µs, or null if it can't be read. */
    suspend fun durationUs(uri: Uri): Long? = withContext(Dispatchers.IO) {
        runCatching {
            MediaMetadataRetriever().run {
                try {
                    setDataSource(context, uri)
                    extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()?.times(1000)
                } finally {
                    release()
                }
            }
        }.getOrNull()
    }

    fun displayName(uri: Uri): String = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull() ?: "file"

    /** Copies the media for [jobId] into the share folder; returns shareable content URIs. */
    suspend fun stage(jobId: String, reference: Uri?, photos: List<Uri>): List<Uri> = withContext(Dispatchers.IO) {
        cleanOld()
        val dir = File(root(), jobId).apply { mkdirs() }
        val out = mutableListOf<Uri>()
        reference?.let { out += copy(it, File(dir, "reference.${ext(it, "mp4")}"), MAX_VIDEO_BYTES) }
        photos.forEachIndexed { i, p -> out += copy(p, File(dir, "subject-${i + 1}.${ext(p, "jpg")}"), MAX_PHOTO_BYTES) }
        out
    }

    /**
     * Opens the provider's app with the brief and media; if it isn't installed, the system share
     * sheet. The brief is also put on the clipboard because some apps drop text sent with files.
     */
    fun send(provider: GenerationProvider, brief: String, uris: List<Uri>): Target {
        copyToClipboard(brief)
        val intent = Intent(if (uris.size > 1) Intent.ACTION_SEND_MULTIPLE else Intent.ACTION_SEND).apply {
            type = if (uris.isEmpty()) "text/plain" else "*/*"
            putExtra(Intent.EXTRA_TEXT, brief)
            if (uris.size > 1) putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
            else if (uris.size == 1) putExtra(Intent.EXTRA_STREAM, uris[0])
            if (uris.isNotEmpty()) {
                clipData = ClipData.newRawUri(null, uris[0]).also { cd -> uris.drop(1).forEach { cd.addItem(ClipData.Item(it)) } }
            }
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        provider.appPackage?.let { pkg ->
            try {
                context.startActivity(Intent(intent).setPackage(pkg))
                return Target.PROVIDER_APP
            } catch (_: ActivityNotFoundException) {
                // Not installed: fall through to the share sheet.
            }
        }
        try {
            context.startActivity(
                Intent.createChooser(intent, "Send to ${provider.appName ?: provider.name}").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        } catch (_: ActivityNotFoundException) {
            throw HandoffException("No app on this phone can receive the files")
        }
        return Target.CHOOSER
    }

    /** Opens the provider's official page in the browser (brief already on the clipboard). */
    fun openPage(provider: GenerationProvider, brief: String) {
        copyToClipboard(brief)
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(provider.launchUrl)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: ActivityNotFoundException) {
            throw HandoffException("No browser found to open ${provider.launchUrl}")
        }
    }

    /** Removes a job's copies once they're no longer needed. */
    fun discard(jobId: String) {
        File(root(), jobId).deleteRecursively()
    }

    private fun copyToClipboard(text: String) {
        runCatching {
            (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                .setPrimaryClip(ClipData.newPlainText("CineMind brief", text))
        }
    }

    private fun copy(src: Uri, dest: File, maxBytes: Long): Uri {
        val input = context.contentResolver.openInputStream(src) ?: throw HandoffException("Couldn't read ${displayName(src)}")
        input.use { ins ->
            dest.outputStream().use { out ->
                val buf = ByteArray(1 shl 16)
                var total = 0L
                while (true) {
                    val n = ins.read(buf)
                    if (n < 0) break
                    total += n
                    if (total > maxBytes) {
                        out.close()
                        dest.delete()
                        throw HandoffException("${displayName(src)} is too large (limit ${maxBytes / (1024 * 1024)} MB)")
                    }
                    out.write(buf, 0, n)
                }
            }
        }
        return FileProvider.getUriForFile(context, "${context.packageName}$AUTHORITY_SUFFIX", dest)
    }

    private fun ext(uri: Uri, fallback: String): String =
        context.contentResolver.getType(uri)?.substringAfter('/')?.substringBefore(';')
            ?.let { if (it == "jpeg") "jpg" else it }
            ?.takeIf { it.length in 2..5 && it.all(Char::isLetterOrDigit) } ?: fallback

    private fun root() = File(context.cacheDir, DIR)

    /** Share copies older than two days are no longer needed by the receiving app. */
    private fun cleanOld() {
        val cutoff = System.currentTimeMillis() - 2L * 24 * 60 * 60 * 1000
        root().listFiles()?.filter { it.lastModified() < cutoff }?.forEach { it.deleteRecursively() }
    }

    companion object {
        const val DIR = "handoff"
        const val AUTHORITY_SUFFIX = ".handoff"
        const val MAX_VIDEO_BYTES = 500L * 1024 * 1024
        const val MAX_PHOTO_BYTES = 30L * 1024 * 1024
    }
}
