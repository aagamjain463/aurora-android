package app.aurora.protect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Both cooldowns: reset on a genuine attempt, never bypassed (founder scenarios E and F). */
class CooldownTest {
    private val min = 60_000L
    private val restriction = Restriction(ShieldReason.Cooldown, setOf("clock"), 0, Long.MAX_VALUE, "run", cooldownWaitMs = 1 * min)
    private fun cooldown(at: Long, rt: Runtime) = decide("clock", at, state(rt)) as Decision.Shield
    private fun state(rt: Runtime) = PolicyState(
        setOf("clock", "insta"), emptySet(), restrictions = listOf(restriction),
        cooldownWaits = rt.cooldownWaits, intentCooldowns = rt.intentCooldowns, intentUses = rt.intentUses,
        intents = mapOf("insta" to IntentConfig(expiry = IntentExpiry.Cooldown, cooldownMinutes = 45)),
        access = rt.access,
    )

    /** Scenario F: a 1-minute wait, reattempted after 8 s, needs a full minute again from the reattempt. */
    @Test
    fun shieldCooldownRestartsFromEachGenuineAttempt() {
        val t0 = 10 * 3_600_000L
        var rt = Runtime().attempted("clock", cooldown(t0, Runtime()), t0)
        assertEquals(t0 + min, rt.cooldownWaits.getValue("clock").readyAt)
        rt = rt.attempted("clock", cooldown(t0 + 8_000, rt), t0 + 8_000)
        assertEquals(t0 + 8_000 + min, rt.cooldownWaits.getValue("clock").readyAt)
        // Before the new deadline the app is still shielded, with the new deadline.
        assertEquals(t0 + 8_000 + min, cooldown(t0 + 61_000, rt).readyAt)
    }

    /** Showing the Shield again (recomposition, recreation, a recheck) never restarts a running wait. */
    @Test
    fun showingTheShieldDoesNotRestartTheWait() {
        val t0 = 1_000_000L
        val rt = Runtime().attempted("clock", cooldown(t0, Runtime()), t0)
        val again = rt.waitFor("clock", restriction, t0 + 30_000)
        assertEquals(rt, again)
    }

    /** A wait that just finished stays finished for a few minutes: stepping away doesn't mean waiting again. */
    @Test
    fun aJustFinishedWaitIsKeptAndAnOldOneStartsAgain() {
        val t0 = 1_000_000L
        val rt = Runtime().attempted("clock", cooldown(t0, Runtime()), t0)
        val ready = t0 + min
        assertEquals(rt, rt.attempted("clock", cooldown(ready + 10_000, rt), ready + 10_000))
        val later = ready + ReadyGraceMs + 1
        assertEquals(later + min, rt.attempted("clock", cooldown(later, rt), later).cooldownWaits.getValue("clock").readyAt)
    }

    /** Scenario E: 15 minutes used, 45-minute cooldown, reopened after 5: blocked, restarted, no new prompt. */
    @Test
    fun intentCooldownCantBeBypassedAndRestarts() {
        val start = 0L
        val used = IntentUse(15 * min, usedMs = 15 * min, runningSince = null, expiry = IntentExpiry.Cooldown, cooldownMinutes = 45, startedAt = start)
        var rt = Runtime(intentUses = mapOf("insta" to used)).settledIntents(20 * min)
        assertTrue(rt.intentUses.isEmpty())
        assertEquals(65 * min, rt.intentCooldowns.getValue("insta"))
        val reopen = 25 * min
        val d = decide("insta", reopen, state(rt)) as Decision.Shield
        assertEquals(ShieldReason.IntentCooldown, d.reason)
        rt = rt.attempted("insta", d, reopen)
        assertEquals(reopen + 45 * min, rt.intentCooldowns.getValue("insta"))
        // Not an Intent prompt, and no grant opens it.
        val withGrant = rt.copy(access = mapOf("insta" to Grant(reopen + 60 * min, Mode.Cooldown)))
        assertEquals(ShieldReason.IntentCooldown, (decide("insta", reopen + min, state(withGrant)) as Decision.Shield).reason)
    }

