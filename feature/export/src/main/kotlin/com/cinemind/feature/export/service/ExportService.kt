package com.cinemind.feature.export.service

import android.app.Service
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.cinemind.core.common.CineMindLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class ExportService : Service() {

    private val notificationManager: NotificationManagerCompat by lazy {
        NotificationManagerCompat.from(this)
    }

    private val scope = CoroutineScope(Dispatchers.Default)

    override fun onBind(intent: Intent): IBinder? = null

    override fun onStartCommand(intent: Intent, flags: Int, startId: Int): Int {
        CineMindLogger.i("ExportService started")

        val notification = NotificationCompat.Builder(this, "export_channel")
            .setContentTitle("Exporting Video")
            .setContentText("Preparing export...")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .build()

        startForeground(1, notification)

        scope.launch {
            // Export logic will be implemented here
            CineMindLogger.i("Export pipeline initialized")
        }

        return START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        CineMindLogger.i("ExportService destroyed")
    }
}