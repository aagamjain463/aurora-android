package app.aurora.protect

import app.aurora.plan.Allowance
import app.aurora.plan.LockdownReservation
import app.aurora.plan.Plan
import app.aurora.plan.ProtectedItem
import app.aurora.plan.keepActive
import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/*
 * What the person configured (Rules) and what is happening now (Runtime), plus the pure function that
 * turns both into the decision snapshot. No Android here: everything is unit tested with fixed times
 * and zones (RulesTest).
 */

/**
 * How aurora protects one app or site outside any timed rule.
 *
 * - [Flexible]: a moment of awareness. The Shield appears and the person may continue at once.
 * - [Friction]: a pause of [AppRule.waitSeconds] before the person may continue. Leaving and coming back
 *   continues the same pause; it doesn't skip or restart it.
 * - [Cooldown]: the person may continue, but after each session the item is closed for
 *   [AppRule.cooldownMinutes]. Friction spaces out every opening; Cooldown spaces out sessions.
 * - [Block]: always shielded, no way through.
 * - [RulesOnly]: no everyday Shield. Only schedules, Focus, Lockdown and daily limits apply.
 */
@Serializable
enum class Mode { Flexible, Friction, Cooldown, Block, RulesOnly }

/** The person's own words, shown when the app opens (aurora Intent). */
@Serializable
data class IntentNote(
    val reason: String,
    val alternative: String = "",
    val reflectionSeconds: Int = 10,
    val enabled: Boolean = true,
)

@Serializable
data class AppRule(
    val mode: Mode = Mode.Friction,
    val waitSeconds: Int = 10,
    val cooldownMinutes: Int = 30,
    val intent: IntentNote? = null,
)

/** A named set of protected items, e.g. "Social". Rules that target a group cover its active items. */
@Serializable
data class Group(val id: String, val name: String, val keys: List<String> = emptyList())

/**
 * A weekly window, in local time wherever the phone is. [days] are ISO days (1 = Monday). A window whose
 * end is at or before its start runs past midnight and belongs to the day it starts. [groupId] null
 * covers every protected item.
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
)

@Serializable
data class DailyLimit(val key: String, val minutes: Int, val enabled: Boolean = true)

@Serializable
data class FocusProfile(val id: String, val name: String, val minutes: Int = 25, val groupId: String? = null)

@Serializable
enum class GoalKind { ScreenTimeUnder, ProtectedTimeUnder, FocusAtLeast, OpensUnder }

/** [target] is minutes per day, or openings per day for [GoalKind.OpensUnder]. */
@Serializable
data class Goal(val id: String, val kind: GoalKind, val target: Int)

@Serializable
data class Rules(
    val apps: Map<String, AppRule> = emptyMap(),
    val groups: List<Group> = emptyList(),
    val schedules: List<Schedule> = emptyList(),
    val limits: List<DailyLimit> = emptyList(),
    val profiles: List<FocusProfile> = listOf(DefaultProfile),
    val goals: List<Goal> = emptyList(),
    /** What the person wants more time for (onboarding). Shown back to them, never sent anywhere. */
    val motivations: List<String> = emptyList(),
    val defaultRule: AppRule = AppRule(),
    /** Content surfaces to leave automatically (Web & content Shield, experimental): [ContentShorts], [ContentReels]. */
    val content: Set<String> = emptySet(),
) {
    fun rule(key: String): AppRule = apps[key] ?: defaultRule

    /** The person's choices that the plan currently enforces; the rest stay saved and paused. */
    fun activeSchedules(plan: Plan) = keepActive(plan, Allowance.Schedules, schedules.filter { it.enabled })
    fun activeLimits(plan: Plan) = keepActive(plan, Allowance.DailyLimitRules, limits.filter { it.enabled })
    fun activeIntents(plan: Plan): Set<String> =
        keepActive(plan, Allowance.IntentApps, apps.filterValues { it.intent?.enabled == true }.keys.toList()).toSet()
    fun activeProfiles(plan: Plan) = keepActive(plan, Allowance.FocusProfiles, profiles)
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
 * A Focus session or a Lockdown. Time is counted on the monotonic clock while the phone stays on, so
 * changing the wall clock neither ends nor extends it. After a restart only the wall clock is left.
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
    fun endWall(t: DeviceTime): Long = t.wall + remaining(t)

    companion object {
        fun start(t: DeviceTime, minutes: Int, groupId: String? = null, profileId: String? = null, intention: String = "") =
            Session(t.wall, t.elapsed, t.boot, minutes * 60_000L, groupId, profileId, intention.trim())
    }
}

