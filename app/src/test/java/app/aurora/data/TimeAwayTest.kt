package app.aurora.data

import org.junit.Assert.assertEquals
import org.junit.Test

/** Time Reclaimed: protected time minus time a covered app was in front. */
class TimeAwayTest {
    private val m = 60_000L
    private val ig = "com.instagram.android"
    private val yt = "com.google.android.youtube"

    @Test
    fun protectionWithNoUseIsAllAway() {
        val r = timeAway(listOf(ProtectedSpan(0, 120 * m, setOf(ig))), emptyList(), 0, 200 * m)
        assertEquals(120 * m, r.protectedMs)
        assertEquals(120 * m, r.awayMs)
    }

    @Test
    fun useOfACoveredAppIsSubtractedButOtherAppsAreNot() {
        val usage = listOf(UsageInterval(ig, 10 * m, 25 * m), UsageInterval("com.mail", 30 * m, 90 * m))
        val r = timeAway(listOf(ProtectedSpan(0, 120 * m, setOf(ig))), usage, 0, 200 * m)
        assertEquals(15 * m, r.usedMs)
        assertEquals(105 * m, r.awayMs)
    }

    @Test
    fun overlappingProtectionIsCountedOnce() {
        val spans = listOf(ProtectedSpan(0, 60 * m, setOf(ig)), ProtectedSpan(30 * m, 90 * m, setOf(ig, yt)))
        val usage = listOf(UsageInterval(yt, 20 * m, 40 * m)) // YouTube is covered only from minute 30
        val r = timeAway(spans, usage, 0, 200 * m)
        assertEquals(90 * m, r.protectedMs)
        assertEquals(10 * m, r.usedMs)
    }

    @Test
    fun protectionStartingMidDayOrStillRunningIsClipped() {
        val r = timeAway(listOf(ProtectedSpan(-60 * m, 500 * m, setOf(ig))), listOf(UsageInterval(ig, -30 * m, 5 * m)), 0, 100 * m)
        assertEquals(100 * m, r.protectedMs)
        assertEquals(5 * m, r.usedMs)
    }

    @Test
    fun useDuringAGrantStillCountsAsUse() {
        // A grant doesn't end protection: time spent in the app is not time away.
        val r = timeAway(listOf(ProtectedSpan(0, 60 * m, setOf(ig))), listOf(UsageInterval(ig, 0, 60 * m)), 0, 60 * m)
        assertEquals(0L, r.awayMs)
    }

    @Test
    fun unionLengthMergesTouchingAndNestedSpans() {
        assertEquals(30L, unionLength(listOf(0L to 10L, 10L to 20L, 12L to 15L, 25L to 35L, 5L to 5L)))
    }

    @Test
    fun reclaimedAddsFinishedDaysToTodayAndNeverCountsTodayTwice() {
        // 2026-10-31 is a Saturday, so its week starts on Monday the 26th.
        val settled = mapOf("2026-10-25" to 100L, "2026-10-26" to 10L, "2026-10-30" to 20L, "2026-10-31" to 999L)
        assertEquals(Reclaimed(5, 35, 135), reclaimed(settled, 5, java.time.LocalDate.parse("2026-10-31")))
    }
}
