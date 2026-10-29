package app.aurora.ui.home

import androidx.compose.foundation.background
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import app.aurora.data.Access
import app.aurora.data.ProtectionStatus
import app.aurora.data.formatClock
import app.aurora.data.openAccessSettings
import app.aurora.data.protectionStatus
import app.aurora.deleteActivation
import app.aurora.progress.Outcome
import app.aurora.progress.RunRecord
import app.aurora.protect.Activation
import app.aurora.protect.ConfigRun
import app.aurora.protect.FocusOutcome
import app.aurora.protect.ShieldService
import app.aurora.protect.Timing
import app.aurora.protect.entitledActivations
import app.aurora.protect.nextStart
import app.aurora.reportFocus
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
import app.aurora.ui.protect.ModeChip
import app.aurora.ui.protect.ProtectData
import app.aurora.ui.protect.SessionCard
import app.aurora.ui.protect.ShieldDisclosure
import app.aurora.ui.protect.ShieldStatusCard
import app.aurora.ui.protect.modeColor
import app.aurora.ui.theme.Aurora
import app.aurora.ui.theme.AuroraType
import app.aurora.ui.theme.Radius
import app.aurora.ui.theme.Space
import kotlinx.coroutines.launch
import java.io.IOException
import java.time.ZoneId

/** The two ways in: quick setup from built-ins, or the person's own saved presets. */
@Composable
fun ChooseCard(onNormalUse: () -> Unit, onCustom: () -> Unit) {
    val colors = Aurora.colors
    Column(Modifier.padding(horizontal = Space.M, vertical = Space.XS).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Space.S)) {
        Text(stringResource(R.string.home_choose_title), Modifier.padding(start = Space.XS, top = Space.S).semantics { heading() }, color = colors.textPrimary, style = AuroraType.Title)
        Row(horizontalArrangement = Arrangement.spacedBy(Space.S)) {
            Choice(AuroraIcons.Play, stringResource(R.string.home_normal_title), stringResource(R.string.home_normal_body), colors.accent, onNormalUse, Modifier.weight(1f))
            Choice(AuroraIcons.Layers, stringResource(R.string.home_custom_title), stringResource(R.string.home_custom_body), colors.sun, onCustom, Modifier.weight(1f), warm = true)
        }
    }
}

@Composable
private fun Choice(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, body: String, tint: Color, onClick: () -> Unit, modifier: Modifier, warm: Boolean = false) {
    val colors = Aurora.colors
    Column(
        modifier.auroraSurface(colors, warm = warm, edge = tint).clickable(role = Role.Button, onClick = onClick).padding(Space.M),
        verticalArrangement = Arrangement.spacedBy(Space.XS),
    ) {
        LitBadge(icon, tint = tint, size = 44)
        Text(title, Modifier.padding(top = Space.XS), color = colors.textPrimary, style = AuroraType.Title)
        Text(body, color = colors.textSecondary, style = AuroraType.Secondary)
    }
}

