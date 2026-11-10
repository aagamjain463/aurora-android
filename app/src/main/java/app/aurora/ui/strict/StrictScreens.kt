package app.aurora.ui.strict

import app.aurora.ui.components.RollingClock
import androidx.compose.ui.unit.sp
import app.aurora.ui.focus.FocusLevelRunning
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import app.aurora.ui.protect.rememberUninstallRequest
import androidx.compose.foundation.layout.size

import app.aurora.ui.components.FocusTimer
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.clip
import app.aurora.ui.components.pressable
import app.aurora.ui.components.glassControl
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
import app.aurora.endUnlock
import app.aurora.ui.components.ArtTier
import app.aurora.ui.components.AuroraButton
import app.aurora.ui.components.AuroraIcons
import app.aurora.ui.components.AuroraScreen
import app.aurora.ui.components.ButtonKind
import app.aurora.ui.components.FactRow
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
                // The commitment, held in the seal.
                app.aurora.ui.components.Seal(64.dp) { androidx.compose.material3.Icon(AuroraIcons.Lock, null, tint = colors.textPrimary, modifier = Modifier.size(28.dp)) }
                Spacer(Modifier.height(Space.L))
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
                    if (i > 0) RowDivider(18.dp)
                    FactRow(icon, stringResource(title), Modifier.padding(horizontal = Space.Inner, vertical = 14.dp), body = stringResource(body))
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
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(stringResource(R.string.strict_admin_title), color = colors.textPrimary, style = AuroraType.Title)
                Text(stringResource(if (active) R.string.strict_admin_on else R.string.strict_admin_off), color = colors.textSecondary, style = AuroraType.Secondary)
            }
            if (active) Tag(stringResource(R.string.strict_admin_tag_on), accent = true)
        }
        when {
            !active -> AuroraButton(stringResource(R.string.strict_admin_enable), rememberUninstallRequest { runCatching { context.startActivity(UninstallGuard.enableIntent(context)) } }, Modifier.fillMaxWidth())
            strictNow -> Text(stringResource(R.string.strict_admin_locked), color = colors.textTertiary, style = AuroraType.Caption)
            else -> AuroraButton(stringResource(R.string.strict_admin_disable), { UninstallGuard.disable(context); active = false }, Modifier.fillMaxWidth(), kind = ButtonKind.Quiet)
        }
    }
}

/**
 * Focus inside a running session: the goal, if one was typed, and the time, over the level (the time left as a solid
 * fill). When the target is reached the level has drained and the page says so. Protection keeps running. There is no
 * pause: Unlock is the way to a break. An unlock that stops the timer takes the page over with its own clock until what
 * it opened locks again.
 */
