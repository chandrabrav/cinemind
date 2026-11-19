package com.cinemind.feature.project.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.cinemind.core.common.Feedback
import com.cinemind.core.ui.components.FeedbackSheet
import com.cinemind.core.ui.components.GlassCard
import com.cinemind.core.ui.components.GradientButton
import com.cinemind.core.ui.theme.CineMind
import com.cinemind.core.ui.theme.Spacing

/**
 * Beta card on the home screen: what to try in this build and a one-tap way to send feedback.
 * Hidden once dismissed; shown again for each new version.
 */
@Composable
internal fun BetaCard(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var visible by remember { mutableStateOf(Feedback.showBetaCard(context)) }
    var feedback by remember { mutableStateOf(false) }
    if (feedback) {
        FeedbackSheet(onSend = { note, mood, diag -> Feedback.share(context, note, mood, diag) }, onDismiss = { feedback = false })
    }
    if (!visible) return
    val c = CineMind.colors
    GlassCard(modifier = modifier.fillMaxWidth().padding(horizontal = Spacing.gutter)) {
        Column {
            Text("You're testing CineMind AI (beta)", style = MaterialTheme.typography.titleSmall, color = c.textPrimary)
            Spacer(Modifier.height(Spacing.xs))
            listOf(
                "Make a project, then try Colour (looks + AI suggestions), Speed and Crop & move",
                "Add Text & stickers and drag / pinch them on the video",
                "Audio: music with beat sync, sound effects, voiceover",
                "Captions from speech and Smart edit (need the on-device AI: ✦ on top)",
                "Export and share the video",
            ).forEach { line ->
                Text("•  $line", style = MaterialTheme.typography.bodySmall, color = c.textSecondary)
            }
            Spacer(Modifier.height(Spacing.s))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
                GradientButton("Send feedback", onClick = { feedback = true }, icon = Icons.AutoMirrored.Filled.Send, modifier = Modifier.weight(1f))
                TextButton(onClick = { Feedback.dismissBetaCard(context); visible = false }) { Text("Hide") }
            }
        }
    }
}

/**
 * Shown after the app crashed, closed natively or stopped responding: one tap shares the
 * diagnostics report through the phone's share sheet. Nothing is sent automatically, and the
 * report holds no videos or project content.
 */
@Composable
internal fun CrashCard(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var visible by remember { mutableStateOf(com.cinemind.core.common.Diagnostics.unseenCrash(context) != null) }
    if (!visible) return
    val c = CineMind.colors
    GlassCard(modifier = modifier.fillMaxWidth().padding(horizontal = Spacing.gutter)) {
        Column {
            Text("CineMind closed unexpectedly", style = MaterialTheme.typography.titleSmall, color = c.textPrimary)
            Spacer(Modifier.height(Spacing.xs))
            Text(
                "Sorry about that. Send the crash report so it can be fixed. It has technical details only: no videos, photos or project content.",
                style = MaterialTheme.typography.bodySmall,
                color = c.textSecondary,
            )
            Spacer(Modifier.height(Spacing.s))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
                GradientButton(
                    "Send report",
                    onClick = {
                        com.cinemind.core.common.Diagnostics.markCrashSeen(context)
                        visible = false
                        Feedback.share(context, "The app closed unexpectedly.", null, includeDiagnostics = true)
                    },
                    icon = Icons.AutoMirrored.Filled.Send,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { com.cinemind.core.common.Diagnostics.markCrashSeen(context); visible = false }) { Text("Not now") }
            }
        }
    }
}
