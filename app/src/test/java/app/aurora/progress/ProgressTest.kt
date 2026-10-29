package app.aurora.progress

import app.aurora.protect.Goal
import app.aurora.protect.GoalKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

class ProgressTest {
    private val today = LocalDate.parse("2026-10-29")

    @Test
    fun xpComesOnlyFromVerifiedBehaviourAndIsCapped() {
        assertEquals(0, DayStats(shields = 40, continued = 12, grantedMinutes = 90, focusMinutes = 50).xp())
        assertEquals(5, DayStats(completedFocusMinutes = 25).xp())
        assertEquals(24, DayStats(completedFocusMinutes = 600).xp())
        assertEquals(10, DayStats(wentBack = 30).xp())
        assertEquals(12, DayStats(alwaysBlockMinutes = 600).xp())
        assertEquals(DailyXpCap, DayStats(completedFocusMinutes = 999, wentBack = 99, alwaysBlockMinutes = 999).xp())
    }

    @Test
    fun focusAndLockdownAreCountedOnce() {
        val record = FocusRecord(start = 1, plannedMs = 25 * 60_000, focusedMs = 25 * 60_000, completed = true)
        val h = History().withFocus(today, record).withFocus(today, record)
        assertEquals(DayStats(focusMinutes = 25, completedFocusMinutes = 25, focusSessions = 1), h.day(today))
        val early = h.withFocus(today, record.copy(start = 2, focusedMs = 10 * 60_000, completed = false))
        assertEquals(35, early.day(today).focusMinutes)
        assertEquals(25, early.day(today).completedFocusMinutes)
        assertEquals(30, History().withAlwaysBlock(today, 5, 30).withAlwaysBlock(today, 5, 30).day(today).alwaysBlockMinutes)
    }

    @Test
    fun levelsGrowGently() {
        assertEquals(Level(1, "First light", 0, 100), levelFor(0))
        assertEquals(2, levelFor(100).number)
        assertEquals(3, levelFor(250).number)
        assertEquals(0.5f, levelFor(175).progress)
    }

    @Test
    fun lightStartsDarkAndFollowsTheWeek() {
        assertEquals(Light(0f, 0f, 0), History().light(today))
        val h = History()
            .record(today) { it.copy(completedFocusMinutes = 50) } // 10 XP
            .record(today.minusDays(3)) { it.copy(wentBack = 5) } // 10 XP
            .record(today.minusDays(9)) { it.copy(wentBack = 5) } // outside the week
        assertEquals(Light(glow = 20 / 140f, rise = 0.5f, activeDays = 2), h.light(today))
    }

    @Test
    fun steadyDaysCountBackFromYesterdayUntilAGap() {
        val h = History()
            .record(today.minusDays(1)) { it.copy(wentBack = 1) }
            .record(today.minusDays(2)) { it.copy(wentBack = 1) }
            .record(today.minusDays(4)) { it.copy(wentBack = 1) }
        assertEquals(2, h.steadyDays(today))
        assertEquals(3, h.record(today) { it.copy(wentBack = 1) }.steadyDays(today))
    }

    @Test
    fun goalsAreHonestAboutWhatIsMeasured() {
        val focus = Goal("f", GoalKind.FocusAtLeast, 30)
        assertEquals(GoalDay(20, null), focus.on(DayStats(focusMinutes = 20), null, null, dayOver = false))
        assertEquals(GoalDay(20, false), focus.on(DayStats(focusMinutes = 20), null, null, dayOver = true))
        assertEquals(GoalDay(30, true), focus.on(DayStats(focusMinutes = 30), null, null, dayOver = false))
        val screen = Goal("s", GoalKind.ScreenTimeUnder, 120)
        assertEquals(GoalDay(null, null), screen.on(DayStats(), screenMinutes = null, protectedMinutes = null, dayOver = true))
        assertEquals(GoalDay(150, false), screen.on(DayStats(), 150, null, dayOver = false))
        assertEquals(GoalDay(90, true), screen.on(DayStats(), 90, null, dayOver = true))
        assertNull(Goal("o", GoalKind.OpensUnder, 2).on(DayStats(continued = 1), null, null, false).met)
    }
}
