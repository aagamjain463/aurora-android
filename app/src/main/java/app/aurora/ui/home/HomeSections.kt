package app.aurora.ui.home

import app.aurora.ui.protect.askFor
import app.aurora.ui.components.glassShine
import app.aurora.ui.components.glassControl
import app.aurora.ui.components.glassRim
import app.aurora.ui.components.glassFill
import app.aurora.ui.components.glassCard
import app.aurora.readyToStart
import app.aurora.ui.components.Eyebrow
import app.aurora.ui.components.glass
import app.aurora.ui.components.RollingClock
import androidx.compose.foundation.background
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.text
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.aurora.AuroraApplication
import app.aurora.R
import app.aurora.account.isWaitingForServer
import app.aurora.data.Access
import app.aurora.data.AccessState
import app.aurora.data.ProtectionStatus
import app.aurora.data.formatClock
import app.aurora.data.openAccessSettings
import app.aurora.data.protectionStatus
import app.aurora.deleteActivation
import app.aurora.progress.Outcome
import app.aurora.progress.RunRecord
import app.aurora.protect.Activation
import app.aurora.protect.ConfigRun
import app.aurora.protect.EndSession
import app.aurora.protect.ShieldService
import app.aurora.protect.Timing
import app.aurora.protect.UsageConfig
import app.aurora.protect.shieldOf
import app.aurora.protect.strict
import app.aurora.protect.intentOf
import app.aurora.protect.entitledActivations
import app.aurora.protect.nextStart
import app.aurora.rememberAccess
import app.aurora.runsNow
import app.aurora.setActivationEnabled
import app.aurora.turnOff
import app.aurora.turnOffRun
import app.aurora.ui.components.AuroraButton
import app.aurora.ui.components.AuroraDialog
import app.aurora.ui.components.AuroraIcons
import app.aurora.ui.components.ButtonKind
import app.aurora.ui.components.LitBadge
import app.aurora.ui.components.ProgressLine
import app.aurora.ui.components.auroraSurface
import app.aurora.ui.components.clockTime
import app.aurora.ui.configure.timingText
import app.aurora.ui.protect.IconStack
import app.aurora.ui.protect.modeName
import app.aurora.ui.components.AuroraIconButton
import app.aurora.ui.protect.ModeChip
import app.aurora.ui.protect.ProtectData
import app.aurora.ui.protect.SessionCard
import app.aurora.ui.protect.ShieldStatusCard
import app.aurora.ui.protect.modeColor
import app.aurora.ui.theme.Aurora
import app.aurora.ui.theme.AuroraType
import app.aurora.ui.theme.Radius
import app.aurora.ui.theme.Space
import kotlinx.coroutines.launch
import app.aurora.saveConfig
import app.aurora.deleteConfig
import app.aurora.newId
import app.aurora.ui.library.ItemMenuItems
import app.aurora.ui.library.DetailsDialog
import app.aurora.ui.library.keyRows
import app.aurora.ui.protect.intentSummary
import java.time.ZoneId
import app.aurora.data.formatDuration
import app.aurora.ui.components.pressable
import app.aurora.ui.components.rise
import app.aurora.ui.components.BrandEyebrow
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.Spacer
import app.aurora.ui.theme.clipShape
import app.aurora.protect.modeOf
import app.aurora.protect.shownMode
import app.aurora.ui.session.SessionRow

/** The way in: Start a session, one full-width filled pill. It asks New or Saved. One session runs at a time. */
@Composable
fun ChooseCard(onStartSession: () -> Unit) {
    val colors = Aurora.colors
    Column(
        Modifier.rise().padding(horizontal = Space.Gutter).padding(top = 36.dp, bottom = Space.XS).fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 54.dp).pressable(Radius.Button, onClick = onStartSession).clip(Radius.Button.clipShape).background(colors.accent, Radius.Button).glassShine(colors, Radius.Button).padding(horizontal = Space.L),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            Icon(AuroraIcons.Play, null, tint = colors.onAccent, modifier = Modifier.size(18.dp))
            Spacer(Modifier.size(10.dp))
            Text(stringResource(R.string.home_start_session), color = colors.onAccent, style = AuroraType.Label)
        }
    }
}

