package app.aurora.ui.components

import android.graphics.BitmapShader
import android.graphics.Matrix
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.animation.core.withInfiniteAnimationFrameNanos
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import app.aurora.R
import kotlinx.coroutines.delay
import app.aurora.ui.theme.Aurora
import app.aurora.ui.theme.AuroraColors
import app.aurora.ui.theme.Motion

/**
 * What the living sky is doing. Light means protected, movement means a session runs:
 *
 * - [Quiet]: nothing protects you now. A slow aurora; the light you earn this week makes it taller.
 * - [Lit]: protection is on (an Always configuration, or the Shield answering for it). Brighter, steadier.
 * - [Alive]: a timed session runs. The full ribbon, winding at its natural pace.
 * - [Flare]: a session just started. The sky surges once, then settles alive.
 *
 * The sky always moves a little (it is a living aurora), and wakes with a short surge each time a screen opens.
 */
enum class Sky { Quiet, Lit, Alive, Flare }

/**
 * The northern lights, live: the ribbons ([RibbonSource]) in the sky over the painting, their reflection broken on
 * the frozen lake. The mask comes from brand/tools/aurora_art.py, so light never crosses the land. [glow] is the
 * week's verified progress (Progress.kt `light()`): the sky you earn grows taller rays. [lit] is how
 * far a running timer has lit the sky across, 0 to 1, or negative when nothing is timed.
 *
 * Sits exactly over the painting drawn at [alignment]. Android 13 and later; earlier versions show the painted
 * still. With reduced motion the sky holds still.
 */
@Composable
fun AuroraSky(
    sky: Sky,
    glow: Float,
    alignment: BiasAlignment,
    modifier: Modifier = Modifier,
    lit: () -> Float = { -1f },
    visible: () -> Boolean = { true },
) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
    val mask = ImageBitmap.imageResource(R.drawable.aurora_mask)
    SkyCanvas(sky, glow.coerceIn(0f, 1f), mask, alignment, lit, modifier, visible)
}

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
@Composable
private fun SkyCanvas(sky: Sky, glow: Float, mask: ImageBitmap, alignment: BiasAlignment, lit: () -> Float, modifier: Modifier, visible: () -> Boolean) {
    val reduced = Aurora.reducedMotion
    val settle = if (reduced) snap<Float>() else tween(1600, easing = Motion.Ease)
    // Earned light: a first day is a trace; a steady week is a full sky.
    val target = when (sky) {
        Sky.Quiet -> 0.85f + 0.15f * glow
        Sky.Lit -> 0.95f + 0.10f * glow
        Sky.Alive, Sky.Flare -> 1.1f
    }
    val strength by animateFloatAsState(target, settle, label = "sky")
    val pace = when (sky) {
        Sky.Quiet -> SkyPace
        Sky.Lit -> SkyPace * 1.25f
        Sky.Alive, Sky.Flare -> SkyPace * 1.8f
    }
    val speed by animateFloatAsState(if (reduced) 0f else pace, settle, label = "drift")
    // The sky lights as the app opens, like the art's first light. Once: after that every screen is simply still lit.
    var woken by SkyClock.woken
    val shown = remember { Animatable(if (reduced || woken) 1f else 0f) }
    LaunchedEffect(Unit) { if (!reduced && !woken) { Opening.awaitSkyTurn(); delay(SkyAfterContentMs); shown.animateTo(1f, tween(Motion.Reveal * 2, easing = Motion.Ease)) } }
    val surge = remember { Animatable(0f) }
    // Waking: the ribbon brightens and hurry as the screen opens, then slow to their pace.
    LaunchedEffect(Unit) { if (!reduced && !woken) { woken = true; Opening.awaitSkyTurn(); delay(SkyAfterContentMs); surge.snapTo(0.12f); surge.animateTo(0f, tween(2600, easing = Motion.Ease)) } }
    LaunchedEffect(sky == Sky.Flare, reduced) {
        if (reduced) surge.snapTo(0f)
        else if (sky == Sky.Flare) {
            surge.animateTo(0.35f, tween(Motion.Reveal, easing = Motion.Ease))
            surge.animateTo(0f, tween(4200, easing = Motion.Ease))
        }
    }
    // Only moves while it can be seen: resumed, and not scrolled away.
    val resumed by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val seen by remember { derivedStateOf(visible) }
    val moving = speed > 0f && seen && resumed.isAtLeast(Lifecycle.State.RESUMED)
    LaunchedEffect(moving) { if (moving) runSky { speed * (1f + 2.5f * surge.value) } }

    val shader = remember { RuntimeShader(SkySource) }
    val brush = remember(shader) { ShaderBrush(shader) }
    val bitmap = remember(mask) { mask.asAndroidBitmap() }
    val maskShader = remember(bitmap) { BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply { filterMode = BitmapShader.FILTER_MODE_LINEAR } }
    val colors = Aurora.colors
    Box(modifier.clearAndSetSemantics { }) { Canvas(Modifier.softLight()) {
        val k = (strength + 0.6f * surge.value) * shown.value
        // While the page warms up under the opening it still draws, faintly, so the shader compiles now and not as the night parts.
        if (k <= 0.005f && !Opening.warming) return@Canvas
        // The art is drawn with ContentScale.Crop at [alignment]; place the mask and the light the same way.
        val scale = maxOf(size.width / ArtWidth, size.height / ArtHeight)
        val w = ArtWidth * scale
        val h = ArtHeight * scale
        val x = (size.width - w) / 2f * (1f + alignment.horizontalBias)
        val y = (size.height - h) / 2f * (1f + alignment.verticalBias)
        maskShader.setLocalMatrix(Matrix().apply { setScale(w / bitmap.width, h / bitmap.height); postTranslate(x, y) })
        shader.setInputShader("sky", maskShader)
        shader.setFloatUniform("origin", x, y)
        shader.setFloatUniform("extent", w, h)
        shader.setFloatUniform("time", SkyClock.time.floatValue)
        shader.setFloatUniform("strength", k)
        shader.setFloatUniform("reach", glow)
        shader.setFloatUniform("lit", lit())
        shader.setRibbonColors(colors)
        drawRect(brush)
    } }
}

