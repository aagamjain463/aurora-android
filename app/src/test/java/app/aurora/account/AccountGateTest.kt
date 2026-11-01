package app.aurora.account

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.aurora.MainActivity
import app.aurora.TestAuroraApplication
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The gate, through the real activity. */
@RunWith(AndroidJUnit4::class)
class AccountGateTest {
    @get:Rule val compose = createEmptyComposeRule()
    private var scenario: ActivityScenario<MainActivity>? = null
    private val app get() = ApplicationProvider.getApplicationContext<TestAuroraApplication>()

    private fun open(signedIn: Boolean, onboarded: Boolean = true) {
        runBlocking {
            app.preferences.setOnboarded(onboarded)
            app.preferences.setOnboardingStep(0)
            app.preferences.setReduceMotion(true)
            if (!signedIn) app.fakeIdentity.signOut()
        }
        scenario = ActivityScenario.launch(MainActivity::class.java)
    }

    @After fun close() { scenario?.close() }

    private fun await(text: String) = compose.waitUntil(5_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
    private fun absent(text: String, substring: Boolean = false) = assertTrue("\"$text\" must not show", compose.onAllNodesWithText(text, substring).fetchSemanticsNodes().isEmpty())

    @Test
    fun signedOutShowsOnlySignInAndNoWayAround() {
        open(signedIn = false)
        await("Continue with Google")
        compose.onNodeWithText("Continue with Email").assertIsDisplayed()
        compose.onNodeWithText("Terms of Service").assertExists()
        compose.onNodeWithText("Privacy Policy").assertExists()
        for (word in listOf("guest", "skip", "without an account", "later")) absent(word, substring = true)
        for (tab in listOf("Home", "Shield", "Intent", "Track", "Start blocking")) absent(tab)
    }

    @Test
    fun firstRunWelcomesThenAsksForAnAccount() {
        open(signedIn = false, onboarded = false)
        await("Begin")
        compose.onNodeWithText("Begin").performClick()
        await("Continue with Google")
    }

    @Test
    fun signedInReachesHome() {
        open(signedIn = true)
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("Home")).fetchSemanticsNodes().isNotEmpty() }
        absent("Continue with Google")
    }

    @Test
    fun signingOutFromTheAppReturnsToSignIn() {
        open(signedIn = true)
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("Home")).fetchSemanticsNodes().isNotEmpty() }
        runBlocking { app.account.signOut() }
        await("Continue with Google")
        absent("Home")
    }

    @Test
    fun cancellingGoogleSaysNothingAndStaysPut() {
        open(signedIn = false)
        await("Continue with Google")
        app.fakeIdentity.googleResult = AuthFailure.Cancelled
        compose.onNodeWithText("Continue with Google").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Continue with Google").assertExists()
        absent("Please try again", substring = true)
    }

    @Test
    fun googleFailureExplainsItself() {
        open(signedIn = false)
        await("Continue with Google")
        app.fakeIdentity.googleResult = AuthFailure.Network
        compose.onNodeWithText("Continue with Google").performClick()
        await("Aurora can't reach the internet. Check your connection and try again.")
    }

    @Test
    fun googleSuccessGoesIn() {
        open(signedIn = false)
        await("Continue with Google")
        app.fakeIdentity.googleResult = null
        compose.onNodeWithText("Continue with Google").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Continue with Google").fetchSemanticsNodes().isEmpty() }
    }

    @Test
    fun theEmailScreensLeadToTheInbox() {
        open(signedIn = false)
        await("Continue with Email")
        compose.onNodeWithText("Continue with Email").performClick()
        await("What's your email?")
        runBlocking { app.account.sendEmailLink("a@b.co") }
        await("Check your inbox")
        compose.onNodeWithText("Change email").assertExists()
        compose.onNodeWithText("Change email").performClick()
        await("What's your email?")
    }
}
