package app.aurora.ui.components

import android.graphics.RuntimeShader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.background
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.aurora.R
import app.aurora.ui.theme.Aurora
import app.aurora.ui.theme.AuroraType
import app.aurora.ui.theme.DarkColors
import app.aurora.ui.theme.Motion
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/** Whether the opening is on screen. Anything that performs on first show waits for it to part. */
object Opening {
    var playing by mutableStateOf(false)
        private set
    var covering by mutableStateOf(false)
        private set
    /** The night is opaque and animating: skip drawing the page under it, so all the GPU goes to the light. */
    var pageHidden by mutableStateOf(false)
        private set
    /** Returning to a running app: the system shows the last page first, so the night fades in over it. */
    var fadesIn = false
        private set

    /** The opening's curtains are going: the page's own sky can take over without both costing the GPU at once. */
    var leaving by mutableStateOf(false)
        private set

    /** The system splash has faded, so the opening can start with nothing covering it. */
    var splashGone by mutableStateOf(false)
        private set
    fun splashLeft() { splashGone = true }

    fun play(fadeIn: Boolean = false) { playing = true; covering = true; pageHidden = false; leaving = false; fadesIn = fadeIn }
    fun hidePage() { pageHidden = true }
    fun part() { covering = false; pageHidden = false }
    fun leave() { leaving = true }
    fun finish() { covering = false; pageHidden = false; leaving = false; playing = false }
    suspend fun awaitParted() { snapshotFlow { covering }.first { !it } }
    suspend fun awaitSkyTurn() { snapshotFlow { !playing || leaving }.first { it } }
}

/**
 * One frame of the opening: [p] moves toward [target] at a pace of [ms] for the whole way. [dt] is capped, so a
 * stalled frame slows the opening for a moment instead of making it jump.
 */
internal fun stepOpening(p: Float, target: Float, dt: Float, ms: Int): Float {
    val step = dt.coerceAtMost(MaxStep) * 1000f / ms
    return if (p < target) minOf(p + step, target) else maxOf(p - step, target)
}

/**
 * The opening, always at night: a point of light catches on the dark and races along the ribbon's path, rays rise
 * from it, and then the ribbon climbs off the top of the screen, lifting the night away from the app behind.
 * A tap hurries it. On a first run ([interactive]) it holds once the ribbon is lit, and the person swipes up to
 * lift it themselves. With reduced motion there is no opening.
 */
