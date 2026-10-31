package app.aurora.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

/**
 * Raw palette. Screens never use these directly; they read semantic [AuroraColors].
 * Source of truth: brand/tokens/aurora.tokens.json (DesignTokensTest keeps the two in sync).
 * See docs/design-studio/AURORA_BRAND_BIBLE.md.
 */
object Palette {
    // Night: the real polar sky, aubergine black, going green-black only where the light pools.
    val Night1000 = Color(0xFF050609)
    val Night950 = Color(0xFF080A0F)
    val Night900 = Color(0xFF0F1416)
    val Night850 = Color(0xFF15201C)
    val Night800 = Color(0xFF1E2F27)
    val Mist300 = Color(0xFFBFD0C4)
    val Mist400 = Color(0xFF8FA697)
    val Frost = Color(0xFFF2F9F1)

    // Aurora: oxygen green, measured from footage of the real thing. 300 is its hottest knot, 400 the band,
    // 600 the body, 700 where it fades. 500 is the sea-green of a thin curtain, and the Always Block mode.
    val Aurora300 = Color(0xFFC9FFB4)
    val Aurora400 = Color(0xFF4BEA55)
    val Aurora500 = Color(0xFF19C48A)
    val Aurora600 = Color(0xFF17A850)
    val Aurora700 = Color(0xFF0B5A2A)

    // Violet: a rare accent. Indigo: the top of a tall ray, where it thins into the sky.
    val Violet300 = Color(0xFFD0BCFF)
    val Violet400 = Color(0xFFA78BFA)
    val Indigo500 = Color(0xFF3D3A9E)

    // Rose: the pink fringe under a strong curtain. Progress, streaks, the person's own words.
    val Rose300 = Color(0xFFF58BD0)
    val Mint100 = Color(0xFFDDF9D4)

    // Glacier: the Cooldown mode, cool and patient.
    val Glacier400 = Color(0xFF7FB8E6)

    // The primary action is the light itself: the ribbon from its hot core out to its emerald edge.
    val CtaStart = Color(0xFF8BF27A)
    val CtaMid = Color(0xFF4BEA55)
    val CtaEnd = Color(0xFF1FC46A)
    val Starlight = Color(0xFFF4FFF0)
    val Coral400 = Color(0xFFF48FA1)
}

@Immutable
data class AuroraColors(
    val isDark: Boolean,
    val background: Color,
    /** The deepest tone: behind the hero and the Shield. */
    val sky: Color,
    val surface: Color,
    /** A surface raised above [surface]: selected cards, sheets. */
    val elevated: Color,
    val hairline: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textTertiary: Color,
    val accent: Color,
    val onAccent: Color,
    val accentSoft: Color,
    val violet: Color,
    val violetText: Color,
    val violetSoft: Color,
    /** Glass: the thin dark base that keeps text readable, its frost, and its bright edge. */
    val glassBase: Color,
    val glassTop: Color,
    val glassBottom: Color,
    val glassRim: Color,
    /** The faintest light in the sky, where a ribbon fades out. */
    val glowDeep: Color,
    /** The body of a ribbon, around the bright band ([accent]). */
    val glowCool: Color,
    /** The top of a tall ray. */
    val glowTop: Color,
    /** A ribbon's hottest knot. */
    val glowHot: Color,
    /** The brightest light: the thread and the focus arc. */
    val light: Color,
    val danger: Color,
    /** The aurora's pink fringe: streaks and warm emphasis. Text-safe on [surface]. */
    val rose: Color,
    /** The lead phrase of a two-tone hero headline. */
    val headlineLead: Color,
    /** Primary action fill: a sweep of aurora light. */
    val cta: List<Color>,
)

val DarkColors = AuroraColors(
    isDark = true,
    background = Palette.Night950,
    sky = Palette.Night1000,
    surface = Palette.Night900,
    elevated = Palette.Night850,
    hairline = Palette.Aurora300.copy(alpha = 0.14f),
    textPrimary = Palette.Frost,
    textSecondary = Palette.Mist300,
    textTertiary = Palette.Mist400,
    accent = Palette.Aurora400,
    onAccent = Palette.Night950,
    accentSoft = Palette.Aurora400.copy(alpha = 0.13f),
    violet = Palette.Violet400,
    violetText = Palette.Violet300,
    violetSoft = Palette.Violet400.copy(alpha = 0.14f),
    glassBase = Palette.Night900.copy(alpha = 0.46f),
    glassTop = Palette.Frost.copy(alpha = 0.13f),
    glassBottom = Palette.Frost.copy(alpha = 0.035f),
    glassRim = Palette.Frost.copy(alpha = 0.42f),
    glowDeep = Palette.Aurora700,
    glowCool = Palette.Aurora600,
    glowTop = Palette.Indigo500,
    glowHot = Palette.Aurora300,
    light = Palette.Starlight,
    danger = Palette.Coral400,
    rose = Palette.Rose300,
    headlineLead = Palette.Mint100,
    cta = listOf(Palette.CtaStart, Palette.CtaMid, Palette.CtaEnd),
)

/** Maps aurora's semantics onto Material 3 so stock components (switch, text selection, ripples) match. */
fun AuroraColors.toMaterial(): ColorScheme {
    return darkColorScheme().copy(
        primary = accent,
        onPrimary = onAccent,
        primaryContainer = accentSoft,
        onPrimaryContainer = accent,
        secondary = violet,
        onSecondary = Palette.Night950,
        background = background,
        onBackground = textPrimary,
        surface = surface,
        onSurface = textPrimary,
        surfaceVariant = surface,
        onSurfaceVariant = textSecondary,
        surfaceContainerLowest = surface,
        surfaceContainerLow = surface,
        surfaceContainer = surface,
        surfaceContainerHigh = elevated,
        surfaceContainerHighest = elevated,
        outline = textTertiary,
        outlineVariant = hairline,
        error = danger,
        scrim = Color.Black.copy(alpha = 0.5f),
    )
}
