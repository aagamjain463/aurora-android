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
data class FocusPlan(val task: String, val description: String = "", val goal: String = "", val minutes: Int? = null, val goalId: String? = null)

/**
 * A complete setup made on Home: a session. A saved one ([custom]) references saved presets by id, as many Shields
 * ([shieldIds]) and Intents ([intentIds]) as the person wants, no app in two of them ([presetOverlap]), and can be linked
 * to a habit. A Normal Session carries its own copies ([shield], [intent], with [intentConfigs] when several Intents are
 * merged), set for that use without changing the presets. [unlocks] are the breaks it allows while it runs.
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
    /** How strict this session is. Null: older data, where the Shield preset said it ([Rules.modeOf]). */
    val mode: Mode? = null,
    /** The Friction challenge, for a Friction session. Null: the Shield preset's (older data). */
    val friction: FrictionSettings? = null,
    /** The Cooldown wait, for a Cooldown session. Null: the Shield preset's (older data). */
    val cooldownWaitMinutes: Int? = null,
    val shieldIds: List<String> = emptyList(),
    val intentIds: List<String> = emptyList(),
    val unlocks: List<SessionUnlock> = emptyList(),
    /** With a carried [intent] merged from several: each app's own Intent settings. */
    val intentConfigs: Map<String, IntentConfig> = emptyMap(),
) {
    /** Its Shields by id: the list, or (older data) the one. */
    val allShieldIds: List<String> get() = shieldIds.ifEmpty { listOfNotNull(shieldId) }
    val allIntentIds: List<String> get() = intentIds.ifEmpty { listOfNotNull(intentId) }
}

/** How strict [c] is: its own mode, else (older data) its Shield preset's, else Flexible. */
fun Rules.modeOf(c: UsageConfig): Mode = c.mode ?: shieldOf(c)?.mode ?: Mode.Flexible
fun Rules.frictionOf(c: UsageConfig): FrictionSettings = c.friction ?: shieldOf(c)?.friction ?: FrictionSettings()
fun Rules.cooldownWaitOf(c: UsageConfig): Int = c.cooldownWaitMinutes ?: shieldOf(c)?.cooldownWaitMinutes ?: 1

/** [c]'s Shield as it runs: the preset's apps and sites, with the session's strictness. */
fun Rules.runningShield(c: UsageConfig): ShieldPreset? =
    shieldOf(c)?.copy(mode = modeOf(c), friction = frictionOf(c), cooldownWaitMinutes = cooldownWaitOf(c))

/** The mode to show for [this]: a run with a Shield, or a strict one with only an Intent. A relaxed Intent alone has none. */
val ConfigRun.shownMode: Mode? get() = sessionMode.takeIf { shield != null || it.strict }

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

/**
 * A break a session allows, set up beforehand: [partIds] (its Shields and Intents by id; empty: all of it) stop for
 * [minutes], at most [times] per run. [focusRuns]: the Focus timer keeps running through it, and the time counts.
 * [on]: checked in the session's list. Only checked unlocks can be used: a session keeps every break it could need, and
 * each start picks the ones for this time.
 */
@Serializable
data class SessionUnlock(
    val id: String,
    val name: String,
    val partIds: List<String> = emptyList(),
    val minutes: Int = 10,
    val times: Int = 1,
    val focusRuns: Boolean = false,
    val on: Boolean = true,
    /** Apps and sites it opens, picked one by one. Empty: [partIds] decide; both empty: everything. */
    val keys: List<String> = emptyList(),
)

/**
 * What a pass is for, read from what it does: a Break opens everything and pauses the timer; Work keeps the timer
 * going; Other opens only the apps chosen and pauses it. Every pass is exactly one of these.
 */
enum class PassKind { Break, Work, Other }

val SessionUnlock.opensAll: Boolean get() = partIds.isEmpty() && keys.isEmpty()
val SessionUnlock.kind: PassKind get() = when {
    focusRuns -> PassKind.Work
    opensAll -> PassKind.Break
    else -> PassKind.Other
}

