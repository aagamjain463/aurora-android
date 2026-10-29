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
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import app.aurora.protect.ShieldPreset
import app.aurora.protect.FocusPlan
import app.aurora.protect.UsageConfig
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
            app.preferences.setReduceMotion(true)
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

    /** Home is a lazy list: bring a node into composition before looking for it. */
    private fun scrollTo(text: String): androidx.compose.ui.test.SemanticsNodeInteraction {
        compose.waitUntil(5_000) { compose.onAllNodes(androidx.compose.ui.test.hasScrollAction()).fetchSemanticsNodes().isNotEmpty() }
        compose.waitUntil(5_000) {
            runCatching {
                compose.onAllNodes(androidx.compose.ui.test.hasScrollAction())[0].performScrollToNode(hasText(text))
            }.isSuccess
        }
        return compose.onNodeWithText(text)
    }

    private fun tab(label: String) = compose.onNode(hasText(label) and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab))

    @Test
    fun onboardingCompletesOnHome() {
        launch(onboarded = false)
        compose.onNodeWithText("Begin").performClick()
        // What matters, apps, modes, Intentional App Opening, Shield, keeping it running: nothing is required.
        repeat(6) {
            awaitText("Continue")
            compose.onNodeWithText("Continue").performClick()
        }
        awaitText("Go to Home")
        compose.onNodeWithText("Go to Home").performClick()
        scrollTo("How do you want to use Aurora?").assertIsDisplayed()
        tab("Home").assertIsSelected()
    }

    @Test
    fun fourTabs() {
        launch(onboarded = true)
        tab("Shield").performClick()
        compose.onNode(hasText("Never get distracted from what matters.") and isHeading()).assertIsDisplayed()
        tab("Shield").assertIsSelected()

        tab("Intent").performClick()
        compose.onNode(hasText("Open apps on purpose.") and isHeading()).assertIsDisplayed()

        tab("Track").performClick()
        compose.onNode(hasText("Track") and isHeading()).assertIsDisplayed()
        tab("Track").assertIsSelected()

        tab("Home").performClick()
        scrollTo("Custom Configuration").assertIsDisplayed()
    }

    /**
     * Founder scenario A through the real nav graph and DataStore: a saved Shield, a Custom Configuration
     * started now for 15 minutes, shown as active on Home, then turned off.
     */
    @Test
    fun customConfigurationStartsAndTurnsOff() {
        val app = ApplicationProvider.getApplicationContext<AuroraApplication>()
        runBlocking {
            app.runtime.update { it.copy(runs = emptyList()) }
            app.rules.update { it.copy(version = 1, configs = emptyList(), activations = emptyList(), shields = listOf(ShieldPreset("t", "Test Shield", listOf("com.example.social")))) }
        }
        launch(onboarded = true)
        scrollTo("Custom Configuration").performClick()
        awaitText("Test Shield")
        compose.onNodeWithText("Test Shield").performClick()
        compose.onNodeWithText("Next").performClick()
        compose.onNode(androidx.compose.ui.test.hasSetTextAction()).performTextInput("Study")
        compose.onNodeWithText("Next").performClick()
        compose.onNodeWithText("Start now").performClick()
        compose.onNodeWithText("Next").performClick()
        compose.onNodeWithText("15 min").performClick()
        compose.onNodeWithText("Next").performClick()
        compose.onNodeWithText("Next").performClick() // no Focus
        compose.onNodeWithText("Start").performClick()
        // Fetching nodes syncs with the main thread, where each step of the activation resumes.
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Home").fetchSemanticsNodes().isNotEmpty() && app.runtime.value?.runs?.size == 1 }
        val runId = app.runtime.value!!.runs.single().id
        scrollTo("Turn off").performSemanticsAction(SemanticsActions.OnClick)
        compose.onNode(hasText("Turn off") and hasAnyAncestor(isDialog())).performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Turn off").fetchSemanticsNodes().isEmpty() && app.history.value?.runs?.isNotEmpty() == true }
        org.junit.Assert.assertTrue(app.runtime.value!!.runs.isEmpty())
        org.junit.Assert.assertEquals("Study", app.history.value!!.runs.single { it.id == runId }.name)
    }

    @Test
    fun habitCanBeCreatedArchivedAndRestoredWithoutStartingProtection() {
        val app = ApplicationProvider.getApplicationContext<AuroraApplication>()
        val config = UsageConfig("habit-test", "Habit Study", focus = FocusPlan("Study"))
        runBlocking {
            app.rules.update { r -> r.copy(
                version = 1,
                configs = r.configs.filterNot { it.id == config.id } + config,
                habits = r.habits.filterNot { it.configId == config.id },
            ) }
        }
        launch(onboarded = true)
        val runsBefore = app.runtime.value!!.runs
        tab("Track").performClick()
        scrollTo("Create Habit").performSemanticsAction(SemanticsActions.OnClick)
        awaitText("Name your habit")
        compose.onNode(androidx.compose.ui.test.hasSetTextAction()).performTextInput("Daily learning")
        compose.onNodeWithText("Next").performClick()
        awaitText("Habit Study")
        compose.onNodeWithText("Habit Study").performClick()
        compose.onNodeWithText("Next").performClick()
        compose.onNodeWithText("Whenever I use it").performScrollTo().performClick()
        compose.onNodeWithText("Next").performClick()
        compose.onNodeWithText("Save habit").performClick()
        awaitText("Linked to Habit Study")
        val habit = app.rules.value!!.habits.single { it.configId == config.id }
        org.junit.Assert.assertTrue(habit.target.onDemand)
        org.junit.Assert.assertEquals(runsBefore, app.runtime.value!!.runs)
        org.junit.Assert.assertTrue(app.rules.value!!.activations.none { it.configId == config.id })
        scrollTo("Archive").performClick()
        awaitText("Restore")
        org.junit.Assert.assertTrue(app.rules.value!!.habits.single { it.id == habit.id }.archived)
        scrollTo("Restore").performClick()
        awaitText("Archive")
        org.junit.Assert.assertFalse(app.rules.value!!.habits.single { it.id == habit.id }.archived)
    }
}
