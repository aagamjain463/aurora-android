package app.aurora.ui.components

import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.aurora.ui.theme.Aurora
import app.aurora.ui.theme.AuroraColors
import app.aurora.ui.theme.Palette
import app.aurora.ui.theme.Radius
import app.aurora.ui.theme.clipShape

/*
 * Aurora's material system. Solid surfaces communicate stability; glass communicates contextual depth.
 *
 * - Glass on the page ([glassCard] via [glass], [auroraSurface], [glassControl]): every card, row group, field,
 *   chip, icon well and button. Translucent fill, sheen, lit rim. The primary button keeps its solid fill + [glassShine].
 * - Glass / Subtle: the top bar once content scrolls under it.
 * - Glass controls ([glassControl]): secondary buttons, segmented tracks, round icon buttons.
 * - Glass / Standard: floating navigation (the dock) and pinned contextual actions (a flow's primary button).
 * - Glass / Elevated: dialogs and menus. Nearly opaque; the page behind is blurred by the window, not the card.
 *
 * Real backdrop blur needs API 31 (RenderEffect). A [Backdrop] records the content behind a pane into a
 * GraphicsLayer (a RenderNode, replayed rather than redrawn); the pane draws that node through a desaturated blur,
 * clipped to its shape, then its neutral tint on top. No per-frame capture: scrolling only moves child nodes.
 * Below API 31, on low-RAM phones, or when the person turned on high-contrast text, the same pane is drawn as a
 * near-opaque tint: still layered, never hard to read.
 */

enum class Material { Subtle, Standard, Elevated }

/**
 * One material's tuning for one theme. [tint]/[fallback] are the fill with and without blur; [rim] lights the
 * edge (bright where light would catch it, top-left, fading round); [sheen] is the soft light across the top.
 * [refraction] is how far the lens at the rim bends what is behind (0 = flat).
 */
@Immutable
data class MaterialSpec(
    val tint: Color,
    val fallback: Color,
    val edge: Color,
    val rimHigh: Color,
    val rimLow: Color,
    val sheen: Color,
    val blur: Dp,
    val refraction: Dp,
    val shadow: Color,
)

fun AuroraColors.material(level: Material): MaterialSpec = if (isDark) when (level) {
    Material.Subtle -> MaterialSpec(Palette.Obsidian.copy(alpha = 0.55f), Palette.Obsidian.copy(alpha = 0.94f), Palette.Porcelain.copy(alpha = 0.08f), Palette.Porcelain.copy(alpha = 0.18f), Palette.Porcelain.copy(alpha = 0.04f), Palette.Porcelain.copy(alpha = 0.04f), 18.dp, 0.dp, Color.Transparent)
    Material.Standard -> MaterialSpec(Palette.Graphite.copy(alpha = 0.46f), Palette.Graphite.copy(alpha = 0.92f), Palette.Porcelain.copy(alpha = 0.10f), Palette.Porcelain.copy(alpha = 0.55f), Palette.Porcelain.copy(alpha = 0.08f), Palette.Porcelain.copy(alpha = 0.10f), 12.dp, 14.dp, Color.Black.copy(alpha = 0.55f))
    Material.Elevated -> MaterialSpec(Palette.Graphite.copy(alpha = 0.70f), Palette.Graphite.copy(alpha = 0.97f), Palette.Porcelain.copy(alpha = 0.12f), Palette.Porcelain.copy(alpha = 0.50f), Palette.Porcelain.copy(alpha = 0.08f), Palette.Porcelain.copy(alpha = 0.08f), 30.dp, 0.dp, Color.Transparent)
} else when (level) {
    Material.Subtle -> MaterialSpec(Palette.Porcelain.copy(alpha = 0.60f), Palette.Porcelain.copy(alpha = 0.95f), Palette.Ink.copy(alpha = 0.07f), Palette.White, Palette.Ink.copy(alpha = 0.03f), Palette.White.copy(alpha = 0.55f), 18.dp, 0.dp, Color.Transparent)
    Material.Standard -> MaterialSpec(Palette.White.copy(alpha = 0.42f), Palette.White.copy(alpha = 0.90f), Palette.Ink.copy(alpha = 0.10f), Palette.White, Palette.Ink.copy(alpha = 0.05f), Palette.White.copy(alpha = 0.85f), 12.dp, 14.dp, Color.Black.copy(alpha = 0.18f))
    Material.Elevated -> MaterialSpec(Palette.White.copy(alpha = 0.74f), Palette.White.copy(alpha = 0.98f), Palette.Ink.copy(alpha = 0.08f), Palette.White, Palette.Ink.copy(alpha = 0.05f), Palette.White.copy(alpha = 0.7f), 30.dp, 0.dp, Color.Transparent)
}

