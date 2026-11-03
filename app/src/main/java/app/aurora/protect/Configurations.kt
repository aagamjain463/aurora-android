package app.aurora.protect

import app.aurora.plan.Allowance
import app.aurora.plan.EntitlementPolicy
import app.aurora.plan.LedgerEntry
import app.aurora.plan.LimitDecision
import app.aurora.plan.Plan
import app.aurora.plan.committedFits
import app.aurora.plan.keepActive
import app.aurora.plan.limit
import app.aurora.plan.quotaNow
import app.aurora.plan.quotaZone
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/*
 * The four-pillar product model (docs/CONFIGURATIONS.md). Pure Kotlin, unit tested (ConfigurationsTest).
 *
 * - A Shield preset says which apps and sites are restricted, and how (one of the four modes).
 * - An Intent preset says which apps ask for a deliberate opening, and how.
 * - A usage configuration combines at most one of each, plus optional Focus. Saving any of these never
 *   protects anything.
 * - An activation says when a configuration applies: later, on a recurring schedule, or always. Starting
 *   now creates a run directly.
 * - A run ([ConfigRun]) is one occurrence that is happening: it holds frozen copies of the presets, so
 *   editing a preset never changes protection that is already running.
 */

@Serializable
data class ShieldPreset(
    val id: String,
    val name: String,
    /** Package names and "web:<domain>" keys. */
    val keys: List<String> = emptyList(),
    val mode: Mode = Mode.Flexible,
    val friction: FrictionSettings = FrictionSettings(),
    val cooldownWaitMinutes: Int = 1,
    val createdAt: Long = 0,
)

@Serializable
data class IntentPreset(
    val id: String,
    val name: String,
    val keys: List<String> = emptyList(),
    val config: IntentConfig = IntentConfig(),
    val createdAt: Long = 0,
)

/** Focus tracked inside a configuration. [minutes] null: the configuration's own time window is the target. */
@Serializable
data class FocusPlan(val task: String, val description: String = "", val goal: String = "", val minutes: Int? = null)

/**
 * A complete setup made on Home. A custom configuration ([custom]) references saved presets by id and can
 * be linked to a Goal. A Normal Session carries its own copies of the presets ([shield], [intent]),
 * set for that use without changing the built-in defaults.
 */
@Serializable
data class UsageConfig(
    val id: String,
    val name: String,
    val custom: Boolean = true,
    val shieldId: String? = null,
    val intentId: String? = null,
    val shield: ShieldPreset? = null,
    val intent: IntentPreset? = null,
    val focus: FocusPlan? = null,
    val createdAt: Long = 0,
    /** The person's own words for why this session matters, shown on every Shield it raises. Blank: none. */
    val note: String = "",
)

/** When an activation applies. [Now] is only an instruction: it becomes a run at once and isn't stored. */
@Serializable
sealed interface Timing {
    @Serializable @SerialName("now")
    data class Now(val minutes: Int) : Timing

    /** One window from [start] (wall clock, an absolute instant: a time-zone change doesn't move it). */
    @Serializable @SerialName("later")
    data class Later(val start: Long, val minutes: Int) : Timing

    /**
     * Weekly windows in local time wherever the phone is ([days] are ISO days, 1 = Monday). A window
     * belongs to the day it starts, so overnight windows work. [until] is the last start date, inclusive.
     */
    @Serializable @SerialName("recurring")
    data class Recurring(val days: Set<Int>, val startMinute: Int, val minutes: Int, val until: String? = null) : Timing

    @Serializable @SerialName("always")
    data object Always : Timing
}

@Serializable
data class Activation(
    val id: String,
    val configId: String,
    val timing: Timing,
    val enabled: Boolean = true,
    val createdAt: Long = 0,
)

@Serializable
enum class FocusOutcome { Completed, Partial, NotCompleted }

/**
 * One occurrence of a configuration that is running or about to. [start]/[end] are wall clock; a run
 * started now also has [time], measured on the monotonic clock, so changing the clock can't end or extend
 * it. [end] null: runs until turned off (Always).
 */
