package app.aurora.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

/**
 * Raw palette: First Light. Aurora is the dawn, so the night theme (the default) is a deep violet-black sky and the
 * one colour in the product is the light coming up over the horizon: plum, rose, ember, gold. The day theme is warm
 * paper with the same dawn, softer. Screens never use these directly; they read semantic [AuroraColors].
 * Source of truth: brand/tokens/aurora.tokens.json (DesignTokensTest keeps the two in sync).
 */
object Palette {
    // Night: the dark theme.
    val Night1000 = Color(0xFF06050A)
    val Night950 = Color(0xFF0A090F)
    val Night900 = Color(0xFF131119)
    val Night850 = Color(0xFF1B1822)
    val Night800 = Color(0xFF25212D)
    val Night700 = Color(0xFF383340)
    val Mist700 = Color(0xFF6E6874)
    val Mist500 = Color(0xFF9B94A0)
    val Mist300 = Color(0xFFC9C1C6)
    val Ivory = Color(0xFFF6F0E8)

    // Dawn: the light over the horizon. The only colour Aurora owns.
    val Plum700 = Color(0xFF3B1E3A)
    val Rose500 = Color(0xFFD0607A)
    val Rose400 = Color(0xFFE8899A)
    val Ember400 = Color(0xFFF59164)
    val Gold300 = Color(0xFFFFD39A)
    val Peach200 = Color(0xFFFFD7B5)
    val Dawn100 = Color(0xFFFFF1E0)

    // Day: the light theme.
    val Day25 = Color(0xFFFFFDFA)
    val Day50 = Color(0xFFFBF7F2)
    val Day100 = Color(0xFFF4EEE7)
    val Day150 = Color(0xFFF1EAE2)
    val Day200 = Color(0xFFEAE1D7)
    val Day300 = Color(0xFFDDD2C6)
    val Umber400 = Color(0xFFADA196)
    val Umber600 = Color(0xFF6F655C)
    val Umber700 = Color(0xFF4A423B)
    val Ink = Color(0xFF16131A)
    val Ink800 = Color(0xFF2A2430)
    val Blush200 = Color(0xFFF3D3C6)
    val Clay400 = Color(0xFFE59A76)
    val Ember700 = Color(0xFFB8532C)
    val Rose700 = Color(0xFFA9445A)

    // Danger only: ending something early, errors.
    val Coral400 = Color(0xFFF08A80)
    val Coral700 = Color(0xFFB3261E)
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
    /** Primary action fill. */
    val cta: List<Color>,
    /** The bottom bar, and the well behind its chosen tab. */
    val dock: Color,
    val dockWell: Color,
    /** Fields, segmented tracks and secondary buttons: a step off the card. */
    val field: Color,
    /** The earth under the Horizon: [dune] is its night side, [duneBack] the lit band just inside its rim. */
    val dune: Color,
    val duneBack: Color,
    /** The firmer hairline: outlined buttons, chips, the dock and switch tracks. */
    val hairlineStrong: Color,
)

val DarkColors = AuroraColors(
    isDark = true,
    background = Palette.Night950,
    sky = Palette.Night1000,
    surface = Palette.Night900,
    elevated = Palette.Night850,
    hairline = Color.White.copy(alpha = 0.06f),
    textPrimary = Palette.Ivory,
    textSecondary = Palette.Mist300,
    textTertiary = Palette.Mist500,
    accent = Palette.Peach200,
    onAccent = Palette.Night1000,
    accentSoft = Palette.Peach200.copy(alpha = 0.12f),
    violet = Palette.Mist300,
    violetText = Palette.Mist300,
    violetSoft = Color.White.copy(alpha = 0.06f),
    glassBase = Palette.Night900,
    glassTop = Palette.Night850,
    glassBottom = Palette.Night900,
    glassRim = Color.White.copy(alpha = 0.13f),
    glowDeep = Palette.Plum700,
    glowCool = Palette.Rose500,
    glowTop = Palette.Gold300,
    glowHot = Palette.Ember400,
    light = Palette.Dawn100,
    danger = Palette.Coral400,
    rose = Palette.Rose400,
    headlineLead = Palette.Mist300,
    cta = listOf(Palette.Dawn100, Palette.Gold300),
    dock = Palette.Night900,
    dockWell = Palette.Night700,
    field = Palette.Night850,
    dune = Palette.Night1000,
    duneBack = Palette.Plum700,
    hairlineStrong = Color.White.copy(alpha = 0.11f),
)

val LightColors = AuroraColors(
    isDark = false,
    background = Palette.Day50,
    sky = Palette.Day100,
    surface = Palette.Day25,
    elevated = Palette.Day200,
    hairline = Color.Black.copy(alpha = 0.07f),
    textPrimary = Palette.Ink,
    textSecondary = Palette.Umber700,
    textTertiary = Palette.Umber600,
    accent = Palette.Ink,
    onAccent = Palette.Day50,
    accentSoft = Palette.Ink.copy(alpha = 0.07f),
    violet = Palette.Umber700,
    violetText = Palette.Umber700,
    violetSoft = Color.Black.copy(alpha = 0.05f),
    glassBase = Palette.Day25,
    glassTop = Palette.Day25,
    glassBottom = Palette.Day25,
    glassRim = Color.White,
    glowDeep = Palette.Blush200,
    glowCool = Palette.Rose400,
    glowTop = Palette.Gold300,
    glowHot = Palette.Clay400,
    light = Palette.Ember700,
    danger = Palette.Coral700,
    rose = Palette.Rose700,
    headlineLead = Palette.Umber700,
    cta = listOf(Palette.Ink800, Palette.Ink),
    dock = Palette.Day25,
    dockWell = Palette.Day200,
    field = Palette.Day150,
    dune = Palette.Day150,
    duneBack = Palette.Blush200,
    hairlineStrong = Color.Black.copy(alpha = 0.10f),
)

/** Maps aurora's semantics onto Material 3 so stock components (switch, text selection, ripples) match. */
fun AuroraColors.toMaterial(): ColorScheme {
    return (if (isDark) darkColorScheme() else lightColorScheme()).copy(
        primary = accent,
        onPrimary = onAccent,
        primaryContainer = accentSoft,
        onPrimaryContainer = accent,
        secondary = violet,
        onSecondary = background,
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