/**
 * Soft light drawn at half resolution, scaled up from its own layer. Curtains this soft look the same at a
 * quarter of the pixels, and the layer only redraws when the light moves, not every time the page does.
 */
/** The sky rises once the page's content has arrived. Together they drop frames on a mid-range phone. */
private const val SkyAfterContentMs = 900L

internal fun Modifier.softLight(): Modifier = fillMaxSize(0.5f).graphicsLayer {
    scaleX = 2f
    scaleY = 2f
    transformOrigin = TransformOrigin(0f, 0f)
    compositingStrategy = CompositingStrategy.Offscreen
}

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
internal fun RuntimeShader.uniformColor(name: String, c: Color) = setFloatUniform(name, c.red, c.green, c.blue)

private const val ArtWidth = 1440f
private const val ArtHeight = 1324f
private const val StillMoment = 260f

/**
 * How fast the light moves. At this pace the wave along the band turns in about 40s and bright knots cross the sky
 * in about 15s: unmistakably moving, still slow enough to rest under. Brightness does not change with it.
 */
private const val SkyPace = 3f

/**
 * One sky: every screen reads the same moment, so the light carries on across a tab switch instead of starting over.
 * The fixed starting moment keeps the still sky the same composed picture.
 */
private object SkyClock {
    val time = mutableFloatStateOf(StillMoment)
    var last = 0L
    val woken = mutableStateOf(false)
}