@Serializable
data class LedgerEntry(val day: String, val start: Long, val end: Long) {
    fun toReservation() = LockdownReservation(LocalDate.parse(day), Instant.ofEpochMilli(start), Instant.ofEpochMilli(end))
}

fun LockdownReservation.toEntry() = LedgerEntry(day.toString(), start.toEpochMilli(), end.toEpochMilli())

/** What is happening now. Kept apart from [Rules] because it changes often. */
@Serializable
data class Runtime(
    val grants: Map<String, Long> = emptyMap(),
    /** When the current Friction or reflection pause for a key began (wall clock). */
    val waits: Map<String, Long> = emptyMap(),
    val cooldowns: Map<String, Long> = emptyMap(),
    val focus: Session? = null,
    val lockdown: Session? = null,
    val ledger: List<LedgerEntry> = emptyList(),
) {
    /** The pause for [key], continuing one that began within [WaitReuseMs]. Returns the new runtime and its start. */
    fun waitFor(key: String, now: Long): Pair<Runtime, Long> {
        val existing = waits[key]?.takeIf { now - it in 0 until WaitReuseMs }
        return if (existing != null) this to existing else copy(waits = waits + (key to now)) to now
    }

    /** Time the person chose with [key]. A Cooldown item closes for its cooldown once the time ends. */
    fun granted(key: String, minutes: Int, rule: AppRule, now: Long): Runtime {
        val until = now + minutes * 60_000L
        return copy(
            grants = grants + (key to until),
            waits = waits - key,
            cooldowns = if (rule.mode == Mode.Cooldown) cooldowns + (key to until + rule.cooldownMinutes * 60_000L) else cooldowns,
        ).pruned(now)
    }

    /** Drops what has ended, and Lockdown reservations older than a week. */
    fun pruned(now: Long): Runtime = copy(
        grants = grants.filterValues { it > now },
        waits = waits.filterValues { now - it in 0 until WaitReuseMs },
        cooldowns = cooldowns.filterValues { it > now },
        ledger = ledger.filter { now - it.end < 7 * 86_400_000L },
    )

    companion object {
        /** A pause that began more than this long ago starts again: the decision should be fresh. */
        const val WaitReuseMs = 5 * 60_000L
    }
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

/**
 * The decision snapshot. Null when the protected list can't be read: [Decision.Unavailable]. Only what
 * [plan] allows is enforced; the rest stays saved and paused.
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
    val blocks = buildList {
        runtime.lockdown?.takeUnless { it.isOver(time) }?.let {
            add(TimedBlock(ShieldReason.Lockdown, rules.cover(it.groupId, active), now - it.elapsed(time), it.endWall(time)))
        }
        runtime.focus?.takeUnless { it.isOver(time) }?.let {
            add(TimedBlock(ShieldReason.Focus, rules.cover(it.groupId, active), now - it.elapsed(time), it.endWall(time)))
        }
        for (schedule in rules.activeSchedules(plan)) {
            val keys = rules.cover(schedule.groupId, active)
            schedule.windowAt(now, zone)?.let { (start, end) -> add(TimedBlock(ShieldReason.Schedule, keys, start, end)) }
            schedule.nextStart(now, zone)?.let { start ->
                schedule.windowAt(start, zone)?.let { (s, end) -> add(TimedBlock(ShieldReason.Schedule, keys, s, end)) }
            }
        }
    }
    return PolicyState(
        protectedPackages = active,
        exempt = exempt,
        grants = runtime.grants,
        modes = active.associateWith { rules.rule(it).mode },
        blocks = blocks,
        limits = rules.activeLimits(plan).filter { it.key in active }.associate { it.key to it.minutes * 60_000L },
        usedToday = usedToday,
        cooldowns = runtime.cooldowns,
        midnight = nextMidnight(now, zone),
    )
}
