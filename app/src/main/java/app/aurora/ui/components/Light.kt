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
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.animation.core.tween
import app.aurora.ui.theme.Aurora
import app.aurora.ui.theme.Motion
import kotlin.math.PI
import kotlin.math.sin

/**
 * Aurora Light, the signature visual: curtains of light over a still horizon, and one rising point.
 * It is drawn from real progress only (progress/Progress.kt `light()`):
 *
 * - [glow] 0..1, the last seven days of verified progress: how tall and bright the curtains are.
 * - [rise] 0..1, today's progress: how high the point of light has risen above the horizon.
 * - [warmth] 0..1 moves the light from aurora green toward dawn (completion, evening).
 *
 * With no progress it is a dark horizon with the point resting on it: a first day starts at first light.
 * It doesn't loop. It rises into place once when shown (instantly with reduced motion) and costs one
 * Canvas draw per change.
 */
@Composable
fun AuroraLight(glow: Float, rise: Float, modifier: Modifier = Modifier, warmth: Float = 0.3f, description: String? = null) {
    val colors = Aurora.colors
    val reduced = Aurora.reducedMotion
    val shown = remember { Animatable(if (reduced) 1f else 0f) }
    LaunchedEffect(Unit) { if (!reduced) shown.animateTo(1f, tween(1600, easing = Motion.Ease)) }
    Canvas(modifier.semantics { if (description != null) contentDescription = description }) {
        val k = shown.value
        val g = (0.18f + 0.82f * glow.coerceIn(0f, 1f)) * k
        val horizon = size.height * 0.80f
        // Light reads weaker on mist, so the light theme draws it a little stronger.
        val strength = if (colors.isDark) 1f else 1.25f

        // Sky glow rising from the horizon.
        scale(scaleX = 1.8f, scaleY = 1f, pivot = Offset(size.width / 2, horizon)) {
            drawCircle(
                Brush.radialGradient(
                    0f to colors.glowCool.copy(alpha = 0.26f * g * strength * (1 - 0.5f * warmth)),
                    0.6f to colors.glowCool.copy(alpha = 0.07f * g * strength),
                    1f to Color.Transparent,
                    center = Offset(size.width / 2, horizon),
                    radius = size.height * 0.95f,
                ),
                radius = size.height * 0.95f,
                center = Offset(size.width / 2, horizon),
            )
        }

        // Curtains: three soft ribbons, taller with more progress, fading upward like a real aurora.
        clipRect(bottom = horizon) {
            curtain(horizon, height = size.height * (0.30f + 0.40f * g), phase = 0.0f, waves = 1.4f, color = colors.glowCool, alpha = 0.34f * g * strength)
            curtain(horizon, height = size.height * (0.22f + 0.34f * g), phase = 1.7f, waves = 2.1f, color = colors.accent, alpha = 0.22f * g * strength)
            curtain(horizon, height = size.height * (0.14f + 0.22f * g), phase = 3.1f, waves = 1.1f, color = colors.glowWarm, alpha = 0.30f * g * strength * (0.4f + 0.6f * warmth))
        }

        // The horizon line, fading at both ends.
        drawLine(
            Brush.horizontalGradient(0f to Color.Transparent, 0.15f to colors.hairline.copy(alpha = colors.hairline.alpha * 2), 0.85f to colors.hairline.copy(alpha = colors.hairline.alpha * 2), 1f to Color.Transparent),
            Offset(0f, horizon), Offset(size.width, horizon), strokeWidth = 1.dp.toPx(),
        )

        // The rising point: you.
        val lift = (horizon - size.height * 0.16f) * (0.06f + 0.94f * rise.coerceIn(0f, 1f) * k)
        val point = Offset(size.width / 2, horizon - lift)
        val halo = 28.dp.toPx()
        drawCircle(Brush.radialGradient(listOf(colors.glowCool.copy(alpha = 0.35f + 0.2f * g), Color.Transparent), point, halo), halo, point)
        drawCircle(colors.light, 5.5.dp.toPx(), point)
    }
}

private fun DrawScope.curtain(horizon: Float, height: Float, phase: Float, waves: Float, color: Color, alpha: Float) {
    if (alpha <= 0.002f) return
    val w = size.width
    val path = Path().apply {
        moveTo(0f, horizon)
        val steps = 48
        for (i in 0..steps) {
            val x = w * i / steps
            val t = i.toFloat() / steps
            // Taller toward the middle, rippled along its top edge.
            val envelope = sin(PI * t).toFloat()
            val ripple = 0.72f + 0.28f * sin(2 * PI * waves * t + phase).toFloat()
            lineTo(x, horizon - height * envelope * ripple)
        }
        lineTo(w, horizon)
        close()
    }
    drawPath(
        path,
        Brush.verticalGradient(
            0f to Color.Transparent,
            0.55f to color.copy(alpha = alpha * 0.55f),
            1f to color.copy(alpha = alpha),
            startY = horizon - height,
            endY = horizon,
        ),
    )
}
