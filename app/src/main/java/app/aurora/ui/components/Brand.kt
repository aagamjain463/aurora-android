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
    val dawn = dawnColors()
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

/** The dawn that lights the mark, top to bottom: pale light at the peak, through gold and ember to rose. */
@Composable
fun dawnColors(): List<androidx.compose.ui.graphics.Color> {
    val colors = Aurora.colors
    return if (colors.isDark) listOf(colors.light, colors.glowTop, colors.glowHot, colors.glowCool) else listOf(colors.glowHot, colors.light, colors.rose)
}

/**
 * The hero headline ("Take back control."): one serif voice in full white, set large and left to wrap as a
 * headline would. The last word is the emphasis, lit by the same dawn as the mark (founder, 2026-11-03).
 */
@Composable
fun HeroHeadline(text: String, modifier: Modifier = Modifier, style: androidx.compose.ui.text.TextStyle = AuroraType.HeroBold) {
    val colors = Aurora.colors
    val cut = text.trimEnd().lastIndexOf(' ') + 1
    val dawn = androidx.compose.ui.graphics.Brush.horizontalGradient(dawnColors().let { if (colors.isDark) it.drop(1) else it })
    val annotated = androidx.compose.ui.text.buildAnnotatedString {
        append(text.substring(0, cut))
        pushStyle(androidx.compose.ui.text.SpanStyle(brush = dawn)); append(text.substring(cut)); pop()
    }
    Text(
        annotated, modifier.semantics { heading() }, color = colors.textPrimary,
        style = style.copy(lineBreak = androidx.compose.ui.text.style.LineBreak.Heading),
    )
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

/** A running Focus's goal, in the person's own words: the serif's italic, above the timer. */
@Composable
fun FocusGoal(goal: String, modifier: Modifier = Modifier) {
    Text(
        goal, modifier.fillMaxWidth().semantics { heading() },
        color = Aurora.colors.textPrimary, textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        maxLines = 3, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
        style = AuroraType.DisplayS.copy(fontSize = 32.sp, lineHeight = 38.sp, fontStyle = androidx.compose.ui.text.font.FontStyle.Italic),
    )
}

/**
 * The Focus dial, shared by both Focus timers: a watch face. A hairline track with a bezel of minute marks inside it;
 * the time held is drawn on the track in the dawn (rose where it began, warming through ember and gold to the light at
 * its head, where a point of first light rides), and the marks it has passed brighten, so a finished session is a
 * closed ring of dawn. No glow. While [live] the point breathes, slowly. Inside: the lit mark, a tracked [label], the
 * light tabular [clock] and one quiet [caption]. [progress] is 0..1 of the time held; [follow] is how the ring
 * catches up with it (a 1s linear glide for a ticking clock, quicker under a finger).
 */
@Composable
fun FocusDial(
    progress: Float,
    clock: String,
    modifier: Modifier = Modifier,
    label: String? = null,
    caption: String? = null,
    spoken: String = clock,
    live: Boolean = false,
    follow: androidx.compose.animation.core.AnimationSpec<Float> = tween(1000, easing = LinearEasing),
) {
    val colors = Aurora.colors
    val fill by rememberFill(progress.coerceIn(0f, 1f), follow)
    val breath = if (live && !Aurora.reducedMotion) {
        rememberInfiniteTransition(label = "breath").animateFloat(
            0f, 1f, infiniteRepeatable(tween(2400, easing = Motion.Ease), androidx.compose.animation.core.RepeatMode.Reverse), label = "breath",
        )
    } else null
    Box(modifier.widthIn(max = 340.dp).aspectRatio(1f), contentAlignment = androidx.compose.ui.Alignment.Center) {
        Canvas(Modifier.fillMaxSize().clearAndSetSemantics { }) {
            val c = center
            val stroke = 2.5.dp.toPx()
            val r = size.minDimension / 2f - 10.dp.toPx()
            drawCircle(colors.hairlineStrong, r, c, style = Stroke(1.dp.toPx()))
            // The bezel: sixty marks, every fifth longer. Those the light has passed are a step brighter.
            val outer = r - 12.dp.toPx()
            for (i in 0 until 60) {
                val major = i % 5 == 0
                val passed = fill > 0f && i / 60f <= fill
                val a = Math.toRadians(i * 6.0 - 90.0)
                val dx = cos(a).toFloat()
                val dy = sin(a).toFloat()
                val inner = outer - (if (major) 9.dp else 4.dp).toPx()
                drawLine(
                    when {
                        passed -> colors.textSecondary.copy(alpha = if (major) 0.75f else 0.4f)
                        major -> colors.textTertiary.copy(alpha = 0.5f)
                        else -> colors.hairlineStrong
                    },
                    Offset(c.x + dx * inner, c.y + dy * inner), Offset(c.x + dx * outer, c.y + dy * outer),
                    strokeWidth = (if (major) 1.5.dp else 1.dp).toPx(), cap = StrokeCap.Round,
                )
            }
            if (fill > 0f) rotate(-90f, c) {
                val dawn = androidx.compose.ui.graphics.Brush.sweepGradient(
                    0f to colors.glowCool, (fill * 0.5f) to colors.glowHot, (fill * 0.85f).coerceAtLeast(0.001f) to colors.glowTop,
                    fill.coerceAtLeast(0.002f) to colors.light, 1f to colors.light,
                    center = c,
                )
                drawArc(dawn, 0f, 360f * fill, false, Offset(c.x - r, c.y - r), Size(2 * r, 2 * r), style = Stroke(stroke, cap = StrokeCap.Round))
                // The round cap at the start reaches back past 0 degrees, where the sweep wraps to the light: keep it rose.
                drawCircle(colors.glowCool, stroke / 2f, Offset(c.x + r, c.y))
                val a = Math.toRadians(360.0 * fill)
                val head = Offset(c.x + r * cos(a).toFloat(), c.y + r * sin(a).toFloat())
                val swell = 1f + 0.3f * (breath?.value ?: 0f)
                drawCircle(colors.background, 8.dp.toPx() * swell, head)
                drawCircle(colors.light, 5.dp.toPx() * swell, head)
            }
        }
        androidx.compose.foundation.layout.Column(
            Modifier.fillMaxWidth(0.7f),
            horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
        ) {
            AuroraMark(18.dp, Modifier.padding(bottom = 14.dp), lit = true)
            label?.let { Eyebrow(it, textAlign = androidx.compose.ui.text.style.TextAlign.Center) }
            RollingClock(clock, colors.textPrimary, AuroraType.Hero, Modifier.fillMaxWidth().padding(vertical = 6.dp), spoken = spoken)
            caption?.let { Text(it, color = colors.textTertiary, style = AuroraType.Caption, textAlign = androidx.compose.ui.text.style.TextAlign.Center) }
        }
    }
}

private fun androidx.compose.ui.semantics.SemanticsPropertyReceiver.semanticsText(value: androidx.compose.ui.text.AnnotatedString) {
    text = value
}
