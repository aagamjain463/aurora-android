package app.aurora.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.aurora.ui.theme.Aurora
import app.aurora.ui.theme.AuroraType
import app.aurora.ui.theme.Motion
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * What a screen shows while its data loads: the mark, on aurora's background. It fades in only after a
 * moment, so a fast load never flashes it.
 */
@Composable
fun LoadingSurface(modifier: Modifier = Modifier) {
    val shown = remember { androidx.compose.animation.core.Animatable(0f) }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(300)
        shown.animateTo(1f, tween(Motion.Gentle))
    }
    androidx.compose.foundation.layout.Box(
        modifier.fillMaxSize().background(Aurora.colors.background),
        contentAlignment = androidx.compose.ui.Alignment.Center,
    ) {
        AuroraMark(64.dp, Modifier.graphicsLayer { alpha = shown.value })
    }
}

/** The wordmark: "aurora", always lowercase, set tight in Manrope Bold. */
@Composable
fun Wordmark(modifier: Modifier = Modifier, color: Color = Aurora.colors.textPrimary) {
    Text("aurora", modifier, color = color, style = AuroraType.Wordmark)
}

/**
 * The aurora symbol, "First Light": a peak drawn as one continuous stroke on a horizon, and a point of
 * light just above its summit. It reads as an A, as a summit catching the first light of the day, and as
 * a person (the point) rising above what pulls them down. Built on a 24-unit grid (docs/BRAND_SYSTEM.md):
 * stroke 2.6u, peak at (12, 8.2), base on the horizon at y 19, light at (12, 3.6) r 1.9u.
 */
@Composable
fun AuroraMark(size: Dp, modifier: Modifier = Modifier) {
    val colors = Aurora.colors
    Canvas(modifier.size(size).clearAndSetSemantics { }) {
        drawAuroraMark(this.size.minDimension, colors.dawn, colors.accent, colors.light, colors.glowCool)
    }
}

/** Draws the symbol in a [side]-sized square at the origin. Shared by the in-app mark and the splash. */
fun androidx.compose.ui.graphics.drawscope.DrawScope.drawAuroraMark(side: Float, warm: Color, cool: Color, light: Color, glow: Color) {
    val u = side / 24f
    val peak = androidx.compose.ui.graphics.Path().apply {
        moveTo(4.2f * u, 19f * u)
        lineTo(12f * u, 8.2f * u)
        lineTo(19.8f * u, 19f * u)
    }
    drawPath(
        peak,
        Brush.linearGradient(listOf(warm, cool), Offset(4f * u, 19f * u), Offset(20f * u, 9f * u)),
        style = Stroke(width = 2.6f * u, cap = StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round),
    )
    // The horizon: short, quiet, centred under the peak.
    drawLine(cool.copy(alpha = 0.45f), Offset(2.5f * u, 21.6f * u), Offset(21.5f * u, 21.6f * u), strokeWidth = 1.1f * u, cap = StrokeCap.Round)
    val point = Offset(12f * u, 3.6f * u)
    drawCircle(Brush.radialGradient(listOf(glow.copy(alpha = 0.45f), Color.Transparent), point, 4.2f * u), 4.2f * u, point)
    drawCircle(light, 1.9f * u, point)
}

/**
 * The signature atmosphere: light rising from the bottom edge of the box it fills.
 * [intensity] is how much light (0 idle → 1 active); [warmth] shifts it from aurora green toward dawn.
 * Breathes slowly while [breathing], unless motion is reduced. With [horizon] the light is clipped at the
 * bottom edge (a visible horizon line); without it the light fades out softly past the box.
 */
