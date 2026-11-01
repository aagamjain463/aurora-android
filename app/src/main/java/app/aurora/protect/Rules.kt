@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package app.aurora.protect

import app.aurora.plan.Allowance
import app.aurora.plan.LedgerEntry
import app.aurora.plan.Plan
import app.aurora.plan.ProtectedItem
import app.aurora.plan.keepActive
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNames
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/*
 * What the person configured (Rules) and what is happening now (Runtime), plus the pure function that
 * turns both into the decision snapshot. No Android here: everything is unit tested with fixed times
 * and zones (RulesTest, ModesTest, IntentTest).
 *
 * Product model (docs/PRODUCT.md):
 * - A protected item has saved preferences ([AppRule]). Saving preferences never starts protection.
 * - A protection session ([ProtectionSession]) has items, one of four modes, a start and a duration.
 * - A schedule creates recurring protection windows, each with its own mode.
 * - Focus enforces a full block on its items for its length.
 * - Intentional App Opening ([IntentConfig]) is not a mode: it asks before an app opens, outside any
 *   stronger restriction, and measures the time the person chose.
 */

/**
 * The four Shield modes.
 *
 * - [Flexible]: fully blocked while active, no way through from the Shield. Turned off from aurora.
 * - [Friction]: a real task (math, a written intention, a reflection) before a timed access grant.
 * - [Cooldown]: a wait *before* access. When it ends, a timed access grant.
 * - [AlwaysBlock]: fully blocked, and no in-app way to end it early. It ends when its timer does.
 *
 * All but Flexible are strict ([strict]): they can't be turned off early, and while they run Settings and
 * uninstalling are locked.
 *
 * Phase 3 stored "Block" (always shielded) and "RulesOnly" (no everyday Shield). Neither exists now:
 * protection only runs inside a session or window, so both read as the safe, cancellable [Flexible].
 */
@Serializable
enum class Mode {
    @JsonNames("Block", "RulesOnly")
    Flexible,
    Friction,
    Cooldown,
    AlwaysBlock,
}

@Serializable
enum class FrictionTask { Math, Intention, Reflection }

@Serializable
enum class Difficulty { Easy, Medium, Hard }

@Serializable
data class FrictionSettings(
    val task: FrictionTask = FrictionTask.Math,
    val difficulty: Difficulty = Difficulty.Medium,
    /** The waiting part of the reflection task. */
    val reflectionSeconds: Int = 20,
)

/** What happens when the time chosen at an intentional opening is used up. */
@Serializable
enum class IntentExpiry { Reminder, Cooldown }

/**
 * Intentional App Opening for one app. Field names before [defaultUsageMinutes] are Phase 3's
 * IntentNote, so saved notes read back unchanged.
 */
@Serializable
data class IntentConfig(
    val reason: String = "",
    val alternative: String = "",
    /** The pause before "Continue intentionally" is available. 0 = no pause. */
    val reflectionSeconds: Int = 15,
    val enabled: Boolean = true,
    /** Preselected in the usage picker; the person can change it every time. Null = no default. */
    val defaultUsageMinutes: Int? = null,
    val expiry: IntentExpiry = IntentExpiry.Reminder,
    /** For [IntentExpiry.Cooldown]: how long the app stays closed after the chosen time is used. */
    val cooldownMinutes: Int = 30,
    /** True: leaving the app ends the session, so every opening is a fresh decision. */
    val freshEachOpen: Boolean = false,
    /** True: after the pause the person confirms "Continue intentionally"; false: the time choice follows at once. */
    val confirmContinue: Boolean = true,
)

/**
 * Saved preferences for one app or site: how a session starts when the person picks it. Nothing here
 * protects anything on its own. Older fields (waitSeconds, cooldownMinutes) are ignored on read.
 */
@Serializable
data class AppRule(
    val mode: Mode = Mode.Flexible,
    val friction: FrictionSettings = FrictionSettings(),
    /** Shield Cooldown: how long to wait after trying to open the app. */
    val cooldownWaitMinutes: Int = 15,
    val intent: IntentConfig? = null,
)

/** A named set of protected items, e.g. "Social". */
@Serializable
data class Group(val id: String, val name: String, val keys: List<String> = emptyList())

/**
 * A weekly window, in local time wherever the phone is. [days] are ISO days (1 = Monday). A window whose
 * end is at or before its start runs past midnight and belongs to the day it starts.
 *
 * It covers [keys] plus every item in [groupIds]. A Phase 3 schedule has neither and covered every
 * protected item ([groupId] null) or one group; [covers] keeps that meaning.
 */
