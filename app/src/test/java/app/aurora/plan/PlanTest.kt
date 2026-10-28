package app.aurora.plan

import app.aurora.plan.Allowance.DailyLimitRules
import app.aurora.plan.Allowance.FocusProfiles
import app.aurora.plan.Allowance.IntentApps
import app.aurora.plan.Allowance.LockdownMinutesPerDay
import app.aurora.plan.Allowance.ProtectedItems
import app.aurora.plan.Allowance.Schedules
import app.aurora.plan.Plan.Free
import app.aurora.plan.Plan.Plus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class PlanTest {

    private fun app(pkg: String) = ProtectedItem.App.of(pkg)!!
    private fun site(host: String) = ProtectedItem.Website.of(host)!!

    // Allowances

    @Test
    fun freeLimitsMatchTheBusinessModel() {
        assertEquals(
            mapOf(ProtectedItems to 5, IntentApps to 3, DailyLimitRules to 3, Schedules to 1, FocusProfiles to 1, LockdownMinutesPerDay to 120),
            Allowance.entries.associateWith { limit(Free, it) },
        )
    }

    @Test
    fun plusIsUnlimitedForEveryAllowance() {
        for (allowance in Allowance.entries) {
            assertNull(allowance.name, limit(Plus, allowance))
            assertTrue(allowance.name, canAdd(Plus, allowance, 10_000))
        }
    }

    @Test
    fun freeCanAddBelowEachLimitButNotAtOrAbove() {
        for ((allowance, max) in FreeLimits) {
            assertTrue(allowance.name, canAdd(Free, allowance, max - 1))
            assertFalse(allowance.name, canAdd(Free, allowance, max))
            assertFalse(allowance.name, canAdd(Free, allowance, max + 1))
        }
    }

    @Test
    fun ruleLimits() {
        assertTrue(canAdd(Free, IntentApps, 2))
        assertFalse(canAdd(Free, IntentApps, 3))
        assertTrue(canAdd(Free, DailyLimitRules, 2))
        assertFalse(canAdd(Free, DailyLimitRules, 3))
        assertTrue(canAdd(Free, Schedules, 0))
        assertFalse(canAdd(Free, Schedules, 1))
        // The default Focus profile is the one Free allows.
        assertFalse(canAdd(Free, FocusProfiles, 1))
        assertTrue(canAdd(Plus, FocusProfiles, 1))
    }

    @Test
    fun featuresArePlusOnly() {
        for (feature in Feature.entries) {
            assertFalse(has(Free, feature))
            assertTrue(has(Plus, feature))
        }
    }

    // Protected items

    @Test
    fun freeProtectsUpToFiveDistinctItems() {
        val four = listOf(app("com.a.one"), app("com.a.two"), app("com.a.three"), site("example.com"))
        assertTrue(canProtect(Free, four, app("com.a.five")))
        val five = four + app("com.a.five")
        assertFalse(canProtect(Free, five, app("com.a.six")))
        assertFalse(canProtect(Free, five, site("news.example")))
    }

    @Test
    fun appsAndWebsitesShareOnePool() {
        val mixed = listOf(app("com.a.one"), app("com.a.two"), site("one.example"), site("two.example"), site("three.example"))
        assertFalse(canProtect(Free, mixed, app("com.a.three")))
        assertFalse(canProtect(Free, mixed, site("four.example")))
    }

    @Test
    fun theSameItemInSeveralRulesCountsOnce() {
        // Five distinct items, each referenced by more than one rule.
        val acrossRules = listOf(
            app("com.a.one"), app("com.a.two"), site("example.com"), app("com.a.three"),
            app("com.a.one"), app("com.a.two"), site("EXAMPLE.com"), app("com.a.four"),
        )
        assertFalse(canProtect(Free, acrossRules, app("com.a.five")))
        // An item that is already protected never needs a new slot, even at the limit.
        assertTrue(canProtect(Free, acrossRules + app("com.a.five"), app("com.a.one")))
        val four = acrossRules.dropLast(1)
        assertTrue(canProtect(Free, four, app("com.a.five")))
    }

    @Test
    fun removingAnItemFreesASlotAtOnce() {
        val five = listOf(app("com.a.one"), app("com.a.two"), app("com.a.three"), app("com.a.four"), site("example.com"))
        assertFalse(canProtect(Free, five, app("com.a.six")))
        assertTrue(canProtect(Free, five - site("example.com"), app("com.a.six")))
    }

    @Test
    fun plusProtectsBeyondTheFreeLimit() {
        val twenty = (1..20).map { app("com.a.app$it") }
        assertTrue(canProtect(Plus, twenty, site("example.com")))
    }

    @Test
    fun websitesAreNormalised() {
        val expected = site("example.com")
        for (input in listOf("Example.COM", " example.com ", "example.com.", "www.example.com", "WWW.Example.com")) {
            assertEquals(input, expected, ProtectedItem.Website.of(input))
        }
        assertEquals("xn--bcher-kva.example", site("bücher.example").domain)
        assertEquals("m.example.com", site("m.example.com").domain)
    }

    @Test
    fun invalidItemsAreRejected() {
        for (input in listOf("", "  ", "localhost", "com", "https://example.com", "example.com/path", "example.com:443", "exa mple.com", "a..b", "under_score.com", "x".repeat(64) + ".com")) {
            assertNull(input, ProtectedItem.Website.of(input))
        }
        for (input in listOf("", "chrome", "com.", ".com.a", "1com.a", "com.a b", "com/a")) {
            assertNull(input, ProtectedItem.App.of(input))
        }
        assertEquals("com.Example.App", app(" com.Example.App ").packageName)
    }

    // Downgrade

    @Test
    fun downgradeKeepsTheOldestActiveUntilThePersonChooses() {
        val items = listOf("a", "b", "c", "d", "e", "f", "g") // oldest first
        assertEquals(listOf("a", "b", "c", "d", "e"), keepActive(Free, ProtectedItems, items))
        assertEquals(listOf("a"), keepActive(Free, Schedules, items))
        assertEquals(items, keepActive(Plus, ProtectedItems, items))
    }

    @Test
    fun downgradeHonoursThePersonsChoice() {
        val items = listOf("a", "b", "c", "d", "e", "f", "g")
        assertEquals(listOf("a", "b", "c", "f", "g"), keepActive(Free, ProtectedItems, items, chosen = setOf("g", "f")))
        assertEquals(listOf("b", "d", "e"), keepActive(Free, IntentApps, items, chosen = listOf("b", "d", "e", "f")))
        // Choices that no longer exist are ignored.
        assertEquals(listOf("c"), keepActive(Free, Schedules, listOf("c", "d"), chosen = setOf("zzz")))
        // Repeats count once.
        assertEquals(listOf("a", "b", "c"), keepActive(Free, IntentApps, listOf("a", "a", "b", "c", "d")))
    }

    // Lockdown

    private val india: ZoneId = ZoneId.of("Asia/Kolkata")
    private val day: LocalDate = LocalDate.of(2026, 10, 28)
    private fun at(hhmm: String, date: LocalDate = day, zone: ZoneId = india): Instant =
        java.time.LocalTime.parse(hhmm).atDate(date).atZone(zone).toInstant()

    private fun reserveAll(plan: Plan, vararg starts: Pair<String, Int>): List<LockdownReservation> =
        starts.fold(emptyList()) { ledger, (time, minutes) ->
            ledger + checkNotNull(reserveLockdown(plan, ledger, at(time), minutes, india)) { "$time for $minutes" }
        }

    @Test
    fun freeLockdownsMayTotalExactly120Minutes() {
        val ledger = reserveAll(Free, "09:00" to 60, "13:00" to 45, "18:00" to 15)
        assertEquals(120, lockdownMinutesUsed(ledger, day))
        assertEquals(0, lockdownMinutesLeft(Free, ledger, day))
    }

    @Test
    fun freeLockdownCannotExceedTheBudget() {
        assertNull(reserveLockdown(Free, emptyList(), at("09:00"), 121, india))
        val ledger = reserveAll(Free, "09:00" to 90)
        assertEquals(30, lockdownMinutesLeft(Free, ledger, day))
        assertNull(reserveLockdown(Free, ledger, at("12:00"), 31, india))
        assertNotNull(reserveLockdown(Free, ledger, at("12:00"), 30, india))
        assertNull(reserveLockdown(Free, ledger, at("12:00"), 0, india))
        assertNull(reserveLockdown(Free, ledger, at("12:00"), -5, india))
    }

    @Test
    fun sequentialLockdownsShareOneBudget() {
        val ledger = reserveAll(Free, "08:00" to 30, "10:00" to 30, "12:00" to 30)
        assertEquals(30, lockdownMinutesLeft(Free, ledger, day))
        assertNull(reserveLockdown(Free, ledger, at("15:00"), 45, india))
    }

    @Test
    fun overlappingLockdownsAreChargedOnce() {
        // A second Lockdown inside a running one costs only the time it adds.
        val ledger = reserveAll(Free, "09:00" to 60, "09:30" to 60)
        assertEquals(90, lockdownMinutesUsed(ledger, day))
        // Restarting the same Lockdown costs nothing and doesn't reset the budget.
        val again = ledger + reserveLockdown(Free, ledger, at("09:00"), 60, india)!!
        assertEquals(90, lockdownMinutesUsed(again, day))
        assertEquals(30, lockdownMinutesLeft(Free, again, day))
    }

    @Test
    fun aLockdownAcrossMidnightCountsTowardItsStartingDay() {
        val ledger = reserveAll(Free, "23:30" to 90)
        val next = day.plusDays(1)
        assertEquals(90, lockdownMinutesUsed(ledger, day))
        assertEquals(0, lockdownMinutesUsed(ledger, next))
        assertEquals(120, lockdownMinutesLeft(Free, ledger, next))
        // Just after midnight a new Lockdown starts on the new day. Its first half hour is
        // already covered by last night's Lockdown, so the new day pays only for the rest.
        val after = ledger + reserveLockdown(Free, ledger, at("00:30", next), 120, india)!!
        assertEquals(90, lockdownMinutesUsed(after, day))
        assertEquals(90, lockdownMinutesUsed(after, next))
    }

    @Test
    fun theBudgetFollowsTheDevicesCurrentTimeZone() {
        // 23:00 in India on the 28th is 17:30 in London on the 28th and 08:00 in Tokyo on the 29th.
        val ledger = reserveAll(Free, "23:00" to 100)
        val now = at("23:30")
        val london = now.atZone(ZoneId.of("Europe/London")).toLocalDate()
        val tokyo = now.atZone(ZoneId.of("Asia/Tokyo")).toLocalDate()
        assertEquals(20, lockdownMinutesLeft(Free, ledger, london))
        assertEquals(120, lockdownMinutesLeft(Free, ledger, tokyo))
        // A Lockdown started after the move is charged to the local day where it starts.
        val inTokyo = reserveLockdown(Free, ledger, at("23:45"), 120, ZoneId.of("Asia/Tokyo"))!!
        assertEquals(day.plusDays(1), inTokyo.day)
    }

    @Test
    fun aClockSetBackDoesNotRefundOrDoubleCharge() {
        val ledger = reserveAll(Free, "10:00" to 60)
        // The clock moves back to 09:30 and a Lockdown starts: 09:30 to 11:00 is charged once, as 90 minutes.
        val back = reserveLockdown(Free, ledger, at("09:30"), 60, india)
        assertNotNull(back)
        assertEquals(90, lockdownMinutesUsed(ledger + back!!, day))
        // Moving the clock to tomorrow gives tomorrow's budget, an accepted limitation (BUSINESS_MODEL.md 7).
        assertEquals(120, lockdownMinutesLeft(Free, ledger, day.plusDays(1)))
    }

    @Test
    fun earlyEndsOutsideAurorasControlAreRefunded() {
        val ledger = reserveAll(Free, "09:00" to 120)
        assertEquals(0, lockdownMinutesLeft(Free, ledger, day))
        // The accessibility service is turned off 40 minutes and 10 seconds in.
        val ended = listOf(ledger.single().endedEarlyAt(at("09:40").plusSeconds(10)))
        assertEquals(41, lockdownMinutesUsed(ended, day))
        assertEquals(79, lockdownMinutesLeft(Free, ended, day))
        // An end time outside the reservation can't refund more than it reserved, or extend it.
        assertEquals(0, lockdownMinutesUsed(listOf(ledger.single().endedEarlyAt(at("08:00"))), day))
        assertEquals(120, lockdownMinutesUsed(listOf(ledger.single().endedEarlyAt(at("23:00"))), day))
    }

    @Test
    fun plusLockdownIsUnlimitedAndADowngradeNeverEndsOne() {
        val ledger = reserveAll(Plus, "08:00" to 300)
        assertNull(lockdownMinutesLeft(Plus, ledger, day))
        // The subscription ends during the Lockdown. The reservation stands as promised; Free's
        // limit applies from the next Lockdown, and the minutes left never go negative.
        assertEquals(at("13:00"), ledger.single().end)
        assertEquals(0, lockdownMinutesLeft(Free, ledger, day))
        assertNull(reserveLockdown(Free, ledger, at("14:00"), 10, india))
        assertEquals(120, lockdownMinutesLeft(Free, ledger, day.plusDays(1)))
    }
}
