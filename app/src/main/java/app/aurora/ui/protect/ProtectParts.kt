package app.aurora.ui.protect

import app.aurora.protect.partOf
import app.aurora.ui.components.Eyebrow
import app.aurora.ui.components.glass
import app.aurora.ui.components.RollingClock
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.aurora.AuroraApplication
import app.aurora.R
import app.aurora.data.formatClock
import app.aurora.protect.FrictionTask
import app.aurora.protect.Mode
import app.aurora.protect.ProtectionSession
import app.aurora.protect.WebPrefix
import app.aurora.ui.components.AuroraButton
import app.aurora.ui.components.AuroraIcons
import app.aurora.ui.components.ButtonKind
import app.aurora.ui.components.ModeGlyph
import app.aurora.ui.components.clockTime
import app.aurora.ui.theme.Aurora
import app.aurora.ui.theme.AuroraType
import app.aurora.ui.theme.Radius
import app.aurora.ui.theme.Space

/** Each mode's tone, from quiet (Flexible) to full ink (Strict). */
@Composable
fun modeColor(mode: Mode): Color {
    // Monochrome: modes differ by name and glyph; the strongest mode carries the most ink.
    return when (mode) {
        Mode.Flexible -> Aurora.colors.textTertiary
        Mode.Friction, Mode.Cooldown -> Aurora.colors.textSecondary
        Mode.AlwaysBlock -> Aurora.colors.accent
    }
}

/** Each mode's glyph, used on chips, tiles and the Shield. */
fun modeIcon(mode: Mode): androidx.compose.ui.graphics.vector.ImageVector = when (mode) {
    Mode.Flexible -> AuroraIcons.Clock
    Mode.Friction -> AuroraIcons.Spark
    Mode.Cooldown -> AuroraIcons.Hourglass
    Mode.AlwaysBlock -> AuroraIcons.Lock
}

/** A small outlined pill naming an item's mode, in the mode's colour. */
@Composable
fun ModeChip(mode: Mode, modifier: Modifier = Modifier, label: String = modeName(mode)) {
    val tint = modeColor(mode)
    Row(
        modifier.glass(Aurora.colors, Radius.Pill, tint = tint)
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(modeIcon(mode), null, tint = tint, modifier = Modifier.size(15.dp))
        Text(label, color = tint, style = AuroraType.Caption, maxLines = 1)
    }
}

@Composable
fun modeName(mode: Mode): String = stringResource(
    when (mode) {
        Mode.Flexible -> R.string.mode_flexible
        Mode.Friction -> R.string.mode_friction
        Mode.Cooldown -> R.string.mode_cooldown
        Mode.AlwaysBlock -> R.string.mode_always_block
    },
)

/** The founder-approved one-line description of each mode. */
@Composable
fun modeDescription(mode: Mode): String = stringResource(
    when (mode) {
        Mode.Flexible -> R.string.mode_flexible_desc
        Mode.Friction -> R.string.mode_friction_desc
        Mode.Cooldown -> R.string.mode_cooldown_desc
        Mode.AlwaysBlock -> R.string.mode_always_block_desc
    },
)

@Composable
fun taskName(task: FrictionTask): String = stringResource(
    when (task) {
        FrictionTask.Math, FrictionTask.Reflection -> R.string.friction_task_math
        FrictionTask.Intention -> R.string.friction_task_intention
    },
)

@Composable
fun taskDescription(task: FrictionTask): String = stringResource(
    when (task) {
        FrictionTask.Math, FrictionTask.Reflection -> R.string.friction_task_math_desc
        FrictionTask.Intention -> R.string.friction_task_intention_desc
    },
)

/** An app's real icon (a part of an app shows its app's), or a site's globe, at a consistent size and corner. */
@Composable
fun ItemIcon(key: String, data: ProtectData?, modifier: Modifier = Modifier, size: Dp = 40.dp) {
    val colors = Aurora.colors
    val app = LocalContext.current.applicationContext as AuroraApplication
    val shape = RoundedCornerShape(size * 0.28f)
    if (key.startsWith(WebPrefix)) {
        Box(modifier.size(size).clip(shape).background(colors.accentSoft), contentAlignment = Alignment.Center) {
            Icon(AuroraIcons.Globe, null, tint = colors.accent, modifier = Modifier.size(size * 0.55f))
        }
        return
    }
    val pkg = partOf(key)?.owner ?: key
    val icon by produceState<ImageBitmap?>(data?.apps?.get(pkg)?.icon, pkg, data?.apps?.containsKey(pkg)) {
        if (value == null) value = app.catalog.app(pkg)?.icon
    }
    val bitmap = icon
    if (bitmap != null) Image(bitmap, null, modifier.size(size).clip(shape))
    else Box(modifier.size(size).clip(shape).background(colors.elevated))
}

/** Overlapping icons for a set of items, at most [max] shown. */
@Composable
fun IconStack(keys: List<String>, data: ProtectData?, max: Int = 5, size: Dp = 32.dp) {
    val colors = Aurora.colors
    Row {
        keys.take(max).forEachIndexed { i, key ->
            Box(Modifier.offset(x = (-8).dp * i).border(2.dp, colors.surface, RoundedCornerShape(size * 0.3f))) { ItemIcon(key, data, size = size) }
        }
        if (keys.size > max) {
            Text("+${keys.size - max}", Modifier.offset(x = (-8).dp * max + 12.dp).align(Alignment.CenterVertically), color = colors.textSecondary, style = AuroraType.Caption)
        }
    }
}

/**
 * A running protection session: its mode, its apps, the time left in large type, and the one action
 * the mode allows. Flexible, Friction and Cooldown can be turned off; Strict says it ends on its own.
 */
@Composable
fun SessionCard(session: ProtectionSession, data: ProtectData, onTurnOff: () -> Unit, modifier: Modifier = Modifier) {
    val colors = Aurora.colors
    val tint = modeColor(session.mode)
    val left = session.time.remaining(data.time)
    val names = session.keys.map { data.label(it) }.filter { it.isNotBlank() }
    Column(
        modifier
            .padding(horizontal = Space.Gutter)
            .fillMaxWidth()
            .glass(colors, Radius.L)
            .padding(Space.L),
        verticalArrangement = Arrangement.spacedBy(Space.S),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.S)) {
            ModeGlyph(tint, size = 14)
            Eyebrow(modeName(session.mode), color = tint)
            Box(Modifier.weight(1f))
            IconStack(session.keys.toList(), data)
        }
        RollingClock(formatClock(left), colors.textPrimary, AuroraType.Hero)
        Text(
            stringResource(R.string.session_ends_at, clockTime(session.time.endWall(data.time))) + if (names.isNotEmpty()) " · " + names.joinToString(", ") else "",
            color = colors.textSecondary,
            style = AuroraType.Secondary,
        )
        if (session.cancellable) {
            AuroraButton(stringResource(R.string.session_turn_off), onTurnOff, Modifier.fillMaxWidth().padding(top = Space.XS), kind = ButtonKind.Secondary)
        } else {
            Row(
                Modifier.padding(top = Space.XS).fillMaxWidth().clip(Radius.Pill).background(colors.mutedSoft).padding(horizontal = Space.M, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Space.XS),
            ) {
                Icon(AuroraIcons.Lock, null, tint = colors.mutedText, modifier = Modifier.size(18.dp))
                Text(stringResource(R.string.session_ends_automatically), color = colors.mutedText, style = AuroraType.Label)
            }
        }
    }
}
