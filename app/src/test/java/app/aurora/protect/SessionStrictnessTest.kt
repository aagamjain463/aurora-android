package app.aurora.protect

import app.aurora.plan.LedgerEntry
import app.aurora.plan.LimitReason
import app.aurora.plan.Plan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

/** Strictness belongs to the session; Free's minutes and app limits count what is on at the same time. */
class SessionStrictnessTest {
    private val zone = ZoneId.of("Europe/London")
    private val min = 60_000L
    private fun at(d: Int, h: Int, m: Int = 0) = LocalDateTime.of(2026, 11, d, h, m).atZone(zone).toInstant().toEpochMilli()
    private fun t(wall: Long) = DeviceTime(wall, wall, -1)
    private fun apps(vararg n: Int) = n.map { "com.example.app$it" }

    @Test
    fun aSessionsModeFallsBackToItsShieldsForOlderData() {
        val legacy = ShieldPreset("s", "Old", apps(1), Mode.Cooldown, cooldownWaitMinutes = 7)
        val rules = Rules(version = 1, shields = listOf(legacy))
        assertEquals(Mode.Cooldown, rules.modeOf(UsageConfig("c", "C", shieldId = "s")))
        assertEquals(7, rules.cooldownWaitOf(UsageConfig("c", "C", shieldId = "s")))
        assertEquals(Mode.Friction, rules.modeOf(UsageConfig("c", "C", shieldId = "s", mode = Mode.Friction)))
        assertEquals(Mode.Flexible, rules.modeOf(UsageConfig("c", "C")))
    }

    @Test
    fun aSessionsModeAppliesToItsFrozenShield() {
        val shield = ShieldPreset("s", "Social", apps(1))
        val c = UsageConfig("c", "Study", shieldId = "s", mode = Mode.Friction, friction = FrictionSettings(task = FrictionTask.Intention, phrase = "I choose this"))
        val rules = Rules(version = 1, shields = listOf(shield), configs = listOf(c))
        val run = rules.runFor(Activation("a", "c", Timing.Later(at(10, 9), 60)), at(10, 9) to at(10, 10), Plan.Plus, at(10, 8), zone)!!
        assertEquals(Mode.Friction, run.mode)
        assertEquals(Mode.Friction, run.shield?.mode)
        assertEquals("I choose this", run.shield?.friction?.phrase)
        // The preset itself is unchanged: what is blocked, nothing about how.
        assertEquals(Mode.Flexible, rules.shields.single().mode)
        assertEquals(ShieldReason.Friction, run.restrictions(t(at(10, 9, 30))).single().reason)
    }

    @Test
    fun aStrictSessionWithOnlyAnIntentIsLockedToo() {
        val run = ConfigRun("now:1", "c", "Evening", intent = IntentPreset("i", "Video", apps(2)), start = at(10, 9), end = at(10, 10), mode = Mode.AlwaysBlock)
        assertFalse(run.cancellable(t(at(10, 9, 30))))
        val lock = run.restrictions(t(at(10, 9, 30))).single()
        assertTrue(lock.locks)
        assertTrue(lock.keys.isEmpty())
        // A relaxed one with only an Intent has nothing to lock and can be turned off.
        val relaxed = run.copy(mode = Mode.Flexible)
        assertTrue(relaxed.cancellable(t(at(10, 9, 30))))
        assertTrue(relaxed.restrictions(t(at(10, 9, 30))).isEmpty())
    }

    @Test
    fun overlappingCommittedTimeCountsOnce() {
        val ledger = listOf(LedgerEntry(start = at(10, 10), end = at(10, 11), zone = zone.id), LedgerEntry(start = at(10, 10), end = at(10, 11), zone = zone.id))
        val q = Runtime(ledger = ledger).quotaToday(Plan.Free, t(at(10, 11, 30)), zone)
        assertEquals(60 * min, q.usedMs)
        assertEquals(60 * min, q.remainingMs)
    }

    @Test
    fun freesAppLimitCountsOnlyWhatIsOnAtTheSameTime() {
        val morning = UsageConfig("m", "Morning", shield = ShieldPreset("m", "M", apps(1, 2, 3, 4, 5)))
        val evening = UsageConfig("e", "Evening", shield = ShieldPreset("e", "E", apps(6, 7, 8)))
        val rules = Rules(version = 1, configs = listOf(morning, evening), activations = listOf(Activation("am", "m", Timing.Later(at(10, 9), 60), createdAt = at(10, 7))))
        // Five apps in the morning and three others in the evening: never more than five at once.
        assertNull(rules.sessionLimitRefusal(evening, Timing.Later(at(10, 18), 60), Plan.Free, Runtime(), t(at(10, 8)), zone))
        // The same three during the morning would make eight at once.
        val refusal = rules.sessionLimitRefusal(evening, Timing.Later(at(10, 9, 30), 60), Plan.Free, Runtime(), t(at(10, 8)), zone)
        assertEquals(ActivationProblem.OverItems, refusal?.problem)
        assertEquals(LimitReason.ShieldTargets, refusal?.decision?.reason)
        // A running session counts too, and Plus has no limit.
        val running = ConfigRun("now:1", "m", "Morning", shield = morning.shield, start = at(10, 8), end = at(10, 9))
        assertEquals(ActivationProblem.OverItems, Rules(version = 1).sessionLimitRefusal(evening, Timing.Now(30), Plan.Free, Runtime(runs = listOf(running)), t(at(10, 8, 10)), zone)?.problem)
        assertNull(rules.sessionLimitRefusal(evening, Timing.Later(at(10, 9, 30), 60), Plan.Plus, Runtime(), t(at(10, 8)), zone))
    }

    @Test
    fun freeKeepsWhatEachOccurrenceNeedsAtItsOwnTime() {
        val morning = UsageConfig("m", "Morning", shield = ShieldPreset("m", "M", apps(1, 2, 3, 4, 5)))
        val evening = UsageConfig("e", "Evening", shield = ShieldPreset("e", "E", apps(6, 7, 8, 9, 10)))
        val am = Activation("am", "m", Timing.Later(at(10, 9), 60), createdAt = at(10, 7))
        val pm = Activation("pm", "e", Timing.Later(at(10, 18), 60), createdAt = at(10, 7, 30))
        val rules = Rules(version = 1, configs = listOf(morning, evening), activations = listOf(am, pm))
        // Both schedules run in full on Free: they are never on together.
        assertEquals(setOf("am", "pm"), rules.entitledActivations(Plan.Free, at(10, 8), zone))
        assertEquals(5, rules.runFor(pm, at(10, 18) to at(10, 19), Plan.Free, at(10, 8), zone)!!.shield!!.keys.size)
        assertEquals(5, rules.runFor(am, at(10, 9) to at(10, 10), Plan.Free, at(10, 8), zone)!!.shield!!.keys.size)
    }
}
