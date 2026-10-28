package app.aurora

import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Smoke tests through the real activity, nav graph and DataStore. They run on the JVM under
 * Robolectric (`testDebugUnitTest`) and on a device (`connectedDebugAndroidTest`). On a device they
 * overwrite aurora's onboarding and session state, so use an emulator.
 */
@RunWith(AndroidJUnit4::class)
class NavigationTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    private var scenario: ActivityScenario<MainActivity>? = null

    /** Fixture: a known starting state, then the app as the person would open it. */
    private fun launch(onboarded: Boolean) {
        val app = ApplicationProvider.getApplicationContext<AuroraApplication>()
        runBlocking {
            app.preferences.setOnboarded(onboarded)
            app.preferences.clearSession()
        }
        scenario = ActivityScenario.launch(MainActivity::class.java)
    }

    @After
    fun close() {
        scenario?.close()
    }

    /** DataStore writes finish off the main thread, outside Compose's idling, so wait for their result. */
    private fun awaitText(text: String, substring: Boolean = false) = compose.waitUntil(5_000) {
        compose.onAllNodesWithText(text, substring).fetchSemanticsNodes().isNotEmpty()
    }

    private fun tab(label: String) = compose.onNode(hasText(label) and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab))

    @Test
    fun onboardingCompletesOnToday() {
        launch(onboarded = false)
        compose.onNodeWithText("Begin").performClick()
        compose.onNodeWithText("Continue").performClick()
        compose.onNodeWithText("Start using aurora").performClick()
        compose.onNodeWithText("Start focus", substring = true).assertIsDisplayed()
        tab("Today").assertIsSelected()
    }

    @Test
    fun tabsSwitchBetweenTodayProtectAndInsights() {
        launch(onboarded = true)
        tab("Protect").performClick()
        compose.onNode(hasText("Protect") and isHeading()).assertIsDisplayed()
        tab("Protect").assertIsSelected()

        tab("Insights").performClick()
        compose.onNode(hasText("Insights") and isHeading()).assertIsDisplayed()
        tab("Insights").assertIsSelected()

        tab("Today").performClick()
        compose.onNodeWithText("Start focus", substring = true).assertIsDisplayed()
    }

    @Test
    fun focusStartsAndEndsEarly() {
        launch(onboarded = true)
        compose.onNodeWithText("Start focus", substring = true).performClick()
        compose.onNodeWithText("Begin focus").performClick()
        awaitText("End session")
        compose.onNodeWithText("End session").performClick()
        compose.onNodeWithText("End this session?").assertIsDisplayed()
        compose.onNode(hasText("End session") and hasAnyAncestor(isDialog())).performClick()
        awaitText("No session running")
        compose.onNodeWithText("No session running").assertIsDisplayed()
    }
}