    /** A spent cooldown session settles before anything can drop it, e.g. a fresh-each-opening exit. */
    @Test
    fun spentSessionsSettleIdempotently() {
        val used = IntentUse(min, usedMs = min, expiry = IntentExpiry.Cooldown, cooldownMinutes = 5, freshEachOpen = true)
        val once = Runtime(intentUses = mapOf("insta" to used)).settledIntents(2 * min)
        assertEquals(once, once.settledIntents(3 * min))
        val reminder = Runtime(intentUses = mapOf("insta" to used.copy(expiry = IntentExpiry.Reminder))).settledIntents(2 * min)
        assertFalse("insta" in reminder.intentCooldowns)
    }

    /** Attempts on other reasons change nothing: an attempt can never open anything. */
    @Test
    fun otherReasonsAreUntouched() {
        val rt = Runtime(intentCooldowns = mapOf("x" to 5L))
        assertEquals(rt, rt.attempted("x", Decision.Shield("x", ShieldReason.Flexible), 1))
    }

    /** The founder's Intent example: 15 minutes, a 1-minute cooldown, then Instagram again too soon. */
    @Test
    fun reopeningAnIntentAppTooSoonAfterItsCooldownIsARelapse() {
        val t0 = 1_000_000L
        val run = ConfigRun("run", "c", "Evening", intent = IntentPreset("i", "Insta", listOf("insta")), start = 0, end = Long.MAX_VALUE)
        val used = Runtime(runs = listOf(run), intentUses = mapOf("insta" to IntentUse(15 * min, 15 * min, null, IntentExpiry.Cooldown, 1, startedAt = t0)))
        val settled = used.settledIntents(t0)
        val free = t0 + min
        // Back 5 minutes after the cooldown ended: within the gap.
        assertEquals(1, settled.relapsed("insta", DeviceTime(free + 5 * min, 0, -1)).runs.single().intentRelapses)
        // Back after the full gap: a new, honest decision.
        assertEquals(0, settled.relapsed("insta", DeviceTime(free + IntentQuietMs, 0, -1)).runs.single().intentRelapses)
    }

    /** When its session ends, an Intent app loses its timer, cooldown and pause; another session's stay. */
    @Test
    fun endingASessionDropsItsIntentState() {
        val rt = Runtime(
            intentUses = mapOf("insta" to IntentUse(min), "clock" to IntentUse(min)),
            intentCooldowns = mapOf("insta" to 5L, "clock" to 6L),
            intentCooldownLengths = mapOf("insta" to 5L, "clock" to 6L),
            reflections = mapOf("insta" to 1L, "clock" to 2L),
        )
        val ended = rt.intentsEnded(listOf("insta"), still = emptySet())
        assertEquals(setOf("clock"), ended.intentUses.keys)
        assertEquals(setOf("clock"), ended.intentCooldowns.keys)
        assertEquals(setOf("clock"), ended.intentCooldownLengths.keys)
        assertEquals(setOf("clock"), ended.reflections.keys)
        // An app another running session still asks about keeps everything.
        assertEquals(rt, rt.intentsEnded(listOf("insta"), still = setOf("insta", "clock")))
        assertEquals(rt, rt.intentsEnded(emptyList(), still = setOf("clock")))
    }

    @Test
    fun aGrantCountsOnTheRunsShieldingThatApp() {
        val run = ConfigRun("run", "c", "Work", shield = ShieldPreset("s", "Social", listOf("clock"), Mode.Friction), start = 0, end = Long.MAX_VALUE)
        val rt = Runtime(runs = listOf(run)).granted("clock", DeviceTime(10, 0, -1)).granted("other", DeviceTime(10, 0, -1))
        assertEquals(1, rt.runs.single().accessGrants)
    }
}