/** Saved sessions, on their own screen: the only place they are listed. */
@Composable
fun SavedSessionsScreen(onBack: () -> Unit, onEdit: (String) -> Unit, onStart: (String) -> Unit, onNew: () -> Unit = {}) {
    val protect = app.aurora.auroraViewModel { app.aurora.ui.protect.ProtectModel(it) }
    val data by protect.data.collectAsStateWithLifecycle()
    val aurora = LocalContext.current.applicationContext as AuroraApplication
    val colors = Aurora.colors
    app.aurora.ui.components.AuroraScreen(title = stringResource(R.string.home_ready_title), onBack = onBack) {
        val d = data ?: return@AuroraScreen
        val running = aurora.runsNow(d.time).map { it.configId }
        val ready = d.rules.readyToStart(Int.MAX_VALUE).filterNot { it.id in running }
        item(key = "new") { Column(Modifier.padding(horizontal = Space.Gutter)) { AuroraButton(stringResource(R.string.saved_new_session), onNew, Modifier.fillMaxWidth(), kind = ButtonKind.Secondary, icon = AuroraIcons.Plus) } }
        if (ready.isEmpty()) item(key = "none") { Text(stringResource(R.string.saved_none_sessions), Modifier.padding(horizontal = Space.Gutter + 4.dp, vertical = Space.M), color = colors.textTertiary, style = AuroraType.Secondary) }
        item(key = "ready") { QuickStart(ready, d, aurora, false, onEdit, onStart) }
    }
}

/** The timed run that ends soonest: Home's hero counts it down. */
fun leadRun(d: ProtectData, app: AuroraApplication): ConfigRun? =
    app.runsNow(d.time).mapNotNull { r -> r.endAt(d.time)?.let { r to it } }.filter { it.second > d.time.wall }.minByOrNull { it.second }?.first

/** How far [run] has gone, 0 to 1: the point's place on the art's ring. */
fun runProgress(run: ConfigRun, d: ProtectData): Float? {
    val end = run.endAt(d.time) ?: return null
    val start = run.startAt(d.time)
    return ((d.time.wall - start).toFloat() / (end - start).coerceAtLeast(1)).coerceIn(0f, 1f)
}

