package app.aurora.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.geometry.Size
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.StrokeCap
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

/**
 * What a screen shows while its data loads: the logo on the page. It fades in only after a moment, so a fast load
 * never flashes it.
 */
@Composable
fun LoadingSurface(modifier: Modifier = Modifier) {
    val shown = remember { Animatable(0f) }
    // Under the opening it is never seen: the opening holds its mark until this goes. Its own logo stays off for good,
    // or it would flash small as the opening fades.
    val underOpening = remember { Opening.playing }
    androidx.compose.runtime.DisposableEffect(Unit) {
        Opening.loading++
        onDispose { Opening.loading-- }
    }
    LaunchedEffect(Unit) {
        if (underOpening) return@LaunchedEffect
        kotlinx.coroutines.delay(300)
        shown.animateTo(1f, tween(Motion.Gentle))
    }
    Box(modifier.fillMaxSize().background(Aurora.colors.background), contentAlignment = Alignment.Center) {
        if (!underOpening) AuroraMark(64.dp, Modifier.graphicsLayer { alpha = shown.value })
    }
}

/**
 * The name in wide-tracked capitals, as on the brand board: set like a maison's signature. Inter, in the page's ink.
 * Medium weight at 0.28em reads as one word; heavier or wider and the letters fall apart into six marks.
 */
@Composable
fun Wordmark(modifier: Modifier = Modifier, size: androidx.compose.ui.unit.TextUnit = 17.sp) {
    Text(
        stringResource(R.string.app_name).uppercase(),
        modifier,
        color = Aurora.colors.textPrimary,
        style = AuroraType.Title.copy(fontSize = size, lineHeight = size * 1.25f, letterSpacing = 0.28.em, fontWeight = androidx.compose.ui.text.font.FontWeight.Medium),
        maxLines = 1,
    )
}

/** The signature lockup: mark, a hairline rule, then the wordmark. Same everywhere the name is signed. */
@Composable
fun Lockup(modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        AuroraMark(LockupMark)
        Spacer(Modifier.width(16.dp))
        Box(Modifier.width(1.dp).height(LockupMark * 0.62f).background(Aurora.colors.hairline))
        // 2dp less than the left gap: the A's open sidebearing makes up the rest, so both gaps look equal.
        Spacer(Modifier.width(14.dp))
        Wordmark()
    }
}

/**
 * The approved logo (brand/Aurora_Final_Approved_Logo.png), [size] square, exactly as supplied: only its black canvas
 * is lifted off so it sits on either theme. Never tinted, redrawn or recoloured. Decorative: the name or the screen
 * says what it is.
 */
@Composable
fun AuroraMark(size: Dp, modifier: Modifier = Modifier, rim: Boolean = true) {
    // The tile's own edge is lit from above and fades below. A fine even rim, white only, on the tile's outline makes
    // it read the same on every side and at every size (founder, 2026-11-08). The logo's pixels are never changed.
    val edge = if (rim) Modifier.border(
        (size * 0.014f).coerceAtLeast(1.dp),
        androidx.compose.ui.graphics.Color.White.copy(alpha = if (Aurora.colors.isDark) 0.34f else 0.26f),
        androidx.compose.foundation.shape.RoundedCornerShape(percent = 22),
    ) else Modifier
    Image(
        androidx.compose.ui.graphics.ImageBitmap.imageResource(R.drawable.aurora_logo), null,
        modifier.size(size).then(edge).clearAndSetSemantics { },
        filterQuality = FilterQuality.High,
    )
}

/** The logo beside the wordmark: sized in dp, so it grows with text size to keep the lockup balanced. */
val LockupMark: Dp
    @Composable get() = 34.dp * LocalDensity.current.fontScale.coerceIn(1f, 1.6f)

/**
 * The top of every tab screen: the logo at the left; Settings at the right, a bare icon.
 */
