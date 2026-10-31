package app.aurora.ui

import app.aurora.ui.components.Opening
import app.aurora.ui.components.opensWithFirstLight
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
    fun onlyARealArrivalOpens() {
        assertTrue(opensWithFirstLight(recreated = false, awayMs = null, touchExploring = false)) // Cold.
        assertTrue(opensWithFirstLight(recreated = false, awayMs = 6 * 60_000L, touchExploring = false)) // Shield kept the process.
        assertFalse(opensWithFirstLight(recreated = false, awayMs = 30_000L, touchExploring = false)) // Back from Settings.
        assertFalse(opensWithFirstLight(recreated = true, awayMs = null, touchExploring = false)) // Rotated, or restored.
        assertFalse(opensWithFirstLight(recreated = false, awayMs = null, touchExploring = true)) // TalkBack: nothing covers the page.
    }

    @Test
    fun slowFramesCannotStretchTheOpening() {
        var p = 0f
        var frames = 0
        // A device managing 300 ms a frame still finishes on time, not 47 frames later.
        while (p < 1f) { p = stepOpening(p, 0.3f, 1550); frames++ }
        assertEquals(6, frames)
        assertEquals(1f, stepOpening(0.99f, 0.3f, 1550), 0f)
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