@Serializable
data class ConfigRun(
    val id: String,
    val configId: String,
    val name: String,
    val activationId: String? = null,
    val custom: Boolean = true,
    val shield: ShieldPreset? = null,
    val intent: IntentPreset? = null,
    val focus: FocusPlan? = null,
    val start: Long,
    val end: Long? = null,
    val time: Session? = null,
    /** Only in runs stored by older versions: Strict was committed until here, then the Shield was Flexible. */
    val committedUntil: Long? = null,
    /** Times the person continued past their chosen time at an Intent reminder. */
    val intentOverruns: Int = 0,
    /** Times a Friction task or Cooldown wait was used to open one of this run's apps. */
    val accessGrants: Int = 0,
    /** Times an Intent app of this run was opened again within [IntentQuietMs] of its time running out. */
    val intentRelapses: Int = 0,
    /** A commitment made under another account, kept running here with nothing that says what it was. */
    val carried: Boolean = false,
    /** Frozen copy of [UsageConfig.note]. */
    val note: String = "",
) {
    fun startAt(t: DeviceTime): Long = time?.startedWall(t) ?: start
    fun endAt(t: DeviceTime): Long? = time?.endWall(t) ?: end
    fun isOver(t: DeviceTime): Boolean = time?.isOver(t) ?: (end != null && t.wall >= end)
    fun covers(t: DeviceTime): Boolean = t.wall >= startAt(t) && !isOver(t)

    /**
     * A strict run (Strict, Cooldown, Friction) can't be turned off in Aurora until it ends, or for
     * Strict on Free, until its committed part is over. An Always run (no end) made before strict
     * Cooldown and Friction stays cancellable: a permanent commitment is never made by accident.
     */
    fun cancellable(t: DeviceTime): Boolean = when {
        shield?.mode?.strict != true || end == null -> true
        shield.mode == Mode.AlwaysBlock -> committedUntil?.let { t.wall >= it } == true
        else -> false
    }

    fun restrictions(t: DeviceTime): List<Restriction> {
        val s = shield ?: return emptyList()
        val from = startAt(t)
        val to = endAt(t) ?: Long.MAX_VALUE
        val base = Restriction(
            s.mode.reason, s.keys.toSet(), from, to, id,
            friction = s.friction, cooldownWaitMs = s.cooldownWaitMinutes * 60_000L, sessionId = id, label = name,
            locks = !cancellable(t),
        )
        val c = committedUntil
        return if (s.mode == Mode.AlwaysBlock && c != null && c < to) {
            listOf(base.copy(end = maxOf(from, c)), base.copy(reason = ShieldReason.Flexible, start = maxOf(from, c), id = "$id:flex", locks = false))
        } else listOf(base)
    }
}

/** The run id of the occurrence of [a] that starts at [start]. */
fun occurrenceId(a: Activation, start: Long): String = when (a.timing) {
    is Timing.Later -> "later:${a.id}"
    // Re-enabling Always starts a new occurrence (createdAt moves), so a turned-off one never returns.
    is Timing.Always -> "always:${a.id}:${a.createdAt}"
    else -> "rec:${a.id}:$start"
}

private fun Timing.Recurring.asSchedule(id: String) =
    Schedule(id, "", days, startMinute, (startMinute + minutes.coerceIn(1, 1440)) % 1440, keys = listOf(id))

/** The window of [this] containing [now], or the next one to start, as (start, end). End MAX for Always. */
fun Activation.windows(now: Long, zone: ZoneId): List<Pair<Long, Long>> = when (val t = timing) {
    is Timing.Now -> emptyList()
    is Timing.Later -> listOf(t.start to t.start + t.minutes * 60_000L).filter { it.second > now }
    // An Always activation applies from when it was made (never before: 0 would mean 1970).
    is Timing.Always -> listOf((if (createdAt > 0) createdAt else now) to Long.MAX_VALUE)
    is Timing.Recurring -> {
        val s = t.asSchedule(id)
        val last = t.until?.let { LocalDate.parse(it) }
        listOfNotNull(s.windowAt(now, zone), s.nextStart(now, zone)?.let { s.windowAt(it, zone) })
            .filter { (start, _) -> start >= createdAt && (last == null || !Instant.ofEpochMilli(start).atZone(zone).toLocalDate().isAfter(last)) }
    }
}

