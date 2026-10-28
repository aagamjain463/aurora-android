package app.aurora

import app.aurora.data.AccessState
import app.aurora.data.AccessState.Granted
import app.aurora.data.AccessState.Missing
import app.aurora.data.AccessState.Revoked
import app.aurora.data.ProtectionStatus
import app.aurora.data.accessState
import app.aurora.data.isListedInEnabledServices
import app.aurora.data.protectionStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AccessTest {

    @Test
    fun accessAbsentGrantedAndRevoked() {
        assertEquals(Missing, accessState(granted = false, previous = null))
        assertEquals(Missing, accessState(granted = false, previous = Missing))
        assertEquals(Granted, accessState(granted = true, previous = Missing))
        // Turned off in Settings while aurora was open.
        assertEquals(Revoked, accessState(granted = false, previous = Granted))
        assertEquals(Revoked, accessState(granted = false, previous = Revoked))
        // Turned back on.
        assertEquals(Granted, accessState(granted = true, previous = Revoked))
    }

    @Test
    fun onlyAConnectedServiceWithPermissionIsActive() {
        fun status(hasItems: Boolean = true, blocked: Boolean = false, access: AccessState = Granted, connected: Boolean = true) =
            protectionStatus(hasItems, blocked, access, connected)

        assertEquals(ProtectionStatus.Active, status())
        assertEquals(ProtectionStatus.Off, status(hasItems = false))
        assertEquals(ProtectionStatus.NeedsPermission, status(access = Missing, connected = false))
        assertEquals(ProtectionStatus.NeedsPermission, status(access = Revoked, connected = false))
        // Permission alone is not protection: force-stopped, crashed or killed by the device maker.
        assertEquals(ProtectionStatus.NotRunning, status(connected = false))
        // Android or the device maker won't let it run, so the switch isn't the way forward.
        assertEquals(ProtectionStatus.Blocked, status(blocked = true))
        assertEquals(ProtectionStatus.Blocked, status(blocked = true, access = Missing, connected = false))
        // A stale connection without the switch still isn't protection.
        assertEquals(ProtectionStatus.NeedsPermission, status(access = Revoked, connected = true))
        // Nothing to protect wins over everything.
        assertEquals(ProtectionStatus.Off, status(hasItems = false, blocked = true, access = Missing, connected = false))
    }

    @Test
    fun enabledServicesSettingIsMatchedExactly() {
        val long = "app.aurora/app.aurora.protect.ShieldService"
        val short = "app.aurora/.protect.ShieldService"
        assertTrue(isListedInEnabledServices("com.other/.Svc:$long", long, short))
        assertTrue(isListedInEnabledServices(short.uppercase(), long, short))
        assertFalse(isListedInEnabledServices(null, long, short))
        assertFalse(isListedInEnabledServices("", long, short))
        assertFalse(isListedInEnabledServices("app.aurora/app.aurora.protect.ShieldServiceX", long, short))
        assertFalse(isListedInEnabledServices("app.aurora.debug/app.aurora.protect.ShieldService", long, short))
    }
}
