package com.cinemind.core.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.cinemind.core.ui.theme.CineMind
import com.cinemind.core.ui.theme.Radius
import com.cinemind.core.ui.theme.Spacing

/** Soft gradient backdrop with a faint accent glow; the base layer of every screen. */
@Composable
fun AppBackground(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    val c = CineMind.colors
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(c.backdropTop, c.backdropBottom)))
            .drawBehind {
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(c.accent.copy(alpha = if (c.isDark) 0.14f else 0.10f), c.accent.copy(alpha = 0f)),
                        center = Offset(size.width * 0.15f, 0f),
                        radius = size.width * 0.9f,
                    ),
                    radius = size.width * 0.9f,
                    center = Offset(size.width * 0.15f, 0f),
                )
            },
        content = content,
    )
}

/**
 * Press feedback: a gentle spring scale-down while pressed. Pair with [hapticClick].
 * Cheap (graphics-layer only), so it stays smooth at 120 Hz.
 */
@Composable
fun Modifier.pressScale(interaction: MutableInteractionSource, pressedScale: Float = 0.97f): Modifier {
    val pressed by interaction.collectIsPressedAsState()
    val s by animateFloatAsState(if (pressed) pressedScale else 1f, spring(stiffness = 800f), label = "press")
    return this.scale(s)
}

/** Wraps a click with a light haptic tick. */
@Composable
fun hapticClick(type: HapticFeedbackType = HapticFeedbackType.TextHandleMove, onClick: () -> Unit): () -> Unit {
    val haptics = LocalHapticFeedback.current
    return {
        haptics.performHapticFeedback(type)
        onClick()
    }
}

/** Layered translucent card with a hairline edge. Clickable when [onClick] is set. */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(Radius.l),
    contentPadding: PaddingValues = PaddingValues(Spacing.l),
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = CineMind.colors
    val interaction = remember { MutableInteractionSource() }
    val click = onClick?.let { hapticClick(onClick = it) }
    Column(
        modifier = modifier
            .then(if (click != null) Modifier.pressScale(interaction) else Modifier)
            .clip(shape)
            .background(c.surfaceGlass)
            .border(1.dp, c.hairline, shape)
            .then(
                if (click != null) Modifier.clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = click)
                else Modifier,
            )
            .padding(contentPadding),
        content = content,
    )
}

/** Large screen title with optional subtitle and trailing content. */
@Composable
fun ScreenHeader(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.gutter, vertical = Spacing.m),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.headlineMedium, color = CineMind.colors.textPrimary)
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = CineMind.colors.textSecondary)
            }
        }
        trailing()
    }
}

/** Small section title inside a screen. */
@Composable
fun SectionHeader(title: String, modifier: Modifier = Modifier, hint: String? = null) {
    Column(modifier = modifier.padding(horizontal = Spacing.gutter, vertical = Spacing.s)) {
        Text(title, style = MaterialTheme.typography.titleSmall, color = CineMind.colors.textPrimary)
        if (hint != null) {
            Text(hint, style = MaterialTheme.typography.bodySmall, color = CineMind.colors.textTertiary)
        }
    }
}

/** Horizontally scrolling single-choice pills. */
@Composable
fun <T> ChoicePills(
    items: List<T>,
    selected: T?,
    label: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(horizontal = Spacing.gutter),
) {
    LazyRow(
        modifier = modifier,
        contentPadding = contentPadding,
        horizontalArrangement = Arrangement.spacedBy(Spacing.s),
    ) {
        items(items) { item ->
            Pill(text = label(item), selected = item == selected, onClick = { onSelect(item) })
        }
    }
}

/** A single pill; selected pills use the accent. 40 dp tall with a 48 dp touch area. */
@Composable
fun Pill(text: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, icon: ImageVector? = null) {
    val c = CineMind.colors
    val bg by animateColorAsState(if (selected) c.accent else c.surfaceSunken, label = "pillBg")
    val fg by animateColorAsState(if (selected) c.onAccent else c.textSecondary, label = "pillFg")
    val interaction = remember { MutableInteractionSource() }
    val click = hapticClick(onClick = onClick)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .heightIn(min = Spacing.touch)
            .pressScale(interaction)
            .semantics { this.selected = selected }
            .clickable(interactionSource = interaction, indication = null, role = Role.RadioButton, onClick = click)
            .padding(vertical = Spacing.xs),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .clip(RoundedCornerShape(Radius.pill))
                .background(bg)
                .padding(horizontal = Spacing.l, vertical = Spacing.s + 2.dp),
        ) {
            if (icon != null) {
                Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(Spacing.xs))
            }
            Text(text, style = MaterialTheme.typography.labelLarge, color = fg, maxLines = 1)
        }
    }
}