/** Windows of [this] that started at or after [from] and ended by [to]: occurrences that are over. */
fun Activation.endedWindows(from: Long, to: Long, zone: ZoneId): List<Pair<Long, Long>> = when (val t = timing) {
    is Timing.Later -> listOf(t.start to t.start + t.minutes * 60_000L)
    is Timing.Recurring -> {
        val last = t.until?.let { LocalDate.parse(it) }
        t.asSchedule(id).windowsBetween(from, to, zone).filter { (start, _) ->
            start >= createdAt && (last == null || !Instant.ofEpochMilli(start).atZone(zone).toLocalDate().isAfter(last))
        }
    }
    else -> emptyList()
}.filter { (start, end) -> start >= from && end <= to }

/** The next start after [now], within a week, or null. */
fun Activation.nextStart(now: Long, zone: ZoneId): Long? = windows(now, zone).map { it.first }.firstOrNull { it > now }

/** What the person chose to keep active on Free after a downgrade. */
@Serializable
data class FreeKeep(val shield: Set<String> = emptySet(), val intent: Set<String> = emptySet(), val goalId: String? = null)

/** One identity per target: "Www.Example.com" and "example.com" are the same site. */
fun canonicalKey(key: String): String = itemOf(key)?.key ?: key

private fun Rules.liveActivations(now: Long, zone: ZoneId) =
    activations.filter { it.enabled && it.windows(now, zone).isNotEmpty() }.sortedBy { it.createdAt }

/** Every Shield target the person has switched on, oldest first: enabled activations, then Daily Limits. */
fun Rules.requestedShield(now: Long, zone: ZoneId): List<String> =
    (liveActivations(now, zone).flatMap { a -> configs.find { it.id == a.configId }?.let { shieldOf(it)?.keys }.orEmpty() } + activeLimits().map { it.key })
        .map(::canonicalKey).distinct()

fun Rules.requestedIntent(now: Long, zone: ZoneId): List<String> =
    liveActivations(now, zone).flatMap { a -> configs.find { it.id == a.configId }?.let { intentOf(it)?.keys }.orEmpty() }.distinct()

/**
 * The targets [plan] enforces, or null for all of them. On Free: the person's choice first, then the oldest.
 * This is computed, never stored, so a downgrade is one idempotent function of the plan and the saved
 * configuration: it survives process death, deletes nothing, and renewing restores everything at once.
 */
fun Rules.retainedShield(plan: Plan, now: Long, zone: ZoneId): Set<String>? =
    if (limit(plan, Allowance.ProtectedItems) == null) null else keepActive(plan, Allowance.ProtectedItems, requestedShield(now, zone), keep.shield).toSet()

fun Rules.retainedIntent(plan: Plan, now: Long, zone: ZoneId): Set<String>? =
    if (limit(plan, Allowance.IntentApps) == null) null else keepActive(plan, Allowance.IntentApps, requestedIntent(now, zone), keep.intent).toSet()

/**
 * The activations [plan] enforces: enabled ones with something left to do once [retainedShield] and
 * [retainedIntent] are applied. The rest stay saved and paused.
 */
fun Rules.entitledActivations(plan: Plan, now: Long, zone: ZoneId): Set<String> {
    val enabled = activations.filter { it.enabled }
    if (plan == Plan.Plus) return enabled.map { it.id }.toSet()
    val shieldKeep = retainedShield(plan, now, zone).orEmpty()
    val intentKeep = retainedIntent(plan, now, zone).orEmpty()
    return enabled.filter { a ->
        val c = configs.find { it.id == a.configId } ?: return@filter false
        val shield = shieldOf(c)?.keys.orEmpty()
        val intent = intentOf(c)?.keys.orEmpty()
        (shield.isEmpty() && intent.isEmpty()) || shield.any { canonicalKey(it) in shieldKeep } || intent.any { it in intentKeep }
    }.map { it.id }.toSet()
}

