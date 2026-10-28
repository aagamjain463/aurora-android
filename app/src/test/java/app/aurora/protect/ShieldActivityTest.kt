package app.aurora.protect

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.aurora.AuroraApplication
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

@RunWith(AndroidJUnit4::class)
class ShieldActivityTest {
    @get:Rule val compose = createEmptyComposeRule()

    private val app = ApplicationProvider.getApplicationContext<AuroraApplication>()
    private val request = ShieldRequest("com.example.social", ShieldReason.Blocked, eventUptime = 1, receivedUptime = 2, decidedUptime = 3)

    private fun launch() = ActivityScenario.launch<ShieldActivity>(ShieldActivity.intentFor(app, request))

    private fun nextIsHome() {
        val next = shadowOf(app).nextStartedActivity
        assertEquals(Intent.ACTION_MAIN, next.action)
        assertTrue(next.hasCategory(Intent.CATEGORY_HOME))
    }

    @Test
    fun showsTheAppAndOneWayBack() {
        launch().use {
            // The app isn't installed here, so its package stands in for the label.
            compose.onNodeWithText("com.example.social is protected").assertIsDisplayed()
            compose.onNodeWithText("Protected by Shield").assertIsDisplayed()
            compose.onNodeWithText("Close app").assertIsDisplayed()
        }
    }

    @Test
    fun closeAppGoesHomeAndFinishes() {
        launch().use { scenario ->
            compose.onNodeWithText("Close app").performClick()
            nextIsHome()
            scenario.onActivity { assertTrue(it.isFinishing) }
        }
    }

    @Test
    fun backGoesHomeNeverToTheProtectedApp() {
        launch().use { scenario ->
            scenario.onActivity {
                it.onBackPressedDispatcher.onBackPressed()
                assertTrue(it.isFinishing)
            }
            nextIsHome()
        }
    }

    @Test
    fun rotationKeepsTheShield() {
        launch().use { scenario ->
            scenario.recreate()
            compose.onNodeWithText("com.example.social is protected").assertIsDisplayed()
        }
    }

    @Test
    fun aShieldAuroraNoLongerWantsNeverAppears() {
        val stale = ShieldActivity.intentFor(app, request)
        ShieldActivity.hide() // the person switched away before the activity started
        ActivityScenario.launch<ShieldActivity>(stale).use { assertEquals(Lifecycle.State.DESTROYED, it.state) }
    }

    @Test
    fun hideFinishesAShowingShield() {
        launch().use { scenario ->
            scenario.onActivity {
                ShieldActivity.hide()
                assertTrue(it.isFinishing)
            }
        }
    }

    /** The manifest is part of the safety story: only Android can bind the service, and the Shield stays out of Recents. */
    @Test
    fun manifestKeepsTheServiceAndShieldLockedDown() {
        val pm = app.packageManager
        val service = pm.getServiceInfo(ComponentName(app, ShieldService::class.java), PackageManager.GET_META_DATA)
        assertEquals(Manifest.permission.BIND_ACCESSIBILITY_SERVICE, service.permission)
        val shield = pm.getActivityInfo(ComponentName(app, ShieldActivity::class.java), 0)
        assertTrue(!shield.exported)
        assertTrue(shield.flags and ActivityInfo.FLAG_EXCLUDE_FROM_RECENTS != 0)
        assertEquals(ActivityInfo.LAUNCH_SINGLE_TASK, shield.launchMode)
    }
}
