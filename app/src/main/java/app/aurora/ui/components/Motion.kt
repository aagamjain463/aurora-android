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
import androidx.compose.ui.draw.drawBehind
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
import kotlinx.coroutines.delay

/**
 * A live clock whose digits roll as they change: each new digit falls in from above while the old one
 * sinks away, so a countdown reads as time passing rather than text replacing. Read as one value by
 * screen readers. With reduced motion it is plain text.
 */
@Composable
fun RollingClock(text: String, color: Color, style: TextStyle, modifier: Modifier = Modifier, spoken: String = text) {
    val semantics = modifier.clearAndSetSemantics { contentDescription = spoken }
    BoxWithConstraints(contentAlignment = Alignment.Center) {
        // A clock this large is already large text, and a day-long one is wide: fit it to the room there is.
        val density = LocalDensity.current
        val em = with(density) { style.fontSize.toDp() }
        val fit = minOf(1f / density.fontScale.coerceAtLeast(1f), maxWidth / (em * text.length * DigitWidth))
        val sized = if (fit < 1f) style.copy(fontSize = style.fontSize * fit, lineHeight = style.lineHeight * fit) else style
        if (Aurora.reducedMotion) {
            Text(text, semantics, color = color, style = sized)
            return@BoxWithConstraints
        }
        Row(semantics) {
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

/** Average width of a clock's characters, in em, in the Hero cut. */
private const val DigitWidth = 0.5f

/**
 * A tappable card or row: it sinks a little under the finger and springs back, with the ripple kept
 * inside [shape]. Put it before the surface (`auroraSurface`, `clip`, `background`) so the whole card moves.
 */
fun Modifier.pressable(shape: Shape, role: Role = Role.Button, onClickLabel: String? = null, onClick: () -> Unit): Modifier = composed {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        if (pressed && !Aurora.reducedMotion) 0.96f else 1f,
        if (Aurora.reducedMotion) snap() else spring(dampingRatio = 0.55f, stiffness = 700f),
        label = "press",
    )
    graphicsLayer { scaleX = scale; scaleY = scale; this.shape = shape; clip = true }
        .clickable(interaction, ripple(), role = role, onClickLabel = onClickLabel, onClick = onClick)
}

/**
 * Every few seconds a knot of brighter light wanders along a primary action, as one does along the ribbon. Put it
 * right after the fill, so it passes behind the label. Still with reduced motion, and where the sky is the painted still.
 */
fun Modifier.ribbonKnot(): Modifier = composed {
    if (Aurora.reducedMotion || Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return@composed this
    val hot = Aurora.colors.glowHot
    val cycle = rememberInfiniteTransition(label = "knot").animateFloat(0f, 1f, infiniteRepeatable(tween(KnotMs, easing = LinearEasing)), label = "knot")
    drawBehind {
        val p = (cycle.value - KnotRests) / (1f - KnotRests)
        if (p <= 0f) return@drawBehind
        val x = size.width * (-0.3f + 1.6f * p)
        val reach = size.width * 0.28f
        drawRect(Brush.horizontalGradient(listOf(Color.Transparent, hot.copy(alpha = 0.78f), Color.Transparent), startX = x - reach, endX = x + reach))
    }
}

private const val KnotMs = 5500
private const val KnotRests = 0.45f

/**
 * A knot of light that travels the rim of a card every few seconds. For the one card a screen leads with: it asks
 * for a redraw every frame. Still with reduced motion, and where the sky is the painted still.
 */
fun Modifier.rimLight(shape: Shape): Modifier = composed {
    if (Aurora.reducedMotion || Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return@composed this
    val colors = Aurora.colors
    val cycle = rememberInfiniteTransition(label = "rim").animateFloat(0f, 1f, infiniteRepeatable(tween(RimMs, easing = LinearEasing)), label = "rim")
    drawWithContent {
        drawContent()
        val x = size.width * (-0.4f + 1.8f * cycle.value)
        val reach = size.width * 0.34f
        drawOutline(
            shape.createOutline(size, layoutDirection, this),
            Brush.linearGradient(listOf(Color.Transparent, colors.accent.copy(alpha = 0.95f), Color.Transparent), Offset(x - reach, 0f), Offset(x + reach, size.height)),
            style = Stroke(1.5.dp.toPx()),
        )
    }
}

private const val RimMs = 6500

/** Set when a tab is chosen: the screen it opens performs its opening again. Coming back from a pushed screen does not. */
object TabArrival { var fresh = false }

/** A screen's opening: how far it has run, 0 to 1, and whether the screen has settled ([rise] on anything newly shown). */
class Intro(val progress: State<Float>, val settled: State<Boolean>)

/** Only screens that run an opening provide it; elsewhere [rise] does nothing. */
val LocalIntro = staticCompositionLocalOf<Intro?> { null }

/** Runs a screen's opening, so blocks marked [rise] settle in from the top of the page down. */
@Composable
fun ProvideIntro(durationMs: Int = IntroMs, content: @Composable () -> Unit) {
    val reduced = Aurora.reducedMotion
    // Once, and again each time its tab is chosen. Coming back from a pushed screen finds it as you left it.
    var played by rememberSaveable { mutableStateOf(false) }
    val again = remember { TabArrival.fresh.also { TabArrival.fresh = false } }
    val still = reduced || (played && !again)
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
 * Once the screen has settled, a block newly shown (scrolled to, or loaded late) rises in by itself, from the edge
 * it came in by.
 */
fun Modifier.rise(): Modifier = composed {
    val intro = LocalIntro.current
    if (intro == null || Aurora.reducedMotion) return@composed this
    var y by remember { mutableFloatStateOf(Float.NaN) }
    val distance = with(LocalDensity.current) { RiseDp.dp.toPx() }
    val late = remember { intro.settled.value }
    val reveal = remember { Animatable(if (late) 0f else 1f) }
    LaunchedEffect(Unit) { if (late) reveal.animateTo(1f, tween(Motion.Gentle, easing = Motion.Ease)) }
    val screen = LocalWindowInfo.current.containerSize.height.toFloat().coerceAtLeast(1f)
    onPlaced { if (y.isNaN()) y = it.positionInRoot().y }.graphicsLayer {
        val delay = if (y.isNaN()) 0f else (y / screen).coerceIn(0f, 1f) * 0.4f
        val p = ((intro.progress.value - delay) / 0.6f).coerceIn(0f, 1f)
        val t = Motion.Ease.transform(p)
        val r = reveal.value
        // From above when it came in at the top of the page, scrolling back up.
        val side = if (late && y < screen / 3f) -0.6f else 1f
        alpha = Motion.Ease.transform((p * 1.6f).coerceAtMost(1f)) * r
        translationY = (1f - t * r) * distance * side
        val s = 1f - RiseShrink * (1f - t * r)
        scaleX = s
        scaleY = s
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
fun CountUpText(text: String, color: Color, style: TextStyle, modifier: Modifier = Modifier) {
    val reduced = Aurora.reducedMotion
    var done by rememberSaveable { mutableStateOf(false) }
    val p = remember { Animatable(if (reduced || done) 1f else 0f) }
    LaunchedEffect(Unit) {
        if (reduced || done) return@LaunchedEffect
        Opening.awaitParted()
        p.animateTo(1f, tween(CountMs, easing = Motion.Ease))
        done = true
    }
    Text(countedText(text, p.value), modifier.clearAndSetSemantics { contentDescription = text }, color = color, style = style)
}

/** [text] with every run of digits at [progress] of its value. */
internal fun countedText(text: String, progress: Float): String =
    if (progress >= 1f) text
    else Regex("\\d+").replace(text) { m -> m.value.toLongOrNull()?.let { (it * progress).toLong().toString() } ?: m.value }

private const val CountMs = 800
internal const val QuickIntroMs = 700
private const val IntroMs = 1000
private const val SettleMs = 300L
private const val RiseDp = 44
private const val RiseShrink = 0.03f
