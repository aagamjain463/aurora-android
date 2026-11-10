package app.aurora.ui.components

import android.os.Build
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import app.aurora.ui.theme.Aurora
import app.aurora.ui.theme.Motion
import app.aurora.ui.theme.clipShape
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.ui.node.currentValueOf

/**
 * A live clock whose digits roll as they change: each new digit falls in from above while the old one
 * sinks away, so a countdown reads as time passing rather than text replacing. Read as one value by
 * screen readers. With reduced motion it is plain text.
 */
@Composable
fun RollingClock(text: String, color: Color, style: TextStyle, modifier: Modifier = Modifier, spoken: String = text) {
    // The caller's size limits go on the box that measures, so the fit below sees them.
    BoxWithConstraints(modifier.clearAndSetSemantics { contentDescription = spoken }, contentAlignment = Alignment.Center) {
        // A clock this large is already large text, and a day-long one is wide: fit it to the room there is.
        val density = LocalDensity.current
        val em = with(density) { style.fontSize.toDp() }
        val fit = minOf(1f / density.fontScale.coerceAtLeast(1f), maxWidth / (em * text.length * DigitWidth))
        val sized = if (fit < 1f) style.copy(fontSize = style.fontSize * fit, lineHeight = style.lineHeight * fit) else style
        if (Aurora.reducedMotion) {
            Text(text, color = color, style = sized)
            return@BoxWithConstraints
        }
        // Each character is its own Text, so the style's tracking has to be put back between them.
        val tracking = with(density) { (sized.fontSize * sized.letterSpacing.value).toDp() }
        Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(tracking)) {
            text.forEach { c ->
                AnimatedContent(
                    c,
                    transitionSpec = {
                        (slideInVertically(tween(Motion.Gentle, easing = Motion.Ease)) { -it / 2 } + fadeIn(tween(Motion.Standard)))
                            .togetherWith(slideOutVertically(tween(Motion.Gentle, easing = Motion.Ease)) { it / 2 } + fadeOut(tween(Motion.Quick)))
                            .using(SizeTransform(clip = true))
                    },
                    label = "digit",
                ) { Text(it.toString(), color = color, style = sized) }
            }
        }
    }
}

/** Average width of a clock's characters, in em, in the Hero cut (Inter tabular digits, colons included). */
private const val DigitWidth = 0.58f

/**
 * A tappable card or row: it sinks a little under the finger and springs back, with the ripple kept
 * inside [shape]. Put it before the surface (`auroraSurface`, `clip`, `background`) so the whole card moves.
 */
// Plain Modifier chain, no composed{}: a list item that scrolls in costs nothing until it is pressed (the
// indication's interaction source is created lazily), and the sink is drawn, not laid out.
fun Modifier.pressable(shape: Shape, role: Role = Role.Button, onClickLabel: String? = null, onClick: () -> Unit): Modifier =
    clip(shape.clipShape).clickable(null, PressHighlight, role = role, onClickLabel = onClickLabel, onClick = onClick)

/**
 * How anything pressable answers the finger, as on iOS: a soft wash of ink appears the instant it is pressed and
 * fades on release. Drawn beneath the content, so labels stay crisp. [color] defaults to the theme's ink.
 */
class HighlightIndication(private val color: Color = Color.Unspecified, private val sink: Boolean = false) : androidx.compose.foundation.IndicationNodeFactory {
    override fun create(interactionSource: androidx.compose.foundation.interaction.InteractionSource): androidx.compose.ui.node.DelegatableNode =
        HighlightNode(interactionSource, color, sink)

    override fun equals(other: Any?) = other is HighlightIndication && other.color == color && other.sink == sink
    override fun hashCode() = color.hashCode() * 31 + sink.hashCode()
}

/** The highlight in the theme's ink: Aurora's default indication. */
val Highlight = HighlightIndication()

/** [Highlight], and the surface sinks a little under the finger and springs back ([pressable]). */
private val PressHighlight = HighlightIndication(sink = true)

private const val PressScale = 0.96f

private const val HighlightAlpha = 0.10f

private class HighlightNode(
    private val source: androidx.compose.foundation.interaction.InteractionSource,
    private val color: Color,
    private val sink: Boolean,
) : Modifier.Node(), androidx.compose.ui.node.DrawModifierNode, androidx.compose.ui.node.CompositionLocalConsumerModifierNode {
    private val level = Animatable(0f)
    private val scale = Animatable(1f)

    override fun onAttach() {
        coroutineScope.launch {
            var presses = 0
            source.interactions.collect { i ->
                when (i) {
                    is androidx.compose.foundation.interaction.PressInteraction.Press -> presses++
                    is androidx.compose.foundation.interaction.PressInteraction.Release,
                    is androidx.compose.foundation.interaction.PressInteraction.Cancel -> presses = (presses - 1).coerceAtLeast(0)
                    else -> return@collect
                }
                val reduced = currentValueOf(app.aurora.ui.theme.LocalReducedMotion)
                if (sink && !reduced) launch { scale.animateTo(if (presses > 0) PressScale else 1f, spring(dampingRatio = 0.9f, stiffness = 700f)) }
                if (presses > 0) level.snapTo(1f)
                else launch { level.animateTo(0f, if (reduced) snap() else tween(Motion.Standard, easing = Motion.Ease)) }
            }
        }
    }

    override fun androidx.compose.ui.graphics.drawscope.ContentDrawScope.draw() {
        val v = level.value
        val s = scale.value
        scale(s) {
            if (v > 0f) {
                val ink = if (color != Color.Unspecified) color else currentValueOf(app.aurora.ui.theme.LocalAuroraColors).textPrimary
                drawRect(ink, alpha = HighlightAlpha * v)
            }
            this@draw.drawContent()
        }
    }
}

