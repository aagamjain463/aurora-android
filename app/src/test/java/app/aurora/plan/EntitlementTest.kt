package app.aurora.plan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EntitlementTest {
    private fun purchase(product: String = "aurora_plus", purchased: Boolean = true, pending: Boolean = false, acknowledged: Boolean = true) =
        PurchaseFacts(listOf(product), purchased, pending, acknowledged, autoRenewing = true, token = "t-$product")

    @Test
    fun onlyAPurchasedPlusSubscriptionGrantsPlus() {
        assertTrue(evaluate(listOf(purchase()), setOf("aurora_plus")).plus)
        assertFalse(evaluate(listOf(purchase("other")), setOf("aurora_plus")).plus)
        assertFalse(evaluate(emptyList(), setOf("aurora_plus")).plus)
        val pending = evaluate(listOf(purchase(purchased = false, pending = true)), setOf("aurora_plus"))
        assertFalse(pending.plus)
        assertTrue(pending.pending)
    }

    @Test
    fun unacknowledgedPurchasesAreAcknowledged() {
        assertEquals(listOf("t-aurora_plus"), evaluate(listOf(purchase(acknowledged = false)), setOf("aurora_plus")).toAcknowledge)
        assertEquals(emptyList<String>(), evaluate(listOf(purchase(purchased = false, pending = true, acknowledged = false)), setOf("aurora_plus")).toAcknowledge)
    }

    @Test
    fun aConfirmedPlusLastsAWeekOfflineAndNoLonger() {
        val cache = EntitlementCache(plus = true, checkedAt = 1_000)
        assertEquals(Plan.Plus, planFrom(cache, 1_000))
        assertEquals(Plan.Plus, planFrom(cache, 1_000 + OfflineGraceMs))
        assertEquals(Plan.Free, planFrom(cache, 1_001 + OfflineGraceMs))
        assertEquals(Plan.Free, planFrom(cache, 999)) // clock set back
        assertEquals(Plan.Free, planFrom(EntitlementCache(), 1_000))
    }
}
