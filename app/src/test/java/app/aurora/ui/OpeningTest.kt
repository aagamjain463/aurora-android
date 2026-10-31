package app.aurora.ui

import app.aurora.ui.components.Opening
import app.aurora.ui.components.stepOpening
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OpeningTest {
    @After fun reset() = Opening.finish()

    @Test
    fun screensWaitOnlyWhileTheNightCovers() = runBlocking {
        withTimeout(1000) { Opening.awaitParted() } // Nothing playing: tests and reduced motion never wait.
        Opening.play()
        assertTrue(Opening.playing && Opening.covering)
        val waiting = async { Opening.awaitParted() }
        Opening.part()
        withTimeout(1000) { waiting.await() }
        assertTrue(Opening.playing)
        val sky = async { Opening.awaitSkyTurn() }
        Opening.leave()
        withTimeout(1000) { sky.await() }
        Opening.finish()
        assertFalse(Opening.playing || Opening.covering)
    }

    @Test
    fun aStalledFrameCannotJump() {
        val normal = stepOpening(0.2f, 1f, 1f / 60f, 2800)
        val stalled = stepOpening(0.2f, 1f, 0.5f, 2800)
        assertEquals(0.2f + (1f / 30f) * 1000f / 2800, stalled, 0.0001f)
        assertTrue(normal < stalled)
    }

    @Test
    fun stepsStopAtTheTargetFromEitherSide() {
        assertEquals(0.4f, stepOpening(0.399f, 0.4f, 1f / 30f, 500), 0f)
        assertEquals(0.4f, stepOpening(0.401f, 0.4f, 1f / 30f, 500), 0f)
    }
}

class CountedTextTest {
    @Test
    fun everyRunOfDigitsCountsAndEndsExact() {
        assertEquals("0h 10m", app.aurora.ui.components.countedText("1h 20m", 0.5f))
        assertEquals("1h 20m", app.aurora.ui.components.countedText("1h 20m", 1f))
        assertEquals("—", app.aurora.ui.components.countedText("—", 0.5f))
    }
}
