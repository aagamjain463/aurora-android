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
import app.aurora.ui.theme.Palette
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

    /** The opening's curtains are going: the page's own sky can take over without both costing the GPU at once. */
    var leaving by mutableStateOf(false)
        private set

    /** How far the page still has to rise into place, 1 to 0. Read it in a layer: it changes every frame. */
    var lift by mutableFloatStateOf(0f)

    /** Back to a running session: no formation, just the finished logo fading off the page. */
    var brief by mutableStateOf(false)
    fun play() { brief = false; playing = true; covering = true; pageHidden = false; leaving = false; lift = 1f }
    fun hidePage() { pageHidden = true }
    fun part() { covering = false; pageHidden = false }
    fun leave() { leaving = true }
    fun finish() { covering = false; pageHidden = false; leaving = false; lift = 0f; playing = false }
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
 * First Light: the approved logo forms along its own ribbons on midnight green, holds, and the night fades off the
 * real page as it rises into place. This is the only place the logo animates: the system splash before it is empty
 * midnight green, so the logo can't form twice. A tap hurries it. With reduced motion the finished logo fades straight
 * away. It only ever draws, so nothing else waits on it.
 */
@Composable
fun AuroraUnveil(modifier: Modifier = Modifier) {
    val reduced = Aurora.reducedMotion
    var p by remember { mutableFloatStateOf(if (reduced) PartAt else 0f) }
    var pace by remember { mutableIntStateOf(if (reduced) ReducedMs else TotalMs) }
    LaunchedEffect(Unit) {
        if (reduced) Opening.lift = 0f
        var last = withFrameNanos { it }
        var frames = 0
        var parted = false
        while (p < 1f) {
            withFrameNanos { now ->
                p = stepOpening(p, (now - last) / 1e9f, pace)
                last = now
            }
            if (Opening.brief && p < PartAt) { p = PartAt; pace = ReducedMs }
            // The page has drawn under the night by now, so its art is uploaded before it's wanted.
            if (++frames == PageReadyFrames && p < PartAt) Opening.hidePage()
            if (!parted && p >= PartAt) { parted = true; Opening.part() }
            if (p >= LeaveAt) Opening.leave()
            if (!reduced) Opening.lift = 1f - leaving(p)
        }
        Opening.finish()
    }
    val logo = ImageBitmap.imageResource(R.drawable.logo_full)
    val reveal = if (!reduced && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) rememberReveal(logo) else null
    val night = colorResource(R.color.aurora_splash)
    Box(
        modifier.fillMaxSize()
            .pointerInput(Unit) { detectTapGestures { pace = minOf(pace, HurryMs) } }
            .clearAndSetSemantics { }
            .drawWithCache {
                val k = LogoDpPerMasterPx * density
                val logoSize = Size(LogoMasterWidth * k, LogoMasterHeight * k)
                val left = size.width / 2f - LogoCentreX * k
                val top = size.height / 2f - LogoCentreY * k
                val centre = Offset(size.width / 2f, size.height / 2f)
                val radius = HaloDp * density
                val halo = Brush.radialGradient(listOf(Palette.Aurora500.copy(alpha = 0.07f), Palette.Aurora500.copy(alpha = 0.02f), Color.Transparent), centre, radius)
                val warm = Brush.radialGradient(listOf(Palette.Gold400.copy(alpha = 0.05f), Color.Transparent), centre + Offset(radius * 0.30f, -radius * 0.04f), radius * 0.6f)
                val rise = RiseDp * density
                reveal?.setSize(logoSize)
                onDrawBehind {
                    val v = p
                    val form = (v * TotalMs / FormMs).coerceIn(0f, 1f)
                    val gone = leaving(v)
                    drawRect(night, alpha = 1f - gone)
                    // The halo arrives with the finished logo and is the last to leave, rising into the page's sky.
                    val lit = smooth((form - 0.62f) / 0.38f) * (1f - gone * gone)
                    if (lit > 0f) translate(top = -gone * rise * 3f) {
                        drawRect(halo, alpha = lit)
                        drawRect(warm, alpha = lit)
                    }
                    translate(left, top - gone * rise) {
                        if (reveal != null) {
                            reveal.setProgress(form)
                            drawRect(reveal.brush, size = logoSize, alpha = 1f - gone)
                        } else {
                            // Below Android 13, and with reduced motion: the logo, whole.
                            val shown = if (reduced) 1f else smooth(form)
                            drawImage(logo, dstSize = IntSize(logoSize.width.toInt(), logoSize.height.toInt()), alpha = shown * (1f - gone), filterQuality = FilterQuality.High)
                        }
                    }
                }
            },
    )
}

