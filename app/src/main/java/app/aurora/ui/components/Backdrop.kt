package app.aurora.ui.components

import android.os.Build
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.getValue
import androidx.compose.runtime.Composable
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.semantics.clearAndSetSemantics
import app.aurora.ui.theme.Motion
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.BiasAlignment
import app.aurora.R
import app.aurora.ui.theme.Aurora
import app.aurora.ui.theme.Radius

/**
 * How much sky a screen carries (brand bible: Artwork). Stage for Focus and the Shield, Immersive for Home,
 * Balanced for the other tabs. Configuration screens carry none: only the faint [TopAurora].
 */
enum class ArtTier(val height: Dp) { Stage(560.dp), Immersive(480.dp), Balanced(380.dp) }

/**
 * The polar night behind the top of a screen, with the northern lights live in it. Art only; every label,
 * number and control sits on top in Compose. [scroll] is the list's scroll offset in px: the sky drifts at half
 * speed (parallax), or moves with the content when motion is reduced. [sky] and [glow] drive the light
 * ([AuroraSky]). [arc] is time passing, 0 to 1: the sky lights across, along a thread on the horizon.
 */
@Composable
fun AuroraBackdrop(
    tier: ArtTier,
    modifier: Modifier = Modifier,
    sky: Sky = Sky.Quiet,
    glow: Float = 0f,
    arc: Float? = null,
    scroll: () -> Int = { 0 },
) {
    val colors = Aurora.colors
    val reduced = Aurora.reducedMotion
    val alignment = BiasAlignment(0f, if (tier == ArtTier.Balanced) 0f else -0.2f)
    val timed by animateFloatAsState(if (arc != null) 1f else 0f, tween(Motion.Reveal, easing = Motion.Ease), label = "timed")
    val p by rememberFill((arc ?: 0f).coerceIn(0f, 1f), tween(1000, easing = LinearEasing))
    Box(
        modifier.fillMaxWidth().height(tier.height).graphicsLayer {
            val s = scroll().toFloat()
            translationY = -(if (reduced) s else s * 0.5f)
            alpha = (1f - s / (tier.height.toPx() * 1.2f)).coerceIn(0f, 1f)
        },
    ) {
        val live = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
        Image(
            painterResource(if (live) R.drawable.aurora_night else R.drawable.aurora_still),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
            alignment = alignment,
        )
        val height = with(androidx.compose.ui.platform.LocalDensity.current) { tier.height.toPx() }
        AuroraSky(sky, glow, alignment, Modifier.fillMaxSize(), lit = { if (arc == null) -1f else p }, visible = { scroll() < height * 1.2f })
        // Keeps the status bar and the header legible, then lets the art fall into the page.
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    0f to colors.background.copy(alpha = 0.26f),
                    0.11f to Color.Transparent,
                    0.62f to Color.Transparent,
                    0.84f to colors.background.copy(alpha = 0.62f),
                    1f to colors.background,
                ),
            ),
        )
        // Time passing: the opening's thread, drawn along the horizon as far as the time has gone.
        Canvas(Modifier.fillMaxSize().clearAndSetSemantics { }) {
            if (timed <= 0f) return@Canvas
            val scale = maxOf(size.width / ArtWidth, size.height / ArtHeight)
            val h = ArtHeight * scale
            val y = (size.height - h) / 2f * (1f + alignment.verticalBias) + h * Horizon
            val end = size.width * p
            drawLine(colors.accent.copy(alpha = 0.26f * timed), Offset(0f, y), Offset(end, y), strokeWidth = 7.dp.toPx(), cap = StrokeCap.Round, blendMode = BlendMode.Screen)
            drawLine(colors.light.copy(alpha = timed), Offset(0f, y), Offset(end, y), strokeWidth = 1.5.dp.toPx(), cap = StrokeCap.Round)
            val halo = 16.dp.toPx()
            drawCircle(Brush.radialGradient(listOf(colors.accent.copy(alpha = 0.6f * timed), Color.Transparent), Offset(end, y), halo), halo, Offset(end, y), blendMode = BlendMode.Screen)
        }
    }
}

private const val ArtWidth = 1440f
private const val ArtHeight = 1324f
private const val Horizon = 0.44f

/** Aurora's container surface: [glass]. [edge] tints it (a mode colour); [warm] tints it rose. */
fun Modifier.auroraSurface(colors: app.aurora.ui.theme.AuroraColors, shape: Shape = Radius.L, edge: Color? = null, warm: Boolean = false): Modifier =
    glass(colors, shape, tint = edge ?: if (warm) colors.rose else null)
