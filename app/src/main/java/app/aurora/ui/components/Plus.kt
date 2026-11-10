package app.aurora.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import app.aurora.R
import app.aurora.ui.theme.Aurora
import app.aurora.ui.theme.AuroraType
import app.aurora.ui.theme.DarkColors
import app.aurora.ui.theme.LocalAuroraColors
import app.aurora.ui.theme.Palette
import app.aurora.ui.theme.Radius
import app.aurora.ui.theme.clipShape

/*
 * Aurora Plus's gold (founder, 2026-11-08). The only hue outside the logo, and only on Plus: its card, its badge, the
 * actions that lead to it. Champagne, lit from the top-left like the glass rim, never glowing.
 */

/** Gold as metal: pale where the light catches it, settling to a warm shade. For fills and gold type. */
val PlusGoldBrush: Brush = Brush.linearGradient(
    0f to Palette.PlusGoldPale, 0.45f to Palette.PlusGold, 1f to Palette.PlusGoldShade,
)

/** A solid gold fill with the same sheen the primary button has. Text on it is Obsidian. */
fun Modifier.plusFill(shape: Shape): Modifier = clip(shape.clipShape)
    .background(PlusGoldBrush, shape)
    .background(PlusSheen, shape)
    .border(1.dp, Brush.linearGradient(0f to Color.White.copy(alpha = 0.7f), 0.5f to Color.White.copy(alpha = 0.1f), 1f to Color.Black.copy(alpha = 0.12f)), shape)

private val PlusSheen = Brush.verticalGradient(0f to Color.White.copy(alpha = 0.35f), 0.5f to Color.Transparent)

/** A fine gold rim for Plus's glass: bright where the light catches the top-left, fading round. */
fun Modifier.plusRim(shape: Shape): Modifier = border(
    1.dp,
    Brush.linearGradient(
        0f to Palette.PlusGoldPale.copy(alpha = 0.75f), 0.4f to Palette.PlusGold.copy(alpha = 0.18f),
        0.8f to Palette.PlusGold.copy(alpha = 0.1f), 1f to Palette.PlusGold.copy(alpha = 0.4f),
    ),
    shape,
)

/** "PLUS" in Obsidian on a small gold pill. [text] replaces the word ("Your plan"). */
@Composable
fun PlusBadge(modifier: Modifier = Modifier, text: String = stringResource(R.string.plus_badge)) {
    Text(
        text.uppercase(),
        modifier.plusFill(Radius.Pill).padding(horizontal = 10.dp, vertical = 4.dp),
        color = Palette.Obsidian,
        style = AuroraType.Eyebrow.copy(fontSize = 10.5.sp, lineHeight = 14.sp, letterSpacing = 0.08.em),
    )
}

/**
 * Plus's own card: always set in night colours, so by day it is the one dark object on the paper and by night clear
 * glass. A warm gold sheen across its top and the gold rim mark it; nothing glows past its edge.
 */
@Composable
fun PlusSurface(modifier: Modifier = Modifier, shape: Shape = Radius.L, content: @Composable ColumnScope.() -> Unit) {
    val pageDark = Aurora.colors.isDark
    CompositionLocalProvider(LocalAuroraColors provides DarkColors) {
        Column(
            modifier
                .glassCard(DarkColors, shape, fill = if (pageDark) Palette.Porcelain.copy(alpha = 0.055f) else Palette.Obsidian.copy(alpha = 0.96f), edge = false)
                .drawBehind { drawRect(Brush.verticalGradient(0f to Palette.PlusGold.copy(alpha = 0.10f), 0.6f to Color.Transparent)) }
                .plusRim(shape),
            content = content,
        )
    }
}