@Serializable
data class Schedule(
    val id: String,
    val name: String,
    val days: Set<Int>,
    val startMinute: Int,
    val endMinute: Int,
    val groupId: String? = null,
    val enabled: Boolean = true,
    val keys: List<String> = emptyList(),
    val groupIds: List<String> = emptyList(),
    val mode: Mode = Mode.Flexible,
    val friction: FrictionSettings = FrictionSettings(),
    val cooldownWaitMinutes: Int = 15,
) {
    val legacy: Boolean get() = keys.isEmpty() && groupIds.isEmpty()
}

@Serializable
data class DailyLimit(val key: String, val minutes: Int, val enabled: Boolean = true)

@Serializable
data class FocusProfile(val id: String, val name: String, val minutes: Int = 25, val groupId: String? = null)

@Serializable
data class Rules(
    val apps: Map<String, AppRule> = emptyMap(),
    val groups: List<Group> = emptyList(),
    val schedules: List<Schedule> = emptyList(),
    val limits: List<DailyLimit> = emptyList(),
    val profiles: List<FocusProfile> = listOf(DefaultProfile),
    /** What the person wants more time for (onboarding). Shown back to them, never sent anywhere. */
    val motivations: List<String> = emptyList(),
    val defaultRule: AppRule = AppRule(),
    /** Content surfaces to leave automatically (Web & content Shield, experimental): [ContentShorts], [ContentReels]. */
    val content: Set<String> = emptySet(),
    /** 0: the Phase 3/reinvention model. 1: presets and configurations ([migratedToConfigurations]). */
    val version: Int = 0,
    val shields: List<ShieldPreset> = emptyList(),
    val intentPresets: List<IntentPreset> = emptyList(),
    val configs: List<UsageConfig> = emptyList(),
    val activations: List<Activation> = emptyList(),
    val trackGoals: List<app.aurora.progress.Goal> = emptyList(),
    /** What the person chose to keep active on Free after a downgrade. See [retainedShield]. */
    val keep: FreeKeep = FreeKeep(),
) {
    fun rule(key: String): AppRule = apps[key] ?: defaultRule

    /** The person's choices that the plan currently enforces; the rest stay saved and paused. */
    fun activeSchedules() = schedules.filter { it.enabled }
    fun activeLimits() = limits.filter { it.enabled }
    fun activeIntents(plan: Plan): Set<String> =
        keepActive(plan, Allowance.IntentApps, apps.filterValues { it.intent?.enabled == true }.keys.toList()).toSet()

    fun intentFor(key: String, plan: Plan): IntentConfig? = apps[key]?.intent?.takeIf { key in activeIntents(plan) }
}

val DefaultProfile = FocusProfile("focus", "Deep work", 25)

const val ContentShorts = "youtube_shorts"
const val ContentReels = "instagram_reels"

/** A key the engine understands: the package name for an app, "web:<domain>" for a site. */
val ProtectedItem.key: String
    get() = when (this) {
        is ProtectedItem.App -> packageName
        is ProtectedItem.Website -> "web:$domain"
    }

fun itemOf(key: String): ProtectedItem? =
    if (key.startsWith(WebPrefix)) ProtectedItem.Website.of(key.removePrefix(WebPrefix)) else ProtectedItem.App.of(key)

const val WebPrefix = "web:"

/** Wall clock, the monotonic clock since boot, and which boot this is (Settings.Global.BOOT_COUNT, -1 if unknown). */
data class DeviceTime(val wall: Long, val elapsed: Long, val boot: Int)

/**
 * A timed span: Focus, a protection session. Time is counted on the monotonic clock while the phone
 * stays on, so changing the wall clock neither ends nor extends it. After a restart only the wall clock
 * is left, so a reboot neither cancels nor pauses a committed session.
 */
