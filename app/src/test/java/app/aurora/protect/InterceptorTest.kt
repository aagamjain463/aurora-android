package app.aurora.protect

import org.junit.Assert.assertEquals
import org.junit.Test

class InterceptorTest {
    private class Recorder : ShieldPresenter {
        val shown = mutableListOf<String>()
        val fresh = mutableListOf<Boolean>()
        var dismissed = 0
        var refuse = false
        override fun present(request: ShieldRequest): Boolean {
            shown += request.packageName
            fresh += request.fresh
            return !refuse
        }
        override fun dismiss() {
            dismissed++
        }
    }

    private var now = 1_000L
    private val presenter = Recorder()
    private val trace = ShieldTrace()
    private val scheduled = mutableListOf<() -> Unit>()
    private val interceptor = Interceptor(presenter, trace, "app.aurora", setOf("com.android.systemui", "ime"), { 0L }, { now }, { _, block -> scheduled += block }).apply {
        state = PolicyState(
            setOf("com.social"), exempt = setOf("app.aurora", "com.launcher", "com.android.systemui"),
            restrictions = listOf(Restriction(ShieldReason.AlwaysBlock, setOf("com.social"), 0, Long.MAX_VALUE, "test")),
        )
    }

    private fun window(pkg: String?, after: Long = 50) {
        now += after
        interceptor.onWindow(pkg, eventUptime = now, receivedUptime = now)
    }

    /**
     * Every protected event re-asserts the Shield: dropping repeats left Clock in front on the emulator.
     * A burst from one opening is timed once.
     */
    @Test
    fun everyProtectedEventReassertsButABurstIsTimedOnce() {
        window("com.social")
        window("com.social", after = 20)
        window("com.social", after = 20)
        assertEquals(listOf("com.social", "com.social", "com.social"), presenter.shown)
        assertEquals(listOf(true, false, false), presenter.fresh)
        assertEquals(2, trace.snapshot().repeats)
    }

    @Test
    fun appComingBackOverTheShieldIsShieldedAgainAtOnce() {
        window("com.social")
        window("app.aurora") // the Shield's own window
        window("com.social", after = 10) // the app started an activity over it
        assertEquals(listOf("com.social", "com.social"), presenter.shown)
        assertEquals(listOf(true, true), presenter.fresh)
    }

    @Test
    fun aLaterOpeningIsShieldedAgain() {
        window("com.social")
        window("com.social", after = Interceptor.RepeatWindowMs)
        assertEquals(2, presenter.shown.size)
    }

    @Test
    fun switchingAwayDismissesAPendingShield() {
        window("com.social")
        window("com.mail", after = 10) // rapid switch before the Shield appeared
        assertEquals(1, presenter.dismissed)
        window("com.launcher") // nothing showing any more: nothing to dismiss
        assertEquals(1, presenter.dismissed)
    }

    @Test
    fun overlaysDoNotDismissTheShield() {
        window("com.social")
        window("com.android.systemui")
        window("ime")
        assertEquals(0, presenter.dismissed)
    }

    @Test
    fun rapidAlternationShieldsEveryProtectedOpening() {
        repeat(10) {
            window("com.social", after = 200)
            window("com.mail", after = 200)
        }
        assertEquals(10, presenter.shown.size)
        assertEquals(10, presenter.dismissed)
    }

    @Test
    fun ruleChangesApplyToTheNextEvent() {
        window("com.social")
        interceptor.state = interceptor.state!!.copy(restrictions = emptyList())
        window("com.social", after = 500)
        assertEquals(1, presenter.shown.size)
        interceptor.state = null
        window("com.social", after = 500)
        assertEquals(1, presenter.shown.size)
        assertEquals(1, trace.snapshot().unavailable)
    }

    private fun runChecks() {
        while (scheduled.isNotEmpty()) scheduled.removeAt(0)()
    }

    @Test
    fun aShieldThatShowsNeedsNoSecondRequest() {
        window("com.social")
        window("app.aurora") // the Shield's own window
        runChecks()
        assertEquals(1, presenter.shown.size)
        assertEquals(0, trace.snapshot().recovered)
    }

    @Test
    fun aSilentReturnIsShieldedAgainAFewTimesAtMost() {
        // Clock-style: the app was brought back while the Shield was starting, and sent no window event.
        window("com.social")
        runChecks()
        assertEquals(1 + Interceptor.ConfirmRetries, presenter.shown.size)
        assertEquals(listOf(true, false, false, false), presenter.fresh)
        assertEquals(Interceptor.ConfirmRetries, trace.snapshot().recovered)
    }

    @Test
    fun aCoveredShieldIsCheckedAgain() {
        window("com.social")
        window("app.aurora")
        runChecks()
        interceptor.shieldHidden() // covered, and the app sent nothing
        scheduled.removeAt(0)()
        assertEquals(2, presenter.shown.size)
    }

    @Test
    fun anotherWindowEndsTheChecks() {
        window("com.social")
        interceptor.shieldHidden()
        window("com.launcher") // the person went home
        runChecks()
        assertEquals(1, presenter.shown.size)
        assertEquals(0, trace.snapshot().recovered)
        // Unprotected meanwhile: nothing to re-show either.
        window("com.social", after = 500)
        interceptor.state = interceptor.state!!.copy(restrictions = emptyList())
        runChecks()
        assertEquals(2, presenter.shown.size)
    }

    @Test
    fun refusedPresentationsAreCounted() {
        presenter.refuse = true
        window("com.social")
        assertEquals(1, trace.snapshot().failures)
        assertEquals(0, trace.snapshot().shields)
    }

    @Test
    fun traceKeepsTheLastTimingsOnly() {
        repeat(ShieldTrace.Keep + 5) { i ->
            trace.shown(ShieldRequest("com.social", ShieldReason.AlwaysBlock, eventUptime = 0, receivedUptime = 10, decidedUptime = 11), frameEndUptime = 100L + i)
        }
        val snap = trace.snapshot()
        assertEquals(ShieldTrace.Keep, snap.timings.size)
        assertEquals(ShieldTrace.Timing(detectMs = 10, decideMs = 1, presentMs = 113, exposureMs = 124), snap.timings.last())
        assertEquals(114L to 124L, snap.exposure)
    }

    @Test
    fun closingTheShieldHomeIsNeverUndoneByALateCheck() {
        // The person tapped Return home; the launcher's window event hasn't arrived yet.
        window("com.social")
        interceptor.closedByPerson(home = true)
        runChecks()
        assertEquals(1, presenter.shown.size)
    }

    @Test
    fun auroraOwnScreenDismissesTheShield() {
        window("com.social")
        interceptor.onWindow("app.aurora", 0, 0, ownApp = true)
        assertEquals(1, presenter.dismissed)
        runChecks()
        assertEquals(1, presenter.shown.size)
    }

    @Test
    fun aLateEventFromTheAppJustLeftDoesNotBringTheShieldBack() {
        window("com.social")
        interceptor.closedByPerson(home = true)
        window("com.social", after = 300) // the app's own delayed window event
        assertEquals(1, presenter.shown.size)
        window("com.launcher", after = 2_000)
        window("com.social", after = 100) // a real reopening later is shielded
        assertEquals(2, presenter.shown.size)
    }
}
