package com.cinemind.feature.settings.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.widget.Toast
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp
import com.cinemind.core.common.Diagnostics
import com.cinemind.core.ui.components.GlassCard
import com.cinemind.core.ui.components.GradientButton
import com.cinemind.core.ui.components.ScreenHeader
import com.cinemind.core.ui.theme.CineMind
import com.cinemind.core.ui.theme.Spacing

/**
 * Shows what the phone recorded about crashes and interrupted exports, so problems that only happen
 * on a real device can be diagnosed. The report has no media, file names or personal data; it is
 * only copied when the user taps Copy.
 */
@Composable
fun DiagnosticsScreen() {
    val c = CineMind.colors
    val context = LocalContext.current
    var report by remember { mutableStateOf(Diagnostics.report(context)) }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(bottom = Spacing.xxl),
    ) {
        ScreenHeader("Diagnostics", subtitle = "Crashes and interrupted exports recorded on this phone")
        Row(modifier = Modifier.padding(horizontal = Spacing.gutter)) {
            GradientButton(
                "Copy report",
                onClick = {
                    context.getSystemService(ClipboardManager::class.java)
                        ?.setPrimaryClip(ClipData.newPlainText("CineMind diagnostics", report))
                    Toast.makeText(context, "Report copied", Toast.LENGTH_SHORT).show()
                },
                icon = Icons.Default.ContentCopy,
            )
            Spacer(Modifier.width(Spacing.s))
            TextButton(onClick = { report = Diagnostics.report(context) }) { Text("Refresh", color = c.accent) }
            TextButton(onClick = {
                Diagnostics.clear(context)
                report = Diagnostics.report(context)
            }) { Text("Clear", color = c.textSecondary) }
        }
        Text(
            "Contains the app version, phone model, memory, how the app last closed (from Android), the step an export reached and " +
                "technical details of its footage (codec, size, frame rate, HDR). No videos, file names or personal data.",
            style = MaterialTheme.typography.bodySmall,
            color = c.textTertiary,
            modifier = Modifier.padding(horizontal = Spacing.gutter, vertical = Spacing.m),
        )
        GlassCard(modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.gutter)) {
            SelectionContainer {
                Text(
                    report,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    lineHeight = 15.sp,
                    color = c.textSecondary,
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                )
            }
        }
    }
}
