package app.aurora.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import app.aurora.R
import app.aurora.ui.components.AuroraButton
import app.aurora.ui.components.AuroraDialog
import app.aurora.ui.components.ButtonKind
import app.aurora.ui.components.Notice
import app.aurora.ui.theme.Aurora
import app.aurora.ui.theme.AuroraTheme
import app.aurora.ui.theme.Space
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.assertEquals
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w360dp-h640dp-xhdpi")
class FeedbackTest {
    @get:Rule val compose = createComposeRule()

    @After fun resetFontScale() = RuntimeEnvironment.setFontScale(1f)

    private fun show(fontScale: Float = 1f, content: @Composable () -> Unit) {
        RuntimeEnvironment.setFontScale(fontScale)
        compose.setContent {
            AuroraTheme(reduceMotion = true) {
                Box(Modifier.fillMaxSize().background(Aurora.colors.background)) { content() }
            }
        }
    }

    private fun pausedNotice(fontScale: Float, name: String) {
        var opened = 0
        show(fontScale) {
            Notice(stringResource(R.string.status_permission_title), stringResource(R.string.status_permission_body), Modifier.padding(horizontal = Space.M)) {
                AuroraButton(stringResource(R.string.status_turn_on), { opened++ }, Modifier.fillMaxWidth(), kind = ButtonKind.Secondary)
            }
        }
        compose.onNodeWithText("Turn on Shield").assertIsDisplayed().performClick()
        assertEquals(1, opened)
        compose.onRoot().captureRoboImage("src/test/screenshots/feedback/$name.png")
    }

    @Test fun pausedNotice() = pausedNotice(1f, "paused_notice")

    @Test fun pausedNoticeLargeText() = pausedNotice(2f, "paused_notice_large_text")

    @Test fun standardDialog() {
        show {
            AuroraDialog(
                title = stringResource(R.string.disclosure_title),
                body = stringResource(R.string.disclosure_body),
                confirmLabel = "Open settings", onConfirm = {},
                dismissLabel = "Not now", onDismiss = {},
            )
        }
        compose.onNodeWithText("Open settings").assertIsDisplayed()
        compose.onNodeWithText("Not now").assertIsDisplayed()
        compose.onNode(isDialog()).captureRoboImage("src/test/screenshots/feedback/dialog.png")
    }

    @Test fun longDialogKeepsActionsVisibleAtLargeText() {
        var confirmed = 0
        var dismissed = 0
        show(2f) {
            AuroraDialog(
                title = stringResource(R.string.disclosure_title),
                body = stringResource(R.string.disclosure_body),
                confirmLabel = "Open settings", onConfirm = { confirmed++ },
                dismissLabel = "Not now", onDismiss = { dismissed++ },
            ) { Text("End of disclosure", color = Aurora.colors.textPrimary) }
        }
        compose.onNodeWithText("Open settings").assertIsDisplayed().performClick()
        compose.onNodeWithText("Not now").assertIsDisplayed().performClick()
        assertEquals(1, confirmed)
        assertEquals(1, dismissed)
        compose.onNode(isDialog()).captureRoboImage("src/test/screenshots/feedback/dialog_large_text.png")
        compose.onNodeWithText("End of disclosure").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Open settings").assertIsDisplayed()
    }

    @Test fun dialogWithOnlyDismissAction() {
        var dismissed = 0
        show {
            AuroraDialog("Confirm your account", { dismissed++ }, "", {}, dismissLabel = "Cancel") {
                Text("Waiting for confirmation", color = Aurora.colors.textPrimary)
            }
        }
        compose.onNodeWithText("Waiting for confirmation").assertIsDisplayed()
        compose.onNodeWithText("Cancel").assertIsDisplayed().performClick()
        assertEquals(1, dismissed)
        compose.onNode(isDialog()).captureRoboImage("src/test/screenshots/feedback/dialog_dismiss_only.png")
    }

    @Test fun pendingDialogWithoutActions() {
        show { AuroraDialog("Confirming session", {}, "", {}, body = "Waiting for confirmation") }
        compose.onNodeWithText("Confirming session").assertIsDisplayed()
        compose.onNodeWithText("Waiting for confirmation").assertIsDisplayed()
    }
}