/** Advances [SkyClock] for as long as it runs. Skies open together share a frame, so only the first moves it on. */
private suspend fun runSky(speed: () -> Float) {
    while (true) {
        withInfiniteAnimationFrameNanos { now ->
            // ponytail: 30 fps is plenty for light this soft and halves the GPU cost on 60–120 Hz
            // screens; raise it only if the drift ever looks stepped.
            val dt = (now - SkyClock.last) / 1e9f
            if (dt >= 1f / 30f) {
                SkyClock.time.floatValue += dt.coerceAtMost(0.1f) * speed()
                SkyClock.last = now
            }
        }
    }
}

/** Value noise shared by the sky and the opening. */
internal const val NoiseSource = """
float hash(float2 p) {
    p = fract(p * float2(123.34, 456.21));
    p += dot(p, p + 45.32);
    return fract(p.x * p.y);
}

float noise(float2 p) {
    float2 i = floor(p);
    float2 f = fract(p);
    float2 u = f * f * (3.0 - 2.0 * f);
    return mix(mix(hash(i), hash(i + float2(1.0, 0.0)), u.x), mix(hash(i + float2(0.0, 1.0)), hash(i + float2(1.0, 1.0)), u.x), u.y);
}
"""

/**
 * The light itself, measured from footage of the real thing: one long ribbon winding across the sky, a fainter one
 * above it, a wash low in the sky and a thin band on the far horizon. Coordinates are the painting's (0..1 across
 * each axis, y down). The ribbon doubles back on itself in folds; a slow wave travels its length, bright knots wander
 * along it, and soft rays rise from it. Broad and dim on purpose: light to rest under. Indigo only at the top of a tall ray.
 *
 * brand/tools/aurora_art.py `ribbons()` is this, line for line, and paints the still: change one, change both.
 */
internal const val RibbonSource = """
uniform half3 deep;
uniform half3 body;
uniform half3 edge;
uniform half3 top;

// x: light, y: how far up the ray this point is (0 at the band, 1 at the top).
float2 ribbon(float2 p, float t, float seed, float base, float slope, float tall) {
    float bend = noise(float2(p.x * 1.6 + seed, p.y * 3.2 + t * 0.030)) - 0.5;
    float sway = noise(float2(p.x * 2.1 + seed + 5.0, t * 0.026)) - 0.5;
    float x = p.x + 0.20 * bend + 0.018 * sin(p.y * 15.0 + p.x * 3.5 + t * 0.045 + seed);
    float c = base + slope * (x - 0.5) + 0.050 * sin(x * 4.6 + t * 0.055 + seed) + 0.016 * sin(x * 11.0 - t * 0.085 + seed * 2.0) + 0.085 * sway;
    float d = p.y - c;
    float knot = 0.30 + 0.70 * smoothstep(0.25, 0.85, noise(float2(x * 2.4 - t * 0.050 + seed, seed + 1.0)));
    float wide = noise(float2(x * 11.0 + t * 0.040, seed + 2.0));
    float fine = noise(float2(x * 34.0 - t * 0.070, seed + 3.0));
    float rays = pow(0.80 * wide + 0.20 * fine, 1.4);
    float thick = 0.026 + 0.024 * smoothstep(0.30, 1.0, knot);
    float core = 0.62 * exp(-(d / thick) * (d / thick)) + 0.30 * exp(-(d / (thick * 2.6)) * (d / (thick * 2.6)));
    float up = -d;
    float reach = tall * (0.40 + 0.95 * noise(float2(x * 5.5 + t * 0.022, seed + 4.0)));
    float above = exp(-max(up, 0.0) / reach) * (0.10 + 0.90 * rays);
    float under = max(-up, 0.0) / 0.040;
    float below = exp(-under * under);
    return float2((core + above * below) * knot, clamp(up / (reach * 2.4), 0.0, 1.0));
}

// Deep green where it is faint, oxygen green in the band. It never burns white: this is light to rest under.
half3 tint(float light, float up) {
    half3 c = mix(deep, body, smoothstep(0.10, 0.45, light));
    c = mix(c, edge, smoothstep(0.40, 1.20, light));
    return mix(c, top, 0.70 * smoothstep(0.45, 1.0, up));
}
"""

