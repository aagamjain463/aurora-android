package app.aurora.ui.protect

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

/** Each mode's colour: green for Flexible, blue for Friction, dawn for Cooldown, ember for Always Block. */
@Composable
fun modeColor(mode: Mode): Color {
    val dark = Aurora.colors.isDark
    return when (mode) {
        Mode.Flexible -> Aurora.colors.accent
        Mode.Friction -> if (dark) Color(0xFF9DB8FF) else Color(0xFF3557B7)
        Mode.Cooldown -> if (dark) Color(0xFFF0B48A) else Color(0xFFA6532A)
        Mode.AlwaysBlock -> if (dark) Color(0xFFFF9B87) else Color(0xFFB13B27)
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
        FrictionTask.Math -> R.string.friction_task_math
        FrictionTask.Intention -> R.string.friction_task_intention
        FrictionTask.Reflection -> R.string.friction_task_reflection
    },
)

@Composable
fun taskDescription(task: FrictionTask): String = stringResource(
    when (task) {
        FrictionTask.Math -> R.string.friction_task_math_desc
        FrictionTask.Intention -> R.string.friction_task_intention_desc
        FrictionTask.Reflection -> R.string.friction_task_reflection_desc
    },
)

/** An app's real icon, or a site's globe, at a consistent size and corner. */
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
    val icon by produceState<ImageBitmap?>(data?.apps?.get(key)?.icon, key, data?.apps?.containsKey(key)) {
        if (value == null) value = app.catalog.app(key)?.icon
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
 * the mode allows. Flexible, Friction and Cooldown can be turned off; Always Block says it ends on its own.
 */
@Composable
fun SessionCard(session: ProtectionSession, data: ProtectData, onTurnOff: () -> Unit, modifier: Modifier = Modifier) {
    val colors = Aurora.colors
    val tint = modeColor(session.mode)
    val left = session.time.remaining(data.time)
    val names = session.keys.map { data.label(it) }.filter { it.isNotBlank() }
    Column(
        modifier
            .padding(horizontal = Space.M)
            .fillMaxWidth()
            .clip(Radius.L)
            .background(colors.surface)
            .border(1.dp, tint.copy(alpha = 0.45f), Radius.L)
            .padding(Space.L),
        verticalArrangement = Arrangement.spacedBy(Space.S),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.S)) {
            ModeGlyph(tint, size = 14)
            Text(modeName(session.mode), color = tint, style = AuroraType.Eyebrow)
            Box(Modifier.weight(1f))
            IconStack(session.keys.toList(), data)
        }
        Text(
            formatClock(left),
            Modifier,
            color = colors.textPrimary,
            style = AuroraType.Hero,
        )
        Text(
            stringResource(R.string.session_ends_at, clockTime(session.time.endWall(data.time))) + if (names.isNotEmpty()) " · " + names.joinToString(", ") else "",
            color = colors.textSecondary,
            style = AuroraType.Secondary,
        )
        if (session.cancellable) {
            AuroraButton(stringResource(R.string.session_turn_off), onTurnOff, Modifier.fillMaxWidth().padding(top = Space.XS), kind = ButtonKind.Secondary)
        } else {
            Row(
                Modifier.padding(top = Space.XS).fillMaxWidth().clip(Radius.Pill).background(colors.dawnSoft).padding(horizontal = Space.M, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Space.XS),
            ) {
                Icon(AuroraIcons.Lock, null, tint = colors.dawnText, modifier = Modifier.size(18.dp))
                Text(stringResource(R.string.session_ends_automatically), color = colors.dawnText, style = AuroraType.Label)
            }
        }
    }
}
