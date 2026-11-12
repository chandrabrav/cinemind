package com.cinemind.core.common

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Keeps CineMind working while it is in the background. Android freezes apps that are not on
 * screen unless they run a foreground service, so long jobs (speech-to-text, translation, Smart
 * edit…) wrap themselves in [run]: while any job is running, [WorkService] shows a quiet
 * "working" notification and the app keeps going; when the last job ends it stops.
 * (Export has its own service with progress.)
 */
object BackgroundWork {

    private val jobs = ConcurrentHashMap<Int, String>()
    private val ids = AtomicInteger()

    /** Runs [block] with the app kept alive; [label] is shown in the notification ("Writing captions"). */
    suspend fun <T> run(context: Context, label: String, block: suspend () -> T): T {
        val id = begin(context.applicationContext, label)
        try {
            return block()
        } finally {
            end(context.applicationContext, id)
        }
    }

    fun begin(context: Context, label: String): Int {
        val id = ids.incrementAndGet()
        jobs[id] = label
        runCatching { context.startForegroundService(Intent(context, WorkService::class.java)) }
            .onFailure { CineMindLogger.w("Background work service not started: ${it.message}") }
        return id
    }

    fun end(context: Context, id: Int) {
        jobs.remove(id)
        if (jobs.isEmpty()) runCatching { context.stopService(Intent(context, WorkService::class.java)) }
        else runCatching { context.startForegroundService(Intent(context, WorkService::class.java)) } // refresh the text
    }

    internal fun label(): String? = jobs.values.lastOrNull()
    internal fun isIdle() = jobs.isEmpty()
}

/** Foreground service that exists only while [BackgroundWork] has jobs (declared in the app manifest). */
class WorkService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val label = BackgroundWork.label()
        try {
            val type = if (Build.VERSION.SDK_INT >= 35) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING else ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            startForeground(NOTIFICATION_ID, notification(label ?: "Finishing up"), type)
        } catch (e: Exception) {
            // Never crash over the notification; the job continues as long as Android allows.
            CineMindLogger.w("Couldn't start background work notification: ${e.message}")
        }
        if (label == null || BackgroundWork.isIdle()) stopSelf()
        return START_NOT_STICKY
    }

    private fun notification(label: String): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Working in the background", NotificationManager.IMPORTANCE_LOW))
        }
        val open = packageManager.getLaunchIntentForPackage(packageName)?.let {
            android.app.PendingIntent.getActivity(this, 0, it, android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT)
        }
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("CineMind AI is working")
            .setContentText(label + " — you can switch apps; it keeps going.")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .apply { if (open != null) setContentIntent(open) }
            .build()
    }

    private companion object {
        const val CHANNEL_ID = "background_work"
        const val NOTIFICATION_ID = 1201
    }
}
