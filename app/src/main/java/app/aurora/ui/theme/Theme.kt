package app.aurora.ui.theme

import android.content.Context
import android.provider.Settings
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

/** 4dp grid. The screen gutter is [Gutter]; everything else steps along this scale. */
object Space {
    val XXS = 4.dp
    val XS = 8.dp
    val S = 12.dp
    val M = 16.dp
    val L = 24.dp
    val XL = 32.dp
    val XXL = 48.dp
    val Gutter = 24.dp
}

/** Corner radii. Interactive pills are fully round; containers are soft but not bubbly. */
object Radius {
    val S = RoundedCornerShape(12.dp)
    val M = RoundedCornerShape(16.dp)
    val L = RoundedCornerShape(22.dp)
    val Dialog = RoundedCornerShape(28.dp)
    val Pill = RoundedCornerShape(percent = 50)
}

/** Motion tokens. One easing curve everywhere: quick to respond, slow to settle. */
object Motion {
    val Ease = CubicBezierEasing(0.2f, 0f, 0f, 1f)
    const val Quick = 120
    const val Standard = 240
    const val Gentle = 420
    /** Hero art and the day's numbers settling in on first show. */
    const val Reveal = 900
    const val Breath = 4200
}

val LocalAuroraColors = staticCompositionLocalOf { DarkColors }
val LocalReducedMotion = staticCompositionLocalOf { false }

object Aurora {
    val colors: AuroraColors
        @Composable @ReadOnlyComposable get() = LocalAuroraColors.current

    /** True when the person (or the system) asked for less motion: no movement, scaling or ambient loops. */
    val reducedMotion: Boolean
        @Composable @ReadOnlyComposable get() = LocalReducedMotion.current
}

/** Tween on the aurora curve, or an instant snap when motion is reduced. */
@Composable
@ReadOnlyComposable
fun <T> auroraTween(durationMs: Int = Motion.Standard): FiniteAnimationSpec<T> =
    if (Aurora.reducedMotion) snap() else tween(durationMs, easing = Motion.Ease)

fun systemAnimationsOff(context: Context): Boolean =
    Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f

@Composable
fun AuroraTheme(reduceMotion: Boolean, content: @Composable () -> Unit) {
    // The northern lights only exist at night, so Aurora has one theme.
    val colors = DarkColors
    val context = LocalContext.current
    // Android Go phones hold still too: every moving light is a redraw they can't spare.
    val systemReduced = remember(context) { systemAnimationsOff(context) || context.getSystemService(android.app.ActivityManager::class.java).isLowRamDevice }
    CompositionLocalProvider(
        LocalAuroraColors provides colors,
        LocalReducedMotion provides (reduceMotion || systemReduced),
    ) {
        MaterialTheme(
            colorScheme = colors.toMaterial(),
            typography = MaterialTypography,
            shapes = Shapes(small = Radius.S, medium = Radius.M, large = Radius.L, extraLarge = Radius.Dialog),
            content = content,
        )
    }
}
