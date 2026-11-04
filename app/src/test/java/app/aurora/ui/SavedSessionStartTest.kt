package app.aurora.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.aurora.AuroraApplication
import app.aurora.protect.Mode
import app.aurora.protect.ShieldPreset
import app.aurora.protect.UsageConfig
import app.aurora.ui.configure.ConfigureScreen
import app.aurora.ui.theme.AuroraTheme
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Starting a saved session asks only when and for how long: its name and contents are changed in Edit. */
@RunWith(AndroidJUnit4::class)
class SavedSessionStartTest {
    @get:Rule val rule = createComposeRule()

    @Before
    fun seed() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<AuroraApplication>()
        app.runtime.reset()
        app.rules.reset()
        app.rules.update {
            it.copy(
                shields = listOf(ShieldPreset("s1", "Social", listOf("com.example.photos"), Mode.Flexible, createdAt = 1)),
                configs = listOf(UsageConfig("c1", "Study Session", shieldId = "s1", createdAt = 1)),
            )
        }
        Unit
    }

    private fun configure(configId: String?, custom: Boolean, startSaved: Boolean) = rule.setContent {
        AuroraTheme(reduceMotion = true) {
            ConfigureScreen(
                custom = custom, configId = configId, forGoal = false,
                createdShield = null, createdIntent = null,
                onBack = {}, onDone = {}, onCreateShield = {}, onCreateIntent = {},
                onStrict = {}, onPlus = {}, startSaved = startSaved,
            )
        }
    }

    private fun assertNoNameOrContents() {
        assert(rule.onAllNodes(hasText("Name this session")).fetchSemanticsNodes().isEmpty())
        assert(rule.onAllNodes(hasText("What should it include?")).fetchSemanticsNodes().isEmpty())
    }

    /** Home's Start on a saved session: When, then timing, then Start. */
    @Test
    fun homeStartGoesWhenThenTimingThenStart() {
        configure("c1", custom = true, startSaved = true)
        rule.waitUntil(5_000) { rule.onAllNodes(hasText("When should it apply?")).fetchSemanticsNodes().isNotEmpty() }
        assertNoNameOrContents()
        rule.onNodeWithText("Start now").performClick()
        rule.onNodeWithText("Next").performClick()
        rule.waitUntil(5_000) { rule.onAllNodes(hasText("How long?", substring = true, ignoreCase = true)).fetchSemanticsNodes().isNotEmpty() }
        assertNoNameOrContents()
        // The timing page is the last one: its action starts the session.
        rule.onNodeWithText("Start").assertIsDisplayed()
    }

    /** Start session, then picking a saved one: the same short path. */
    @Test
    fun pickingASavedSessionSkipsNameAndContents() {
        configure(null, custom = false, startSaved = false)
        rule.waitUntil(5_000) { rule.onAllNodes(hasText("Study Session")).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("Study Session").performClick()
        rule.waitUntil(5_000) { rule.onAllNodes(hasText("When should it apply?")).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("Start now").performClick()
        rule.onNodeWithText("Next").performClick()
        assertNoNameOrContents()
        rule.onNodeWithText("Start").assertIsDisplayed()
    }
}
