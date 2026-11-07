package app.aurora.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.aurora.ui.theme.Aurora
import app.aurora.ui.theme.AuroraType
import app.aurora.ui.theme.Radius
import app.aurora.ui.theme.Space

enum class ButtonKind {
    /** One per screen: the thing aurora suggests you do next. */
    Primary,

    /** Alternatives and reversible actions. */
    Secondary,

    /** Low-emphasis actions such as "Not now". */
    Quiet,

    /** Ending something early, such as a Focus session. Outlined in the danger colour, never filled. */
    Danger,
}

@Composable
fun AuroraButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    kind: ButtonKind = ButtonKind.Primary,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    /** A small pill for actions inside a card header ("Add apps"). */
    compact: Boolean = false,
) {
    val colors = Aurora.colors
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        if (pressed && !Aurora.reducedMotion) 0.98f else 1f,
        if (Aurora.reducedMotion) snap() else androidx.compose.animation.core.tween(app.aurora.ui.theme.Motion.Quick, easing = app.aurora.ui.theme.Motion.Ease),
        label = "press",
    )
    // The suggested next step answers with a light tick under the finger.
    val haptics = LocalHapticFeedback.current
    val shape = if (compact) Radius.Pill else Radius.Button
    val (container, content) = when (kind) {
        ButtonKind.Primary -> if (enabled) colors.accent to colors.onAccent else colors.field to colors.textTertiary
        ButtonKind.Secondary -> Color.Transparent to colors.textPrimary
        ButtonKind.Quiet -> Color.Transparent to colors.textSecondary
        ButtonKind.Danger -> Color.Transparent to colors.danger
    }
    Box(
        modifier
            .graphicsLayer { scaleX = scale; scaleY = scale; alpha = if (enabled || kind == ButtonKind.Primary) 1f else 0.4f }
            .heightIn(min = when { kind == ButtonKind.Quiet || compact -> 48.dp; else -> 54.dp })
            .clip(shape)
            // The one filled action: solid Porcelain at night, solid Obsidian by day.
            .background(container)
            .then(
                when (kind) {
                    // A waiting primary keeps its outline, so it never melts into the page as bare text.
                    ButtonKind.Primary -> if (enabled) Modifier else Modifier.border(1.dp, colors.hairlineStrong, shape)
                    ButtonKind.Secondary -> Modifier.border(1.dp, colors.hairlineStrong, shape)
                    ButtonKind.Danger -> Modifier.border(1.dp, colors.danger.copy(alpha = 0.6f), shape)
                    ButtonKind.Quiet -> Modifier
                },
            )
            .clickable(
                interactionSource = interaction,
                indication = ripple(color = content),
                enabled = enabled,
                role = Role.Button,
                onClick = { if (kind == ButtonKind.Primary) haptics.performHapticFeedback(HapticFeedbackType.ContextClick); onClick() },
            )
            .padding(horizontal = if (compact) Space.M else Space.L),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.XS)) {
            if (icon != null) Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size(18.dp))
            // A compact pill stays one line: large text shrinks to fit (as SegmentedControl does) instead of
            // breaking into a column of single words.
            Text(
                text, color = content, style = when (kind) {
                    ButtonKind.Primary -> AuroraType.Label
                    ButtonKind.Quiet -> AuroraType.BodyStrong.copy(fontSize = 15.sp)
                    else -> AuroraType.Title
                }, textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                maxLines = if (compact) 1 else Int.MAX_VALUE, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                autoSize = if (compact) androidx.compose.foundation.text.TextAutoSize.StepBased(minFontSize = 10.sp, maxFontSize = AuroraType.Label.fontSize) else null,
            )
        }
    }
}

/** 48dp round icon button. Every icon-only control needs a [label] for screen readers. */
@Composable
fun AuroraIconButton(icon: ImageVector, label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(48.dp)
            .pressable(Radius.Pill, onClickLabel = label, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = label, tint = Aurora.colors.textPrimary, modifier = Modifier.size(20.dp))
    }
}
