package com.cinemind.feature.settings.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import com.cinemind.core.ui.components.GlassCard
import com.cinemind.core.ui.components.SectionHeader
import com.cinemind.core.ui.theme.CineMind
import com.cinemind.core.ui.theme.Radius
import com.cinemind.core.ui.theme.Spacing

/** Branding text, exactly as specified by the owner. Do not change the wording. */
object Branding {
    const val APP_NAME = "CineMind AI"
    const val DEVELOPER_CREDIT = "Developed by Gautam"
    const val DESCRIPTION =
        "CineMind AI is an AI-powered video editor. Import your clips, edit them by hand on a timeline, " +
            "or describe the video you want and let on-device AI help with the edit, captions and music. " +
            "Every AI change stays editable."
}

/** A third-party component and its licence (all verified against the project's dependencies). */
private data class Acknowledgement(val name: String, val use: String, val license: String)

private val ACKNOWLEDGEMENTS = listOf(
    Acknowledgement("Jetpack Compose & AndroidX", "User interface, lifecycle, navigation", "Apache License 2.0"),
    Acknowledgement("AndroidX Media3 (ExoPlayer, Transformer)", "Preview playback and video export", "Apache License 2.0"),
    Acknowledgement("AndroidX Room", "Project storage", "Apache License 2.0"),
    Acknowledgement("AndroidX DataStore", "Settings storage", "Apache License 2.0"),
    Acknowledgement("Dagger Hilt", "Dependency injection", "Apache License 2.0"),
    Acknowledgement("Kotlin & kotlinx.coroutines", "Language and concurrency", "Apache License 2.0"),
    Acknowledgement("Coil", "Thumbnails", "Apache License 2.0"),
    Acknowledgement("Google LiteRT-LM", "On-device AI runtime", "Apache License 2.0"),
    Acknowledgement("Google Gemma 4 E2B / E4B (downloaded on request)", "On-device AI model", "Apache License 2.0 (as published by Google)"),
)

@Composable
fun AboutScreen() {
    val c = CineMind.colors
    val context = LocalContext.current
    val info = remember {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0) }.getOrNull()
    }
    val version = info?.versionName ?: "?"
    val build = info?.longVersionCode ?: 0L
    val logo = remember {
        runCatching {
            context.packageManager.getApplicationIcon(context.packageName).toBitmap(192, 192).asImageBitmap()
        }.getOrNull()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(bottom = Spacing.xxl),
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.gutter, vertical = Spacing.xl),
        ) {
            if (logo != null) {
                Image(
                    bitmap = logo,
                    contentDescription = "${Branding.APP_NAME} logo",
                    modifier = Modifier
                        .size(88.dp)
                        .clip(RoundedCornerShape(Radius.xl)),
                )
                Spacer(Modifier.height(Spacing.l))
            }
            Text(
                Branding.APP_NAME,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.SemiBold,
                color = c.textPrimary,
                modifier = Modifier.semantics { heading() },
            )
            Text(
                "Version $version · Build $build",
                style = MaterialTheme.typography.bodyMedium,
                color = c.textSecondary,
            )
            Spacer(Modifier.height(Spacing.s))
            // Smaller and quieter than the brand, as the owner asked.
            Text(Branding.DEVELOPER_CREDIT, style = MaterialTheme.typography.labelLarge, color = c.textTertiary)
        }

        GlassCard(modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.gutter)) {
            Text(Branding.DESCRIPTION, style = MaterialTheme.typography.bodyMedium, color = c.textSecondary, textAlign = TextAlign.Start)
        }

        SectionHeader("Privacy", modifier = Modifier.padding(top = Spacing.l))
        GlassCard(modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.gutter)) {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
                listOf(
                    "Your videos, photos, projects and captions stay on this phone. Originals are only read, never changed or moved.",
                    "AI features run on the phone. Speech-to-text and Smart edit do not upload your media.",
                    "The app connects to the internet only when you choose to download an AI model (from Hugging Face).",
                    "No account is needed. The app contains no advertising or analytics code.",
                    "Deleting a project never deletes your original videos or photos.",
                ).forEach { Text("•  $it", style = MaterialTheme.typography.bodySmall, color = c.textSecondary) }
                Text(
                    "A separate privacy policy document has not been published yet.",
                    style = MaterialTheme.typography.bodySmall,
                    color = c.textTertiary,
                )
            }
        }

        SectionHeader("Open-source licences", hint = "CineMind AI is built with these components.", modifier = Modifier.padding(top = Spacing.l))
        GlassCard(modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.gutter)) {
            ACKNOWLEDGEMENTS.forEachIndexed { i, a ->
                if (i > 0) HorizontalDivider(color = c.hairline, modifier = Modifier.padding(vertical = Spacing.s))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(a.name, style = MaterialTheme.typography.titleSmall, color = c.textPrimary)
                        Text(a.use, style = MaterialTheme.typography.bodySmall, color = c.textTertiary)
                    }
                    Text(a.license, style = MaterialTheme.typography.labelSmall, color = c.textSecondary, textAlign = TextAlign.End, modifier = Modifier.padding(start = Spacing.s))
                }
            }
        }
        Text(
            "Apache License 2.0: https://www.apache.org/licenses/LICENSE-2.0",
            style = MaterialTheme.typography.labelSmall,
            color = c.textTertiary,
            modifier = Modifier.padding(horizontal = Spacing.gutter, vertical = Spacing.m),
        )
    }
}
