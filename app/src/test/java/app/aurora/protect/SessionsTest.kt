package app.aurora.protect

import app.aurora.plan.Plan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

/** Sessions with many Shields and Intents: one strictness, no app twice, one session at a time, and unlocks. */
class SessionsTest {
    private val zone = ZoneId.of("Europe/London")
    private val min = 60_000L
    private fun at(d: Int, h: Int, m: Int = 0) = LocalDateTime.of(2026, 11, d, h, m).atZone(zone).toInstant().toEpochMilli()
    private fun t(wall: Long) = DeviceTime(wall, wall, -1)

    private val insta = "com.instagram.android"
    private val yt = "com.google.android.youtube"
    private val reddit = "web:reddit.com"
    private val social = ShieldPreset("s", "Social", listOf(insta), Mode.Flexible)
    private val sites = ShieldPreset("w", "Sites", listOf(reddit), Mode.AlwaysBlock)
    private val video = ShieldPreset("v", "Video", listOf(yt), Mode.Flexible)
    private val ytIntent = IntentPreset("i", "YouTube", listOf(yt), IntentConfig(reflectionSeconds = 30))
    private val chat = IntentPreset("c", "Chat", listOf("com.example.chat"), IntentConfig(reflectionSeconds = 5))
    private val brk = SessionUnlock("u1", "Break", minutes = 10, times = 2)
    private val work = SessionUnlock("u2", "Instagram for work", partIds = listOf("s"), minutes = 30, times = 1, focusRuns = true)
    private val study = UsageConfig(
        "study", "Study", shieldIds = listOf("s", "w"), intentIds = listOf("i", "c"), mode = Mode.Cooldown, cooldownWaitMinutes = 5,
        unlocks = listOf(brk, work),
    )
    private val rules = Rules(version = 1, shields = listOf(social, sites, video), intentPresets = listOf(ytIntent, chat), configs = listOf(study))
    private val start = at(10, 9)
    private val a = Activation("a", "study", Timing.Later(start, 180), createdAt = start - min)
    private fun state(r: Rules, rt: Runtime, wall: Long) = policyState(ProtectedList(), r, rt, Plan.Plus, emptySet(), emptyMap(), t(wall), zone)!!

    @Test
    fun allItsShieldsAndIntentsRunAsOneSessionAtItsStrictness() {
        val run = rules.runsFor(a, start to start + 180 * min, Plan.Plus, start, zone).single()
        assertEquals(listOf(insta, reddit), run.shield?.keys)
        assertEquals(Mode.Cooldown, run.sessionMode)
        assertEquals(5, run.shield?.cooldownWaitMinutes)
        assertEquals(listOf(yt, "com.example.chat"), run.intent?.keys)
        assertEquals(listOf(brk, work), run.unlocks)
        // Each Intent keeps its own settings.
        val r = rules.copy(activations = listOf(a))
        val s = state(r, Runtime(), start + min)
        assertEquals(30, s.intents[yt]?.reflectionSeconds)
        assertEquals(5, s.intents["com.example.chat"]?.reflectionSeconds)
        assertEquals(ShieldReason.Cooldown, (decide(reddit, start + min, s) as Decision.Shield).reason)
        // An older session with one Shield and one Intent reads as before.
        val old = UsageConfig("o", "Old", shieldId = "v")
        assertEquals(listOf("v"), old.allShieldIds)
        assertEquals(video, rules.shieldOf(old))
    }

    @Test
    fun noAppMayBeInTwoOfItsShieldsAndIntents() {
        assertEquals(setOf(yt), rules.presetOverlap(listOf("s", "v"), listOf("i")))
        assertEquals(emptySet<String>(), rules.presetOverlap(listOf("s", "w"), listOf("i", "c")))
        val clash = UsageConfig("x", "Clash", shieldIds = listOf("v"), intentIds = listOf("i"))
        assertEquals(ConflictKind.Inside, rules.sessionClash(clash)?.kind)
        val twoShields = UsageConfig("y", "Twice", shieldIds = listOf("v", "v2"))
        val r = rules.copy(shields = rules.shields + video.copy(id = "v2", name = "Video 2"))
        assertEquals(ConflictKind.Plan, r.sessionClash(twoShields)?.kind)
        assertEquals(setOf(yt), r.sessionClash(twoShields)?.keys)
        assertNull(rules.sessionClash(study))
    }

