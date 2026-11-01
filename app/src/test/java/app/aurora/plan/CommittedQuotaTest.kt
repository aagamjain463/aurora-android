package app.aurora.plan

import app.aurora.plan.Plan.Free
import app.aurora.plan.Plan.Plus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class CommittedQuotaTest {
    private val india: ZoneId = ZoneId.of("Asia/Kolkata")
    private val min = 60_000L
    private val day = LocalDate.of(2026, 10, 28)
    private fun at(h: Int, m: Int = 0, on: LocalDate = day, zone: ZoneId = india) =
        on.atTime(h, m).atZone(zone).toInstant().toEpochMilli()

    private fun entry(start: Long, minutes: Int, zone: ZoneId = india) = LedgerEntry(start = start, end = start + minutes * min, zone = zone.id)

    private fun fits(ledger: List<LedgerEntry>, start: Long, minutes: Int, plan: Plan = Free, zone: ZoneId = india) =
        committedFits(plan, ledger, start, start + minutes * min, zone)

    private fun used(ledger: List<LedgerEntry>, at: Long, zone: ZoneId = india) = quotaStatus(Free, ledger, at, zone).usedMs / min

    @Test
    fun boundaries() {
        assertTrue("119", fits(emptyList(), at(10), 119))
        assertTrue("exactly 120", fits(emptyList(), at(10), 120))
        assertFalse("121", fits(emptyList(), at(10), 121))
    }

    @Test
    fun cooldownAndAlwaysBlockShareOneAllowance() {
        val sixty = listOf(entry(at(9), 60))
        assertTrue(fits(sixty, at(11), 60))
        assertFalse(fits(listOf(entry(at(9), 90)), at(11), 31))
        assertTrue(fits(listOf(entry(at(9), 90)), at(11), 30))
    }

    @Test
    fun overlapsCountOnce() {
        // Cooldown 10:00-11:00 and Always Block 10:30-11:30 cover 90 minutes, not 120.
        val ledger = listOf(entry(at(10), 60), entry(at(10, 30), 60))
        assertEquals(90, used(ledger, at(12)))
        assertTrue(fits(ledger, at(12), 30))
        assertFalse(fits(ledger, at(12), 31))
    }

    @Test
    fun aSessionInsideExistingCoverageCostsNothingEvenWhenTheDayIsFull() {
        val full = listOf(entry(at(10), 120))
        assertTrue(fits(full, at(10, 30), 30))
        assertFalse(fits(full, at(11, 30), 31))
    }

    @Test
    fun twentyFiveLeftRefusesFortyButNotTwentyFive() {
        val ledger = listOf(entry(at(9), 95))
        val d = EntitlementPolicy.canStartCommittedProtection(Free, ledger, at(12), at(12) + 40 * min, india)
        assertFalse(d.allowed)
        assertEquals(LimitReason.CommittedProtection, d.reason)
        assertEquals(25 * min, d.remaining)
        assertEquals(Remedy.ChooseShorter, d.remedy)
        assertTrue(d.upgradeResolves)
        assertEquals(at(0, on = day.plusDays(1)), d.resetAt)
        assertTrue(EntitlementPolicy.canStartCommittedProtection(Free, ledger, at(12), at(12) + 25 * min, india).allowed)
    }

    @Test
    fun usedUpOffersNoShorterTime() {
        val d = EntitlementPolicy.canStartCommittedProtection(Free, listOf(entry(at(9), 120)), at(12), at(12) + min, india)
        assertEquals(Remedy.WaitForReset, d.remedy)
    }

    @Test
    fun aSessionAcrossMidnightChargesEachDayItsOwnPart() {
        val night = entry(at(23), 120)
        assertEquals(60, used(listOf(night), at(23, 30)))
        assertEquals(60, used(listOf(night), at(0, 30, day.plusDays(1))))
        // Each part must fit its own day.
        val tomorrowUsed = listOf(entry(at(9, on = day.plusDays(1)), 90))
        assertTrue(fits(tomorrowUsed, at(23), 90))
        assertFalse(fits(tomorrowUsed, at(23), 91))
    }

    @Test
    fun aThreeHourWindowAcrossMidnightFitsTwoEmptyDaysButNotOne() {
        assertTrue(fits(emptyList(), at(22, 30), 180))
        assertFalse(fits(emptyList(), at(12), 180))
    }

    @Test
    fun daylightSavingDaysKeepTheirOwnLength() {
        val ny = ZoneId.of("America/New_York")
        val spring = LocalDate.of(2026, 3, 8) // 23 hours
        val fall = LocalDate.of(2026, 11, 1) // 25 hours
        val (a, b) = dayWindow(at(12, on = spring, zone = ny), ny)
        assertEquals(23 * 60 * min, b - a)
        val (c, d) = dayWindow(at(12, on = fall, zone = ny), ny)
        assertEquals(25 * 60 * min, d - c)
        // 120 minutes across the repeated hour is still 120 minutes.
        val start = at(0, 30, fall, ny)
        assertTrue(fits(emptyList(), start, 120, zone = ny))
        assertFalse(fits(emptyList(), start, 121, zone = ny))
        assertEquals(at(0, on = fall.plusDays(1), zone = ny), quotaStatus(Free, emptyList(), start, ny).resetAt)
    }

    @Test
    fun changingTheZoneDoesNotStartANewDay() {
        val tokyo = ZoneId.of("Asia/Tokyo")
        val ledger = listOf(entry(at(20), 120))
        // Midnight has passed in Tokyo, but not in the zone the time was charged in.
        val now = at(23, 50)
        assertEquals(india, quotaZone(ledger, now, tokyo))
        assertEquals(120, used(ledger, now, quotaZone(ledger, now, tokyo)))
        assertFalse(fits(ledger, now, 5, zone = quotaZone(ledger, now, tokyo)))
        // Once that day is over, the phone's zone applies.
        assertEquals(tokyo, quotaZone(ledger, at(0, 1, day.plusDays(1)), tokyo))
    }

    @Test
    fun movingTheClockInOneBootChangesNothing() {
        val entry = entry(at(10), 120).copy(elapsed = 1_000_000L, boot = 7)
        val realNow = at(11) // an hour of monotonic time later
        val elapsed = 1_000_000L + 60 * min
        assertEquals(realNow, quotaNow(listOf(entry), at(10, on = day.plusDays(5)), elapsed, 7))
        assertEquals(realNow, quotaNow(listOf(entry), at(10, on = day.minusDays(5)), elapsed, 7))
    }

    @Test
    fun afterARestartASmallClockRollbackCannotReopenADay() {
        val ledger = listOf(entry(at(10), 120).copy(elapsed = 5_000L, boot = 7))
        // Boot 8: the monotonic clock means nothing now.
        assertEquals(at(10), quotaNow(ledger, at(10, on = day.minusDays(1)), 100L, 8))
        // A clock wrong by weeks is corrected, not trusted over the truth.
        assertEquals(at(10, on = day.minusDays(20)), quotaNow(ledger, at(10, on = day.minusDays(20)), 100L, 8))
        // Moving forward normally is simply time passing.
        assertEquals(at(9, on = day.plusDays(1)), quotaNow(ledger, at(9, on = day.plusDays(1)), 100L, 8))
    }

    @Test
    fun theLedgerIsStoredDataSoRestartsChangeNothing() {
        val stored = listOf(entry(at(10), 90))
        val reloaded = app.aurora.data.AuroraJson.decodeFromString(
            kotlinx.serialization.builtins.ListSerializer(LedgerEntry.serializer()),
            app.aurora.data.AuroraJson.encodeToString(kotlinx.serialization.builtins.ListSerializer(LedgerEntry.serializer()), stored),
        )
        assertEquals(90, used(reloaded, at(13)))
    }

    @Test
    fun plusHasNoQuotaButStillRecords() {
        assertTrue(fits(listOf(entry(at(0), 1000)), at(20), 600, Plus))
        assertEquals(null, quotaStatus(Plus, emptyList(), at(10), india).remainingMs)
    }

    @Test
    fun aStoredEntryWithoutAZoneOrFromAnOlderVersionStillCounts() {
        val old = app.aurora.data.AuroraJson.decodeFromString(LedgerEntry.serializer(), """{"day":"2026-10-28","start":${at(10)},"end":${at(10) + 30 * min}}""")
        assertEquals(30, used(listOf(old), at(13)))
    }
}
