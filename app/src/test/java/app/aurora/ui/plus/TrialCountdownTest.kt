package app.aurora.ui.plus

import app.aurora.account.RemoteTrial
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class TrialCountdownTest {
    private val zone = ZoneId.of("Asia/Kolkata")
    private fun at(y: Int, m: Int, d: Int, h: Int) = LocalDateTime.of(y, m, d, h, 0).atZone(zone).toInstant().toEpochMilli()

    @Test
    fun countsCalendarDaysNotRoundedHours() {
        val end = at(2026, 11, 24, 9)
        assertEquals(14, trialDaysLeft(end, at(2026, 11, 10, 9), zone))
        // 23 hours left but the end is tomorrow: one day, not "today".
        assertEquals(1, trialDaysLeft(end, at(2026, 11, 23, 10), zone))
        assertEquals(0, trialDaysLeft(end, at(2026, 11, 24, 1), zone))
        // Past the end (an offline phone): never negative.
        assertEquals(0, trialDaysLeft(end, at(2026, 11, 26, 1), zone))
    }

    @Test
    fun onboardingOffersTheTrialOnlyWhenTheServerDoesAndOnlyOnFree() {
        assertTrue(RemoteTrial("available", days = 14).offered(onPlus = false))
        assertFalse(RemoteTrial("available", days = 14).offered(onPlus = true))
        assertFalse(RemoteTrial("unavailable", reason = "off").offered(onPlus = false))
        assertFalse(RemoteTrial("active").offered(onPlus = false))
        assertFalse((null as RemoteTrial?).offered(onPlus = false))
    }
}
