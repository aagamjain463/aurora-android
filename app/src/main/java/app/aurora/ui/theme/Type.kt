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

private fun manrope(weight: Int) = Font(
    resId = R.font.manrope,
    weight = FontWeight(weight),
    variationSettings = FontVariation.Settings(FontVariation.weight(weight)),
)

/** Manrope (SIL OFL), bundled as one variable font. */
val Manrope = FontFamily(manrope(300), manrope(400), manrope(450), manrope(500), manrope(550), manrope(560), manrope(600), manrope(620), manrope(650), manrope(700), manrope(800))

private fun style(size: Int, line: Int, weight: Int, tracking: Double = 0.0, features: String? = null) = TextStyle(
    fontFamily = Manrope,
    fontSize = size.sp,
    lineHeight = line.sp,
    fontWeight = FontWeight(weight),
    letterSpacing = tracking.em,
    fontFeatureSettings = features,
)

/**
 * aurora's type scale. Big, confident numbers; firm headlines; body text that is easy to read at arm's
 * length. Nothing below 13sp, and supporting text is never faded to a whisper.
 */
object AuroraType {
    /** The one number a screen is about. Tabular so digits don't jitter. */
    val Metric = style(56, 60, 500, -0.035, "tnum")
    /** Timers on the Shield and in Focus. */
    val Hero = style(60, 64, 500, -0.04, "tnum")
    /** One-word screen titles set large over the artwork: "Focus". */
    val DisplayXL = style(56, 60, 500, -0.04)
    /** Hero headlines, usually two-tone ([TwoToneHeadline]). Confident but not heavy. */
    val Display = style(36, 42, 500, -0.018)
    val Headline = style(26, 32, 620, -0.02)
    val Title = style(19, 25, 620, -0.012)
    val Body = style(16, 24, 450)
    val BodyStrong = style(16, 22, 620, -0.005)
    val Secondary = style(15, 21, 450)
    val Label = style(16, 20, 620, -0.005)
    val Caption = style(13, 18, 550, 0.005)
    /** Section labels: sentence case, tracked, firm. */
    val Eyebrow = style(13, 16, 650, 0.04)
    /** "Aurora", title case, set beside the symbol. */
    val Wordmark = style(24, 28, 500, -0.01)
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
