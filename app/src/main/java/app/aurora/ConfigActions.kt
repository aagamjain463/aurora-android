package app.aurora

import app.aurora.plan.EntitlementPolicy
import app.aurora.plan.LimitDecision
import app.aurora.progress.GoalStatus
import app.aurora.progress.Goal
import app.aurora.progress.GoalKind
import app.aurora.progress.ProtectionRecord
import app.aurora.progress.reaching
import app.aurora.progress.record
import app.aurora.protect.Activation
import app.aurora.protect.ActivationProblem
import app.aurora.protect.ActiveTargets
import app.aurora.protect.FreeKeep
import app.aurora.protect.Refusal
import app.aurora.protect.activeGoalIds
import app.aurora.protect.activeTargets
import app.aurora.protect.cover
import app.aurora.protect.goalsReconciled
import app.aurora.account.Authorization
import app.aurora.plan.Plan
import app.aurora.account.serverName
import app.aurora.plan.LimitReason
import app.aurora.plan.Remedy
import app.aurora.protect.CommitmentAuthorization
import app.aurora.protect.authorizationsToRelease
import app.aurora.protect.occurrencesToAuthorize
import app.aurora.protect.ledgerEntry
import app.aurora.protect.requestedIntent
import app.aurora.protect.requestedShield
import app.aurora.protect.retainedIntent
import app.aurora.protect.retainedShield
import app.aurora.protect.runsRefusedByQuota
import app.aurora.protect.targetsRefusal
import app.aurora.protect.usesQuota
import app.aurora.protect.ConfigRun
import app.aurora.protect.Decision
import app.aurora.protect.DeviceTime
import app.aurora.protect.IntentPreset
import app.aurora.protect.FrictionSettings
import app.aurora.protect.Mode
import app.aurora.protect.Session
import app.aurora.protect.ShieldPreset
import app.aurora.protect.Timing
import app.aurora.protect.UsageConfig
import app.aurora.protect.Rules
import app.aurora.protect.attempted
import app.aurora.protect.endedWindows
import app.aurora.protect.entitledActivations
import app.aurora.protect.runsFor
import app.aurora.protect.sessionClash
import app.aurora.protect.unlockKeys
import app.aurora.protect.intentConfigsOf
import app.aurora.protect.UnlockUse
import app.aurora.protect.sessionConflict
import app.aurora.protect.sessionLimitRefusal
import app.aurora.protect.modeOf
import app.aurora.protect.shownMode
import app.aurora.protect.runningShield
import app.aurora.protect.intentsEnded
import app.aurora.protect.intentOf
import app.aurora.protect.key
import app.aurora.protect.liveRuns
import app.aurora.protect.lockedUntil
import app.aurora.protect.migratedContent
import app.aurora.protect.migratedToConfigurations
import app.aurora.protect.problem
import app.aurora.protect.runsToMaterialize
import app.aurora.protect.shieldOf
import java.io.IOException
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock

/*
 * The things a person does with presets, configurations, activations and goals (docs/CONFIGURATIONS.md).
 * Like Actions.kt: each change is one or two transactions, and callers handle IOException.
 */

private val zone: ZoneId get() = ZoneId.systemDefault()

private fun dayOf(wall: Long): LocalDate = Instant.ofEpochMilli(wall).atZone(zone).toLocalDate()

/** Version 0 → 1, once, when everything has loaded. Idempotent. */
suspend fun AuroraApplication.migrateConfigurations() {
    val list = protectedList.value ?: return
    val r = rules.value ?: return
    if (r.version < 1) rules.update { it.migratedToConfigurations(list.items.map { i -> i.key }, System.currentTimeMillis()) }
    if (r.content.isNotEmpty()) rules.update { it.migratedContent(System.currentTimeMillis()) }
}

/**
 * Onboarding still asks in the familiar way (apps, a mode, Intent apps). When it finishes, those choices
 * become presets the same way a migration makes them: saved Shields, and Intent on for the apps chosen.
 * Running it again (replayed introduction) replaces what it made before instead of duplicating it.
 */
suspend fun AuroraApplication.adoptOnboarding() {
    val list = protectedList.value ?: return
    rules.update { r ->
        val made = r.copy(version = 0, shields = emptyList(), intentPresets = emptyList(), configs = emptyList(), activations = emptyList(), schedules = emptyList())
            .migratedToConfigurations(list.items.map { it.key }, System.currentTimeMillis())
        fun <T> merge(old: List<T>, new: List<T>, id: (T) -> String) = old.filterNot { o -> new.any { id(it) == id(o) } } + new
        r.copy(
            version = 1,
            shields = merge(r.shields, made.shields) { it.id },
            intentPresets = merge(r.intentPresets, made.intentPresets) { it.id },
            configs = merge(r.configs, made.configs) { it.id },
            activations = merge(r.activations, made.activations) { it.id },
        )
    }
}