/** The person's Goals that count as active under [plan]. On Free: the one they kept, or the oldest. */
fun Rules.activeGoalIds(plan: Plan): Set<String> = keepActive(
    plan, Allowance.ActiveGoals,
    trackGoals.filter { it.status == app.aurora.progress.GoalStatus.Active }.sortedBy { it.createdAt }.map { it.id },
    listOfNotNull(keep.goalId),
).toSet()

/** Records when each Goal stops and starts being measured. Idempotent: called on every sync. */
fun Rules.goalsReconciled(plan: Plan, now: Long): Rules {
    val active = activeGoalIds(plan)
    val next = trackGoals.map { it.withMeasuring(it.id in active, now) }
    return if (next == trackGoals) this else copy(trackGoals = next)
}

fun Rules.shieldOf(c: UsageConfig): ShieldPreset? = c.shield ?: c.shieldId?.let { id -> shields.find { it.id == id } }
fun Rules.intentOf(c: UsageConfig): IntentPreset? = c.intent ?: c.intentId?.let { id -> intentPresets.find { it.id == id } }

/** A run for the occurrence of [a] in [window], from the presets as they are now, over what [plan] retains. */
fun Rules.runFor(a: Activation, window: Pair<Long, Long>, plan: Plan, now: Long, zone: ZoneId): ConfigRun? {
    val c = configs.find { it.id == a.configId } ?: return null
    val shieldKeep = retainedShield(plan, now, zone)
    val intentKeep = retainedIntent(plan, now, zone)
    val shield = shieldOf(c)?.let { s -> if (shieldKeep == null) s else s.copy(keys = s.keys.filter { canonicalKey(it) in shieldKeep }) }?.takeIf { it.keys.isNotEmpty() || shieldOf(c)?.keys.isNullOrEmpty() }
    val intent = intentOf(c)?.let { p -> if (intentKeep == null) p else p.copy(keys = p.keys.filter { it in intentKeep }) }?.takeIf { it.keys.isNotEmpty() || intentOf(c)?.keys.isNullOrEmpty() }
    return ConfigRun(
        occurrenceId(a, window.first), c.id, c.name, a.id, c.custom, shield, intent, c.focus,
        start = window.first, end = window.second.takeIf { it != Long.MAX_VALUE }, note = c.note,
    )
}

/** A ledger entry for committed protection from [start] (at the earliest now) to [end], on the quota's clock and calendar. */
fun Runtime.ledgerEntry(start: Long, end: Long, t: DeviceTime, zone: ZoneId): LedgerEntry {
    val now = quotaNow(ledger, t.wall, t.elapsed, t.boot)
    // The window is in wall-clock time; the quota's clock may disagree with a clock that was moved.
    val shift = now - t.wall
    val from = maxOf(start + shift, now)
    return LedgerEntry(start = from, end = maxOf(from, end + shift), zone = quotaZone(ledger, from, zone).id, elapsed = t.elapsed + (from - now), boot = t.boot)
}

/** Today's committed allowance as shown to the person. */
fun Runtime.quotaToday(plan: Plan, t: DeviceTime, zone: ZoneId): app.aurora.plan.QuotaStatus {
    val now = quotaNow(ledger, t.wall, t.elapsed, t.boot)
    return app.aurora.plan.quotaStatus(plan, ledger, now, quotaZone(ledger, now, zone))
}

