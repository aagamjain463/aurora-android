package app.aurora.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.aurora.AuroraApplication
import app.aurora.plan.Plan
import app.aurora.protect.Mode
import app.aurora.protect.ProtectedList
import app.aurora.protect.Rules
import app.aurora.protect.Runtime
import app.aurora.protect.ShieldPreset
import app.aurora.protect.UsageConfig
import app.aurora.ui.home.QuickStart
import app.aurora.ui.protect.ProtectData
import app.aurora.ui.theme.AuroraTheme
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w411dp-h914dp")
class HomeCustomDurationTest {
    @get:Rule val rule = createComposeRule()
    private val app get() = ApplicationProvider.getApplicationContext<AuroraApplication>()

    private var started: String? = null

    private fun show(mode: Mode = Mode.Flexible) {
        val shield = ShieldPreset("shield", "Shield", listOf("com.instagram.android"), mode)
        val config = UsageConfig("study", "Study", shieldId = shield.id)
        val rules = Rules(version = 1, shields = listOf(shield), configs = listOf(config))
        runBlocking {
            app.rules.update { rules }
            app.runtime.update { Runtime() }
        }
        android.provider.Settings.Secure.putString(app.contentResolver,
            android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, "app.aurora/.protect.ShieldService")
        val data = ProtectData(
            list = ProtectedList(), rules = rules, rulesReadable = true, runtime = Runtime(),
            plan = Plan.Free, apps = emptyMap(), time = app.now(),
        )
        rule.setContent {
            AuroraTheme(reduceMotion = true) {
                Column { QuickStart(listOf(config), data, app, onStart = { started = it }) }
            }
        }
    }

    @Test fun homeShowsOnlyStartUntilTapped() {
        show()
        rule.onNodeWithContentDescription("hours").assertDoesNotExist()
        rule.onNodeWithText("Start").assertIsEnabled()
    }

    /** Start on a saved session goes to the When step (now, later or a schedule); it never starts here. */
    @Test fun homeStartAsksWhenInsteadOfStarting() {
        show(Mode.Cooldown)
        rule.onNodeWithText("Start").performClick()
        rule.waitForIdle()
        assertEquals("study", started)
        rule.onNodeWithContentDescription("hours").assertDoesNotExist()
        assertTrue(app.runtime.value!!.runs.isEmpty())
    }
}