@Serializable
data class Session(
    val startWall: Long,
    val startElapsed: Long,
    val boot: Int,
    val durationMs: Long,
    val groupId: String? = null,
    val profileId: String? = null,
    val intention: String = "",
) {
    fun elapsed(t: DeviceTime): Long =
        if (boot >= 0 && boot == t.boot && t.elapsed >= startElapsed) t.elapsed - startElapsed
        else (t.wall - startWall).coerceAtLeast(0L)

    fun remaining(t: DeviceTime): Long = (durationMs - elapsed(t)).coerceIn(0L, durationMs)
    fun progress(t: DeviceTime): Float = if (durationMs <= 0L) 1f else 1f - remaining(t).toFloat() / durationMs
    fun isOver(t: DeviceTime): Boolean = remaining(t) == 0L
    /** When it ends or ended, the same however late it is asked: a session settled hours later still lasted its length. */
    fun endWall(t: DeviceTime): Long = startedWall(t) + durationMs
    fun startedWall(t: DeviceTime): Long = t.wall - elapsed(t)

    companion object {
        fun start(t: DeviceTime, minutes: Int, groupId: String? = null, profileId: String? = null, intention: String = "") =
            Session(t.wall, t.elapsed, t.boot, minutes * 60_000L, groupId, profileId, intention.trim())
    }
}

/**
 * One activated protection: [keys] are fixed when it starts, so editing a rule, a group or the protected
 * list never changes what a running session covers. [scheduleId] is set for an Always Block window that
 * was committed from a schedule.
 */
@Serializable
data class ProtectionSession(
    val id: String,
    val keys: Set<String>,
    val mode: Mode,
    val time: Session,
    val friction: FrictionSettings = FrictionSettings(),
    val cooldownWaitMinutes: Int = 15,
    val scheduleId: String? = null,
) {
    /** Only Flexible can be turned off in aurora: Always Block, Cooldown and Friction run to their end. */
    val cancellable: Boolean get() = !mode.strict
}

/** Timed access from a completed Friction task or a finished Cooldown wait. It lifts [level] and weaker. */
@Serializable
data class Grant(val until: Long, val level: Mode)

/**
 * A Shield Cooldown wait for one key. It belongs to one restriction ([restrictionId]) only. [startedAt] is
 * the genuine opening attempt it runs from: each new attempt starts the full wait again ([attempted]).
 */
@Serializable
data class CooldownWait(val readyAt: Long, val restrictionId: String, val startedAt: Long = 0)

/**
 * An intentional opening in progress. [usedMs] is measured foreground time; while the app is in front,
 * [runningSince] is when it came to the front (wall clock).
 */
@Serializable
data class IntentUse(
    val allowanceMs: Long,
    val usedMs: Long = 0,
    val runningSince: Long? = null,
    val expiry: IntentExpiry = IntentExpiry.Reminder,
    val cooldownMinutes: Int = 30,
    val freshEachOpen: Boolean = false,
    val startedAt: Long = 0,
) {
    fun used(now: Long): Long = usedMs + (runningSince?.let { (now - it).coerceAtLeast(0) } ?: 0)
    fun left(now: Long): Long = (allowanceMs - used(now)).coerceAtLeast(0)
    fun spent(now: Long): Boolean = left(now) == 0L
}

/** What is happening now. Kept apart from [Rules] because it changes often. */
@Serializable
data class Runtime(
    val sessions: List<ProtectionSession> = emptyList(),
    /** Friction and Cooldown access grants. Phase 3 stored plain end times under "grants"; those are dropped. */
    val access: Map<String, Grant> = emptyMap(),
    val cooldownWaits: Map<String, CooldownWait> = emptyMap(),
    /** When a reflection pause for a key began (Intent, or the Friction reflection task). */
    val reflections: Map<String, Long> = emptyMap(),
    val intentUses: Map<String, IntentUse> = emptyMap(),
    /** Post-usage cooldowns from Intentional App Opening: closed until the wall-clock value. */
    val intentCooldowns: Map<String, Long> = emptyMap(),
    /** Each post-usage cooldown's full length, so a new attempt can start it again from the beginning. */
    val intentCooldownLengths: Map<String, Long> = emptyMap(),
    /** Configuration runs happening now (Configurations.kt). */
    val runs: List<ConfigRun> = emptyList(),
    /** Occurrences turned off or settled: run id to when its window ends, so they never start again. */
    val closed: Map<String, Long> = emptyMap(),
    /** Flexible schedule windows the person turned off: schedule id to that window's start. */
    val skipped: Map<String, Long> = emptyMap(),
    val focus: Session? = null,
    /** A Phase 3 Lockdown, read once and migrated into an Always Block session ([migrated]). */
    val lockdown: Session? = null,
    /** Committed Cooldown and Always Block time (plan/CommittedQuota.kt), kept on Plus too so a downgrade counts today honestly. */
    val ledger: List<LedgerEntry> = emptyList(),
    /** Scheduled occurrences the day's allowance couldn't cover: run id to window end. They don't start late. */
    val quotaDenied: Map<String, Long> = emptyMap(),
) {
    /** The reflection pause for [key], continuing one that began within [ReflectionReuseMs]. */
    fun reflectionFor(key: String, now: Long): Pair<Runtime, Long> {
        val existing = reflections[key]?.takeIf { now - it in 0 until ReflectionReuseMs }
        return if (existing != null) this to existing else copy(reflections = reflections + (key to now)) to now
    }

    /** Drops what has ended, and ledger entries older than a week. */
    fun pruned(t: DeviceTime): Runtime {
        val now = t.wall
        return copy(
            sessions = sessions.filterNot { it.time.isOver(t) },
            access = access.filterValues { it.until > now },
            reflections = reflections.filterValues { now - it in 0 until ReflectionReuseMs },
            intentCooldowns = intentCooldowns.filterValues { it > now },
            intentCooldownLengths = intentCooldownLengths.filterKeys { (intentCooldowns[it] ?: 0) > now },
            closed = closed.filterValues { it > now },
            ledger = ledger.filter { now - it.end < 7 * 86_400_000L },
            quotaDenied = quotaDenied.filterValues { it > now },
        )
    }

    companion object {
        /** A pause that began more than this long ago starts again: the decision should be fresh. */
        const val ReflectionReuseMs = 5 * 60_000L
    }
}

