package app.aurora.protect

import app.aurora.plan.Plan
import app.aurora.plan.ProtectedItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

/** Precedence, modes, schedules, sessions and the snapshot, with fixed times and zones only. */
class RulesTest {
    private val zone = ZoneId.of("Asia/Kolkata")
    private fun at(text: String) = LocalDateTime.parse(text).atZone(zone).toInstant().toEpochMilli()
    private val min = 60_000L

    private val base = PolicyState(
        protectedPackages = setOf("com.social", "com.video", "web:reddit.com"),
        exempt = setOf("app.aurora"),
        modes = mapOf("com.social" to Mode.Flexible, "com.video" to Mode.RulesOnly, "web:reddit.com" to Mode.Block),
    )

    @Test
    fun modesDecideOutsideAnyRule() {
        assertEquals(Decision.Shield("com.social", ShieldReason.Choice), decide("com.social", 0, base))
        assertEquals(Decision.Allow, decide("com.video", 0, base))
        assertEquals(Decision.Shield("web:reddit.com", ShieldReason.Blocked), decide("web:reddit.com", 0, base))
        for (mode in listOf(Mode.Friction, Mode.Cooldown)) {
            assertEquals(ShieldReason.Choice, (decide("com.social", 0, base.copy(modes = mapOf("com.social" to mode))) as Decision.Shield).reason)
        }
    }

    /** The documented precedence: Lockdown > schedule > Focus > limit > grant > cooldown > mode. */
    @Test
    fun strongerRestrictionsWinAndGrantsNeverLiftThem() {
        val granted = base.copy(grants = mapOf("com.social" to 10 * min))
        assertEquals(Decision.TemporarilyAllowed(10 * min), decide("com.social", 0, granted))

        val focus = TimedBlock(ShieldReason.Focus, setOf("com.social"), 0, 5 * min)
        val schedule = TimedBlock(ShieldReason.Schedule, setOf("com.social"), 0, 6 * min)
        val lockdown = TimedBlock(ShieldReason.Lockdown, setOf("com.social"), 0, 7 * min)
        assertEquals(Decision.Shield("com.social", ShieldReason.Focus, 5 * min), decide("com.social", 1, granted.copy(blocks = listOf(focus))))
        assertEquals(Decision.Shield("com.social", ShieldReason.Schedule, 6 * min), decide("com.social", 1, granted.copy(blocks = listOf(focus, schedule))))
        assertEquals(
            Decision.Shield("com.social", ShieldReason.Lockdown, 7 * min),
            decide("com.social", 1, granted.copy(blocks = listOf(focus, schedule, lockdown))),
        )
        // RulesOnly items are covered by rules too.
        assertEquals(ShieldReason.Focus, (decide("com.video", 1, base.copy(blocks = listOf(focus.copy(keys = setOf("com.video"))))) as Decision.Shield).reason)
        // A block over other apps, or one that hasn't started or has ended, doesn't apply.
        assertEquals(Decision.TemporarilyAllowed(10 * min), decide("com.social", 1, granted.copy(blocks = listOf(focus.copy(keys = setOf("com.x"))))))
        assertEquals(Decision.TemporarilyAllowed(10 * min), decide("com.social", 5 * min, granted.copy(blocks = listOf(focus))))
    }

    @Test
    fun aSpentBudgetBeatsAGrantUntilMidnight() {
        val limited = base.copy(limits = mapOf("com.video" to 20 * min), usedToday = mapOf("com.video" to 20 * min), midnight = 99 * min)
        assertEquals(Decision.Shield("com.video", ShieldReason.Limit, 99 * min), decide("com.video", 0, limited.copy(grants = mapOf("com.video" to 50 * min))))
        assertEquals(Decision.Allow, decide("com.video", 0, limited.copy(usedToday = mapOf("com.video" to 19 * min))))
    }

    @Test
    fun cooldownFollowsTheGrantAndThenEnds() {
        val rule = AppRule(mode = Mode.Cooldown, cooldownMinutes = 30)
        val runtime = Runtime().granted("com.social", 10, rule, now = 0)
        val state = base.copy(modes = mapOf("com.social" to Mode.Cooldown), grants = runtime.grants, cooldowns = runtime.cooldowns)
        assertEquals(Decision.TemporarilyAllowed(10 * min), decide("com.social", 5 * min, state))
        assertEquals(Decision.Shield("com.social", ShieldReason.Cooldown, 40 * min), decide("com.social", 10 * min, state))
        assertEquals(Decision.Shield("com.social", ShieldReason.Choice), decide("com.social", 40 * min, state))
        // Flexible and Friction grants leave no cooldown.
        assertTrue(Runtime().granted("com.social", 10, AppRule(mode = Mode.Friction), 0).cooldowns.isEmpty())
    }

