package app.aurora

import android.os.Looper
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.aurora.plan.LedgerEntry
import app.aurora.plan.LimitReason
import app.aurora.plan.Plan
import app.aurora.plan.ProtectedItem
import app.aurora.plan.dayWindow
import app.aurora.progress.Goal
import app.aurora.progress.GoalKind
import app.aurora.progress.GoalPeriod
import app.aurora.progress.GoalStatus
import app.aurora.protect.ActivationProblem
import app.aurora.protect.DailyLimit
import app.aurora.protect.IntentConfig
import app.aurora.protect.IntentExpiry
import app.aurora.protect.IntentPreset
import app.aurora.protect.Mode
import app.aurora.protect.ShieldPreset
import app.aurora.protect.Timing
import app.aurora.protect.UsageConfig
import app.aurora.protect.quotaToday
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration
import java.time.ZoneId

/** Free's limits through the real actions and store, the way every screen reaches them. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class PlanFlowTest {
    private val app get() = ApplicationProvider.getApplicationContext<AuroraApplication>()

    /** With a known boot, time here runs on the monotonic clock the tests can advance, as it does on a phone. */
    @Before
    fun boot() {
        Settings.Global.putInt(app.contentResolver, Settings.Global.BOOT_COUNT, 1)
    }

    private fun flow(block: suspend () -> Unit) {
        val job = app.scope.async { block() }
        val deadline = System.nanoTime() + 60_000_000_000L
        while (!job.isCompleted) {
            check(System.nanoTime() < deadline) { "Timed out" }
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(2)
        }
        job.getCompletionExceptionOrNull()?.let { throw it }
    }

    private suspend fun loaded() {
        app.rules.state.filterNotNull().first()
        app.runtime.state.filterNotNull().first()
        app.history.state.filterNotNull().first()
        app.protectedList.filterNotNull().first()
        app.billing.awaitLoaded()
        app.deleteAllData()
        // The store outlives one test's application, and deleting data keeps the ledger on purpose.
        app.runtime.reset()
        app.limitPrompt.value = null
    }

    private fun apps(vararg n: Int) = n.map { "com.example.app$it" }

    private fun session(keys: List<String>, mode: Mode = Mode.Flexible, id: String = keys.joinToString()) =
        UsageConfig("c$id", "Session", custom = false, shield = ShieldPreset("s$id", "S", keys, mode, createdAt = 1), createdAt = 1)

    private fun intent(keys: List<String>, id: String = keys.joinToString()) =
        UsageConfig("i$id", "Intent", custom = false, intent = IntentPreset("p$id", "I", keys, createdAt = 1), createdAt = 1)

    private fun goal(id: String) = Goal(id, id, GoalKind.Session, GoalPeriod.Daily, 30, createdAt = System.currentTimeMillis())

    @Test
    fun theFreePlanIsFreeWithNoVerifiedPurchase() = flow {
        loaded()
        assertEquals(Plan.Free, app.plan)
    }

    // Shield targets

    @Test
    fun aSixthTargetIsRefusedAndNothingIsStored() = flow {
        loaded()
        assertNull(app.activate(session(apps(1, 2, 3, 4, 5)), Timing.Now(30)))
        val refusal = app.activate(session(apps(6)), Timing.Now(30))
        assertEquals(ActivationProblem.OverItems, refusal?.problem)
        assertEquals(LimitReason.ShieldTargets, refusal?.decision?.reason)
        assertEquals(1, app.runtime.value!!.runs.size)
        // And the app's root has been told to explain it.
        assertEquals(LimitReason.ShieldTargets, app.limitPrompt.value?.decision?.reason)
    }

    @Test
    fun anAlreadyProtectedTargetCanBeUsedAgain() = flow {
        loaded()
        assertNull(app.activate(session(apps(1, 2, 3, 4, 5)), Timing.Now(30)))
        assertNull(app.activate(session(apps(1, 5)), Timing.Now(30)))
    }

    @Test
    fun aSavedDraftTakesNoSlot() = flow {
        loaded()
        assertNull(app.saveShield(ShieldPreset("draft", "Draft", apps(11, 12, 13, 14, 15, 16, 17), Mode.Flexible)))
        assertNull(app.activate(session(apps(1, 2, 3, 4, 5)), Timing.Now(30)))
    }

    @Test
    fun aScheduleCountsWhileEnabledAndFreesItsSlotWhenPaused() = flow {
        loaded()
        val five = session(apps(1, 2, 3, 4, 5), id = "five").copy(custom = true)
        assertNull(app.activate(five, Timing.Recurring(setOf(1, 2, 3, 4, 5, 6, 7), 60, 30)))
        assertEquals(ActivationProblem.OverItems, app.activate(session(apps(6)), Timing.Now(10))?.problem)
        val id = app.rules.value!!.activations.single().id
        assertNull(app.setActivationEnabled(id, false))
        assertNull(app.activate(session(apps(6)), Timing.Now(10)))
        // Resuming is activating: the slot has gone, so it's refused.
        val resume = app.setActivationEnabled(id, true)
        assertEquals(ActivationProblem.OverItems, resume?.problem)
        assertFalse(app.rules.value!!.activations.single().enabled)
    }

    @Test
    fun editingAPresetThatIsOnCannotGrowPastTheLimit() = flow {
        loaded()
        val preset = ShieldPreset("p", "P", apps(1, 2, 3, 4, 5), Mode.Flexible, createdAt = 1)
        assertNull(app.saveShield(preset))
        val config = UsageConfig("c", "C", shieldId = "p", createdAt = 1)
        assertNull(app.activate(config, Timing.Recurring(setOf(1, 2, 3, 4, 5, 6, 7), 60, 30)))
        assertEquals(ActivationProblem.OverItems, app.saveShield(preset.copy(keys = apps(1, 2, 3, 4, 5, 6)))?.problem)
        assertEquals(apps(1, 2, 3, 4, 5), app.rules.value!!.shields.single().keys)
        // Replacing one stays within five.
        assertNull(app.saveShield(preset.copy(keys = apps(1, 2, 3, 4, 6))))
    }

    @Test
    fun theSameSiteWrittenTwoWaysIsOneTarget() = flow {
        loaded()
        assertNull(app.activate(session(apps(1, 2, 3, 4) + "web:www.Example.com"), Timing.Now(10)))
        assertNull(app.activate(session(listOf("web:example.com")), Timing.Now(10)))
    }

    @Test
    fun focusCannotBypassTheTargetLimit() = flow {
        loaded()
        // Five different targets are active, and Focus would add more from the protected list.
        assertNull(app.activate(session(apps(1, 2, 3, 4, 5)), Timing.Now(30)))
        ProtectedItem.App.of("com.example.app6")?.let { app.protect(it) }
        assertFalse(app.startFocus(25, "focus", null, "write"))
        assertNull(app.runtime.value!!.focus)
        // Focus itself is free and unlimited: with room, or over the same targets, it starts.
        ProtectedItem.App.of("com.example.app6")?.let { app.unprotect(listOf(it)) }
        assertTrue(app.startFocus(25, "focus", null, "write"))
    }

    @Test
    fun aDailyLimitCountsItsTargetButHasNoLimitOfItsOwn() = flow {
        loaded()
        assertNull(app.activate(session(apps(1, 2, 3, 4, 5)), Timing.Now(30)))
        // A limit on one of the five is fine, however many there are.
        assertNull(app.editRules { r -> r.copy(limits = apps(1, 2, 3, 4, 5).map { DailyLimit(it, 30) }) })
        // A limit on a sixth target would activate it, and is refused.
        assertEquals(ActivationProblem.OverItems, app.editRules { r -> r.copy(limits = r.limits + DailyLimit("com.example.app6", 30)) }?.problem)
        assertEquals(5, app.rules.value!!.limits.size)
    }

    @Test
    fun manySchedulesAndProfilesNeedNoPlus() = flow {
        loaded()
        repeat(4) { assertNull(app.activate(session(apps(1), id = "s$it").copy(custom = true), Timing.Recurring(setOf(1, 2, 3, 4, 5, 6, 7), 100 + it, 10))) }
        assertEquals(4, app.rules.value!!.activations.size)
    }

    // Intent

    @Test
    fun aFourthIntentAppIsRefusedHoweverItIsReached() = flow {
        loaded()
        assertNull(app.activate(intent(apps(1, 2, 3)), Timing.Always))
        assertEquals(ActivationProblem.OverIntentApps, app.activate(intent(apps(4)), Timing.Now(10))?.problem)
        assertEquals(ActivationProblem.OverIntentApps, app.activate(intent(apps(4)), Timing.Recurring(setOf(1), 60, 30))?.problem)
        assertNull(app.activate(intent(apps(1, 2)), Timing.Now(10)))
    }

    @Test
    fun anIntentCooldownStaysIndependentOfTheCommittedMinutes() = flow {
        loaded()
        assertNull(app.activate(session(apps(1), Mode.AlwaysBlock), Timing.Now(120)))
        // The shared minutes are gone; Intent's post-use cooldown still runs, and exhausting minutes doesn't end it.
        assertTrue(app.startIntent("com.example.app2", 1, IntentConfig(expiry = IntentExpiry.Cooldown, cooldownMinutes = 30)))
        ShadowSystemClock.advanceBy(Duration.ofMinutes(2))
        app.settleIntent("com.example.app2")
        assertTrue((app.runtime.value!!.intentCooldowns["com.example.app2"] ?: 0) > app.now().wall)
        // And it took none of the ledger.
        assertEquals(1, app.runtime.value!!.ledger.size)
    }

    // Goals

    @Test
    fun oneActiveGoalAndHistoryIsKept() = flow {
        loaded()
        assertTrue(app.saveGoal(goal("first")).allowed)
        val second = app.saveGoal(goal("second"))
        assertFalse(second.allowed)
        assertEquals(LimitReason.ActiveGoal, second.reason)
        assertEquals(listOf("first"), app.rules.value!!.trackGoals.map { it.id })
        // Archiving keeps it, with its history, and frees the slot.
        assertTrue(app.setGoalStatus("first", GoalStatus.Archived).allowed)
        assertEquals(GoalStatus.Archived, app.rules.value!!.trackGoals.single().status)
        assertTrue(app.saveGoal(goal("second")).allowed)
        assertEquals(setOf("first", "second"), app.rules.value!!.trackGoals.map { it.id }.toSet())
        // A paused or archived Goal can't come back while the slot is taken.
        assertFalse(app.setGoalStatus("first", GoalStatus.Active).allowed)
        assertEquals(GoalStatus.Archived, app.rules.value!!.trackGoals.first { it.id == "first" }.status)
    }

    @Test
    fun switchingGoalsIsOneTransaction() = flow {
        loaded()
        assertTrue(app.saveGoal(goal("a")).allowed)
        assertTrue(app.saveGoal(goal("b").copy(status = GoalStatus.Paused)).allowed)
        assertTrue(app.setGoalStatus("b", GoalStatus.Active, replacing = "a", replacingStatus = GoalStatus.Completed).allowed)
        val goals = app.rules.value!!.trackGoals.associateBy { it.id }
        assertEquals(GoalStatus.Completed, goals["a"]?.status)
        assertEquals(GoalStatus.Active, goals["b"]?.status)
    }

    @Test
    fun aGoalIsNeverCompletedByATimer() = flow {
        loaded()
        assertTrue(app.saveGoal(goal("a")).allowed)
        assertNull(app.activate(session(apps(1)), Timing.Now(10)))
        ShadowSystemClock.advanceBy(Duration.ofMinutes(11))
        app.syncRuns()
        assertEquals(GoalStatus.Active, app.rules.value!!.trackGoals.single().status)
    }

    @Test
    fun simultaneousGoalActivationsCannotBothWin() = flow {
        loaded()
        val results = (1..5).map { i -> app.scope.async { app.saveGoal(goal("g$i")) } }.awaitAll()
        assertEquals(1, results.count { it.allowed })
        assertEquals(1, app.rules.value!!.trackGoals.size)
    }

    // Committed minutes

    @Test
    fun cooldownAndAlwaysBlockShareTheDay() = flow {
        loaded()
        assertNull(app.activate(session(apps(1), Mode.Cooldown, "a"), Timing.Now(60)))
        assertNull(app.activate(session(apps(2), Mode.AlwaysBlock, "b"), Timing.Now(60)))
        // Started at the same moment, they overlap: an hour of coverage so far. Past 120 of coverage is refused.
        val refused = app.activate(session(apps(3), Mode.AlwaysBlock, "c"), Timing.Now(121))
        assertEquals(ActivationProblem.OverCommittedAllowance, refused?.problem)
        assertEquals(LimitReason.CommittedProtection, refused?.decision?.reason)
        assertEquals(2, app.runtime.value!!.runs.size)
    }

    @Test
    fun flexibleAndFrictionNeverTouchTheMinutes() = flow {
        loaded()
        assertNull(app.activate(session(apps(1), Mode.Flexible, "a"), Timing.Now(600)))
        assertNull(app.activate(session(apps(1), Mode.Friction, "b"), Timing.Now(600)))
        assertTrue(app.runtime.value!!.ledger.isEmpty())
        assertEquals(120, app.committedMinutesLeft())
    }

    @Test
    fun simultaneousStartsCannotOversubscribe() = flow {
        loaded()
        // Started together, in whatever order they run: only what fits is reserved, and what is reserved is stored.
        val lengths = listOf(100, 110, 120, 121, 130, 140)
        val results = lengths.mapIndexed { i, minutes ->
            app.scope.async { app.activate(session(apps(i), Mode.AlwaysBlock, "$i"), Timing.Now(minutes)) }
        }.awaitAll()
        assertEquals(3, results.count { it == null })
        assertEquals(3, results.count { it?.problem == ActivationProblem.OverCommittedAllowance })
        assertEquals(3, app.runtime.value!!.ledger.size)
        assertEquals(3, app.runtime.value!!.runs.size)
        assertTrue(usedToday() <= 120)
    }

    @Test
    fun aScheduledRunAfterTheMinutesAreGoneDoesNotStartAndNothingReplacesIt() = flow {
        loaded()
        val z = ZoneId.systemDefault()
        // Today's 120 minutes were spent earlier, in a stretch this run won't overlap.
        val (dayStart, _) = dayWindow(app.now().wall, z)
        app.runtime.update { it.copy(ledger = listOf(LedgerEntry(start = dayStart, end = dayStart + 120 * 60_000L, zone = z.id))) }
        // Friction is free and strict: it holds the clock steady while the test moves it.
        assertNull(app.activate(session(apps(9), Mode.Friction, "f"), Timing.Now(24 * 60)))
        val start = maxOf(app.now().wall + 5 * 60_000L, dayStart + 125 * 60_000L)
        val config = UsageConfig("sched", "Evening", shield = ShieldPreset("sp", "SP", apps(1), Mode.AlwaysBlock, createdAt = 1), createdAt = 1)
        // Creating it is allowed: schedules are unlimited, and one window fits an empty day.
        assertNull(app.activate(config, Timing.Later(start, 10)))
        ShadowSystemClock.advanceBy(Duration.ofMillis(start - app.now().wall + 60_000L))
        assertTrue(app.runsNow().none { it.configId == "sched" })
        app.syncRuns()
        val rt = app.runtime.value!!
        assertTrue(rt.runs.none { it.configId == "sched" })
        assertEquals(1, rt.quotaDenied.size)
        // No softer stand-in: the app isn't covered by this occurrence at all.
        assertTrue(app.runsNow().none { it.configId == "sched" })
        assertEquals(1, rt.ledger.size)
        // Told once: asking again changes nothing.
        app.syncRuns()
        assertEquals(1, app.runtime.value!!.quotaDenied.size)
    }

    @Test
    fun aCommittedSessionSurvivesRestartsOfTheStoreAndTheMinutesStaySpent() = flow {
        loaded()
        assertNull(app.activate(session(apps(1), Mode.Cooldown, "a"), Timing.Now(120)))
        // Delete all data is no way back to a fresh allowance.
        app.deleteAllData()
        assertEquals(0, app.committedMinutesLeft())
        assertEquals(ActivationProblem.OverCommittedAllowance, app.activate(session(apps(2), Mode.AlwaysBlock, "b"), Timing.Now(121))?.problem)
        // A session wholly inside coverage already reserved costs nothing, and starts.
        assertNull(app.activate(session(apps(2), Mode.AlwaysBlock, "c"), Timing.Now(1)))
    }

    @Test
    fun aRefusedStartOffersWhatStillFits() = flow {
        loaded()
        assertNull(app.activate(session(apps(1), Mode.AlwaysBlock, "a"), Timing.Now(95)))
        ShadowSystemClock.advanceBy(Duration.ofMinutes(96))
        val refused = app.activate(session(apps(2), Mode.AlwaysBlock, "b"), Timing.Now(40))
        assertEquals(ActivationProblem.OverCommittedAllowance, refused?.problem)
        assertEquals(25L, (refused?.decision?.remaining ?: 0) / 60_000)
        assertNotNull(app.limitPrompt.value?.shorter)
        assertNull(app.activate(session(apps(2), Mode.AlwaysBlock, "b"), Timing.Now(25)))
    }

    private fun usedToday(): Int = (app.runtime.value!!.quotaToday(app.plan, app.now(), ZoneId.systemDefault()).usedMs / 60_000).toInt()
}