/**
 * A Phase 3 Lockdown becomes an Always Block session over the same items, keeping its start and length,
 * so a running commitment neither disappears nor becomes cancellable when aurora updates.
 */
fun Runtime.migrated(rules: Rules, active: Set<String>): Runtime {
    val old = lockdown ?: return this
    val session = ProtectionSession("lockdown-${old.startWall}", rules.cover(old.groupId, active), Mode.AlwaysBlock, old)
    return copy(lockdown = null, sessions = sessions + session)
}

/** The window of [this] schedule that contains [now], as wall-clock start and end, or null. */
fun Schedule.windowAt(now: Long, zone: ZoneId): Pair<Long, Long>? {
    val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    return listOf(today.minusDays(1), today).firstNotNullOfOrNull { day ->
        window(day, zone)?.takeIf { (start, end) -> now in start until end }
    }
}

/** The start of the next window after [now], within the coming week, or null. */
fun Schedule.nextStart(now: Long, zone: ZoneId): Long? {
    val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    return (0L..7L).firstNotNullOfOrNull { d -> window(today.plusDays(d), zone)?.first?.takeIf { it > now } }
}

/** Every window that overlaps [from, to), for time-away and history. */
fun Schedule.windowsBetween(from: Long, to: Long, zone: ZoneId): List<Pair<Long, Long>> {
    val first = Instant.ofEpochMilli(from).atZone(zone).toLocalDate().minusDays(1)
    val last = Instant.ofEpochMilli(to).atZone(zone).toLocalDate()
    return generateSequence(first) { it.plusDays(1) }.takeWhile { !it.isAfter(last) }
        .mapNotNull { window(it, zone) }.filter { (s, e) -> s < to && e > from }.toList()
}

/**
 * The window that starts on [day], or null if the schedule doesn't run that day. Start and end are local
 * times, so a window keeps its clock times across daylight-saving changes; a start in a skipped hour
 * moves to just after it.
 */
private fun Schedule.window(day: LocalDate, zone: ZoneId): Pair<Long, Long>? {
    if (day.dayOfWeek.value !in days) return null
    val length = ((endMinute - startMinute) % MinutesPerDay + MinutesPerDay) % MinutesPerDay
    val start = LocalDateTime.of(day, LocalTime.MIDNIGHT).plusMinutes(startMinute.toLong())
    val end = start.plusMinutes(if (length == 0) MinutesPerDay.toLong() else length.toLong())
    return start.atZone(zone).toInstant().toEpochMilli() to end.atZone(zone).toInstant().toEpochMilli()
}

private const val MinutesPerDay = 1440

fun nextMidnight(now: Long, zone: ZoneId): Long =
    Instant.ofEpochMilli(now).atZone(zone).toLocalDate().plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()

/** The active items a rule with [groupId] covers: every active item, or the active items in that group. */
fun Rules.cover(groupId: String?, active: Set<String>): Set<String> =
    if (groupId == null) active else groups.find { it.id == groupId }?.keys.orEmpty().filterTo(mutableSetOf()) { it in active }

