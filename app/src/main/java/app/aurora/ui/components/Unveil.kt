package app.aurora.ui.components

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.IntSize
import app.aurora.R
import app.aurora.ui.theme.Aurora
import kotlinx.coroutines.flow.first

/** Whether the opening is on screen. Anything that performs on first show waits for it to part. */
object Opening {
    var playing by mutableStateOf(false)
        private set
    var covering by mutableStateOf(false)
        private set
    /** The night is opaque and animating: skip drawing the page under it, so all the GPU goes to the light. */
    var pageHidden by mutableStateOf(false)
        private set
    /** The page is drawing under the night for the first time, so its art and shaders are ready before they're wanted. */
    var warming by mutableStateOf(false)
        private set

    /** The opening's curtains are going: the page's own sky can take over without both costing the GPU at once. */
    var leaving by mutableStateOf(false)
        private set

    /** How far the page still has to rise into place, 1 to 0. Read it in a layer: it changes every frame. */
    var lift by mutableFloatStateOf(0f)

    /** Back to a running session: no formation, just the finished logo fading off the page. */
    var brief by mutableStateOf(false)

    /**
     * How many loading surfaces are on screen under the opening. The mark holds until the real page is there, so the
     * fade never uncovers a loading screen (whose own small logo would flash on its way out).
     */
    var loading by mutableIntStateOf(0)
    fun play() { brief = false; playing = true; covering = true; pageHidden = false; warming = true; leaving = false; lift = 1f }
    fun hidePage() { warming = false; pageHidden = true }
    fun showPage() { pageHidden = false }
    fun part() { covering = false; pageHidden = false; warming = false }
    fun leave() { leaving = true }
    fun finish() { covering = false; pageHidden = false; warming = false; leaving = false; lift = 0f; playing = false }
    suspend fun awaitParted() { snapshotFlow { covering }.first { !it } }
    suspend fun awaitSkyTurn() { snapshotFlow { !playing || leaving }.first { it } }
}

/**
 * Whether this launch gets the opening. Aurora's process lives on with Shield, so a new activity alone isn't a real
 * arrival: only one after a real absence is. A recreated activity, or a return from a permission page, goes straight
 * to the page. With a screen reader moving around the page, nothing covers it.
 */
internal fun opensWithFirstLight(recreated: Boolean, awayMs: Long?, touchExploring: Boolean): Boolean =
    !recreated && !touchExploring && (awayMs == null || awayMs > ReopenAfterMs)

/**
 * One frame of the opening: [p] moves on by the real time passed, at a pace of [ms] for the whole way. Never capped:
 * on a device dropping frames the opening has fewer of them, not more seconds, so it can't hold anyone up.
 */
internal fun stepOpening(p: Float, dt: Float, ms: Int): Float = (p + dt * 1000f / ms).coerceAtMost(1f)

/**
 * The opening: the approved mark, off its tile and already on screen from the system splash, holds on its black. Then
 * the black and the mark fade off the real page as it settles into place. The mark itself never moves or changes. A
 * tap hurries it. With reduced motion it fades straight away. It only ever draws, so nothing else waits on it.
 */
