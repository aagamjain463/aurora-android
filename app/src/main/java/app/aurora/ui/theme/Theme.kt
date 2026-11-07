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
    val Gutter = 20.dp
}

/** Corner radii (tokens: radius-dp). Buttons and fields 14dp, cards 20dp, dialogs 24dp; chips and toggles are round. */
object Radius {
    val S = RoundedCornerShape(10.dp)
    /** Buttons, fields, icon wells. */
    val M = RoundedCornerShape(14.dp)
    val Button = M
    /** Cards. */
    val L = RoundedCornerShape(20.dp)
    val Dialog = RoundedCornerShape(24.dp)
    val Pill = RoundedCornerShape(percent = 50)
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
