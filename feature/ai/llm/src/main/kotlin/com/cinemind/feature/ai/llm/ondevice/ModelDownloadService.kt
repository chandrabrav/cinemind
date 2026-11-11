package com.cinemind.feature.ai.llm.ondevice

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Keeps a multi-gigabyte model download alive in the background and shows its progress. */
@AndroidEntryPoint
class ModelDownloadService : Service() {

    @Inject lateinit var downloads: ModelDownloadManager

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var running = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureChannel()
        ServiceCompat.startForeground(this, ID, notification("Preparing…", 0, indeterminate = true), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        if (!running) {
            running = true
            scope.launch {
                // Give the manager a moment to mark the download active.
                delay(500)
                while (isActive) {
                    val active = downloads.activeState() ?: break
                    val (spec, state) = active
                    val n = when (state) {
                        is ModelState.Downloading -> notification(
                            "${spec.name}: ${DownloadMath.formatBytes(state.bytes)} of ${DownloadMath.formatBytes(state.total)}",
                            (state.fraction * 100).toInt(),
                            indeterminate = false,
                        )
                        ModelState.Verifying -> notification("${spec.name}: checking the file…", 0, indeterminate = true)
                        else -> null
                    }
                    if (n != null && NotificationManagerCompat.from(this@ModelDownloadService).areNotificationsEnabled()) {
                        runCatching { NotificationManagerCompat.from(this@ModelDownloadService).notify(ID, n) }
                    }
                    delay(1_000)
                }
                ServiceCompat.stopForeground(this@ModelDownloadService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        downloads.pause() // resumable later
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun notification(text: String, percent: Int, indeterminate: Boolean): Notification =
        NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Downloading AI model")
            .setContentText(text)
            .setProgress(100, percent, indeterminate)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()

    private fun ensureChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL) == null) {
            manager.createNotificationChannel(NotificationChannel(CHANNEL, "AI model downloads", NotificationManager.IMPORTANCE_LOW))
        }
    }

    private companion object {
        const val CHANNEL = "model_download"
        const val ID = 2001
    }
}
