package app.aurora.progress

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

class HabitsTest {
    private val zone = ZoneOffset.UTC
    private fun ms(d: LocalDate, hour: Int = 9) = d.atStartOfDay(zone).toInstant().toEpochMilli() + hour * 3_600_000L
    // Monday 2026-10-19 to Sunday 2026-10-25, then the next week.
    private val mon = LocalDate.of(2026, 10, 19)

    private fun ok(d: LocalDate, config: String = "c") = RunRecord("r$d", config, "Study", start = ms(d), end = ms(d, 11), protection = Outcome.Succeeded)
    private fun bad(d: LocalDate) = ok(d).copy(id = "b$d", protection = Outcome.NotAchieved)

    private val weekdays = Habit("h", "Daily study", "c", "Study", listOf(HabitTarget(mon.toString(), days = setOf(1, 2, 3, 4, 5))), createdAt = ms(mon, 0))

    @Test
    fun weekdayStreaksIgnoreRestDays() {
        // Mon–Fri done, weekend off, Monday done, Tuesday today with nothing yet.
        val records = (0L..4L).map { ok(mon.plusDays(it)) } + ok(mon.plusDays(7))
        val p = habitProgress(weekdays, records, mon.plusDays(8), zone)
        assertEquals(6, p.currentStreak)
        assertEquals(6, p.successes)
        assertEquals(0, p.missed)
        assertEquals(6, p.expected)
        assertEquals(DayMark.Pending, p.calendar.last().second)
        assertEquals(DayMark.Rest, p.calendar.first { it.first == mon.plusDays(5) }.second)
    }

    @Test
    fun aMissedExpectedDayBreaksTheStreak() {
        val records = listOf(ok(mon), bad(mon.plusDays(1)), ok(mon.plusDays(2)))
        val p = habitProgress(weekdays, records, mon.plusDays(3), zone)
        assertEquals(1, p.currentStreak)
        assertEquals(1, p.longestStreak)
        assertEquals(1, p.missed)
        assertEquals(2f / 3, p.rate!!, 0.001f)
    }

    @Test
    fun otherConfigurationsDoNotCount() {
        val p = habitProgress(weekdays, listOf(ok(mon, config = "other")), mon.plusDays(1), zone)
        assertEquals(0, p.successes)
        assertEquals(1, p.missed)
    }

    @Test
    fun runsBeforeCreationAndAfterTodayDoNotCount() {
        val h = weekdays.copy(
            createdAt = ms(mon, 10),
            targets = listOf(HabitTarget(mon.toString())),
        )
        val valid = ok(mon).copy(start = ms(mon, 10), verifiedMs = 60_000)
        val records = listOf(ok(mon.minusDays(1)), ok(mon), valid, ok(mon.plusDays(1)))
        val p = habitProgress(h, records, mon, zone)
        assertEquals(1, p.runs)
        assertEquals(1, p.successes)
        assertEquals(60_000L, p.verifiedMs)
        assertEquals(1, p.weeks.last())
        assertEquals(DayMark.Succeeded, p.calendar.last().second)
    }

    @Test
    fun onDemandHasNoMisses() {
        val h = weekdays.copy(targets = listOf(HabitTarget(mon.toString())))
        val p = habitProgress(h, listOf(ok(mon.plusDays(3))), mon.plusDays(10), zone)
        assertEquals(1, p.successes)
        assertEquals(0, p.missed)
        assertNull(p.expected)
    }

    @Test
    fun weeklyTargets() {
        val h = weekdays.copy(targets = listOf(HabitTarget(mon.toString(), perWeek = 2)))
        val records = listOf(ok(mon), ok(mon.plusDays(3)), ok(mon.plusDays(8)))
        val p = habitProgress(h, records, mon.plusDays(9), zone)
        assertEquals(1, p.currentStreak)
        assertEquals(1, p.successes)
        assertEquals(1, p.expected)
    }

    /** Changing the target from today keeps earlier days judged by the target they had. */
    @Test
    fun editingKeepsHistory() {
        val edited = weekdays.copy(targets = weekdays.targets + HabitTarget(mon.plusDays(7).toString(), days = setOf(6, 7)))
        val p = habitProgress(edited, (0L..4L).map { ok(mon.plusDays(it)) }, mon.plusDays(8), zone)
        assertEquals(5, p.successes)
        assertEquals(0, p.missed)
    }

    @Test
    fun changingToWeeklyKeepsEarlierWeekdayResults() {
        val edited = weekdays.copy(targets = weekdays.targets + HabitTarget(mon.plusDays(7).toString(), perWeek = 2))
        val p = habitProgress(edited, (0L..4L).map { ok(mon.plusDays(it)) }, mon.plusDays(8), zone)
        assertEquals(5, p.successes)
        assertEquals(5, p.expected)
        assertEquals(0, p.missed)
        assertEquals(0, p.currentStreak)
    }

    @Test
    fun changingFromWeeklyKeepsEarlierWeeklyResults() {
        val edited = weekdays.copy(targets = listOf(
            HabitTarget(mon.toString(), perWeek = 2),
            HabitTarget(mon.plusDays(7).toString(), days = setOf(1, 2, 3, 4, 5)),
        ))
        val p = habitProgress(edited, listOf(ok(mon), ok(mon.plusDays(1)), ok(mon.plusDays(7))), mon.plusDays(8), zone)
        assertEquals(2, p.successes)
        assertEquals(2, p.expected)
        assertEquals(1, p.currentStreak)
    }

    @Test
    fun changingFromOnDemandDoesNotInventEarlierMisses() {
        val edited = weekdays.copy(targets = listOf(
            HabitTarget(mon.toString()),
            HabitTarget(mon.plusDays(7).toString(), perWeek = 2),
        ))
        val p = habitProgress(edited, emptyList(), mon.plusDays(8), zone)
        assertEquals(0, p.missed)
        assertEquals(0, p.expected)
    }

    @Test
    fun changingToOnDemandStillCountsNewSuccesses() {
        val edited = weekdays.copy(targets = weekdays.targets + HabitTarget(mon.plusDays(7).toString()))
        val p = habitProgress(edited, listOf(ok(mon), ok(mon.plusDays(7))), mon.plusDays(8), zone)
        assertEquals(2, p.successes)
        assertEquals(4, p.missed)
        assertEquals(0, p.currentStreak)
        assertNull(p.expected)
    }

    @Test
    fun earlierOnDemandRunsCountAsOccurrencesAfterChangingToWeekdays() {
        val edited = weekdays.copy(targets = listOf(
            HabitTarget(mon.toString()),
            HabitTarget(mon.plusDays(7).toString(), days = setOf(1, 2, 3, 4, 5)),
        ))
        val p = habitProgress(edited, listOf(ok(mon), bad(mon.plusDays(1)), ok(mon.plusDays(7))), mon.plusDays(8), zone)
        assertEquals(2, p.successes)
        assertEquals(3, p.expected)
        assertEquals(0, p.missed)
        assertEquals(2f / 3, p.rate!!, 0.001f)
    }

    @Test
    fun streakHelper() {
        assertEquals(2 to 3, streaks(listOf(true, true, true, false, true, null, true)))
    }
}
