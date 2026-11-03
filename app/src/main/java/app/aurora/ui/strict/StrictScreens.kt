package app.aurora.ui.strict

import app.aurora.ui.components.FocusDial
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
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
import app.aurora.ui.components.AuroraIcons
import app.aurora.ui.components.AuroraScreen
import app.aurora.ui.components.ButtonKind
import app.aurora.ui.components.FactRow
import app.aurora.ui.components.LitBadge
import app.aurora.ui.components.RowDivider
import app.aurora.ui.components.RowGroup
import app.aurora.ui.components.SectionLabel
import app.aurora.ui.components.Tag
import app.aurora.ui.components.auroraSurface
import app.aurora.ui.protect.ProtectModel
import app.aurora.ui.theme.Aurora
import app.aurora.ui.theme.AuroraType
import app.aurora.ui.theme.Space

/** What Strict, Cooldown and Friction lock while they run (docs/CONFIGURATIONS.md "Strict protection"). Only Flexible can be undone. */
private val StrictLocks = listOf(
    Triple(AuroraIcons.Close, R.string.strict_cancel, R.string.strict_cancel_body),
    Triple(AuroraIcons.Layers, R.string.strict_weaker, R.string.strict_weaker_body),
    Triple(AuroraIcons.Settings, R.string.strict_settings, R.string.strict_settings_body),
    Triple(AuroraIcons.Trash, R.string.strict_uninstall, R.string.strict_uninstall_body),
    Triple(AuroraIcons.Play, R.string.strict_restart, R.string.strict_restart_body),
    Triple(AuroraIcons.Clock, R.string.strict_clock, R.string.strict_clock_body),
)

@Composable
fun StrictModeScreen(onBack: () -> Unit) {
    val colors = Aurora.colors
    AuroraScreen(
        title = stringResource(R.string.strict_title),
        onBack = onBack,
        header = {
            Column(
                Modifier.fillMaxWidth().padding(horizontal = Space.Gutter).padding(top = Space.S, bottom = Space.S),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                LitBadge(AuroraIcons.Lock, size = 64)
                Spacer(Modifier.height(Space.M))
                Text(stringResource(R.string.strict_title), Modifier.semantics { heading() }, color = colors.textPrimary, style = AuroraType.Display, textAlign = TextAlign.Center)
                Text(
                    stringResource(R.string.strict_body), Modifier.padding(top = Space.XS, start = Space.S, end = Space.S),
                    color = colors.textSecondary, style = AuroraType.Secondary, textAlign = TextAlign.Center,
                )
            }
        },
    ) {
        item(key = "locks") {
            SectionLabel(stringResource(R.string.strict_while_it_runs))
            RowGroup {
                StrictLocks.forEachIndexed { i, (icon, title, body) ->
                    if (i > 0) RowDivider(68.dp)
                    FactRow(icon, stringResource(title), Modifier.padding(horizontal = 18.dp, vertical = 14.dp), body = stringResource(body))
                }
            }
        }
        item(key = "admin") { UninstallProtection(Modifier.padding(horizontal = Space.Gutter).padding(top = Space.L)) }
        item(key = "limits") {
            Text(
                stringResource(R.string.strict_limits), Modifier.fillMaxWidth().padding(horizontal = Space.XL, vertical = Space.L),
                color = colors.textTertiary, style = AuroraType.Caption, textAlign = TextAlign.Center,
            )
        }
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
    Column(modifier.fillMaxWidth().auroraSurface(colors, warm = active).padding(20.dp), verticalArrangement = Arrangement.spacedBy(Space.M)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            LitBadge(AuroraIcons.Protect, lit = active, size = 44)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(stringResource(R.string.strict_admin_title), color = colors.textPrimary, style = AuroraType.Title)
                Text(stringResource(if (active) R.string.strict_admin_on else R.string.strict_admin_off), color = colors.textSecondary, style = AuroraType.Secondary)
            }
            if (active) Tag(stringResource(R.string.strict_admin_tag_on), accent = true)
        }
        when {
            !active -> AuroraButton(stringResource(R.string.strict_admin_enable), { runCatching { context.startActivity(UninstallGuard.enableIntent(context)) } }, Modifier.fillMaxWidth())
            strictNow -> Text(stringResource(R.string.strict_admin_locked), color = colors.textTertiary, style = AuroraType.Caption)
            else -> AuroraButton(stringResource(R.string.strict_admin_disable), { UninstallGuard.disable(context); active = false }, Modifier.fillMaxWidth(), kind = ButtonKind.Quiet)
        }
    }
}

/**
 * Focus inside a running configuration: the goal, if one was typed, and the timer. Nothing else.
 * The configuration's protection keeps running either way.
 */
@Composable
fun FocusRunScreen(runId: String, onBack: () -> Unit) {
    val vm = auroraViewModel { ProtectModel(it) }
    val data by vm.data.collectAsStateWithLifecycle()
    val d = data
    val run = d?.let { vm.app.runsNow(it.time).find { r -> r.id == runId } }
    val focus = run?.focus
    // An empty header: no title, no session name.
    AuroraScreen(title = "", onBack = onBack, art = ArtTier.Immersive, header = {}) {
        if (d == null || run == null || focus == null) return@AuroraScreen
        val end = run.endAt(d.time)
        val start = run.startAt(d.time)
        val target = focus.minutes?.let { it * 60_000L } ?: ((end ?: d.time.wall) - start)
        val elapsed = (d.time.wall - start).coerceIn(0, target.coerceAtLeast(1))
        item(key = "focus") {
            Column(
                Modifier.fillParentMaxHeight(0.8f).fillMaxWidth().padding(horizontal = Space.Gutter),
                verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                focus.goal.takeIf { it.isNotBlank() }?.let { app.aurora.ui.components.FocusGoal(it, Modifier.padding(bottom = Space.XL)) }
                FocusDial(
                    elapsed.toFloat() / target.coerceAtLeast(1), formatClock(target - elapsed), Modifier.fillMaxWidth(0.92f),
                    spoken = stringResource(R.string.focus_spoken_remaining, app.aurora.data.spokenDuration(target - elapsed)),
                )
            }
        }
    }
}
