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

/** Starting a saved session asks only for its unlocks, when and how long: the rest is changed in Edit. */
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

    /** Starting a saved session: its unlocks first, then when, how long, and Review, whose action starts it. */
    @Test
    fun savedStartGoesUnlocksWhenTimingThenReview() {
        configure("c1", custom = true, startSaved = true)
        rule.waitUntil(5_000) { rule.onAllNodes(hasText("Passes")).fetchSemanticsNodes().isNotEmpty() }
        assertNoNameOrContents()
        // The rest of it can be viewed here, not changed.
        // The session itself sits in one row above its passes; a tap shows every part.
        rule.onNode(androidx.compose.ui.test.SemanticsMatcher("opens details") { (if (androidx.compose.ui.semantics.SemanticsActions.OnClick in it.config) it.config[androidx.compose.ui.semantics.SemanticsActions.OnClick].label else null) == "View details" }).assertIsDisplayed()
        rule.onNodeWithText("Next").performClick()
        rule.waitUntil(5_000) { rule.onAllNodes(hasText("When should it apply?")).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("Start now").performClick()
        rule.onNodeWithText("Next").performClick()
        rule.waitUntil(5_000) { rule.onAllNodes(hasText("How long?", substring = true, ignoreCase = true)).fetchSemanticsNodes().isNotEmpty() }
        assertNoNameOrContents()
        rule.onNodeWithText("Next").performClick()
        rule.waitUntil(5_000) { rule.onAllNodes(hasText("Review")).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("Start").assertIsDisplayed()
    }
}
