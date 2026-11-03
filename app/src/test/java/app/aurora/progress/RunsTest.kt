package app.aurora.progress

import app.aurora.data.ProtectedSpan
import app.aurora.data.UsageInterval
import app.aurora.protect.FocusOutcome
import app.aurora.protect.Mode
import org.junit.Assert.assertEquals
import org.junit.Test

class RunsTest {
    private val h = 3_600_000L

    private val m = 60_000L
    private val always = listOf(ServiceSpan(-h, 3 * h))

    private fun run(
        spans: List<ServiceSpan>, end: Long = 2 * h, turnedOff: Boolean = false, task: String? = null, intent: String? = null,
        overruns: Int = 0, grants: Int = 0, relapses: Int = 0, usage: List<UsageInterval>? = null, shield: String? = "Social",
    ) = record("r", "c", "Study", true, shield, Mode.Flexible, intent, setOf("x"), 0, 2 * h, end, spans, turnedOff, task, 2 * h, overruns, grants, relapses, usage)

    @Test
    fun aTimerAloneProvesNothing() {
        assertEquals(Outcome.Unverified, run(emptyList()).outcome)
        assertEquals(Outcome.Succeeded, run(listOf(ServiceSpan(-h, 3 * h))).outcome)
        assertEquals(Outcome.Partial, run(listOf(ServiceSpan(0, 75 * 60_000L))).outcome)
    }

    @Test
    fun anUntouchedSessionIsVerifiedWhileTheServiceStaysBound() {
        // Last heartbeat at the screen going off, ten minutes in; nothing happened until the session ended.
        val spans = listOf(ServiceSpan(0, 10 * 60_000L))
        assertEquals(Outcome.Succeeded, run(spans.reaching(2 * h, connected = true)).outcome)
        assertEquals(Outcome.Unverified, run(spans.reaching(2 * h, connected = false)).outcome)
        // A span the service closed stays closed: Accessibility was turned off ten minutes in.
        assertEquals(Outcome.NotAchieved, run(listOf(ServiceSpan(0, 10 * 60_000L, closed = true)).reaching(2 * h, connected = true)).outcome)
    }

    @Test
    fun turnedOffEarlyIsNotAchieved() {
        assertEquals(Outcome.NotAchieved, run(listOf(ServiceSpan(0, 3 * h)), end = h, turnedOff = true).outcome)
    }

    @Test
    fun focusIsJudgedWithoutAsking() {
        val done = run(always, task = "Maths")
        assertEquals(FocusOutcome.Completed, done.focus)
        assertEquals(Outcome.Succeeded, done.outcome)
        assertEquals(2 * h, done.focusMs)
        val stopped = run(always, end = h, turnedOff = true, task = "Maths")
        assertEquals(FocusOutcome.NotCompleted, stopped.focus)
        assertEquals(0L, stopped.focusMs)
        // Focus with nothing shielded: ending it early is the only way to break it.
        assertEquals(FocusOutcome.Completed, run(emptyList(), task = "Maths", shield = null).focus)
        assertEquals(FocusOutcome.NotCompleted, run(emptyList(), end = h, turnedOff = true, task = "Maths", shield = null).focus)
    }

    @Test
    fun usingTheAppThroughFrictionOrCooldownIsNotAchieved() {
        assertEquals(Outcome.Succeeded, run(always).outcome)
        assertEquals(Outcome.NotAchieved, run(always, grants = 1).outcome)
    }

    @Test
    fun intentPastItsTimeOrAgainTooSoonIsNotAchieved() {
        assertEquals(Outcome.Succeeded, run(always, intent = "YouTube", shield = null).outcome)
        assertEquals(Outcome.NotAchieved, run(always, intent = "YouTube", overruns = 1).intent)
        assertEquals(Outcome.NotAchieved, run(always, intent = "YouTube", relapses = 1, shield = null).outcome)
    }

    @Test
    fun accessibilityOffAndBackSoonIsARestartNotABypass() {
        // Turned off at 30 minutes, back a minute later (an app update): still protected.
        val restart = listOf(ServiceSpan(-h, 30 * m, closed = true), ServiceSpan(31 * m, 3 * h))
        assertEquals(Outcome.Succeeded, run(restart).outcome)
        // Back after ten minutes: switched off.
        val off = listOf(ServiceSpan(-h, 30 * m, closed = true), ServiceSpan(40 * m, 3 * h))
        assertEquals(Outcome.NotAchieved, run(off).outcome)
        assertEquals(true, run(off).bypassed)
    }

    @Test
    fun appUsedWhileTheServiceWasDownIsABypass() {
        // The phone restarted (safe mode, or off): the service was gone from 30 to 50 minutes, nothing closed it.
        val down = listOf(ServiceSpan(-h, 30 * m), ServiceSpan(50 * m, 3 * h))
        assertEquals(false, run(down, usage = emptyList()).bypassed)
        assertEquals(true, run(down, usage = listOf(UsageInterval("x", 35 * m, 40 * m))).bypassed)
        // Another app, or use while the Shield was up (it doesn't pause the app beneath it), says nothing.
        assertEquals(false, run(down, usage = listOf(UsageInterval("y", 35 * m, 40 * m))).bypassed)
        assertEquals(false, run(down, usage = listOf(UsageInterval("x", 10 * m, 20 * m))).bypassed)
        // Within a minute of the last heartbeat the service was still bound.
        assertEquals(false, run(down, usage = listOf(UsageInterval("x", 30 * m, 30 * m + 50_000))).bypassed)
    }

    @Test
    fun reclaimedCountsOnlyTimeTheServiceWasBound() {
        val bound = listOf(ServiceSpan(0, 30 * m, closed = true), ServiceSpan(50 * m, 60 * m, closed = true)).bound()
        val span = ProtectedSpan(0, 2 * h, setOf("x"))
        assertEquals(40 * m, span.whileBound(bound, evidenceFrom = 0).sumOf { it.end - it.start })
        // Before any service record, protection stands as it was.
        assertEquals(2 * h, span.whileBound(emptyList(), evidenceFrom = Long.MAX_VALUE).sumOf { it.end - it.start })
    }

    @Test
    fun overlappingRunsAreCountedOnce() {
        val spans = listOf(ServiceSpan(0, 10 * h))
        val a = run(spans).copy(id = "a", start = 0, end = 2 * h)
        val b = run(spans).copy(id = "b", start = h, end = 3 * h)
        val p = performance(listOf(a, b), running = 1, spans = spans)
        assertEquals(3 * h, p.verifiedMs)
        assertEquals(3, p.activations)
    }

    @Test
    fun serviceSpansCloseAndReopen() {
        var x = History().serviceSeen(0, connected = true, newSpan = true).serviceSeen(10, true).serviceSeen(20, connected = false)
        x = x.serviceSeen(100, true)
        assertEquals(listOf(ServiceSpan(0, 20, closed = true), ServiceSpan(100, 100)), x.service)
        assertEquals(20L, verifiedWithin(x.service, 0, 1000) - 0)
    }
}
