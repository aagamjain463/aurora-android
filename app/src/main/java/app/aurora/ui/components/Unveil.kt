package app.aurora.ui.components

import android.annotation.SuppressLint
import android.graphics.BitmapShader
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import androidx.annotation.RequiresApi
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.IntSize
import app.aurora.R
import app.aurora.ui.theme.Aurora
import app.aurora.ui.theme.Motion
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
 * The opening: the approved logo, already on screen from the system splash, holds for a moment on its black; then the
 * black fades off the real page as it rises into place. The logo itself never animates or changes. A tap hurries it.
 * With reduced motion it fades straight away. It only ever draws, so nothing else waits on it.
 */
@Composable
fun AuroraUnveil(modifier: Modifier = Modifier) {
    val reduced = Aurora.reducedMotion
    var p by remember { mutableFloatStateOf(if (reduced) PartAt else 0f) }
    var pace by remember { mutableIntStateOf(if (reduced) ReducedMs else TotalMs) }
    LaunchedEffect(Unit) {
        if (reduced) Opening.lift = 0f
        var last = withFrameNanos { it }
        // Nothing has been seen yet. The clock waits for the page's heavy first frames, so the light never starts on a stutter.
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
        val began = last
        var shown = false
        var parted = false
        while (p < 1f) {
            withFrameNanos { now ->
                // A dropped frame slows the light for a moment instead of making it jump. Past that, a device that
                // can't keep up runs on real time, so the opening can never hold anyone up for long.
                val real = (now - last) / 1e9f
                val dt = if (now - began < TotalMs * 1_400_000L) real.coerceAtMost(MaxStep) else real
                p = stepOpening(p, dt, pace)
                last = now
            }
            if (Opening.brief && p < PartAt) { p = PartAt; pace = ReducedMs }
            if (!shown && p >= ShowAt) { shown = true; Opening.showPage() }
            if (!parted && p >= ContentAt) { parted = true; Opening.part() }
            if (p >= LeaveAt) Opening.leave()
            if (!reduced) Opening.lift = 1f - leaving(p)
        }
        Opening.finish()
    }
    // The same art as the system splash, at the same size on the same black, so the handover is invisible.
    val logo = androidx.compose.ui.graphics.ImageBitmap.imageResource(R.drawable.ic_launcher_logo)
    val night = colorResource(R.color.aurora_splash)
    Box(
        modifier.fillMaxSize()
            .pointerInput(Unit) { detectTapGestures { pace = minOf(pace, HurryMs) } }
            .clearAndSetSemantics { }
            .drawWithCache {
                val side = (LogoDp * density).toInt()
                val left = (size.width - side) / 2f
                val top = (size.height - side) / 2f
                val rise = RiseDp * density
                onDrawBehind {
                    val gone = leaving(p)
                    drawRect(night, alpha = 1f - gone)
                    // The logo holds still, then lifts away with the black as the page rises into place.
                    drawImage(
                        logo, dstOffset = androidx.compose.ui.unit.IntOffset(left.toInt(), (top - gone * rise).toInt()),
                        dstSize = IntSize(side, side), alpha = 1f - gone, filterQuality = FilterQuality.High,
                    )
                }
            },
    )
}

private fun leaving(p: Float) = Motion.Ease.transform(((p - PartAt) / (1f - PartAt)).coerceIn(0f, 1f))

private const val TotalMs = 1400
private const val HurryMs = 300
private const val ReducedMs = 900
private const val MaxStep = 1f / 30f
private const val CalmFrames = 4
private const val CalmFrameNs = 24_000_000L
private const val SettleMaxNs = 700_000_000L
/** The page draws again under the opaque night, while the logo is still, so that frame's cost is never seen. */
private const val ShowAt = 700f / TotalMs
/** The finished logo has held for its moment: the night starts to fade and the page rises. */
private const val PartAt = 900f / TotalMs
/** The night is nearly gone: Home's content can start its own entrance. Together they drop frames on a mid-range phone. */
private const val ContentAt = 1250f / TotalMs
/** The night has gone: the sky over the page can start to rise. */
private const val LeaveAt = 1350f / TotalMs
private const val RiseDp = 14f
private const val ReopenAfterMs = 5 * 60 * 1000L

/** The Android 12+ splash draws an un-backed icon 288dp square. */
private const val LogoDp = 288f

