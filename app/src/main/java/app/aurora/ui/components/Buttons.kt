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

    /** Ending something early, such as a Focus session. Outlined in coral, never filled. */
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
        if (pressed && !Aurora.reducedMotion) 0.94f else 1f,
        if (Aurora.reducedMotion) snap() else spring(dampingRatio = 0.5f, stiffness = 700f),
        label = "press",
    )
    // The suggested next step answers with a light tick under the finger.
    val haptics = LocalHapticFeedback.current
    val (container, content) = when (kind) {
        ButtonKind.Primary -> if (enabled) colors.cta.first() to colors.onAccent else Color.Transparent to colors.textTertiary
        ButtonKind.Secondary -> Color.Transparent to colors.textPrimary
        ButtonKind.Quiet -> Color.Transparent to colors.textPrimary
        ButtonKind.Danger -> Color.Transparent to colors.danger
    }
    val fill = if (kind == ButtonKind.Primary && enabled) androidx.compose.ui.graphics.Brush.horizontalGradient(colors.cta) else SolidColor(container)
    Box(
        modifier
            .graphicsLayer { scaleX = scale; scaleY = scale; alpha = if (enabled || kind == ButtonKind.Primary) 1f else 0.4f }
            .heightIn(min = if (kind == ButtonKind.Quiet || compact) 48.dp else 56.dp)
            .clip(Radius.Pill)
            .background(fill)
            .then(
                when (kind) {
                    ButtonKind.Primary -> if (enabled) Modifier.ribbonKnot().gloss(Radius.Pill) else Modifier.glass(colors, Radius.Pill)
                    ButtonKind.Secondary -> Modifier.glass(colors, Radius.Pill)
                    ButtonKind.Danger -> Modifier.glass(colors, Radius.Pill, tint = colors.danger)
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
            if (icon != null) Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size(20.dp))
            Text(text, color = content, style = AuroraType.Label, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
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
        Icon(icon, contentDescription = label, tint = Aurora.colors.textPrimary, modifier = Modifier.size(24.dp))
    }
}
