package app.aurora

import app.aurora.plan.Plan
import app.aurora.plan.alwaysBlockMinutesLeft
import app.aurora.plan.reserveAlwaysBlock
import app.aurora.progress.Goal
import app.aurora.progress.GoalKind
import app.aurora.progress.ProtectionRecord
import app.aurora.progress.record
import app.aurora.protect.Activation
import app.aurora.protect.ActivationProblem
import app.aurora.protect.ConfigRun
import app.aurora.protect.Decision
import app.aurora.protect.DeviceTime
import app.aurora.protect.FocusOutcome
import app.aurora.protect.IntentPreset
import app.aurora.protect.Mode
import app.aurora.protect.Session
import app.aurora.protect.ShieldPreset
import app.aurora.protect.Timing
import app.aurora.protect.UsageConfig
import app.aurora.protect.Rules
import app.aurora.protect.attempted
import app.aurora.protect.endedWindows
import app.aurora.protect.entitledActivations
import app.aurora.protect.runFor
import app.aurora.protect.intentOf
import app.aurora.protect.key
import app.aurora.protect.liveRuns
import app.aurora.protect.lockedUntil
import app.aurora.protect.migratedToConfigurations
import app.aurora.protect.problem
import app.aurora.protect.runsToMaterialize
import app.aurora.protect.shieldOf
import app.aurora.protect.toEntry
import java.io.IOException
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/*
 * The things a person does with presets, configurations, activations and goals (docs/CONFIGURATIONS.md).
 * Like Actions.kt: each change is one or two transactions, and callers handle IOException.
 */

private val zone: ZoneId get() = ZoneId.systemDefault()

private fun dayOf(wall: Long): LocalDate = Instant.ofEpochMilli(wall).atZone(zone).toLocalDate()

/** Version 0 → 1, once, when everything has loaded. Idempotent. */
suspend fun AuroraApplication.migrateConfigurations() {
    val list = protectedList.value ?: return
    if ((rules.value ?: return).version >= 1) return
    rules.update { it.migratedToConfigurations(list.items.map { i -> i.key }, System.currentTimeMillis()) }
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
    val used = configs.flatMap { listOfNotNull(it.shieldId, it.intentId) }.toSet()
    val presets = shields.filter { it.id !in used && it.keys.isNotEmpty() }.map { UsageConfig("preset:${it.id}", it.name, custom = false, shield = it, createdAt = it.createdAt) } +
        intentPresets.filter { it.id !in used && it.keys.isNotEmpty() }.map { UsageConfig("preset:${it.id}", it.name, custom = false, intent = it, createdAt = it.createdAt) }
    return (configs.filter { it.custom }.sortedByDescending { it.createdAt } + presets.sortedByDescending { it.createdAt }).take(max)
}

/** The runs live right now (stored and not yet stored), for screens. */
fun AuroraApplication.runsNow(t: DeviceTime = now()): List<ConfigRun> {
    val r = rules.value ?: return emptyList()
    val rt = runtime.value ?: return emptyList()
    return liveRuns(r, rt, plan, t, zone).filter { it.covers(t) }
}

// ---- Presets ---------------------------------------------------------------------------------------

/** Saves [p] (new or edited). Running protection keeps the copy it started with; future runs use this. */
suspend fun AuroraApplication.saveShield(p: ShieldPreset) {
    rules.update { r -> r.copy(shields = r.shields.filterNot { it.id == p.id } + p) }
}

suspend fun AuroraApplication.saveIntentPreset(p: IntentPreset) {
    rules.update { r -> r.copy(intentPresets = r.intentPresets.filterNot { it.id == p.id } + p) }
}

/** Names of the configurations using a preset: a preset in use can't be deleted until they change. */
fun AuroraApplication.configsUsing(presetId: String): List<String> =
    rules.value?.configs.orEmpty().filter { it.shieldId == presetId || it.intentId == presetId }.map { it.name }

/** Deletes a preset no configuration uses. Returns false (nothing deleted) when one does. */
suspend fun AuroraApplication.deletePreset(id: String): Boolean {
    var done = false
    rules.update { r ->
        if (r.configs.any { it.shieldId == id || it.intentId == id }) r
        else {
            done = true
            r.copy(shields = r.shields.filterNot { it.id == id }, intentPresets = r.intentPresets.filterNot { it.id == id })
        }
    }
    return done
}

fun newId(): String = UUID.randomUUID().toString()

// ---- Configurations --------------------------------------------------------------------------------

suspend fun AuroraApplication.saveConfig(c: UsageConfig) {
    rules.update { r -> r.copy(configs = r.configs.filterNot { it.id == c.id } + c) }
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
 * Applies [c] at [timing]: a run now, or a stored activation. Returns why not, when it can't (nothing
 * changes then). Always Block on Free must fit the day's remaining Always Block minutes when it starts now.
 */
