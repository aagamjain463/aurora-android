package app.aurora

import java.io.File
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Test

/** The founder-approved logo is the source of truth. If this fails, the master was altered: restore it. */
class LogoMasterTest {
    @Test
    fun theApprovedMasterIsUnchanged() {
        val master = listOf("brand/final/aurora-final-logo.png", "../brand/final/aurora-final-logo.png").map(::File).first { it.exists() }
        val sha = MessageDigest.getInstance("SHA-256").digest(master.readBytes()).joinToString("") { "%02x".format(it) }
        assertEquals("123d236f633240a53cc0580b99f0a712f219758ca6de4fbd387365ff603ddb61", sha)
    }
}