/** Occurrences not stored yet, in start order: those that may run, and those the day's allowance refuses. */
private fun occurrences(rules: Rules, runtime: Runtime, plan: Plan, t: DeviceTime, zone: ZoneId): Pair<List<ConfigRun>, List<ConfigRun>> {
    val known = runtime.runs.map { it.id }.toSet() + runtime.closed.keys
    val entitled = rules.entitledActivations(plan, t.wall, zone)
    val due = rules.activations.filter { it.id in entitled }.flatMap { a ->
        a.windows(t.wall, zone).mapNotNull { w ->
            val id = occurrenceId(a, w.first)
            // A Phase 3 schedule migrated with its id: its committed Strict window is already a session.
            val legacy = runtime.sessions.any { it.scheduleId == a.id && !it.time.isOver(t) && it.time.startWall >= w.first }
            if (id in known || legacy) null else rules.runFor(a, w, plan, t.wall, zone)
        }
    }.sortedBy { it.start }
    val allowed = mutableListOf<ConfigRun>()
    val refused = mutableListOf<ConfigRun>()
    var ledger = runtime.ledger
    for (run in due) {
        if (plan == Plan.Plus || run.shield?.mode?.usesQuota != true) {
            allowed += run
            continue
        }
        // Open-ended committed protection can't be reserved, so Free doesn't start it.
        val end = run.end
        if (end == null || run.id in runtime.quotaDenied) {
            if (run.covers(t)) refused += run
            continue
        }
        val entry = Runtime(ledger = ledger).ledgerEntry(run.start, end, t, zone)
        if (!committedFits(plan, ledger, entry.start, entry.end, ZoneId.of(entry.zone))) {
            if (run.covers(t)) refused += run
            continue
        }
        // Due now: only the server's signed go-ahead starts it. Nothing is enforced while that is pending.
        if (run.covers(t)) {
            if (run.id in runtime.authorizations) {
                ledger = ledger + entry
                allowed += run
            }
            continue
        }
        ledger = ledger + entry
        allowed += run
    }
    return allowed to refused
}

/**
 * Every run that applies now or starts next: the stored runs, plus the occurrences of entitled activations
 * not stored yet (computed from the current presets until [materialize] freezes them). [closed] occurrences
 * were turned off or settled and never come back. A committed occurrence that doesn't fit Free's allowance
 * isn't here at all: no Flexible stand-in, and nothing enforced before its time is reserved.
 */
fun liveRuns(rules: Rules, runtime: Runtime, plan: Plan, t: DeviceTime, zone: ZoneId): List<ConfigRun> =
    runtime.runs.filterNot { it.isOver(t) } + occurrences(rules, runtime, plan, t, zone).first

/**
 * One session at a time: true while a timed run (started now, scheduled or with Focus) covers [t], or an older
 * session or Focus still counts down. A run with no end (Always) is standing protection, not a session.
 */
fun sessionRunning(rules: Rules, runtime: Runtime, plan: Plan, t: DeviceTime, zone: ZoneId): Boolean =
    liveRuns(rules, runtime, plan, t, zone).any { it.covers(t) && (it.end != null || it.time != null) } ||
        runtime.sessions.any { !it.time.isOver(t) } || runtime.focus?.isOver(t) == false

/** Committed occurrences due now that the day's allowance refuses. */
fun runsRefusedByQuota(rules: Rules, runtime: Runtime, plan: Plan, t: DeviceTime, zone: ZoneId): List<ConfigRun> =
    occurrences(rules, runtime, plan, t, zone).second

/** The virtual runs that cover [t] now: these are stored, freezing their presets. */
fun runsToMaterialize(rules: Rules, runtime: Runtime, plan: Plan, t: DeviceTime, zone: ZoneId): List<ConfigRun> {
    val stored = runtime.runs.map { it.id }.toSet()
    return liveRuns(rules, runtime, plan, t, zone).filter { it.id !in stored && it.covers(t) }
}

/** A committed occurrence on Free that needs the server's word: now, or soon enough to survive losing the network first. */
data class PendingOccurrence(val run: ConfigRun, val minutes: Int, val mode: Mode)

fun occurrencesToAuthorize(rules: Rules, runtime: Runtime, plan: Plan, t: DeviceTime, zone: ZoneId, lookAheadMs: Long): List<PendingOccurrence> {
    if (plan == Plan.Plus) return emptyList()
    val known = runtime.runs.map { it.id }.toSet() + runtime.closed.keys + runtime.quotaDenied.keys + runtime.authorizations.keys
    val entitled = rules.entitledActivations(plan, t.wall, zone)
    return rules.activations.filter { it.id in entitled }.flatMap { a ->
        a.windows(t.wall, zone).filter { it.first <= t.wall + lookAheadMs && it.second != Long.MAX_VALUE }.mapNotNull { w ->
            val run = rules.runFor(a, w, plan, t.wall, zone) ?: return@mapNotNull null
            val mode = run.shield?.mode?.takeIf { it.usesQuota } ?: return@mapNotNull null
            if (run.id in known || runtime.sessions.any { it.scheduleId == a.id && !it.time.isOver(t) && it.time.startWall >= w.first }) return@mapNotNull null
            PendingOccurrence(run, ((w.second - w.first) / 60_000).toInt(), mode)
        }
    }.sortedBy { it.run.start }
}

