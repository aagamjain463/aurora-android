package app.aurora.progress

import app.aurora.protect.FocusOutcome
import app.aurora.protect.Mode
import org.junit.Assert.assertEquals
import org.junit.Test

class RunsTest {
    private val h = 3_600_000L

    private fun run(spans: List<ServiceSpan>, end: Long = 2 * h, turnedOff: Boolean = false, focus: FocusOutcome? = null, task: String? = null, intent: String? = null, overruns: Int = 0) =
        record("r", "c", "Study", true, "Social", Mode.Flexible, intent, setOf("x"), 0, 2 * h, end, spans, turnedOff, task, 2 * h, focus, overruns)

    @Test
    fun aTimerAloneProvesNothing() {
        assertEquals(Outcome.Unverified, run(emptyList()).outcome)
        assertEquals(Outcome.Succeeded, run(listOf(ServiceSpan(-h, 3 * h))).outcome)
        assertEquals(Outcome.Partial, run(listOf(ServiceSpan(0, 75 * 60_000L))).outcome)
    }

    @Test
    fun turnedOffEarlyIsNotAchieved() {
        assertEquals(Outcome.NotAchieved, run(listOf(ServiceSpan(0, 3 * h)), end = h, turnedOff = true).outcome)
    }

    @Test
    fun focusNeedsTheReport() {
        val spans = listOf(ServiceSpan(0, 3 * h))
        val awaiting = run(spans, task = "Maths")
        assertEquals(Outcome.Unverified, awaiting.outcome)
        assertEquals(true, awaiting.awaitingReport)
        assertEquals(Outcome.Succeeded, awaiting.copy(focus = FocusOutcome.Completed).outcome)
        assertEquals(Outcome.NotAchieved, awaiting.copy(focus = FocusOutcome.NotCompleted).outcome)
        assertEquals(2 * h, awaiting.copy(focus = FocusOutcome.Partial).focusMs)
    }

    @Test
    fun intentOverrunIsPartial() {
        assertEquals(Outcome.Partial, run(listOf(ServiceSpan(0, 3 * h)), intent = "YouTube", overruns = 1).intent)
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
