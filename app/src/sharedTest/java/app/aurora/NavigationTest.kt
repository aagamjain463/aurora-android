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
import app.aurora.plan.EntitlementPolicy
import app.aurora.progress.Goal
import app.aurora.progress.GoalKind
import app.aurora.progress.GoalPeriod
import app.aurora.progress.GoalStatus
import app.aurora.protect.Activation
import app.aurora.protect.FreeKeep
import app.aurora.protect.Timing
import app.aurora.plan.ProtectedItem
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

    /**
     * Start session asks one thing per page: a name (and whether to save it), the Shield, when, then how long.
     * Leaves the flow on How long with 30 minutes chosen, so a test can go on to Review.
     */
    private fun nextPages(name: String = "Test", save: Boolean = false) {
        awaitText("Name this session")
        compose.onAllNodes(androidx.compose.ui.test.hasSetTextAction())[0].performTextInput(name)
        if (save) compose.onNodeWithText("Save this session").performClick()
        compose.onNodeWithText("Next").performClick()
        scrollTo("Test Shield").performClick()
        compose.onNodeWithText("Next").performClick()
        scrollTo("Start now").performClick()
        compose.onNodeWithText("Next").performClick()
        scrollTo("30m").performClick()
    }

    /** From How long to Review. */
    private fun review() {
        compose.onNodeWithText("Next").performClick()
        awaitText("Review")
    }

    /** Home is a lazy list: bring a node into composition before looking for it. */
    private fun scrollTo(text: String, substring: Boolean = false): androidx.compose.ui.test.SemanticsNodeInteraction {
        compose.waitUntil(5_000) { compose.onAllNodes(androidx.compose.ui.test.hasScrollAction()).fetchSemanticsNodes().isNotEmpty() }
        compose.waitUntil(5_000) {
            runCatching {
                compose.onAllNodes(androidx.compose.ui.test.hasScrollAction())[0].performScrollToNode(hasText(text, substring = substring))
            }.isSuccess
        }
        return compose.onNodeWithText(text, substring)
    }

    private fun tab(label: String) = compose.onNode(hasText(label) and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab))

    @Test
    fun onboardingCompletesOnHome() {
        shieldOn()
        launch(onboarded = false)
        // Onboarding picks nothing: the welcome, how Aurora works, each tab in turn, then Shield.
        awaitText("Get started")
        compose.onNodeWithText("Get started").performClick()
        repeat(5) {
            awaitText("Continue")
            compose.onNodeWithText("Continue").performClick()
        }
        awaitText("Go to Home")
        compose.onNodeWithText("Go to Home").performClick()
        scrollTo("Start session").assertIsDisplayed()
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
        scrollTo("Start session").assertIsDisplayed()
    }

    /**
     * Founder scenario A through the real nav graph and DataStore: Start session, saved as "Study" and started
     * for 30 minutes, shown as active on Home, then turned off. The saved session stays for next time.
     */
    @Test
    fun savedSessionStartsAndTurnsOff() {
        val app = ApplicationProvider.getApplicationContext<AuroraApplication>()
        shieldOn()
        runBlocking {
            app.runtime.update { it.copy(runs = emptyList()) }
            app.rules.update { it.copy(version = 1, configs = emptyList(), activations = emptyList(), shields = listOf(ShieldPreset("t", "Test Shield", listOf("com.example.social"), createdAt = 1))) }
        }
        launch(onboarded = true)
        // The 320x470 JVM viewport can put the row centre behind the floating dock.
        scrollTo("Start session").performSemanticsAction(SemanticsActions.OnClick)
        // One question per page: name and save, the Shield, when and how long, then Review.
        nextPages("Study", save = true)
        review()
        compose.onNodeWithText("Start").assertIsEnabled().performClick()
        // Fetching nodes syncs with the main thread, where each step of the activation resumes.
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Home").fetchSemanticsNodes().isNotEmpty() && app.runtime.value?.runs?.size == 1 }
        val runId = app.runtime.value!!.runs.single().id
        org.junit.Assert.assertEquals(listOf("Study"), app.rules.value!!.configs.filter { it.custom }.map { it.name })
        scrollTo("Turn off").performSemanticsAction(SemanticsActions.OnClick)
        compose.onNode(hasText("Turn off") and hasAnyAncestor(isDialog())).performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Turn off").fetchSemanticsNodes().isEmpty() && app.history.value?.runs?.isNotEmpty() == true }
        org.junit.Assert.assertTrue(app.runtime.value!!.runs.isEmpty())
        org.junit.Assert.assertEquals("Study", app.history.value!!.runs.single { it.id == runId }.name)
        scrollTo("Saved Sessions").assertIsDisplayed()
        runBlocking { app.rules.update { it.copy(configs = emptyList()) } }
    }

    /** Start session pages through name, Shield and time, then Start. Unsaved, it leaves nothing behind. */
    @Test
    fun normalSessionStartsFromOneScreen() {
        val app = ApplicationProvider.getApplicationContext<AuroraApplication>()
        shieldOn()
        runBlocking {
            app.runtime.update { it.copy(runs = emptyList()) }
            app.rules.update { it.copy(version = 1, configs = emptyList(), activations = emptyList(), shields = listOf(ShieldPreset("t", "Test Shield", listOf("com.example.social"), createdAt = 1))) }
        }
        launch(onboarded = true)
        scrollTo("Start session").performSemanticsAction(SemanticsActions.OnClick)
        nextPages()
        review()
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

    /** Skipping the tour leads to Shield, which says what it needs; nothing has started. */
    @Test
    fun onboardingTourSkipsToShield() {
        val app = ApplicationProvider.getApplicationContext<AuroraApplication>()
        android.provider.Settings.Secure.putString(app.contentResolver, android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, "")
        runBlocking {
            app.runtime.update { it.copy(runs = emptyList()) }
            app.preferences.setOnboarded(false)
            app.preferences.setOnboardingStep(1)
            app.preferences.setReduceMotion(true)
        }
        scenario = ActivityScenario.launch(MainActivity::class.java)
        awaitText("Skip")
        compose.onNodeWithText("Skip").performClick()
        awaitText("Turn on Shield.")
        org.junit.Assert.assertTrue(app.runtime.value!!.runs.isEmpty())
        awaitText("Skip for now")
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
        scrollTo("Start session").performSemanticsAction(SemanticsActions.OnClick)
        nextPages()
        review()
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
        compose.waitUntil(5_000) { compose.onAllNodes(androidx.compose.ui.test.hasContentDescription("Habits")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(androidx.compose.ui.test.hasContentDescription("Habits")).performClick()
        awaitText("Create a Habit")
        compose.onNodeWithText("Create a Habit").performClick()
        awaitText("Name your habit")
        compose.onNode(androidx.compose.ui.test.hasSetTextAction()).performTextInput("Daily learning")
        compose.onNodeWithText("Next").performClick()
        compose.onNodeWithText("A custom session").performClick()
        compose.onNodeWithText("Next").performClick()
        awaitText("Goal Study")
        compose.onNodeWithText("Goal Study").performClick()
        compose.onNodeWithText("Next").performClick()
        // The hint above the disabled button leaves the 320x470 JVM viewport less room: scroll to the choice.
        compose.onNode(androidx.compose.ui.test.hasScrollToNodeAction()).performScrollToNode(hasText("Sessions per week"))
        compose.onNodeWithText("Sessions per week").performClick()
        compose.onNodeWithText("Create habit").performClick()
        awaitText("Daily learning")
        val goal = app.rules.value!!.trackGoals.single()
        org.junit.Assert.assertEquals(3, goal.sessions)
        org.junit.Assert.assertEquals(config.id, goal.configId)
        org.junit.Assert.assertEquals(runsBefore, app.runtime.value!!.runs)
        org.junit.Assert.assertTrue(app.rules.value!!.activations.none { it.configId == config.id })
        // Delete waits behind the card's one quiet control.
        compose.onNode(androidx.compose.ui.test.hasContentDescription("More for Daily learning")).performScrollTo().performSemanticsAction(SemanticsActions.OnClick)
        awaitText("Delete habit")
        compose.onNodeWithText("Delete habit").performClick()
        awaitText("Delete Daily learning?")
        compose.onNodeWithText("Delete").performClick()
        compose.waitUntil(5_000) { app.rules.value!!.trackGoals.isEmpty() }
    }

    private fun cleanPlanState(app: AuroraApplication) = runBlocking {
        app.runtime.reset()
        app.rules.update { it.copy(version = 1, shields = emptyList(), configs = emptyList(), activations = emptyList(), trackGoals = emptyList(), limits = emptyList(), keep = FreeKeep()) }
        app.limitPrompt.value = null
    }

    private fun apps(range: IntRange) = range.map { "com.example.app$it" }

    @Test
    fun aSixthTargetIsExplainedChangesNothingAndLeadsToPlus() {
        val app = ApplicationProvider.getApplicationContext<AuroraApplication>()
        cleanPlanState(app)
        launch(onboarded = true)
        fun session(keys: List<String>) = UsageConfig(keys.first(), "S", custom = false, shield = ShieldPreset(keys.first(), "S", keys), createdAt = 1)
        runBlocking {
            // Standing protection holds the five slots (one session runs at a time).
            org.junit.Assert.assertNull(app.activate(session(apps(1..5)), Timing.Always))
            org.junit.Assert.assertNotNull(app.activate(session(apps(6..6)), Timing.Now(30)))
        }
        awaitText("That\'s the most for now")
        val runs = app.runtime.value!!.runs.size
        // Cancelling can't continue the action: it never happened.
        compose.onNodeWithText("Cancel").performClick()
        compose.waitUntil(5_000) { app.limitPrompt.value == null }
        org.junit.Assert.assertEquals(runs, app.runtime.value!!.runs.size)
        // Upgrading goes to the plan comparison, which matches the rule.
        app.limitPrompt.value = LimitPrompt(EntitlementPolicy.canActivateShieldTarget(app.plan, apps(1..5).toSet(), apps(6..6).toSet()))
        awaitText("See Aurora Plus")
        compose.onNodeWithText("See Aurora Plus").performClick()
        awaitText("Active Shield apps and sites")
    }

    @Test
    fun aSecondGoalNamesTheActiveOneAndSwitchingKeepsItsHistory() {
        val app = ApplicationProvider.getApplicationContext<AuroraApplication>()
        cleanPlanState(app)
        val first = Goal("first", "Read daily", GoalKind.Limit, GoalPeriod.Daily, 30, createdAt = System.currentTimeMillis())
        runBlocking { org.junit.Assert.assertTrue(app.saveGoal(first).allowed) }
        launch(onboarded = true)
        tab("Track").performClick()
        compose.waitUntil(5_000) { compose.onAllNodes(androidx.compose.ui.test.hasContentDescription("Habits")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(androidx.compose.ui.test.hasContentDescription("Habits")).performClick()
        awaitText("Create a Habit")
        compose.onNodeWithText("Create a Habit").performClick()
        awaitText("Name your habit")
        compose.onNode(androidx.compose.ui.test.hasSetTextAction()).performTextInput("Second goal")
        compose.onNodeWithText("Next").performClick()
        compose.onNodeWithText("A time limit").performClick()
        compose.onNodeWithText("Next").performClick()
        compose.onNodeWithText("Whole screen time").performClick()
        compose.onNodeWithText("Next").performClick()
        compose.onNodeWithText("30m").performClick()
        compose.onNodeWithText("Create habit").performClick()
        awaitText("Read daily is your active habit", substring = true)
        org.junit.Assert.assertEquals(1, app.rules.value!!.trackGoals.size)
        compose.onNodeWithText("Archive it").performSemanticsAction(SemanticsActions.OnClick)
        awaitText("Second goal")
        val goals = app.rules.value!!.trackGoals.associateBy { it.name }
        org.junit.Assert.assertEquals(GoalStatus.Archived, goals.getValue("Read daily").status)
        org.junit.Assert.assertEquals(GoalStatus.Active, goals.getValue("Second goal").status)
    }

    @Test
    fun overFreesLimitsHomeShowsNoPausedNotice() {
        val app = ApplicationProvider.getApplicationContext<AuroraApplication>()
        cleanPlanState(app)
        // What Plus leaves behind: more switched on than Free covers. Nothing is deleted.
        val preset = ShieldPreset("all", "All", apps(1..8))
        val config = UsageConfig("all", "All", shieldId = "all", createdAt = 1)
        runBlocking {
            app.rules.update { it.copy(shields = listOf(preset), configs = listOf(config), activations = listOf(Activation("a", "all", Timing.Always, true, 1))) }
        }
        launch(onboarded = true)
        // Free enforces the oldest and marks the rest where they live; Home raises no alarm about it.
        scrollTo("Start session")
        org.junit.Assert.assertTrue(compose.onAllNodesWithText("Some of your setup is paused").fetchSemanticsNodes().isEmpty())
        org.junit.Assert.assertEquals(8, app.rules.value!!.shields.single().keys.size)
    }
}
