package com.cinemind.feature.settings.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.cinemind.core.ui.components.GlassCard
import com.cinemind.core.ui.components.ScreenHeader
import com.cinemind.core.ui.components.SectionHeader
import com.cinemind.core.ui.components.hapticClick
import com.cinemind.core.ui.components.pressScale
import com.cinemind.core.ui.theme.CineMind
import com.cinemind.core.ui.theme.CineMindColors
import com.cinemind.core.ui.theme.CineMindTheme
import com.cinemind.core.ui.theme.DarkCineMindColors
import com.cinemind.core.ui.theme.LightCineMindColors
import com.cinemind.core.ui.theme.Radius
import com.cinemind.core.ui.theme.Spacing
import com.cinemind.core.ui.theme.ThemeMode
import com.cinemind.feature.settings.appearance.AppearanceRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val appearance: AppearanceRepository,
) : ViewModel() {
    val themeMode: StateFlow<ThemeMode> =
        appearance.themeMode.stateIn(viewModelScope, SharingStarted.Eagerly, appearance.current())

    fun setTheme(mode: ThemeMode) = appearance.set(mode)
}

@Composable
fun SettingsScreen(
    onOpenAiModels: () -> Unit,
    onOpenAbout: () -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val mode by viewModel.themeMode.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val version = remember {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "?"
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(bottom = Spacing.xxl),
    ) {
        ScreenHeader("Settings", subtitle = "Make CineMind yours")

        SectionHeader("Appearance", hint = "Applies to the whole app instantly and is remembered.")
        Row(
            horizontalArrangement = Arrangement.spacedBy(Spacing.m),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.gutter),
        ) {
            ThemeMode.entries.forEach { option ->
                ThemeOption(
                    mode = option,
                    selected = option == mode,
                    onClick = { viewModel.setTheme(option) },
                    modifier = Modifier.weight(1f),
                )
            }
        }

        SectionHeader("Editing & AI", modifier = Modifier.padding(top = Spacing.l))
        GlassCard(
            onClick = onOpenAiModels,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.gutter),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.AutoAwesome, contentDescription = null, tint = CineMind.colors.accent)
                Spacer(Modifier.width(Spacing.m))
                Column(modifier = Modifier.weight(1f)) {
                    Text("On-device AI", style = MaterialTheme.typography.titleMedium, color = CineMind.colors.textPrimary)
                    Text(
                        "Download and benchmark AI models for Smart edit",
                        style = MaterialTheme.typography.bodySmall,
                        color = CineMind.colors.textTertiary,
                    )
                }
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = CineMind.colors.textTertiary)
            }
        }

        SectionHeader("About", modifier = Modifier.padding(top = Spacing.l))
        GlassCard(
            onClick = onOpenAbout,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.gutter),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Info, contentDescription = null, tint = CineMind.colors.textSecondary)
                Spacer(Modifier.width(Spacing.m))
                Column(modifier = Modifier.weight(1f)) {
                    Text("About ${Branding.APP_NAME}", style = MaterialTheme.typography.titleSmall, color = CineMind.colors.textPrimary)
                    Text(
                        "Version $version · ${Branding.DEVELOPER_CREDIT}",
                        style = MaterialTheme.typography.bodySmall,
                        color = CineMind.colors.textTertiary,
                    )
                }
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = CineMind.colors.textTertiary)
            }
        }
    }
}

@Composable
private fun ThemeOption(mode: ThemeMode, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = CineMind.colors
    val interaction = remember { MutableInteractionSource() }
    val click = hapticClick(onClick = onClick)
    val shape = RoundedCornerShape(Radius.l)
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .pressScale(interaction)
            .semantics(mergeDescendants = true) {
                this.selected = selected
                contentDescription = "${mode.label} theme"
            }
            .clickable(interactionSource = interaction, indication = null, role = Role.RadioButton, onClick = click),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(0.62f)
                .clip(shape)
                .border(if (selected) 2.5.dp else 1.dp, if (selected) c.accent else c.hairline, shape),
        ) {
            when (mode) {
                ThemeMode.LIGHT -> MiniApp(LightCineMindColors, Modifier.fillMaxSize())
                ThemeMode.DARK -> MiniApp(DarkCineMindColors, Modifier.fillMaxSize())
                ThemeMode.SYSTEM -> Row(Modifier.fillMaxSize()) {
                    MiniApp(LightCineMindColors, Modifier.weight(1f).fillMaxHeight())
                    MiniApp(DarkCineMindColors, Modifier.weight(1f).fillMaxHeight())
                }
            }
            if (selected) {
                Icon(
                    Icons.Default.CheckCircle,
                    contentDescription = null,
                    tint = c.accent,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(Spacing.s)
                        .size(22.dp)
                        .clip(CircleShape)
                        .background(c.surface),
                )
            }
        }
        Spacer(Modifier.height(Spacing.s))
        Text(
            mode.label,
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) c.accent else c.textSecondary,
        )
    }
}

/** A miniature of the home screen drawn with the given theme's real tokens. */
@Composable
private fun MiniApp(colors: CineMindColors, modifier: Modifier) {
    CineMindTheme(colors = colors) {
        Column(
            modifier = modifier
                .background(Brush.verticalGradient(listOf(colors.backdropTop, colors.backdropBottom)))
                .padding(Spacing.s),
            verticalArrangement = Arrangement.spacedBy(Spacing.xs + 1.dp),
        ) {
            Box(Modifier.fillMaxWidth(0.6f).height(8.dp).clip(RoundedCornerShape(4.dp)).background(colors.textPrimary))
            Box(Modifier.fillMaxWidth(0.4f).height(5.dp).clip(RoundedCornerShape(3.dp)).background(colors.textTertiary))
            repeat(3) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(6.dp))
                        .background(colors.surfaceGlass)
                        .padding(4.dp),
                ) {
                    Box(Modifier.size(14.dp).clip(RoundedCornerShape(4.dp)).background(colors.trackVideo))
                    Spacer(Modifier.width(4.dp))
                    Box(Modifier.fillMaxWidth(0.7f).height(4.dp).clip(RoundedCornerShape(2.dp)).background(colors.textSecondary))
                }
            }
            Spacer(Modifier.weight(1f))
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(12.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Brush.horizontalGradient(listOf(colors.accentGradientStart, colors.accentGradientEnd))),
            )
        }
    }
}
