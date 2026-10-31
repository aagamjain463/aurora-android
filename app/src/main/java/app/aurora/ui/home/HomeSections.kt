package app.aurora.ui.home

import app.aurora.ui.components.gloss
import app.aurora.ui.components.glass
import app.aurora.ui.components.ribbonKnot
import app.aurora.ui.components.RollingClock
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
import app.aurora.ui.components.Sky
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
import app.aurora.protect.UsageConfig
import app.aurora.protect.ActivationProblem
import app.aurora.protect.shieldOf
import app.aurora.protect.strict
import app.aurora.protect.intentOf
import app.aurora.activate
import app.aurora.SessionNotification
import app.aurora.ui.components.minutesText
import app.aurora.ui.configure.problemText
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
import app.aurora.data.formatDuration
import app.aurora.ui.components.pressable
import app.aurora.ui.components.rimLight
import app.aurora.ui.components.rise
import app.aurora.ui.components.BrandEyebrow
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.unit.sp

/** The two ways in: quick setup from built-ins, or the person's own saved presets. */
@Composable
fun ChooseCard(onNormalUse: () -> Unit, onCustom: () -> Unit, primary: Boolean = true) {
    val colors = Aurora.colors
    Column(Modifier.rise().padding(horizontal = Space.M, vertical = Space.S).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Space.XS)) {
        if (primary) {
            Choice(AuroraIcons.Play, stringResource(R.string.home_normal_title), stringResource(R.string.home_normal_body), onNormalUse, primary = true)
            Choice(AuroraIcons.Layers, stringResource(R.string.home_custom_title), stringResource(R.string.home_custom_body), onCustom)
        } else {
            // Once Home has something better to offer, the two ways in step back to a pair of tiles.
            // Large text stacks the tiles, so a title never breaks mid-word.
            if (androidx.compose.ui.platform.LocalDensity.current.fontScale > 1.15f) {
                SmallChoice(AuroraIcons.Play, stringResource(R.string.home_normal_title), onNormalUse, Modifier.fillMaxWidth())
                SmallChoice(AuroraIcons.Layers, stringResource(R.string.home_custom_title), onCustom, Modifier.fillMaxWidth())
            } else Row(horizontalArrangement = Arrangement.spacedBy(Space.XS)) {
                SmallChoice(AuroraIcons.Play, stringResource(R.string.home_normal_title), onNormalUse, Modifier.weight(1f))
                SmallChoice(AuroraIcons.Layers, stringResource(R.string.home_custom_title), onCustom, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun Choice(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, body: String, onClick: () -> Unit, primary: Boolean = false) {
    val colors = Aurora.colors
    val foreground = if (primary) colors.onAccent else colors.textPrimary
    Row(
        Modifier.fillMaxWidth().pressable(Radius.L, onClick = onClick)
            .then(if (primary) Modifier else Modifier.rimLight(Radius.L))
            .then(if (primary) Modifier.background(androidx.compose.ui.graphics.Brush.horizontalGradient(colors.cta)).ribbonKnot().gloss(Radius.L) else Modifier.auroraSurface(colors))
            .padding(Space.M),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.M),
    ) {
        Box(Modifier.size(44.dp).clip(Radius.Pill).background(foreground.copy(alpha = 0.08f)), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = if (primary) foreground else colors.accent, modifier = Modifier.size(22.dp))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Space.XXS)) {
            Text(title, color = foreground, style = AuroraType.BodyStrong)
            Text(body, color = if (primary) foreground else colors.textSecondary, style = AuroraType.Caption)
        }
        Icon(AuroraIcons.Chevron, null, tint = foreground, modifier = Modifier.size(18.dp))
    }
}

@Composable
private fun SmallChoice(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, onClick: () -> Unit, modifier: Modifier) {
    val colors = Aurora.colors
    // Icon over title, so the whole tile's width holds the name on one line.
    Column(
        modifier.heightIn(min = 64.dp).pressable(Radius.L, onClick = onClick).auroraSurface(colors).padding(Space.M),
        verticalArrangement = Arrangement.spacedBy(Space.XS),
    ) {
        Icon(icon, null, tint = colors.accent, modifier = Modifier.size(20.dp))
        Text(title, color = colors.textPrimary, style = AuroraType.BodyStrong, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
    }
}

/** The timed run that ends soonest: Home's hero counts it down over First Light. */
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
fun NowHero(run: ConfigRun, d: ProtectData) {
    val colors = Aurora.colors
    val end = run.endAt(d.time) ?: return
    val left = end - d.time.wall
    // Honest about the sky: without the Shield service this time is only a timer.
    val connected by ShieldService.connected.collectAsStateWithLifecycle()
    Column(Modifier.rise().padding(horizontal = Space.Gutter).padding(bottom = Space.M)) {
        BrandEyebrow(stringResource(if (connected) R.string.home_now_eyebrow else R.string.home_not_enforced), Modifier.padding(bottom = Space.XS))
        RollingClock(
            formatClock(left), colors.textPrimary, AuroraType.Hero,
            spoken = stringResource(R.string.home_now_spoken, app.aurora.data.spokenDuration(left)),
        )
        Text(stringResource(R.string.home_now_detail, run.name, clockTime(end)), color = colors.textSecondary, style = AuroraType.Body)
    }
}

private const val FlareMs = 6_000L

/** The sky over Today: alive while a timed session runs, lit while anything else protects, quiet otherwise. */
fun skyNow(d: ProtectData, app: AuroraApplication): Sky {
    val runs = app.runsNow(d.time)
    return when {
        // Beginning is a moment too: the sky surges once as protection starts, then drifts.
        runs.any { d.time.wall - it.startAt(d.time) in 0..FlareMs } -> Sky.Flare
        runs.any { it.end != null || it.time != null } || d.sessions.isNotEmpty() || d.runtime.focus?.isOver(d.time) == false -> Sky.Alive
        runs.isNotEmpty() -> Sky.Lit
        else -> Sky.Quiet
    }
}

/**
 * What needs the person now, above everything else: Shield attention, a finish, Focus reports waiting and
 * what is running. Returns true when a report or run is shown, so Home can give up its filled row; a finish
 * has no action of its own, so starting the next one stays primary.
 */
/** Something on Home needs the person now: a Focus report to answer, a timed run, a session or Focus. */
fun busyNow(d: ProtectData, app: AuroraApplication): Boolean =
    d.history.runs.any { it.awaitingReport } || app.runsNow(d.time).any { it.end != null || it.time != null } ||
        d.sessions.isNotEmpty() || d.runtime.focus?.isOver(d.time) == false

fun LazyListScope.homeNow(d: ProtectData, app: AuroraApplication, onOpenFocus: (String) -> Unit, onOpenLegacyFocus: () -> Unit) {
    val runs = app.runsNow(d.time).sortedBy { it.endAt(d.time) ?: Long.MAX_VALUE }
    val legacy = d.sessions
    val legacyFocus = d.runtime.focus?.takeUnless { it.isOver(d.time) }
    val hasEnforcement = runs.isNotEmpty() || legacy.isNotEmpty() || d.rules.activations.any { it.enabled }

    item(key = "status") { Column(Modifier.animateItem()) { EnforcementStatus(d, hasEnforcement) } }

    // The finish is a moment: a run that just succeeded gets the arc of light, completed, for two hours.
    val finished = d.history.runs.lastOrNull()?.takeIf { it.outcome == Outcome.Succeeded && !it.turnedOff && d.time.wall - it.end in 0..2 * 3_600_000L }
    if (finished != null) item(key = "finished-" + finished.id) { Column(Modifier.animateItem()) { FinishedCard(finished) } }

    val awaiting = d.history.runs.filter { it.awaitingReport }.takeLast(3)
    items(awaiting, key = { "report-" + it.id }) { r -> Column(Modifier.animateItem()) { FocusReport(r, app) } }

    val timed = runs.filter { it.end != null || it.time != null }
    if (timed.isNotEmpty() || legacy.isNotEmpty() || legacyFocus != null) item(key = "active-label") { Column(Modifier.animateItem()) { HomeLabel(stringResource(R.string.home_active)) } }
    val lead = leadRun(d, app)
    items(timed, key = { "run-" + it.id }) { r -> Column(Modifier.animateItem()) { RunCard(r, d, app, onOpenFocus, inHero = r.id == lead?.id) } }
    items(legacy, key = { "legacy-" + it.id }) { s ->
        SessionCard(s, d, onTurnOff = { app.scope.launch { runCatching { app.turnOff(s.id) } } }, Modifier.padding(top = Space.XS))
    }
    if (legacyFocus != null) item(key = "legacy-focus") {
        LegacyFocusCard(legacyFocus.intention.ifBlank { stringResource(R.string.home_legacy_focus) }, legacyFocus.remaining(d.time), onOpenLegacyFocus)
    }
}

/** Scheduled, always active and recent: each only when it has something. */
fun LazyListScope.homeLater(d: ProtectData, app: AuroraApplication, onOpenFocus: (String) -> Unit) {
    val runs = app.runsNow(d.time)
    val zone = ZoneId.systemDefault()
    val entitled = d.rules.entitledActivations(d.plan)
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
    Text(text, Modifier.rise().padding(start = Space.Gutter, end = Space.Gutter, top = Space.L, bottom = Space.XS).semantics { heading() }, color = Aurora.colors.textSecondary, style = AuroraType.Eyebrow)
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
        Column(Modifier.rise().padding(top = Space.XS)) {
            ShieldStatusCard(status, 0, onTurnOn = { disclosure = true }, onOpenSettings = { openAccessSettings(context, Access.Accessibility) })
        }
    }
    if (disclosure) ShieldDisclosure(onContinue = { disclosure = false; openAccessSettings(context, Access.Accessibility) }, onDismiss = { disclosure = false })
}

/**
 * One running session: its Shield and Intent, the time left (or that it runs until turned off),
 * whether it is really enforced, Focus progress, and the one action it allows. Always Block shows that
 * it ends on its own instead of a button.
 */
@Composable
fun RunCard(run: ConfigRun, d: ProtectData, app: AuroraApplication, onOpenFocus: (String) -> Unit, inHero: Boolean = false) {
    val colors = Aurora.colors
    val scope = rememberCoroutineScope()
    val connected by ShieldService.connected.collectAsStateWithLifecycle()
    var confirm by remember { mutableStateOf(false) }
    val tint = run.shield?.let { modeColor(it.mode) } ?: colors.rose
    val end = run.endAt(d.time)
    val keys = (run.shield?.keys.orEmpty() + run.intent?.keys.orEmpty()).distinct()
    Column(
        Modifier.rise().padding(horizontal = Space.M).padding(top = Space.XS).fillMaxWidth().auroraSurface(colors, edge = tint).padding(Space.L),
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
            Box(Modifier.size(8.dp).clip(Radius.Pill).background(if (connected) colors.accent else colors.violet))
            Text(stringResource(if (connected) R.string.home_enforced else R.string.home_not_enforced), color = if (connected) colors.accent else colors.violetText, style = AuroraType.Caption)
        }
        run.focus?.let { f ->
            val target = f.minutes?.let { it * 60_000L } ?: ((end ?: d.time.wall) - run.startAt(d.time))
            val done = (d.time.wall - run.startAt(d.time)).coerceIn(0, target.coerceAtLeast(1))
            Column(Modifier.fillMaxWidth().glass(colors, Radius.M, tint = colors.rose).padding(Space.M), verticalArrangement = Arrangement.spacedBy(Space.XS)) {
                Text(stringResource(R.string.focus_name), color = colors.rose, style = AuroraType.Eyebrow)
                Text(f.task, color = colors.textPrimary, style = AuroraType.BodyStrong)
                ProgressLine(done.toFloat() / target.coerceAtLeast(1), warm = true)
                AuroraButton(stringResource(R.string.home_open_focus), { onOpenFocus(run.id) }, kind = ButtonKind.Quiet, compact = true, icon = AuroraIcons.Focus)
            }
        }
        if (run.cancellable(d.time)) {
            AuroraButton(stringResource(R.string.home_turn_off), { confirm = true }, Modifier.fillMaxWidth(), kind = ButtonKind.Secondary)
        } else {
            Row(
                Modifier.fillMaxWidth().glass(colors, Radius.Pill, tint = colors.violet).padding(horizontal = Space.M, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.XS),
            ) {
                Icon(AuroraIcons.Lock, null, tint = colors.violetText, modifier = Modifier.size(18.dp))
                Text(
                    run.committedUntil?.let { stringResource(R.string.home_committed_until, clockTime(it)) } ?: stringResource(R.string.session_ends_automatically),
                    color = colors.violetText, style = AuroraType.Label,
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
        Modifier.rise().padding(horizontal = Space.M).padding(top = Space.XS).fillMaxWidth().auroraSurface(colors).padding(Space.L),
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
            color = if (a.enabled && entitled) colors.accent else colors.violetText, style = AuroraType.Label,
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
        Modifier.rise().padding(horizontal = Space.M).padding(top = Space.XS).fillMaxWidth().auroraSurface(colors, warm = true).padding(Space.L),
        verticalArrangement = Arrangement.spacedBy(Space.S),
    ) {
        Text(stringResource(R.string.focus_name), color = colors.rose, style = AuroraType.Eyebrow)
        Text(stringResource(R.string.home_focus_prompt, r.focusTask.orEmpty()), color = colors.textPrimary, style = AuroraType.Title)
        val report = { o: FocusOutcome -> scope.launch { runCatching { app.reportFocus(r.id, o) } }; Unit }
        // The hoped-for answer gets the full width; the other two share a row (stacked with large text),
        // so no label ever wraps.
        AuroraButton(stringResource(R.string.focus_completed), { report(FocusOutcome.Completed) }, Modifier.fillMaxWidth(), compact = true)
        val others = listOf(FocusOutcome.Partial to R.string.focus_partial, FocusOutcome.NotCompleted to R.string.focus_not_completed)
        if (androidx.compose.ui.platform.LocalDensity.current.fontScale > 1.15f) {
            others.forEach { (o, label) -> AuroraButton(stringResource(label), { report(o) }, Modifier.fillMaxWidth(), kind = ButtonKind.Secondary, compact = true) }
        } else Row(horizontalArrangement = Arrangement.spacedBy(Space.XS)) {
            others.forEach { (o, label) -> AuroraButton(stringResource(label), { report(o) }, Modifier.weight(1f), kind = ButtonKind.Secondary, compact = true) }
        }
    }
}

@Composable
private fun FinishedCard(r: RunRecord) {
    val colors = Aurora.colors
    val haptics = androidx.compose.ui.platform.LocalHapticFeedback.current
    // Haptic Confirm once, when the finish is first seen (brand bible: Motion).
    androidx.compose.runtime.LaunchedEffect(r.id) { haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.Confirm) }
    Column(
        Modifier.rise().padding(horizontal = Space.M).padding(top = Space.S).fillMaxWidth().auroraSurface(colors, warm = true).padding(Space.L),
        verticalArrangement = Arrangement.spacedBy(Space.XS),
    ) {
        Text(stringResource(R.string.home_finished_eyebrow), color = colors.rose, style = AuroraType.Eyebrow)
        app.aurora.ui.components.HorizonArc(1f, Modifier.fillMaxWidth(0.6f).align(Alignment.CenterHorizontally), complete = true)
        Text(r.name, color = colors.textPrimary, style = AuroraType.Headline)
        Text(
            if (r.verifiedMs > 0) stringResource(R.string.home_finished_verified, formatDuration(r.verifiedMs)) else stringResource(R.string.home_finished_body),
            color = colors.textSecondary, style = AuroraType.Secondary,
        )
    }
}

@Composable
private fun LegacyFocusCard(title: String, left: Long, onOpen: () -> Unit) {
    val colors = Aurora.colors
    Column(
        Modifier.rise().padding(horizontal = Space.M).padding(top = Space.XS).fillMaxWidth().pressable(Radius.L, onClick = onOpen).auroraSurface(colors, warm = true).padding(Space.L),
        verticalArrangement = Arrangement.spacedBy(Space.XS),
    ) {
        Text(stringResource(R.string.focus_name), color = colors.rose, style = AuroraType.Eyebrow)
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
    val tint = Aurora.colors.rose
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
        Outcome.Partial -> colors.rose.copy(alpha = 0.14f) to colors.rose
        Outcome.NotAchieved -> colors.violetSoft to colors.violetText
        Outcome.Unverified -> colors.elevated to colors.textSecondary
    }
    Text(outcomeName(o), Modifier.clip(Radius.Pill).background(bg).padding(horizontal = 10.dp, vertical = 4.dp), color = fg, style = AuroraType.Caption)
}

private val QuickMinutes = listOf(30, 60, 120)

/**
 * Saved custom sessions, one tap from starting now: the most common thing a person does after
 * setting up. Strict ones (Always Block, Cooldown, Friction) still ask for the commitment first.
 */
@Composable
fun QuickStart(configs: List<UsageConfig>, d: ProtectData, app: AuroraApplication, primary: Boolean = true) {
    val colors = Aurora.colors
    val context = LocalContext.current
    val notifications = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { }
    // While Home asks to turn Shield on, that is its one filled action.
    val access = rememberAccess(Access.Accessibility)
    val connected by ShieldService.connected.collectAsStateWithLifecycle()
    val enforcing = app.runsNow(d.time).isNotEmpty() || d.sessions.isNotEmpty() || d.rules.activations.any { it.enabled }
    val attention = protectionStatus(enforcing, blocked = false, access, connected, d.list.readable && d.rulesReadable) !in setOf(ProtectionStatus.Active, ProtectionStatus.Off)
    val haptics = androidx.compose.ui.platform.LocalHapticFeedback.current
    var pending by remember { mutableStateOf<Pair<UsageConfig, Int>?>(null) }
    var problem by remember { mutableStateOf<Pair<String, ActivationProblem>?>(null) }
    fun start(c: UsageConfig, minutes: Int) {
        // Asked first: this card leaves Home as soon as the run starts. The first time protection starts,
        // Aurora asks to show it in the notification shade (Android 13+).
        if (android.os.Build.VERSION.SDK_INT >= 33 && !SessionNotification.allowed(context)) {
            runCatching { notifications.launch(android.Manifest.permission.POST_NOTIFICATIONS) }
        }
        // The application's scope, so leaving Home can't cancel a start halfway.
        haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.Confirm)
        app.scope.launch {
            val p = try { app.activate(c, Timing.Now(minutes)) } catch (_: IOException) { return@launch }
            problem = p?.let { c.id to it }
        }
    }
    HomeLabel(stringResource(R.string.home_ready_title))
    configs.forEachIndexed { index, c ->
        val shield = d.rules.shieldOf(c)
        val intent = d.rules.intentOf(c)
        val keys = (shield?.keys.orEmpty() + intent?.keys.orEmpty()).distinct()
        Column(
            Modifier.rise().padding(horizontal = Space.M).padding(top = Space.XS).fillMaxWidth()
                .auroraSurface(colors, edge = shield?.let { modeColor(it.mode) }).padding(Space.L),
            verticalArrangement = Arrangement.spacedBy(Space.S),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.S)) {
                Text(c.name, Modifier.weight(1f), color = colors.textPrimary, style = AuroraType.Title)
                IconStack(keys, d, max = 4, size = 28.dp)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(Space.XS), verticalAlignment = Alignment.CenterVertically) {
                shield?.let { if (it.name == c.name) ModeChip(it.mode) else ModeChip(it.mode, label = it.name) }
                intent?.let { IntentChip(it.name) }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.XS)) {
                QuickMinutes.forEach { m ->
                    AuroraButton(
                        minutesText(m),
                        { if (shield?.mode?.strict == true) pending = c to m else start(c, m) },
                        Modifier.weight(1f),
                        // Only the first card's middle choice is filled: one primary action on Home.
                        kind = if (primary && index == 0 && m == 60 && !attention) ButtonKind.Primary else ButtonKind.Secondary,
                        compact = true,
                    )
                }
            }
            problem?.takeIf { it.first == c.id }?.let { (_, p) ->
                Text(problemText(p), color = colors.danger, style = AuroraType.Secondary)
            }
        }
    }
    pending?.let { (c, m) ->
        AuroraDialog(
            stringResource(R.string.home_ready_confirm, c.name, minutesText(m)), { pending = null },
            stringResource(R.string.always_block_confirm), { pending = null; start(c, m) },
            stringResource(R.string.start_commit_body) + "\n\n" + stringResource(R.string.start_commit_limits), stringResource(R.string.action_cancel),
        )
    }
}
