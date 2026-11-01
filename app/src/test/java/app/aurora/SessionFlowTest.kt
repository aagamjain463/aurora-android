package app.aurora

import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.aurora.protect.Mode
import app.aurora.protect.ShieldPreset
import app.aurora.protect.Timing
import app.aurora.protect.UsageConfig
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.filterNotNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration

/** A session through the real actions and store: what is saved, and what time is recorded. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class SessionFlowTest {
    private val app get() = ApplicationProvider.getApplicationContext<AuroraApplication>()
    private val preset = ShieldPreset("s1", "Social", listOf("com.example.video"), Mode.Flexible)

    /** Runs [block] on the main scope the app's stores use, turning the looper the test thread would otherwise block. */
    private fun flow(block: suspend () -> Unit) {
        val job = app.scope.async { block() }
        val deadline = System.nanoTime() + 30_000_000_000L
        while (!job.isCompleted) {
            check(System.nanoTime() < deadline) { "Timed out" }
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(2)
        }
        job.getCompletionExceptionOrNull()?.let { throw it }
    }

    private suspend fun loaded() {
        app.rules.state.filterNotNull().first()
        app.runtime.state.filterNotNull().first()
        app.history.state.filterNotNull().first()
        app.protectedList.filterNotNull().first()
        // The store outlives one test's application.
        app.deleteAllData()
        app.runtime.reset()
    }

    private fun config(custom: Boolean) = UsageConfig("c1", "Session", custom, shieldId = if (custom) "s1" else null, shield = if (custom) null else preset, createdAt = 1)

    @Test
    fun aNormalSessionRunsAndIsRecordedButNeverSaved() = flow {
        loaded()
        assertNull(app.activate(config(custom = false), Timing.Now(30)))
        assertTrue(app.rules.value!!.configs.isEmpty())
        val run = app.runtime.value!!.runs.single()
        assertEquals(false, run.custom)
        assertEquals(listOf("com.example.video"), run.shield?.keys)

        ShadowSystemClock.advanceBy(Duration.ofMinutes(31))
        app.syncRuns()
        val record = app.history.value!!.runs.single()
        assertEquals(false, record.custom)
        // Settled a minute late, it still lasted the thirty minutes it was set for.
        assertEquals(30 * 60_000L, record.end - record.start)
        assertTrue(app.runtime.value!!.runs.isEmpty())
        assertTrue(app.rules.value!!.configs.isEmpty())
    }

    @Test
    fun aCustomSessionIsSavedToStartAgain() = flow {
        loaded()
        app.saveShield(preset)
        assertNull(app.activate(config(custom = true), Timing.Now(30)))
        assertEquals(listOf("c1"), app.rules.value!!.configs.map { it.id })
    }

    @Test
    fun turningOffEndsTheRecordAndItsProtectionTogether() = flow {
        loaded()
        assertNull(app.activate(config(custom = false), Timing.Now(60)))
        assertTrue(app.turnOffRun(app.runtime.value!!.runs.single().id))
        val record = app.history.value!!.runs.single()
        assertTrue(record.turnedOff)
        assertTrue(record.end < record.start + 60 * 60_000L)
        assertEquals(record.end, app.history.value!!.protection.single().end)
    }
}