/** A screen's opening: how far it has run, 0 to 1, and whether the screen has settled ([rise] on anything newly shown). */
class Intro(val progress: State<Float>, val settled: State<Boolean>)

/** Only screens that run an opening provide it; elsewhere [rise] does nothing. */
val LocalIntro = staticCompositionLocalOf<Intro?> { null }

/** Runs a screen's opening, so blocks marked [rise] settle in from the top of the page down. */
@Composable
fun ProvideIntro(durationMs: Int = IntroMs, content: @Composable () -> Unit) {
    val reduced = Aurora.reducedMotion
    // Once per screen. Choosing its tab again, or coming back from a pushed screen, finds it as you left it.
    var played by rememberSaveable { mutableStateOf(false) }
    val still = reduced || played
    val intro = remember { Animatable(if (still) 1f else 0f) }
    val settled = remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (still) {
            // Whatever composes with the screen is simply there; only what appears after it rises.
            delay(SettleMs)
        } else {
            // The first screen waits for the night to part, so its opening is seen.
            Opening.awaitParted()
            intro.animateTo(1f, tween(durationMs, easing = LinearEasing))
            played = true
        }
        settled.value = true
    }
    val state = remember { Intro(intro.asState(), settled) }
    CompositionLocalProvider(LocalIntro provides state, content = content)
}

/**
 * During the opening the block fades up as it climbs into place, a beat later the further down the page it sits.
 * A block shown after the opening (scrolled to, or loaded late) is simply there: lists scroll like lists.
 */
fun Modifier.rise(): Modifier = composed {
    val intro = LocalIntro.current
    // Composed once the opening has run (scrolled to, loaded late): simply there. Scrolling never animates.
    val late = remember(intro) { intro == null || intro.settled.value || intro.progress.value >= 1f }
    if (late || Aurora.reducedMotion) return@composed this
    var y by remember { mutableFloatStateOf(Float.NaN) }
    val distance = with(LocalDensity.current) { RiseDp.dp.toPx() }
    val screen = LocalWindowInfo.current.containerSize.height.toFloat().coerceAtLeast(1f)
    onPlaced { if (y.isNaN()) y = it.positionInRoot().y }.graphicsLayer {
        val delay = if (y.isNaN()) 0f else (y / screen).coerceIn(0f, 1f) * 0.4f
        val p = ((intro!!.progress.value - delay) / 0.6f).coerceIn(0f, 1f)
        val t = Motion.Ease.transform(p)
        alpha = Motion.Ease.transform((p * 1.6f).coerceAtMost(1f))
        translationY = (1f - t) * distance
        val s = 1f - RiseShrink * (1f - t)
        scaleX = s
        scaleY = s
        // Fade each draw, not an offscreen copy of the block: no extra render target per block on the GPU.
        compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.ModulateAlpha
    }
}

/**
 * A fill that sweeps up from nothing the first time it is shown, then follows [target] with [follow]
 * (a progress bar settling, a running clock gliding). With reduced motion it simply is the value.
 */
@Composable
fun rememberFill(target: Float, follow: AnimationSpec<Float> = tween(Motion.Gentle, easing = Motion.Ease)): State<Float> {
    val reduced = Aurora.reducedMotion
    val fill = remember { Animatable(if (reduced) target else 0f) }
    var first by remember { mutableStateOf(!reduced) }
    LaunchedEffect(target, reduced) {
        when {
            reduced -> fill.snapTo(target)
            first -> { first = false; Opening.awaitParted(); fill.animateTo(target, tween(Motion.Reveal + 300, easing = Motion.Ease)) }
            else -> fill.animateTo(target, follow)
        }
    }
    return fill.asState()
}

/**
 * A number that counts up from zero the first time it is shown, then is simply its value. Any text works: each
 * run of digits counts ("1h 20m"). Read as the final value by screen readers. With reduced motion it is plain text.
 */
@Composable
fun CountUpText(text: String, color: Color, style: TextStyle, modifier: Modifier = Modifier, autoSize: androidx.compose.foundation.text.TextAutoSize? = null) {
    val reduced = Aurora.reducedMotion
    var done by rememberSaveable { mutableStateOf(false) }
    val p = remember { Animatable(if (reduced || done) 1f else 0f) }
    LaunchedEffect(Unit) {
        if (reduced || done) return@LaunchedEffect
        Opening.awaitParted()
        p.animateTo(1f, tween(CountMs, easing = Motion.Ease))
        done = true
    }
    Text(countedText(text, p.value), modifier.clearAndSetSemantics { contentDescription = text }, color = color, style = style, maxLines = if (autoSize != null) 1 else Int.MAX_VALUE, autoSize = autoSize)
}

/** [text] with every run of digits at [progress] of its value. */
internal fun countedText(text: String, progress: Float): String =
    if (progress >= 1f) text
    else Regex("\\d+").replace(text) { m -> m.value.toLongOrNull()?.let { (it * progress).toLong().toString() } ?: m.value }

private const val CountMs = 800
internal const val QuickIntroMs = 420
private const val IntroMs = 640
private const val SettleMs = 0L
private const val RiseDp = 44
private const val RiseShrink = 0.03f
