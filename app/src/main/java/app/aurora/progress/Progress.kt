@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package app.aurora.progress

import app.aurora.protect.Goal
import app.aurora.protect.GoalKind
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNames
import java.time.LocalDate
import kotlin.math.min

/*
 * Progress, XP and Aurora Light, all derived from what aurora itself observed (docs/PRODUCT.md
 * "Progress"). Nothing is invented: XP is recomputed from the daily record every time, so it can't drift
 * from the data, and a day with no record earns nothing. Pure Kotlin, unit tested (ProgressTest).
 */

/** What happened on one local day. Every field is counted by aurora at the moment it happens. */
@Serializable
data class DayStats(
    /** Shields shown for a new opening (repeats of the same opening aren't counted). */
    val shields: Int = 0,
    /** Times the person chose to go back at a Shield that offered a way through. */
    val wentBack: Int = 0,
    /** Times the person chose to continue. */
    val continued: Int = 0,
    /** Minutes the person chose to spend in protected apps. */
    val grantedMinutes: Int = 0,
    /** Every minute of Focus, ended early or not. */
    val focusMinutes: Int = 0,
    /** Minutes of Focus sessions that ran to the end. Only these earn XP. */
    val completedFocusMinutes: Int = 0,
    val focusSessions: Int = 0,
    /** Committed Always Block minutes (Phase 3: Lockdown minutes, migrated unchanged). */
    @JsonNames("lockdownMinutes") val alwaysBlockMinutes: Int = 0,
    /** Friction tasks completed, and wrong answers given. */
    val frictionDone: Int = 0,
    val frictionMissed: Int = 0,
    /** Intentional App Opening: prompts shown, openings the person let go, sessions started. */
    val intentPrompts: Int = 0,
    val intentLetGo: Int = 0,
    val intentSessions: Int = 0,
    /** Minutes chosen at intentional openings, and foreground minutes actually measured. */
    val intentChosenMinutes: Int = 0,
    val intentUsedMinutes: Int = 0,
    /** Sessions that ended within the chosen time (the person left before it ran out, or finished at the reminder). */
    val intentWithin: Int = 0,
    val remindersShown: Int = 0,
    val intentCooldowns: Int = 0,
    val intentCooldownMinutes: Int = 0,
)

/** A span of protection, recorded when it starts and corrected if it ends early. For time away. */
@Serializable
data class ProtectionRecord(val id: String, val start: Long, val end: Long, val keys: Set<String>)

@Serializable
data class FocusRecord(
    val start: Long,
    val plannedMs: Long,
    val focusedMs: Long,
    val completed: Boolean,
    val profile: String = "",
    val intention: String = "",
)

@Serializable
data class History(
    /** Keyed by ISO date ("2026-10-29"). */
    val days: Map<String, DayStats> = emptyMap(),
    /** Newest first. */
    val focus: List<FocusRecord> = emptyList(),
    /** Starts of Always Block sessions already counted (Phase 3: Lockdowns), so counting is idempotent. */
    @JsonNames("lockdowns") val committed: List<Long> = emptyList(),
    /** Newest last. Kept for [KeepDays]; time away is computed from these. */
    val protection: List<ProtectionRecord> = emptyList(),
) {
    /** Adds or replaces the record with [record]'s id. */
    fun withProtection(record: ProtectionRecord): History =
        copy(protection = (protection.filterNot { it.id == record.id } + record).filter { record.start - it.end < KeepDays * 86_400_000L }.takeLast(KeepSessions))

    /** Ends the record [id] at [at], if it was planned to run longer (turned off early). */
    fun endProtection(id: String, at: Long): History =
        copy(protection = protection.map { if (it.id == id && it.end > at) it.copy(end = maxOf(it.start, at)) else it })

    fun day(date: LocalDate): DayStats = days[date.toString()] ?: DayStats()

    fun record(date: LocalDate, change: (DayStats) -> DayStats): History =
        copy(days = (days + (date.toString() to change(day(date)))).trimmed())

    /** Counts a Focus session once, however often it is settled. */
    fun withFocus(date: LocalDate, record: FocusRecord): History {
        if (focus.any { it.start == record.start }) return this
        val minutes = (record.focusedMs / 60_000).toInt()
        return copy(focus = (listOf(record) + focus).take(KeepSessions)).record(date) {
            it.copy(
                focusMinutes = it.focusMinutes + minutes,
                completedFocusMinutes = it.completedFocusMinutes + if (record.completed) minutes else 0,
                focusSessions = it.focusSessions + 1,
            )
        }
    }

    fun withAlwaysBlock(date: LocalDate, start: Long, minutes: Int): History {
        if (start in committed) return this
        return copy(committed = (listOf(start) + committed).take(KeepSessions)).record(date) {
            it.copy(alwaysBlockMinutes = it.alwaysBlockMinutes + minutes)
        }
    }

    private fun Map<String, DayStats>.trimmed() = if (size <= KeepDays) this else toSortedMap().entries.toList().takeLast(KeepDays).associate { it.toPair() }

    companion object {
        const val KeepDays = 400
        const val KeepSessions = 500
    }
}