/** Whether panes may blur what is behind them. Set once per theme from device capability and accessibility. */
val LocalGlassBlur = staticCompositionLocalOf { false }

fun glassBlurSupported(context: android.content.Context): Boolean =
    Build.VERSION.SDK_INT >= 31 &&
        // Robolectric draws in software, where RenderEffect is ignored: JVM screenshots show the fallback instead.
        Build.FINGERPRINT != "robolectric" &&
        !context.getSystemService(android.app.ActivityManager::class.java).isLowRamDevice &&
        // High-contrast text is Android's nearest to "reduce transparency": readability wins, so panes go opaque.
        android.provider.Settings.Secure.getInt(context.contentResolver, "high_text_contrast_enabled", 0) == 0

/** Content that glass panes may sit over. Created by [rememberBackdrop], recorded by [backdropSource]. */
@Stable
class Backdrop internal constructor(internal val layer: GraphicsLayer) {
    internal var coords: LayoutCoordinates? = null
}

/** A backdrop for this screen, or null where blur is off (the panes then draw their fallback tint). */
@Composable
fun rememberBackdrop(): Backdrop? {
    if (!LocalGlassBlur.current) return null
    val layer = rememberGraphicsLayer()
    return remember(layer) { Backdrop(layer) }
}

/**
 * Records this content so panes can blur it. A pane must never sit inside its own source: put the pane beside the
 * source, not in it, or the node would draw itself.
 */
fun Modifier.backdropSource(backdrop: Backdrop?): Modifier = if (backdrop == null) this else
    onGloballyPositioned { backdrop.coords = it }.drawWithContent {
        backdrop.layer.record { this@drawWithContent.drawContent() }
        drawLayer(backdrop.layer)
    }

/** Per-pane state: where it is, and the lens effect built for its current size. */
private class Pane {
    var coords: LayoutCoordinates? = null
    var effectKey: Long = 0
    var recordedAt = Offset.Unspecified
    var recordedSize = androidx.compose.ui.unit.IntSize.Zero
    var recordedPage = Color.Unspecified
    var recordedSource: Backdrop? = null
}

/**
 * A glass pane. Over a [backdrop] (API 31+): the content behind, softly blurred with a little extra life in its
 * tones, bent by a lens along the rim (API 33+, AGSL), under a light neutral tint; then a lit rim and a sheen across
 * the top, and for floating panes ([float]) a soft shadow that lifts it off the page. Without a backdrop it draws
 * its fallback tint with the same rim and sheen, so it still reads as glass and never hurts reading.
 */
