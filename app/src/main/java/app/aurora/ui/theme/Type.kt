package app.aurora.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontSynthesis
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

/** Geist (SIL OFL), bundled as one variable font: crisp, quiet, tabular. The one voice of the interface. */
val Geist = FontFamily(geist(300), geist(380), geist(400), geist(420), geist(500), geist(560), geist(600), geist(650), geist(700))

/**
 * Instrument Serif (SIL OFL): the voice of the dawn. Only what a screen is about is set in it (the headline, the
 * greeting, the promise); everything you read or tap stays in Geist. One weight, never synthesised bold.
 */
val Serif = FontFamily(
    Font(R.font.instrument_serif, FontWeight.Normal, FontStyle.Normal),
    Font(R.font.instrument_serif_italic, FontWeight.Normal, FontStyle.Italic),
)

private fun serif(size: Number, line: Number, tracking: Double = -0.01, italic: Boolean = false) = TextStyle(
    fontFamily = Serif,
    fontSize = size.toFloat().sp,
    lineHeight = line.toFloat().sp,
    fontWeight = FontWeight.Normal,
    fontStyle = if (italic) FontStyle.Italic else FontStyle.Normal,
    fontSynthesis = FontSynthesis.None,
    letterSpacing = tracking.em,
    fontFeatureSettings = "lnum",
)

private fun style(size: Number, line: Number, weight: Int, tracking: Double = 0.0, features: String? = null) = TextStyle(
    fontFamily = Geist,
    fontSize = size.toFloat().sp,
    lineHeight = line.toFloat().sp,
    fontWeight = FontWeight(weight),
    letterSpacing = tracking.em,
    fontFeatureSettings = features,
)

/**
 * Aurora's type scale: a serif for what the screen is about, a quiet sans for everything you read and tap, and light
 * tabular numerals for time, like a watch face. Nothing below 13sp, and supporting text is never faded to a whisper.
 */
object AuroraType {
    /**
     * The one number a screen is about ("1h 42m"). Light, lining and tabular Geist, like the timers: the serif's 1 reads
     * as an l ("lh 30m"), so numbers never use it.
     */
    val Metric = style(56, 60, 300, -0.045, "lnum, tnum")
    /** Timers on the Shield and in Focus: light and wide, a watch face. */
    val Hero = style(72, 76, 300, -0.045, "lnum, tnum")
    /** The quiet first line of a hero headline ("Take back"). */
    val HeroLead = style(19, 26, 420, -0.01)
    /** The hero headline, one serif voice ("Take back control."). */
    val HeroBold = serif(64, 64, -0.025)
    /** One-word screen titles set large: "Insights", "Settings". */
    val DisplayXL = serif(40, 44, -0.015)
    /** Screen headlines ("Never get distracted from what matters."). */
    val Display = serif(38, 42, -0.012)
    /** Step titles in setup flows (tokens: display-s). */
    val DisplayS = serif(32, 36, -0.01)
    val Headline = style(20, 26, 650, -0.02)
    /** The title centred in the top bar. */
    val Bar = style(17, 22, 600, -0.01)
    /** Card and row titles. */
    val Title = style(15.5, 20, 600, 0.0)
    val Body = style(15.5, 22.5, 400, 0.0)
    val BodyStrong = style(15.5, 20, 500, 0.0)
    val Secondary = style(13, 18, 400, 0.0)
    /** The filled action's words. */
    val Label = style(16.5, 20, 600, -0.01)
    /** Chips and segmented choices. */
    val Choice = style(14.5, 18, 500, 0.0)
    val Caption = style(13, 18, 400, 0.0, "tnum")
    /** Section labels: small capitals, tracked, quiet. */
    val Eyebrow = style(12, 16, 600, 0.08)
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
