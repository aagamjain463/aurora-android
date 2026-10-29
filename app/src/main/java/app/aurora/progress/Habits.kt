package app.aurora.progress

import kotlinx.serialization.Serializable
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

/*
 * Habits (docs/CONFIGURATIONS.md "Habits"). A habit is a goal over one saved custom configuration: it
 * never protects anything itself. Progress is derived from the configuration's run records every time,
 * so it can't drift from what happened. Pure, unit tested (HabitsTest).
 */

/** Which parts of a run must succeed for an occurrence to count. [All]: every part the run has. */
@Serializable
enum class HabitCriterion { All, Protection, Intent, Focus }

/**
 * What the habit expects from [since] on: specific weekdays ([days], ISO 1 = Monday), or a number of
 * times a week ([perWeek]), or neither (on demand: no day is ever missed).
 */
@Serializable
data class HabitTarget(val since: String, val days: Set<Int> = emptySet(), val perWeek: Int? = null) {
    val onDemand: Boolean get() = days.isEmpty() && perWeek == null
}

@Serializable
data class Habit(
    val id: String,
    val name: String,
    val configId: String,
    /** The configuration's name when linked, kept so history reads well after a rename or deletion. */
    val configName: String,
    /** Newest last. Editing adds a target from today, so past days keep the target they had. */
    val targets: List<HabitTarget>,
    val criterion: HabitCriterion = HabitCriterion.All,
    val createdAt: Long = 0,
    val archived: Boolean = false,
) {
    val target: HabitTarget get() = targets.last()
    fun targetOn(date: LocalDate): HabitTarget? = targets.lastOrNull { !LocalDate.parse(it.since).isAfter(date) }
}

/** Whether [r] meets [criterion]: only the parts the criterion names, and only parts the run has. */
fun RunRecord.meets(criterion: HabitCriterion): Outcome = when (criterion) {
    HabitCriterion.All -> outcome
    HabitCriterion.Protection -> protection ?: Outcome.Unverified
    HabitCriterion.Intent -> intent ?: Outcome.Unverified
    HabitCriterion.Focus -> if (hasFocus) focus.outcome() else Outcome.Unverified
}

enum class DayMark { Succeeded, Partial, Missed, Pending, Rest, Before }

data class HabitProgress(
    val currentStreak: Int,
    val longestStreak: Int,
    /** "days" for weekday habits, "weeks" for a number a week; on-demand habits count runs. */
    val streakUnitWeeks: Boolean,
    val successes: Int,
    val missed: Int,
    /** Expected occurrences that are over, or null for an on-demand habit. */
    val expected: Int?,
    val runs: Int,
    val verifiedMs: Long,
    val focusMs: Long,
    /** The last [CalendarDays] days, oldest first. */
    val calendar: List<Pair<LocalDate, DayMark>>,
    /** Successful occurrences per week, for the last [TrendWeeks] weeks, oldest first. */
    val weeks: List<Int>,
) {
    val rate: Float? get() = expected?.takeIf { it > 0 }?.let { successes.toFloat() / it }
}

const val CalendarDays = 35
const val TrendWeeks = 8

/**
 * Progress for [habit] from [records] (any configuration's; filtered here), up to [today].
 *
 * Weekday targets: each expected day before today counts; today counts once it succeeded, otherwise it
 * is pending. A missed expected day breaks the streak; days that aren't expected never do. Weekly
 * targets: a week succeeds with enough successful runs; the current week is pending until it does. On
 * demand: successes over runs, with no misses and no streak of days.
 */