@Composable
fun AuroraUnveil(modifier: Modifier = Modifier) {
    val reduced = Aurora.reducedMotion
    var p by remember { mutableFloatStateOf(if (reduced) PartAt else 0f) }
    var pace by remember { mutableIntStateOf(if (reduced) ReducedMs else TotalMs) }
    LaunchedEffect(Unit) {
        if (reduced) Opening.lift = 0f
        var last = withFrameNanos { it }
        // Nothing has been seen yet. The clock waits for the page's heavy first frames, so the fade never starts on a stutter.
        if (!reduced) {
            val first = last
            var calm = 0
            while (calm < CalmFrames && last - first < SettleMaxNs) {
                val now = withFrameNanos { it }
                calm = if (now - last < CalmFrameNs) calm + 1 else 0
                last = now
            }
            Opening.hidePage()
        }
        var began = last
        var shown = false
        var parted = false
        var held = 0L
        while (p < 1f) {
            withFrameNanos { now ->
                // A dropped frame slows the fade for a moment instead of making it jump. Past that, a device that
                // can't keep up runs on real time, so the opening can never hold anyone up for long.
                val real = (now - last) / 1e9f
                // The mark holds, still, while the page under it is still loading (at most HoldMaxNs).
                if (p >= PartAt && Opening.loading > 0 && held < HoldMaxNs) { held += now - last; began += now - last }
                else {
                    val dt = if (now - began < TotalMs * 1_400_000L) real.coerceAtMost(MaxStep) else real
                    p = stepOpening(p, dt, pace)
                }
                last = now
            }
            if (Opening.brief && p < PartAt) { p = PartAt; pace = ReducedMs }
            if (!shown && p >= ShowAt) { shown = true; Opening.showPage() }
            if (!parted && p >= ContentAt) { parted = true; Opening.part() }
            if (p >= LeaveAt) Opening.leave()
            if (!reduced) Opening.lift = 1f - fade(p)
        }
        Opening.finish()
    }
    // The same art as the system splash, at the same size on the same black, so the handover is invisible.
    val mark = ImageBitmap.imageResource(R.drawable.aurora_glyph)
    val night = colorResource(R.color.aurora_splash)
    Box(
        modifier.fillMaxSize()
            .pointerInput(Unit) { detectTapGestures { pace = minOf(pace, HurryMs) } }
            .clearAndSetSemantics { }
            .drawWithCache {
                val side = (LogoDp * density).roundToInt()
                val at = IntOffset(((size.width - side) / 2f).roundToInt(), ((size.height - side) / 2f).roundToInt())
                onDrawBehind {
                    val t = fadeT(p)
                    val gone = fade(p)
                    // The mark dissolves into the black first, drifting a breath closer; only then does the black lift
                    // off the page, so the mark is never ghosted over the page's text. The drift is a float scale, not
                    // a resize in whole pixels, so it glides instead of stepping.
                    val markGone = smoother((t / MarkFadeBy).coerceAtMost(1f))
                    drawRect(night, alpha = 1f - gone)
                    if (markGone < 1f) scale(1f + MarkDrift * markGone, center) {
                        drawImage(mark, dstOffset = at, dstSize = IntSize(side, side), alpha = 1f - markGone, filterQuality = FilterQuality.High)
                    }
                }
            },
    )
}

/** How far the night has faded off the page, 0 to 1, on the one ease curve. */
private fun fade(p: Float) = smoother(((fadeT(p) - NightFrom) / (1f - NightFrom)).coerceIn(0f, 1f))

private fun fadeT(p: Float) = ((p - PartAt) / (1f - PartAt)).coerceIn(0f, 1f)

/** Smootherstep: starts and ends at rest with no jolt in speed, so a crossfade has no visible edge at either end. */
internal fun smoother(t: Float) = t * t * t * (t * (t * 6f - 15f) + 10f)

// ~1.4 s from first frame to the page: the mark registers, then one long, soft crossfade. Never feels like waiting.
private const val TotalMs = 1400
private const val HurryMs = 250
private const val ReducedMs = 600
private const val MaxStep = 1f / 30f
private const val CalmFrames = 4
private const val CalmFrameNs = 24_000_000L
private const val SettleMaxNs = 400_000_000L
/** The page draws again under the opaque night, while the mark is still, so that frame's cost is never seen. */
private const val ShowAt = 300f / TotalMs
/** The mark has held for its moment: the night starts to fade and the page settles. */
private const val PartAt = 450f / TotalMs
/** The night is nearly gone: Home's content can start its own entrance. */
private const val ContentAt = 1150f / TotalMs
/** The night has gone: the sky over the page can start to rise. */
private const val LeaveAt = 1300f / TotalMs
/** The mark is gone by this share of the fade. */
private const val MarkFadeBy = 0.6f
/** The night starts to lift off the page from this share of the fade, as the mark's last light goes. */
private const val NightFrom = 0.2f
/** How much closer the mark drifts as it dissolves. */
private const val MarkDrift = 0.04f
private const val ReopenAfterMs = 5 * 60 * 1000L
/** The longest the mark waits for a loading page before fading anyway. */
private const val HoldMaxNs = 2_500_000_000L

/** The Android 12+ splash draws an un-backed icon 288dp square; the mark fills its inner 192dp circle. */
private const val LogoDp = 288f

