package app.aurora.protect

import app.aurora.plan.LedgerEntry
import app.aurora.plan.Plan.Free
import app.aurora.plan.Plan.Plus
import app.aurora.progress.Goal
import app.aurora.progress.GoalKind
import app.aurora.progress.GoalPeriod
import app.aurora.progress.GoalState
import app.aurora.progress.DayMark
import app.aurora.progress.goalProgress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/** What Plus ending does, and doesn't. All of it is computed from the plan and the saved configuration. */
class DowngradeTest {
    private val zone = ZoneId.of("Asia/Kolkata")
    private val start = LocalDate.of(2026, 10, 28).atTime(12, 0).atZone(zone).toInstant().toEpochMilli()
    private val t = DeviceTime(start, 1_000_000L, 3)
    private val keys = (1..15).map { "com.example.app$it" }
    private val apps = (1..8).map { "com.example.intent$it" }

    private val rules = Rules(
        version = 1,
        shields = listOf(ShieldPreset("s", "All", keys, Mode.Flexible, createdAt = 1)),
        intentPresets = listOf(IntentPreset("i", "I", apps, createdAt = 1)),
        configs = listOf(UsageConfig("c", "All", shieldId = "s", intentId = "i", createdAt = 1)),
        activations = listOf(Activation("a", "c", Timing.Recurring((1..7).toSet(), 11 * 60, 180), true, createdAt = 1)),
        trackGoals = (1..5).map { Goal("g$it", "G$it", GoalKind.Session, GoalPeriod.Daily, 30, createdAt = it.toLong()) },
    )

    @Test
    fun plusKeepsEverythingActive() {
        assertFalse(rules.planExcess(Plus, start, zone).any)
        assertNull(rules.retainedShield(Plus, start, zone))
        assertEquals(5, rules.activeGoalIds(Plus).size)
    }

    @Test
    fun expiryPausesTheExcessWithoutDeletingIt() {
        val excess = rules.planExcess(Free, start, zone)
        assertEquals(10, excess.shield.size)
        assertEquals(5, excess.intent.size)
        assertEquals(4, excess.goals.size)
        // Nothing stored changed: this is the same Rules value.
        assertEquals(15, rules.shields.single().keys.size)
        assertEquals(5, rules.trackGoals.size)
    }

    @Test
    fun unchosenTheOldestStayAndItIsDeterministic() {
        assertEquals(keys.take(5).toSet(), rules.retainedShield(Free, start, zone))
        assertEquals(apps.take(3).toSet(), rules.retainedIntent(Free, start, zone))
        assertEquals(setOf("g1"), rules.activeGoalIds(Free))
        assertEquals(rules.retainedShield(Free, start, zone), rules.retainedShield(Free, start, zone))
    }

    @Test
    fun thePersonsChoiceWinsAndTheRunCoversOnlyThat() {
        val chosen = rules.copy(keep = FreeKeep(keys.takeLast(5).toSet(), apps.takeLast(3).toSet(), "g4"))
        assertEquals(keys.takeLast(5).toSet(), chosen.retainedShield(Free, start, zone))
        assertEquals(setOf("g4"), chosen.activeGoalIds(Free))
        val run = chosen.runFor(chosen.activations.single(), start to start + 3_600_000L, Free, start, zone)!!
        assertEquals(keys.takeLast(5), run.shield?.keys)
        assertEquals(apps.takeLast(3), run.intent?.keys)
    }

    @Test
    fun theEnforcedRunNeverCoversMoreThanFiveTargets() {
        val runs = liveRuns(rules, Runtime(), Free, t, zone).filter { it.covers(t) }
        assertEquals(5, runs.flatMap { it.shield?.keys.orEmpty() }.toSet().size)
        assertEquals(3, runs.flatMap { it.intent?.keys.orEmpty() }.toSet().size)
    }

    @Test
    fun plusReturningRestoresAllOfItAtOnce() {
        val chosen = rules.copy(keep = FreeKeep(keys.take(1).toSet()))
        val run = chosen.runFor(chosen.activations.single(), start to start + 3_600_000L, Plus, start, zone)!!
        assertEquals(15, run.shield?.keys?.size)
        assertEquals(8, run.intent?.keys?.size)
    }

    @Test
    fun aCommittedAlwaysBlockSurvivesExpiry() {
        val session = Session.start(t, 600)
        val run = ConfigRun(
            "now:1", "c", "Committed", shield = ShieldPreset("x", "X", keys, Mode.AlwaysBlock), start = t.wall, end = t.wall + 600 * 60_000L, time = session,
        )
        val rt = Runtime(runs = listOf(run))
        for (plan in listOf(Plus, Free)) {
            val state = policyState(ProtectedList(), rules, rt, plan, emptySet(), emptyMap(), t, zone)!!
            val r = state.restrictions.first { it.id == "now:1" }
            assertEquals(ShieldReason.AlwaysBlock, r.reason)
            assertEquals(keys.toSet(), r.keys)
            assertTrue(r.locks)
            assertEquals(t.wall + 600 * 60_000L, r.end)
        }
        // Nothing the plan does can turn it off.
        assertFalse(run.cancellable(t))
    }

