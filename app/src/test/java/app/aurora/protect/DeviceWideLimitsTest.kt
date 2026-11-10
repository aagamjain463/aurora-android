package app.aurora.protect

import app.aurora.account.mergeCarried
import app.aurora.plan.LedgerEntry
import app.aurora.plan.Plan.Free
import app.aurora.plan.Plan.Plus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/** Free's limits belong to the phone too: switching accounts can't add Shield targets or refill the allowance. */
class DeviceWideLimitsTest {
    private val zone = ZoneId.of("Asia/Kolkata")
    private val start = LocalDate.of(2026, 10, 28).atTime(12, 0).atZone(zone).toInstant().toEpochMilli()
    private val t = DeviceTime(start, 1_000_000L, 3)
    private val hour = 3_600_000L
    private val aKeys = (1..5).map { "com.example.a$it" }
    private val bKeys = (1..5).map { "com.example.b$it" }

    /** A's committed Strict session on five targets, running when A signs out. */
    private val aRuntime = Runtime(
        runs = listOf(ConfigRun("run-a", "ca", "A's evening", shield = ShieldPreset("sa", "A", aKeys, Mode.AlwaysBlock, createdAt = 1), start = start - hour, end = start + hour)),
        ledger = listOf(LedgerEntry(start = start - hour, end = start + hour, zone = zone.id)),
    )

    /** B set up five targets of their own, switched on all day. */
    private val bRules = Rules(
        version = 1,
        shields = listOf(ShieldPreset("sb", "B", bKeys, Mode.Flexible, createdAt = 1)),
        configs = listOf(UsageConfig("cb", "B", shieldId = "sb", createdAt = 1)),
        activations = listOf(Activation("ab", "cb", Timing.Recurring((1..7).toSet(), 0, 24 * 60 - 1), true, createdAt = 1)),
    )

    private val switched = mergeCarried(aRuntime, Runtime(), t, "Commitment")

    @Test
    fun aCarriedCommitmentHoldsFreesSlotsSoBGetsNoExtraFive() {
        // 2. B can't put five more targets under protection while A's five are carried.
        assertEquals(aKeys.toSet(), switched.carriedShield(t))
        val active = activeTargets(bRules, switched, t, zone, Free)
        assertTrue(active.shield.containsAll(aKeys))
        val refusal = targetsRefusal(Free, active, listOf("com.example.new"), emptyList())
        assertEquals(ActivationProblem.OverItems, refusal?.problem)
        // What B had switched on waits: the phone enforces five targets on Free, not ten.
        assertTrue(bRules.entitledActivations(Free, t.wall, zone, switched.carriedShield(t)).isEmpty())
        assertTrue(liveRuns(bRules, switched, Free, t, zone).all { it.carried })
    }

    @Test
    fun aCarriedStrictSessionAlsoHoldsFreesSlots() {
        // A's committed Strict session (a schedule window or a manual start), not a configuration run.
        val aSession = Runtime(sessions = listOf(ProtectionSession("s-a", aKeys.toSet(), Mode.AlwaysBlock, Session(start - hour, t.elapsed - hour, t.boot, 2 * hour))))
        val carried = mergeCarried(aSession, Runtime(), t, "Commitment")
        val active = activeTargets(bRules, carried, t, zone, Free)
        assertEquals("five on the phone, not ten", 5, active.shield.size)
        assertTrue(active.shield.containsAll(aKeys))
        assertTrue(bRules.entitledActivations(Free, t.wall, zone, carried.carriedShield(t)).isEmpty())
    }

    @Test
    fun bsOwnTargetsComeBackWhenTheCarriedCommitmentEnds() {
        val later = DeviceTime(start + 2 * hour, t.elapsed + 2 * hour, t.boot)
        assertTrue(switched.carriedShield(later).isEmpty())
        assertEquals(setOf("ab"), bRules.entitledActivations(Free, later.wall, zone, switched.carriedShield(later)))
        assertNull(targetsRefusal(Free, activeTargets(bRules, switched, later, zone, Free), emptyList(), emptyList()))
    }

    @Test
    fun aTargetBothAccountsShareTakesOneSlot() {
        val shared = bRules.copy(shields = listOf(ShieldPreset("sb", "B", aKeys.take(2) + bKeys.take(3), Mode.Flexible, createdAt = 1)))
        val retained = shared.retainedShield(Free, t.wall, zone, switched.carriedShield(t))!!
        assertTrue(retained.containsAll(aKeys.take(2)))
        assertEquals(5, retained.size)
    }

    @Test
    fun plusIsLimitedByItsEntitlementOnly() {
        assertNull(bRules.retainedShield(Plus, t.wall, zone, switched.carriedShield(t)))
        assertEquals(setOf("ab"), bRules.entitledActivations(Plus, t.wall, zone, switched.carriedShield(t)))
    }

    @Test
    fun theLedgerFollowsThePhoneAcrossAccountsAndBack() {
        // 1 and 3. B starts with A's committed time on this phone; switching back to A brings it all back again.
        assertEquals(aRuntime.ledger, switched.ledger)
        assertEquals(2 * hour, switched.quotaToday(Free, t, zone).usedMs)
        val bLedger = switched.copy(ledger = switched.ledger + LedgerEntry(start = start + hour, end = start + hour + 10 * 60_000L, zone = zone.id))
        val backToA = mergeCarried(bLedger, aRuntime, t, "Commitment")
        assertEquals(bLedger.ledger.toSet(), backToA.ledger.toSet())
        assertEquals("A's own run, not a carried copy of it", 1, backToA.runs.size)
        // Only time crosses over: nothing that names A's targets or plans is in what B received beyond the carried run.
        assertTrue(switched.runs.single().carried)
        assertEquals("Commitment", switched.runs.single().name)
        assertNotNull(switched.runs.single().shield)
    }
}