suspend fun AuroraApplication.activate(c: UsageConfig, timing: Timing): ActivationProblem? {
    syncRuns()
    val r = rules.value ?: throw IOException("Rules can't be read")
    val t = now()
    val withConfig = r.copy(configs = r.configs.filterNot { it.id == c.id } + c)
    withConfig.problem(c, timing, plan, runsNow(t), t.wall)?.let { return it }
    if (timing !is Timing.Now) {
        rules.update { x ->
            x.copy(configs = x.configs.filterNot { it.id == c.id } + c, activations = x.activations + Activation(newId(), c.id, timing, true, t.wall))
        }
        syncRuns()
        return null
    }
    val shield = withConfig.shieldOf(c)
    val run = ConfigRun(
        "now:${newId()}", c.id, c.name, null, c.custom, shield, withConfig.intentOf(c), c.focus,
        start = t.wall, end = t.wall + timing.minutes * 60_000L, time = Session.start(t, timing.minutes),
    )
    var refused = false
    runtime.update { rt ->
        val base = rt.pruned(t)
        if (shield?.mode != Mode.AlwaysBlock) return@update base.copy(runs = base.runs + run)
        val reservation = reserveAlwaysBlock(plan, rt.ledger.map { it.toReservation() }, Instant.ofEpochMilli(t.wall), timing.minutes, zone)
        if (reservation == null) {
            refused = true
            rt
        } else base.copy(runs = base.runs + run, ledger = base.ledger + reservation.toEntry())
    }
    if (refused) return ActivationProblem.OverAlwaysBlockAllowance
    if (c.custom) rules.update { x -> x.copy(configs = x.configs.filterNot { it.id == c.id } + c) }
    history.update { it.withProtection(ProtectionRecord(run.id, t.wall, run.end!!, shield?.keys.orEmpty().toSet())) }
    if (shield?.mode == Mode.AlwaysBlock) history.update { it.withAlwaysBlock(dayOf(t.wall), t.wall, timing.minutes) }
    return null
}

/**
 * Stores every occurrence that has started (freezing its presets) and settles every run that is over.
 * Called when aurora starts, from Home, and from the Shield service. Idempotent.
 */
