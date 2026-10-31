package app.aurora.progress

import app.aurora.data.unionLength
import kotlinx.serialization.Serializable
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

/*
 * Goals and Track's session numbers. A goal never blocks or starts anything: it is judged from run records,
 * Shield service spans and Android's usage records, so it can't drift from what happened. Pure, unit tested
 * (GoalsTest).
 *
 * A session goal counts verified time in its session (only while the Shield service was bound), or sessions
 * that succeeded. It succeeds once the target is reached. A period that ends short has failed, except the
 * one the goal was created in, which may not have had enough time left.
 *
 * A limit goal counts foreground time in its apps, or all screen time. It fails the moment the limit is
 * passed and succeeds when the period ends within it. A day Android has no records for is never a success.
 */

@Serializable
enum class GoalKind { Session, Limit }

/** [Weekly] runs Monday to Sunday. */
@Serializable
enum class GoalPeriod { Daily, Weekly }

@Serializable
data class Goal(
    val id: String,
    val name: String,
    val kind: GoalKind,
    val period: GoalPeriod,
    /** Time to complete (Session) or the most allowed (Limit), per period. Unused when counting [sessions]. */
    val minutes: Int = 0,
    val configId: String? = null,
    /** Session: count successful sessions per period instead of time. */
    val sessions: Int? = null,
    /** Limit: the apps it covers. Empty: the whole screen time. */
    val apps: Set<String> = emptySet(),
    val createdAt: Long = 0,
) {
    /** Milliseconds, or a number of sessions. */
    val target: Long get() = sessions?.toLong() ?: (minutes * 60_000L)

    fun periodStart(date: LocalDate): LocalDate =
        if (period == GoalPeriod.Daily) date else date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))

    fun createdOn(zone: ZoneId): LocalDate = Instant.ofEpochMilli(createdAt).atZone(zone).toLocalDate()

    /** What [usage] (one day's foreground time per app) counts towards this limit. */
    fun usedIn(usage: Map<String, Long>): Long =
        if (apps.isEmpty()) usage.values.sum() else usage.filterKeys { it in apps }.values.sum()
}

enum class DayMark { Succeeded, Missed, Pending, Rest, Before }

enum class GoalState { OnTrack, Achieved, Failed, Unknown }

data class GoalProgress(
    /** The current period's value, in [Goal.target]'s unit. Null: Android has no records to measure it. */
    val value: Long?,
    val state: GoalState,
    /** The last [RecordDays] days or [RecordWeeks] weeks, oldest first. */
    val record: List<Pair<LocalDate, DayMark>>,
    val streak: Int,
    val met: Int,
    val failed: Int,
)

const val RecordDays = 14
const val RecordWeeks = 8

/** A run still going, as Track reads it. */
data class LiveRun(val configId: String, val start: Long, val plannedEnd: Long?, val shielded: Boolean)

/** [usage] is the goal's foreground time per ISO date. */
fun goalProgress(
    goal: Goal, records: List<RunRecord>, live: List<LiveRun>, spans: List<ServiceSpan>, usage: Map<String, Long>,
    today: LocalDate, now: Long, zone: ZoneId,
): GoalProgress {
    val step = if (goal.period == GoalPeriod.Daily) 1L else 7L
    val first = goal.periodStart(goal.createdOn(zone))
    val current = goal.periodStart(today)
    val mine = records.filter { it.configId == goal.configId }
    val windows = mine.map { it.start to it.end } + live.filter { it.configId == goal.configId }.map { it.start to now }

    fun valueIn(start: LocalDate): Long? {
        val from = start.atStartOfDay(zone).toInstant().toEpochMilli()
        val to = start.plusDays(step).atStartOfDay(zone).toInstant().toEpochMilli()
        if (goal.kind == GoalKind.Limit) {
            val days = (0 until step).map { start.plusDays(it) }.filterNot { it.isAfter(today) }.map { usage[it.toString()] }
            return if (null in days) null else days.sumOf { it ?: 0L }
        }
        val a = maxOf(from, goal.createdAt)
        val b = minOf(to, now)
        if (goal.sessions != null) return mine.count { it.start in a until b && it.outcome == Outcome.Succeeded }.toLong()
        return unionLength(windows.filter { it.second > a && it.first < b }.flatMap { (s, e) ->
            spans.mapNotNull { span -> (maxOf(s, span.start, a) to minOf(e, span.seen, b)).takeIf { it.second > it.first } }
        })
    }

    fun mark(start: LocalDate, value: Long?): DayMark = when {
        goal.kind == GoalKind.Limit -> when {
            value == null -> DayMark.Rest
            value > goal.target -> DayMark.Missed
            start == current -> DayMark.Pending
            else -> DayMark.Succeeded
        }
        value != null && value >= goal.target -> DayMark.Succeeded
        start == current -> DayMark.Pending
        start == first -> DayMark.Rest
        else -> DayMark.Missed
    }

    val marks = generateSequence(first) { it.plusDays(step) }.takeWhile { !it.isAfter(current) }.associateWith { mark(it, valueIn(it)) }
    val value = valueIn(current)
    val size = if (goal.period == GoalPeriod.Daily) RecordDays else RecordWeeks
    return GoalProgress(
        value = value,
        state = when (mark(current, value)) {
            DayMark.Succeeded -> GoalState.Achieved
            DayMark.Missed -> GoalState.Failed
            DayMark.Pending -> GoalState.OnTrack
            else -> GoalState.Unknown
        },
        record = (size - 1L downTo 0).map { current.minusDays(it * step) }.map { it to (marks[it] ?: DayMark.Before) },
        streak = streaks(marks.values.map { if (it == DayMark.Succeeded) true else if (it == DayMark.Missed) false else null }).first,
        met = marks.values.count { it == DayMark.Succeeded },
        failed = marks.values.count { it == DayMark.Missed },
    )
}

/** Current and longest runs of true in [results] (oldest first). Null is pending: it neither counts nor breaks. */
fun streaks(results: List<Boolean?>): Pair<Int, Int> {
    var longest = 0
    var run = 0
    for (r in results) {
        when (r) {
            true -> { run++; longest = maxOf(longest, run) }
            false -> run = 0
            null -> Unit
        }
    }
    return run to longest
}

/** Sessions begun in a period, normal and custom, how many succeeded, and the protection time set for them. */
data class SessionStats(val started: Int, val succeeded: Int, val setMs: Long)

/** Counts sessions by the day they began. Time set is the shielded sessions' planned length, or what ran if open-ended. */
fun sessionStats(records: List<RunRecord>, live: List<LiveRun>, from: Long, to: Long, now: Long): SessionStats {
    val done = records.filter { it.start in from until to }
    val going = live.filter { it.start in from until to }
    return SessionStats(
        started = done.size + going.size,
        succeeded = done.count { it.outcome == Outcome.Succeeded },
        setMs = done.filter { it.shieldName != null }.sumOf { (it.plannedEnd ?: it.end) - it.start } +
            going.filter { it.shielded }.sumOf { (it.plannedEnd ?: now) - it.start },
    )
}
