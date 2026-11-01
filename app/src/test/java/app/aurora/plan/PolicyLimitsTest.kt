package app.aurora.plan

import app.aurora.plan.Plan.Free
import app.aurora.plan.Plan.Plus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PolicyLimitsTest {
    private val five = setOf("a", "b", "c", "d", "e")

    @Test
    fun fiveShieldTargetsAllowedAndASixthRefused() {
        assertTrue(EntitlementPolicy.canActivateShieldTarget(Free, setOf("a", "b", "c", "d"), setOf("e")).allowed)
        val d = EntitlementPolicy.canActivateShieldTarget(Free, five, setOf("f"))
        assertFalse(d.allowed)
        assertEquals(LimitReason.ShieldTargets, d.reason)
        assertEquals(5L, d.used)
        assertEquals(5L, d.limit)
        assertEquals(0L, d.remaining)
        assertEquals(Remedy.FreeASlot, d.remedy)
        assertTrue(d.upgradeResolves)
    }

    @Test
    fun reusingATargetNeedsNoSlot() {
        assertTrue(EntitlementPolicy.canActivateShieldTarget(Free, five, setOf("a", "e")).allowed)
    }

    @Test
    fun replacingATargetChecksTheUnionAfterTheChange() {
        assertTrue(EntitlementPolicy.canActivateShieldTarget(Free, five - "e", setOf("f")).allowed)
    }

    @Test
    fun threeIntentAppsAllowedAndAFourthRefused() {
        assertTrue(EntitlementPolicy.canActivateIntentApp(Free, setOf("a", "b"), setOf("c")).allowed)
        assertTrue(EntitlementPolicy.canActivateIntentApp(Free, setOf("a", "b", "c"), setOf("a")).allowed)
        val d = EntitlementPolicy.canActivateIntentApp(Free, setOf("a", "b", "c"), setOf("d"))
        assertFalse(d.allowed)
        assertEquals(LimitReason.IntentApps, d.reason)
    }

    @Test
    fun oneActiveGoalAllowedAndASecondRefused() {
        assertTrue(EntitlementPolicy.canActivateGoal(Free, 0).allowed)
        val d = EntitlementPolicy.canActivateGoal(Free, 1)
        assertFalse(d.allowed)
        assertEquals(LimitReason.ActiveGoal, d.reason)
    }

    @Test
    fun plusHasNoCommercialLimit() {
        assertTrue(EntitlementPolicy.canActivateShieldTarget(Plus, (1..100).map { "$it" }.toSet(), setOf("x")).allowed)
        assertTrue(EntitlementPolicy.canActivateIntentApp(Plus, (1..100).map { "$it" }.toSet(), setOf("x")).allowed)
        assertTrue(EntitlementPolicy.canActivateGoal(Plus, 100).allowed)
    }

    @Test
    fun advancedAnalyticsIsPlus() {
        assertFalse(EntitlementPolicy.canAccessAdvancedAnalytics(Free).allowed)
        assertTrue(EntitlementPolicy.canAccessAdvancedAnalytics(Plus).allowed)
    }
}