@Composable
fun FocusRunScreen(runId: String, onBack: () -> Unit) {
    val vm = auroraViewModel { ProtectModel(it) }
    val data by vm.data.collectAsStateWithLifecycle()
    val d = data
    val run = d?.let { vm.app.runsNow(it.time).find { r -> r.id == runId } }
    val focus = run?.focus
    val colors = Aurora.colors
    var choosing by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().background(colors.background).statusBarsPadding().navigationBarsPadding()) {
        androidx.compose.foundation.layout.Box(Modifier.fillMaxWidth().height(60.dp).padding(start = Space.M, top = Space.XS), contentAlignment = Alignment.CenterStart) {
            app.aurora.ui.components.AuroraIconButton(AuroraIcons.Back, stringResource(R.string.action_close), onBack)
        }
        if (focus == null || d == null || run == null) return@Column
        // The time reclaimed, counted forward from 0: it stands still through a pass that pauses it, never otherwise.
        val now = d.time.wall
        val used = d.runtime.unlocks[run.id].orEmpty()
        val unlocked = run.unlockedAt(used, now)
        val pass = unlocked?.let { u -> run.unlocks.find { it.id == u.unlockId } }
        val paused = unlocked != null && !unlocked.focusRuns
        val focused = run.focusedMs(d.time, used)
        val end = run.endAt(d.time)
        val target = (focus.minutes?.let { it * 60_000L } ?: ((end ?: now) - run.startAt(d.time))).coerceAtLeast(1)
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = Space.Gutter),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
        ) {
            // Only the goal: the one thing this time is for.
            Text(
                focus.goal.ifBlank { focus.task }, Modifier.semantics { heading() }, color = colors.textPrimary,
                style = AuroraType.DisplayS.copy(fontSize = 32.sp, lineHeight = 40.sp), textAlign = androidx.compose.ui.text.style.TextAlign.Center, maxLines = 3,
            )
            // The same ring as Home's, in the brand's white: it fills as the time reclaimed grows toward the session.
            androidx.compose.foundation.layout.BoxWithConstraints(Modifier.padding(top = 48.dp).fillMaxWidth(), contentAlignment = Alignment.Center) {
                val ring = minOf(maxWidth, 360.dp)
                androidx.compose.foundation.layout.Box(Modifier.size(ring), contentAlignment = Alignment.Center) {
                    app.aurora.ui.session.TimerRing((focused.toFloat() / target).coerceIn(0f, 1f), Modifier.fillMaxSize(), white = true)
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        val clock = formatClock(focused)
                        RollingClock(
                            clock, colors.textPrimary,
                            AuroraType.Timer.copy(fontSize = (if (clock.length > 5) 60 else 80).sp, lineHeight = (if (clock.length > 5) 68 else 88).sp),
                            spoken = app.aurora.data.spokenDuration(focused),
                        )
                        if (paused && pass != null) {
                            Text(
                                stringResource(R.string.focus_paused, pass.name),
                                Modifier.padding(top = 4.dp), color = colors.plus, style = AuroraType.Secondary,
                            )
                        }
                    }
                }
            }
        }
        Column(Modifier.fillMaxWidth().padding(horizontal = Space.Gutter).padding(top = Space.S, bottom = Space.L), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Space.S)) {
            // The session's own clock, which a pass never stops.
            if (end != null) Text(stringResource(R.string.session_time_left, formatClock((end - now).coerceAtLeast(0))), color = colors.textTertiary, style = AuroraType.Caption)
            val left = run.unlocks.sumOf { run.left(it, used) }
            // One quiet pill, as Home's controls are: how many are left is said in the chooser.
            when {
                unlocked != null -> FocusPill(AuroraIcons.Lock, stringResource(R.string.task_unlock_end_short), lit = true) { vm.run { vm.app.endUnlock(run.id) } }
                run.unlocks.isNotEmpty() -> FocusPill(AuroraIcons.Unlock, stringResource(R.string.task_unlock_choose_title), enabled = left > 0) { choosing = true }
            }
        }
    }
    if (choosing && d != null && run != null) app.aurora.ui.session.UnlockChooser(run, d, vm.app) { choosing = false }
}

/** The Focus timer's one action: a glass pill with its glyph in a small disc; lit (the brand's white) to lock again. */
@Composable
private fun FocusPill(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, lit: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    val colors = Aurora.colors
    Row(
        Modifier.graphicsLayer { alpha = if (enabled) 1f else 0.4f }.clip(app.aurora.ui.theme.Radius.Pill)
            .then(if (enabled) Modifier.pressable(app.aurora.ui.theme.Radius.Pill, onClick = onClick) else Modifier)
            .glassControl(app.aurora.ui.theme.Radius.Pill).padding(start = 6.dp, end = 22.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        androidx.compose.foundation.layout.Box(
            Modifier.size(40.dp).clip(androidx.compose.foundation.shape.CircleShape)
                .then(if (lit) Modifier.background(colors.accent) else Modifier.glassControl(androidx.compose.foundation.shape.CircleShape)),
            contentAlignment = Alignment.Center,
        ) { androidx.compose.material3.Icon(icon, null, tint = if (lit) colors.onAccent else colors.textPrimary, modifier = Modifier.size(18.dp)) }
        Text(label, color = colors.textPrimary, style = AuroraType.Label)
    }
}