    @Test
    fun aPauseContinuesInsteadOfRestartingOrSkipping() {
        val (first, start) = Runtime().waitFor("com.social", now = 1_000)
        assertEquals(1_000L, start)
        // Leave and come back 4 s later: the same pause.
        assertEquals(1_000L, first.waitFor("com.social", now = 5_000).second)
        // Long after, a fresh pause.
        assertEquals(1_000L + Runtime.WaitReuseMs, first.waitFor("com.social", now = 1_000 + Runtime.WaitReuseMs).second)
        // Continuing clears it.
        assertFalse("com.social" in first.granted("com.social", 5, AppRule(), 6_000).waits)
    }

    @Test
    fun nextChangeFindsTheEarliestBoundary() {
        val state = base.copy(
            grants = mapOf("com.social" to 10 * min),
            blocks = listOf(TimedBlock(ShieldReason.Schedule, setOf("com.social"), 8 * min, 20 * min)),
        )
        assertEquals(8 * min, nextChange("com.social", 0, state))
        assertEquals(10 * min, nextChange("com.social", 9 * min, state))
        assertEquals(20 * min, nextChange("com.social", 15 * min, state))
        assertNull(nextChange("com.mail", 0, state))
        val limited = base.copy(limits = mapOf("com.video" to 20 * min), usedToday = mapOf("com.video" to 15 * min), midnight = 60 * min)
        assertEquals(5 * min, nextChange("com.video", 0, limited))
    }

    @Test
    fun weekdayEveningScheduleCoversItsWindowOnly() {
        // Monday to Friday, 18:00 to 21:00. 2026-11-02 is a Monday.
        val s = Schedule("s", "Study", setOf(1, 2, 3, 4, 5), 18 * 60, 21 * 60)
        assertEquals(at("2026-11-02T18:00") to at("2026-11-02T21:00"), s.windowAt(at("2026-11-02T19:30"), zone))
        assertNull(s.windowAt(at("2026-11-02T21:00"), zone))
        assertNull(s.windowAt(at("2026-11-07T19:00"), zone)) // Saturday
        assertEquals(at("2026-11-02T18:00"), s.nextStart(at("2026-11-02T09:00"), zone))
        assertEquals(at("2026-11-09T18:00"), s.nextStart(at("2026-11-06T22:00"), zone)) // Friday night: next Monday
    }

    @Test
    fun overnightScheduleBelongsToTheDayItStarts() {
        // Friday only, 23:00 to 07:00.
        val s = Schedule("n", "Night", setOf(5), 23 * 60, 7 * 60)
        assertEquals(at("2026-11-06T23:00") to at("2026-11-07T07:00"), s.windowAt(at("2026-11-07T03:00"), zone))
        assertNull(s.windowAt(at("2026-11-08T03:00"), zone)) // Sunday 03:00: Saturday has no window
        // Same start and end: the whole day.
        val allDay = Schedule("d", "Day", setOf(1), 9 * 60, 9 * 60)
        assertEquals(24 * 60 * min, allDay.windowAt(at("2026-11-02T12:00"), zone)!!.let { it.second - it.first })
    }

    @Test
    fun windowsKeepTheirClockTimesAcrossDaylightSaving() {
        // Europe/London leaves summer time on Sunday 2026-10-25 at 02:00.
        val london = ZoneId.of("Europe/London")
        val s = Schedule("n", "Night", setOf(6), 22 * 60, 7 * 60) // Saturday 22:00 to Sunday 07:00
        val now = LocalDateTime.parse("2026-10-25T05:00").atZone(london).toInstant().toEpochMilli()
        val (start, end) = s.windowAt(now, london)!!
        assertEquals(LocalDateTime.parse("2026-10-25T07:00").atZone(london).toInstant().toEpochMilli(), end)
        assertEquals(10 * 60 * min, end - start) // 9 clock hours, 10 real hours
    }

