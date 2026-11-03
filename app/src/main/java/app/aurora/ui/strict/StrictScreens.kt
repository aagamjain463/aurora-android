package app.aurora.ui.strict

import app.aurora.ui.components.FocusDial
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.aurora.AuroraApplication
import app.aurora.R
import app.aurora.auroraViewModel
import app.aurora.data.formatClock
import app.aurora.protect.UninstallGuard
import app.aurora.protect.lockedUntil
import app.aurora.runsNow
import app.aurora.ui.components.ArtTier
import app.aurora.ui.components.AuroraButton
import app.aurora.ui.components.AuroraScreen
import app.aurora.ui.components.ButtonKind
import app.aurora.ui.components.SectionLabel
import app.aurora.ui.components.Tag
import app.aurora.ui.components.auroraSurface
import app.aurora.ui.components.clockTime
import app.aurora.ui.components.minutesText
import app.aurora.ui.protect.ProtectModel
import app.aurora.ui.theme.Aurora
import app.aurora.ui.theme.AuroraType
import app.aurora.ui.theme.Space
import kotlinx.coroutines.launch

/** Whether a lock works on its own, or needs uninstall protection turned on (docs/CONFIGURATIONS.md "Strict protection"). */
enum class Capability { Works, Setup }

/** What Strict, Cooldown and Friction lock while they run. Only Flexible can be undone. */
val StrictCapabilities = listOf(
    Triple(R.string.strict_cancel, R.string.strict_cancel_body, Capability.Works),
    Triple(R.string.strict_weaker, R.string.strict_weaker_body, Capability.Works),
    Triple(R.string.strict_settings, R.string.strict_settings_body, Capability.Works),
    Triple(R.string.strict_uninstall, R.string.strict_uninstall_body, Capability.Setup),
    Triple(R.string.strict_restart, R.string.strict_restart_body, Capability.Works),
    Triple(R.string.strict_clock, R.string.strict_clock_body, Capability.Works),
)

@Composable
fun StrictModeScreen(onBack: () -> Unit) {
    val colors = Aurora.colors
    AuroraScreen(title = stringResource(R.string.strict_title), subtitle = stringResource(R.string.strict_body), onBack = onBack) {
        items(StrictCapabilities, key = { it.first }) { (title, body, cap) ->
            Column(
                Modifier.padding(horizontal = Space.Gutter).padding(bottom = Space.S).fillMaxWidth().auroraSurface(colors, warm = cap == Capability.Works).padding(horizontal = 22.dp, vertical = 20.dp),
                verticalArrangement = Arrangement.spacedBy(Space.XS),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.S)) {
                    Text(stringResource(title), Modifier.weight(1f), color = colors.textPrimary, style = AuroraType.Title)
                    Tag(stringResource(if (cap == Capability.Works) R.string.strict_works else R.string.strict_setup), accent = cap == Capability.Works)
                }
                Text(stringResource(body), color = colors.textSecondary, style = AuroraType.Secondary)
            }
        }
        item(key = "admin") { UninstallProtection(Modifier.padding(horizontal = Space.Gutter)) }
        item(key = "limits") { Text(stringResource(R.string.strict_limits), Modifier.padding(Space.Gutter), color = colors.textTertiary, style = AuroraType.Caption) }
    }
}

/**
 * Uninstall protection (UninstallGuard): turned on through Android's own screen, before a commitment
 * starts (Settings, where that screen lives, is locked during one). Turned off here only while nothing
 * strict is running.
 */
