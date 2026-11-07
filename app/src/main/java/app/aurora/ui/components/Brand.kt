package app.aurora.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import app.aurora.R
import app.aurora.ui.theme.Aurora
import app.aurora.ui.theme.AuroraType
import app.aurora.ui.theme.Motion
import app.aurora.ui.theme.Space
import kotlin.math.cos
import kotlin.math.sin

/**
 * What a screen shows while its data loads: the logo on the page. It fades in only after a moment, so a fast load
 * never flashes it.
 */
@Composable
fun LoadingSurface(modifier: Modifier = Modifier) {
    val shown = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(300)
        shown.animateTo(1f, tween(Motion.Gentle))
    }
    Box(modifier.fillMaxSize().background(Aurora.colors.background), contentAlignment = Alignment.Center) {
        AuroraMark(64.dp, Modifier.graphicsLayer { alpha = shown.value })
    }
}

/** The stacked lockup: the logo over the wordmark. */
@Composable
fun AuroraLogo(width: Dp, modifier: Modifier = Modifier) {
    val name = stringResource(R.string.app_name)
    Column(modifier.width(width).clearAndSetSemantics { contentDescription = name }, horizontalAlignment = Alignment.CenterHorizontally) {
        AuroraMark(width * 0.62f)
        Spacer(Modifier.height(width * 0.14f))
        Wordmark(size = (width.value * 0.17f).sp)
    }
}

/** The name in tracked capitals, as on the brand board. Inter, in the page's ink. */
@Composable
fun Wordmark(modifier: Modifier = Modifier, size: androidx.compose.ui.unit.TextUnit = 15.sp) {
    Text(
        stringResource(R.string.app_name).uppercase(),
        modifier,
        color = Aurora.colors.textPrimary,
        style = AuroraType.Title.copy(fontSize = size, lineHeight = size * 1.25f, letterSpacing = 0.22.em, fontWeight = androidx.compose.ui.text.font.FontWeight(500)),
        maxLines = 1,
    )
}

/**
 * The approved logo (brand/Aurora_Final_Approved_Logo.png), [size] square, exactly as supplied: only its black canvas
 * is lifted off so it sits on either theme. Never tinted, redrawn or recoloured. Decorative: the name or the screen
 * says what it is.
 */
@Composable
fun AuroraMark(size: Dp, modifier: Modifier = Modifier) {
    Image(
        androidx.compose.ui.graphics.ImageBitmap.imageResource(R.drawable.aurora_logo), null,
        modifier.size(size).clearAndSetSemantics { },
        filterQuality = FilterQuality.High,
    )
}

/** The logo beside the wordmark: sized in dp, so it grows with text size to keep the lockup balanced. */
val LockupMark: Dp
    @Composable get() = 30.dp * LocalDensity.current.fontScale.coerceIn(1f, 1.6f)

/**
 * The top of every tab screen: the logo and wordmark at the left; info and Settings at the right, as 44dp round
 * buttons on the raised surface.
 */
@Composable
fun AuroraHeader(onOpenSettings: (() -> Unit)?, modifier: Modifier = Modifier, title: String? = null, onInfo: (() -> Unit)? = null) {
    val colors = Aurora.colors
    Row(
        modifier.fillMaxWidth().heightIn(min = 60.dp).padding(horizontal = Space.Gutter),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AuroraMark(LockupMark)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f).semantics(mergeDescendants = true) { }) {
            Wordmark()
            if (title != null) Text(title, color = colors.textSecondary, style = AuroraType.Caption)
        }
        if (onInfo != null) {
            HeaderButton(AuroraIcons.Info, stringResource(R.string.guide_open), onInfo)
            if (onOpenSettings != null) Spacer(Modifier.width(Space.XS))
        }
        if (onOpenSettings != null) HeaderButton(AuroraIcons.Settings, stringResource(R.string.settings_title), onOpenSettings)
    }
}

@Composable
private fun HeaderButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    val colors = Aurora.colors
    // 44dp visible, 48dp touch.
    Box(Modifier.size(48.dp).clip(CircleShape).clickable(role = Role.Button, onClickLabel = label, onClick = onClick), contentAlignment = Alignment.Center) {
        Box(Modifier.size(44.dp).clip(CircleShape).background(colors.surface), contentAlignment = Alignment.Center) {
            Icon(icon, label, tint = colors.textPrimary, modifier = Modifier.size(20.dp))
        }
    }
}

/** A section label. */
@Composable
fun BrandEyebrow(text: String, modifier: Modifier = Modifier) {
    Eyebrow(text, modifier, color = Aurora.colors.textSecondary)
}

/** The hero headline ("Take back control."): one voice, set large, left to wrap as a headline would. */
@Composable
fun HeroHeadline(text: String, modifier: Modifier = Modifier, style: TextStyle = AuroraType.HeroBold) {
    Text(text, modifier.semantics { heading() }, color = Aurora.colors.textPrimary, style = style.copy(lineBreak = LineBreak.Heading))
}

