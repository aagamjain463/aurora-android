package app.aurora.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

/**
 * Raw palette: Aurora's finalized monochrome identity (brand/Aurora_Brand_Guidelines.md, 2026-11-07).
 * Obsidian night is the default; Porcelain day is the alternative. There is no accent hue: gold lives only inside the
 * approved logo asset and is never a colour here. Screens never use these directly; they read semantic [AuroraColors].
 * Source of truth: brand/tokens/aurora.tokens.json (DesignTokensTest keeps the two in sync).
 */
object Palette {
    // The five brand colours.
    val Obsidian = Color(0xFF0B0B0D)
    val Graphite = Color(0xFF1B1C20)
    val Slate = Color(0xFF34363B)
    val Porcelain = Color(0xFFF5F5F2)
    val Silver = Color(0xFFA7A9AE)

    // Light theme brand colours.
    val White = Color(0xFFFFFFFF)
    val Ink = Color(0xFF0B0B0D)
    val Stone = Color(0xFF55565A)
    val Rule = Color(0xFFDDDDDA)

    // Neutral steps between them (no hue).
    val Graphite2 = Color(0xFF232428)
    val Graphite3 = Color(0xFF2A2B30)
    val Ash = Color(0xFF86888E)
    val Paper2 = Color(0xFFECECE8)
    val Fog = Color(0xFFC9C9C5)
    val Smoke = Color(0xFF6B6C70)

    // Danger only: ending something early, errors.
    val SignalDark = Color(0xFFF2726B)
    val SignalLight = Color(0xFFC23A33)
}

@Immutable
data class AuroraColors(
    val isDark: Boolean,
    val background: Color,
    /** Full-bleed moments (opening, sign-in, the Shield): the same as [background], named for intent. */
    val sky: Color,
    /** Cards. */
    val surface: Color,
    /** A surface raised above [surface]: icon wells, selected rows, sheets. */
    val elevated: Color,
    val hairline: Color,
    /** The firmer border: outlined buttons, chips, switch tracks. */
    val hairlineStrong: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textTertiary: Color,
    /** The primary action's fill and the strongest ink: Porcelain at night, Obsidian by day. */
    val accent: Color,
    val onAccent: Color,
    val accentSoft: Color,
    /** Quiet states: locked, failed, not yet set. Said in words, never in colour. */
    val muted: Color,
    val mutedText: Color,
    val mutedSoft: Color,
    /** The lead phrase of a two-tone headline. */
    val headlineLead: Color,
    /** Fields, segmented tracks and secondary buttons: a step off the card. */
    val field: Color,
    val danger: Color,
)

val DarkColors = AuroraColors(
    isDark = true,
    background = Palette.Obsidian,
    sky = Palette.Obsidian,
    surface = Palette.Graphite,
    elevated = Palette.Graphite2,
    hairline = Palette.Graphite3,
    hairlineStrong = Palette.Slate,
    textPrimary = Palette.Porcelain,
    textSecondary = Palette.Silver,
    textTertiary = Palette.Ash,
    accent = Palette.Porcelain,
    onAccent = Palette.Obsidian,
    accentSoft = Palette.Porcelain.copy(alpha = 0.08f),
    muted = Palette.Silver,
    mutedText = Palette.Silver,
    mutedSoft = Palette.Graphite2,
    headlineLead = Palette.Silver,
    field = Palette.Graphite2,
    danger = Palette.SignalDark,
)

val LightColors = AuroraColors(
    isDark = false,
    background = Palette.Porcelain,
    sky = Palette.Porcelain,
    surface = Palette.White,
    elevated = Palette.Paper2,
    hairline = Palette.Rule,
    hairlineStrong = Palette.Fog,
    textPrimary = Palette.Ink,
    textSecondary = Palette.Stone,
    textTertiary = Palette.Smoke,
    accent = Palette.Ink,
    onAccent = Palette.Porcelain,
    accentSoft = Palette.Ink.copy(alpha = 0.06f),
    muted = Palette.Stone,
    mutedText = Palette.Stone,
    mutedSoft = Palette.Paper2,
    headlineLead = Palette.Stone,
    field = Palette.Paper2,
    danger = Palette.SignalLight,
)

/** Maps Aurora's semantics onto Material 3 so stock components (switch, text selection, ripples) match. */
fun AuroraColors.toMaterial(): ColorScheme {
    return (if (isDark) darkColorScheme() else lightColorScheme()).copy(
        primary = accent,
        onPrimary = onAccent,
        primaryContainer = accentSoft,
        onPrimaryContainer = accent,
        secondary = muted,
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
