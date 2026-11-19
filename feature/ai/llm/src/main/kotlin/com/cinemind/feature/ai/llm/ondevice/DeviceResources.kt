package com.cinemind.feature.ai.llm.ondevice

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.PowerManager
import com.cinemind.core.domain.agents.ResourceSnapshot

/** Reads memory, temperature and battery as far as Android lets an app (for ResourcePolicy). */
object DeviceResources {

    fun snapshot(context: Context): ResourceSnapshot {
        val info = ActivityManager.MemoryInfo()
        context.getSystemService(ActivityManager::class.java)?.getMemoryInfo(info)
        val power = context.getSystemService(PowerManager::class.java)
        val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val plugged = (battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
        return ResourceSnapshot(
            availMemMb = info.availMem / (1024 * 1024),
            totalMemMb = info.totalMem / (1024 * 1024),
            lowMemory = info.lowMemory,
            thermal = power?.currentThermalStatus ?: -1,
            batteryPercent = if (level >= 0 && scale > 0) level * 100 / scale else 100,
            charging = plugged,
            powerSave = power?.isPowerSaveMode == true,
        )
    }
}