/** An unlock in use: [keys] (empty: everything) stop from [start] to [end]. [focusRuns] as in [SessionUnlock]. */
@Serializable
data class UnlockUse(val unlockId: String, val start: Long, val end: Long, val keys: Set<String> = emptySet(), val focusRuns: Boolean = false) {
    fun lifts(key: String): Boolean = keys.isEmpty() || canonicalKey(key) in keys
}

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
    /** Several Intents merged into [intent]: each app's own settings. */
    val intentConfigs: Map<String, IntentConfig> = emptyMap(),
    /** Frozen copy of [UsageConfig.unlocks]. */
    val unlocks: List<SessionUnlock> = emptyList(),
    /** The session's strictness, frozen when the run began. Null: older runs, where the Shield said it. */
    val mode: Mode? = null,
) {
    /** How strict this run is, whether or not it has a Shield. */
    val sessionMode: Mode get() = mode ?: shield?.mode ?: Mode.Flexible

    fun startAt(t: DeviceTime): Long = time?.startedWall(t) ?: start
    fun endAt(t: DeviceTime): Long? = time?.endWall(t) ?: end
    fun isOver(t: DeviceTime): Boolean = time?.isOver(t) ?: (end != null && t.wall >= end)
    fun covers(t: DeviceTime): Boolean = t.wall >= startAt(t) && !isOver(t)

    /**
     * A strict run (Strict, Cooldown, Friction) can't be turned off at once until it ends, or for
     * Strict on Free, until its committed part is over. An Always run (no end) made before strict
     * Cooldown and Friction stays cancellable: a permanent commitment is never made by accident.
     */
    fun cancellable(t: DeviceTime): Boolean = when {
        !sessionMode.strict || end == null -> true
        sessionMode == Mode.AlwaysBlock -> committedUntil?.let { t.wall >= it } == true
        else -> false
    }

    /**
     * Friction and Cooldown end early only in Aurora, for the whole session: after the challenge, or after
     * the wait sat out on that screen. Never from the Shield. Strict has no early end.
     */
    val endsAfterTask: Boolean get() = sessionMode == Mode.Friction || sessionMode == Mode.Cooldown

    /** The unlock on at [now] out of [used] (this run's), if any. */
    fun unlockedAt(used: List<UnlockUse>, now: Long): UnlockUse? = used.lastOrNull { now >= it.start && now < it.end }
    /** How many more times [u] may be used. */
    fun left(u: SessionUnlock, used: List<UnlockUse>): Int = (u.times - used.count { it.unlockId == u.id }).coerceAtLeast(0)
    /** Time a Focus timer stood still (unlocks that stop it) up to [now]. */
    fun stoppedMs(used: List<UnlockUse>, now: Long): Long = used.filterNot { it.focusRuns }.sumOf { (minOf(it.end, now) - it.start).coerceAtLeast(0) }
    /**
     * Time reclaimed (focused) so far: counted forward from 0 since the session began, standing still through a pass
     * that pauses it (Break, Other), never through one that doesn't (Work). The session's own clock never stops.
     */
    fun focusedMs(t: DeviceTime, used: List<UnlockUse>): Long {
        val now = minOf(t.wall, endAt(t) ?: t.wall)
        return ((now - startAt(t)) - stoppedMs(used, now)).coerceAtLeast(0)
    }

    fun restrictions(t: DeviceTime): List<Restriction> {
        val from = startAt(t)
        val to = endAt(t) ?: Long.MAX_VALUE
        // A strict session with only an Intent blocks nothing itself, yet locks like any other strict run.
        val s = shield ?: return if (cancellable(t)) emptyList() else listOf(Restriction(sessionMode.reason, emptySet(), from, to, id, sessionId = id, label = name, locks = true))
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

/** Enabled activations with a window covering [at], oldest first. */
private fun Rules.liveActivations(at: Long, zone: ZoneId) =
    activations.filter { it.enabled && it.windowsIn(at, at + 1, zone).isNotEmpty() }.sortedBy { it.createdAt }

/** Every Shield target switched on at [now] (any time: what is on then), oldest first: activations covering it, then Daily Limits. */
fun Rules.requestedShield(now: Long, zone: ZoneId): List<String> =
    (liveActivations(now, zone).flatMap { a -> configsOf(a).flatMap { shieldOf(it)?.keys.orEmpty() } } + activeLimits().map { it.key })
        .map(::canonicalKey).distinct()

fun Rules.requestedIntent(now: Long, zone: ZoneId): List<String> =
    liveActivations(now, zone).flatMap { a -> configsOf(a).flatMap { intentOf(it)?.keys.orEmpty() } }.distinct()

/** The session an activation runs. */
fun Rules.configsOf(a: Activation): List<UsageConfig> = listOfNotNull(configs.find { it.id == a.configId })

/** Apps and sites in more than one of these Shields and Intents: a session may hold each in one of them only. */
fun Rules.presetOverlap(shieldIds: List<String>, intentIds: List<String>): Set<String> {
    val parts = shieldIds.mapNotNull { id -> shields.find { it.id == id }?.keys } + intentIds.mapNotNull { id -> intentPresets.find { it.id == id }?.keys }
    return parts.flatMap { it.map(::canonicalKey).toSet() }.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
}

/** The names of [c]'s Shields and Intents by id. */
fun Rules.partNames(c: UsageConfig): Map<String, String> =
    c.allShieldIds.associateWith { id -> shields.find { it.id == id }?.name.orEmpty() } + c.allIntentIds.associateWith { id -> intentPresets.find { it.id == id }?.name.orEmpty() }

/** The apps and sites of the Shields and Intents [ids] (of [c]), as an unlock lifts them. Empty [ids]: everything. */
/** What [u] opens when used: its own apps, else its Shields and Intents' apps; empty is everything. */
fun Rules.unlockKeys(c: UsageConfig?, u: SessionUnlock): Set<String> =
    if (u.keys.isNotEmpty()) u.keys.map(::canonicalKey).toSet() else unlockKeys(c, u.partIds)

fun Rules.unlockKeys(c: UsageConfig?, ids: List<String>): Set<String> =
    if (ids.isEmpty()) emptySet()
    else (ids.mapNotNull { id -> shields.find { it.id == id }?.keys ?: intentPresets.find { it.id == id }?.keys }.flatten()).map(::canonicalKey).toSet()

/**
 * The targets [plan] enforces, or null for all of them. On Free: the person's choice first, then the oldest.
 * This is computed, never stored, so a downgrade is one idempotent function of the plan and the saved
 * configuration: it survives process death, deletes nothing, and renewing restores everything at once.
 */
fun Rules.retainedShield(plan: Plan, now: Long, zone: ZoneId, reserved: Set<String> = emptySet()): Set<String>? =
    if (limit(plan, Allowance.ProtectedItems) == null) null
    else keepActive(plan, Allowance.ProtectedItems, reserved.toList() + requestedShield(now, zone), reserved + keep.shield).toSet()

/**
 * The targets of commitments another account on this phone left running ([app.aurora.account.mergeCarried]). They
 * hold Free's slots first, so switching accounts can't put more than Free's targets under protection at once.
 */
fun Runtime.carriedShield(t: DeviceTime): Set<String> =
    runs.filter { it.carried && !it.isOver(t) }.flatMap { it.shield?.keys.orEmpty() }.map(::canonicalKey).toSet()

fun Rules.retainedIntent(plan: Plan, now: Long, zone: ZoneId): Set<String>? =
    if (limit(plan, Allowance.IntentApps) == null) null else keepActive(plan, Allowance.IntentApps, requestedIntent(now, zone), keep.intent).toSet()

/**
 * The activations [plan] enforces: enabled ones with something left to do once [retainedShield] and
 * [retainedIntent] are applied. The rest stay saved and paused.
 */
fun Rules.entitledActivations(plan: Plan, now: Long, zone: ZoneId, reserved: Set<String> = emptySet()): Set<String> {
    val enabled = activations.filter { it.enabled }
    if (plan == Plan.Plus) return enabled.map { it.id }.toSet()
    return enabled.filter { a ->
        val cs = configsOf(a).ifEmpty { return@filter false }
        // Each activation is judged at its own time: the window on now, or the next one to start.
        val at = maxOf(now, a.windows(now, zone).firstOrNull()?.first ?: now)
        val shieldKeep = retainedShield(plan, at, zone, reserved).orEmpty()
        val intentKeep = retainedIntent(plan, at, zone).orEmpty()
        val shield = cs.flatMap { shieldOf(it)?.keys.orEmpty() }
        val intent = cs.flatMap { intentOf(it)?.keys.orEmpty() }
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

/** [c]'s Shields as one: every app and site of them all, under their names. */
fun Rules.shieldOf(c: UsageConfig): ShieldPreset? = c.shield ?: c.allShieldIds.mapNotNull { id -> shields.find { it.id == id } }.let { ps ->
    when (ps.size) {
        0 -> null
        1 -> ps.single()
        else -> ps.first().copy(name = ps.joinToString(" + ") { it.name }, keys = ps.flatMap { it.keys }.distinct())
    }
}

/** [c]'s Intents as one: every app of them all ([intentConfigsOf] keeps each one's settings). */
fun Rules.intentOf(c: UsageConfig): IntentPreset? = c.intent ?: c.allIntentIds.mapNotNull { id -> intentPresets.find { it.id == id } }.let { ps ->
    when (ps.size) {
        0 -> null
        1 -> ps.single()
        else -> ps.first().copy(name = ps.joinToString(" + ") { it.name }, keys = ps.flatMap { it.keys }.distinct())
    }
}

/** Each app's own Intent settings when [c] has several Intents; empty with one. */
fun Rules.intentConfigsOf(c: UsageConfig): Map<String, IntentConfig> = c.intentConfigs.ifEmpty {
    val ps = c.allIntentIds.mapNotNull { id -> intentPresets.find { it.id == id } }
    if (ps.size < 2) emptyMap() else ps.flatMap { p -> p.keys.map { it to p.config } }.toMap()
}

/** A run for the occurrence of [a] in [window], from the presets as they are now, over what [plan] retains. */
fun Rules.runFor(a: Activation, window: Pair<Long, Long>, plan: Plan, now: Long, zone: ZoneId, reserved: Set<String> = emptySet()): ConfigRun? {
    val c = configs.find { it.id == a.configId } ?: return null
    return retainedRun(c, occurrenceId(a, window.first), a.id, window.first, window.second.takeIf { it != Long.MAX_VALUE }, plan, now, zone, reserved).copy(focus = c.focus)
}

/** A run of [c] from [start] to [end], frozen from the presets as they are now, over what [plan] retains. */
private fun Rules.retainedRun(c: UsageConfig, id: String, activationId: String, start: Long, end: Long?, plan: Plan, now: Long, zone: ZoneId, reserved: Set<String>): ConfigRun {
    // What Free keeps is judged at the time the run is on, not at the time it is computed.
    val at = maxOf(start, now)
    val shieldKeep = retainedShield(plan, at, zone, reserved)
    val intentKeep = retainedIntent(plan, at, zone)
    val shield = runningShield(c)?.let { s -> if (shieldKeep == null) s else s.copy(keys = s.keys.filter { canonicalKey(it) in shieldKeep }) }?.takeIf { it.keys.isNotEmpty() || shieldOf(c)?.keys.isNullOrEmpty() }
    val intent = intentOf(c)?.let { p -> if (intentKeep == null) p else p.copy(keys = p.keys.filter { it in intentKeep }) }?.takeIf { it.keys.isNotEmpty() || intentOf(c)?.keys.isNullOrEmpty() }
    return ConfigRun(
        id, c.id, c.name, activationId, c.custom, shield, intent, null, start = start, end = end, note = c.note, mode = modeOf(c),
        intentConfigs = intentConfigsOf(c), unlocks = c.unlocks.filter { it.on },
    )
}

/** The runs of the occurrence of [a] in [window]: its session's, if that session is still saved. */
fun Rules.runsFor(a: Activation, window: Pair<Long, Long>, plan: Plan, now: Long, zone: ZoneId, reserved: Set<String> = emptySet()): List<ConfigRun> =
    listOfNotNull(runFor(a, window, plan, now, zone, reserved))

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
    val reserved = runtime.carriedShield(t)
    val entitled = rules.entitledActivations(plan, t.wall, zone, reserved)
    val due = rules.activations.filter { it.id in entitled }.flatMap { a ->
        a.windows(t.wall, zone).mapNotNull { w ->
            val id = occurrenceId(a, w.first)
            // A Phase 3 schedule migrated with its id: its committed Strict window is already a session.
            val legacy = runtime.sessions.any { it.scheduleId == a.id && !it.time.isOver(t) && it.time.startWall >= w.first }
            if (id in known || legacy) null else rules.runsFor(a, w, plan, t.wall, zone, reserved)
        }.flatten()
    }.filter { it.id !in known && (it.end == null || it.end > t.wall) }.sortedBy { it.start }
    val allowed = mutableListOf<ConfigRun>()
    val refused = mutableListOf<ConfigRun>()
    var ledger = runtime.ledger
    for (run in due) {
        if (plan == Plan.Plus || !run.sessionMode.usesQuota) {
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
    val reserved = runtime.carriedShield(t)
    val entitled = rules.entitledActivations(plan, t.wall, zone, reserved)
    return rules.activations.filter { it.id in entitled }.flatMap { a ->
        a.windows(t.wall, zone).filter { it.second != Long.MAX_VALUE && occurrenceId(a, it.first) !in known }.flatMap { w ->
            rules.runsFor(a, w, plan, t.wall, zone, reserved).mapNotNull { run ->
                val mode = run.sessionMode.takeIf { it.usesQuota } ?: return@mapNotNull null
                val end = run.end ?: return@mapNotNull null
                if (run.start > t.wall + lookAheadMs || end <= t.wall) return@mapNotNull null
                if (run.id in known || runtime.sessions.any { it.scheduleId == a.id && !it.time.isOver(t) && it.time.startWall >= w.first }) return@mapNotNull null
                PendingOccurrence(run, ((end - run.start) / 60_000).toInt(), mode)
            }
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
        for (k in p.keys) (r.intentConfigs[k] ?: p.config).let { cfg -> out[k] = out[k]?.let { stricter(it, cfg) } ?: cfg }
    }
    return out
}

/** Why a configuration can't be activated as asked. OverItems, OverIntentApps and OverCommittedAllowance are plan limits, explained by a [LimitDecision]. */
enum class ActivationProblem {
    Empty, AlwaysWithAlwaysBlock, InThePast, NoDays, TooShort, OverItems, OverIntentApps, OverCommittedAllowance,
    /** Free's allowance is the server's to give, and it can't be reached. Nothing started; existing commitments are untouched. */
    NeedsConnection,

    /** One session at a time: another is running now. Only legacy Focus still answers this. */
    SessionRunning,

    /** The same app, a task, or other sessions are already on at that time ([Refusal.conflict] says which). */
    Conflict;

    val overLimit: Boolean get() = this == OverItems || this == OverIntentApps || this == OverCommittedAllowance
}

/** A refused operation: nothing changed. [decision] says why, for plan limits. */
data class Refusal(val problem: ActivationProblem, val decision: LimitDecision? = null, val conflict: Conflict? = null)

/** The targets holding a slot: what is running, and what [plan] retains of what is switched on. [focusKeys] are a running Focus's. */
data class ActiveTargets(val shield: Set<String>, val intent: Set<String>)

fun activeTargets(rules: Rules, runtime: Runtime, t: DeviceTime, zone: ZoneId, plan: Plan, focusKeys: Set<String> = emptySet()): ActiveTargets {
    val runs = runtime.runs.filterNot { it.isOver(t) }
    return ActiveTargets(
        ((rules.retainedShield(plan, t.wall, zone, runtime.carriedShield(t)) ?: rules.requestedShield(t.wall, zone)) + runs.flatMap { it.shield?.keys.orEmpty() } + runtime.sessions.filterNot { it.time.isOver(t) }.flatMap { it.keys } + focusKeys).map(::canonicalKey).toSet(),
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
    if (timing is Timing.Always && modeOf(c).strict) return Refusal(ActivationProblem.AlwaysWithAlwaysBlock)
    when (timing) {
        is Timing.Now -> {
            if (timing.minutes <= 0) return Refusal(ActivationProblem.TooShort)
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
    sessionLimitRefusal(c, timing, plan, runtime, t, zone, focusKeys = focusKeys)?.let { return it }
    if (!modeOf(c).usesQuota) return null
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

// ---- Unlocks ----------------------------------------------------------------------------------------

/** [r] without the spans in [lifted]: the pieces before, between and after them, each keeping its id. */
fun Restriction.without(lifted: List<Pair<Long, Long>>): List<Restriction> =
    app.aurora.progress.gaps(lifted, start, end).map { (s, e) -> copy(start = s, end = e) }

/**
 * [r] (one of [run]'s) with [used] unlocks lifted: each app and site loses the spans of the unlocks that open it, and keeps
 * the rest, so it is blocked again the moment an unlock ends.
 */
fun Restriction.lifted(used: List<UnlockUse>): List<Restriction> {
    if (used.isEmpty()) return listOf(this)
    // A strict Intent-only lock has no keys: it lifts with an unlock of everything.
    if (keys.isEmpty()) return without(used.filter { it.keys.isEmpty() }.map { it.start to it.end })
    return keys.groupBy { k -> used.filter { it.lifts(k) }.map { it.start to it.end } }.flatMap { (spans, ks) ->
        copy(keys = ks.toSet()).without(spans)
    }
}

// ---- Conflicts ---------------------------------------------------------------------------------------
// ---- Conflicts ---------------------------------------------------------------------------------------

/**
 * Why a session can't be saved or run as asked. [Inside]: an app in one of its Shields and one of its Intents. [Plan]: an app
 * in two of its Shields or Intents. [Sessions]: another session at that time (one runs at a time). [keys] are the apps.
 */
enum class ConflictKind { Inside, Plan, Sessions, Apps }

data class Conflict(val kind: ConflictKind, val keys: Set<String> = emptySet(), val name: String = "", val start: Long = 0, val end: Long = 0)

/** How far ahead schedules are compared: two weeks covers every weekly pattern. */
private const val ConflictHorizonMs = 15 * 86_400_000L

private fun overlap(a: Pair<Long, Long>, b: Pair<Long, Long>) = a.first < b.second && b.first < a.second

/** Every window of [this] that is not over by [from] and starts before [to]. */
fun Activation.windowsIn(from: Long, to: Long, zone: ZoneId): List<Pair<Long, Long>> = when (val t = timing) {
    is Timing.Now -> listOf(from to from + t.minutes * 60_000L)
    is Timing.Later -> listOf(t.start to t.start + t.minutes * 60_000L)
    is Timing.Always -> listOf((if (createdAt > 0) createdAt else from) to Long.MAX_VALUE)
    is Timing.Recurring -> {
        val last = t.until?.let { LocalDate.parse(it) }
        t.asSchedule(id).windowsBetween(from, to, zone).filter { (start, _) ->
            start >= createdAt && (last == null || !Instant.ofEpochMilli(start).atZone(zone).toLocalDate().isAfter(last))
        }
    }
}.filter { it.second > from && it.first < to }

private enum class Occupies { Session }

/** Something on, or to come: its name, what it covers (Shield and Intent apart), and when. */
private data class Occupant(
    val holds: Occupies, val name: String, val shield: Set<String>, val windows: List<Pair<Long, Long>>, val always: Boolean = false,
    val intent: Set<String> = emptySet(), val carried: Boolean = false,
) {
    val keys: Set<String> get() = shield + intent
}

private fun Rules.shieldKeysOf(c: UsageConfig): Set<String> = shieldOf(c)?.keys.orEmpty().map(::canonicalKey).toSet()
private fun Rules.intentKeysOf(c: UsageConfig): Set<String> = intentOf(c)?.keys.orEmpty().map(::canonicalKey).toSet()
private fun Rules.keysOf(c: UsageConfig): Set<String> = shieldKeysOf(c) + intentKeysOf(c)
private fun ConfigRun.shieldKeys() = shield?.keys.orEmpty().map(::canonicalKey).toSet()
private fun ConfigRun.intentKeys() = intent?.keys.orEmpty().map(::canonicalKey).toSet()

/**
 * Everything that will be on from now on: running sessions, and every enabled session and task with its windows.
 * Commitments carried from another account are included, marked [Occupant.carried]: they hold slots, not apps.
 */
private fun Rules.held(runtime: Runtime, t: DeviceTime, zone: ZoneId, excluding: String?, plan: Plan = Plan.Plus): List<Occupant> {
    val to = t.wall + ConflictHorizonMs
    val live = runtime.runs.filter { !it.isOver(t) }
    fun window(r: ConfigRun) = listOf(r.startAt(t) to (r.endAt(t) ?: Long.MAX_VALUE))
    val running = live.map { r ->
        Occupant(Occupies.Session, r.name, r.shieldKeys(), window(r), r.endAt(t) == null, r.intentKeys(), r.carried)
    }
    val stored = runtime.runs.map { it.id }.toSet()
    val planned = activations.filter { it.enabled && it.id != excluding }.flatMap { a ->
        // A turned-off or already running occurrence is counted once, from its run (or not at all).
        val windows = a.windowsIn(t.wall, to, zone).filter { occurrenceId(a, it.first).let { id -> id !in runtime.closed && id !in stored } }
        if (windows.isEmpty()) return@flatMap emptyList()
        configs.find { it.id == a.configId }?.let { listOf(Occupant(Occupies.Session, it.name, shieldKeysOf(it), windows, a.timing is Timing.Always, intentKeysOf(it))) }.orEmpty()
    }
    // On Free, what a schedule would run is only what Free keeps at its time: a target paused by the plan isn't on.
    val kept = if (plan == Plan.Plus) planned else planned.map { o ->
        val at = maxOf(t.wall, o.windows.firstOrNull()?.first ?: t.wall)
        val shieldKeep = retainedShield(plan, at, zone, runtime.carriedShield(t))
        val intentKeep = retainedIntent(plan, at, zone)
        o.copy(shield = shieldKeep?.let { k -> o.shield.filter { it in k }.toSet() } ?: o.shield, intent = intentKeep?.let { k -> o.intent.filter { it in k }.toSet() } ?: o.intent)
    }
    return running + kept
}

/**
 * Free's app limits, counted over what is on at the same time: for each window of each candidate part, the Shield (and
 * Intent) targets of everything overlapping it, plus the part's own. Over the limit: the plan's refusal, as before.
 * [parts] are the candidate's pieces (one for a session, one per block for a task): Shield keys, Intent keys, windows.
 */
private fun Rules.limitRefusal(plan: Plan, parts: List<Occupant>, runtime: Runtime, t: DeviceTime, zone: ZoneId, excluding: String?, focusKeys: Set<String> = emptySet()): Refusal? {
    if (plan == Plan.Plus) return null
    val others = held(runtime, t, zone, excluding, plan)
    for (part in parts) for (w in part.windows) {
        val with = others.filter { o -> o.windows.any { overlap(it, w) } } + parts.filter { p -> p !== part && p.windows.any { overlap(it, w) } }
        val nowKeys = if (w.first <= t.wall && t.wall < w.second) focusKeys.map(::canonicalKey) else emptyList()
        val s = EntitlementPolicy.canActivateShieldTarget(plan, with.flatMap { it.shield }.toSet() + nowKeys, part.shield)
        if (!s.allowed) return Refusal(ActivationProblem.OverItems, s)
        val i = EntitlementPolicy.canActivateIntentApp(plan, with.flatMap { it.intent }.toSet(), part.intent)
        if (!i.allowed) return Refusal(ActivationProblem.OverIntentApps, i)
    }
    return null
}

/** Free's app limits for session [c] at [timing], beside what is on then. */
fun Rules.sessionLimitRefusal(c: UsageConfig, timing: Timing, plan: Plan, runtime: Runtime, t: DeviceTime, zone: ZoneId, excluding: String? = null, focusKeys: Set<String> = emptySet()): Refusal? {
    val windows = Activation("candidate", c.id, timing, createdAt = t.wall).windowsIn(t.wall, t.wall + ConflictHorizonMs, zone)
    return limitRefusal(plan, listOf(Occupant(Occupies.Session, c.name, shieldKeysOf(c), windows, intent = intentKeysOf(c))), runtime, t, zone, excluding, focusKeys)
}

/**
 * Why session [c] can't be saved or run at [timing], or null. Checked when the person confirms: an app in two of its
 * Shields and Intents, or another session at that time: one session runs at a time. [excluding] is the activation this
 * one replaces.
 */
fun Rules.sessionConflict(c: UsageConfig, timing: Timing, runtime: Runtime, t: DeviceTime, zone: ZoneId, excluding: String? = null): Conflict? {
    sessionClash(c)?.let { return it }
    val mine = Activation("candidate", c.id, timing, createdAt = t.wall).windowsIn(t.wall, t.wall + ConflictHorizonMs, zone)
    for (h in held(runtime, t, zone, excluding)) {
        if (h.carried) continue
        val w = h.windows.firstOrNull { o -> mine.any { overlap(it, o) } } ?: continue
        return Conflict(ConflictKind.Sessions, name = h.name, start = w.first, end = w.second)
    }
    return null
}

/** An app in two of [c]'s Shields and Intents, or null. A session holds each app in one of them only. */
fun Rules.sessionClash(c: UsageConfig): Conflict? {
    val inside = shieldOf(c)?.keys.orEmpty().map(::canonicalKey).toSet() intersect intentOf(c)?.keys.orEmpty().map(::canonicalKey).toSet()
    if (inside.isNotEmpty()) return Conflict(ConflictKind.Inside, inside, c.name)
    return presetOverlap(c.allShieldIds, c.allIntentIds).takeIf { it.isNotEmpty() }?.let { Conflict(ConflictKind.Plan, it, c.name) }
}
