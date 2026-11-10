package app.aurora.ui.theme

import android.content.Context
import android.provider.Settings
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import app.aurora.data.ThemeMode
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

/** 8dp grid in 4dp steps (tokens: space-dp). The screen gutter is [Gutter]; everything else steps along this scale. */
object Space {
    val XXS = 4.dp
    val XS = 8.dp
    val S = 12.dp
    val M = 16.dp
    val L = 24.dp
    val XL = 32.dp
    val XXL = 48.dp
    val Gutter = 24.dp
    /** Inside a card or row group: where text starts. */
    val Inner = 20.dp
    /** Gap between stacked cards: generous, so each card stands alone. */
    val Card = 20.dp
}

/** Corner radii (tokens: radius-dp). Buttons and fields 14dp, cards 20dp, dialogs 24dp; chips and toggles are round. */
object Radius {
    val S = ContinuousShape(10.dp)
    /** Buttons, fields, icon wells. */
    val M = ContinuousShape(14.dp)
    val Button = M
    /** Cards. */
    val L = ContinuousShape(20.dp)
    val Dialog = ContinuousShape(24.dp)
    val Pill = RoundedCornerShape(percent = 50)
}

/**
 * A rounded rectangle with continuous corners: the curve eases out of the straight edge instead of meeting it at a
 * hard tangent, so a card reads as one smooth form. Where the box is too small for the eased corner it falls back to
 * a plain rounded corner of the same [radius].
 */
@androidx.compose.runtime.Immutable
data class ContinuousShape(val radius: androidx.compose.ui.unit.Dp) : androidx.compose.ui.graphics.Shape {
    /** What clips this shape cheaply: see [clipShape]. */
    internal val clip: androidx.compose.ui.graphics.Shape = RoundedCornerShape(radius * 0.99f)

    override fun createOutline(size: androidx.compose.ui.geometry.Size, layoutDirection: androidx.compose.ui.unit.LayoutDirection, density: androidx.compose.ui.unit.Density): androidx.compose.ui.graphics.Outline {
        val r = with(density) { radius.toPx() }
        if (r * SMOOTH > minOf(size.width, size.height) / 2f) {
            return androidx.compose.ui.graphics.Outline.Rounded(androidx.compose.ui.geometry.RoundRect(size.toRect(), androidx.compose.ui.geometry.CornerRadius(r.coerceAtMost(minOf(size.width, size.height) / 2f))))
        }
        return androidx.compose.ui.graphics.Outline.Generic(continuousPath(size.width, size.height, r))
    }

    private fun androidx.compose.ui.geometry.Size.toRect() = androidx.compose.ui.geometry.Rect(0f, 0f, width, height)

    companion object {
        /** How far along each edge the eased corner reaches, in radii. */
        internal const val SMOOTH = 1.52866483f

        /** One corner, from where it leaves the incoming edge to where it joins the outgoing one: (along, across) in radii. */
        private val CORNER = floatArrayOf(
            1.08849323f, 0f, 0.86840689f, 0.02349313f, 0.63149399f, 0.07491100f,
            0.37282392f, 0.16905899f, 0.16905899f, 0.37282392f, 0.07491100f, 0.63149399f,
            0.02349313f, 0.86840689f, 0f, 1.08849323f, 0f, SMOOTH,
        )

        internal fun continuousPath(w: Float, h: Float, r: Float) = androidx.compose.ui.graphics.Path().apply {
            // Clockwise from the top edge. Each corner: its point, the direction we arrive in, the direction we leave in.
            val corners = arrayOf(
                floatArrayOf(w, 0f, 1f, 0f, 0f, 1f),
                floatArrayOf(w, h, 0f, 1f, -1f, 0f),
                floatArrayOf(0f, h, -1f, 0f, 0f, -1f),
                floatArrayOf(0f, 0f, 0f, -1f, 1f, 0f),
            )
            corners.forEachIndexed { i, c ->
                fun x(a: Float, b: Float) = c[0] - c[2] * a * r + c[4] * b * r
                fun y(a: Float, b: Float) = c[1] - c[3] * a * r + c[5] * b * r
                if (i == 0) moveTo(x(SMOOTH, 0f), y(SMOOTH, 0f)) else lineTo(x(SMOOTH, 0f), y(SMOOTH, 0f))
                for (k in 0 until 3) {
                    val o = k * 6
                    cubicTo(
                        x(CORNER[o], CORNER[o + 1]), y(CORNER[o], CORNER[o + 1]),
                        x(CORNER[o + 2], CORNER[o + 3]), y(CORNER[o + 2], CORNER[o + 3]),
                        x(CORNER[o + 4], CORNER[o + 5]), y(CORNER[o + 4], CORNER[o + 5]),
                    )
                }
            }
            close()
        }
    }
}

