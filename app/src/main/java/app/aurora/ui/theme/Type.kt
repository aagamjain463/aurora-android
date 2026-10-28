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
val Manrope = FontFamily(manrope(300), manrope(400), manrope(500), manrope(600), manrope(700))

private fun style(size: Int, line: Int, weight: Int, tracking: Double = 0.0, features: String? = null) = TextStyle(
    fontFamily = Manrope,
    fontSize = size.sp,
    lineHeight = line.sp,
    fontWeight = FontWeight(weight),
    letterSpacing = tracking.em,
    fontFeatureSettings = features,
)

/** aurora's type scale. Light, large numerals; firm headlines; quiet body. */
object AuroraType {
    /** Timers and the one number a screen is about. Tabular so digits don't jitter. */
    val Hero = style(64, 68, 300, -0.035, "tnum")
    val Display = style(34, 40, 600, -0.025)
    val Headline = style(26, 32, 600, -0.02)
    val Title = style(18, 24, 600, -0.01)
    val Body = style(16, 24, 400)
    val BodyStrong = style(16, 24, 600, -0.005)
    val Secondary = style(14, 20, 400)
    val Label = style(15, 20, 600, 0.0)
    val Caption = style(12, 16, 500, 0.01)
    /** Section labels: lowercase with open tracking. Never uppercase; aurora speaks softly. */
    val Eyebrow = style(12, 16, 600, 0.09)
    val Wordmark = style(22, 26, 600, -0.03)
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