/**
 * What Home can start in one tap: saved custom configurations, newest first, then saved Shields and Intents
 * that no configuration uses yet. A preset starts as a one-off configuration (not saved), like Normal Use.
 */
fun Rules.readyToStart(max: Int = 3): List<UsageConfig> {
    val used = configs.flatMap { it.allShieldIds + it.allIntentIds }.toSet()
    val presets = shields.filter { it.id !in used && it.keys.isNotEmpty() }.map { UsageConfig("preset:${it.id}", it.name, custom = false, shield = it, createdAt = it.createdAt) } +
        intentPresets.filter { it.id !in used && it.keys.isNotEmpty() }.map { UsageConfig("preset:${it.id}", it.name, custom = false, intent = it, createdAt = it.createdAt) }
    return (configs.filter { it.custom }.sortedByDescending { it.createdAt } + presets.sortedByDescending { it.createdAt }).take(max)
}

/** A Normal Session: its own copy of a Shield, saved nowhere. */
fun normalSession(name: String, keys: List<String>, mode: Mode, friction: FrictionSettings = FrictionSettings(), cooldownMinutes: Int = 5): UsageConfig {
    val now = System.currentTimeMillis()
    return UsageConfig(newId(), name, custom = false, shield = ShieldPreset(newId(), name, keys, mode, friction = friction, cooldownWaitMinutes = cooldownMinutes, createdAt = now), createdAt = now)
}

/** The runs live right now (stored and not yet stored), for screens. */
fun AuroraApplication.runsNow(t: DeviceTime = now()): List<ConfigRun> {
    val r = rules.value ?: return emptyList()
    val rt = runtime.value ?: return emptyList()
    return liveRuns(r, rt, plan, t, zone).filter { it.covers(t) }
}

// ---- The one way rules that can activate protection change -------------------------------------------

/** The targets of a running Focus. */
private fun AuroraApplication.focusKeys(t: DeviceTime, r: Rules): Set<String> =
    runtime.value?.focus?.takeUnless { it.isOver(t) }?.let { r.cover(it.groupId, protectedList.value?.active(plan)?.map { i -> i.key }?.toSet().orEmpty()) }.orEmpty()

/**
 * Why [next] can't replace [old]: it would switch on targets past the plan's limit. The check is on the
 * union after the change, so replacing a target is fine. Over-limit data already saved never refuses an
 * edit that adds nothing.
 */
private fun AuroraApplication.refusalOf(next: Rules, old: Rules): Refusal? {
    val t = now()
    val rt = runtime.value ?: return null
    val added = (next.requestedShield(t.wall, zone) - old.requestedShield(t.wall, zone).toSet()).toSet()
    val addedIntent = (next.requestedIntent(t.wall, zone) - old.requestedIntent(t.wall, zone).toSet()).toSet()
    if (added.isEmpty() && addedIntent.isEmpty()) return null
    // What keeps its slot after the change: what is running, and what was retained and is still on.
    val running = activeTargets(Rules(), rt, t, zone, plan, focusKeys(t, next))
    val shieldKeep = old.retainedShield(plan, t.wall, zone)
    val intentKeep = old.retainedIntent(plan, t.wall, zone)
    val staying = ActiveTargets(
        running.shield + next.requestedShield(t.wall, zone).filter { it !in added && (shieldKeep == null || it in shieldKeep) },
        running.intent + next.requestedIntent(t.wall, zone).filter { it !in addedIntent && (intentKeep == null || it in intentKeep) },
    )
    return targetsRefusal(plan, staying, added, addedIntent)
}

/**
 * Changes the rules if the result stays within the plan, in one transaction, or changes nothing and says
 * why. Every edit that can switch on a target comes through here, whichever screen asks.
 */
suspend fun AuroraApplication.changeRules(transform: (Rules) -> Rules): Refusal? = entitlements.withLock {
    var refusal: Refusal? = null
    rules.update { r ->
        val next = transform(r)
        refusal = refusalOf(next, r)
        if (refusal == null) next else r
    }
    refusal?.also { explain(it) }
}

/** Shows why, at the app's root. The screen that asked changes nothing. */
fun AuroraApplication.explain(refusal: Refusal, shorter: ((Int) -> Unit)? = null) {
    refusal.decision?.let { limitPrompt.value = LimitPrompt(it, shorter) }
}