/** Motion tokens (tokens: motion-ms). One easing curve everywhere: quick to respond, calm to settle. */
object Motion {
    val Ease = CubicBezierEasing(0.2f, 0f, 0f, 1f)
    /** Micro-interactions. */
    const val Quick = 160
    /** Navigation. */
    const val Standard = 240
    /** Modals and sheets. */
    const val Gentle = 280
    /** The day's numbers settling in on first show. */
    const val Reveal = 600
}

val LocalAuroraColors = staticCompositionLocalOf { DarkColors }
val LocalReducedMotion = staticCompositionLocalOf { false }

object Aurora {
    val colors: AuroraColors
        @Composable @ReadOnlyComposable get() = LocalAuroraColors.current

    /** True when the person (or the system) asked for less motion: no movement or scaling. */
    val reducedMotion: Boolean
        @Composable @ReadOnlyComposable get() = LocalReducedMotion.current
}

/** Tween on Aurora's curve, or an instant snap when motion is reduced. */
@Composable
@ReadOnlyComposable
fun <T> auroraTween(durationMs: Int = Motion.Standard): FiniteAnimationSpec<T> =
    if (Aurora.reducedMotion) snap() else tween(durationMs, easing = Motion.Ease)

fun systemAnimationsOff(context: Context): Boolean =
    Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f

@Composable
fun AuroraTheme(reduceMotion: Boolean, themeMode: ThemeMode = ThemeMode.Dark, content: @Composable () -> Unit) {
    // Dark is the default; Light and System are the person's choice in Settings.
    val dark = when (themeMode) {
        ThemeMode.Dark -> true
        ThemeMode.Light -> false
        ThemeMode.System -> isSystemInDarkTheme()
    }
    val colors = if (dark) DarkColors else LightColors
    val context = LocalContext.current
    // Android Go phones hold still too: every animation is a redraw they can't spare.
    val systemReduced = remember(context) { systemAnimationsOff(context) || context.getSystemService(android.app.ActivityManager::class.java).isLowRamDevice }
    val blur = remember(context) { app.aurora.ui.components.glassBlurSupported(context) }
    CompositionLocalProvider(
        LocalAuroraColors provides colors,
        LocalReducedMotion provides (reduceMotion || systemReduced),
        app.aurora.ui.components.LocalGlassBlur provides blur,
    ) {
        MaterialTheme(
            colorScheme = colors.toMaterial(),
            typography = MaterialTypography,
            // Material's own shapes must be corner-based; Aurora's components draw with the continuous [Radius] shapes.
            shapes = Shapes(small = RoundedCornerShape(10.dp), medium = RoundedCornerShape(14.dp), large = RoundedCornerShape(20.dp), extraLarge = RoundedCornerShape(24.dp)),
        ) {
            // Presses answer with a soft highlight, as on iOS, never Material's ripple.
            CompositionLocalProvider(androidx.compose.foundation.LocalIndication provides app.aurora.ui.components.Highlight, content = content)
        }
    }
}

/**
 * The shape to clip by. A continuous corner lies wholly inside the plain rounded corner of the same radius (checked
 * numerically: the curve touches it only at the diagonal), so that rounded rectangle clips it. The GPU clips a rounded
 * rectangle for free; a path clip is rasterized on the CPU and uploaded again every frame the card moves or fades,
 * which is what made scrolling and tab changes stutter. Draw the fill and edge with the shape itself
 * (`background(color, shape)`, `border(.., shape)`), so what shows is still the continuous curve.
 */
val androidx.compose.ui.graphics.Shape.clipShape: androidx.compose.ui.graphics.Shape
    get() = (this as? ContinuousShape)?.clip ?: this
