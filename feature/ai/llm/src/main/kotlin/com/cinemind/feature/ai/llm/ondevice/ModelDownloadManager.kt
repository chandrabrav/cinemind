package com.cinemind.feature.ai.llm.ondevice

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.core.content.ContextCompat
import com.cinemind.core.common.CineMindLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext

sealed interface ModelState {
    data object NotInstalled : ModelState
    data class Downloading(val bytes: Long, val total: Long) : ModelState {
        val fraction: Float get() = DownloadMath.fraction(bytes, total)
    }
    data class Paused(val bytes: Long, val total: Long) : ModelState
    data object Verifying : ModelState
    data object Installed : ModelState
    data class Failed(val message: String, val bytes: Long) : ModelState
}

/**
 * Downloads models directly from their publisher (Hugging Face), resumable with HTTP Range,
 * optionally Wi-Fi only, then verifies the SHA-256 before marking them installed.
 * One download at a time; a foreground service keeps it alive in the background.
 */
@Singleton
class ModelDownloadManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val store: ModelStore,
    private val prefs: AiPreferences,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val states = ModelCatalog.ALL.associate { it.id to MutableStateFlow(initialState(it)) }
    private var job: Job? = null
    private var activeId: String? = null

    fun state(spec: ModelSpec): StateFlow<ModelState> = states.getValue(spec.id).asStateFlow()

    val isBusy: Boolean get() = job?.isActive == true

    private fun initialState(spec: ModelSpec): ModelState = when {
        store.isInstalled(spec) -> ModelState.Installed
        store.partialBytes(spec) > 0 -> ModelState.Paused(store.partialBytes(spec), spec.sizeBytes)
        else -> ModelState.NotInstalled
    }

    fun start(spec: ModelSpec) {
        if (isBusy) return
        val flow = states.getValue(spec.id)
        val done = store.partialBytes(spec)
        if (prefs.wifiOnly && isMetered()) {
            flow.value = ModelState.Failed("Connect to Wi-Fi, or turn off \"Wi-Fi only\" to use mobile data.", done)
            return
        }
        if (store.freeBytes() < DownloadMath.requiredFreeBytes(spec.sizeBytes, done)) {
            val need = DownloadMath.formatBytes(DownloadMath.requiredFreeBytes(spec.sizeBytes, done))
            flow.value = ModelState.Failed("Not enough free storage: about $need needed.", done)
            return
        }
        activeId = spec.id
        flow.value = ModelState.Downloading(done, spec.sizeBytes)
        runCatching { ContextCompat.startForegroundService(context, Intent(context, ModelDownloadService::class.java)) }
            .onFailure { CineMindLogger.w("Download service not started: ${it.message}") }
        job = scope.launch {
            try {
                download(spec)
                flow.value = ModelState.Verifying
                verify(spec)
                store.markVerified(spec)
                flow.value = ModelState.Installed
            } catch (e: CancellationException) {
                flow.value = ModelState.Paused(store.partialBytes(spec), spec.sizeBytes)
                throw e
            } catch (e: Exception) {
                CineMindLogger.e(e, "Model download failed")
                flow.value = ModelState.Failed(e.message ?: "Download failed", store.partialBytes(spec))
            } finally {
                activeId = null
            }
        }
    }

    /** Stops the download, keeping what was downloaded so it can resume. */
    fun pause() {
        job?.cancel()
    }

    fun delete(spec: ModelSpec) {
        if (activeId == spec.id) job?.cancel()
        store.delete(spec)
        states.getValue(spec.id).value = ModelState.NotInstalled
    }

    /** Progress of whatever is downloading now (for the notification). */
    fun activeState(): Pair<ModelSpec, ModelState>? =
        activeId?.let { id -> ModelCatalog.byId(id)?.let { it to states.getValue(id).value } }

    private suspend fun download(spec: ModelSpec) {
        val part = store.partFile(spec)
        var attempt = 0
        while (true) {
            coroutineContext.ensureActive()
            val already = if (part.exists()) part.length() else 0L
            if (already >= spec.sizeBytes) break
            try {
                fetch(spec, already)
                break
            } catch (e: IOException) {
                // Flaky networks: retry a few times from where we stopped.
                if (++attempt >= 5) throw IOException("Network error: ${e.message}", e)
                CineMindLogger.w("Download interrupted (${e.message}); retrying")
                kotlinx.coroutines.delay(3_000L * attempt)
            }
        }
        if (!part.renameTo(store.file(spec))) throw IOException("Couldn't move the downloaded file into place")
    }

    private suspend fun fetch(spec: ModelSpec, from: Long) {
        val flow = states.getValue(spec.id)
        val conn = (URL(spec.url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            if (from > 0) setRequestProperty("Range", "bytes=$from-")
        }
        try {
            val code = conn.responseCode
            val append = when (code) {
                HttpURLConnection.HTTP_PARTIAL -> true
                HttpURLConnection.HTTP_OK -> false // server ignored Range: start over
                else -> throw IOException("Server answered HTTP $code")
            }
            val total = DownloadMath.totalFromContentRange(conn.getHeaderField("Content-Range"))
                ?: conn.contentLengthLong.takeIf { it > 0 }?.let { if (append) it + from else it }
                ?: spec.sizeBytes
            if (total != spec.sizeBytes) throw IOException("Unexpected file size $total (expected ${spec.sizeBytes})")

            var done = if (append) from else 0L
            conn.inputStream.use { input ->
                FileOutputStream(store.partFile(spec), append).use { out ->
                    val buf = ByteArray(1 shl 16)
                    var lastEmit = 0L
                    while (true) {
                        coroutineContext.ensureActive()
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (done - lastEmit > 4L * 1024 * 1024) {
                            flow.value = ModelState.Downloading(done, spec.sizeBytes)
                            lastEmit = done
                        }
                    }
                }
            }
            flow.value = ModelState.Downloading(done, spec.sizeBytes)
            if (done < spec.sizeBytes) throw IOException("Connection closed early")
        } finally {
            conn.disconnect()
        }
    }

    private suspend fun verify(spec: ModelSpec) {
        val digest = MessageDigest.getInstance("SHA-256")
        store.file(spec).inputStream().use { input ->
            val buf = ByteArray(1 shl 20)
            while (true) {
                coroutineContext.ensureActive()
                val n = input.read(buf)
                if (n < 0) break
                digest.update(buf, 0, n)
            }
        }
        val actual = DownloadMath.hex(digest.digest())
        if (!actual.equals(spec.sha256, ignoreCase = true)) {
            store.delete(spec)
            throw IOException("The download was corrupted (checksum mismatch). Please download again.")
        }
    }

    private fun isMetered(): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return true
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return true
        return !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }
}
