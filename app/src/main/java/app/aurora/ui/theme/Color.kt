package app.aurora.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

/** Raw palette. Screens never use these directly; they read semantic [AuroraColors]. See docs/BRAND.md. */
object Palette {
    // Night: rich green-tinted near-blacks, the sky an hour before dawn.
    val Night1000 = Color(0xFF050B09)
    val Night950 = Color(0xFF08110E)
    val Night900 = Color(0xFF0E1915)
    val Night850 = Color(0xFF14221D)
    val Night800 = Color(0xFF1B2C26)
    val Mist300 = Color(0xFFB4C2BC)
    val Mist400 = Color(0xFF8A9993)
    val Frost = Color(0xFFF1F6F3)

    // Day: warm off-whites, like paper in early light.
    val Linen = Color(0xFFF5F1E8)
    val Paper = Color(0xFFFFFCF6)
    val Shell = Color(0xFFEDE7DA)
    val Ink = Color(0xFF0B1411)
    val Ink75 = Color(0xFF39453F)
    val Ink60 = Color(0xFF5B6761)

    // Aurora: the signature luminous green. 400 on night, 700 on day.
    val Aurora300 = Color(0xFFA6F0CF)
    val Aurora400 = Color(0xFF7BE3B6)
    val Aurora500 = Color(0xFF3FC495)
    val Aurora700 = Color(0xFF137A57)
    val Aurora900 = Color(0xFF0A3D2C)

    // Dawn: the warm counterpart, for completion, commitment and human moments.
    val Dawn300 = Color(0xFFF7CFAF)
    val Dawn400 = Color(0xFFF0B48A)
    val Dawn700 = Color(0xFF9C4A20)

    val Starlight = Color(0xFFFFF6E6)
    val Coral400 = Color(0xFFF2877E)
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
    val dawn: Color,
    val dawnText: Color,
    val dawnSoft: Color,
    val glowCool: Color,
    val glowWarm: Color,
    /** The rising point of light in the mark and the focus arc. */
    val light: Color,
    val danger: Color,
)

val LightColors = AuroraColors(
    isDark = false,
    background = Palette.Linen,
    sky = Palette.Shell,
    surface = Palette.Paper,
    elevated = Color.White,
    hairline = Palette.Ink.copy(alpha = 0.09f),
    textPrimary = Palette.Ink,
    textSecondary = Palette.Ink75,
    textTertiary = Palette.Ink60,
    accent = Palette.Aurora700,
    onAccent = Color.White,
    accentSoft = Palette.Aurora700.copy(alpha = 0.10f),
    dawn = Palette.Dawn400,
    dawnText = Palette.Dawn700,
    dawnSoft = Palette.Dawn400.copy(alpha = 0.20f),
    glowCool = Palette.Aurora500,
    glowWarm = Palette.Dawn400,
    light = Palette.Aurora700,
    danger = Palette.Coral700,
)

val DarkColors = AuroraColors(
    isDark = true,
    background = Palette.Night950,
    sky = Palette.Night1000,
    surface = Palette.Night900,
    elevated = Palette.Night850,
    hairline = Color.White.copy(alpha = 0.09f),
    textPrimary = Palette.Frost,
    textSecondary = Palette.Mist300,
    textTertiary = Palette.Mist400,
    accent = Palette.Aurora400,
    onAccent = Palette.Night950,
    accentSoft = Palette.Aurora400.copy(alpha = 0.13f),
    dawn = Palette.Dawn400,
    dawnText = Palette.Dawn300,
    dawnSoft = Palette.Dawn400.copy(alpha = 0.14f),
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
