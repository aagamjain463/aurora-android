package app.aurora

import app.aurora.ui.onboarding.onboardingPageFor
import org.junit.Assert.assertEquals
import org.junit.Test

class OnboardingPageTest {
    @Test
    fun oldWelcomeAndMotivationBecomeOnePage() {
        assertEquals(0, onboardingPageFor(0))
        assertEquals(0, onboardingPageFor(1))
    }

    @Test
    fun oldAppsModesAndSetupPagesKeepTheirOrder() {
        assertEquals(1, onboardingPageFor(2))
        assertEquals(2, onboardingPageFor(3))
        assertEquals(3, onboardingPageFor(4))
        assertEquals(3, onboardingPageFor(5))
        assertEquals(3, onboardingPageFor(6))
    }

    @Test
    fun oldFinishStaysFinish() {
        assertEquals(4, onboardingPageFor(7))
    }

    @Test
    fun anythingOutsideTheOldFlowIsClamped() {
        assertEquals(0, onboardingPageFor(-4))
        assertEquals(4, onboardingPageFor(99))
    }
}