/** Active now, Focus reports waiting, scheduled, always active and recent: each only when it has something. */
fun LazyListScope.homeSections(d: ProtectData, app: AuroraApplication, onOpenFocus: (String) -> Unit, onOpenLegacyFocus: () -> Unit) {
    val runs = app.runsNow(d.time).sortedBy { it.endAt(d.time) ?: Long.MAX_VALUE }
    val zone = ZoneId.systemDefault()
    val entitled = d.rules.entitledActivations(d.plan)
    val legacy = d.sessions
    val legacyFocus = d.runtime.focus?.takeUnless { it.isOver(d.time) }
    val hasEnforcement = runs.isNotEmpty() || legacy.isNotEmpty() || d.rules.activations.any { it.enabled }

    item(key = "status") { EnforcementStatus(d, hasEnforcement) }

    val awaiting = d.history.runs.filter { it.awaitingReport }.takeLast(3)
    items(awaiting, key = { "report-" + it.id }) { r -> FocusReport(r, app) }

    val timed = runs.filter { it.end != null || it.time != null }
    if (timed.isNotEmpty() || legacy.isNotEmpty() || legacyFocus != null) item(key = "active-label") { HomeLabel(stringResource(R.string.home_active)) }
    items(timed, key = { "run-" + it.id }) { r -> RunCard(r, d, app, onOpenFocus) }
    items(legacy, key = { "legacy-" + it.id }) { s ->
        SessionCard(s, d, onTurnOff = { app.scope.launch { runCatching { app.turnOff(s.id) } } }, Modifier.padding(top = Space.XS))
    }
    if (legacyFocus != null) item(key = "legacy-focus") {
        LegacyFocusCard(legacyFocus.intention.ifBlank { stringResource(R.string.home_legacy_focus) }, legacyFocus.remaining(d.time), onOpenLegacyFocus)
    }

    val scheduled = d.rules.activations.filter { it.timing is Timing.Later || it.timing is Timing.Recurring }
    if (scheduled.isNotEmpty()) item(key = "scheduled-label") { HomeLabel(stringResource(R.string.home_scheduled)) }
    items(scheduled, key = { "act-" + it.id }) { a -> ActivationCard(a, d, app, a.id in entitled, zone) }

    val always = d.rules.activations.filter { it.timing is Timing.Always }
    if (always.isNotEmpty()) item(key = "always-label") { HomeLabel(stringResource(R.string.home_always)) }
    items(always, key = { "always-" + it.id }) { a ->
        val run = runs.find { it.activationId == a.id }
        if (run != null) RunCard(run, d, app, onOpenFocus) else ActivationCard(a, d, app, a.id in entitled, zone)
    }

    if (timed.isEmpty() && legacy.isEmpty() && scheduled.isEmpty() && always.isEmpty() && legacyFocus == null) item(key = "nothing") {
        Text(stringResource(R.string.home_nothing_active), Modifier.padding(horizontal = Space.Gutter, vertical = Space.S), color = Aurora.colors.textSecondary, style = AuroraType.Secondary)
    }

    val recent = d.history.runs.filterNot { it.awaitingReport }.takeLast(3).reversed()
    if (recent.isNotEmpty()) {
        item(key = "recent-label") { HomeLabel(stringResource(R.string.home_recent)) }
        items(recent, key = { "rec-" + it.id }) { r -> RecentRow(r) }
    }
}

@Composable
private fun HomeLabel(text: String) {
    Text(text, Modifier.padding(start = Space.Gutter, end = Space.Gutter, top = Space.L, bottom = Space.XS).semantics { heading() }, color = Aurora.colors.textSecondary, style = AuroraType.Eyebrow)
}

/** The Shield service's real state, shown only when something should be enforced and isn't. */
@Composable
private fun EnforcementStatus(d: ProtectData, hasEnforcement: Boolean) {
    val access = rememberAccess(Access.Accessibility)
    val connected by ShieldService.connected.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var disclosure by remember { mutableStateOf(false) }
    val status = protectionStatus(hasEnforcement, blocked = false, access, connected, d.list.readable && d.rulesReadable)
    if (status != ProtectionStatus.Active && status != ProtectionStatus.Off) {
        Column(Modifier.padding(top = Space.XS)) {
            ShieldStatusCard(status, 0, onTurnOn = { disclosure = true }, onOpenSettings = { openAccessSettings(context, Access.Accessibility) })
        }
    }
    if (disclosure) ShieldDisclosure(onContinue = { disclosure = false; openAccessSettings(context, Access.Accessibility) }, onDismiss = { disclosure = false })
}

/**
 * One running configuration: its Shield and Intent, the time left (or that it runs until turned off),
 * whether it is really enforced, Focus progress, and the one action it allows. Always Block shows that
 * it ends on its own instead of a button.
 */
