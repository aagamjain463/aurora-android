package app.aurora.progress

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
        assertEquals(Level(1, "First glow", 0, 100), levelFor(0))
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

}
