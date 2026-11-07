package app.aurora.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.unit.dp
import app.aurora.ui.theme.AuroraColors
import app.aurora.ui.theme.Radius

/**
 * Aurora's one surface recipe: a flat card a step off the page with a 1dp border (Graphite on Obsidian at night,
 * white on Porcelain by day). No gradient, glow or blur. [tint] marks a chosen state: a faint wash and a firmer edge.
 * The name is historical; the surface is solid.
 */
fun Modifier.glass(colors: AuroraColors, shape: Shape = Radius.L, tint: Color? = null): Modifier =
    clip(shape)
        .background(if (tint != null) tint.copy(alpha = 0.06f).compositeOver(colors.surface) else colors.surface)
        .border(1.dp, tint?.copy(alpha = 0.5f) ?: colors.hairline, shape)

