package app.aurora

import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
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
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
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
            app.preferences.setOnboardingStep(0)
            app.runtime.update { it.copy(focus = null) }
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

    /** Today is a lazy list: bring the Focus button into composition before looking for it. */
    private fun focusButton(): androidx.compose.ui.test.SemanticsNodeInteraction {
        compose.waitUntil(5_000) { compose.onAllNodes(androidx.compose.ui.test.hasScrollAction()).fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodes(androidx.compose.ui.test.hasScrollAction())[0].performScrollToNode(hasText("Start Focus", substring = true))
        return compose.onNodeWithText("Start Focus", substring = true)
    }

    private fun tab(label: String) = compose.onNode(hasText(label) and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab))

    @Test
    fun onboardingCompletesOnToday() {
        launch(onboarded = false)
        compose.onNodeWithText("Begin").performClick()
        // What matters, apps, modes, Intentional App Opening, Shield, keeping it running: nothing is required.
        repeat(6) {
            awaitText("Continue")
            compose.onNodeWithText("Continue").performClick()
        }
        awaitText("Go to Today")
        compose.onNodeWithText("Go to Today").performClick()
        focusButton().assertIsDisplayed()
        tab("Today").assertIsSelected()
    }

    @Test
    fun tabsSwitchBetweenTodayProtectAndInsights() {
        launch(onboarded = true)
        tab("Protect").performClick()
        compose.onNode(hasText("Never get distracted from what matters.") and isHeading()).assertIsDisplayed()
        tab("Protect").assertIsSelected()

        tab("Insights").performClick()
        compose.onNode(hasText("Insights") and isHeading()).assertIsDisplayed()
        tab("Insights").assertIsSelected()

        tab("Today").performClick()
        focusButton().assertIsDisplayed()
    }

    @Test
    fun focusStartsAndEndsEarly() {
        launch(onboarded = true)
        // The floating tab bar can cover a button scrolled to the bottom edge of this small test screen.
        focusButton().performSemanticsAction(SemanticsActions.OnClick)
        awaitText("Begin focus")
        compose.onNodeWithText("Begin focus").performClick()
        awaitText("End session")
        compose.onNodeWithText("End session").performClick()
        compose.onNodeWithText("End this session?").assertIsDisplayed()
        compose.onNode(hasText("End session") and hasAnyAncestor(isDialog())).performClick()
        // Ending early closes Focus and Today offers a new session.
        focusButton().assertIsDisplayed()
    }
}
