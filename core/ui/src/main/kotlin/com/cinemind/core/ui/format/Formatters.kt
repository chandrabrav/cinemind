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