@Composable
fun AuroraUnveil(interactive: Boolean, modifier: Modifier = Modifier) {
    if (Aurora.reducedMotion) {
        LaunchedEffect(Unit) { Opening.finish() }
        return
    }
    val haptics = LocalHapticFeedback.current
    var p by remember { mutableFloatStateOf(0f) }
    var time by remember { mutableFloatStateOf(0f) }
    var target by remember { mutableFloatStateOf(if (interactive) HoldAt else 1f) }
    var pace by remember { mutableIntStateOf(PlayMs) }
    var holding by remember { mutableStateOf(false) }
    val complete: () -> Unit = {
        holding = false
        target = 1f
        pace = HurryMs
    }
    val fadesIn = remember { Opening.fadesIn }
    val veil = remember { Animatable(if (fadesIn) 0f else 1f) }
    LaunchedEffect(Unit) { veil.animateTo(1f, tween(FadeInMs, easing = Motion.Ease)) }
    LaunchedEffect(Unit) {
        // The logo gets its moment first. The timeout covers starts that show no splash, where nothing would call back.
        if (!fadesIn && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            withTimeoutOrNull(SplashWaitMs) { snapshotFlow { Opening.splashGone }.first { it } }
        }
        // The page behind composes and uploads its art first, so nothing competes with the light once it moves.
        val first = withFrameNanos { it }
        val least = (if (fadesIn) FadeInMs + 40 else 0) * 1_000_000L
        var last = first
        var calm = 0
        while ((calm < CalmFrames || last - first < least) && last - first < (if (fadesIn) SettleMaxNs else AfterSplashMaxNs)) {
            val now = withFrameNanos { it }
            calm = if (now - last < CalmFrameNs) calm + 1 else 0
            last = now
        }
        Opening.hidePage()
        var parted = false
        while (p < 1f) {
            withFrameNanos { now ->
                val dt = (now - last) / 1e9f
                last = now
                time += dt.coerceAtMost(MaxStep)
                p = stepOpening(p, target, dt, pace)
            }
            if (!parted && p >= PartAt) {
                parted = true
                Opening.part()
                haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
            }
            if (p >= LeaveAt) Opening.leave()
            if (interactive && target == HoldAt && p == HoldAt) holding = true
        }
        Opening.finish()
    }
    val spoken = stringResource(R.string.opening_hint)
    Box(
        modifier.fillMaxSize()
            .graphicsLayer { alpha = veil.value }
            .pointerInput(Unit) { detectTapGestures { complete() } }
            .pointerInput(interactive) {
                if (!interactive) return@pointerInput
                val height = size.height.toFloat()
                detectVerticalDragGestures(
                    onDragEnd = {
                        if (target > HoldAt + 0.08f) complete() else { target = HoldAt; pace = PlayMs }
                    },
                    onVerticalDrag = { change, dy ->
                        change.consume()
                        holding = false
                        pace = DragMs
                        target = (target - dy / (height * 0.8f)).coerceIn(HoldAt, 1f)
                    },
                )
            }
            .clearAndSetSemantics {
                if (interactive) { contentDescription = spoken; onClick { complete(); true } }
            },
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) UnveilCanvas({ p }, { time })
        else Box(Modifier.fillMaxSize().graphicsLayer { alpha = 1f - ((p - PartAt) / (1f - PartAt)).coerceIn(0f, 1f) }.background(DarkColors.sky))
        // The logo and name sit over the ribbon as it catches, and leaves as the night lifts.
        val lockup = Modifier.graphicsLayer {
            val v = p
            alpha = ((v - 0.06f) / 0.14f).coerceIn(0f, 1f) * (1f - ((v - PartAt) / 0.1f).coerceIn(0f, 1f))
            translationY = (1f - Motion.Ease.transform((v / 0.3f).coerceIn(0f, 1f))) * 12.dp.toPx() - ((v - PartAt).coerceAtLeast(0f)) * 240.dp.toPx()
        }
        AuroraLogo(280.dp, Modifier.align(Alignment.Center).offset(y = (-88).dp).then(lockup))
        if (interactive) {
            val hint = remember { Animatable(0f) }
            LaunchedEffect(holding) { hint.animateTo(if (holding) 1f else 0f, tween(if (holding) Motion.Gentle else Motion.Quick)) }
            Text(
                spoken,
                Modifier.align(Alignment.Center).offset(y = 148.dp).fillMaxWidth().graphicsLayer { alpha = hint.value },
                color = DarkColors.textSecondary,
                style = AuroraType.Body,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
@Composable
private fun UnveilCanvas(progress: () -> Float, time: () -> Float) {
    val shader = remember { RuntimeShader(UnveilSource) }
    val brush = remember(shader) { ShaderBrush(shader) }
    Canvas(Modifier.softLight()) {
        shader.setFloatUniform("size", size.width, size.height)
        shader.setFloatUniform("time", time())
        shader.setFloatUniform("p", progress())
        shader.uniformColor("veil", DarkColors.sky)
        shader.setRibbonColors(DarkColors)
        drawRect(brush)
    }
}

private const val PlayMs = 5200
private const val HurryMs = 1100
private const val DragMs = 500
private const val FadeInMs = 420
private const val SplashWaitMs = 2400L
private const val MaxStep = 1f / 30f
private const val CalmFrames = 4
private const val CalmFrameNs = 24_000_000L
private const val SettleMaxNs = 1_200_000_000L
/** After a splash the page has already had the whole logo to get ready. */
private const val AfterSplashMaxNs = 300_000_000L
/** The first run holds here: the ribbon is lit, and the night hasn't lifted. */
private const val HoldAt = 0.40f
/** The night starts to lift, so the screen behind can start its own opening. */
private const val PartAt = 0.44f
/** The ribbon starts to fade, so the sky over the page can start to rise. */
private const val LeaveAt = 0.84f

private const val UnveilSource = """
uniform float2 size;
uniform float time;
uniform float p;
uniform half3 veil;

$NoiseSource
$RibbonSource

half4 main(float2 c) {
    float2 uv = c / size;
    float parted = clamp((p - 0.44) / 0.56, 0.0, 1.0);
    parted = parted * parted * (3.0 - 2.0 * parted);

    // The night lifts from the bottom edge, torn by the folds, and carries the ribbon up with it.
    float tear = noise(float2(uv.x * 2.7 + time * 0.08, 9.0)) - 0.5;
    float lift = mix(1.25, -0.50, parted) + tear * 0.10 * parted;
    float gap = smoothstep(lift - 0.07, lift + 0.07, uv.y);
    float line = min(0.60, lift - 0.04);

    // A point of light catches at the left and races along the ribbon's path.
    float front = mix(-0.15, 1.30, smoothstep(0.02, 0.28, p));
    float span = 1.0 - smoothstep(front - 0.02, front + 0.14, uv.x);
    float head = exp(-(uv.x - front) * (uv.x - front) / 0.012) * (1.0 - smoothstep(0.22, 0.34, p));

    // The ribbon itself, scaled to the screen's width, with rays growing as it wakes.
    float tall = 0.03 + 0.20 * smoothstep(0.12, 0.52, p);
    float y = (uv.y - line) * size.y / size.x * 0.58 + 0.285;
    float2 near = ribbon(float2(uv.x * 0.58 + 0.21, y), time * 0.9 + 170.0, 11.7, 0.285, -0.170, tall);
    float2 far = ribbon(float2(uv.x * 0.58 + 0.21, y), time * 0.7 + 170.0, 3.1, 0.150, 0.060, tall * 0.8);
    float wake = smoothstep(0.26, 0.46, p);
    float light = (near.x + far.x * 0.36 * wake) * span * (1.0 + 1.2 * head);
    light += head * 0.5 * exp(-abs(y - 0.285) * 22.0);
    // The catching point burns green all the way up; only a settled ray thins into indigo.
    float up = near.y * wake;

    float leave = 1.0 - smoothstep(0.86, 1.0, p);
    float a = (1.0 - exp(-light * 1.3)) * 0.72 * leave;
    float night = (1.0 - gap) * (1.0 - smoothstep(0.93, 1.0, p));

    // Stars in the night that is left.
    float2 grid = uv * float2(70.0, 70.0 * size.y / size.x);
    float seed = hash(floor(grid));
    float star = step(0.975, seed) * smoothstep(0.10, 0.02, length(fract(grid) - 0.5)) * (0.25 + 0.6 * fract(seed * 91.0));
    star *= night * (1.0 - a) * smoothstep(0.0, 0.14, p);

    half3 rgb = veil * night * (1.0 - a) + tint(light, up) * a + half3(star);
    float alpha = max(max(night, a), star);
    // A touch of noise, so slow dark gradients never band.
    rgb += (hash(c) - 0.5) / 160.0 * alpha;
    return half4(clamp(rgb, half3(0.0), half3(1.0)), alpha);
}
"""