// ---- Presets ---------------------------------------------------------------------------------------

/**
 * Saves [p] (new or edited). Running protection keeps the copy it started with; future runs use this. A preset
 * nothing activates can be anything; one that is switched on is held to the plan.
 */
suspend fun AuroraApplication.saveShield(p: ShieldPreset): Refusal? = changeRules { r -> r.copy(shields = r.shields.filterNot { it.id == p.id } + p) }

suspend fun AuroraApplication.saveIntentPreset(p: IntentPreset): Refusal? = changeRules { r -> r.copy(intentPresets = r.intentPresets.filterNot { it.id == p.id } + p) }

/** Names of the configurations using a preset: a preset in use can't be deleted until they change. */
fun AuroraApplication.configsUsing(presetId: String): List<String> =
    rules.value?.configs.orEmpty().filter { presetId in it.allShieldIds || presetId in it.allIntentIds }.map { it.name }

/** Deletes a preset no configuration uses. Returns false (nothing deleted) when one does. */
suspend fun AuroraApplication.deletePreset(id: String): Boolean {
    var done = false
    rules.update { r ->
        if (r.configs.any { id in it.allShieldIds || id in it.allIntentIds }) r
        else {
            done = true
            r.copy(shields = r.shields.filterNot { it.id == id }, intentPresets = r.intentPresets.filterNot { it.id == id })
        }
    }
    return done
}

fun newId(): String = UUID.randomUUID().toString()

// ---- Configurations --------------------------------------------------------------------------------

/** Saves [c]. Refused (nothing saved) when an app is in two of its Shields and Intents. */
suspend fun AuroraApplication.saveConfig(c: UsageConfig): Refusal? {
    rules.value?.copy(configs = rules.value!!.configs.filterNot { it.id == c.id } + c)?.sessionClash(c)?.let { return Refusal(ActivationProblem.Conflict, conflict = it) }
    return changeRules { r -> r.copy(configs = r.configs.filterNot { it.id == c.id } + c) }
}

/**
 * Deletes a configuration and its schedules. Goals linked to it go with it, and only when [removeGoals]
 * says the person chose that. Run history stays. Runs already going keep running: deleting
 * can't end a commitment early.
 */
suspend fun AuroraApplication.deleteConfig(id: String, removeGoals: Boolean): Boolean {
    syncRuns()
    var done = false
    rules.update { r ->
        if (!removeGoals && r.trackGoals.any { it.configId == id }) r
        else {
            done = true
            r.copy(
                configs = r.configs.filterNot { it.id == id },
                activations = r.activations.filterNot { it.configId == id },
                trackGoals = r.trackGoals.filterNot { it.configId == id },
            )
        }
    }
    return done
}

/**
 * Applies [c] at [timing]: a run now, or a stored activation. Returns why not when it can't, and nothing
 * changes then. Order for a start now: check the plan, then reserve the time, store the commitment and
 * start enforcing it, all in one transaction. The lock means two starts can't both claim the last slot or minutes.
 */
suspend fun AuroraApplication.activate(c: UsageConfig, timing: Timing): Refusal? = entitlements.withLock {
    syncLocked()
    val r = rules.value ?: throw IOException("Rules can't be read")
    val rt0 = runtime.value ?: throw IOException("Runtime can't be read")
    val t = now()
    val withConfig = r.copy(configs = r.configs.filterNot { it.id == c.id } + c)
    withConfig.problem(c, timing, plan, rt0, t, zone, focusKeys(t, withConfig))?.let { refusal ->
        val left = (refusal.decision?.remaining ?: 0) / 60_000
        // A start that doesn't fit offers what does.
        val fits = timing is Timing.Now && left >= 1 && refusal.problem == ActivationProblem.OverCommittedAllowance
        explain(refusal, if (fits) { minutes -> scope.launch { saving { activate(c, Timing.Now(minutes)) } } } else null)
        return@withLock refusal
    }
    withConfig.sessionConflict(c, timing, rt0, t, zone)?.let { return@withLock Refusal(ActivationProblem.Conflict, conflict = it) }
    if (timing !is Timing.Now) {
        rules.update { x ->
            x.copy(configs = x.configs.filterNot { it.id == c.id } + c, activations = x.activations + Activation(newId(), c.id, timing, true, t.wall))
        }
        syncLocked()
        return@withLock null
    }
    val shield = withConfig.runningShield(c)
    val mode = withConfig.modeOf(c)
    val runId = "now:${newId()}"
    if (mode.usesQuota) serverRefusal(runId, mode, timing.minutes, null)?.let { refusal ->
        val left = (refusal.decision?.remaining ?: 0) / 60_000
        explain(refusal, if (left >= 1) { minutes -> scope.launch { saving { activate(c, Timing.Now(minutes)) } } } else null)
        return@withLock refusal
    }
    val run = ConfigRun(
        runId, c.id, c.name, null, c.custom, shield, withConfig.intentOf(c), c.focus,
        start = t.wall, end = t.wall + timing.minutes * 60_000L, time = Session.start(t, timing.minutes), note = c.note.trim(), mode = mode,
        intentConfigs = withConfig.intentConfigsOf(c), unlocks = c.unlocks.filter { it.on },
    )
    runtime.update { rt ->
        val base = rt.pruned(t)
        if (mode.usesQuota) base.copy(runs = base.runs + run, ledger = base.ledger + base.ledgerEntry(run.start, run.end!!, t, zone))
        else base.copy(runs = base.runs + run)
    }
    if (c.custom) rules.update { x -> x.copy(configs = x.configs.filterNot { it.id == c.id } + c) }
    history.update { it.withProtection(ProtectionRecord(run.id, t.wall, run.end!!, shield?.keys.orEmpty().toSet())) }
    if (mode == Mode.AlwaysBlock) history.update { it.withAlwaysBlock(dayOf(t.wall), t.wall, timing.minutes) }
    null
}