suspend fun AuroraApplication.syncRuns() {
    val r = rules.value ?: return
    if (runtime.value == null) return
    val t = now()
    val started = mutableListOf<ConfigRun>()
    val ended = mutableListOf<ConfigRun>()
    runtime.update { rt ->
        // A run stored with no start (an early build of this migration) starts from now instead of 1970.
        var next = if (rt.runs.none { it.start <= 0 }) rt else rt.copy(runs = rt.runs.map { if (it.start <= 0) it.copy(start = t.wall) else it })
        for (run in runsToMaterialize(r, rt, plan, t, zone)) {
            var committed: Long? = null
            if (run.shield?.mode == Mode.AlwaysBlock && plan != Plan.Plus) {
                // Free: the committed part is what today's Always Block allowance still covers.
                val want = (((run.end ?: t.wall) - t.wall + 59_999) / 60_000).toInt()
                val left = alwaysBlockMinutesLeft(plan, next.ledger.map { it.toReservation() }, dayOf(t.wall)) ?: want
                val minutes = minOf(want, left)
                reserveAlwaysBlock(plan, next.ledger.map { it.toReservation() }, Instant.ofEpochMilli(t.wall), minutes, zone)?.let { next = next.copy(ledger = next.ledger + it.toEntry()) }
                committed = if (minutes >= want) null else t.wall + minutes * 60_000L
            }
            val frozen = run.copy(committedUntil = committed)
            started += frozen
            next = next.copy(runs = next.runs + frozen)
        }
        ended += next.runs.filter { it.isOver(t) }
        if (ended.isEmpty() && started.isEmpty() && next.runs == rt.runs) rt
        else next.copy(runs = next.runs - ended.toSet(), closed = next.closed + ended.map { it.id to (it.endAt(t) ?: t.wall) })
    }
    if (started.isNotEmpty()) history.update { h ->
        started.fold(h) { acc, run -> acc.withProtection(ProtectionRecord(run.id, run.start, run.end ?: Long.MAX_VALUE, run.shield?.keys.orEmpty().toSet())) }
    }
    if (ended.isNotEmpty()) {
        val spans = history.value?.service.orEmpty()
        history.update { h -> ended.fold(h) { acc, run -> acc.withRun(recordOf(run, run.endAt(t) ?: t.wall, turnedOff = false, spans)) } }
    }
    // Occurrences that came and went without being stored (the phone untouched all window long) still
    // get a record, judged on the same evidence. Only since this version began recording evidence.
    val h = history.value
    val evidenceFrom = h?.service?.firstOrNull()?.start
    if (h != null && evidenceFrom != null) {
        val known = h.runs.map { it.id }.toSet() + runtime.value?.closed?.keys.orEmpty() + runtime.value?.runs.orEmpty().map { it.id }
        val entitled = r.entitledActivations(plan)
        val missed = r.activations.filter { it.id in entitled }.flatMap { a ->
            a.endedWindows(maxOf(evidenceFrom, t.wall - 2 * 86_400_000L), t.wall, zone).mapNotNull { w ->
                r.runFor(a, w)?.takeIf { it.id !in known }
            }
        }
        if (missed.isNotEmpty()) history.update { x -> missed.fold(x) { acc, run -> acc.withRun(recordOf(run, run.end ?: t.wall, turnedOff = false, x.service)) } }
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

private fun recordOf(run: ConfigRun, end: Long, turnedOff: Boolean, spans: List<app.aurora.progress.ServiceSpan>) = record(
    run.id, run.configId, run.name, run.custom,
    run.shield?.name, run.shield?.mode, run.intent?.name, run.shield?.keys.orEmpty().toSet() + run.intent?.keys.orEmpty(),
    run.time?.startWall ?: run.start, run.end ?: run.time?.let { it.startWall + it.durationMs }, end, spans + liveSpan(end), turnedOff,
    run.focus?.task, run.focus?.minutes?.let { it * 60_000L } ?: ((run.end ?: end) - run.start), run.focusOutcome, run.intentOverruns,
)

/** While the service is bound now, the current span reaches now even if its last heartbeat is older. */
private fun liveSpan(now: Long): List<app.aurora.progress.ServiceSpan> =
    if (app.aurora.protect.ShieldService.connected.value) listOf(app.aurora.progress.ServiceSpan(now - 1, now)) else emptyList()

/**
 * Turns off a running occurrence. Always Block refuses until its committed part is over, whatever calls
 * this. Turning off Always ends it (its activation is switched off); a Later ends; a recurring schedule
 * skips only this occurrence.
 */
suspend fun AuroraApplication.turnOffRun(runId: String): Boolean {
    syncRuns()
    val t = now()
    var stopped: ConfigRun? = null
    runtime.update { rt ->
        val run = rt.runs.find { it.id == runId }
        if (run == null || !run.cancellable(t)) rt
        else {
            stopped = run
            rt.copy(
                runs = rt.runs - run,
                closed = rt.closed + (run.id to (run.endAt(t) ?: (t.wall + 86_400_000L))),
                cooldownWaits = rt.cooldownWaits.filterValues { it.restrictionId != run.id && it.restrictionId != "${run.id}:flex" },
            )
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
    history.update { it.endProtection(run.id, t.wall).withRun(recordOf(run, t.wall, turnedOff = true, spans)) }
    return true
}

/** Pauses or resumes a schedule or an Always activation. Re-enabling Always starts a new occurrence from now. */
suspend fun AuroraApplication.setActivationEnabled(id: String, enabled: Boolean) {
    syncRuns()
    val now = now().wall
    rules.update { r ->
        r.copy(activations = r.activations.map { a ->
            if (a.id != id) a else a.copy(enabled = enabled, createdAt = if (enabled && a.timing is Timing.Always) now else a.createdAt)
        })
    }
    syncRuns()
}

/** Removes a schedule. Its occurrence already running (frozen) continues: Always Block can't be ended this way. */
suspend fun AuroraApplication.deleteActivation(id: String) {
    syncRuns()
    rules.update { r ->
        val activations = r.activations.filterNot { it.id == id }
        r.copy(activations = activations, configs = r.configs.filter { c -> c.custom || activations.any { it.configId == c.id } })
    }
}

/** The person says how the Focus task went while the run is still going (finished early, or done). */
suspend fun AuroraApplication.reportFocusNow(runId: String, outcome: FocusOutcome) {
    runtime.update { rt -> rt.copy(runs = rt.runs.map { if (it.id == runId) it.copy(focusOutcome = outcome) else it }) }
}

/** The person says how the Focus task of a finished run went. */
suspend fun AuroraApplication.reportFocus(recordId: String, outcome: FocusOutcome) {
    history.update { h -> h.copy(runs = h.runs.map { if (it.id == recordId) it.copy(focus = outcome) else it }) }
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

suspend fun AuroraApplication.saveGoal(g: Goal) {
    rules.update { r -> r.copy(trackGoals = r.trackGoals.filterNot { it.id == g.id } + g) }
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
