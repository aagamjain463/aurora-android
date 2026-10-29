package app.aurora.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.BiasAlignment
import app.aurora.R
import app.aurora.ui.theme.Aurora
import app.aurora.ui.theme.Radius

/**
 * How much First Light a screen carries (brand bible: Artwork). Immersive for Today, Focus and the
 * Shield; Balanced for Protect and Insights. Configuration screens carry none: they keep only the
 * palette, the hairlines and the mark.
 */
enum class ArtTier(val height: Dp) { Immersive(440.dp), Balanced(360.dp) }

/**
 * The signature horizon behind the top of a screen: the ring of light over the lake, the night art in
 * dark theme and the morning art in light. Art only; every label, number and control sits on top of it
 * in Compose. [scroll] is the list's scroll offset in px: the art drifts at half speed (parallax), or
 * moves with the content when motion is reduced.
 */
@Composable
fun FirstLightBackdrop(tier: ArtTier, modifier: Modifier = Modifier, scroll: () -> Int = { 0 }) {
    val colors = Aurora.colors
    val reduced = Aurora.reducedMotion
    Box(
        modifier.fillMaxWidth().height(tier.height).graphicsLayer {
            val s = scroll().toFloat()
            translationY = -(if (reduced) s else s * 0.5f)
            alpha = (1f - s / (tier.height.toPx() * 1.2f)).coerceIn(0f, 1f)
        },
    ) {
        Image(
            painterResource(if (colors.isDark) R.drawable.first_light_cinematic else R.drawable.first_light_day),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
            // The ring sits right of centre, as in the approved references, leaving the left for type.
            alignment = BiasAlignment(0.42f, if (tier == ArtTier.Immersive) -0.2f else -0.45f),
        )
        // Shade on the reading side: type sits at the left, the ring stays bright at the right.
        Box(
            Modifier.fillMaxSize().background(
                Brush.horizontalGradient(
                    0f to colors.background.copy(alpha = if (colors.isDark) 0.62f else 0.45f),
                    0.55f to colors.background.copy(alpha = 0.12f),
                    1f to Color.Transparent,
                ),
            ),
        )
        // Keeps the status bar and the header legible, then lets the art fall into the page.
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    0f to colors.background.copy(alpha = 0.55f),
                    0.16f to colors.background.copy(alpha = 0.10f),
                    0.45f to Color.Transparent,
                    0.78f to colors.background.copy(alpha = 0.55f),
                    1f to colors.background,
                ),
            ),
        )
    }
}

/**
 * Aurora's container surface: quiet green-black glass, lit faintly from the top edge like the lake under
 * the ring. Every card and grouped list uses it, so containers read as one family. [edge] tints the
 * border (a mode colour, or the sun for warm cards).
 */
fun Modifier.auroraSurface(colors: app.aurora.ui.theme.AuroraColors, shape: Shape = Radius.L, edge: Color? = null, warm: Boolean = false): Modifier {
    val top = when {
        warm -> colors.sun.copy(alpha = if (colors.isDark) 0.10f else 0.08f).compositeOver(colors.surface)
        colors.isDark -> colors.elevated.copy(alpha = 0.78f)
        else -> colors.elevated
    }
    val bottom = if (colors.isDark) colors.surface.copy(alpha = 0.86f) else colors.surface
    val border = edge?.copy(alpha = 0.45f)
        ?: if (warm) colors.sun.copy(alpha = 0.32f) else colors.hairline
    return this
        .clip(shape)
        .background(Brush.verticalGradient(listOf(top, bottom)))
        .border(1.dp, Brush.verticalGradient(listOf(border.copy(alpha = (border.alpha * 1.6f).coerceAtMost(1f)), border)), shape)
}

/**
 * The Focus timer: a ring of light around a window onto First Light. Time runs clockwise from the top,
 * emerald warming to sun, with the point of light at its head. [content] is the readout in the middle.
 * The ring only moves when [progress] does (once a second, glided linearly; instantly with reduced motion).
 */