@Composable
fun Modifier.material(level: Material, shape: Shape = Radius.L, backdrop: Backdrop? = null, float: Boolean = false, windowBlur: Boolean = false): Modifier {
    val colors = Aurora.colors
    val spec = colors.material(level)
    val blur = backdrop != null && Build.VERSION.SDK_INT >= 31
    val layer = if (blur) rememberGraphicsLayer() else null
    val density = LocalDensity.current
    val blurPx = with(density) { spec.blur.toPx() }
    val bendPx = with(density) { spec.refraction.toPx() }
    val pane = remember { Pane() }
    var m: Modifier = this
    if (float && spec.shadow != Color.Transparent) m = m.dropShadow(shape, androidx.compose.ui.graphics.shadow.Shadow(28.dp, spec.shadow, offset = androidx.compose.ui.unit.DpOffset(0.dp, 10.dp)))
    // A blurred backdrop must be cut to the exact curve; a plain fill draws the curve itself under a cheap clip.
    m = m.clip(if (layer != null) shape else shape.clipShape)
    if (layer != null && backdrop != null) m = m.onGloballyPositioned { pane.coords = it }.drawBehind {
        val source = backdrop.coords ?: return@drawBehind
        val me = pane.coords ?: return@drawBehind
        if (!source.isAttached || !me.isAttached) return@drawBehind
        val corner = when (shape) {
            is androidx.compose.foundation.shape.CornerBasedShape -> shape.topStart.toPx(size, this)
            is app.aurora.ui.theme.ContinuousShape -> shape.radius.toPx()
            else -> 0f
        }
        val key = (size.width.toInt().toLong() shl 32) or (size.height.toInt().toLong() shl 16) or corner.toInt().toLong()
        // The blur runs at a quarter of the pane's resolution and is drawn back up: a blurred picture loses nothing
        // to it, and the GPU blurs 16x fewer pixels every frame the page scrolls beneath the pane.
        val small = androidx.compose.ui.unit.IntSize(
            kotlin.math.ceil(size.width * BlurScale).toInt().coerceAtLeast(1), kotlin.math.ceil(size.height * BlurScale).toInt().coerceAtLeast(1),
        )
        if (pane.effectKey != key) {
            pane.effectKey = key
            layer.renderEffect = glassEffect(blurPx * BlurScale, bendPx * BlurScale, small.width.toFloat(), small.height.toFloat(), corner * BlurScale)
        }
        // ponytail: offset is read at draw time, so during the dock's 280ms slide-in the blur trails by a frame.
        val at = source.localPositionOf(me, Offset.Zero)
        // The page first: sources are often transparent between items, and the sharp content beneath the pane
        // must never show through next to its blurred copy.
        // Recorded again only when the pane moves or resizes. The page's own changes reach the blur through its
        // node; recording on every redraw of the pane (a tab's capsule animating) would make the GPU blur the
        // whole page again each frame although nothing behind the pane changed.
        if (at != pane.recordedAt || small != pane.recordedSize || colors.background != pane.recordedPage || backdrop !== pane.recordedSource) {
            pane.recordedSource = backdrop
            pane.recordedAt = at
            pane.recordedSize = small
            pane.recordedPage = colors.background
            layer.record(small) {
                drawRect(colors.background)
                scale(BlurScale, pivot = Offset.Zero) { translate(-at.x, -at.y) { drawLayer(backdrop.layer) } }
            }
        }
        scale(1f / BlurScale, pivot = Offset.Zero) { drawLayer(layer) }
    }
    // A dialog's window blurs the page itself, so its card can be see-through without a backdrop of its own.
    m = m.background(if (blur || windowBlur) spec.tint else spec.fallback, shape)
    return m.glassLight(spec, shape)
}

private const val BlurScale = 0.25f

/** The light on glass: a sheen across the top and a rim lit from the top-left, fading as it goes round. */
private fun Modifier.glassLight(spec: MaterialSpec, shape: Shape): Modifier = (
    if (spec.sheen == Color.Transparent) this
    else background(Brush.verticalGradient(0f to spec.sheen, 0.55f to Color.Transparent), shape)
).then(
    if (shape == RectangleShape) Modifier.drawBehind {
        drawRect(spec.edge, Offset(0f, size.height - 1.dp.toPx()), Size(size.width, 1.dp.toPx()))
    } else Modifier
        .border(1.dp, spec.edge, shape)
        .border(1.dp, Brush.linearGradient(0f to spec.rimHigh, 0.45f to spec.rimLow, 0.8f to spec.rimLow, 1f to spec.rimHigh.copy(alpha = spec.rimHigh.alpha * 0.45f)), shape),
)

