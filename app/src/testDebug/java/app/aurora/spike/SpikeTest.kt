package app.aurora.spike

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpikeTest {
    private val protected = setOf("com.social", "com.android.settings")

    @Test
    fun shieldsOnlyProtectedApps() {
        assertEquals(SpikeDecision.Shield("com.social"), spikeDecide("com.social", protected, SystemExempt, emptyMap(), 0))
        assertEquals(SpikeDecision.Allow, spikeDecide("com.other", protected, SystemExempt, emptyMap(), 0))
    }

    @Test
    fun exemptionsWinEvenWhenProtected() {
        // Settings must stay reachable so the person can always turn the service off.
        assertEquals(SpikeDecision.Allow, spikeDecide("com.android.settings", protected, SystemExempt, emptyMap(), 0))
    }

    @Test
    fun grantAllowsUntilItExpires() {
        val grants = mapOf("com.social" to 1_000L)
        assertEquals(SpikeDecision.Allow, spikeDecide("com.social", protected, SystemExempt, grants, 999))
        assertEquals(SpikeDecision.Shield("com.social"), spikeDecide("com.social", protected, SystemExempt, grants, 1_000))
    }

    @Test
    fun activeOnlyWhenEnabledAndConnected() {
        assertEquals(SpikeStatus.Off, spikeStatus(hasItems = false, enabledInSettings = true, connected = true))
        assertEquals(SpikeStatus.NeedsPermission, spikeStatus(hasItems = true, enabledInSettings = false, connected = true))
        assertEquals(SpikeStatus.NotRunning, spikeStatus(hasItems = true, enabledInSettings = true, connected = false))
        assertEquals(SpikeStatus.Active, spikeStatus(hasItems = true, enabledInSettings = true, connected = true))
    }

    @Test
    fun parsesEnabledServicesSetting() {
        val full = "app.aurora/app.aurora.spike.SpikeShieldService"
        val short = "app.aurora/.spike.SpikeShieldService"
        assertTrue(isListedInEnabledServices("com.x/.A:$short", full, short))
        assertTrue(isListedInEnabledServices(full, full, short))
        assertFalse(isListedInEnabledServices(null, full, short))
        assertFalse(isListedInEnabledServices("app.aurora/.Other", full, short))
    }
}