/**
 * Whether the server allows a new commitment on Free. Null means go. The lock is held across this network call on
 * purpose: nothing else can spend the allowance meanwhile, and the root shows that Aurora is confirming.
 */
private val refusalThrottle = java.util.concurrent.ConcurrentHashMap<String, Long>()
private val lastAsked = java.util.concurrent.ConcurrentHashMap<String, Long>()

internal fun resetQuotaAuthorizations() {
    refusalThrottle.clear()
    lastAsked.clear()
}

internal suspend fun AuroraApplication.serverRefusal(
    commitmentId: String,
    mode: Mode,
    minutes: Int,
    startAt: Long?,
    silent: Boolean = false,
): Refusal? {
    if (plan == Plan.Plus) return null
    val t = now()
    val last = refusalThrottle[commitmentId]
    if (last != null && t.elapsed - last < 60_000L) {
        return Refusal(ActivationProblem.NeedsConnection)
    }
    if (!silent) account.authorizing.value = true
    val answer = try {
        account.authorize(commitmentId, mode.serverName, minutes, startAt ?: t.wall, zone)
    } finally {
        if (!silent) account.authorizing.value = false
    }
    return when (answer) {
        is Authorization.Granted -> {
            refusalThrottle.remove(commitmentId)
            null
        }
        Authorization.Unreachable -> {
            refusalThrottle[commitmentId] = t.elapsed
            Refusal(ActivationProblem.NeedsConnection)
        }
        is Authorization.Denied -> {
            refusalThrottle.remove(commitmentId)
            val q = answer.quota
            Refusal(
                ActivationProblem.OverCommittedAllowance,
                LimitDecision(
                    false, LimitReason.CommittedProtection, q.usedMs, q.limitMs, q.remainingMs,
                    if (q.remainingMs >= 60_000L) Remedy.ChooseShorter else Remedy.WaitForReset, upgradeResolves = true, resetAt = q.resetAt,
                ),
            )
        }
    }
}

private class OccurrenceAnswers(val granted: Map<String, CommitmentAuthorization>, val denied: List<ConfigRun>, val waiting: List<ConfigRun>, val released: Set<String>)

/** When each occurrence last asked and got no answer, so a dead network isn't asked on every Shield event. */
private const val AskAgainMs = 60_000L
private const val LookAheadMs = 6 * 60 * 60_000L

/** Asks the server about committed occurrences on Free. Those it can't confirm wait, unenforced, and ask again. */
private suspend fun AuroraApplication.authorizeOccurrences(r: Rules, t: DeviceTime): OccurrenceAnswers {
    val rt = runtime.value ?: return OccurrenceAnswers(emptyMap(), emptyList(), emptyList(), emptySet())
    val granted = mutableMapOf<String, CommitmentAuthorization>()
    val denied = mutableListOf<ConfigRun>()
    val waiting = mutableListOf<ConfigRun>()
    for (p in occurrencesToAuthorize(r, rt, plan, t, zone, LookAheadMs)) {
        val now = p.run.covers(t)
        val asked = lastAsked[p.run.id]
        if (asked != null && t.elapsed - asked < AskAgainMs) {
            if (now) waiting += p.run
            continue
        }
        when (val answer = account.authorize(p.run.id, p.mode.serverName, p.minutes, p.run.start, zone)) {
            is Authorization.Granted -> {
                granted[p.run.id] = CommitmentAuthorization(answer.reservationId, p.run.activationId, answer.start, answer.end)
                lastAsked.remove(p.run.id)
            }
            is Authorization.Denied -> {
                lastAsked.remove(p.run.id)
                denied += p.run
            }
            Authorization.Unreachable -> {
                lastAsked[p.run.id] = t.elapsed
                if (now) waiting += p.run
            }
        }
    }
    val released = authorizationsToRelease(r, rt, t.wall).filter { (_, auth) -> auth.reservationId == null || account.release(auth.reservationId) }.keys
    return OccurrenceAnswers(granted, denied, waiting, released)
}

