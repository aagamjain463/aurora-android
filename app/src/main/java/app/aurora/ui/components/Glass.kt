package app.aurora.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.unit.dp
import app.aurora.ui.theme.Aurora
import app.aurora.ui.theme.AuroraColors
import app.aurora.ui.theme.Radius

/**
 * Aurora's one surface recipe. At night a card is an object catching first light: its fill is a breath brighter at the
 * top than at the bottom, and its edge is a hairline lit from above that fades down the sides. By day it is a sheet of
 * paper lifted off the desk by a soft, warm shadow. [tint] marks a chosen state or a mode: a faint wash and a firmer edge.
 */
fun Modifier.glass(colors: AuroraColors, shape: Shape = Radius.L, tint: Color? = null): Modifier =
    (if (colors.isDark || tint != null) this else this.shadow(10.dp, shape, ambientColor = Shade, spotColor = Shade))
        .clip(shape)
        .then(
            when {
                tint != null -> Modifier.background(tint.copy(alpha = 0.08f).compositeOver(colors.surface))
                colors.isDark -> Modifier.background(Brush.verticalGradient(listOf(colors.glassTop, colors.glassBottom)))
                else -> Modifier.background(colors.surface)
            },
        )
        .then(
            when {
                tint != null -> Modifier.border(1.dp, tint.copy(alpha = 0.55f), shape)
                colors.isDark -> Modifier.border(1.dp, Brush.verticalGradient(0f to colors.glassRim, 0.45f to colors.hairline, 1f to colors.hairline), shape)
                else -> Modifier.border(1.dp, colors.hairline, shape)
            },
        )

/** The day theme's shadow: warm umber, never grey, so paper sits on paper. */
private val Shade = Color(0xFF6B4A2E).copy(alpha = 0.16f)

/**
 * The one filled action's light. At night it is the sun itself: a pale gold that warms to peach, its top edge catching
 * the light. By day it is ink on paper.
 */
fun Modifier.sunlit(colors: AuroraColors): Modifier =
    background(Brush.verticalGradient(colors.cta))
        .then(if (colors.isDark) Modifier.border(1.dp, Brush.verticalGradient(0f to Color.White.copy(alpha = 0.75f), 0.5f to Color.White.copy(alpha = 0f)), Radius.Pill) else Modifier)
