package app.aurora.protect

import app.aurora.plan.Plan
import app.aurora.plan.ProtectedItem
import org.junit.Assert.assertEquals
import org.junit.Test

class PolicyTest {
    private val state = PolicyState(
        protectedPackages = setOf("com.social", "com.video"),
        exempt = SystemExempt + setOf("com.launcher", "app.aurora", "com.video"),
    )

    @Test
    fun protectedAppsAreShieldedAndOthersAllowed() {
        assertEquals(Decision.Shield("com.social", ShieldReason.Protected), decide("com.social", 0, state))
        assertEquals(Decision.Allow, decide("com.mail", 0, state))
        assertEquals(Decision.Allow, decide("com.social", 0, state.copy(protectedPackages = emptySet())))
    }

    @Test
    fun exemptionsAlwaysWinEvenWhenChosen() {
        // The person chose com.video, but this phone needs it (for example as its dialer).
        assertEquals(Decision.Exempt, decide("com.video", 0, state))
        for (pkg in listOf("app.aurora", "com.launcher", "com.android.settings", "com.android.systemui", "com.android.phone")) {
            assertEquals(pkg, Decision.Exempt, decide(pkg, 0, state))
        }
    }

    @Test
    fun grantsAllowUntilTheyEnd() {
        val granted = state.copy(grants = mapOf("com.social" to 1_000L))
        assertEquals(Decision.TemporarilyAllowed(1_000L), decide("com.social", 999, granted))
        assertEquals(Decision.Shield("com.social", ShieldReason.Protected), decide("com.social", 1_000, granted))
        // A grant for an app that isn't protected changes nothing.
        assertEquals(Decision.Allow, decide("com.mail", 0, state.copy(grants = mapOf("com.mail" to 5_000L))))
    }

    @Test
    fun unknownConfigurationAndMissingPackagesNeverShield() {
        assertEquals(Decision.Unavailable, decide("com.social", 0, null))
        assertEquals(Decision.Invalid, decide(null, 0, state))
        assertEquals(Decision.Invalid, decide(" ", 0, state))
        assertEquals(Decision.Invalid, decide("", 0, null))
    }

    /** decide() never reads the plan: the same active items give the same decision under Free and Plus. */
    @Test
    fun freeAndPlusDecideTheSameForTheSameItems() {
        val list = ProtectedList(listOf("com.a", "com.b", "com.c").map { ProtectedItem.App.of(it)!! })
        val free = list.toPolicyState(Plan.Free, emptySet())
        val plus = list.toPolicyState(Plan.Plus, emptySet())
        for (pkg in listOf("com.a", "com.b", "com.c", "com.d", null)) {
            assertEquals(decide(pkg, 0, free), decide(pkg, 0, plus))
        }
    }

    @Test
    fun anUnreadableListGivesNoSnapshot() {
        assertEquals(null, ProtectedList(readable = false).toPolicyState(Plan.Free, emptySet()))
        assertEquals(Decision.Unavailable, decide("com.a", 0, ProtectedList(readable = false).toPolicyState(Plan.Free, emptySet())))
    }
}