/**
 * Stores every occurrence that has started (freezing its presets) and settles every run that is over. A
 * committed occurrence reserves its time here, on the plan as it is now. One the allowance can't cover
 * doesn't start, and the person is told once. Called when aurora starts, from Home, and from the Shield
 * service. Idempotent.
 */
suspend fun AuroraApplication.syncRuns() = entitlements.withLock { syncLocked() }

private suspend fun AuroraApplication.syncLocked() {
    val r = rules.value ?: return
    if (runtime.value == null || !billing.loaded) return
    val t = now()
    if (r.goalsReconciled(plan, t.wall) != r) rules.update { it.goalsReconciled(plan, t.wall) }
    val started = mutableListOf<ConfigRun>()
    val ended = mutableListOf<ConfigRun>()
    val refused = mutableListOf<ConfigRun>()
    val answers = authorizeOccurrences(r, t)
    runtime.update { rt ->
        // A run stored with no start (an early build of this migration) starts from now instead of 1970.
        var next = if (rt.runs.none { it.start <= 0 }) rt else rt.copy(runs = rt.runs.map { if (it.start <= 0) it.copy(start = t.wall) else it })
        next = next.copy(authorizations = (next.authorizations + answers.granted - answers.released).filterValues { it.end > t.wall })
        refused += answers.denied.filter { it.id !in next.quotaDenied }
        refused += runsRefusedByQuota(r, next, plan, t, zone).filter { it.id !in next.quotaDenied }
        if (refused.isNotEmpty()) next = next.copy(quotaDenied = next.quotaDenied + refused.map { it.id to (it.end ?: (t.wall + 86_400_000L)) })
        for (run in runsToMaterialize(r, next, plan, t, zone)) {
            val end = run.end
            if (run.sessionMode.usesQuota && end != null) next = next.copy(ledger = next.ledger + next.ledgerEntry(run.start, end, t, zone))
            started += run
            next = next.copy(runs = next.runs + run)
        }
        ended += next.runs.filter { it.isOver(t) }
        if (ended.isEmpty() && started.isEmpty() && next.runs == rt.runs && next.quotaDenied == rt.quotaDenied && next.authorizations == rt.authorizations) rt
        else next.copy(runs = next.runs - ended.toSet(), closed = next.closed + ended.map { it.id to (it.endAt(t) ?: t.wall) })
            .intentsEnded(ended.flatMap { it.intent?.keys.orEmpty() }, (next.runs - ended.toSet()).flatMap { it.intent?.keys.orEmpty() }.toSet())
    }
    refused.forEach { SessionNotification.quotaRefused(this, it.name) }
    answers.waiting.forEach { SessionNotification.authorizationWaiting(this, it.id, it.name) }
    if (started.isNotEmpty()) history.update { h -> started.fold(h) { acc, run -> acc.withStarted(run) } }
    if (ended.isNotEmpty()) {
        val spans = history.value?.service.orEmpty()
        val usage = usageSince(ended.minOf { it.startAt(t) }, t.wall)
        // A carried commitment was another account's: it ends here, and isn't this one's history.
        val rt = runtime.value
        history.update { h -> ended.filterNot { it.carried }.fold(h) { acc, run -> acc.withRun(recordOf(run, run.endAt(t) ?: t.wall, turnedOff = false, spans, usage, rt.stoppedFor(run))) } }
    }
    // Occurrences that came and went without being stored (the phone untouched all window long) still
    // get a record, judged on the same evidence. Only since this version began recording evidence.
    val h = history.value
    val evidenceFrom = h?.service?.firstOrNull()?.start
    if (h != null && evidenceFrom != null) {
        val known = h.runs.map { it.id }.toSet() + runtime.value?.closed?.keys.orEmpty() + runtime.value?.runs.orEmpty().map { it.id } + runtime.value?.quotaDenied?.keys.orEmpty()
        val entitled = r.entitledActivations(plan, t.wall, zone)
        val missed = r.activations.filter { it.id in entitled }.flatMap { a ->
            a.endedWindows(maxOf(evidenceFrom, t.wall - 2 * 86_400_000L), t.wall, zone).flatMap { w ->
                r.runsFor(a, w, plan, t.wall, zone).filter { it.id !in known }
            }
        }
        // Screen-off time counts as away, so a window slept through is protected time like any other.
        val usage = if (missed.isEmpty()) null else usageSince(missed.minOf { it.start }, t.wall)
        if (missed.isNotEmpty()) history.update { x -> missed.fold(x) { acc, run -> acc.withStarted(run).withRun(recordOf(run, run.end ?: t.wall, turnedOff = false, x.service, usage)) } }
    }
    // No strict commitment running: the clock follows the system clock again (TrustedClock.kt).
    policySnapshot(emptySet(), emptyMap())?.takeIf { it.lockedUntil(t.wall) == null }?.let { app.aurora.protect.TrustedClock.relax() }
    // A Later activation whose window is over is done; Normal Use configurations nothing uses are removed.
    val stale = r.activations.filter { a -> (a.timing as? Timing.Later)?.let { it.start + it.minutes * 60_000L <= t.wall } == true }
    if (stale.isNotEmpty()) rules.update { x ->
        val activations = x.activations - stale.toSet()
        x.copy(activations = activations, configs = x.configs.filter { c -> c.custom || activations.any { it.configId == c.id } })
    }
}