@Composable
fun RunCard(run: ConfigRun, d: ProtectData, app: AuroraApplication, onOpenFocus: (String) -> Unit) {
    val colors = Aurora.colors
    val scope = rememberCoroutineScope()
    val connected by ShieldService.connected.collectAsStateWithLifecycle()
    var confirm by remember { mutableStateOf(false) }
    val tint = run.shield?.let { modeColor(it.mode) } ?: colors.sun
    val end = run.endAt(d.time)
    val keys = (run.shield?.keys.orEmpty() + run.intent?.keys.orEmpty()).distinct()
    Column(
        Modifier.padding(horizontal = Space.M).padding(top = Space.XS).fillMaxWidth().auroraSurface(colors, edge = tint).padding(Space.L),
        verticalArrangement = Arrangement.spacedBy(Space.S),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.S)) {
            Text(run.name, Modifier.weight(1f), color = colors.textPrimary, style = AuroraType.Title)
            IconStack(keys, d, max = 4, size = 28.dp)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(Space.XS), verticalAlignment = Alignment.CenterVertically) {
            run.shield?.let { ModeChip(it.mode, label = it.name) }
            run.intent?.let { IntentChip(it.name) }
        }
        if (end != null) {
            Text(formatClock(end - d.time.wall), color = colors.textPrimary, style = AuroraType.Hero)
            Text(stringResource(R.string.home_ends_at, clockTime(end)), color = colors.textSecondary, style = AuroraType.Secondary)
        } else {
            Text(stringResource(R.string.home_until_off), color = colors.textSecondary, style = AuroraType.Secondary)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.XS)) {
            Box(Modifier.size(8.dp).clip(Radius.Pill).background(if (connected) colors.accent else colors.dawn))
            Text(stringResource(if (connected) R.string.home_enforced else R.string.home_not_enforced), color = if (connected) colors.accent else colors.dawnText, style = AuroraType.Caption)
        }
        run.focus?.let { f ->
            val target = f.minutes?.let { it * 60_000L } ?: ((end ?: d.time.wall) - run.startAt(d.time))
            val done = (d.time.wall - run.startAt(d.time)).coerceIn(0, target.coerceAtLeast(1))
            Column(Modifier.fillMaxWidth().clip(Radius.M).background(colors.sun.copy(alpha = 0.07f)).padding(Space.M), verticalArrangement = Arrangement.spacedBy(Space.XS)) {
                Text(stringResource(R.string.focus_name).uppercase(), color = colors.sun, style = AuroraType.Eyebrow)
                Text(f.task, color = colors.textPrimary, style = AuroraType.BodyStrong)
                ProgressLine(done.toFloat() / target.coerceAtLeast(1), warm = true)
                AuroraButton(stringResource(R.string.home_open_focus), { onOpenFocus(run.id) }, kind = ButtonKind.Quiet, compact = true, icon = AuroraIcons.Focus)
            }
        }
        if (run.cancellable(d.time)) {
            AuroraButton(stringResource(R.string.home_turn_off), { confirm = true }, Modifier.fillMaxWidth(), kind = ButtonKind.Secondary)
        } else {
            Row(
                Modifier.fillMaxWidth().clip(Radius.Pill).background(colors.dawnSoft).padding(horizontal = Space.M, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.XS),
            ) {
                Icon(AuroraIcons.Lock, null, tint = colors.dawnText, modifier = Modifier.size(18.dp))
                Text(
                    run.committedUntil?.let { stringResource(R.string.home_committed_until, clockTime(it)) } ?: stringResource(R.string.session_ends_automatically),
                    color = colors.dawnText, style = AuroraType.Label,
                )
            }
        }
    }
    if (confirm) AuroraDialog(
        stringResource(R.string.session_turn_off_title), { confirm = false }, stringResource(R.string.home_turn_off),
        { confirm = false; scope.launch { runCatching { app.turnOffRun(run.id) } } },
        stringResource(R.string.session_turn_off_body), stringResource(R.string.action_cancel),
    )
}

/** A schedule or an Always activation that isn't running now: when it next starts, and pause, resume or remove. */
@Composable
private fun ActivationCard(a: Activation, d: ProtectData, app: AuroraApplication, entitled: Boolean, zone: ZoneId) {
    val colors = Aurora.colors
    val scope = rememberCoroutineScope()
    val config = d.rules.configs.find { it.id == a.configId }
    val next = a.nextStart(d.time.wall, zone)
    Column(
        Modifier.padding(horizontal = Space.M).padding(top = Space.XS).fillMaxWidth().auroraSurface(colors).padding(Space.L),
        verticalArrangement = Arrangement.spacedBy(Space.XS),
    ) {
        Text(config?.name.orEmpty(), color = colors.textPrimary, style = AuroraType.Title)
        Text(timingText(a.timing, zone), color = colors.textSecondary, style = AuroraType.Secondary)
        Text(
            when {
                !a.enabled -> stringResource(R.string.home_paused)
                !entitled -> stringResource(R.string.home_paused_plan)
                next != null -> stringResource(R.string.home_next_start, java.time.Instant.ofEpochMilli(next).atZone(zone).format(java.time.format.DateTimeFormatter.ofPattern("EEE d MMM, ")) + clockTime(next))
                else -> ""
            },
            color = if (a.enabled && entitled) colors.accent else colors.dawnText, style = AuroraType.Label,
        )
        Row(Modifier.padding(top = Space.XS), horizontalArrangement = Arrangement.spacedBy(Space.XS)) {
            AuroraButton(
                stringResource(if (a.enabled) R.string.home_pause else R.string.home_resume),
                { scope.launch { try { app.setActivationEnabled(a.id, !a.enabled) } catch (_: IOException) { } } },
                Modifier.weight(1f), kind = ButtonKind.Secondary, compact = true,
            )
            AuroraButton(stringResource(R.string.home_remove), { scope.launch { try { app.deleteActivation(a.id) } catch (_: IOException) { } } }, Modifier.weight(1f), kind = ButtonKind.Quiet, compact = true)
        }
    }
}

