package app.aurora.ui.components

import android.os.Build
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import app.aurora.ui.theme.Aurora
import app.aurora.ui.theme.AuroraColors
import app.aurora.ui.theme.Radius
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Aurora's glass, the one surface recipe: a thin dark base so text always reads, a frost that catches light from the
 * top left, and a bright edge where the light strikes. Over those, the look of a real pane: a soft streak across it,
 * a lip of light along its top, and the sky's glow pooling in its far corner. [tint] colours the frost, the edge and
 * the glow (a mode colour, a chosen state). Everything that isn't a filled action is made of this.
 *
 * All gradients, no blur: it costs next to nothing, so a page of it stays smooth on any phone.
 */
fun Modifier.glass(colors: AuroraColors, shape: Shape = Radius.L, tint: Color? = null): Modifier {
    val frost = Brush.linearGradient(
        0f to (tint?.copy(alpha = 0.20f) ?: colors.glassTop),
        0.6f to (tint?.copy(alpha = 0.07f) ?: colors.glassBottom),
        1f to (tint?.copy(alpha = 0.05f) ?: colors.glassBottom),
    )
    val edge = Brush.linearGradient(
        0f to (tint?.copy(alpha = 0.85f) ?: colors.glassRim),
        0.4f to (tint?.copy(alpha = 0.35f) ?: colors.glassRim.copy(alpha = 0.10f)),
        1f to (tint?.copy(alpha = 0.22f) ?: colors.accent.copy(alpha = 0.16f)),
    )
    val glow = tint ?: colors.accent
    return clip(shape).background(colors.glassBase).background(frost).drawWithCache {
        val streak = Brush.linearGradient(
            0.16f to Color.Transparent,
            0.30f to Color.White.copy(alpha = 0.075f),
            0.38f to Color.White.copy(alpha = 0.015f),
            0.50f to Color.Transparent,
            start = Offset.Zero,
            end = Offset(size.width, size.width * 0.7f),
        )
        val lipHeight = 16.dp.toPx().coerceAtMost(size.height)
        val lip = Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.085f), Color.Transparent), endY = lipHeight)
        val corner = Offset(size.width, size.height)
        val pool = Brush.radialGradient(listOf(glow.copy(alpha = 0.13f), Color.Transparent), corner, size.maxDimension * 0.8f)
        onDrawBehind {
            drawRect(pool)
            drawRect(streak)
            drawRect(lip, size = Size(size.width, lipHeight))
        }
    }.border(1.dp, edge, shape)
}

/** The same light on a filled action: a soft reflection across its upper half, and a bright edge. Goes after the fill. */
fun Modifier.gloss(shape: Shape): Modifier = drawBehind {
    drawRect(Brush.verticalGradient(0f to Color.White.copy(alpha = 0.34f), 0.5f to Color.White.copy(alpha = 0.04f), 0.5f to Color.Transparent))
}.border(1.dp, Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.60f), Color.White.copy(alpha = 0.10f))), shape)

/**
 * What glass can see through: everything drawn inside [glassSource], recorded so a [frosted] surface can blur the part
 * behind it. Null before Android 12, which has no blur; those surfaces stay readable on their own.
 */
@Stable
class GlassSource(val layer: GraphicsLayer) {
    var origin by mutableStateOf(Offset.Zero)
}

@Composable
fun rememberGlassSource(): GlassSource? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null
    val layer = rememberGraphicsLayer()
    return remember(layer) { GlassSource(layer) }
}

/** Records this content for [frosted]. A frosted surface must sit outside it, or it would blur itself. */
fun Modifier.glassSource(source: GlassSource?): Modifier =
    if (source == null) this
    else onPlaced { source.origin = it.positionInRoot() }.drawWithContent {
        source.layer.record { this@drawWithContent.drawContent() }
        drawLayer(source.layer)
    }

/**
 * Real frosted glass: draws the part of [source] behind this surface, blurred, in [shape]. For what floats over moving
 * content (the dock, the top bar). Cards don't use it: they sit over soft light, and a blur per card would cost too
 * much under the live sky. [shown] fades it, 0 to 1.
 */
fun Modifier.frosted(source: GlassSource?, shape: Shape, shown: () -> Float = { 1f }): Modifier = composed {
    if (source == null) return@composed this
    val blur = rememberGraphicsLayer()
    var at by remember { mutableStateOf(Offset.Zero) }
    onPlaced { at = it.positionInRoot() }.drawBehind {
        val k = shown()
        if (k <= 0f) return@drawBehind
        val radius = 26.dp.toPx()
        blur.renderEffect = BlurEffect(radius, radius, TileMode.Clamp)
        blur.alpha = k
        blur.record { translate(source.origin.x - at.x, source.origin.y - at.y) { drawLayer(source.layer) } }
        clipPath(Path().apply { addOutline(shape.createOutline(size, layoutDirection, this@drawBehind)) }) { drawLayer(blur) }
    }
}

/** A faint glow down the page, so glass below the sky still has light to catch. Goes over the backdrop, under the content. */
@Composable
fun PageGlow(modifier: Modifier = Modifier) {
    val colors = Aurora.colors
    // The light down the page wanders and breathes, slowly. Still with reduced motion, and on phones whose sky is the
    // painted still: the older, weaker phones, where redrawing it would take the time scrolling needs.
    val cycle = if (Aurora.reducedMotion || Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) null else rememberInfiniteTransition(label = "glow").animateFloat(0f, 2f * PI.toFloat(), infiniteRepeatable(tween(GlowMs, easing = LinearEasing)), label = "glow")
    // ponytail: 15 redraws a second is plenty for light this slow, and the whole page's glass redraws with it.
    val drift = cycle?.let { c -> remember { derivedStateOf { (c.value * GlowSteps).toInt() / GlowSteps } } }
    Canvas(modifier.fillMaxSize().clearAndSetSemantics { }) {
        val t = drift?.value ?: 0f
        fun glow(x: Float, y: Float, radius: Float, color: Color) {
            val center = Offset(x, y)
            drawCircle(Brush.radialGradient(listOf(color, Color.Transparent), center, radius), radius, center)
        }
        val first = if (drift == null) 0f else sin(t)
        val second = if (drift == null) 0f else sin(t + 2f)
        glow(size.width * (0.95f - 0.30f * (first + 1f)), size.height * (0.60f + 0.10f * cos(t)), size.width * 0.90f, colors.glowCool.copy(alpha = 0.15f + 0.05f * first))
        glow(size.width * (0.02f + 0.30f * (second + 1f)), size.height * (0.92f - 0.10f * cos(t + 2f)), size.width * 1.00f, colors.accent.copy(alpha = 0.08f + 0.03f * second))
    }
}

private const val GlowMs = 13500
private const val GlowSteps = 38f