/** A run has begun: its protection span, and for a scheduled Strict what one started by hand earns, its committed minutes. */
private fun app.aurora.progress.History.withStarted(run: ConfigRun): app.aurora.progress.History {
    val recorded = withProtection(ProtectionRecord(run.id, run.start, run.end ?: Long.MAX_VALUE, run.shield?.keys.orEmpty().toSet()))
    val until = run.committedUntil ?: run.end
    return if (run.sessionMode == Mode.AlwaysBlock && until != null) recorded.withAlwaysBlock(dayOf(run.start), run.start, ((until - run.start) / 60_000).toInt()) else recorded
}

/** Android's usage records from [from] to [to] for judging runs, or null without usage access. */
private suspend fun AuroraApplication.usageSince(from: Long, to: Long) = runCatching { screenTime.intervals(from, to) }.getOrNull()

/** [run]'s unlocks so far that stopped its Focus timer. */
private fun app.aurora.protect.Runtime?.stoppedFor(run: ConfigRun): List<Pair<Long, Long>> =
    this?.unlocks?.get(run.id).orEmpty().filterNot { it.focusRuns }.map { it.start to it.end }

private fun recordOf(
    run: ConfigRun, end: Long, turnedOff: Boolean, spans: List<app.aurora.progress.ServiceSpan>, usage: List<app.aurora.data.UsageInterval>?,
    stopped: List<Pair<Long, Long>> = emptyList(),
) = record(
    run.id, run.configId, run.name, run.custom,
    run.shield?.name, run.shownMode, run.intent?.name, run.shield?.keys.orEmpty().toSet() + run.intent?.keys.orEmpty(),
    run.time?.startWall ?: run.start, run.end ?: run.time?.let { it.startWall + it.durationMs }, end, spans.reaching(end, app.aurora.protect.ShieldService.connected.value), turnedOff,
    run.focus?.task, run.focus?.minutes?.let { it * 60_000L } ?: ((run.end ?: end) - run.start), run.intentOverruns,
    // Intent apps are meant to be used: only the Shield's apps say anything when used while the service was down.
    run.accessGrants, run.intentRelapses, usage?.filter { it.packageName in run.shield?.keys.orEmpty() },
    stopped = stopped.mapNotNull { (s, e) -> (s to minOf(e, end)).takeIf { it.second > it.first } },
)

/**
 * Turns off a running occurrence. Strict refuses until its committed part is over, whatever calls
 * this. Friction and Cooldown come here only after their challenge or wait (EndSession). Turning off Always ends it (its activation is switched off); a Later ends; a recurring schedule
 * skips only this occurrence.
 */
