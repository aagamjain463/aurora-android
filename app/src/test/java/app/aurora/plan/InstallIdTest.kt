package app.aurora.plan

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Audit REL-2 (audit/RELIABILITY_FINDINGS.md). The install id is read by the account and by billing, from
 * different threads, and a process killed between creating the file and writing it left it empty. An empty
 * or garbled id is refused by every account-service route (`bad_request`), forever, so it is made again.
 */
@RunWith(AndroidJUnit4::class)
class InstallIdTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val file get() = File(context.noBackupFilesDir, "install_id")
    private val shape = Regex("[A-Za-z0-9_-]{1,64}")

    @Before
    fun clean() {
        file.delete()
    }

    @Test
    fun anIdIsMadeOnceAndKept() {
        val first = installId(context)
        assertTrue(shape.matches(first))
        assertEquals(first, installId(context))
        assertEquals(first, file.readText())
    }

    @Test
    fun anEmptyFileLeftByAKilledProcessIsReplaced() {
        file.parentFile!!.mkdirs()
        file.writeText("")
        val id = installId(context)
        assertTrue("got '$id'", shape.matches(id))
        assertEquals(id, installId(context))
    }

    @Test
    fun aGarbledFileIsReplaced() {
        file.parentFile!!.mkdirs()
        file.writeText("half-writ\u0000")
        assertTrue(shape.matches(installId(context)))
    }

    @Test
    fun threadsReadingAtOnceAllGetTheSameId() {
        val pool = Executors.newFixedThreadPool(8)
        val start = CountDownLatch(1)
        val ids = (1..32).map { pool.submit<String> { start.await(); installId(context) } }
        start.countDown()
        val seen = ids.map { it.get(10, TimeUnit.SECONDS) }.toSet()
        pool.shutdown()
        assertEquals(1, seen.size)
        assertEquals(seen.single(), file.readText())
    }
}