/** Occurrences reserved ahead whose schedule is gone or paused, before they began: their reservation can go back. */
fun authorizationsToRelease(rules: Rules, runtime: Runtime, now: Long): Map<String, CommitmentAuthorization> =
    runtime.authorizations.filter { (id, auth) ->
        auth.start > now && id !in runtime.runs.map { it.id } && rules.activations.none { it.id == auth.activationId && it.enabled }
    }

/** The note of the first run covering [t] that restricts [key] (Shield or Intent), or null. */
fun sessionNote(runs: List<ConfigRun>, key: String, t: DeviceTime): String? {
    val k = canonicalKey(key)
    return runs.firstOrNull { r ->
        r.note.isNotBlank() && r.covers(t) && (r.shield?.keys.orEmpty() + r.intent?.keys.orEmpty()).any { canonicalKey(it) == k }
    }?.note
}

/** For two active Intent presets on one app, the stricter decides: a cooldown, then the longer pause. */
fun stricter(a: IntentConfig, b: IntentConfig): IntentConfig = listOf(a, b).maxWith(
    compareBy<IntentConfig> { it.expiry == IntentExpiry.Cooldown }.thenBy { it.reflectionSeconds }.thenBy { it.cooldownMinutes },
)

/** The Intent settings that apply to each app now, from every run covering now. */
fun intentsNow(runs: List<ConfigRun>, t: DeviceTime): Map<String, IntentConfig> {
    val out = mutableMapOf<String, IntentConfig>()
    for (r in runs) {
        if (!r.covers(t)) continue
        val p = r.intent ?: continue
        for (k in p.keys) out[k] = out[k]?.let { stricter(it, p.config) } ?: p.config
    }
    return out
}

/** Why a configuration can't be activated as asked. OverItems, OverIntentApps and OverCommittedAllowance are plan limits, explained by a [LimitDecision]. */
enum class ActivationProblem {
    Empty, AlwaysWithAlwaysBlock, InThePast, NoDays, TooShort, OverItems, OverIntentApps, OverCommittedAllowance,
    /** Free's allowance is the server's to give, and it can't be reached. Nothing started; existing commitments are untouched. */
    NeedsConnection,

    /** One session at a time: another is running now. Nothing started; the running one is untouched. */
    SessionRunning;

    val overLimit: Boolean get() = this == OverItems || this == OverIntentApps || this == OverCommittedAllowance
}

/** A refused operation: nothing changed. [decision] says why, for plan limits. */
data class Refusal(val problem: ActivationProblem, val decision: LimitDecision? = null)

/** The targets holding a slot: what is running, and what [plan] retains of what is switched on. [focusKeys] are a running Focus's. */
data class ActiveTargets(val shield: Set<String>, val intent: Set<String>)

fun activeTargets(rules: Rules, runtime: Runtime, t: DeviceTime, zone: ZoneId, plan: Plan, focusKeys: Set<String> = emptySet()): ActiveTargets {
    val runs = runtime.runs.filterNot { it.isOver(t) }
    return ActiveTargets(
        ((rules.retainedShield(plan, t.wall, zone) ?: rules.requestedShield(t.wall, zone)) + runs.flatMap { it.shield?.keys.orEmpty() } + runtime.sessions.filterNot { it.time.isOver(t) }.flatMap { it.keys } + focusKeys).map(::canonicalKey).toSet(),
        ((rules.retainedIntent(plan, t.wall, zone) ?: rules.requestedIntent(t.wall, zone)) + runs.flatMap { it.intent?.keys.orEmpty() }).toSet(),
    )
}