private fun leaving(p: Float) = Motion.Ease.transform(((p - PartAt) / (1f - PartAt)).coerceIn(0f, 1f))

private fun smooth(v: Float): Float {
    val k = v.coerceIn(0f, 1f)
    return k * k * (3f - 2f * k)
}

/** Only ever built on Android 13+, by [rememberReveal]. */
@SuppressLint("NewApi")
private class Reveal(private val shader: RuntimeShader) {
    val brush = ShaderBrush(shader)
    fun setSize(size: Size) = shader.setFloatUniform("size", size.width, size.height)
    fun setProgress(p: Float) = shader.setFloatUniform("p", p)
}

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
@Composable
private fun rememberReveal(logo: ImageBitmap): Reveal {
    val map = ImageBitmap.imageResource(R.drawable.first_light_reveal)
    return remember(logo, map) {
        val shader = RuntimeShader(UnveilSource)
        shader.setInputShader("logo", linear(logo))
        // The map holds times, not colours, so it skips colour management.
        shader.setInputBuffer("reveal", linear(map))
        shader.setFloatUniform("logoPx", logo.width.toFloat(), logo.height.toFloat())
        shader.setFloatUniform("mapPx", map.width.toFloat(), map.height.toFloat())
        Reveal(shader)
    }
}

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
private fun linear(image: ImageBitmap) =
    BitmapShader(image.asAndroidBitmap(), Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply { filterMode = BitmapShader.FILTER_MODE_LINEAR }

private const val TotalMs = 4500
private const val FormMs = 3000
private const val HurryMs = 350
private const val ReducedMs = 1000
private const val PageReadyFrames = 3
/** The finished logo has held for its moment: the night starts to fade and the page rises. */
private const val PartAt = 3700f / TotalMs
/** The sky over the page can start to rise. */
private const val LeaveAt = 3800f / TotalMs
private const val RiseDp = 14f
private const val HaloDp = 230f
private const val ReopenAfterMs = 5 * 60 * 1000L

// The same size and centre as the still splash icon (brand/tools/final_logo.py).
private const val LogoDpPerMasterPx = 1f / 5.625f
private const val LogoMasterWidth = 980f
private const val LogoMasterHeight = 844f
private const val LogoCentreX = 485f
private const val LogoCentreY = 382f

/** Times come from brand/tools/first_light.py. At p = 1 this is the raster, untouched. */
private const val UnveilSource = """
uniform shader logo;
uniform shader reveal;
uniform float2 size;
uniform float2 logoPx;
uniform float2 mapPx;
uniform float p;

half4 main(float2 c) {
    float2 uv = c / size;
    half4 l = logo.eval(uv * logoPx);
    float t = reveal.eval(uv * mapPx).r * 0.75;
    float a = smoothstep(t, t + 0.12, p);
    float age = clamp((p - t) / 0.25, 0.0, 1.0);
    float d = (p - t - 0.03) / 0.04;
    float front = exp(-d * d) * (1.0 - age);
    half3 rgb = l.rgb * mix(half3(0.18, 0.78, 0.55), half3(1.0), age) + l.a * front * half3(0.19, 0.35, 0.26);
    return half4(clamp(rgb, half3(0.0), half3(1.0)), l.a) * a;
}
"""