suspend fun AuroraApplication.turnOffRun(runId: String): Boolean {
    syncRuns()
    val t = now()
    var stopped: ConfigRun? = null
    runtime.update { rt ->
        val run = rt.runs.find { it.id == runId }
        if (run == null || !(run.cancellable(t) || run.endsAfterTask)) rt
        else {
            stopped = run
            rt.copy(
                runs = rt.runs - run,
                closed = rt.closed + (run.id to (run.endAt(t) ?: (t.wall + 86_400_000L))),
                cooldownWaits = rt.cooldownWaits.filterValues { it.restrictionId != run.id && it.restrictionId != "${run.id}:flex" },
            ).intentsEnded(run.intent?.keys.orEmpty(), (rt.runs - run).flatMap { it.intent?.keys.orEmpty() }.toSet())
        }
    }
    val run = stopped ?: return false
    run.activationId?.let { aid ->
        rules.update { r ->
            r.copy(activations = r.activations.mapNotNull { a ->
                when {
                    a.id != aid -> a
                    a.timing is Timing.Always -> a.copy(enabled = false)
                    a.timing is Timing.Later -> null
                    else -> a
                }
            })
        }
    }
    val spans = history.value?.service.orEmpty()
    val rt = runtime.value
    history.update { it.endProtection(run.id, t.wall).withRun(recordOf(run, t.wall, turnedOff = true, spans, usage = null, rt.stoppedFor(run))) }
    return true
}

/**
 * Pauses or resumes a schedule or an Always activation. Re-enabling Always starts a new occurrence from now.
 * Resuming is activating: it answers to the plan like any other way in.
 */
suspend fun AuroraApplication.setActivationEnabled(id: String, enabled: Boolean): Refusal? {
    syncRuns()
    // Resuming is activating: Free's app limits count what will be on beside it, at its own times.
    if (enabled) {
        val r = rules.value
        val rt = runtime.value
        val a = r?.activations?.find { it.id == id }
        if (r != null && rt != null && a != null) {
            val t = now()
            val timing = a.timing
            val refusal = r.configs.find { it.id == a.configId }?.let { r.sessionLimitRefusal(it, timing, plan, rt, t, zone, excluding = id) }
            if (refusal != null) { explain(refusal); return refusal }
        }
    }
    val now = now().wall
    val refusal = changeRules { r ->
        r.copy(activations = r.activations.map { a ->
            if (a.id != id) a else a.copy(enabled = enabled, createdAt = if (enabled && a.timing is Timing.Always) now else a.createdAt)
        })
    }
    syncRuns()
    return refusal
}

/** Removes a schedule. Its occurrence already running (frozen) continues: Strict can't be ended this way. */
suspend fun AuroraApplication.deleteActivation(id: String) {
    syncRuns()
    rules.update { r ->
        val activations = r.activations.filterNot { it.id == id }
        r.copy(activations = activations, configs = r.configs.filter { c -> c.custom || activations.any { it.configId == c.id } })
    }
}

// ---- Unlocks ---------------------------------------------------------------------------------------

/**
 * Uses [unlockId] in running session [runId]: what it opens stops for its time, then is blocked again on its own. Refused
 * (false) when it has no uses left, another unlock is on, or the session is over. Never past the session's end.
 */
suspend fun AuroraApplication.useUnlock(runId: String, unlockId: String): Boolean {
    val t = now()
    val r = rules.value ?: return false
    var done = false
    runtime.update { rt ->
        val run = rt.runs.find { it.id == runId && it.covers(t) } ?: return@update rt
        val u = run.unlocks.find { it.id == unlockId } ?: return@update rt
        val used = rt.unlocks[run.id].orEmpty()
        if (run.left(u, used) <= 0 || run.unlockedAt(used, t.wall) != null) return@update rt
        done = true
        val end = minOf(run.endAt(t) ?: Long.MAX_VALUE, t.wall + u.minutes * 60_000L)
        rt.copy(unlocks = rt.unlocks + (run.id to used + UnlockUse(u.id, t.wall, end, r.unlockKeys(r.configs.find { it.id == run.configId }, u), u.focusRuns)))
    }
    return done
}

/** Ends the unlock on in session [runId] now: what it opened is blocked again at once. It still counts as used. */
suspend fun AuroraApplication.endUnlock(runId: String) {
    val now = now().wall
    runtime.update { rt ->
        val uses = rt.unlocks[runId] ?: return@update rt
        rt.copy(unlocks = rt.unlocks + (runId to uses.map { if (now >= it.start && now < it.end) it.copy(end = now) else it }))
    }
}

// ---- At the Shield ---------------------------------------------------------------------------------

/** A genuine new attempt to open a shielded app (Interceptor): both cooldowns restart from now. */
suspend fun AuroraApplication.attempted(decision: Decision.Shield) {
    val now = now().wall
    runtime.update { it.attempted(decision.packageName, decision, now) }
}

/** The person continued past their chosen time at a reminder: the runs with that Intent app note it. */
suspend fun AuroraApplication.intentOverrun(key: String) {
    val t = now()
    runtime.update { rt ->
        rt.copy(runs = rt.runs.map { if (it.covers(t) && key in it.intent?.keys.orEmpty()) it.copy(intentOverruns = it.intentOverruns + 1) else it })
    }
}

