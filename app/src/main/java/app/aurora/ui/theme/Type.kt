package app.aurora.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import app.aurora.R

private fun geist(weight: Int) = Font(
    resId = R.font.geist,
    weight = FontWeight(weight),
    variationSettings = FontVariation.Settings(FontVariation.weight(weight)),
)

/** Geist (SIL OFL), bundled as one variable font: crisp, quiet, tabular. The voice of the interface. */
val Geist = FontFamily(geist(400), geist(420), geist(500), geist(560))

/**
 * Cormorant Garamond (SIL OFL): headlines and every big number. Its fine strokes are light on a night sky.
 * Its letters are small for their size, so it is set larger than most serifs, and never below [AuroraType.DisplayS].
 */
val Cormorant = FontFamily(Font(
    R.font.cormorant,
    weight = FontWeight.Medium,
    variationSettings = FontVariation.Settings(FontVariation.weight(500)),
))

/** The lighter cut, for numbers so large they read as engraved, not as a stopwatch. */
val CormorantNumerals = FontFamily(Font(
    R.font.cormorant,
    weight = FontWeight.Normal,
    variationSettings = FontVariation.Settings(FontVariation.weight(400)),
))

private fun style(size: Int, line: Int, weight: Int, tracking: Double = 0.0, features: String? = null) = TextStyle(
    fontFamily = Geist,
    fontSize = size.sp,
    lineHeight = line.sp,
    fontWeight = FontWeight(weight),
    letterSpacing = tracking.em,
    fontFeatureSettings = features,
)

/**
 * aurora's type scale. A fine serif for what the screen is about, a crisp sans for everything you act on. Nothing below
 * 13sp, and supporting text is never faded to a whisper.
 */
object AuroraType {
    /** The one number a screen is about. Lining and tabular, so digits line up and don't jitter. */
    val Metric = style(66, 68, 400, -0.015, "lnum, tnum").copy(fontFamily = CormorantNumerals)
    /** Timers on the Shield and in Focus: the signature of the app. */
    val Hero = style(92, 94, 400, -0.02, "lnum, tnum").copy(fontFamily = CormorantNumerals)
    /** One-word screen titles set large over the artwork: "Track". */
    val DisplayXL = style(68, 70, 500, -0.02, "lnum").copy(fontFamily = Cormorant)
    /** Hero headlines, usually two-tone ([TwoToneHeadline]). Confident but not heavy. */
    val Display = style(50, 52, 500, -0.018, "lnum").copy(fontFamily = Cormorant)
    /** Step titles in setup flows (tokens: display-s). */
    val DisplayS = style(36, 40, 500, -0.012, "lnum").copy(fontFamily = Cormorant)
    val Headline = style(24, 30, 560, -0.022)
    val Title = style(18, 24, 560, -0.014)
    val Body = style(16, 24, 420, -0.004)
    val BodyStrong = style(16, 22, 560, -0.008)
    val Secondary = style(15, 21, 420, -0.002)
    val Label = style(16, 20, 560, -0.008)
    val Caption = style(13, 18, 500, 0.002, "tnum")
    /** Section labels: sentence case, tracked, firm. */
    val Eyebrow = style(13, 16, 560, 0.018)
}

val MaterialTypography = Typography(
    displayLarge = AuroraType.Hero,
    headlineMedium = AuroraType.Display,
    headlineSmall = AuroraType.Headline,
    titleLarge = AuroraType.Title,
    titleMedium = AuroraType.BodyStrong,
    bodyLarge = AuroraType.Body,
    bodyMedium = AuroraType.Secondary,
    labelLarge = AuroraType.Label,
    labelMedium = AuroraType.Caption,
    labelSmall = AuroraType.Caption,
)