    @Test
    fun whileItRunsItsTargetsCountAndNothingNewJoins() {
        val run = ConfigRun("now:1", "c", "C", shield = ShieldPreset("x", "X", keys, Mode.AlwaysBlock), start = t.wall, end = t.wall + 60_000L, time = Session.start(t, 1))
        val active = activeTargets(Rules(version = 1), Runtime(runs = listOf(run)), t, zone, Free)
        val refusal = targetsRefusal(Free, active, listOf("com.example.new"), emptyList())
        assertEquals(ActivationProblem.OverItems, refusal?.problem)
    }

    @Test
    fun expiredPlusCannotStartNewCommittedTimeOverTheAllowance() {
        // Plus used four hours today. The ledger keeps it, so Free has nothing left today.
        val plusDay = Runtime(ledger = listOf(LedgerEntry(start = start - 4 * 3_600_000L, end = start, zone = zone.id)))
        val c = UsageConfig("n", "N", custom = false, shield = ShieldPreset("n", "N", keys.take(1), Mode.Cooldown))
        val refused = Rules(version = 1).copy(configs = listOf(c)).problem(c, Timing.Now(10), Free, plusDay, t, zone)
        assertEquals(ActivationProblem.OverCommittedAllowance, refused?.problem)
        assertNull(Rules(version = 1).copy(configs = listOf(c)).problem(c, Timing.Now(10), Plus, plusDay, t, zone))
    }

    @Test
    fun aPauseNeitherEarnsNorBreaksAStreak() {
        val created = start - 10 * 86_400_000L
        val today = LocalDate.of(2026, 10, 28)
        val goal = Goal("g", "G", GoalKind.Session, GoalPeriod.Daily, 30, createdAt = created)
        val paused = goal.withMeasuring(false, start - 5 * 86_400_000L).withMeasuring(true, start - 86_400_000L)
        val marks = goalProgress(paused, emptyList(), emptyList(), emptyList(), emptyMap(), today, start, zone).record.toMap()
        assertEquals(DayMark.Rest, marks[today.minusDays(3)])
        assertEquals(DayMark.Missed, marks[today.minusDays(7)])
        // Idempotent: asking twice opens one pause.
        val open = goal.withMeasuring(false, start).withMeasuring(false, start + 5)
        assertEquals(1, open.pauses.size)
        assertNotNull(GoalState.Unknown)
    }

    @Test
    fun goalReconcileIsIdempotentAndRecoversAfterProcessDeath() {
        val once = rules.goalsReconciled(Free, start)
        assertEquals(4, once.trackGoals.count { it.pauses.lastOrNull()?.to == null && it.pauses.isNotEmpty() })
        assertEquals(once, once.goalsReconciled(Free, start + 1_000))
        val back = once.goalsReconciled(Plus, start + 5_000)
        assertEquals(0, back.trackGoals.count { it.pauses.lastOrNull()?.to == null && it.pauses.isNotEmpty() })
    }

    @Test
    fun aRefusedSchedulesNextDayIsItsOwnDecision() {
        // A scheduled Cooldown on a day with no minutes left doesn't run; the same schedule tomorrow does.
        val c = UsageConfig("c", "C", shield = ShieldPreset("s", "S", keys.take(1), Mode.Cooldown), createdAt = 1)
        val r = Rules(version = 1, configs = listOf(c), activations = listOf(Activation("a", "c", Timing.Recurring((1..7).toSet(), 11 * 60, 180), createdAt = 1)))
        val spent = Runtime(ledger = listOf(LedgerEntry(start = start - 3 * 3_600_000L, end = start - 3_600_000L, zone = zone.id)))
        assertTrue(runsRefusedByQuota(r, spent, Free, t, zone).isNotEmpty())
        assertTrue(liveRuns(r, spent, Free, t, zone).none { it.covers(t) })
        val tomorrow = DeviceTime(start + 86_400_000L, t.elapsed + 86_400_000L, t.boot)
        assertTrue(liveRuns(r, spent, Free, tomorrow, zone).any { it.covers(tomorrow) })
    }

    @Test
    fun aDailyLimitIsNoRouteToCommittedProtection() {
        // A limit never locks Settings, never reserves minutes, and exhausting the minutes doesn't touch it.
        val r = Rules(version = 1, limits = listOf(DailyLimit("com.example.app1", 30)))
        val list = ProtectedList(items = listOfNotNull(app.aurora.plan.ProtectedItem.App.of("com.example.app1")))
        val spent = Runtime(ledger = listOf(LedgerEntry(start = start - 3 * 3_600_000L, end = start - 3_600_000L, zone = zone.id)))
        val state = policyState(list, r, spent, Free, emptySet(), emptyMap(), t, zone)!!
        assertEquals(mapOf("com.example.app1" to 30 * 60_000L), state.limits)
        assertTrue(state.restrictions.none { it.locks })
        assertEquals(null, state.lockedUntil(t.wall))
    }

    @Test
    fun plusRunsTheSameScheduleWithNoQuestion() {
        val c = UsageConfig("c", "C", shield = ShieldPreset("s", "S", keys.take(1), Mode.AlwaysBlock), createdAt = 1)
        val r = Rules(version = 1, configs = listOf(c), activations = listOf(Activation("a", "c", Timing.Recurring((1..7).toSet(), 11 * 60, 180), createdAt = 1)))
        val spent = Runtime(ledger = listOf(LedgerEntry(start = start - 6 * 3_600_000L, end = start - 3_600_000L, zone = zone.id)))
        assertTrue(liveRuns(r, spent, Plus, t, zone).any { it.covers(t) })
    }
}
