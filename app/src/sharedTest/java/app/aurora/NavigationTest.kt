package app.aurora

import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
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

    /** Nothing starts without Shield. Under Robolectric the switch can be set; on a device, turn Shield on first. */
    private fun shieldOn() {
        val app = ApplicationProvider.getApplicationContext<AuroraApplication>()
        runCatching {
            android.provider.Settings.Secure.putString(app.contentResolver, android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, "app.aurora/.protect.ShieldService")
        }
    }

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
        // Apps, then Shield: nothing is required. Until Shield is on, its step offers only "Turn on Shield" and a quiet skip.
        repeat(2) {
            compose.waitUntil(5_000) { listOf("Continue", "Skip for now").any { t -> compose.onAllNodesWithText(t).fetchSemanticsNodes().isNotEmpty() } }
            val next = if (compose.onAllNodesWithText("Continue").fetchSemanticsNodes().isNotEmpty()) "Continue" else "Skip for now"
            compose.onNodeWithText(next).performClick()
        }
        // Skipping leaves the last step honest about what's missing.
        awaitText("Almost there.")
        awaitText("Go to Home")
        compose.onNodeWithText("Go to Home").performClick()
        scrollTo("Normal Session").assertIsDisplayed()
        tab("Home").assertIsSelected()
    }

    @Test
    fun fourTabs() {
        launch(onboarded = true)
        tab("Shield").performClick()
        compose.onNode(hasText("Shield yourself from distractions.") and isHeading()).assertIsDisplayed()
        tab("Shield").assertIsSelected()

        tab("Intent").performClick()
        compose.onNode(hasText("Use with purpose. Not impulse.") and isHeading()).assertIsDisplayed()

        tab("Track").performClick()
        compose.onNode(hasText("See your progress. Own your growth.") and isHeading()).assertIsDisplayed()
        tab("Track").assertIsSelected()

        tab("Home").performClick()
        scrollTo("Custom Session").assertIsDisplayed()
    }

    /**
     * Founder scenario A through the real nav graph and DataStore: a saved Shield, a Custom Configuration
     * started now for 15 minutes, shown as active on Home, then turned off.
     */
    @Test
    fun customConfigurationStartsAndTurnsOff() {
        val app = ApplicationProvider.getApplicationContext<AuroraApplication>()
        shieldOn()
        runBlocking {
            app.runtime.update { it.copy(runs = emptyList()) }
            app.rules.update { it.copy(version = 1, configs = emptyList(), activations = emptyList(), shields = listOf(ShieldPreset("t", "Test Shield", listOf("com.example.social")))) }
        }
        launch(onboarded = true)
        // The 320x470 JVM viewport can put the row centre behind the floating dock.
        scrollTo("Custom Session").performSemanticsAction(SemanticsActions.OnClick)
        // What: the name and the Shield on one step; When: the timing and its length; then Review.
        compose.waitUntil(5_000) { compose.onAllNodes(androidx.compose.ui.test.hasSetTextAction()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(androidx.compose.ui.test.hasSetTextAction()).performTextInput("Study")
        compose.onNode(androidx.compose.ui.test.hasScrollToNodeAction()).performScrollToNode(hasText("Test Shield"))
        compose.onNodeWithText("Test Shield").performClick()
        compose.onNodeWithText("Next").performClick()
        compose.onNodeWithText("Start now").performClick()
        compose.onNode(androidx.compose.ui.test.hasScrollToNodeAction()).performScrollToNode(hasText("15 min"))
        compose.onNodeWithText("15 min").performClick()
        compose.onNodeWithText("Next").performClick()
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

    /** A Normal Session is one screen: time, mode, apps, Start. */
    @Test
    fun normalSessionStartsFromOneScreen() {
        val app = ApplicationProvider.getApplicationContext<AuroraApplication>()
        shieldOn()
        runBlocking {
            app.runtime.update { it.copy(runs = emptyList()) }
            app.rules.update { it.copy(version = 1, configs = emptyList(), activations = emptyList(), shields = listOf(ShieldPreset("t", "Test Shield", listOf("com.example.social"), createdAt = 1))) }
        }
        launch(onboarded = true)
        scrollTo("Normal Session").performSemanticsAction(SemanticsActions.OnClick)
        awaitText("Start")
        compose.onNodeWithText("Start").assertIsEnabled()
        compose.onNodeWithText("Start").performClick()
        // Fetching nodes syncs with the main thread, where each step of the activation resumes.
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Home").fetchSemanticsNodes().isNotEmpty() && app.runtime.value?.runs?.size == 1 }
        val run = app.runtime.value!!.runs.single()
        org.junit.Assert.assertEquals(listOf("com.example.social"), run.shield?.keys)
        org.junit.Assert.assertEquals(false, run.custom)
        org.junit.Assert.assertTrue(app.rules.value!!.configs.isEmpty())
        runBlocking { app.runtime.update { it.copy(runs = emptyList()) } }
    }

    /** Without the Accessibility switch a session would only be a timer, so Review won't start it. */
    @Test
    fun nothingStartsWithoutShield() {
        val app = ApplicationProvider.getApplicationContext<AuroraApplication>()
        android.provider.Settings.Secure.putString(app.contentResolver, android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, "")
        runBlocking {
            app.runtime.update { it.copy(runs = emptyList()) }
            app.rules.update { it.copy(version = 1, configs = emptyList(), activations = emptyList(), shields = listOf(ShieldPreset("t", "Test Shield", listOf("com.example.social"), createdAt = 1))) }
        }
        launch(onboarded = true)
        scrollTo("Normal Session").performSemanticsAction(SemanticsActions.OnClick)
        awaitText("Start")
        compose.onNodeWithText("Start").performClick()
        compose.waitForIdle()
        org.junit.Assert.assertTrue(app.runtime.value!!.runs.isEmpty())
    }

    @Test
    fun goalCanBeCreatedAndDeletedWithoutStartingProtection() {
        val app = ApplicationProvider.getApplicationContext<AuroraApplication>()
        val config = UsageConfig("goal-test", "Goal Study", focus = FocusPlan("Study"))
        runBlocking {
            app.rules.update { r -> r.copy(version = 1, configs = r.configs.filterNot { it.id == config.id } + config, trackGoals = emptyList()) }
        }
        launch(onboarded = true)
        val runsBefore = app.runtime.value!!.runs
        tab("Track").performClick()
        compose.waitUntil(5_000) { compose.onAllNodes(androidx.compose.ui.test.hasContentDescription("Goals")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(androidx.compose.ui.test.hasContentDescription("Goals")).performClick()
        awaitText("Create a Goal")
        compose.onNodeWithText("Create a Goal").performClick()
        awaitText("Name your goal")
        compose.onNode(androidx.compose.ui.test.hasSetTextAction()).performTextInput("Daily learning")
        compose.onNodeWithText("Next").performClick()
        compose.onNodeWithText("A custom session").performClick()
        compose.onNodeWithText("Next").performClick()
        awaitText("Goal Study")
        compose.onNodeWithText("Goal Study").performClick()
        compose.onNodeWithText("Next").performClick()
        compose.onNodeWithText("Sessions per week").performClick()
        compose.onNodeWithText("Create goal").performClick()
        awaitText("Daily learning")
        val goal = app.rules.value!!.trackGoals.single()
        org.junit.Assert.assertEquals(3, goal.sessions)
        org.junit.Assert.assertEquals(config.id, goal.configId)
        org.junit.Assert.assertEquals(runsBefore, app.runtime.value!!.runs)
        org.junit.Assert.assertTrue(app.rules.value!!.activations.none { it.configId == config.id })
        scrollTo("Delete goal").performSemanticsAction(SemanticsActions.OnClick)
        awaitText("Delete Daily learning?")
        compose.onNodeWithText("Delete").performClick()
        compose.waitUntil(5_000) { app.rules.value!!.trackGoals.isEmpty() }
    }
}