@Composable
fun LightRing(progress: Float, modifier: Modifier = Modifier, size: Dp = 288.dp, content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    val colors = Aurora.colors
    val reduced = Aurora.reducedMotion
    val p by androidx.compose.animation.core.animateFloatAsState(
        progress.coerceIn(0f, 1f),
        if (reduced) androidx.compose.animation.core.snap() else androidx.compose.animation.core.tween(1000, easing = androidx.compose.animation.core.LinearEasing),
        label = "ring",
    )
    Box(modifier.size(size), contentAlignment = androidx.compose.ui.Alignment.Center) {
        Box(Modifier.fillMaxSize().padding(26.dp).clip(androidx.compose.foundation.shape.CircleShape)) {
            Image(
                painterResource(if (colors.isDark) R.drawable.first_light_cinematic else R.drawable.first_light_day),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
                alignment = BiasAlignment(0f, 0.15f),
            )
            Box(
                Modifier.fillMaxSize().background(
                    Brush.radialGradient(
                        0f to colors.background.copy(alpha = 0.55f),
                        0.7f to colors.background.copy(alpha = 0.35f),
                        1f to colors.background.copy(alpha = 0.75f),
                    ),
                ),
            )
        }
        androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
            val stroke = 5.dp.toPx()
            val r = this.size.minDimension / 2f - 14.dp.toPx()
            val c = center
            // Minute ticks outside the ring, quiet.
            for (i in 0 until 60) {
                val a = Math.toRadians(i * 6.0 - 90.0)
                val inner = r + 7.dp.toPx()
                val outer = r + if (i % 5 == 0) 12.dp.toPx() else 10.dp.toPx()
                drawLine(
                    colors.textTertiary.copy(alpha = if (i % 5 == 0) 0.45f else 0.22f),
                    androidx.compose.ui.geometry.Offset(c.x + inner * kotlin.math.cos(a).toFloat(), c.y + inner * kotlin.math.sin(a).toFloat()),
                    androidx.compose.ui.geometry.Offset(c.x + outer * kotlin.math.cos(a).toFloat(), c.y + outer * kotlin.math.sin(a).toFloat()),
                    strokeWidth = 1.dp.toPx(),
                )
            }
            val topLeft = androidx.compose.ui.geometry.Offset(c.x - r, c.y - r)
            val arc = androidx.compose.ui.geometry.Size(2 * r, 2 * r)
            drawCircle(colors.hairline, r, c, style = androidx.compose.ui.graphics.drawscope.Stroke(2.dp.toPx()))
            if (p > 0f) {
                val sweep = Brush.sweepGradient(listOf(colors.accent, colors.accent, colors.cta.last(), colors.sun), c)
                rotate(-90f, c) {
                    drawArc(sweep, 0f, 360f * p, false, topLeft, arc, alpha = 0.22f, style = androidx.compose.ui.graphics.drawscope.Stroke(stroke * 3.2f, cap = androidx.compose.ui.graphics.StrokeCap.Round))
                    drawArc(sweep, 0f, 360f * p, false, topLeft, arc, style = androidx.compose.ui.graphics.drawscope.Stroke(stroke, cap = androidx.compose.ui.graphics.StrokeCap.Round))
                }
            }
            val a = Math.toRadians(360.0 * p - 90.0)
            val head = androidx.compose.ui.geometry.Offset(c.x + r * kotlin.math.cos(a).toFloat(), c.y + r * kotlin.math.sin(a).toFloat())
            val halo = 18.dp.toPx()
            drawCircle(Brush.radialGradient(listOf(colors.sun.copy(alpha = 0.55f), Color.Transparent), head, halo), halo, head)
            drawCircle(colors.light, 5.dp.toPx(), head)
        }
        androidx.compose.foundation.layout.Column(horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally, content = content)
    }
}
