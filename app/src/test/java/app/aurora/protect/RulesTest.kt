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

    private val apps = (1..7).map { ProtectedItem.App.of("com.app$it")!! }
    private val list = ProtectedList(apps)

    @Test
    fun snapshotEnforcesOnlyWhatThePlanAllows() {
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
        // Schedules have no limit of their own on Free; each covers only active items (app7 is paused).
        assertEquals(setOf(setOf("com.app1", "com.app2"), (1..5).map { "com.app$it" }.toSet()), free.restrictions.filter { it.start <= t.wall }.map { it.keys }.toSet())
        assertEquals(4, free.limits.size)
        val plus = policyState(list, rules, Runtime(), Plan.Plus, emptySet(), emptyMap(), t, zone)!!
        assertEquals(4, plus.limits.size)
        assertTrue(plus.restrictions.any { it.keys.size == 7 })
        assertNull(policyState(ProtectedList(readable = false), rules, Runtime(), Plan.Free, emptySet(), emptyMap(), t, zone))
    }

    @Test
    fun aScheduleCarriesItsOwnModeAppsAndGroups() {
        val t = DeviceTime(at("2026-11-02T10:00"), 0, 1)
        val rules = Rules(
            groups = listOf(Group("g", "Games", listOf("com.app5"))),
            schedules = listOf(Schedule("s", "Study hours", setOf(1, 2, 3, 4, 5), 9 * 60, 17 * 60, keys = listOf("com.app1", "com.app2"), groupIds = listOf("g"), mode = Mode.Friction)),
        )
        val state = policyState(list, rules, Runtime(), Plan.Free, emptySet(), emptyMap(), t, zone)!!
        val now = state.restrictions.single { it.start <= t.wall }
        assertEquals(setOf("com.app1", "com.app2", "com.app5"), now.keys)
        assertEquals(ShieldReason.Friction, now.reason)
        assertEquals("Study hours", now.label)
        assertEquals(ShieldReason.Friction, (decide("com.app1", t.wall, state) as Decision.Shield).reason)
        assertEquals(Decision.Allow, decide("com.app3", t.wall, state))
    }

    @Test
    fun overlappingSchedulesTheStrongestWins() {
        val t = DeviceTime(at("2026-11-02T10:00"), 0, 1)
        val rules = Rules(schedules = listOf(
            Schedule("a", "Work", setOf(1), 9 * 60, 17 * 60, keys = listOf("com.app1"), mode = Mode.Friction),
            Schedule("b", "Deep", setOf(1), 9 * 60, 12 * 60, keys = listOf("com.app1"), mode = Mode.AlwaysBlock),
        ))
        val state = policyState(list, rules, Runtime(), Plan.Plus, emptySet(), emptyMap(), t, zone)!!
        assertEquals(ShieldReason.AlwaysBlock, (decide("com.app1", t.wall, state) as Decision.Shield).reason)
        assertEquals(ShieldReason.Friction, (decide("com.app1", at("2026-11-02T12:00"), state) as Decision.Shield).reason)
    }

    @Test
    fun aFlexibleWindowCanBeTurnedOffButACommittedOneCannot() {
        val t = DeviceTime(at("2026-11-02T10:00"), 0, 1)
        val flexible = Schedule("f", "Work", setOf(1), 9 * 60, 17 * 60, keys = listOf("com.app1"))
        val skipped = Runtime(skipped = mapOf("f" to at("2026-11-02T09:00")))
        assertEquals(Decision.Allow, decide("com.app1", t.wall, policyState(list, Rules(schedules = listOf(flexible)), skipped, Plan.Plus, emptySet(), emptyMap(), t, zone)))
        val strict = flexible.copy(mode = Mode.AlwaysBlock)
        assertEquals(ShieldReason.AlwaysBlock, (decide("com.app1", t.wall, policyState(list, Rules(schedules = listOf(strict)), skipped, Plan.Plus, emptySet(), emptyMap(), t, zone)) as Decision.Shield).reason)
    }

    @Test
    fun onFreeAScheduledAlwaysBlockIsCommittedOnlyFromTheAllowance() {
        val t = DeviceTime(at("2026-11-02T10:00"), 0, 1)
        val strict = Schedule("s", "Work", setOf(1), 9 * 60, 17 * 60, keys = listOf("com.app1"), mode = Mode.AlwaysBlock)
        // Without a committed session the window is still a full block, but one that can be turned off.
        val state = policyState(list, Rules(schedules = listOf(strict)), Runtime(), Plan.Free, emptySet(), emptyMap(), t, zone)!!
        assertEquals(ShieldReason.Flexible, (decide("com.app1", t.wall, state) as Decision.Shield).reason)
    }

    @Test
    fun aCommittedSessionKeepsItsAppsThroughEditsRemovalAndDowngrade() {
        val t = DeviceTime(at("2026-11-02T10:00"), 5_000, 1)
        val session = ProtectionSession("x", setOf("com.app7"), Mode.AlwaysBlock, Session.start(t, 30))
        // com.app7 is paused on Free and removed from every rule and group: the commitment still holds.
        val empty = ProtectedList(apps.take(2))
        val state = policyState(empty, Rules(), Runtime(sessions = listOf(session)), Plan.Free, emptySet(), emptyMap(), t, zone)!!
        assertEquals(ShieldReason.AlwaysBlock, (decide("com.app7", t.wall + 29 * min, state) as Decision.Shield).reason)
        assertFalse(session.cancellable)
        assertTrue(session.copy(mode = Mode.Flexible).cancellable)
    }

    @Test
    fun aCommittedSessionSurvivesARestartOnTheWallClock() {
        val t = DeviceTime(at("2026-11-02T10:00"), 5_000, 1)
        val session = ProtectionSession("x", setOf("com.app1"), Mode.AlwaysBlock, Session.start(t, 30))
        // After a reboot: new boot count, monotonic clock restarted.
        val after = DeviceTime(t.wall + 10 * min, 2_000, 2)
        val state = policyState(list, Rules(), Runtime(sessions = listOf(session)), Plan.Plus, emptySet(), emptyMap(), after, zone)!!
        assertEquals(Decision.Shield("com.app1", ShieldReason.AlwaysBlock, t.wall + 30 * min, state.restrictions.single()), decide("com.app1", after.wall, state))
        assertEquals(Decision.Allow, decide("com.app1", t.wall + 30 * min, policyState(list, Rules(), Runtime(sessions = listOf(session)), Plan.Plus, emptySet(), emptyMap(), DeviceTime(t.wall + 30 * min, 0, 2), zone)))
    }

    @Test
    fun aPhase3LockdownBecomesAnAlwaysBlockSession() {
        val t = DeviceTime(1_000_000, 10_000, 3)
        val small = ProtectedList(listOf("com.a", "com.b").map { ProtectedItem.App.of(it)!! })
        val runtime = Runtime(lockdown = Session.start(t, 30))
        val later = t.copy(wall = t.wall + 10 * min, elapsed = t.elapsed + 10 * min)
        // Before migration it already enforces as Always Block.
        val before = policyState(small, Rules(), runtime, Plan.Free, emptySet(), emptyMap(), later, zone)!!
        assertEquals(ShieldReason.AlwaysBlock, (decide("com.a", later.wall, before) as Decision.Shield).reason)
        val migrated = runtime.migrated(Rules(), setOf("com.a", "com.b"))
        assertNull(migrated.lockdown)
        val s = migrated.sessions.single()
        assertEquals(Mode.AlwaysBlock, s.mode)
        assertEquals(setOf("com.a", "com.b"), s.keys)
        assertEquals(20 * min, s.time.remaining(later))
        assertFalse(s.cancellable)
        // Migrating twice changes nothing.
        assertEquals(migrated, migrated.migrated(Rules(), setOf("com.a")))
    }

    @Test
    fun focusInTheSnapshot() {
        val small = ProtectedList(listOf("com.a", "com.b").map { ProtectedItem.App.of(it)!! })
        val t = DeviceTime(1_000_000, 10_000, 3)
        val runtime = Runtime(focus = Session.start(t, 25, groupId = "g"))
        val rules = Rules(groups = listOf(Group("g", "One", listOf("com.b"))))
        val later = t.copy(wall = t.wall + 10 * min, elapsed = t.elapsed + 10 * min)
        val state = policyState(small, rules, runtime, Plan.Free, emptySet(), emptyMap(), later, zone)!!
        assertEquals(ShieldReason.Focus, (decide("com.b", later.wall, state) as Decision.Shield).reason)
        assertEquals(Decision.Allow, decide("com.a", later.wall, state))
    }

    @Test
    fun intentAppsAreLimitedOnFreeAndKeptOnDowngrade() {
        val rules = Rules(apps = (1..5).associate { "com.app$it" to AppRule(intent = IntentConfig("Because")) })
        assertEquals(setOf("com.app1", "com.app2", "com.app3"), rules.activeIntents(Plan.Free))
        assertEquals(5, rules.activeIntents(Plan.Plus).size)
        // Nothing is deleted: the other two stay saved and come back on Plus.
        assertEquals(5, rules.apps.values.count { it.intent?.enabled == true })
    }

    @Test
    fun reflectionContinuesInsteadOfRestarting() {
        val (r1, start) = Runtime().reflectionFor("k", 1_000)
        val (r2, again) = r1.reflectionFor("k", 1_000 + 30_000)
        assertEquals(start, again)
        assertEquals(r1, r2)
        val (_, fresh) = r2.reflectionFor("k", 1_000 + Runtime.ReflectionReuseMs + 1)
        assertEquals(1_000 + Runtime.ReflectionReuseMs + 1, fresh)
    }

    @Test
    fun keysRoundTrip() {
        assertEquals("web:reddit.com", ProtectedItem.Website.of("www.Reddit.com")!!.key)
        assertEquals(ProtectedItem.Website.of("reddit.com"), itemOf("web:reddit.com"))
        assertEquals(ProtectedItem.App.of("com.social"), itemOf("com.social"))
    }
}