/** Home's hero while protection runs: the time left, large, over the lake; the ring above shows how far along it is. */
@Composable
fun NowHero(run: ConfigRun, d: ProtectData, onOpenFocus: (String) -> Unit = {}) {
    val colors = Aurora.colors
    val end = run.endAt(d.time) ?: return
    val left = end - d.time.wall
    // Honest about the sky: without the Shield service this time is only a timer.
    val connected by ShieldService.connected.collectAsStateWithLifecycle()
    // The time is the hero: centred, as large as the width allows (a day-long clock steps down to fit), with air round it.
    Column(Modifier.rise().fillMaxWidth().padding(horizontal = Space.Gutter).padding(top = Space.S, bottom = Space.XL), horizontalAlignment = Alignment.CenterHorizontally) {
        BrandEyebrow(stringResource(if (connected) R.string.home_now_eyebrow else R.string.home_not_enforced))
        RollingClock(
            formatClock(left), colors.textPrimary, AuroraType.Timer.copy(fontSize = 96.sp, lineHeight = 104.sp),
            Modifier.fillMaxWidth().padding(top = Space.S),
            spoken = stringResource(R.string.home_now_spoken, app.aurora.data.spokenDuration(left)),
        )
        Text(
            stringResource(R.string.home_now_detail, run.name, clockTime(end)), Modifier.padding(top = Space.XS),
            color = colors.textSecondary, style = AuroraType.Body, textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        // A session with Focus opens its timer from right here, under the time left.
        if (run.focus != null) OpenFocusPill(Modifier.padding(top = Space.L)) { onOpenFocus(run.id) }
    }
}

/**
 * The way into a running Focus: a glass pill with a lit disc holding the timer, its name, and a chevron. The disc is
 * the solid porcelain of a primary action, held small, so the pill stays quiet beside the time.
 */
@Composable
fun OpenFocusPill(modifier: Modifier = Modifier, onClick: () -> Unit) {
    val colors = Aurora.colors
    Row(
        modifier.heightIn(min = 52.dp).pressable(Radius.Pill, onClick = onClick).glassControl(Radius.Pill)
            .padding(start = 6.dp, end = 16.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(40.dp).clip(Radius.Pill).background(colors.accent).glassShine(colors, Radius.Pill), contentAlignment = Alignment.Center) {
            Icon(AuroraIcons.Focus, null, tint = colors.onAccent, modifier = Modifier.size(19.dp))
        }
        // Full width, the chevron sits at the far end.
        Text(stringResource(R.string.home_open_focus), Modifier.weight(1f, fill = false), color = colors.textPrimary, style = AuroraType.Label)
        if (modifier != Modifier) Box(Modifier.weight(1f))
        Icon(AuroraIcons.Chevron, null, tint = colors.textTertiary, modifier = Modifier.size(16.dp))
    }
}

/**
 * What needs the person now, above everything else: Shield attention, Focus reports waiting and what is running.
 */
fun LazyListScope.homeNow(d: ProtectData, app: AuroraApplication, onOpenFocus: (String) -> Unit, onOpenLegacyFocus: () -> Unit, onOpenRun: (String) -> Unit = {}) {
    // Every session is one equal row, in the order they started: a new one joins below the last.
    // The session the hero shows isn't listed again here.
    val lead = leadRun(d, app)?.id
    val runs = app.runsNow(d.time).filter { it.id != lead }.sortedBy { it.startAt(d.time) }
    val legacy = d.sessions
    val legacyFocus = d.runtime.focus?.takeUnless { it.isOver(d.time) }
    val hasEnforcement = lead != null || runs.isNotEmpty() || legacy.isNotEmpty() || d.rules.activations.any { it.enabled }

    // A finished session says nothing here: it goes to Recent sessions in Progress.
    val timed = runs.filter { it.end != null || it.time != null }
    if (timed.isNotEmpty() || legacy.isNotEmpty() || legacyFocus != null) item(key = "active-label") { Column(Modifier.animateItem()) { HomeLabel(stringResource(R.string.home_active)) } }
    items(timed, key = { "run-" + it.id }) { r -> Column(Modifier.animateItem()) { SessionRow(r, d, app, { onOpenRun(r.id) }, onOpenFocus) } }
    items(legacy, key = { "legacy-" + it.id }) { s ->
        SessionCard(s, d, onTurnOff = { app.scope.launch { app.saving { app.turnOff(s.id) } } }, Modifier.padding(top = Space.Card))
    }
    if (legacyFocus != null) item(key = "legacy-focus") {
        LegacyFocusCard(legacyFocus.intention.ifBlank { stringResource(R.string.home_legacy_focus) }, legacyFocus.remaining(d.time), onOpenLegacyFocus)
    }
    // Shield problems (permission off, service not running) and the one action that fixes them, under what is running.
    item(key = "status") { Column(Modifier.animateItem()) { EnforcementStatus(d, hasEnforcement) } }
}

/** Scheduled, always active and recent: each only when it has something. */
fun LazyListScope.homeLater(d: ProtectData, app: AuroraApplication, onOpenFocus: (String) -> Unit) {
    val runs = app.runsNow(d.time)
    val zone = ZoneId.systemDefault()
    val entitled = d.rules.entitledActivations(d.plan, d.time.wall, zone)
    // A task started now is one window from now: it shows as running, not as scheduled.
    val scheduled = d.rules.activations.filter { it.timing is Timing.Later || it.timing is Timing.Recurring }
    if (scheduled.isNotEmpty()) item(key = "scheduled-label") { Column(Modifier.animateItem()) { HomeLabel(stringResource(R.string.home_scheduled)) } }
    items(scheduled, key = { "act-" + it.id }) { a -> Column(Modifier.animateItem()) { ActivationCard(a, d, app, a.id in entitled, zone) } }

    val always = d.rules.activations.filter { it.timing is Timing.Always }
    if (always.isNotEmpty()) item(key = "always-label") { Column(Modifier.animateItem()) { HomeLabel(stringResource(R.string.home_always)) } }
    items(always, key = { "always-" + it.id }) { a ->
        val run = runs.find { it.activationId == a.id }
        if (run != null) RunCard(run, d, app, onOpenFocus) else ActivationCard(a, d, app, a.id in entitled, zone)
    }

}

@Composable
private fun HomeLabel(text: String) {
    // Small caps like every section label; read aloud as written.
    Text(
        text,
        Modifier.rise().padding(start = Space.Gutter + 4.dp, end = Space.Gutter, top = 40.dp, bottom = 14.dp)
            .clearAndSetSemantics { heading(); this.text = androidx.compose.ui.text.AnnotatedString(text) },
        color = Aurora.colors.textTertiary, style = AuroraType.Eyebrow,
    )
}

/** The Shield service's real state, shown only when something should be enforced and isn't. */
@Composable
private fun EnforcementStatus(d: ProtectData, hasEnforcement: Boolean) {
    val access = rememberAccess(Access.Accessibility)
    val connected by ShieldService.connected.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val status = protectionStatus(hasEnforcement, blocked = false, access, connected, d.list.readable && d.rulesReadable)
    if (status != ProtectionStatus.Active && status != ProtectionStatus.Off) {
        Column(Modifier.rise().padding(top = Space.Card)) {
            ShieldStatusCard(status, 0, onTurnOn = askFor(Access.Accessibility), onOpenSettings = { openAccessSettings(context, Access.Accessibility) })
        }
    }
}

/**
 * One running session: its Shield and Intent, the time left (or that it runs until turned off),
 * whether it is really enforced, Focus progress, and the one action it allows. Strict shows that
 * it ends on its own instead of a button.
 */
@Composable
fun RunCard(run: ConfigRun, d: ProtectData, app: AuroraApplication, onOpenFocus: (String) -> Unit, inHero: Boolean = false) {
    val colors = Aurora.colors
    // The app's scope: turning off removes this card, and the run's record must still be written.
    val scope = app.scope
    val connected by ShieldService.connected.collectAsStateWithLifecycle()
    var confirm by remember { mutableStateOf(false) }
    var ending by remember { mutableStateOf(false) }
    val host = androidx.lifecycle.compose.LocalLifecycleOwner.current
    val tint = run.shownMode?.let { modeColor(it) } ?: colors.textSecondary
    val end = run.endAt(d.time)
    // A commitment carried over from another account doesn't show what it covers.
    val keys = if (run.carried) emptyList() else (run.shield?.keys.orEmpty() + run.intent?.keys.orEmpty()).distinct()
    Column(
        Modifier.rise().padding(horizontal = Space.Gutter).padding(top = Space.Card).fillMaxWidth().auroraSurface(colors, edge = tint).padding(horizontal = Space.Inner, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(Space.S),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.S)) {
            Text(run.name, Modifier.weight(1f), color = colors.textPrimary, style = AuroraType.Title)
            IconStack(keys, d, max = 4, size = 28.dp)
        }
        androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.XS), verticalArrangement = Arrangement.spacedBy(Space.XS)) {
            run.shownMode?.let { ModeChip(it, label = run.shield?.name ?: modeName(it)) }
            run.intent?.let { IntentChip(it.name) }
        }
        if (inHero) {
            // Home's hero already counts this one down; the card keeps what it is and what it allows.
        } else if (end != null) {
            RollingClock(formatClock(end - d.time.wall), colors.textPrimary, AuroraType.Hero)
            Text(stringResource(R.string.home_ends_at, clockTime(end)), color = colors.textSecondary, style = AuroraType.Secondary)
        } else {
            Text(stringResource(R.string.home_until_off), color = colors.textSecondary, style = AuroraType.Secondary)
        }
        // The hero above already says this for the run it counts down.
        if (!inHero) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.XS)) {
            Box(Modifier.size(8.dp).clip(Radius.Pill).background(if (connected) colors.accent else colors.muted))
            Text(stringResource(if (connected) R.string.home_enforced else R.string.home_not_enforced), color = if (connected) colors.accent else colors.mutedText, style = AuroraType.Caption)
        }
        if (!inHero) run.focus?.let { f ->
            val target = f.minutes?.let { it * 60_000L } ?: ((end ?: d.time.wall) - run.startAt(d.time))
            val done = (d.time.wall - run.startAt(d.time)).coerceIn(0, target.coerceAtLeast(1))
            Column(Modifier.fillMaxWidth().glassCard(colors, Radius.M, fill = colors.glassFill(field = true)).padding(Space.M), verticalArrangement = Arrangement.spacedBy(Space.XS)) {
                Eyebrow(stringResource(R.string.focus_name), color = colors.textSecondary)
                Text(f.task, color = colors.textPrimary, style = AuroraType.BodyStrong)
                ProgressLine(done.toFloat() / target.coerceAtLeast(1))
                AuroraButton(stringResource(R.string.home_open_focus), { onOpenFocus(run.id) }, kind = ButtonKind.Quiet, compact = true, icon = AuroraIcons.Focus)
            }
        }
        if (run.cancellable(d.time)) {
            AuroraButton(stringResource(R.string.home_turn_off), { confirm = true }, Modifier.fillMaxWidth(), kind = ButtonKind.Secondary)
        } else if (run.endsAfterTask) {
            // Friction and Cooldown end here only, after the challenge or the wait: never from the Shield.
            AuroraButton(stringResource(R.string.home_turn_off), { ending = true }, Modifier.fillMaxWidth(), kind = ButtonKind.Secondary)
        } else {
            Row(
                Modifier.fillMaxWidth().glass(colors, Radius.Pill).padding(horizontal = Space.M, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.XS),
            ) {
                Icon(AuroraIcons.Lock, null, tint = colors.mutedText, modifier = Modifier.size(18.dp))
                Text(
                    run.committedUntil?.let { stringResource(R.string.home_committed_until, clockTime(it)) } ?: stringResource(R.string.session_ends_automatically),
                    color = colors.mutedText, style = AuroraType.Label,
                )
            }
        }
    }
    if (confirm) AuroraDialog(
        stringResource(R.string.session_turn_off_title), { confirm = false }, stringResource(R.string.home_turn_off),
        { confirm = false; scope.launch { app.saving { app.turnOffRun(run.id) } } },
        stringResource(R.string.session_turn_off_body), stringResource(R.string.action_cancel),
    )
    if (ending) androidx.compose.ui.window.Dialog({ ending = false }, androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
        EndSession(app, run, host, onEnd = { ending = false; scope.launch { app.saving { app.turnOffRun(run.id) } } }, onCancel = { ending = false })
    }
}

