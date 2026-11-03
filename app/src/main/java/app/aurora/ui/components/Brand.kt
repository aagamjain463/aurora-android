package app.aurora.ui.components

import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.text
import app.aurora.ui.theme.Space
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import app.aurora.ui.theme.Aurora
import app.aurora.ui.theme.AuroraType
import app.aurora.ui.theme.Motion
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * What a screen shows while its data loads: the mark, on aurora's background. It fades in only after a
 * moment, so a fast load never flashes it.
 */
@Composable
fun LoadingSurface(modifier: Modifier = Modifier) {
    val shown = remember { androidx.compose.animation.core.Animatable(0f) }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(300)
        shown.animateTo(1f, tween(Motion.Gentle))
    }
    androidx.compose.foundation.layout.Box(
        modifier.fillMaxSize().background(Aurora.colors.background),
        contentAlignment = androidx.compose.ui.Alignment.Center,
    ) {
        AuroraMark(64.dp, Modifier.graphicsLayer { alpha = shown.value }, lit = true)
    }
}

/** The logo: the mark over the name, stacked, both in the page's ink. */
@Composable
fun AuroraLogo(width: Dp, modifier: Modifier = Modifier) {
    val name = androidx.compose.ui.res.stringResource(app.aurora.R.string.app_name)
    androidx.compose.foundation.layout.Column(
        modifier.width(width).clearAndSetSemantics { contentDescription = name },
        horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
    ) {
        AuroraMark(width * 0.42f, lit = true)
        androidx.compose.foundation.layout.Spacer(Modifier.height(width * 0.08f))
        Text(name, color = Aurora.colors.textPrimary, style = AuroraType.HeroBold.copy(fontSize = (width.value * 0.3f).sp, lineHeight = (width.value * 0.32f).sp))
    }
}

/** The name beside the mark, set in the interface's bold sans, as in the reference. */
@Composable
fun Wordmark(modifier: Modifier = Modifier) {
    Text(
        androidx.compose.ui.res.stringResource(app.aurora.R.string.app_name),
        modifier,
        color = Aurora.colors.textPrimary,
        style = AuroraType.HeroBold.copy(fontSize = 27.sp, lineHeight = 30.sp, letterSpacing = (-0.01).em),
        maxLines = 1,
    )
}

/**
 * The mark, [size] tall. [lit] fills it with the dawn (pale light at the peak, through gold and ember to rose at the
 * feet), as on the launcher icon; otherwise it is in the page's ink. Decorative: the name or the screen says what it is.
 */
@Composable
fun AuroraMark(size: Dp, modifier: Modifier = Modifier, lit: Boolean = false) {
    val colors = Aurora.colors
    val dawn = if (colors.isDark) listOf(colors.light, colors.glowTop, colors.glowHot, colors.glowCool) else listOf(colors.glowHot, colors.light, colors.rose)
    androidx.compose.material3.Icon(
        androidx.compose.ui.res.painterResource(app.aurora.R.drawable.ic_aurora_mark),
        null,
        modifier.height(size).aspectRatio(MarkAspect).then(
            if (!lit) Modifier else Modifier
                .graphicsLayer { compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen }
                .drawWithContent {
                    drawContent()
                    drawRect(androidx.compose.ui.graphics.Brush.verticalGradient(dawn), blendMode = androidx.compose.ui.graphics.BlendMode.SrcIn)
                },
        ),
        tint = colors.textPrimary,
    )
}

private const val MarkAspect = 20f / 15.6f

/** The symbol beside the wordmark: sized in dp, so it grows with text size to keep the lockup balanced. */
val LockupMark: androidx.compose.ui.unit.Dp
    @Composable get() = 20.3.dp * androidx.compose.ui.platform.LocalDensity.current.fontScale.coerceIn(1f, 1.8f)

/**
 * The top of every tab screen: the symbol and wordmark at the left, Settings at the right. Aurora has no
 * accounts, so there is no profile button; Settings uses the sliders glyph, which can't be mistaken for
 * the symbol.
 */
