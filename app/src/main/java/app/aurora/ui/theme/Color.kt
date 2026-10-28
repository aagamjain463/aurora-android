package app.aurora.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

/** Raw palette. Screens never use these directly; they read semantic [AuroraColors]. See docs/BRAND.md. */
object Palette {
    // Polar: neutrals with a faint green-teal undertone, like a northern night.
    val Polar950 = Color(0xFF0A0F0E)
    val Polar900 = Color(0xFF111816)
    val Polar850 = Color(0xFF18211E)
    val Polar400 = Color(0xFF8D9894)
    val Polar450 = Color(0xFF717E7A)
    val Frost = Color(0xFFECEFEC)

    // Mist: warm, paper-like light neutrals.
    val Mist = Color(0xFFF3F4EF)
    val Paper = Color(0xFFFBFBF8)
    val Ink = Color(0xFF0F1715)
    val Ink70 = Color(0xFF45504C)
    val Ink55 = Color(0xFF646F6B)

    // Aurora: the signature luminous green. 400 for dark surfaces, 700 for light ones.
    val Aurora400 = Color(0xFF7BE3B6)
    val Aurora500 = Color(0xFF3FC495)
    val Aurora700 = Color(0xFF137A57)

    // Dawn: the warm secondary, reserved for completion and human moments.
    val Dawn400 = Color(0xFFF0B48A)
    val Dawn700 = Color(0xFFA64B22)

    val Starlight = Color(0xFFF6F1E7)
    val Coral400 = Color(0xFFF2877E)
    val Coral700 = Color(0xFFB3261E)
}

@Immutable
data class AuroraColors(
    val isDark: Boolean,
    val background: Color,
    val surface: Color,
    val hairline: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textTertiary: Color,
    val accent: Color,
    val onAccent: Color,
    val accentSoft: Color,
    val dawn: Color,
    val dawnText: Color,
    val glowCool: Color,
    val glowWarm: Color,
    /** The rising point of light in the mark and the focus arc. */
    val light: Color,
    val danger: Color,
)

val LightColors = AuroraColors(
    isDark = false,
    background = Palette.Mist,
    surface = Palette.Paper,
    hairline = Palette.Ink.copy(alpha = 0.08f),
    textPrimary = Palette.Ink,
    textSecondary = Palette.Ink70,
    textTertiary = Palette.Ink55,
    accent = Palette.Aurora700,
    onAccent = Color.White,
    accentSoft = Palette.Aurora700.copy(alpha = 0.10f),
    dawn = Palette.Dawn400,
    dawnText = Palette.Dawn700,
    glowCool = Palette.Aurora500,
    glowWarm = Palette.Dawn400,
    light = Palette.Aurora700,
    danger = Palette.Coral700,
)

val DarkColors = AuroraColors(
    isDark = true,
    background = Palette.Polar950,
    surface = Palette.Polar900,
    hairline = Color.White.copy(alpha = 0.08f),
    textPrimary = Palette.Frost,
    textSecondary = Palette.Polar400,
    textTertiary = Palette.Polar450,
    accent = Palette.Aurora400,
    onAccent = Palette.Polar950,
    accentSoft = Palette.Aurora400.copy(alpha = 0.12f),
    dawn = Palette.Dawn400,
    dawnText = Palette.Dawn400,
    glowCool = Palette.Aurora500,
    glowWarm = Palette.Dawn400,
    light = Palette.Starlight,
    danger = Palette.Coral400,
)

/** Maps aurora's semantics onto Material 3 so stock components (switch, text selection, ripples) match. */
fun AuroraColors.toMaterial(): ColorScheme {
    val base = if (isDark) darkColorScheme() else lightColorScheme()
    return base.copy(
        primary = accent,
        onPrimary = onAccent,
        primaryContainer = accentSoft,
        onPrimaryContainer = accent,
        secondary = dawn,
        onSecondary = Palette.Polar950,
        background = background,
        onBackground = textPrimary,
        surface = surface,
        onSurface = textPrimary,
        surfaceVariant = surface,
        onSurfaceVariant = textSecondary,
        surfaceContainerLowest = surface,
        surfaceContainerLow = surface,
        surfaceContainer = surface,
        surfaceContainerHigh = surface,
        surfaceContainerHighest = surface,
        outline = textTertiary,
        outlineVariant = hairline,
        error = danger,
        scrim = Color.Black.copy(alpha = 0.5f),
    )
}