@Composable
fun HorizonGlow(
    intensity: Float,
    modifier: Modifier = Modifier,
    warmth: Float = 0.4f,
    breathing: Boolean = false,
    horizon: Boolean = false,
) {
    val colors = Aurora.colors
    val reduced = Aurora.reducedMotion
    val level by animateFloatAsState(intensity, if (reduced) snap() else tween(1200, easing = Motion.Ease), label = "glow")
    val warm by animateFloatAsState(warmth, if (reduced) snap() else tween(1200, easing = Motion.Ease), label = "warmth")
    val breath: State<Float> = if (breathing && !reduced) {
        rememberInfiniteTransition(label = "breath").animateFloat(
            initialValue = 0.82f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(Motion.Breath, easing = Motion.Ease), RepeatMode.Reverse),
            label = "breath",
        )
    } else {
        remember { mutableFloatStateOf(1f) }
    }
    Canvas(modifier.clearAndSetSemantics { }) {
        // Mist washes light out, so the light theme needs a stronger glow to read the same.
        val k = level * breath.value * if (colors.isDark) 1f else 1.15f
        if (k <= 0.001f) return@Canvas
        val w = size.width
        val h = size.height
        val horizonPoint = Offset(w / 2f, h)
        // Only the bottom edge clips (when [horizon]): light may spread sideways and upward freely.
        clipRect(left = -w, top = -h, right = 2 * w, bottom = if (horizon) h else 2 * h) {
            // Wide, low ellipses: the light hugs the horizon instead of forming a spotlight.
            scale(scaleX = 1.7f, scaleY = 1f, pivot = horizonPoint) {
                val cool = horizonPoint.copy(x = w * 0.44f)
                val warmAt = horizonPoint.copy(x = w * 0.58f)
                drawCircle(
                    Brush.radialGradient(
                        0f to colors.glowCool.copy(alpha = 0.30f * k * (1f - 0.6f * warm)),
                        0.55f to colors.glowCool.copy(alpha = 0.10f * k * (1f - 0.6f * warm)),
                        1f to Color.Transparent,
                        center = cool,
                        radius = h * 0.95f,
                    ),
                    radius = h * 0.95f,
                    center = cool,
                )
                drawCircle(
                    Brush.radialGradient(
                        0f to colors.glowWarm.copy(alpha = 0.32f * k * (0.35f + 0.65f * warm)),
                        1f to Color.Transparent,
                        center = warmAt,
                        radius = h * 0.55f,
                    ),
                    radius = h * 0.55f,
                    center = warmAt,
                )
            }
        }
    }
}

/**
 * The focus arc: time travels from the left horizon, over the top, to the right — like the sun
 * crossing the sky. [progress] 0..1. Drawn 2:1; place the readout inside its lower half.
 */
@Composable
fun HorizonArc(progress: Float, modifier: Modifier = Modifier, complete: Boolean = false) {
    val colors = Aurora.colors
    val reduced = Aurora.reducedMotion
    // The ViewModel ticks once a second; a linear 1s glide makes the light move continuously.
    val p by animateFloatAsState(
        progress.coerceIn(0f, 1f),
        if (reduced) snap() else tween(1000, easing = LinearEasing),
        label = "arc",
    )
    val glowAlpha by animateFloatAsState(if (complete) 0.45f else 0.22f, tween(Motion.Gentle), label = "dot")
    Canvas(modifier.aspectRatio(2f).clearAndSetSemantics { }) {
        val pad = 14.dp.toPx()
        val r = min(size.width / 2f, size.height) - pad
        val center = Offset(size.width / 2f, size.height - 1.dp.toPx())
        val topLeft = Offset(center.x - r, center.y - r)
        val arcSize = Size(2 * r, 2 * r)

        // Horizon line, fading out at both ends.
        drawLine(
            Brush.horizontalGradient(
                0f to Color.Transparent, 0.2f to colors.hairline, 0.8f to colors.hairline, 1f to Color.Transparent,
            ),
            Offset(0f, center.y), Offset(size.width, center.y), strokeWidth = 1.dp.toPx(),
        )
        drawArc(
            colors.hairline, 180f, 180f, false, topLeft, arcSize,
            style = Stroke(1.5.dp.toPx(), cap = StrokeCap.Round),
        )
        if (p > 0f) {
            drawArc(
                Brush.linearGradient(listOf(colors.dawn, colors.accent), Offset(center.x - r, 0f), Offset(center.x + r, 0f)),
                180f, 180f * p, false, topLeft, arcSize,
                style = Stroke(3.dp.toPx(), cap = StrokeCap.Round),
            )
        }
        val angle = Math.toRadians(180.0 + 180.0 * p)
        val dot = Offset(center.x + r * cos(angle).toFloat(), center.y + r * sin(angle).toFloat())
        val halo = 22.dp.toPx()
        drawCircle(Brush.radialGradient(listOf(colors.glowCool.copy(alpha = glowAlpha), Color.Transparent), dot, halo), halo, dot)
        drawCircle(colors.light, 5.dp.toPx(), dot)
    }
}