/** A finished run with a Focus task asks how it went. A timer alone never decides. */
@Composable
private fun FocusReport(r: RunRecord, app: AuroraApplication) {
    val colors = Aurora.colors
    val scope = rememberCoroutineScope()
    Column(
        Modifier.padding(horizontal = Space.M).padding(top = Space.XS).fillMaxWidth().auroraSurface(colors, warm = true).padding(Space.L),
        verticalArrangement = Arrangement.spacedBy(Space.S),
    ) {
        Text(stringResource(R.string.focus_name).uppercase(), color = colors.sun, style = AuroraType.Eyebrow)
        Text(stringResource(R.string.home_focus_prompt, r.focusTask.orEmpty()), color = colors.textPrimary, style = AuroraType.Title)
        Row(horizontalArrangement = Arrangement.spacedBy(Space.XS)) {
            listOf(FocusOutcome.Completed to R.string.focus_completed, FocusOutcome.Partial to R.string.focus_partial, FocusOutcome.NotCompleted to R.string.focus_not_completed).forEach { (o, label) ->
                AuroraButton(stringResource(label), { scope.launch { runCatching { app.reportFocus(r.id, o) } } }, Modifier.weight(1f), kind = if (o == FocusOutcome.Completed) ButtonKind.Primary else ButtonKind.Secondary, compact = true)
            }
        }
    }
}

@Composable
private fun LegacyFocusCard(title: String, left: Long, onOpen: () -> Unit) {
    val colors = Aurora.colors
    Column(
        Modifier.padding(horizontal = Space.M).padding(top = Space.XS).fillMaxWidth().auroraSurface(colors, warm = true).clickable(role = Role.Button, onClick = onOpen).padding(Space.L),
        verticalArrangement = Arrangement.spacedBy(Space.XS),
    ) {
        Text(stringResource(R.string.focus_name).uppercase(), color = colors.sun, style = AuroraType.Eyebrow)
        Text(title, color = colors.textPrimary, style = AuroraType.Title)
        Text(formatClock(left), color = colors.textPrimary, style = AuroraType.Headline)
    }
}

@Composable
private fun RecentRow(r: RunRecord) {
    val colors = Aurora.colors
    Row(
        Modifier.padding(horizontal = Space.M).padding(top = Space.XS).fillMaxWidth().auroraSurface(colors, Radius.M).padding(horizontal = Space.M, vertical = Space.S),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.S),
    ) {
        Column(Modifier.weight(1f)) {
            Text(r.name, color = colors.textPrimary, style = AuroraType.BodyStrong)
            Text(clockTime(r.start) + " – " + clockTime(r.end), color = colors.textSecondary, style = AuroraType.Caption)
        }
        OutcomeTag(r.outcome)
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
    val tint = Aurora.colors.sun
    Row(
        Modifier.clip(Radius.Pill).background(tint.copy(alpha = 0.08f)).border(1.dp, tint.copy(alpha = 0.55f), Radius.Pill).padding(horizontal = 10.dp, vertical = 5.dp),
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
        Outcome.Partial -> colors.sun.copy(alpha = 0.14f) to colors.sun
        Outcome.NotAchieved -> colors.dawnSoft to colors.dawnText
        Outcome.Unverified -> colors.elevated to colors.textSecondary
    }
    Text(outcomeName(o), Modifier.clip(Radius.Pill).background(bg).padding(horizontal = 10.dp, vertical = 4.dp), color = fg, style = AuroraType.Caption)
}
