package app.aurora.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.aurora.ui.components.DurationPicker
import app.aurora.ui.configure.ConfigureScreen
import app.aurora.ui.configure.When
import app.aurora.ui.theme.AuroraTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CustomSessionDurationTest {
    @get:Rule val rule = createComposeRule()

    @Test
    fun customFieldsStartWithCurrentDurationAndKeepExactValues() {
        var minutes by mutableStateOf<Int?>(60)
        rule.setContent {
            AuroraTheme(reduceMotion = true) {
                DurationPicker(minutes, { minutes = it }, listOf(15, 30, 60), customInitially = true)
            }
        }
        val hours = rule.onNodeWithContentDescription("hours")
        val remainder = rule.onNodeWithContentDescription("minutes")
        hours.assertIsDisplayed()
        remainder.assertIsDisplayed()
        remainder.performTextReplacement("17")
        rule.runOnIdle { assertEquals(77, minutes) }
        hours.performTextReplacement("25")
        rule.runOnIdle { assertNull(minutes) }
        rule.onNodeWithText("30m").performClick()
        rule.runOnIdle { assertEquals(30, minutes) }
        rule.onNodeWithText("Custom").performClick()
        hours.performTextReplacement("1")
        rule.runOnIdle { assertEquals(77, minutes) }
    }

    @Test
    fun customSessionShowsTimeFieldsAndRequiresValidDuration() {
        rule.setContent {
            AuroraTheme(reduceMotion = true) {
                ConfigureScreen(
                    custom = true, configId = null, forGoal = false,
                    createdShield = null, createdIntent = null,
                    onBack = {}, onDone = {}, onCreateShield = {}, onCreateIntent = {},
                    onStrict = {}, onPlus = {}, startStep = "Timing", startWhen = When.Now,
                )
            }
        }
        rule.waitUntil(5_000) {
            rule.onAllNodes(hasScrollAction()).fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNode(hasScrollAction()).performScrollToNode(hasContentDescription("hours"))
        val hours = rule.onNodeWithContentDescription("hours")
        hours.assertIsDisplayed()
        hours.performTextReplacement("0")
        rule.onNodeWithText("Next").assertIsNotEnabled()
        rule.onNodeWithContentDescription("minutes").performTextReplacement("7")
        rule.onNodeWithText("Next").assertIsEnabled()
    }

    @Test
    fun newSessionAsksForANameAndWhetherToSave() {
        rule.setContent {
            AuroraTheme(reduceMotion = true) {
                ConfigureScreen(
                    custom = false, configId = null, forGoal = false,
                    createdShield = null, createdIntent = null,
                    onBack = {}, onDone = {}, onCreateShield = {}, onCreateIntent = {},
                    onStrict = {}, onPlus = {},
                )
            }
        }
        rule.waitUntil(5_000) { rule.onAllNodes(hasText("Name this session")).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("Save this session").assertIsDisplayed()
        rule.onNodeWithText("Next").assertIsNotEnabled()
        rule.onNode(hasSetTextAction()).performTextReplacement("Deep work")
        rule.onNodeWithText("Next").assertIsEnabled().performClick()
        rule.onNodeWithText("At most one Shield and one Intent.").assertIsDisplayed()
    }
}