/** Hands the ribbon its colours. Every shader built on [RibbonSource] calls this. */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
internal fun RuntimeShader.setRibbonColors(colors: AuroraColors) {
    uniformColor("deep", colors.glowDeep)
    uniformColor("body", colors.glowCool)
    uniformColor("edge", colors.accent)
    uniformColor("top", colors.glowTop)
}

private const val SkySource = """
uniform shader sky;
uniform float2 origin;
uniform float2 extent;
uniform float time;
uniform float strength;
uniform float reach;
uniform float lit;

$NoiseSource
$RibbonSource

float2 ribbons(float2 p, float t) {
    float tall = 0.15 + 0.08 * reach;
    float2 near = ribbon(p, t, 11.7, 0.245, -0.070, tall);
    float2 far = ribbon(p, t * 0.8, 3.1, 0.130, 0.030, tall * 0.8);
    // The wash of light low in the sky, and the thin band that always sits on the far horizon.
    float veil = 0.11 * smoothstep(0.00, 0.38, p.y) * (0.50 + 0.70 * noise(float2(p.x * 1.9 - t * 0.012, p.y * 3.0 + 9.0)));
    float band = 0.07 * exp(-abs(p.y - 0.428) / 0.030);
    float farLight = far.x * 0.36;
    // A soft bank of light over the massif, so the left of the sky is never empty, whatever the ribbon is doing.
    float high = (p.y - 0.17) / 0.105;
    float bank = 0.34 * (1.0 - smoothstep(0.12, 0.66, p.x)) * exp(-high * high) * (0.55 + 0.75 * noise(float2(p.x * 3.0 + t * 0.018, p.y * 4.0 + 2.0)));
    // A low arc over the snow ridges, with its own rays, so the lower right of the sky is lit too.
    float lane = p.y - (0.315 + 0.05 * (p.x - 0.5) + 0.018 * sin(p.x * 5.3 - t * 0.04 + 1.0));
    float under = lane / 0.030;
    float arc = 0.52 * smoothstep(0.22, 0.64, p.x) * (0.50 + 0.80 * noise(float2(p.x * 9.0 + t * 0.03, 6.0))) * (lane > 0.0 ? exp(-under * under) : exp(lane / 0.085));
    float light = near.x + farLight;
    return float2(light + veil + band + bank + arc, (near.x * near.y + farLight * far.y) / max(light, 0.0001));
}

half4 main(float2 fragCoord) {
    half4 mask = sky.eval(fragCoord);
    float2 uv = (fragCoord - origin) / extent;
    // A running timer lights the sky across as time passes.
    float across = lit < 0.0 ? 1.0 : mix(0.34, 1.0, smoothstep(lit + 0.10, lit - 0.10, uv.x));
    if (mask.r > 0.004) {
        float2 l = ribbons(uv, time);
        float light = l.x * across;
        float a = (1.0 - exp(-light * strength * 1.4)) * 0.70 * mask.r;
        // A few of the stars twinkle. The rest are painted, pin-sharp, into the night behind.
        float2 grid = uv * float2(44.0, 40.0);
        float seed = hash(floor(grid));
        float star = step(0.90, seed) * smoothstep(0.035, 0.01, length(fract(grid) - 0.5)) * (0.5 + 0.5 * sin(time * (0.25 + 0.6 * fract(seed * 37.0)) + seed * 90.0));
        star *= 0.50 * mask.r * (1.0 - a);
        return half4(tint(light, l.y) * a + half3(star), max(a, star));
    }
    if (mask.g > 0.004) {
        // The lake: the sky mirrored at the shore, dim and smeared along the ripples.
        float depth = uv.y - 0.44;
        float ripple = noise(float2(uv.x * 14.0, uv.y * 160.0)) - 0.5;
        float2 l = ribbons(float2(uv.x + ripple * 0.010 * (1.0 + depth * 8.0), 0.44 - depth * 1.5), time);
        float a = (1.0 - exp(-l.x * across * strength * 1.4)) * 0.34 * exp(-depth / 0.20) * mask.g;
        return half4(tint(l.x, l.y) * a, a);
    }
    return half4(0.0);
}
"""

