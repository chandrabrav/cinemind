package com.cinemind.feature.project.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.cinemind.core.ui.components.GlassCard
import com.cinemind.core.ui.theme.CineMind
import com.cinemind.core.ui.theme.Radius
import com.cinemind.core.ui.theme.Spacing

/** Home entry for Teleprompter Studio. */
@Composable
internal fun TeleprompterCard(onClick: () -> Unit) {
    val c = CineMind.colors
    GlassCard(onClick = onClick, modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.gutter)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.size(52.dp).clip(RoundedCornerShape(Radius.m)).background(c.accentContainer),
            ) {
                Icon(Icons.Default.Subtitles, contentDescription = null, tint = c.onAccentContainer)
            }
            Spacer(Modifier.width(Spacing.m))
            Column(modifier = Modifier.weight(1f)) {
                Text("Teleprompter", style = MaterialTheme.typography.titleMedium, color = c.textPrimary)
                Text("Read your script to the camera, part by part", style = MaterialTheme.typography.bodySmall, color = c.textTertiary)
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = c.textTertiary)
        }
    }
}
