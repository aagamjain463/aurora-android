package app.aurora.ui.components

import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import app.aurora.ui.theme.Aurora
import app.aurora.ui.theme.AuroraType
import app.aurora.ui.theme.Motion
import app.aurora.ui.theme.Radius
import app.aurora.ui.theme.Space
import app.aurora.ui.theme.auroraTween

enum class ButtonKind {
    /** One per screen: the thing aurora suggests you do next. */
    Primary,

    /** Alternatives and reversible actions. */
    Secondary,

    /** Low-emphasis actions such as "Not now". */
    Quiet,
}

@Composable
fun AuroraButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    kind: ButtonKind = ButtonKind.Primary,
    icon: ImageVector? = null,
    enabled: Boolean = true,
) {
    val colors = Aurora.colors
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        if (pressed && !Aurora.reducedMotion) 0.97f else 1f,
        auroraTween(Motion.Quick),
        label = "press",
    )
    val (container, content) = when (kind) {
        ButtonKind.Primary -> colors.accent to colors.onAccent
        ButtonKind.Secondary -> colors.elevated to colors.textPrimary
        ButtonKind.Quiet -> Color.Transparent to colors.textPrimary
    }
    val fill = if (kind == ButtonKind.Primary && colors.isDark) {
        Brush.horizontalGradient(0f to colors.accent, 0.65f to colors.accent, 1f to colors.light)
    } else SolidColor(container)
    Box(
        modifier
            .graphicsLayer { scaleX = scale; scaleY = scale; alpha = if (enabled) 1f else 0.4f }
            .heightIn(min = if (kind == ButtonKind.Quiet) 48.dp else 58.dp)
            .clip(Radius.Pill)
            .background(fill)
            .then(if (kind == ButtonKind.Secondary) Modifier.border(1.dp, colors.hairline, Radius.Pill) else Modifier)
            .clickable(
                interactionSource = interaction,
                indication = ripple(color = content),
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
            )
            .padding(horizontal = Space.L),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.XS)) {
            if (icon != null) Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size(20.dp))
            Text(text, color = content, style = AuroraType.Label)
        }
    }
}

/** 48dp round icon button. Every icon-only control needs a [label] for screen readers. */
@Composable
fun AuroraIconButton(icon: ImageVector, label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(48.dp)
            .clip(Radius.Pill)
            .clickable(role = Role.Button, onClickLabel = label, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = label, tint = Aurora.colors.textPrimary, modifier = Modifier.size(24.dp))
    }
}