@Composable
fun AuroraHeader(onOpenSettings: (() -> Unit)?, modifier: Modifier = Modifier, title: String? = null, onInfo: (() -> Unit)? = null) {
    val colors = Aurora.colors
    androidx.compose.foundation.layout.Row(
        modifier.fillMaxWidth().heightIn(min = 60.dp).padding(start = Space.Gutter, end = Space.Gutter),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
    ) {
        AuroraMark(LockupMark, lit = true)
        androidx.compose.foundation.layout.Spacer(Modifier.width(10.dp))
        androidx.compose.foundation.layout.Column(Modifier.weight(1f).semantics(mergeDescendants = true) { }) {
            Wordmark()
            if (title != null) Text(title, color = colors.textSecondary, style = AuroraType.Caption)
        }
        // What this screen offers, explained: a quiet round button beside Settings.
        if (onInfo != null) {
            androidx.compose.foundation.layout.Box(
                Modifier.size(42.dp).clip(CircleShape).background(colors.elevated)
                    .clickable(role = androidx.compose.ui.semantics.Role.Button, onClickLabel = androidx.compose.ui.res.stringResource(app.aurora.R.string.guide_open), onClick = onInfo),
                contentAlignment = androidx.compose.ui.Alignment.Center,
            ) {
                androidx.compose.material3.Icon(AuroraIcons.Info, androidx.compose.ui.res.stringResource(app.aurora.R.string.guide_open), tint = colors.textPrimary, modifier = Modifier.size(20.dp))
            }
            if (onOpenSettings != null) androidx.compose.foundation.layout.Spacer(Modifier.width(10.dp))
        }
        if (onOpenSettings != null) {
            androidx.compose.foundation.layout.Box(
                Modifier.size(42.dp).clip(CircleShape).background(colors.elevated)
                    .clickable(role = androidx.compose.ui.semantics.Role.Button, onClickLabel = androidx.compose.ui.res.stringResource(app.aurora.R.string.settings_title), onClick = onOpenSettings),
                contentAlignment = androidx.compose.ui.Alignment.Center,
            ) {
                androidx.compose.material3.Icon(AuroraIcons.Settings, androidx.compose.ui.res.stringResource(app.aurora.R.string.settings_title), tint = colors.textPrimary, modifier = Modifier.size(20.dp))
            }
        }
    }
}

/** A short thread of light introduces sections without adding another card or badge. */
@Composable
fun BrandEyebrow(text: String, modifier: Modifier = Modifier) {
    val colors = Aurora.colors
    androidx.compose.foundation.layout.Row(
        modifier,
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(Space.S),
    ) {
        Canvas(Modifier.size(width = 28.dp, height = 12.dp).clearAndSetSemantics { }) {
            drawLine(colors.textTertiary, Offset(0f, center.y), Offset(size.width, center.y), strokeWidth = 1.5.dp.toPx(), cap = StrokeCap.Round)
        }
        Eyebrow(text, color = colors.textSecondary)
    }
}

/**
 * The reference's greeting: the lead in body size ("Good morning,"), the last [tailWords] words large and bold on
 * their own line ("Aagam"). The words themselves never change; only the break and the sizes are design.
 */
@Composable
fun GreetingHeadline(text: String, modifier: Modifier = Modifier, tailWords: Int = 2) {
    val colors = Aurora.colors
    val words = text.split(" ")
    val split = (words.size - tailWords).coerceIn(0, words.size)
    androidx.compose.foundation.layout.Column(modifier.clearAndSetSemantics { heading(); semanticsText(androidx.compose.ui.text.AnnotatedString(text)) }) {
        if (split > 0) Text(words.take(split).joinToString(" "), color = colors.headlineLead, style = AuroraType.Body.copy(fontSize = 16.sp, lineHeight = 22.sp))
        Text(words.drop(split).joinToString(" "), color = colors.textPrimary, style = AuroraType.Display.copy(fontSize = 44.sp, lineHeight = 48.sp))
    }
}

/**
 * The hero headline: every word but the last [tailWords] light, then the tail large and bold on its own line
 * ("Your phone." / "Your rules."). The words never change; only the break and the sizes are design.
 */
@Composable
fun HeroHeadline(text: String, modifier: Modifier = Modifier, tailWords: Int = 2) {
    val colors = Aurora.colors
    val words = text.split(" ")
    val split = (words.size - tailWords).coerceIn(0, words.size)
    androidx.compose.foundation.layout.Column(modifier.clearAndSetSemantics { heading(); semanticsText(androidx.compose.ui.text.AnnotatedString(text)) }) {
        if (split > 0) Text(words.take(split).joinToString(" "), color = colors.headlineLead, style = AuroraType.HeroLead)
        Text(words.drop(split).joinToString(" "), Modifier.padding(top = 2.dp), color = colors.textPrimary, style = AuroraType.HeroBold)
    }
}

/**
 * A hero headline in two tones: the lead in pale aurora, the last [tailWords] words in full white,
 * on their own line. The words themselves are never changed (founder-approved copy stays exact); only the
 * line break and colour are design.
 */
@Composable
fun TwoToneHeadline(
    text: String,
    modifier: Modifier = Modifier,
    tailWords: Int = 2,
    style: androidx.compose.ui.text.TextStyle = AuroraType.Display,
    leadFirst: Boolean = true,
    textAlign: androidx.compose.ui.text.style.TextAlign? = null,
) {
    val colors = Aurora.colors
    val words = text.split(" ")
    val spoken = androidx.compose.ui.text.AnnotatedString(text)
    val split = (words.size - tailWords).coerceIn(0, words.size)
    val lead = words.take(split).joinToString(" ")
    val tail = words.drop(split).joinToString(" ")
    val annotated = androidx.compose.ui.text.buildAnnotatedString {
        val leadColor = if (leadFirst) colors.headlineLead else colors.textPrimary
        val tailColor = if (leadFirst) colors.textPrimary else colors.headlineLead
        if (lead.isNotEmpty()) {
            pushStyle(androidx.compose.ui.text.SpanStyle(color = leadColor)); append(lead); pop()
            append(if (leadFirst) "\n" else " ")
        }
        pushStyle(androidx.compose.ui.text.SpanStyle(color = tailColor)); append(tail); pop()
    }
    Text(
        annotated,
        // Screen readers and tests get the exact copy, without the designed line break.
        modifier.clearAndSetSemantics { heading(); semanticsText(spoken) },
        style = style,
        textAlign = textAlign,
    )
}

