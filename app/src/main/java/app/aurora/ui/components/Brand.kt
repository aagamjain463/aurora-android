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
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.text
import app.aurora.ui.theme.Space
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

/** The wordmark: "Aurora", title case, Manrope Medium. Always beside or below the symbol, never restyled. */
@Composable
fun Wordmark(modifier: Modifier = Modifier, color: Color = Aurora.colors.textPrimary) {
    Text("Aurora", modifier, color = color, style = AuroraType.Wordmark)
}

/**
 * The Aurora symbol, "First Light": a ring of light resting on the horizon, and a point of light rising
 * above it. The ring is the one in the First Light artwork; the point is you. Built on a 24-unit grid
 * (brand/tools/logo.py, the source of the SVG masters and launcher icon): ring centre (12, 19.2), radius
 * 7.5, stroke 3 with flat ends on the horizon; point at (12, 6.2), radius 2.
 */
@Composable
fun AuroraMark(size: Dp, modifier: Modifier = Modifier, mono: Color? = null) {
    val colors = Aurora.colors
    Canvas(modifier.size(size).clearAndSetSemantics { }) {
        drawAuroraMark(this.size.minDimension, colors.isDark, mono)
    }
}

/** Draws the symbol in a [side]-sized square at the origin. Shared by the in-app mark and the splash. */
fun androidx.compose.ui.graphics.drawscope.DrawScope.drawAuroraMark(side: Float, dark: Boolean = true, mono: Color? = null) {
    val u = side / 24f
    val c = Offset(12f * u, 19.2f * u)
    val r = 7.5f * u
    val ring = if (mono != null) androidx.compose.ui.graphics.SolidColor(mono) else Brush.linearGradient(
        if (dark) listOf(Color(0xFF2FB883), Color(0xFF8BE8BF)) else listOf(Color(0xFF137A57), Color(0xFF2FB883)),
        Offset(c.x - r, c.y), Offset(c.x + r, c.y - r),
    )
    drawArc(ring, 180f, 180f, false, Offset(c.x - r, c.y - r), Size(2 * r, 2 * r), style = Stroke(width = 3f * u, cap = StrokeCap.Butt))
    val point = Offset(12f * u, 6.2f * u)
    if (mono == null) {
        val sun = if (dark) Color(0xFFF3C77D) else Color(0xFFD99A4E)
        drawCircle(Brush.radialGradient(listOf(sun.copy(alpha = 0.35f), Color.Transparent), point, 4.4f * u), 4.4f * u, point)
        drawCircle(sun, 2f * u, point)
    } else {
        drawCircle(mono, 2f * u, point)
    }
}

/**
 * The top of every tab screen: the symbol and wordmark at the left, Settings at the right. Aurora has no
 * accounts, so there is no profile button; Settings uses the sliders glyph, which can't be mistaken for
 * the symbol.
 */
@Composable
fun AuroraHeader(onOpenSettings: (() -> Unit)?, modifier: Modifier = Modifier, title: String? = null) {
    val colors = Aurora.colors
    androidx.compose.foundation.layout.Row(
        modifier.fillMaxWidth().padding(start = Space.Gutter, end = Space.M, top = Space.XS, bottom = Space.XS),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
    ) {
        AuroraMark(34.dp)
        androidx.compose.foundation.layout.Spacer(Modifier.width(Space.S))
        androidx.compose.foundation.layout.Column(Modifier.weight(1f).semantics(mergeDescendants = true) { }) {
            Wordmark()
            if (title != null) Text(title, color = colors.textSecondary, style = AuroraType.Caption)
        }
        if (onOpenSettings != null) {
            androidx.compose.foundation.layout.Box(
                Modifier.size(48.dp).border(1.dp, colors.hairline.copy(alpha = 0.3f).compositeOver(colors.textTertiary.copy(alpha = 0.35f)), CircleShape)
                    .clip(CircleShape).background(colors.background.copy(alpha = 0.35f))
                    .clickable(role = androidx.compose.ui.semantics.Role.Button, onClickLabel = androidx.compose.ui.res.stringResource(app.aurora.R.string.settings_title), onClick = onOpenSettings),
                contentAlignment = androidx.compose.ui.Alignment.Center,
            ) {
                androidx.compose.material3.Icon(AuroraIcons.Settings, androidx.compose.ui.res.stringResource(app.aurora.R.string.settings_title), tint = colors.textPrimary, modifier = Modifier.size(22.dp))
            }
        }
    }
}

/**
 * A hero headline in two tones: the lead in First Light cream, the last [tailWords] words in full white,
 * on their own line. The words themselves are never changed (founder-approved copy stays exact); only the
 * line break and colour are design.
 */
@Composable
fun TwoToneHeadline(
    text: String,
    modifier: Modifier = Modifier,
    tailWords: Int = 2,
    style: androidx.compose.ui.text.TextStyle = AuroraType.Display,
    leadFirst: Boolean = true,
    textAlign: androidx.compose.ui.text.style.TextAlign? = null,
) {
    val colors = Aurora.colors
    val words = text.split(" ")
    val spoken = androidx.compose.ui.text.AnnotatedString(text)
    val split = (words.size - tailWords).coerceIn(0, words.size)
    val lead = words.take(split).joinToString(" ")
    val tail = words.drop(split).joinToString(" ")
    val annotated = androidx.compose.ui.text.buildAnnotatedString {
        val leadColor = if (leadFirst) colors.headlineLead else colors.textPrimary
        val tailColor = if (leadFirst) colors.textPrimary else colors.headlineLead
        if (lead.isNotEmpty()) {
            pushStyle(androidx.compose.ui.text.SpanStyle(color = leadColor)); append(lead); pop()
            append(if (leadFirst) "\n" else " ")
        }
        pushStyle(androidx.compose.ui.text.SpanStyle(color = tailColor)); append(tail); pop()
    }
    Text(
        annotated,
        // Screen readers and tests get the exact copy, without the designed line break.
        modifier.clearAndSetSemantics { heading(); semanticsText(spoken) },
        style = style,
        textAlign = textAlign,
    )
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

private fun androidx.compose.ui.semantics.SemanticsPropertyReceiver.semanticsText(value: androidx.compose.ui.text.AnnotatedString) {
    text = value
}