private var lastSeen = 0L

/** Records that the Shield service is bound now (at most once a minute), or that it just unbound. */
suspend fun AuroraApplication.serviceSeen(connected: Boolean, newSpan: Boolean = false) {
    val now = now().wall
    if (connected && !newSpan && now - lastSeen < 60_000L) return
    lastSeen = now
    history.update { it.serviceSeen(now, connected, newSpan) }
}

// ---- Goals -----------------------------------------------------------------------------------------

/**
 * Saves [g]. A Goal saved as active must fit the plan: the check runs in the same transaction as the
 * write, so two saves can't both take the last slot. Refused, nothing is stored.
 */
suspend fun AuroraApplication.saveGoal(g: Goal): LimitDecision {
    var decision = LimitDecision(true)
    val now = now().wall
    rules.update { r ->
        val others = r.copy(trackGoals = r.trackGoals.filterNot { it.id == g.id })
        decision = if (g.status == GoalStatus.Active) EntitlementPolicy.canActivateGoal(plan, others.activeGoalIds(plan).size) else LimitDecision(true)
        if (decision.allowed) others.copy(trackGoals = others.trackGoals + g).goalsReconciled(plan, now) else r
    }
    return decision
}

/**
 * Moves a Goal to [status]. Resuming one is activating it, and answers to the plan. History, streaks and
 * linked activity stay. [replacing] moves that Goal to [replacingStatus] in the same transaction, for
 * "pause this one and start that one".
 */
suspend fun AuroraApplication.setGoalStatus(id: String, status: GoalStatus, replacing: String? = null, replacingStatus: GoalStatus = GoalStatus.Paused): LimitDecision {
    var decision = LimitDecision(true)
    val now = now().wall
    rules.update { r ->
        val freed = r.copy(trackGoals = r.trackGoals.map { if (it.id == replacing) it.copy(status = replacingStatus) else it })
        val others = freed.copy(trackGoals = freed.trackGoals.filterNot { it.id == id })
        decision = if (status == GoalStatus.Active) EntitlementPolicy.canActivateGoal(plan, others.activeGoalIds(plan).size) else LimitDecision(true)
        if (decision.allowed) freed.copy(trackGoals = freed.trackGoals.map { if (it.id == id) it.copy(status = status) else it }).goalsReconciled(plan, now) else r
    }
    return decision
}

suspend fun AuroraApplication.deleteGoal(id: String) {
    rules.update { r -> r.copy(trackGoals = r.trackGoals.filterNot { it.id == id }) }
    history.update { it.copy(goalUsage = it.goalUsage - id) }
}

/**
 * Each limit goal's foreground time per ISO date. Finished days are stored while Android still holds them;
 * a day it can't read stays unknown. [includeToday] adds today so far, which is never stored.
 */
suspend fun AuroraApplication.goalUsage(includeToday: Boolean): Map<String, Map<String, Long>> {
    val goals = rules.value?.trackGoals.orEmpty().filter { it.kind == GoalKind.Limit }
    val stored = history.value?.goalUsage.orEmpty()
    if (goals.isEmpty()) return stored
    val zone = java.time.ZoneId.systemDefault()
    val today = java.time.LocalDate.now(zone)
    val read = HashMap<java.time.LocalDate, Map<String, Long>?>()
    suspend fun usageOn(date: java.time.LocalDate): Map<String, Long>? {
        if (date !in read) read[date] = screenTime.day(date)
        return read[date]
    }
    val finished = HashMap<String, Map<String, Long>>()
    val all = goals.associate { g ->
        val first = g.periodStart(g.createdOn(zone))
        val known = stored[g.id].orEmpty()
        val fresh = (app.aurora.data.UsageKeepDays.toLong() downTo 1).map(today::minusDays)
            .filter { !it.isBefore(first) && it.toString() !in known }
            .mapNotNull { date -> usageOn(date)?.let { date.toString() to g.usedIn(it) } }.toMap()
        if (fresh.isNotEmpty()) finished[g.id] = fresh
        val now = if (includeToday) usageOn(today)?.let { mapOf(today.toString() to g.usedIn(it)) }.orEmpty() else emptyMap()
        g.id to known + fresh + now
    }
    if (finished.isNotEmpty()) history.update { h ->
        h.copy(goalUsage = finished.entries.fold(h.goalUsage) { acc, (id, days) -> acc + (id to acc[id].orEmpty() + days) })
    }
    return all
}