/**
 * XP for one day, from verified behaviour only: completed Focus (1 per 5 minutes, at most 24), going back
 * at a Shield or an intentional opening (2 each, at most 5 times), and committed Always Block time
 * (1 per 10 minutes, at most 12). Opening aurora,
 * adding apps or tapping around earns nothing. The caps keep XP from becoming the reason to do anything.
 */
fun DayStats.xp(): Int = min(completedFocusMinutes / 5, 24) + min(wentBack + intentLetGo, 5) * 2 + min(alwaysBlockMinutes / 10, 12)

const val DailyXpCap = 24 + 10 + 12

fun History.totalXp(): Int = days.values.sumOf { it.xp() }

/** A calm level: each one takes a little longer than the last (100, 250, 450, ...). */
data class Level(val number: Int, val name: String, val intoLevel: Int, val levelSize: Int) {
    val progress: Float get() = intoLevel.toFloat() / levelSize
}

private val LevelNames = listOf("First light", "Dawn", "Daybreak", "Sunrise", "Morning", "Clear sky", "Daylight", "High sun")

fun levelFor(xp: Int): Level {
    var n = 1
    var floor = 0
    var size = 100
    while (xp >= floor + size) {
        floor += size
        n++
        size += 50
    }
    return Level(n, LevelNames[min(n, LevelNames.size) - 1], xp - floor, size)
}

/**
 * Aurora Light's state, from the last seven days ending [today]. [glow] is the week's XP against a
 * steady week (20 a day), [rise] is today's against 20. Both are 0 with no record: a first day starts dark.
 */
data class Light(val glow: Float, val rise: Float, val activeDays: Int)

fun History.light(today: LocalDate): Light {
    val week = (0L..6L).map { day(today.minusDays(it)).xp() }
    return Light(
        glow = (week.sum() / 140f).coerceIn(0f, 1f),
        rise = (week.first() / 20f).coerceIn(0f, 1f),
        activeDays = week.count { it > 0 },
    )
}

/** Days in a row, ending today or yesterday, with any XP. Today without XP yet doesn't break it. */
fun History.steadyDays(today: LocalDate): Int {
    var d = if (day(today).xp() > 0) today else today.minusDays(1)
    var n = 0
    while (day(d).xp() > 0) {
        n++
        d = d.minusDays(1)
    }
    return n
}

/**
 * How a goal stands on one day. [value] is what was measured, or null when aurora can't measure it
 * (no usage access, or Android no longer has that day's records).
 */
data class GoalDay(val value: Int?, val met: Boolean?)

/**
 * [screenMinutes] and [protectedMinutes] come from Android's usage records and are null when unavailable.
 * A goal for "under" counts as met only once the day is over ([dayOver]); until then it's "on track".
 */
fun Goal.on(stats: DayStats, screenMinutes: Int?, protectedMinutes: Int?, dayOver: Boolean): GoalDay {
    val value = when (kind) {
        GoalKind.ScreenTimeUnder -> screenMinutes
        GoalKind.ProtectedTimeUnder -> protectedMinutes
        GoalKind.FocusAtLeast -> stats.focusMinutes
        GoalKind.OpensUnder -> stats.continued + stats.intentSessions
    } ?: return GoalDay(null, null)
    val met = when (kind) {
        GoalKind.FocusAtLeast -> if (value >= target) true else if (dayOver) false else null
        else -> if (value > target) false else if (dayOver) true else null
    }
    return GoalDay(value, met)
}

fun GoalKind.title(target: Int): String = when (this) {
    GoalKind.ScreenTimeUnder -> "Screen time under ${hm(target)}"
    GoalKind.ProtectedTimeUnder -> "Protected apps under ${hm(target)}"
    GoalKind.FocusAtLeast -> "Focus for ${hm(target)}"
    GoalKind.OpensUnder -> if (target == 1) "Continue past a Shield at most once" else "Continue past a Shield at most $target times"
}

private fun hm(minutes: Int) = when {
    minutes < 60 -> "$minutes min"
    minutes % 60 == 0 -> "${minutes / 60} h"
    else -> "${minutes / 60} h ${minutes % 60} min"
}
