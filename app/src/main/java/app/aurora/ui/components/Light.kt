package app.aurora.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.animation.core.tween
import app.aurora.ui.theme.Aurora
import app.aurora.ui.theme.Motion

/**
 * Aurora Light, the signature visual: the curve of a horizon seen from above, its rim lit by the first
 * light of day, and one point of light rising over it. It is drawn from real progress only
 * (progress/Progress.kt `light()`):
 *
 * - [glow] 0..1, the last seven days of verified progress: how wide and bright the dawn spreads.
 * - [rise] 0..1, today's progress: how high the point of light has risen above the rim.
 * - [warmth] 0..1 moves the light from aurora green toward dawn.
 *
 * With no progress it is a thin, cool rim and a point resting on it: a first day starts at first light.
 * It rises into place once when shown (instantly with reduced motion), then holds still.
 */
@Composable
fun AuroraLight(glow: Float, rise: Float, modifier: Modifier = Modifier, warmth: Float = 0.45f, description: String? = null) {
    val colors = Aurora.colors
    val reduced = Aurora.reducedMotion
    val shown = remember { Animatable(if (reduced) 1f else 0f) }
    LaunchedEffect(Unit) { if (!reduced) shown.animateTo(1f, tween(1800, easing = Motion.Ease)) }
    Canvas(modifier.semantics { if (description != null) contentDescription = description }) {
        val k = shown.value
        val g = (0.22f + 0.78f * glow.coerceIn(0f, 1f)) * k
        val w = size.width
        val h = size.height
        val rim = h * 0.80f
        val radius = w * 1.35f
        val center = Offset(w / 2f, rim + radius)
        val strength = if (colors.isDark) 1f else 1.3f

        // The dawn: light spreading up from the rim, wider and warmer with more progress.
        scale(scaleX = 2.2f, scaleY = 1f, pivot = Offset(w / 2f, rim)) {
            val r = h * (0.55f + 0.45f * g)
            drawCircle(
                Brush.radialGradient(
                    0f to colors.glowWarm.copy(alpha = 0.34f * g * strength * (0.45f + 0.55f * warmth)),
                    0.35f to colors.glowCool.copy(alpha = 0.20f * g * strength),
                    1f to Color.Transparent,
                    center = Offset(w / 2f, rim),
                    radius = r,
                ),
                radius = r,
                center = Offset(w / 2f, rim),
            )
        }

        // The body of the horizon: the sky colour, so the light sits on a real edge.
        drawCircle(colors.sky, radius, center)
        // Atmosphere: a soft band of light hugging the rim, then the rim itself.
        val span = Math.toDegrees(kotlin.math.asin((w / 2f / radius).toDouble())).toFloat() + 2f
        val rimBrush = Brush.horizontalGradient(
            0f to Color.Transparent,
            0.22f to colors.glowCool.copy(alpha = 0.55f * strength),
            0.5f to colors.glowWarm.copy(alpha = (0.6f + 0.4f * warmth).coerceAtMost(1f)),
            0.78f to colors.glowCool.copy(alpha = 0.55f * strength),
            1f to Color.Transparent,
        )
        val topLeft = Offset(center.x - radius, center.y - radius)
        val arcSize = Size(2 * radius, 2 * radius)
        for ((width, alpha) in listOf(26f to 0.07f, 12f to 0.14f, 5f to 0.32f)) {
            drawArc(rimBrush, 270f - span, 2 * span, false, topLeft, arcSize, alpha = alpha * (0.4f + 0.6f * g), style = Stroke(width.dp.toPx()))
        }
        drawArc(rimBrush, 270f - span, 2 * span, false, topLeft, arcSize, style = Stroke(1.6.dp.toPx(), cap = StrokeCap.Round))

        // The rising point: you.
        val lift = (rim - h * 0.14f) * (0.04f + 0.96f * rise.coerceIn(0f, 1f) * k)
        val point = Offset(w / 2f, rim - lift)
        val halo = 34.dp.toPx()
        drawCircle(Brush.radialGradient(listOf(colors.glowWarm.copy(alpha = 0.30f + 0.25f * g), colors.glowCool.copy(alpha = 0.10f), Color.Transparent), point, halo), halo, point)
        drawCircle(colors.light, 5.dp.toPx(), point)
    }
}