    @Test
    fun oneSessionRunsAtATime() {
        val r = rules.copy(activations = listOf(a))
        val other = UsageConfig("o", "Other", shieldIds = listOf("v"))
        // Any other session at that time is refused, whatever its apps.
        val c = r.sessionConflict(other, Timing.Later(start + 100 * min, 30), Runtime(), t(start - min), zone)
        assertEquals(ConflictKind.Sessions, c?.kind)
        assertEquals("Study", c?.name)
        assertNull(r.sessionConflict(other, Timing.Later(start + 180 * min, 30), Runtime(), t(start - min), zone))
        // A session running now holds now.
        val running = ConfigRun("now:1", "o2", "Running", shield = video, start = start - 10 * min, end = start + 20 * min)
        assertEquals(ConflictKind.Sessions, rules.sessionConflict(other, Timing.Now(30), Runtime(runs = listOf(running)), t(start), zone)?.kind)
        // Once turned off, the time is free again.
        val weekdays = Activation("w", "study", Timing.Recurring((1..5).toSet(), 9 * 60, 120), createdAt = at(1, 0))
        val r2 = rules.copy(activations = listOf(weekdays))
        assertNotNull(r2.sessionConflict(other, Timing.Now(60), Runtime(), t(at(11, 9, 30)), zone))
        assertNull(r2.sessionConflict(other, Timing.Now(60), Runtime(closed = mapOf("rec:w:${at(11, 9)}" to at(11, 11))), t(at(11, 9, 30)), zone))
    }

    @Test
    fun anUnlockOpensWhatItNamesForItsTimeThenItLocksAgain() {
        val r = rules.copy(activations = listOf(a))
        val runId = "later:a"
        val now = start + 60 * min
        // A break on everything, ten minutes from 10:00.
        val rt = Runtime(unlocks = mapOf(runId to listOf(UnlockUse("u1", now, now + 10 * min))))
        assertEquals(Decision.Allow, decide(reddit, now + min, state(r, rt, now + min)))
        assertEquals(Decision.Allow, decide(insta, now + min, state(r, rt, now + min)))
        assertNull(state(r, rt, now + min).intents[yt])
        // The Shield knows when it comes back, so an app left open is blocked the moment the break ends.
        assertEquals(now + 10 * min, nextChange(reddit, now + min, state(r, rt, now + min)))
        assertNotNull(decide(reddit, now + 10 * min, state(r, rt, now + 10 * min)) as? Decision.Shield)
        // Instagram for work opens only the Social Shield's apps.
        val keys = r.unlockKeys(study, work.partIds)
        assertEquals(setOf(insta), keys)
        val forWork = Runtime(unlocks = mapOf(runId to listOf(UnlockUse("u2", now, now + 30 * min, keys, focusRuns = true))))
        assertEquals(Decision.Allow, decide(insta, now + min, state(r, forWork, now + min)))
        assertNotNull(decide(reddit, now + min, state(r, forWork, now + min)) as? Decision.Shield)
        assertNotNull(state(r, forWork, now + min).intents[yt])
    }

    @Test
    fun unlocksAreCountedAndOnlyTheOnesThatStopTheTimerLeaveFocusOut() {
        val run = rules.runsFor(a, start to start + 180 * min, Plan.Plus, start, zone).single()
        val now = start + 120 * min
        val used = listOf(UnlockUse("u1", start + 30 * min, start + 40 * min), UnlockUse("u2", start + 60 * min, start + 90 * min, setOf(insta), focusRuns = true))
        assertEquals(1, run.left(brk, used))
        assertEquals(0, run.left(work, used))
        assertNull(run.unlockedAt(used, now))
        assertEquals(10 * min, run.stoppedMs(used, now))
    }
}
