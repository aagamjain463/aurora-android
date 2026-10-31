package app.aurora.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.aurora.ui.protect.TimeEntry
import app.aurora.ui.theme.AuroraTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TimeEntryTest {
    @get:Rule val rule = createComposeRule()

    @Test
    fun minutesTypedOneDigitAtATimeStayAsTyped() {
        var minute by mutableIntStateOf(7 * 60)
        rule.setContent { AuroraTheme(reduceMotion = true) { TimeEntry("Start", minute) { minute = it } } }
        val minutes = rule.onAllNodes(hasSetTextAction())[1]
        minutes.performTextClearance()
        minutes.performTextInput("5")
        minutes.performTextInput("4")
        rule.runOnIdle { assertEquals(7 * 60 + 54, minute) }
    }
}
