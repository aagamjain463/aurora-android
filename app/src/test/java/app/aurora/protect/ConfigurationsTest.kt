package app.aurora.protect

import app.aurora.plan.Plan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class ConfigurationsTest {
    private val zone = ZoneId.of("Europe/London")
    private val min = 60_000L
    private fun at(y: Int, mo: Int, d: Int, h: Int, m: Int = 0) = LocalDateTime.of(y, mo, d, h, m).atZone(zone).toInstant().toEpochMilli()
    private fun t(wall: Long) = DeviceTime(wall, wall, -1)

    private val social = ShieldPreset("s", "Social", listOf("com.instagram.android"), Mode.Flexible)
    private val strict = ShieldPreset("ab", "Strict", listOf("com.instagram.android"), Mode.AlwaysBlock)
    private val yt = IntentPreset("i", "YouTube", listOf("com.google.android.youtube"), IntentConfig(reflectionSeconds = 10))
    private val config = UsageConfig("c", "Study", shieldId = "s", intentId = "i")
    private val rules = Rules(version = 1, shields = listOf(social, strict), intentPresets = listOf(yt), configs = listOf(config))
    private val list = ProtectedList()

    private fun policy(r: Rules, rt: Runtime, wall: Long, plan: Plan = Plan.Plus) = policyState(list, r, rt, plan, emptySet(), emptyMap(), t(wall), zone)!!

    /** Saving presets and configurations enforces nothing. */
    @Test
    fun savedPresetsDoNotEnforce() {
        val p = policy(rules, Runtime(), at(2026, 10, 27, 9))
        assertEquals(Decision.Allow, decide("com.instagram.android", at(2026, 10, 27, 9), p))
        assertEquals(Decision.Allow, decide("com.google.android.youtube", at(2026, 10, 27, 9), p))
    }

    /** Scenario B: one Shield and one Intent together, each app under its own rule. */
    @Test
    fun aRunAppliesItsShieldAndItsIntent() {
        val now = at(2026, 10, 27, 9)
        val run = ConfigRun("now:1", "c", "Study", shield = social, intent = yt, start = now, end = now + 360 * min)
        val p = policy(rules, Runtime(runs = listOf(run)), now + min)
        assertEquals(ShieldReason.Flexible, (decide("com.instagram.android", now + min, p) as Decision.Shield).reason)
        val yd = decide("com.google.android.youtube", now + min, p) as Decision.Shield
        assertEquals(ShieldReason.Intent, yd.reason)
        assertEquals(10, yd.intent!!.reflectionSeconds)
        assertEquals(Decision.Allow, decide("com.instagram.android", now + 361 * min, policy(rules, Runtime(runs = listOf(run)), now + 361 * min)))
    }

    /** Scenario C: tomorrow 7:00 for two hours. Upcoming, then active, then over. */
    @Test
    fun laterActivatesAtItsTime() {
        val a = Activation("a", "c", Timing.Later(at(2026, 10, 28, 7), 120), createdAt = at(2026, 10, 27, 20))
        val r = rules.copy(activations = listOf(a))
        assertEquals(Decision.Allow, decide("com.instagram.android", at(2026, 10, 28, 6, 59), policy(r, Runtime(), at(2026, 10, 28, 6, 59))))
        assertEquals(at(2026, 10, 28, 7), a.nextStart(at(2026, 10, 27, 21), zone))
        val during = at(2026, 10, 28, 8)
        assertTrue(decide("com.instagram.android", during, policy(r, Runtime(), during)) is Decision.Shield)
        assertEquals(Decision.Allow, decide("com.instagram.android", at(2026, 10, 28, 9, 1), policy(r, Runtime(), at(2026, 10, 28, 9, 1))))
    }

    /** Weekdays 22:00 for 9 hours: overnight, and across the October daylight-saving change. */
    @Test
    fun recurringOvernightAndDst() {
        val a = Activation("a", "c", Timing.Recurring(setOf(6), 22 * 60, 9 * 60), createdAt = 0)
        // Saturday 24 Oct 2026 22:00 BST to Sunday 25 Oct 07:00 GMT: clocks go back at 02:00 BST.
        val w = a.windows(at(2026, 10, 25, 3), zone).first()
        assertEquals(at(2026, 10, 24, 22), w.first)
        assertEquals(at(2026, 10, 25, 7), w.second)
        assertEquals(10 * 60 * min, w.second - w.first)
        assertTrue(decide("com.instagram.android", at(2026, 10, 25, 3), policy(rules.copy(activations = listOf(a)), Runtime(), at(2026, 10, 25, 3))) is Decision.Shield)
    }

    /** A recurring plan starts with its next window, not one already under way when it was made. */
    @Test
    fun recurringDoesNotStartMidWindow() {
        val made = at(2026, 10, 27, 10)
        val a = Activation("a", "c", Timing.Recurring((1..7).toSet(), 9 * 60, 120), createdAt = made)
        assertEquals(Decision.Allow, decide("com.instagram.android", made + min, policy(rules.copy(activations = listOf(a)), Runtime(), made + min)))
        assertEquals(at(2026, 10, 28, 9), a.nextStart(made, zone))
    }

    @Test
    fun recurringEndDateIsInclusive() {
        val a = Activation("a", "c", Timing.Recurring((1..7).toSet(), 9 * 60, 60, until = "2026-10-28"), createdAt = 0)
        assertEquals(at(2026, 10, 28, 9), a.nextStart(at(2026, 10, 27, 12), zone))
        assertNull(a.nextStart(at(2026, 10, 28, 12), zone))
    }

    /** Scenario G: Always runs with no end; Always isn't allowed with an Always Block Shield. */
    @Test
    fun alwaysAndItsEligibility() {
        val a = Activation("a", "c", Timing.Always, createdAt = 5)
        val now = at(2027, 1, 1, 0)
        assertTrue(decide("com.instagram.android", now, policy(rules.copy(activations = listOf(a)), Runtime(), now)) is Decision.Shield)
        val strictConfig = UsageConfig("x", "Strict", shieldId = "ab")
        assertEquals(ActivationProblem.AlwaysWithAlwaysBlock, rules.problem(strictConfig, Timing.Always, Plan.Plus, emptyList(), now))
        assertNull(rules.problem(strictConfig, Timing.Now(60), Plan.Plus, emptyList(), now))
        assertEquals(ActivationProblem.InThePast, rules.problem(config, Timing.Later(now - 1, 30), Plan.Plus, emptyList(), now))
        assertEquals(ActivationProblem.Empty, rules.problem(UsageConfig("e", "Empty"), Timing.Now(30), Plan.Plus, emptyList(), now))
    }

    @Test
    fun alwaysWithMissingStartBeginsNowAndKeepsItsFrozenStart() {
        val now = at(2026, 10, 27, 9)
        val a = Activation("a", "c", Timing.Always)
        val r = rules.copy(activations = listOf(a))
        val run = runsToMaterialize(r, Runtime(), Plan.Plus, t(now), zone).single()
        assertEquals(now, run.start)
        val later = liveRuns(r, Runtime(runs = listOf(run)), Plan.Plus, t(now + min), zone).single()
        assertEquals(run.id, later.id)
        assertEquals(now, later.start)
    }

    /** Scenario H: a weaker configuration can't lift a committed one over the same app. */
    @Test
    fun strongestRuleWinsAcrossRuns() {
        val now = at(2026, 10, 27, 9)
        val committed = ConfigRun("now:ab", "x", "Strict", shield = strict, start = now, end = now + 60 * min)
        val weak = ConfigRun("now:f", "y", "Light", shield = social.copy(mode = Mode.Friction), intent = yt.copy(keys = listOf("com.instagram.android")), start = now, end = now + 60 * min)
        val p = policy(rules, Runtime(runs = listOf(weak, committed), access = mapOf("com.instagram.android" to Grant(now + 30 * min, Mode.Friction))), now + min)
        assertEquals(ShieldReason.AlwaysBlock, (decide("com.instagram.android", now + min, p) as Decision.Shield).reason)
        assertTrue(!committed.cancellable(t(now + min)))
        // Friction and Cooldown are strict too: only Flexible can be turned off, and an Always run stays cancellable.
        assertTrue(!weak.cancellable(t(now + min)))
        assertTrue(weak.copy(shield = social.copy(mode = Mode.Flexible)).cancellable(t(now + min)))
        assertTrue(weak.copy(end = null).cancellable(t(now + min)))
        assertTrue(committed.restrictions(t(now + min)).single().locks)
        assertTrue(weak.copy(shield = social.copy(mode = Mode.Flexible)).restrictions(t(now + min)).none { it.locks })
        val cooling = rules.copy(shields = rules.shields + social.copy(id = "cd", mode = Mode.Cooldown))
        assertEquals(ActivationProblem.AlwaysWithAlwaysBlock, cooling.problem(UsageConfig("w", "W", shieldId = "cd"), Timing.Always, Plan.Plus, emptyList(), now))
    }

    /** Free: committed only for the allowance, then the same window is Flexible and can be turned off. */
    @Test
    fun freeAlwaysBlockCommitsOnlyItsAllowance() {
        val now = at(2026, 10, 27, 9)
        val run = ConfigRun("rec:a:1", "x", "Strict", shield = strict, start = now, end = now + 180 * min, committedUntil = now + 120 * min)
        assertEquals(listOf(ShieldReason.AlwaysBlock, ShieldReason.Flexible), run.restrictions(t(now)).map { it.reason })
        assertTrue(!run.cancellable(t(now + 119 * min)))
        assertTrue(run.cancellable(t(now + 120 * min)))
    }

    /** Free counts actively enforced items once, across configurations, and one recurring schedule. */
    @Test
    fun freeEntitlementsCountWhatIsEnforced() {
        val five = ShieldPreset("five", "Five", (1..5).map { "a.p$it" })
        val other = ShieldPreset("other", "Other", listOf("a.p1", "a.p6"))
        val r = Rules(
            version = 1, shields = listOf(five, other),
            configs = listOf(UsageConfig("c5", "Five", shieldId = "five"), UsageConfig("co", "Other", shieldId = "other")),
            activations = listOf(Activation("1", "c5", Timing.Always, createdAt = 1), Activation("2", "co", Timing.Always, createdAt = 2)),
        )
        assertEquals(setOf("1"), r.entitledActivations(Plan.Free))
        assertEquals(setOf("1", "2"), r.entitledActivations(Plan.Plus))
        val sameApps = UsageConfig("again", "Again", shieldId = "five")
        assertNull(r.problem(sameApps, Timing.Now(30), Plan.Free, emptyList(), 0))
        assertEquals(ActivationProblem.OverItems, r.problem(UsageConfig("co", "Other", shieldId = "other"), Timing.Now(30), Plan.Free, emptyList(), 0))
        val rec = r.copy(activations = listOf(Activation("r", "c5", Timing.Recurring(setOf(1), 0, 60), createdAt = 1)))
        assertEquals(ActivationProblem.OverSchedules, rec.problem(sameApps, Timing.Recurring(setOf(2), 0, 60), Plan.Free, emptyList(), 0))
    }

    /** A run freezes its presets: editing the saved preset changes only later runs. */
    @Test
    fun editingAPresetDoesNotChangeARunningOccurrence() {
        val now = at(2026, 10, 27, 9)
        val a = Activation("a", "c", Timing.Always, createdAt = 0)
        val r = rules.copy(activations = listOf(a))
        val stored = runsToMaterialize(r, Runtime(), Plan.Plus, t(now), zone).single()
        val edited = r.copy(shields = listOf(social.copy(keys = listOf("com.other"))))
        val p = policy(edited, Runtime(runs = listOf(stored)), now + min)
        assertTrue(decide("com.instagram.android", now + min, p) is Decision.Shield)
        assertEquals(Decision.Allow, decide("com.other", now + min, p))
        // Turned off: closed, and never re-created for the same occurrence.
        val off = Runtime(closed = mapOf(stored.id to Long.MAX_VALUE))
        assertEquals(Decision.Allow, decide("com.instagram.android", now + min, policy(r, off, now + min)))
    }

    /** Two Intent presets on one app: the stricter one applies. */
    @Test
    fun stricterIntentWins() {
        val a = IntentConfig(reflectionSeconds = 5, expiry = IntentExpiry.Reminder)
        val b = IntentConfig(reflectionSeconds = 1, expiry = IntentExpiry.Cooldown)
        assertEquals(b, stricter(a, b))
    }
}
