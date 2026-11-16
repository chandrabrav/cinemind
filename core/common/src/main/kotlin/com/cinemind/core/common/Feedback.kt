package com.cinemind.core.common

import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * Beta feedback: opens the phone's share sheet (WhatsApp, Gmail, …) with the tester's note and,
 * if they agree, the Diagnostics report (crashes, exits, memory and export log — no media, no
 * project content). Nothing is sent automatically; the tester picks where it goes.
 */
object Feedback {

    fun share(context: Context, note: String, mood: String?, includeDiagnostics: Boolean) {
        val version = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "?"
        val text = buildString {
            appendLine("CineMind AI beta feedback")
            appendLine("Version $version · ${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE}")
            if (!mood.isNullOrBlank()) appendLine("Overall: $mood")
            appendLine()
            appendLine(note.ifBlank { "(no note)" })
            if (includeDiagnostics) {
                appendLine()
                appendLine("— Diagnostics —")
                append(runCatching { Diagnostics.report(context) }.getOrElse { "Diagnostics unavailable: ${it.message}" })
            }
        }
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "CineMind AI beta feedback ($version)")
            putExtra(Intent.EXTRA_TEXT, text)
        }
        context.startActivity(Intent.createChooser(send, "Send feedback").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    // ---- "What to try" card on the home screen (dismissed per app version) ----

    private fun prefs(c: Context) = c.getSharedPreferences("beta", Context.MODE_PRIVATE)
    private fun version(c: Context) = runCatching { c.packageManager.getPackageInfo(c.packageName, 0).versionName }.getOrNull() ?: ""

    fun showBetaCard(c: Context): Boolean = prefs(c).getString("dismissed_for", null) != version(c)
    fun dismissBetaCard(c: Context) = prefs(c).edit().putString("dismissed_for", version(c)).apply()
}
