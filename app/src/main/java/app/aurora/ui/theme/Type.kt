package app.aurora.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import app.aurora.R

private fun inter(weight: Int) = Font(
    resId = R.font.inter,
    weight = FontWeight(weight),
    variationSettings = FontVariation.Settings(FontVariation.weight(weight)),
)

/** Inter (SIL OFL), bundled as one variable font: Aurora's only typeface. */
val Inter = FontFamily(inter(350), inter(400), inter(500), inter(600), inter(700))

/** Inter's display cut (the same file at optical size 32): finer joins and tighter fit, drawn for very large type. */
private val InterDisplay = FontFamily(
    Font(
        resId = R.font.inter,
        weight = FontWeight.SemiBold,
        variationSettings = FontVariation.Settings(FontVariation.weight(600), FontVariation.Setting("opsz", 32f)),
    ),
)

private fun style(size: Number, line: Number, weight: Int, tracking: Double = 0.0, features: String? = null) = TextStyle(
    fontFamily = Inter,
    fontSize = size.toFloat().sp,
    lineHeight = line.toFloat().sp,
    fontWeight = FontWeight(weight),
    letterSpacing = tracking.em,
    fontFeatureSettings = features,
    // Balanced breaking everywhere: wrapped lines come out even, never one word left alone on the last line.
    lineBreak = LineBreak.Heading,
)

/**
 * Aurora's type scale (brand/tokens/aurora.tokens.json): one family, deliberate weights. Headings are semibold with
 * slightly tightened tracking; everything read is regular; timers and statistics use tabular numerals. Nothing
 * below 12sp and nothing thinner than 350.
 */
object AuroraType {
    /** The one number a screen is about ("1h 42m"). */
    val Metric = style(48, 52, 400, -0.035, "tnum")
    /** Timers on the Shield and in Focus. */
    val Hero = style(64, 68, 350, -0.03, "tnum")
    /** The Focus timer: the time is the screen. */
    val Timer = style(112, 112, 350, -0.05, "tnum")
    /** The seconds or the unit beside the Focus timer. */
    val TimerTail = style(22, 26, 400, -0.01, "tnum")
    /** The quiet first line of a hero headline. */
    val HeroLead = style(17, 24, 500, -0.01)
    /** The hero headline on full-bleed moments ("Take back control."). */
    val HeroBold = style(44, 48, 600, -0.03)
    /** The brand line itself ("Take back control."), set in Inter's display cut. Line height in em so it follows auto-sizing. */
    val BrandLine = style(66, 68, 600, -0.015).copy(fontFamily = InterDisplay, lineHeight = 1.02.em)
    /** One-word screen titles: "Insights", "Settings". */
    val DisplayXL = style(32, 38, 600, -0.025)
    /** Screen headlines. */
    val Display = style(30, 36, 600, -0.025)
    /** Step titles in setup flows. */
    val DisplayS = style(26, 32, 600, -0.02)
    /** Section headings. */
    val Headline = style(20, 26, 600, -0.015)
    /** The title centred in the top bar. */
    val Bar = style(17, 22, 600, -0.01)
    /** Card and row titles. */
    val Title = style(16, 21, 600, -0.01)
    val Body = style(16, 24, 400, -0.005)
    val BodyStrong = style(15, 21, 500, -0.005)
    val Secondary = style(14, 20, 400, 0.0)
    /** The quiet line under a row or card title. */
    val Subtext = style(13, 18, 400, 0.0)
    /** Button labels. */
    val Label = style(16, 20, 600, -0.01)
    /** Chips and segmented choices. */
    val Choice = style(14, 18, 500, 0.0)
    val Caption = style(13, 18, 400, 0.0, "tnum")
    /** Section labels: small and semibold, in sentence case (no shouting capitals). */
    val Eyebrow = style(13, 18, 600, 0.0)
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