@Composable
fun UninstallProtection(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val app = context.applicationContext as AuroraApplication
    val colors = Aurora.colors
    var active by remember { mutableStateOf(UninstallGuard.active(context)) }
    LifecycleResumeEffect(Unit) {
        active = UninstallGuard.active(context)
        onPauseOrDispose {}
    }
    // Read so a commitment starting or ending recomposes this.
    app.runtime.state.collectAsStateWithLifecycle().value
    val strictNow = app.policySnapshot(emptySet(), emptyMap())?.lockedUntil(app.now().wall) != null
    Column(modifier.fillMaxWidth().auroraSurface(colors, warm = active).padding(horizontal = 22.dp, vertical = 20.dp), verticalArrangement = Arrangement.spacedBy(Space.XS)) {
        Text(stringResource(R.string.strict_admin_title), color = colors.textPrimary, style = AuroraType.Title)
        Text(stringResource(if (active) R.string.strict_admin_on else R.string.strict_admin_off), color = colors.textSecondary, style = AuroraType.Secondary)
        when {
            !active -> AuroraButton(stringResource(R.string.strict_admin_enable), { runCatching { context.startActivity(UninstallGuard.enableIntent(context)) } }, Modifier.fillMaxWidth())
            strictNow -> Text(stringResource(R.string.strict_admin_locked), color = colors.textTertiary, style = AuroraType.Caption)
            else -> AuroraButton(stringResource(R.string.strict_admin_disable), { UninstallGuard.disable(context); active = false }, Modifier.fillMaxWidth(), kind = ButtonKind.Secondary)
        }
    }
}

/**
 * Focus inside a running configuration: the task, the light ring over its target, and the person's own
 * word on how it went. The configuration's protection keeps running either way.
 */
@Composable
fun FocusRunScreen(runId: String, onBack: () -> Unit) {
    val vm = auroraViewModel { ProtectModel(it) }
    val data by vm.data.collectAsStateWithLifecycle()
    val colors = Aurora.colors
    val d = data
    val run = d?.let { vm.app.runsNow(it.time).find { r -> r.id == runId } }
    val focus = run?.focus
    AuroraScreen(
        title = focus?.task.orEmpty(), onBack = onBack, art = ArtTier.Immersive,
        // Centred and quiet: the session it belongs to, then the task in the serif.
        header = {
            Column(Modifier.fillMaxWidth().padding(horizontal = Space.Gutter).padding(top = Space.M), horizontalAlignment = Alignment.CenterHorizontally) {
                run?.name?.let { app.aurora.ui.components.Eyebrow(it, textAlign = androidx.compose.ui.text.style.TextAlign.Center) }
                Text(
                    focus?.task.orEmpty(), Modifier.padding(top = Space.XS), color = colors.headlineLead, style = AuroraType.DisplayS,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center, maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
            }
        },
    ) {
        if (d == null || run == null || focus == null) return@AuroraScreen
        val end = run.endAt(d.time)
        val start = run.startAt(d.time)
        val target = focus.minutes?.let { it * 60_000L } ?: ((end ?: d.time.wall) - start)
        val elapsed = (d.time.wall - start).coerceIn(0, target.coerceAtLeast(1))
        item(key = "ring") {
            Column(Modifier.fillMaxWidth().padding(top = Space.L), horizontalAlignment = Alignment.CenterHorizontally) {
                FocusDial(
                    elapsed.toFloat() / target.coerceAtLeast(1), formatClock(target - elapsed), Modifier.fillMaxWidth(0.86f),
                    label = stringResource(R.string.focus_remaining),
                    caption = stringResource(R.string.focusrun_target, minutesText((target / 60_000).toInt())),
                    spoken = stringResource(R.string.focus_spoken_remaining, app.aurora.data.spokenDuration(target - elapsed)),
                )
            }
        }
        item(key = "words") {
            Column(
                Modifier.fillMaxWidth().padding(horizontal = Space.Gutter).padding(top = Space.XL),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Space.S),
            ) {
                if (focus.goal.isNotBlank()) Text(focus.goal, color = colors.textPrimary, style = AuroraType.BodyStrong, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                if (focus.description.isNotBlank()) Text(focus.description, color = colors.textSecondary, style = AuroraType.Body, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                end?.let { Text(stringResource(R.string.focusrun_protection, clockTime(it)), Modifier.padding(top = Space.XS), color = colors.textTertiary, style = AuroraType.Caption) }
            }
        }
    }
}