fun habitProgress(habit: Habit, records: List<RunRecord>, today: LocalDate, zone: ZoneId): HabitProgress {
    val mine = records.filter {
        it.configId == habit.configId && it.start >= habit.createdAt &&
            !Instant.ofEpochMilli(it.start).atZone(zone).toLocalDate().isAfter(today)
    }
    val first = Instant.ofEpochMilli(habit.createdAt).atZone(zone).toLocalDate()
    fun dateOf(r: RunRecord) = Instant.ofEpochMilli(r.start).atZone(zone).toLocalDate()
    val byDate = mine.groupBy(::dateOf)
    fun mark(date: LocalDate): DayMark {
        val outcomes = byDate[date].orEmpty().map { it.meets(habit.criterion) }
        val target = habit.targetOn(date)
        return when {
            date.isBefore(first) -> DayMark.Before
            Outcome.Succeeded in outcomes -> DayMark.Succeeded
            target == null || target.onDemand || target.perWeek != null || date.dayOfWeek.value !in target.days ->
                if (Outcome.Partial in outcomes) DayMark.Partial else DayMark.Rest
            date == today -> DayMark.Pending
            Outcome.Partial in outcomes -> DayMark.Partial
            else -> DayMark.Missed
        }
    }
    val calendar = (CalendarDays - 1 downTo 0).map { today.minusDays(it.toLong()) }.map { it to mark(it) }
    val days = generateSequence(first) { it.plusDays(1) }.takeWhile { !it.isAfter(today) }.toList()
    val weekly = habit.target.perWeek != null
    val successRuns = mine.count { it.meets(habit.criterion) == Outcome.Succeeded }
    val onDemandRuns = mine.filter { habit.targetOn(dateOf(it))?.onDemand == true }

    // Judge each occurrence by its historical target, even after switching between days and weeks.
    val dailyResults = days.filter { d -> habit.targetOn(d)?.let { d.dayOfWeek.value in it.days } == true }
        .map { d -> Triple(d, when (mark(d)) {
            DayMark.Succeeded -> true
            DayMark.Pending -> null
            else -> false
        }, false) }
    val weeklyResults = days.filter { habit.targetOn(it)?.perWeek != null }
        .groupBy { it.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)) }
        .map { (monday, inWeek) ->
            val last = inWeek.last()
            val need = habit.targetOn(last)!!.perWeek!!
            val got = inWeek.sumOf { d -> byDate[d].orEmpty().count { it.meets(habit.criterion) == Outcome.Succeeded } }
            Triple(last, when {
                got >= need -> true
                last == today && monday == today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)) -> null
                else -> false
            }, true)
        }
    val results = (dailyResults + weeklyResults).sortedBy { it.first }
    val (current, longest, successes, missed, expected) = if (habit.targets.all { it.onDemand }) {
        listOf(0, 0, successRuns, 0, -1)
    } else {
        // Days and weeks are different streak units; a change starts a new streak, without erasing totals.
        val currentTargets = habit.targets.asReversed().takeWhile {
            (it.perWeek != null) == weekly && it.onDemand == habit.target.onDemand
        }
        val since = LocalDate.parse(currentTargets.last().since)
        val streakResults = results.filter { !it.first.isBefore(since) && it.third == weekly }.map { it.second }
        val (c, l) = streaks(streakResults)
        listOf(
            c, l,
            results.count { it.second == true } + onDemandRuns.count { it.meets(habit.criterion) == Outcome.Succeeded },
            results.count { it.second == false },
            if (habit.target.onDemand) -1 else results.count { it.second != null } + onDemandRuns.size,
        )
    }
    val weeks = (TrendWeeks - 1 downTo 0).map { back ->
        val monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).minusWeeks(back.toLong())
        (0L..6L).sumOf { d -> byDate[monday.plusDays(d)].orEmpty().count { it.meets(habit.criterion) == Outcome.Succeeded } }
    }
    return HabitProgress(
        currentStreak = current, longestStreak = longest, streakUnitWeeks = weekly,
        successes = successes, missed = missed, expected = expected.takeIf { it >= 0 },
        runs = mine.size, verifiedMs = mine.sumOf { it.verifiedMs }, focusMs = mine.sumOf { it.focusMs },
        calendar = calendar, weeks = weeks,
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
