package app.aurora

import java.io.File
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Test

/** The founder-approved logo is the source of truth. If this fails, the master was altered: restore it. */
class LogoMasterTest {
    @Test
    fun theApprovedMasterIsUnchanged() {
        val master = listOf("brand/Aurora_Final_Approved_Logo.png", "../brand/Aurora_Final_Approved_Logo.png").map(::File).first { it.exists() }
        val sha = MessageDigest.getInstance("SHA-256").digest(master.readBytes()).joinToString("") { "%02x".format(it) }
        assertEquals("461a6779eecb4a963bff1acf6c8e3de4fc71332d5483161ed74d62aa5123daea", sha)
    }
}
