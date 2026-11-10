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
import app.aurora.protect.SessionUnlock
import app.aurora.protect.ConflictKind
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
        // One session runs at a time, so standing protection holds the five slots.
        assertNull(app.activate(session(apps(1, 2, 3, 4, 5)), Timing.Always))
        val runs = app.runtime.value!!.runs
        val refusal = app.activate(session(apps(6)), Timing.Now(30))
        assertEquals(ActivationProblem.OverItems, refusal?.problem)
        assertEquals(LimitReason.ShieldTargets, refusal?.decision?.reason)
        assertEquals(runs, app.runtime.value!!.runs)
        // And the app's root has been told to explain it.
        assertEquals(LimitReason.ShieldTargets, app.limitPrompt.value?.decision?.reason)
    }

    @Test
    fun oneSessionRunsAtATime() = flow {
        loaded()
        assertNull(app.activate(session(apps(1)), Timing.Now(30)))
        // A second session is refused while it runs, whatever its apps, and nothing is stored.
        val again = app.activate(session(apps(2), id = "y"), Timing.Now(30))
        assertEquals(ActivationProblem.Conflict, again?.problem)
        assertEquals(ConflictKind.Sessions, again?.conflict?.kind)
        assertEquals(1, app.runtime.value!!.runs.size)
        // Later, once it is over, another is fine.
        assertNull(app.activate(session(apps(2), id = "z"), Timing.Later(app.now().wall + 3_600_000L, 30)))
        // Legacy Focus still waits for sessions.
        assertFalse(app.startFocus(25, "focus", null, "write"))
    }

    @Test
    fun aSessionWithManyShieldsUsesItsUnlocks() = flow {
        loaded()
        app.rules.update { it.copy(shields = it.shields + ShieldPreset("sa", "A", apps(1), Mode.Flexible, createdAt = 1) + ShieldPreset("sb", "B", apps(2), Mode.Flexible, createdAt = 1)) }
        val brk = SessionUnlock("u", "Break", minutes = 10, times = 1)
        val c = UsageConfig("many", "Study", shieldIds = listOf("sa", "sb"), unlocks = listOf(brk), createdAt = 1)
        assertNull(app.activate(c, Timing.Now(60)))
        val run = app.runtime.value!!.runs.single()
        assertEquals(apps(1) + apps(2), run.shield?.keys)
        // A break: allowed once, then no more.
        assertTrue(app.useUnlock(run.id, "u"))
        assertFalse(app.useUnlock(run.id, "u"))
        app.endUnlock(run.id)
        assertFalse(app.useUnlock(run.id, "u"))
        // Two Shields that share an app can't be saved together.
        val clash = c.copy(id = "clash", shieldIds = listOf("sa", "sa2"))
        app.rules.update { it.copy(shields = it.shields + ShieldPreset("sa2", "A2", apps(1), Mode.Flexible, createdAt = 1)) }
        assertEquals(ConflictKind.Plan, app.saveConfig(clash)?.conflict?.kind)
        assertTrue(app.rules.value!!.configs.none { it.id == "clash" })
    }

    @Test
    fun anAlreadyProtectedTargetCanBeUsedAgain() = flow {
        loaded()
        // Switched on for later (an app is in one session at a time, so not now): it holds its slots already.
        assertNull(app.activate(session(apps(1, 2, 3, 4, 5)), Timing.Later(app.now().wall + 3_600_000L, 30)))
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
        // Limits count what is on at the same time: a sixth app beside the schedule's window is refused.
        val at = app.now().wall + 3_600_000L
        assertNull(app.activate(five, Timing.Later(at, 60)))
        assertEquals(ActivationProblem.OverItems, app.activate(session(apps(6)), Timing.Later(at + 600_000L, 10))?.problem)
        // Outside its window it's fine.
        assertNull(app.activate(session(apps(6), id = "early"), Timing.Now(10)))
        val id = app.rules.value!!.activations.single { it.configId == five.id }.id
        assertNull(app.setActivationEnabled(id, false))
        assertNull(app.activate(session(apps(6)), Timing.Later(at + 600_000L, 10)))
        // Resuming is activating: the slot has gone, so it's refused.
        val resume = app.setActivationEnabled(id, true)
        assertEquals(ActivationProblem.OverItems, resume?.problem)
        assertFalse(app.rules.value!!.activations.single { it.id == id }.enabled)
    }

    @Test
    fun editingAPresetThatIsOnCannotGrowPastTheLimit() = flow {
        loaded()
        val preset = ShieldPreset("p", "P", apps(1, 2, 3, 4, 5), Mode.Flexible, createdAt = 1)
        assertNull(app.saveShield(preset))
        val config = UsageConfig("c", "C", shieldId = "p", createdAt = 1)
        // An edit is judged on what is on now.
        assertNull(app.activate(config, Timing.Always))
        assertEquals(ActivationProblem.OverItems, app.saveShield(preset.copy(keys = apps(1, 2, 3, 4, 5, 6)))?.problem)
        assertEquals(apps(1, 2, 3, 4, 5), app.rules.value!!.shields.single().keys)
    }

    @Test
    fun theSameSiteWrittenTwoWaysIsOneTarget() = flow {
        loaded()
        assertNull(app.activate(session(apps(1, 2, 3, 4) + "web:www.Example.com"), Timing.Later(app.now().wall + 3_600_000L, 30)))
        assertNull(app.activate(session(listOf("web:example.com")), Timing.Now(10)))
    }

    @Test
    fun focusCannotBypassTheTargetLimit() = flow {
        loaded()
        // Five different targets are active, and Focus would add more from the protected list.
        assertNull(app.activate(session(apps(1, 2, 3, 4, 5)), Timing.Always))
        ProtectedItem.App.of("com.example.app6")?.let { app.protect(it) }
        assertFalse(app.startFocus(25, "focus", null, "write"))
        assertNull(app.runtime.value!!.focus)
        // Focus itself is free and unlimited: with room, or over the same targets, it starts.
        ProtectedItem.App.of("com.example.app6")?.let { app.unprotect(listOf(it)) }
        assertTrue(app.startFocus(25, "focus", null, "write"))
    }

    @Test
    fun manySchedulesAndProfilesNeedNoPlus() = flow {
        loaded()
        repeat(4) { assertNull(app.activate(session(apps(1), id = "s$it").copy(custom = true), Timing.Recurring(setOf(1, 2, 3, 4, 5, 6, 7), 100 + it * 15, 10))) }
        assertEquals(4, app.rules.value!!.activations.size)
    }

    // Intent

    @Test
    fun aFourthIntentAppIsRefusedHoweverItIsReached() = flow {
        loaded()
        assertNull(app.activate(intent(apps(1, 2, 3)), Timing.Always))
        assertEquals(ActivationProblem.OverIntentApps, app.activate(intent(apps(4)), Timing.Now(10))?.problem)
        // A schedule meets Always at its time too.
        assertEquals(ActivationProblem.OverIntentApps, app.activate(intent(apps(4)), Timing.Recurring(setOf(1), 60, 30))?.problem)
        // The same apps again fit the limit, but an app is in one session at a time.
        assertEquals(ActivationProblem.Conflict, app.activate(intent(apps(1, 2)), Timing.Now(10))?.problem)
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

    @Test
    fun aWebsiteIntentLastsWhileItsBrowserIsInFront() = flow {
        loaded()
        assertTrue(app.startIntent("web:reddit.com", 10, IntentConfig()))
        // The browser coming back to the front after the Shield is the site, not leaving it.
        app.foregroundChanged("com.android.chrome")
        assertTrue("web:reddit.com" in app.runtime.value!!.intentUses)
        // Any other app ends it, as leaving an app does.
        app.foregroundChanged("com.example.app1")
        assertFalse("web:reddit.com" in app.runtime.value!!.intentUses)
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
        // One session at a time: the next starts once the first has ended, and they share the day's 120.
        ShadowSystemClock.advanceBy(Duration.ofMinutes(61))
        val refused = app.activate(session(apps(2), Mode.AlwaysBlock, "b"), Timing.Now(61))
        assertEquals(ActivationProblem.OverCommittedAllowance, refused?.problem)
        assertEquals(LimitReason.CommittedProtection, refused?.decision?.reason)
        assertNull(app.activate(session(apps(2), Mode.AlwaysBlock, "c"), Timing.Now(60)))
        assertEquals(2, app.runtime.value!!.ledger.size)
    }

    @Test
    fun flexibleAndFrictionNeverTouchTheMinutes() = flow {
        loaded()
        assertNull(app.activate(session(apps(1), Mode.Flexible, "a"), Timing.Now(600)))
        // One session at a time: Flexible can be turned off, and then Friction starts.
        assertTrue(app.turnOffRun(app.runtime.value!!.runs.single().id))
        assertNull(app.activate(session(apps(1), Mode.Friction, "b"), Timing.Now(600)))
        assertTrue(app.runtime.value!!.ledger.isEmpty())
        assertEquals(120, app.committedMinutesLeft())
    }

    @Test
    fun simultaneousStartsCannotOversubscribe() = flow {
        loaded()
        // Started together, in whatever order they run: one session runs at a time, so one that fits starts and the rest
        // are refused (another is running, or the day's minutes don't cover it). The day never passes 120.
        val lengths = listOf(100, 110, 119, 121, 130, 140)
        val results = lengths.mapIndexed { i, minutes ->
            app.scope.async { app.activate(session(apps(i), Mode.AlwaysBlock, "$i"), Timing.Now(minutes)) }
        }.awaitAll()
        assertEquals(1, results.count { it == null })
        assertTrue(results.filterNotNull().all { it.problem == ActivationProblem.OverCommittedAllowance || it.problem == ActivationProblem.Conflict })
        assertEquals(results.count { it == null }, app.runtime.value!!.runs.size)
        assertTrue(usedToday() <= 120)
    }

    @Test
    fun aScheduledRunAfterTheMinutesAreGoneDoesNotStartAndNothingReplacesIt() = flow {
        loaded()
        val z = ZoneId.systemDefault()
        // Today's 120 minutes were spent earlier, in a stretch this run won't overlap.
        val (dayStart, _) = dayWindow(app.now().wall, z)
        app.runtime.update { it.copy(ledger = listOf(LedgerEntry(start = dayStart, end = dayStart + 120 * 60_000L, zone = z.id))) }
        val start = maxOf(app.now().wall + 5 * 60_000L, dayStart + 125 * 60_000L)
        // Friction is free and strict: it holds the clock steady until the schedule (one session runs at a time).
        assertNull(app.activate(session(apps(9), Mode.Friction, "f"), Timing.Now(((start - app.now().wall) / 60_000L - 1).toInt())))
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
        // Nothing else starts beside it: one session runs at a time.
        assertEquals(ActivationProblem.Conflict, app.activate(session(apps(2), Mode.AlwaysBlock, "b"), Timing.Now(1))?.problem)
        // Once it has ended, the spent minutes still count.
        ShadowSystemClock.advanceBy(Duration.ofMinutes(121))
        assertEquals(ActivationProblem.OverCommittedAllowance, app.activate(session(apps(2), Mode.AlwaysBlock, "c"), Timing.Now(1))?.problem)
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
