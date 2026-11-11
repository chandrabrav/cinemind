package com.cinemind.feature.export.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import com.cinemind.core.common.CineMindLogger
import com.cinemind.feature.export.engine.ExportManager
import com.cinemind.feature.export.engine.ExportState
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Foreground service that keeps an export alive while the user leaves the app and shows its
 * progress. It does no rendering itself — it mirrors [ExportManager.state] and stops when the
 * export ends.
 */
@AndroidEntryPoint
class ExportService : Service() {

    @Inject lateinit var exportManager: ExportManager

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var observing = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureChannel()
        ServiceCompat.startForeground(this, PROGRESS_ID, progressNotification(null), foregroundType())

        if (!observing) {
            observing = true
            scope.launch {
                exportManager.state.collect { state ->
                    when (state) {
                        is ExportState.Running -> notify(PROGRESS_ID, progressNotification(state.progress))
                        is ExportState.Preparing, is ExportState.Saving -> notify(PROGRESS_ID, progressNotification(null))
                        is ExportState.Success -> finish(doneNotification("Video exported", "Saved to Movies/CineMind"))
                        is ExportState.Failed -> finish(doneNotification("Export failed", state.message))
                        is ExportState.Cancelled, ExportState.Idle -> finish(null)
                    }
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun finish(result: Notification?) {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        if (result != null) notify(RESULT_ID, result)
        stopSelf()
    }

    /** Android 15+ limits long-running foreground work; stop cleanly instead of being killed. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        CineMindLogger.w("Export service timed out; cancelling export")
        exportManager.cancel()
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun foregroundType(): Int =
        if (Build.VERSION.SDK_INT >= 35) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING
        } else {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        }

    private fun openAppIntent(): PendingIntent? =
        packageManager.getLaunchIntentForPackage(packageName)?.let {
            PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }

    private fun progressNotification(progress: Float?): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("Exporting video")
            .setContentText(progress?.let { "${(it * 100).toInt()}%" } ?: "Preparing…")
            .setProgress(100, ((progress ?: 0f) * 100).toInt(), progress == null)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openAppIntent())
            .build()

    private fun doneNotification(title: String, text: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload_done)
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(true)
            .setContentIntent(openAppIntent())
            .build()

    private fun notify(id: Int, notification: Notification) {
        // Silently skipped if the user hasn't granted notification permission (Android 13+).
        if (NotificationManagerCompat.from(this).areNotificationsEnabled()) {
            try {
                NotificationManagerCompat.from(this).notify(id, notification)
            } catch (e: SecurityException) {
                CineMindLogger.w("Notification not shown: ${e.message}")
            }
        }
    }

    private fun ensureChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Video export", NotificationManager.IMPORTANCE_LOW),
            )
        }
    }

    private companion object {
        const val CHANNEL_ID = "export_channel"
        const val PROGRESS_ID = 1001
        const val RESULT_ID = 1002
    }
}