/** Checks whether more targets fit, given [rules] as they will be once the change is stored. */
fun targetsRefusal(plan: Plan, active: ActiveTargets, shield: Collection<String>, intent: Collection<String>): Refusal? {
    val s = EntitlementPolicy.canActivateShieldTarget(plan, active.shield, shield.map(::canonicalKey).toSet())
    if (!s.allowed) return Refusal(ActivationProblem.OverItems, s)
    val i = EntitlementPolicy.canActivateIntentApp(plan, active.intent, intent.toSet())
    if (!i.allowed) return Refusal(ActivationProblem.OverIntentApps, i)
    return null
}

/**
 * Checks [timing] for configuration [c]; [this] already holds [c]. Always scheduling isn't offered for a
 * strict Shield (Strict, Cooldown, Friction): a permanent commitment must never be created by accident.
 *
 * Starting now must fit today's committed allowance. A schedule's executions are checked when each one
 * runs; here it is refused only when one window alone could never fit.
 */
fun Rules.problem(c: UsageConfig, timing: Timing, plan: Plan, runtime: Runtime, t: DeviceTime, zone: ZoneId, focusKeys: Set<String> = emptySet()): Refusal? {
    val shield = shieldOf(c)
    val intent = intentOf(c)
    if ((shield == null || shield.keys.isEmpty()) && (intent == null || intent.keys.isEmpty()) && c.focus == null) return Refusal(ActivationProblem.Empty)
    if (timing is Timing.Always && shield?.mode?.strict == true) return Refusal(ActivationProblem.AlwaysWithAlwaysBlock)
    when (timing) {
        is Timing.Now -> {
            if (timing.minutes <= 0) return Refusal(ActivationProblem.TooShort)
            if (sessionRunning(this, runtime, plan, t, zone)) return Refusal(ActivationProblem.SessionRunning)
        }
        is Timing.Later -> {
            if (timing.minutes <= 0) return Refusal(ActivationProblem.TooShort)
            if (timing.start <= t.wall) return Refusal(ActivationProblem.InThePast)
        }
        is Timing.Recurring -> {
            if (timing.days.isEmpty()) return Refusal(ActivationProblem.NoDays)
            if (timing.minutes <= 0) return Refusal(ActivationProblem.TooShort)
        }
        Timing.Always -> Unit
    }
    targetsRefusal(plan, activeTargets(this, runtime, t, zone, plan, focusKeys), shield?.keys.orEmpty(), intent?.keys.orEmpty())?.let { return it }
    if (shield?.mode?.usesQuota != true) return null
    val minutes = when (timing) {
        is Timing.Now -> timing.minutes
        is Timing.Later -> timing.minutes
        is Timing.Recurring -> timing.minutes
        Timing.Always -> return null
    }
    val decision = if (timing is Timing.Now) {
        val entry = runtime.ledgerEntry(t.wall, t.wall + minutes * 60_000L, t, zone)
        EntitlementPolicy.canStartCommittedProtection(plan, runtime.ledger, entry.start, entry.end, ZoneId.of(entry.zone))
    } else {
        val start = (timing as? Timing.Later)?.start ?: t.wall
        EntitlementPolicy.canStartCommittedProtection(plan, emptyList(), start, start + minutes * 60_000L, zone)
    }
    return if (decision.allowed) null else Refusal(ActivationProblem.OverCommittedAllowance, decision)
}

/**
 * Apps and sites that two parts of a configuration, or this configuration and one already running,
 * both cover. Both apply; the stronger one decides (Policy.kt), so this is shown, not refused.
 */
fun Rules.overlaps(c: UsageConfig, running: List<ConfigRun>): Set<String> {
    val mine = shieldOf(c)?.keys.orEmpty().toSet() + intentOf(c)?.keys.orEmpty()
    val inside = shieldOf(c)?.keys.orEmpty().toSet() intersect intentOf(c)?.keys.orEmpty().toSet()
    val others = running.flatMap { it.shield?.keys.orEmpty() + it.intent?.keys.orEmpty() }.toSet()
    return inside + (mine intersect others)
}