    @Test
    fun sessionsCountOnTheMonotonicClockUntilARestart() {
        val start = DeviceTime(wall = 1_000_000, elapsed = 50_000, boot = 7)
        val session = Session.start(start, minutes = 30)
        // Wall clock moved forward a day, 10 real minutes later: still 20 minutes left.
        assertEquals(20 * min, session.remaining(DeviceTime(1_000_000 + 86_400_000, 50_000 + 10 * min, 7)))
        // Clock set back: no extra time either.
        assertEquals(20 * min, session.remaining(DeviceTime(0, 50_000 + 10 * min, 7)))
        // After a restart only the wall clock is left.
        assertEquals(25 * min, session.remaining(DeviceTime(1_000_000 + 5 * min, 1_000, 8)))
        assertTrue(session.isOver(DeviceTime(1_000_000 + 31 * min, 1_000, 8)))
    }

    @Test
    fun snapshotEnforcesOnlyWhatThePlanAllows() {
        val apps = (1..7).map { ProtectedItem.App.of("com.app$it")!! }
        val list = ProtectedList(apps)
        val t = DeviceTime(at("2026-11-02T19:00"), 0, 1)
        val rules = Rules(
            apps = apps.associate { it.key to AppRule(Mode.Flexible) },
            groups = listOf(Group("g", "Social", listOf("com.app1", "com.app2", "com.app7"))),
            schedules = listOf(
                Schedule("a", "Evening", setOf(1), 18 * 60, 21 * 60, groupId = "g"),
                Schedule("b", "Second", setOf(1), 0, 0),
            ),
            limits = (1..4).map { DailyLimit("com.app$it", 10) },
        )
        val free = policyState(list, rules, Runtime(), Plan.Free, emptySet(), emptyMap(), t, zone)!!
        assertEquals((1..5).map { "com.app$it" }.toSet(), free.protectedPackages)
        // One schedule on Free; it covers only the group's active items (app7 is paused).
        assertEquals(setOf(setOf("com.app1", "com.app2")), free.blocks.filter { it.start <= t.wall }.map { it.keys }.toSet())
        assertEquals(3, free.limits.size)
        val plus = policyState(list, rules, Runtime(), Plan.Plus, emptySet(), emptyMap(), t, zone)!!
        assertEquals(7, plus.protectedPackages.size)
        assertEquals(4, plus.limits.size)
        assertTrue(plus.blocks.any { it.keys.size == 7 })
        assertNull(policyState(ProtectedList(readable = false), rules, Runtime(), Plan.Free, emptySet(), emptyMap(), t, zone))
    }

    @Test
    fun lockdownAndFocusInTheSnapshot() {
        val list = ProtectedList(listOf("com.a", "com.b").map { ProtectedItem.App.of(it)!! })
        val t = DeviceTime(1_000_000, 10_000, 3)
        val runtime = Runtime(lockdown = Session.start(t, 30), focus = Session.start(t, 25, groupId = "g"))
        val rules = Rules(groups = listOf(Group("g", "One", listOf("com.b"))))
        val later = t.copy(wall = t.wall + 10 * min, elapsed = t.elapsed + 10 * min)
        val state = policyState(list, rules, runtime, Plan.Free, emptySet(), emptyMap(), later, zone)!!
        assertEquals(Decision.Shield("com.a", ShieldReason.Lockdown, t.wall + 30 * min), decide("com.a", later.wall, state))
        val noLockdown = policyState(list, rules, runtime.copy(lockdown = null), Plan.Free, emptySet(), emptyMap(), later, zone)!!
        assertEquals(Decision.Shield("com.b", ShieldReason.Focus, t.wall + 25 * min), decide("com.b", later.wall, noLockdown))
        assertEquals(ShieldReason.Choice, (decide("com.a", later.wall, noLockdown.copy(modes = mapOf("com.a" to Mode.Flexible))) as Decision.Shield).reason)
    }

    @Test
    fun intentAppsAreLimitedOnFree() {
        val rules = Rules(apps = (1..5).associate { "com.app$it" to AppRule(intent = IntentNote("Because")) })
        assertEquals(setOf("com.app1", "com.app2", "com.app3"), rules.activeIntents(Plan.Free))
        assertEquals(5, rules.activeIntents(Plan.Plus).size)
    }

    @Test
    fun keysRoundTrip() {
        assertEquals("web:reddit.com", ProtectedItem.Website.of("www.Reddit.com")!!.key)
        assertEquals(ProtectedItem.Website.of("reddit.com"), itemOf("web:reddit.com"))
        assertEquals(ProtectedItem.App.of("com.social"), itemOf("com.social"))
    }
}
