package app.aurora.ui

import app.aurora.ui.components.DurationProblem
import app.aurora.ui.components.durationProblem
import app.aurora.ui.components.minutesText
import app.aurora.ui.components.parseDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The custom duration entry: exact values, never rounded to a preset, with clear limits. */
class DurationTest {
    @Test
    fun customValuesAreKeptExactly() {
        assertEquals(7, parseDuration("", "7", 1, 1440))
        assertEquals(45, parseDuration("0", "45", 1, 1440))
        assertEquals(75, parseDuration("1", "15", 1, 1440))
        assertEquals(220, parseDuration("3", "40", 1, 1440))
        assertEquals(480, parseDuration("8", "", 1, 1440))
    }

    @Test
    fun invalidEntriesAreRejectedWithAReason() {
        assertEquals(DurationProblem.Empty, durationProblem("", "", 1, 1440))
        assertEquals(DurationProblem.NotWhole, durationProblem("-1", "0", 1, 1440))
        assertEquals(DurationProblem.MinutesOver59, durationProblem("1", "75", 1, 1440))
        assertEquals(DurationProblem.TooShort, durationProblem("0", "0", 1, 1440))
        assertEquals(DurationProblem.TooLong, durationProblem("25", "0", 1, 1440))
        assertEquals(DurationProblem.TooLong, durationProblem("2", "1", 1, 120))
        assertNull(parseDuration("x", "1", 1, 1440))
    }

    @Test
    fun lengthsReadNaturally() {
        assertEquals("7 min", minutesText(7))
        assertEquals("1 h", minutesText(60))
        assertEquals("1 h 15 min", minutesText(75))
        assertEquals("3 h 40 min", minutesText(220))
    }
}
