package com.cinemind.core.ui.format

import java.util.Locale

/** Human-friendly length: m:ss under an hour, h:mm:ss otherwise. Locale-independent digits. */
fun formatDuration(durationUs: Long): String {
    val totalSeconds = (durationUs / 1_000_000).coerceAtLeast(0)
    val h = totalSeconds / 3600
    val m = (totalSeconds % 3600) / 60
    val s = totalSeconds % 60
    return if (h > 0) {
        String.format(Locale.ROOT, "%d:%02d:%02d", h, m, s)
    } else {
        String.format(Locale.ROOT, "%d:%02d", m, s)
    }
}

/** Compact time for tight spaces: "0:03.2", "12:05.0". Locale-independent digits. */
fun formatShortTime(us: Long): String {
    val tenths = (us.coerceAtLeast(0) / 100_000)
    val totalSeconds = tenths / 10
    return String.format(Locale.ROOT, "%d:%02d.%d", totalSeconds / 60, totalSeconds % 60, tenths % 10)
}