/** The lit rim of any glass: bright where light catches the top-left, fading round, a little back at the far corner. */
fun AuroraColors.glassRim(strength: Float = 1f): Brush {
    if (!isDark) return Brush.linearGradient(0f to Palette.White, 0.35f to Palette.White.copy(alpha = 0.5f), 0.6f to Palette.Ink.copy(alpha = 0.06f), 1f to Palette.Ink.copy(alpha = 0.18f * strength))
    return Brush.linearGradient(0f to Palette.Porcelain.copy(alpha = 0.22f * strength), 0.35f to Palette.Porcelain.copy(alpha = 0.07f), 0.65f to Palette.Porcelain.copy(alpha = 0.03f), 1f to Palette.Porcelain.copy(alpha = 0.08f * strength))
}

/** Translucent glass fill for cards, controls and fields on the page. */
fun AuroraColors.glassFill(field: Boolean = false): Color = when {
    isDark && field -> Palette.Porcelain.copy(alpha = 0.09f)
    isDark -> Palette.Porcelain.copy(alpha = 0.075f)
    field -> Palette.White.copy(alpha = 0.5f)
    else -> Palette.White.copy(alpha = 0.6f)
}

/**
 * Glass on the page: every card, control, field, chip and icon well. A translucent neutral fill, a sheen across the
 * top, a fine neutral edge and the lit rim. Behind it there is only the page, so there is nothing to blur; floating
 * panes over content use [material]. [tint] marks a chosen state: a faint wash and a firmer edge.
 */
fun Modifier.glassCard(colors: AuroraColors, shape: Shape = Radius.L, tint: Color? = null, fill: Color? = null, edge: Boolean = true): Modifier {
    // One glass, two lights: by night porcelain highlights over black shade, by day white highlights over ink shade.
    // Light catches the top-left and the far corner falls into shade, a sheen across the top, a bevelled rim and a
    // shadow beneath. Day mirrors night (founder, 2026-11-09).
    val dark = colors.isDark
    val hi = if (dark) Palette.Porcelain else Palette.White
    val lo = if (dark) Color.Black else Palette.Ink
    val base = fill ?: colors.glassFill()
    val bevel = Brush.linearGradient(0f to hi.copy(alpha = if (dark) 0.045f else 0.35f), 0.5f to Color.Transparent, 1f to lo.copy(alpha = if (dark) 0.16f else 0.04f))
    val sheen = Brush.verticalGradient(0f to hi.copy(alpha = if (dark) 0.035f else 0.5f), 0.42f to Color.Transparent)
    val shadow = if (edge) androidx.compose.ui.graphics.shadow.Shadow(14.dp, lo.copy(alpha = if (dark) 0.55f else 0.10f), offset = androidx.compose.ui.unit.DpOffset(0.dp, 4.dp))
    else androidx.compose.ui.graphics.shadow.Shadow(32.dp, lo.copy(alpha = if (dark) 0.7f else 0.12f), offset = androidx.compose.ui.unit.DpOffset(0.dp, 12.dp))
    var m = dropShadow(shape, shadow).clip(shape).background(base)
    if (tint != null) m = m.background(tint.copy(alpha = 0.06f))
    m = m.background(bevel).drawBehind { drawRect(sheen) }
    // A quiet rim (founder, 2026-11-08): it should be felt, not read as an outline.
    m = m.border(1.dp, if (dark) Palette.Porcelain.copy(alpha = 0.03f) else Palette.Ink.copy(alpha = 0.04f), shape).border(1.dp, colors.glassRim(if (tint != null) 1.3f else 1f), shape)
    return if (tint != null) m.border(1.dp, tint.copy(alpha = 0.5f), shape) else m
}

