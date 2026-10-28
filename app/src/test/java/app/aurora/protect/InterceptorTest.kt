package app.aurora.protect

import org.junit.Assert.assertEquals
import org.junit.Test

class InterceptorTest {
    private class Recorder : ShieldPresenter {
        val shown = mutableListOf<String>()
        var dismissed = 0
        var refuse = false
        override fun present(request: ShieldRequest): Boolean {
            shown += request.packageName
            return !refuse
        }
        override fun dismiss() {
            dismissed++
        }
    }

    private var now = 1_000L
    private val presenter = Recorder()
    private val trace = ShieldTrace()
    private val interceptor = Interceptor(presenter, trace, "app.aurora", setOf("com.android.systemui", "ime"), { 0L }, { now }).apply {
        state = PolicyState(setOf("com.social"), exempt = setOf("app.aurora", "com.launcher", "com.android.systemui"))
    }

    private fun window(pkg: String?, after: Long = 50) {
        now += after
        interceptor.onWindow(pkg, eventUptime = now, receivedUptime = now)
    }

    @Test
    fun burstOfEventsFromOneOpeningIsOneShield() {
        window("com.social")
        window("com.social", after = 20)
        window("com.social", after = 20)
        assertEquals(listOf("com.social"), presenter.shown)
        assertEquals(2, trace.snapshot().repeats)
    }

    @Test
    fun appComingBackOverTheShieldIsShieldedAgainAtOnce() {
        window("com.social")
        window("app.aurora") // the Shield's own window
        window("com.social", after = 10) // the app started an activity over it
        assertEquals(listOf("com.social", "com.social"), presenter.shown)
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
        interceptor.state = interceptor.state!!.copy(protectedPackages = emptySet())
        window("com.social", after = 500)
        assertEquals(1, presenter.shown.size)
        interceptor.state = null
        window("com.social", after = 500)
        assertEquals(1, presenter.shown.size)
        assertEquals(1, trace.snapshot().unavailable)
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
            trace.shown(ShieldRequest("com.social", ShieldReason.Protected, eventUptime = 0, receivedUptime = 10, decidedUptime = 11), frameEndUptime = 100L + i)
        }
        val snap = trace.snapshot()
        assertEquals(ShieldTrace.Keep, snap.timings.size)
        assertEquals(ShieldTrace.Timing(decideMs = 1, presentMs = 113, exposureMs = 124), snap.timings.last())
        assertEquals(114L to 124L, snap.exposure)
    }
}