/**
 * The Focus dial, shared by both Focus timers: an instrument, not a decoration. Sixty hairline ticks make a precision
 * bezel around a single hairline track. The time held is drawn on the track in the dawn (rose where it began, warming
 * through ember and gold to the light at its head, where a small sun rides) and every tick it has passed is lit the
 * same way, so a finished session is a full sunrise. Inside: a tracked [label], the light tabular [clock] and one
 * quiet [caption]. Nothing else. [progress] is 0..1 of the time held.
 */
@Composable
fun FocusDial(
    progress: Float,
    clock: String,
    modifier: Modifier = Modifier,
    label: String? = null,
    caption: String? = null,
    spoken: String = clock,
) {
    val colors = Aurora.colors
    // The ViewModel ticks once a second; a linear 1s glide makes the light move continuously.
    val fill by rememberFill(progress.coerceIn(0f, 1f), tween(1000, easing = LinearEasing))
    Box(modifier.widthIn(max = 340.dp).aspectRatio(1f), contentAlignment = androidx.compose.ui.Alignment.Center) {
        Canvas(Modifier.fillMaxSize().clearAndSetSemantics { }) {
            val c = center
            val outer = size.minDimension / 2f - 2.dp.toPx()
            val major = 11.dp.toPx()
            val minor = 5.dp.toPx()
            val track = outer - major - 12.dp.toPx()
            // Depth: a faint warm well inside the track that deepens as the time is held.
            drawCircle(
                androidx.compose.ui.graphics.Brush.radialGradient(
                    listOf(colors.glowDeep.copy(alpha = (if (colors.isDark) 0.10f else 0.05f) + 0.14f * fill), Color.Transparent),
                    c, track,
                ),
                track, c,
            )
            // The bezel: every minute a hairline, every fifth a longer one. Passed ticks take the dawn.
            for (i in 0 until 60) {
                val t = i / 60f
                val isMajor = i % 5 == 0
                val a = Math.toRadians(i * 6.0 - 90.0)
                val dx = cos(a).toFloat()
                val dy = sin(a).toFloat()
                val inner = outer - if (isMajor) major else minor
                val lit = fill > 0f && t <= fill
                val color = if (lit) androidx.compose.ui.graphics.lerp(colors.glowCool, colors.glowTop, (t / fill).coerceIn(0f, 1f))
                else colors.textTertiary.copy(alpha = if (isMajor) 0.55f else 0.24f)
                drawLine(
                    color, Offset(c.x + inner * dx, c.y + inner * dy), Offset(c.x + outer * dx, c.y + outer * dy),
                    strokeWidth = (if (isMajor) 1.6f else 1f).dp.toPx(), cap = StrokeCap.Round,
                )
            }
            val topLeft = Offset(c.x - track, c.y - track)
            val arcSize = Size(2 * track, 2 * track)
            drawCircle(colors.hairline, track, c, style = Stroke(1.dp.toPx()))
            if (fill > 0f) rotate(-90f, c) {
                val dawn = androidx.compose.ui.graphics.Brush.sweepGradient(
                    0f to colors.glowCool, (fill * 0.5f) to colors.glowHot, (fill * 0.85f).coerceAtLeast(0.001f) to colors.glowTop,
                    fill.coerceAtLeast(0.002f) to colors.light, 1f to colors.light,
                    center = c,
                )
                drawArc(dawn, 0f, 360f * fill, false, topLeft, arcSize, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round))
                val a = Math.toRadians(360.0 * fill)
                val sun = Offset(c.x + track * cos(a).toFloat(), c.y + track * sin(a).toFloat())
                drawCircle(
                    androidx.compose.ui.graphics.Brush.radialGradient(listOf(colors.glowTop.copy(alpha = 0.5f), Color.Transparent), sun, 18.dp.toPx()),
                    18.dp.toPx(), sun,
                )
                drawCircle(colors.background, 6.dp.toPx(), sun)
                drawCircle(colors.light, 4.dp.toPx(), sun)
            }
        }
        androidx.compose.foundation.layout.Column(
            Modifier.fillMaxWidth(0.66f),
            horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
        ) {
            label?.let { Eyebrow(it, textAlign = androidx.compose.ui.text.style.TextAlign.Center) }
            RollingClock(clock, colors.textPrimary, AuroraType.Hero, Modifier.fillMaxWidth().padding(vertical = 6.dp), spoken = spoken)
            caption?.let { Text(it, color = colors.textTertiary, style = AuroraType.Caption, textAlign = androidx.compose.ui.text.style.TextAlign.Center) }
        }
    }
}

private fun androidx.compose.ui.semantics.SemanticsPropertyReceiver.semanticsText(value: androidx.compose.ui.text.AnnotatedString) {
    text = value
}