/** Light alone, for surfaces that keep their own solid fill (the primary button): sheen and lit rim. */
fun Modifier.glassShine(colors: AuroraColors, shape: Shape): Modifier = (
    // Porcelain at night takes its shape from shade below; Obsidian by day from light above.
    if (colors.isDark) Brush.verticalGradient(0f to Color.White.copy(alpha = 0.55f), 0.45f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.12f))
    else Brush.verticalGradient(0f to Color.White.copy(alpha = 0.26f), 0.48f to Color.White.copy(alpha = 0.04f), 1f to Color.Transparent)
).let { b -> background(b, shape) }.border(
    1.dp,
    if (colors.isDark) Brush.linearGradient(0f to Color.White, 0.5f to Color.White.copy(alpha = 0.2f), 1f to Color.Black.copy(alpha = 0.16f))
    else Brush.linearGradient(0f to Color.White.copy(alpha = 0.6f), 0.5f to Color.White.copy(alpha = 0.08f), 1f to Color.White.copy(alpha = 0.18f)),
    shape,
)

/** Glass for controls on the page (secondary buttons, segmented tracks, round icon buttons). */
@Composable
fun Modifier.glassControl(shape: Shape): Modifier = glassCard(Aurora.colors, shape, fill = if (Aurora.colors.isDark) Palette.Porcelain.copy(alpha = 0.06f) else Palette.White.copy(alpha = 0.6f))

private const val LENS = """
uniform shader content;
uniform float2 size;
uniform float radius;
uniform float rim;
uniform float bend;
float box(float2 p, float2 b, float r) {
    float2 q = abs(p) - b + r;
    return min(max(q.x, q.y), 0.0) + length(max(q, 0.0)) - r;
}
half4 main(float2 xy) {
    float2 c = size * 0.5;
    float2 p = xy - c;
    float d = -box(p, c, radius);
    if (d <= 0.0 || d >= rim) return content.eval(xy);
    float t = 1.0 - d / rim;
    float e = 0.5;
    float2 n = normalize(float2(box(p + float2(e, 0.0), c, radius) - box(p - float2(e, 0.0), c, radius),
                                box(p + float2(0.0, e), c, radius) - box(p - float2(0.0, e), c, radius)));
    // Toward the edge the lens draws its picture from further in, as thick glass does.
    float2 at = xy - n * bend * t * t;
    at = clamp(at, float2(0.5), size - 0.5);
    half4 col = content.eval(at);
    // Glass is a touch brighter where it is thickest.
    return half4(col.rgb + half3(0.06 * t * t), col.a);
}
"""

/** Blur with a little vibrancy, then (API 33+) the lens along the rim. */
private fun glassEffect(blur: Float, bend: Float, w: Float, h: Float, corner: Float): androidx.compose.ui.graphics.RenderEffect? {
    if (Build.VERSION.SDK_INT < 31) return null
    val vivid = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(1.35f) })
    var effect = android.graphics.RenderEffect.createChainEffect(
        android.graphics.RenderEffect.createColorFilterEffect(vivid),
        android.graphics.RenderEffect.createBlurEffect(blur, blur, android.graphics.Shader.TileMode.CLAMP),
    )
    if (Build.VERSION.SDK_INT >= 33 && bend > 0f && w > 0f && h > 0f) {
        val lens = android.graphics.RuntimeShader(LENS)
        lens.setFloatUniform("size", w, h)
        lens.setFloatUniform("radius", corner.coerceAtMost(minOf(w, h) / 2f))
        lens.setFloatUniform("rim", minOf(bend * 1.6f, minOf(w, h) / 2f))
        lens.setFloatUniform("bend", bend)
        effect = android.graphics.RenderEffect.createChainEffect(android.graphics.RenderEffect.createRuntimeShaderEffect(lens, "content"), effect)
    }
    return effect.asComposeRenderEffect()
}

/** Aurora's card. Since the founder's glass direction (2026-11-07) every card is [glassCard]. */
fun Modifier.glass(colors: AuroraColors, shape: Shape = Radius.L, tint: Color? = null): Modifier =
    // Pills and circles (chips, status, avatars) keep their lit rim; cards don't.
    glassCard(colors, shape, tint, edge = shape == Radius.Pill || shape == androidx.compose.foundation.shape.CircleShape)