/**
 * A headline in two tones: the lead in Silver, the last [tailWords] words in full ink on their own line. The words are
 * never changed (approved copy stays exact); only the line break and tone are design.
 */
@Composable
fun TwoToneHeadline(
    text: String,
    modifier: Modifier = Modifier,
    tailWords: Int = 2,
    style: TextStyle = AuroraType.Display,
    leadFirst: Boolean = true,
    textAlign: TextAlign? = null,
) {
    val colors = Aurora.colors
    val words = text.split(" ")
    val spoken = AnnotatedString(text)
    val split = (words.size - tailWords).coerceIn(0, words.size)
    val lead = words.take(split).joinToString(" ")
    val tail = words.drop(split).joinToString(" ")
    val annotated = buildAnnotatedString {
        val leadColor = if (leadFirst) colors.headlineLead else colors.textPrimary
        val tailColor = if (leadFirst) colors.textPrimary else colors.headlineLead
        if (lead.isNotEmpty()) {
            pushStyle(SpanStyle(color = leadColor)); append(lead); pop()
            append(if (leadFirst) "\n" else " ")
        }
        pushStyle(SpanStyle(color = tailColor)); append(tail); pop()
    }
    // Screen readers and tests get the exact copy, without the designed line break.
    Text(annotated, modifier.clearAndSetSemantics { heading(); spokenText(spoken) }, style = style, textAlign = textAlign)
}

private fun androidx.compose.ui.semantics.SemanticsPropertyReceiver.spokenText(value: AnnotatedString) {
    text = value
}

/** A running Focus's goal, in the person's own words, above the timer. */
@Composable
fun FocusGoal(goal: String, modifier: Modifier = Modifier) {
    Text(
        goal, modifier.fillMaxWidth().semantics { heading() },
        color = Aurora.colors.textPrimary, textAlign = TextAlign.Center,
        maxLines = 3, overflow = TextOverflow.Ellipsis,
        style = AuroraType.DisplayS,
    )
}

/**
 * The Focus dial, shared by both Focus timers: a precise instrument. A hairline track with a bezel of minute marks;
 * the time held is a solid ink arc with a round head, and the marks it has passed darken to ink. No gradient, glow or
 * pulse. Inside: an optional [label], the tabular [clock] and one quiet [caption]. [progress] is 0..1 of the time held;
 * [follow] is how the arc catches up with it (a 1s linear glide for a ticking clock, quicker under a finger).
 */
@Composable
fun FocusDial(
    progress: Float,
    clock: String,
    modifier: Modifier = Modifier,
    label: String? = null,
    caption: String? = null,
    spoken: String = clock,
    @Suppress("UNUSED_PARAMETER") live: Boolean = false,
    follow: AnimationSpec<Float> = tween(1000, easing = LinearEasing),
) {
    val colors = Aurora.colors
    val fill by rememberFill(progress.coerceIn(0f, 1f), follow)
    Box(modifier.widthIn(max = 340.dp).aspectRatio(1f), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize().clearAndSetSemantics { }) {
            val c = center
            val stroke = 3.dp.toPx()
            val r = size.minDimension / 2f - 10.dp.toPx()
            drawCircle(colors.hairlineStrong, r, c, style = Stroke(1.dp.toPx()))
            // The bezel: sixty marks, every fifth longer. Those the arc has passed are inked.
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
                        passed -> colors.textPrimary.copy(alpha = if (major) 0.9f else 0.5f)
                        major -> colors.textTertiary.copy(alpha = 0.6f)
                        else -> colors.hairlineStrong
                    },
                    Offset(c.x + dx * inner, c.y + dy * inner), Offset(c.x + dx * outer, c.y + dy * outer),
                    strokeWidth = (if (major) 1.5.dp else 1.dp).toPx(), cap = StrokeCap.Round,
                )
            }
            if (fill > 0f) rotate(-90f, c) {
                drawArc(colors.textPrimary, 0f, 360f * fill, false, Offset(c.x - r, c.y - r), Size(2 * r, 2 * r), style = Stroke(stroke, cap = StrokeCap.Round))
                val a = Math.toRadians(360.0 * fill)
                val head = Offset(c.x + r * cos(a).toFloat(), c.y + r * sin(a).toFloat())
                drawCircle(colors.background, 8.dp.toPx(), head)
                drawCircle(colors.textPrimary, 5.dp.toPx(), head)
            }
        }
        Column(Modifier.fillMaxWidth(0.7f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            label?.let { Eyebrow(it, textAlign = TextAlign.Center) }
            RollingClock(clock, colors.textPrimary, AuroraType.Hero, Modifier.fillMaxWidth().padding(vertical = 6.dp), spoken = spoken)
            caption?.let { Text(it, color = colors.textSecondary, style = AuroraType.Caption, textAlign = TextAlign.Center) }
        }
    }
}
