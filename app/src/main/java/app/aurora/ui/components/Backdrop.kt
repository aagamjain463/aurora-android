package app.aurora.ui.components

import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.aurora.ui.theme.AuroraColors
import app.aurora.ui.theme.Radius

/**
 * How a screen opens. Stage for Focus and the Shield, Immersive for Home, Balanced for the other tabs; screens
 * with a tier open with their own header instead of a top bar. Configuration screens carry none.
 */
enum class ArtTier(val height: Dp) { Stage(560.dp), Immersive(480.dp), Balanced(380.dp) }

/** Aurora's container surface: [glass]. */
@Suppress("UNUSED_PARAMETER")
fun Modifier.auroraSurface(colors: AuroraColors, shape: Shape = Radius.L, edge: Color? = null, warm: Boolean = false): Modifier =
    // Every card is flat, exactly like Time Reclaimed: the mode shows in its chip, never in the card's fill or edge.
    // ponytail: [edge]/[warm] kept so callers compile; drop them when those call sites are next touched.
    glass(colors, shape)