@Composable
fun AuroraHeader(onOpenSettings: (() -> Unit)?, modifier: Modifier = Modifier, title: String? = null) {
    val colors = Aurora.colors
    Row(
        modifier.fillMaxWidth().heightIn(min = 72.dp).padding(horizontal = Space.Gutter).padding(top = Space.XS),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).semantics(mergeDescendants = true) { }) {
            // The logo alone (founder, 2026-11-08): the name is not repeated at the top of the tabs.
            AuroraMark(LockupMark)
            if (title != null) Text(title, Modifier.padding(top = Space.XXS), color = colors.textSecondary, style = AuroraType.Caption)
        }
        if (onOpenSettings != null) HeaderButton(AuroraIcons.Settings, stringResource(R.string.settings_title), onOpenSettings)
    }
}

@Composable
private fun HeaderButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    val colors = Aurora.colors
    Box(Modifier.size(48.dp).padding(2.dp).glassControl(CircleShape).clickable(role = Role.Button, onClickLabel = label, onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(icon, label, tint = colors.textSecondary, modifier = Modifier.size(20.dp))
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
    /** Shrinks the type so the lead keeps to one line and the tail to its own: never a stray word on a line alone. */
    autoSize: androidx.compose.foundation.text.TextAutoSize? = null,
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
    Text(
        annotated, modifier.clearAndSetSemantics { heading(); spokenText(spoken) }, style = style, textAlign = textAlign,
        maxLines = if (autoSize != null && tail.split(" ").size <= 3) 2 else Int.MAX_VALUE, autoSize = autoSize,
    )
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
 * The Focus timer, shared by both Focus screens. Round, but not a clock face: one hairline ring, no ticks or numerals.
 * The time held is drawn over it in solid ink, clockwise from the top, ending in a round head; the time itself sits
 * large in the middle. The [clock]'s last field (the seconds) sits small and quiet beside the rest, so the eye reads
 * minutes; a [unit] replaces it when the number is a length rather than a countdown. No gradient, glow or pulse.
 * [progress] is 0..1 of the time held; [follow] is how the ring catches up with it (a 1s linear glide for a ticking
 * clock, quicker under a finger).
 */
@Composable
fun FocusTimer(
    progress: Float,
    clock: String,
    modifier: Modifier = Modifier,
    unit: String? = null,
    caption: String? = null,
    spoken: String = clock,
    follow: AnimationSpec<Float> = tween(1000, easing = LinearEasing),
) {
    val colors = Aurora.colors
    val split = clock.lastIndexOf(':')
    val lead = if (unit == null && split > 0) clock.substring(0, split) else clock
    val tail = unit ?: if (split > 0) clock.substring(split + 1) else null
    Box(modifier.widthIn(max = 320.dp).fillMaxWidth().aspectRatio(1f), contentAlignment = Alignment.Center) {
        // The same gold mercury ring a running session wears.
        app.aurora.ui.session.TimerRing(progress.coerceIn(0f, 1f), Modifier.fillMaxSize().clearAndSetSemantics { }, stroke = 3.5.dp, follow = follow, white = true)
        Column(Modifier.fillMaxWidth(0.74f), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(Modifier.clearAndSetSemantics { contentDescription = spoken }, verticalAlignment = Alignment.Bottom) {
                RollingClock(lead, colors.textPrimary, AuroraType.Timer.copy(fontSize = 76.sp, lineHeight = 80.sp), Modifier.weight(1f, fill = false).alignByBaseline())
                tail?.let {
                    // Seconds roll too; a unit is a word and simply sits there.
                    val style = AuroraType.TimerTail.copy(fontSize = 18.sp, lineHeight = 22.sp)
                    if (unit == null) RollingClock(it, colors.textTertiary, style, Modifier.padding(start = 4.dp).alignByBaseline())
                    else Text(it, Modifier.padding(start = 6.dp).alignByBaseline(), color = colors.textTertiary, style = style, maxLines = 1)
                }
            }
            caption?.let { Text(it, Modifier.padding(top = Space.XS), color = colors.textSecondary, style = AuroraType.Caption, textAlign = TextAlign.Center) }
        }
    }
}
