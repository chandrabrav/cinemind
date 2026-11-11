package com.cinemind.core.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Animation
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cinemind.core.ui.components.AppBackground
import com.cinemind.core.ui.components.ChoicePills
import com.cinemind.core.ui.components.GlassCard
import com.cinemind.core.ui.components.GradientButton
import com.cinemind.core.ui.components.ScreenHeader
import com.cinemind.core.ui.components.SectionHeader
import com.cinemind.core.ui.components.SettingRow
import com.cinemind.core.ui.components.StatusPill
import com.cinemind.core.ui.components.ToolButton
import com.cinemind.core.ui.components.Tone
import com.cinemind.core.ui.theme.CineMind
import com.cinemind.core.ui.theme.CineMindColors
import com.cinemind.core.ui.theme.CineMindTheme
import com.cinemind.core.ui.theme.DarkCineMindColors
import com.cinemind.core.ui.theme.LightCineMindColors
import com.cinemind.core.ui.theme.Spacing
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Renders the shared components in both themes and saves screenshots for visual review. */
@RunWith(AndroidJUnit4::class)
class DesignSystemScreenshotTest {

    @get:Rule
    val compose = createComposeRule()

    private fun gallery(colors: CineMindColors) {
        compose.setContent {
            CineMindTheme(colors = colors) {
                AppBackground {
                    Column(verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
                        ScreenHeader("Your stories", subtitle = "3 projects")
                        GlassCard(modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.gutter), onClick = {}) {
                            Text("Goa trip · AI edit", style = MaterialTheme.typography.titleMedium, color = CineMind.colors.textPrimary)
                            Text("12 clips · 0:30", style = MaterialTheme.typography.bodySmall, color = CineMind.colors.textSecondary)
                            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
                                StatusPill("9:16")
                                StatusPill("Installed", Tone.Success)
                                StatusPill("Paused", Tone.Warning)
                                StatusPill("Problem", Tone.Danger)
                            }
                        }
                        SectionHeader("Style", hint = "Pick a look for the edit")
                        ChoicePills(listOf("Auto", "Cinematic", "Travel", "Gym"), "Cinematic", { it }, {})
                        GlassCard(modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.gutter)) {
                            SettingRow("Use AI in Smart edit", subtitle = "Reads your brief") { Switch(checked = true, onCheckedChange = {}) }
                            SettingRow("Download on Wi-Fi only", subtitle = "Models are 2.6–3.7 GB") { Switch(checked = false, onCheckedChange = {}) }
                        }
                        GradientButton("Create smart edit", onClick = {}, icon = Icons.Default.AutoAwesome, modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.gutter))
                        GradientButton("Disabled", onClick = {}, enabled = false, modifier = Modifier.padding(horizontal = Spacing.gutter))
                        Row(horizontalArrangement = Arrangement.SpaceEvenly, modifier = Modifier.fillMaxWidth()) {
                            ToolButton(Icons.Default.AutoAwesome, "Smart edit", onClick = {})
                            ToolButton(Icons.Default.Add, "Add", onClick = {}, selected = true)
                            ToolButton(Icons.Default.Animation, "Transitions", onClick = {})
                            ToolButton(Icons.Default.MusicNote, "Music", onClick = {}, enabled = false)
                            ToolButton(Icons.Default.FileUpload, "Export", onClick = {})
                        }
                    }
                }
            }
        }
    }

    @Test
    fun lightTheme() {
        gallery(LightCineMindColors)
        compose.onNodeWithText("Create smart edit").assertIsDisplayed()
        compose.saveScreenshot("design_system_light")
    }

    @Test
    fun darkTheme() {
        gallery(DarkCineMindColors)
        compose.onNodeWithText("Create smart edit").assertIsDisplayed()
        compose.saveScreenshot("design_system_dark")
    }
}