/** Primary action: accent-gradient pill button. */
@Composable
fun GradientButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
) {
    val c = CineMind.colors
    val interaction = remember { MutableInteractionSource() }
    val click = hapticClick(HapticFeedbackType.LongPress, onClick)
    val shape = RoundedCornerShape(Radius.pill)
    Row(
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .heightIn(min = 52.dp)
            .pressScale(interaction)
            .clip(shape)
            .background(
                if (enabled) Brush.horizontalGradient(listOf(c.accentGradientStart, c.accentGradientEnd))
                else Brush.horizontalGradient(listOf(c.surfaceSunken, c.surfaceSunken)),
            )
            .clickable(interactionSource = interaction, indication = null, enabled = enabled, role = Role.Button, onClick = click)
            .padding(horizontal = Spacing.xl, vertical = Spacing.m),
    ) {
        val fg = if (enabled) c.onAccentGradient else c.textTertiary
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(Spacing.s))
        }
        Text(text, style = MaterialTheme.typography.titleSmall, color = fg)
    }
}

/** Small coloured status label. */
@Composable
fun StatusPill(text: String, tone: Tone = Tone.Neutral, modifier: Modifier = Modifier) {
    val c = CineMind.colors
    val color = when (tone) {
        Tone.Neutral -> c.textSecondary
        Tone.Accent -> c.accent
        Tone.Success -> c.success
        Tone.Warning -> c.warning
        Tone.Danger -> c.danger
    }
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = color,
        maxLines = 1,
        softWrap = false,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
            .clip(RoundedCornerShape(Radius.pill))
            .background(color.copy(alpha = 0.12f))
            .padding(horizontal = Spacing.s + 2.dp, vertical = Spacing.xs),
    )
}

enum class Tone { Neutral, Accent, Success, Warning, Danger }

/** Icon-over-label tool button for toolbars (≥ 48 dp target, haptic tick). */
@Composable
fun ToolButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    selected: Boolean = false,
    minWidth: Dp = 64.dp,
) {
    val c = CineMind.colors
    val interaction = remember { MutableInteractionSource() }
    val click = hapticClick(onClick = onClick)
    val tint = when {
        !enabled -> c.textTertiary.copy(alpha = 0.5f)
        selected -> c.accent
        else -> c.textPrimary
    }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .widthIn(min = minWidth)
            .heightIn(min = 56.dp)
            .pressScale(interaction, 0.92f)
            .clip(RoundedCornerShape(Radius.m))
            .background(if (selected) c.accentContainer else androidx.compose.ui.graphics.Color.Transparent)
            .clickable(interactionSource = interaction, indication = null, enabled = enabled, role = Role.Button, onClick = click)
            .padding(horizontal = Spacing.xxs, vertical = Spacing.s),
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
        Spacer(Modifier.height(Spacing.xxs))
        Text(label, style = MaterialTheme.typography.labelSmall, color = tint, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Centered empty/zero state with an optional action. */
@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    action: @Composable (() -> Unit)? = null,
) {
    val c = CineMind.colors
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = modifier
            .fillMaxSize()
            .padding(Spacing.xxl),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(88.dp)
                .clip(RoundedCornerShape(Radius.xl))
                .background(Brush.linearGradient(listOf(c.accentGradientStart.copy(alpha = 0.18f), c.accentGradientEnd.copy(alpha = 0.18f)))),
        ) {
            Icon(icon, contentDescription = null, tint = c.accent, modifier = Modifier.size(40.dp))
        }
        Spacer(Modifier.height(Spacing.l))
        Text(title, style = MaterialTheme.typography.titleLarge, color = c.textPrimary, textAlign = TextAlign.Center)
        Spacer(Modifier.height(Spacing.s))
        Text(body, style = MaterialTheme.typography.bodyMedium, color = c.textSecondary, textAlign = TextAlign.Center)
        if (action != null) {
            Spacer(Modifier.height(Spacing.xl))
            action()
        }
    }
}

/** App-styled bottom sheet: rounded top, themed surface, drag handle. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CineMindSheet(
    onDismiss: () -> Unit,
    title: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = CineMind.colors
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = c.surfaceRaised,
        contentColor = c.textPrimary,
        scrimColor = c.scrim,
        shape = RoundedCornerShape(topStart = Radius.xl, topEnd = Radius.xl),
    ) {
        Column(modifier = Modifier.padding(start = Spacing.gutter, end = Spacing.gutter, bottom = Spacing.xl)) {
            Text(title, style = MaterialTheme.typography.titleLarge, color = c.textPrimary)
            Spacer(Modifier.height(Spacing.m))
            content()
        }
    }
}

/** Row with title/subtitle and a trailing control (switches etc.). */
@Composable
fun SettingRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    trailing: @Composable () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = modifier.fillMaxWidth().heightIn(min = 56.dp)) {
        Column(modifier = Modifier.weight(1f).padding(end = Spacing.m)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = CineMind.colors.textPrimary)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = CineMind.colors.textTertiary)
        }
        trailing()
    }
}