/** A schedule or an Always activation that isn't running now: when it next starts, and pause, resume or remove. */
@Composable
private fun ActivationCard(a: Activation, d: ProtectData, app: AuroraApplication, entitled: Boolean, zone: ZoneId) {
    val colors = Aurora.colors
    val scope = app.scope
    val config = d.rules.configs.find { it.id == a.configId }
    val next = a.nextStart(d.time.wall, zone)
    Column(
        Modifier.rise().padding(horizontal = Space.Gutter).padding(top = Space.Card).fillMaxWidth().auroraSurface(colors).padding(horizontal = Space.Inner, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(Space.XS),
    ) {
        Text(config?.name.orEmpty(), color = colors.textPrimary, style = AuroraType.Title)
        Text(timingText(a.timing, zone), color = colors.textSecondary, style = AuroraType.Secondary)
        Text(
            when {
                !a.enabled -> stringResource(R.string.home_paused)
                !entitled -> stringResource(R.string.home_paused_plan)
                d.runtime.quotaDenied.keys.any { it == "later:${a.id}" || it.startsWith("rec:${a.id}:") } -> stringResource(R.string.home_skipped_quota)
                app.isWaitingForServer(a, d) -> stringResource(R.string.home_waiting_server)
                next != null -> stringResource(R.string.home_next_start, java.time.Instant.ofEpochMilli(next).atZone(zone).format(java.time.format.DateTimeFormatter.ofPattern("EEE d MMM, ")) + clockTime(next))
                else -> ""
            },
            color = if (a.enabled && entitled) colors.accent else colors.mutedText, style = AuroraType.Label,
        )
        Row(Modifier.padding(top = Space.XS), horizontalArrangement = Arrangement.spacedBy(Space.XS)) {
            AuroraButton(
                stringResource(if (a.enabled) R.string.home_pause else R.string.home_resume),
                { scope.launch { app.saving { app.setActivationEnabled(a.id, !a.enabled) } } },
                Modifier.weight(1f), kind = ButtonKind.Secondary, compact = true,
            )
            AuroraButton(stringResource(R.string.home_remove), { scope.launch { app.saving { app.deleteActivation(a.id) } } }, Modifier.weight(1f), kind = ButtonKind.Quiet, compact = true)
        }
    }
}

@Composable
private fun LegacyFocusCard(title: String, left: Long, onOpen: () -> Unit) {
    val colors = Aurora.colors
    Column(
        Modifier.rise().padding(horizontal = Space.Gutter).padding(top = Space.Card).fillMaxWidth().pressable(Radius.L, onClick = onOpen).auroraSurface(colors, warm = true).padding(horizontal = Space.Inner, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(Space.XS),
    ) {
        Eyebrow(stringResource(R.string.focus_name), color = colors.textSecondary)
        Text(title, color = colors.textPrimary, style = AuroraType.Title)
        Text(formatClock(left), color = colors.textPrimary, style = AuroraType.Headline)
    }
}

@Composable
fun outcomeName(o: Outcome): String = stringResource(
    when (o) {
        Outcome.Succeeded -> R.string.outcome_succeeded
        Outcome.Partial -> R.string.outcome_partial
        Outcome.NotAchieved -> R.string.outcome_not
        Outcome.Unverified -> R.string.outcome_unverified
    },
)

/** An Intent preset's name, in the sun's colour beside the Shield's mode chip. */
@Composable
fun IntentChip(name: String) {
    val tint = Aurora.colors.textSecondary
    Row(
        Modifier.glass(Aurora.colors, Radius.Pill, tint = tint).padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(AuroraIcons.Intent, null, tint = tint, modifier = Modifier.size(15.dp))
        Text(name, color = tint, style = AuroraType.Caption, maxLines = 1)
    }
}

/** An outcome, in calm colours: success is lit, the rest are information, never red. */
@Composable
fun OutcomeTag(o: Outcome) {
    val colors = Aurora.colors
    val (bg, fg) = when (o) {
        Outcome.Succeeded -> colors.accentSoft to colors.accent
        Outcome.Partial -> colors.textSecondary.copy(alpha = 0.14f) to colors.textSecondary
        Outcome.NotAchieved -> colors.mutedSoft to colors.mutedText
        Outcome.Unverified -> colors.elevated to colors.textSecondary
    }
    Text(outcomeName(o), Modifier.clip(Radius.Pill).background(bg).padding(horizontal = 10.dp, vertical = 4.dp), color = fg, style = AuroraType.Caption)
}

/**
 * Saved custom sessions. Start opens the same When step as Start session: now, later or on a schedule,
 * then Review (Focus, message and, for a strict Shield, the commitment).
 */
@Composable
fun QuickStart(configs: List<UsageConfig>, d: ProtectData, app: AuroraApplication, busy: Boolean = false, onEdit: (String) -> Unit = {}, onStart: (String) -> Unit = {}) {
    val colors = Aurora.colors
    val context = LocalContext.current
    val access = rememberAccess(Access.Accessibility)
    // Without Shield a session is only a timer: nothing starts until it's on.
    var viewing by remember { mutableStateOf<UsageConfig?>(null) }
    var deleting by remember { mutableStateOf<UsageConfig?>(null) }
    configs.forEach { c ->
        key(c.id) {
            val askShield = askFor(Access.Accessibility)
            val shield = d.rules.shieldOf(c)
            val intent = d.rules.intentOf(c)
            val keys = (shield?.keys.orEmpty() + intent?.keys.orEmpty()).distinct()
            Column(
                Modifier.rise().padding(horizontal = Space.Gutter).padding(top = Space.Card).fillMaxWidth()
                    .auroraSurface(colors).padding(horizontal = Space.Inner, vertical = 20.dp),
                verticalArrangement = Arrangement.spacedBy(Space.S),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.S)) {
                    Text(c.name, Modifier.weight(1f), color = colors.textPrimary, style = AuroraType.Title)
                    IconStack(keys, d, max = 4, size = 28.dp)
                    // Only a saved session is the person's to change; a Shield or Intent offered here is changed in its own tab.
                    if (c.custom) Box {
                        var open by remember { mutableStateOf(false) }
                        AuroraIconButton(AuroraIcons.More, stringResource(R.string.preset_more, c.name), { open = true }, Modifier.size(40.dp))
                        androidx.compose.material3.DropdownMenu(
                            open, { open = false },
                            shape = Radius.M,
                            containerColor = colors.elevated,
                            border = androidx.compose.foundation.BorderStroke(1.dp, colors.glassRim()),
                        ) {
                            ItemMenuItems(
                                { open = false },
                                onView = { viewing = c },
                                onEdit = { onEdit(c.id) },
                                onDuplicate = {
                                    app.scope.launch {
                                        runCatching { app.saveConfig(c.copy(id = newId(), name = app.getString(R.string.preset_copy_name, c.name), createdAt = System.currentTimeMillis())) }
                                            .onFailure { app.saveFailed.value = true }
                                    }
                                },
                                onDelete = { deleting = c },
                            )
                        }
                    }
                }
                // What it holds on the left, Start on the right: a soft pill with no rim, so the card stays calm.
                // While a session runs, the note at the top says why nothing here starts.
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.S)) {
                    androidx.compose.foundation.layout.FlowRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(Space.XS), verticalArrangement = Arrangement.spacedBy(Space.XS)) {
                        d.rules.modeOf(c).takeIf { shield != null || it.strict }?.let { ModeChip(it, label = shield?.name ?: modeName(it)) }
                        intent?.let { IntentChip(it.name) }
                    }
                    if (!busy) AuroraButton(
                        stringResource(R.string.cfg_start),
                        { if (access != AccessState.Granted) askShield() else onStart(c.id) },
                        kind = ButtonKind.Tonal, icon = AuroraIcons.Play, compact = true,
                    )
                }
            }
        }
    }
    viewing?.let { c -> DetailsDialog(c.name, sessionRows(c), { viewing = null }, sessionSections(c, d)) { onEdit(c.id) } }
    deleting?.let { c ->
        val goals = d.rules.trackGoals.filter { it.configId == c.id }
        AuroraDialog(
            stringResource(R.string.config_delete_title, c.name), { deleting = null },
            stringResource(if (goals.isEmpty()) R.string.preset_delete else R.string.config_delete_archive),
            {
                deleting = null
                app.scope.launch { runCatching { app.deleteConfig(c.id, removeGoals = true) }.onFailure { app.saveFailed.value = true } }
            },
            if (goals.isEmpty()) stringResource(R.string.config_delete_body) else stringResource(R.string.config_delete_habits, goals.joinToString(", ") { it.name }) + " " + stringResource(R.string.config_delete_body),
            stringResource(R.string.action_cancel),
        )
    }
}