/** The active items [schedule] covers: its own items and groups, or (Phase 3) every item or one group. */
fun Rules.covers(schedule: Schedule, active: Set<String>): Set<String> =
    if (schedule.legacy) cover(schedule.groupId, active)
    else (schedule.keys + schedule.groupIds.flatMap { id -> groups.find { it.id == id }?.keys.orEmpty() }).filterTo(mutableSetOf()) { it in active }

/** The restriction id of one schedule window: a Cooldown wait or a skip belongs to that window only. */
fun windowId(scheduleId: String, start: Long) = "schedule:$scheduleId:$start"

/**
 * The decision snapshot. Null when the protected list can't be read: [Decision.Unavailable]. Only what
 * [plan] allows is enforced; the rest stays saved and paused. Running sessions are the exception: their
 * items were fixed when they started, and a downgrade never cancels a commitment.
 *
 * The legacy [Rules.schedules] exist only until the migration at start-up empties them. Until then a Free
 * Always Block window is enforced as a block that can be turned off, as it always was.
 */
fun policyState(
    list: ProtectedList,
    rules: Rules,
    runtime: Runtime,
    plan: Plan,
    exempt: Set<String>,
    usedToday: Map<String, Long>,
    time: DeviceTime,
    zone: ZoneId,
): PolicyState? {
    if (!list.readable) return null
    val now = time.wall
    val active = list.active(plan).map { it.key }.toSet()
    val runs = liveRuns(rules, runtime, plan, time, zone)
    val configured = intentsNow(runs, time)
    val restrictions = buildList {
        for (run in runs) addAll(run.restrictions(time))
        runtime.lockdown?.takeUnless { it.isOver(time) }?.let {
            add(Restriction(ShieldReason.AlwaysBlock, rules.cover(it.groupId, active), it.startedWall(time), it.endWall(time), "lockdown-${it.startWall}", locks = true))
        }
        for (s in runtime.sessions) {
            if (s.time.isOver(time)) continue
            add(
                Restriction(
                    s.mode.reason, s.keys, s.time.startedWall(time), s.time.endWall(time), s.id,
                    friction = s.friction, cooldownWaitMs = s.cooldownWaitMinutes * 60_000L, sessionId = s.id, locks = !s.cancellable,
                ),
            )
        }
        runtime.focus?.takeUnless { it.isOver(time) }?.let {
            add(Restriction(ShieldReason.Focus, rules.cover(it.groupId, active), it.startedWall(time), it.endWall(time), "focus-${it.startWall}"))
        }
        for (schedule in rules.activeSchedules()) {
            val keys = rules.covers(schedule, active)
            val windows = listOfNotNull(schedule.windowAt(now, zone), schedule.nextStart(now, zone)?.let { schedule.windowAt(it, zone) })
            for ((start, end) in windows) {
                if (runtime.skipped[schedule.id] == start && schedule.mode != Mode.AlwaysBlock) continue
                val reason = if (schedule.mode == Mode.AlwaysBlock && plan != Plan.Plus) ShieldReason.Flexible else schedule.mode.reason
                add(
                    Restriction(
                        reason, keys, start, end, windowId(schedule.id, start),
                        friction = schedule.friction, cooldownWaitMs = schedule.cooldownWaitMinutes * 60_000L,
                        scheduleId = schedule.id, label = schedule.name,
                    ),
                )
            }
        }
    }
    // Before the configurations migration, Intent was a per-app setting; after it, it comes from runs.
    val legacyIntents = if (rules.version >= 1) emptyMap()
    else rules.activeIntents(plan).filter { it in active }.associateWith { rules.apps.getValue(it).intent!! }
    val intents = (legacyIntents.keys + configured.keys).associateWith { k ->
        listOfNotNull(legacyIntents[k], configured[k]).reduce(::stricter)
    }
    val retained = rules.retainedShield(plan, now, zone)
    val limits = rules.activeLimits().filter { it.key in active && (retained == null || it.key in retained) }.associate { it.key to it.minutes * 60_000L }
    return PolicyState(
        protectedPackages = active + restrictions.flatMap { it.keys } + intents.keys,
        exempt = exempt,
        restrictions = restrictions,
        access = runtime.access,
        cooldownWaits = runtime.cooldownWaits,
        limits = limits,
        usedToday = usedToday,
        intents = intents,
        intentUses = runtime.intentUses,
        intentCooldowns = runtime.intentCooldowns,
        midnight = nextMidnight(now, zone),
    )
}