/**
 * The same light for a screen with no painting: the ribbons alone, fading into the page. Takes no touches.
 * Android 13 and later. With reduced motion it holds still.
 */
@Composable
internal fun AuroraGlow(modifier: Modifier = Modifier) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
    GlowCanvas(modifier)
}

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
@Composable
private fun GlowCanvas(modifier: Modifier) {
    val reduced = Aurora.reducedMotion
    val resumed by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val moving = !reduced && resumed.isAtLeast(Lifecycle.State.RESUMED)
    LaunchedEffect(moving) { if (moving) runSky { SkyPace } }
    val shader = remember { RuntimeShader(GlowSource) }
    val brush = remember(shader) { ShaderBrush(shader) }
    val colors = Aurora.colors
    Box(modifier.clearAndSetSemantics { }) { Canvas(Modifier.softLight()) {
        shader.setFloatUniform("size", size.width, size.height)
        shader.setFloatUniform("time", SkyClock.time.floatValue)
        shader.setRibbonColors(colors)
        drawRect(brush)
    } }
}

private const val GlowSource = """
uniform float2 size;
uniform float time;

$NoiseSource
$RibbonSource

half4 main(float2 c) {
    float2 uv = c / size;
    float2 p = float2(uv.x * 0.70 + 0.15, uv.y * 0.30 + 0.12);
    float2 near = ribbon(p, time, 11.7, 0.245, -0.070, 0.17);
    float2 far = ribbon(p, time * 0.8, 3.1, 0.130, 0.030, 0.12);
    float light = near.x + far.x * 0.40;
    float a = (1.0 - exp(-light * 1.2)) * 0.46 * (1.0 - smoothstep(0.35, 1.0, uv.y));
    return half4(tint(light, near.y) * a, a);
}
"""

/**
 * Protection starting, felt: a ribbon of the sky's own light climbs the whole screen and fades, once each time
 * [play] turns true. Draws over the page and takes no touches. No sweep with reduced motion or before Android 13
 * (the hero hand-over still says it).
 */
@Composable
fun AuroraSweep(play: Boolean, modifier: Modifier = Modifier) {
    if (Aurora.reducedMotion || Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
    val p = remember { Animatable(1f) }
    LaunchedEffect(play) { if (play) { p.snapTo(0f); p.animateTo(1f, tween(SweepMs, easing = LinearEasing)) } }
    SweepCanvas({ p.value }, modifier)
}

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
@Composable
private fun SweepCanvas(progress: () -> Float, modifier: Modifier) {
    val shader = remember { RuntimeShader(SweepSource) }
    val brush = remember(shader) { ShaderBrush(shader) }
    val colors = Aurora.colors
    Box(modifier.clearAndSetSemantics { }) { Canvas(Modifier.softLight()) {
        val t = progress()
        if (t >= 1f) return@Canvas
        shader.setFloatUniform("size", size.width, size.height)
        shader.setFloatUniform("p", Motion.Ease.transform(t))
        shader.setFloatUniform("fade", kotlin.math.sin(Math.PI * t).toFloat())
        shader.setRibbonColors(colors)
        drawRect(brush, blendMode = BlendMode.Screen)
    } }
}

private const val SweepMs = 1900

private const val SweepSource = """
uniform float2 size;
uniform float p;
uniform float fade;

$NoiseSource
$RibbonSource

half4 main(float2 c) {
    float2 uv = c / size;
    float y = (uv.y - mix(1.18, -0.30, p)) * size.y / size.x * 0.58 + 0.245;
    float2 near = ribbon(float2(uv.x * 0.58 + 0.21, y), p * 60.0 + 260.0, 11.7, 0.245, -0.070, 0.26);
    float a = (1.0 - exp(-near.x * 1.3)) * 0.70 * fade;
    return half4(tint(near.x, near.y) * a, a);
}
"""