/** Everything a saved session holds, to read: each Shield and Intent with its apps, its Focus goal and its note. */
@Composable
fun SessionDetails(c: UsageConfig, d: ProtectData, onDismiss: () -> Unit) = DetailsDialog(c.name, sessionRows(c), onDismiss, sessionSections(c, d))

/**
 * A saved session's Shield and Intent, each its own card with what it covers: what's blocked and what's asked about
 * are never mixed in one list. A part the session doesn't use says None.
 */
@Composable
private fun sessionSections(c: UsageConfig, d: ProtectData): List<app.aurora.ui.library.DetailSection> {
    val none = stringResource(R.string.detail_none)
    // Only what it covers: an empty kind (no websites, say) is left out rather than listed as None.
    @Composable
    fun covers(keys: List<String>) = keyRows(keys, d).filter { it.second != none }
    // Every Shield and every Intent on its own, so what each one holds is plain; none of a kind says None once.
    val shields = c.shield?.let(::listOf) ?: c.allShieldIds.mapNotNull { id -> d.rules.shields.find { it.id == id } }
    val intents = c.intent?.let(::listOf) ?: c.allIntentIds.mapNotNull { id -> d.rules.intentPresets.find { it.id == id } }
    val mode = modeName(d.rules.modeOf(c))
    return shields.map { s ->
        app.aurora.ui.library.DetailSection(app.aurora.ui.components.AuroraIcons.Protect, stringResource(R.string.detail_shield_kind), s.name + " · " + mode, covers(s.keys))
    }.ifEmpty { listOf(app.aurora.ui.library.DetailSection(app.aurora.ui.components.AuroraIcons.Protect, stringResource(R.string.detail_shield_kind), none, emptyList())) } +
        intents.map { i ->
            app.aurora.ui.library.DetailSection(app.aurora.ui.components.AuroraIcons.Intent, stringResource(R.string.detail_intent_kind), i.name + " · " + intentSummary(i.config), covers(i.keys))
        }.ifEmpty { listOf(app.aurora.ui.library.DetailSection(app.aurora.ui.components.AuroraIcons.Intent, stringResource(R.string.detail_intent_kind), none, emptyList())) }
}

/** The rest of a saved session: its Focus goal, only when Focus is on, and its note. */
@Composable
private fun sessionRows(c: UsageConfig): List<Pair<String, String>> = listOf(
    stringResource(R.string.detail_focus) to (c.focus?.let { f -> f.goal.ifBlank { stringResource(R.string.detail_focus_no_goal) } }.orEmpty()),
    stringResource(R.string.detail_note) to c.note,
)

