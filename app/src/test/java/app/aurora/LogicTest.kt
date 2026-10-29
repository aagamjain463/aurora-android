package app.aurora

import app.aurora.data.FocusSession
import app.aurora.data.UsageSignal
import app.aurora.data.UsageSignal.Kind.Start
import app.aurora.data.UsageSignal.Kind.Stop
import app.aurora.data.UsageSignal.Kind.StopAll
import app.aurora.data.foregroundTime
import app.aurora.data.foregroundIntervals
import app.aurora.data.UsageInterval
import app.aurora.data.formatClock
import app.aurora.data.formatDuration
import app.aurora.data.spokenDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LogicTest {
    @Test
    fun foregroundIntervalsSeedAnAlreadyOpenAppAndClipBothEdges() {
        val signals = listOf(
            UsageSignal(Start, "old", 0), UsageSignal(Stop, "old", 20),
            UsageSignal(Start, "youtube", 50), UsageSignal(Stop, "youtube", 150),
            UsageSignal(Start, "other", 180), UsageSignal(Stop, "other", 300),
        )
        assertEquals(
            listOf(UsageInterval("youtube", 100, 150), UsageInterval("other", 180, 200)),
            foregroundIntervals(signals, 100, 200),
        )
        assertEquals(listOf(UsageInterval("youtube", 100, 200)), foregroundIntervals(signals.take(3), 100, 200))
    }


    @Test
    fun foregroundTimeSumsSequentialApps() {
        val signals = listOf(
            UsageSignal(Start, "a", 100), UsageSignal(Stop, "a", 400),
            UsageSignal(Start, "b", 400), UsageSignal(Stop, "b", 450),
            UsageSignal(Start, "a", 500), UsageSignal(Stop, "a", 600),
        )
        assertEquals(mapOf("a" to 400L, "b" to 50L), foregroundTime(signals, 0, 1000))
    }

    @Test
    fun foregroundTimeHandlesWindowEdgesAndScreenOff() {
        val signals = listOf(
            UsageSignal(Stop, "early", 50), // open since before midnight
            UsageSignal(Start, "a", 100),
            UsageSignal(Start, "a", 150), // activity change inside the same app
            UsageSignal(StopAll, "", 300), // screen off
            UsageSignal(Start, "late", 900), // still open now
        )
        assertEquals(mapOf("early" to 50L, "a" to 200L, "late" to 100L), foregroundTime(signals, 0, 1000))
    }

    @Test
    fun focusSessionTracksTime() {
        val s = FocusSession(startedAt = 1_000, durationMs = 60_000, intention = "")
        assertEquals(60_000, s.remaining(0))
        assertEquals(0.5f, s.progress(31_000), 0.0001f)
        assertFalse(s.isComplete(60_999))
        assertTrue(s.isComplete(61_000))
        assertEquals(0, s.remaining(99_000))
    }

    @Test
    fun formatting() {
        assertEquals("<1m", formatDuration(59_999))
        assertEquals("14m", formatDuration(14 * 60_000L))
        assertEquals("2h", formatDuration(120 * 60_000L))
        assertEquals("2h 14m", formatDuration(134 * 60_000L))
        assertEquals("25:00", formatClock(25 * 60_000L))
        assertEquals("00:01", formatClock(1))
        assertEquals("1:30:00", formatClock(90 * 60_000L))
        assertEquals("1 hour 1 minute", spokenDuration(61 * 60_000L))
        assertEquals("25 minutes", spokenDuration(24 * 60_000L + 1))
    }
}
