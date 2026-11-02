package app.aurora.protect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WebTest {
    @Test
    fun hostsComeFromWhatBrowsersShow() {
        assertEquals("reddit.com", hostOf("reddit.com/r/android"))
        assertEquals("m.youtube.com", hostOf("https://m.youtube.com/watch?v=1"))
        assertEquals("example.com", hostOf("http://user@Example.com:8080/x"))
        assertNull(hostOf("how to focus better"))
        assertNull(hostOf("localhost"))
        assertNull(hostOf(""))
    }

    @Test
    fun aDomainCoversItsSubdomainsOnly() {
        val keys = listOf("web:reddit.com", "web:old.reddit.com", "com.app")
        assertEquals("web:reddit.com", webKeyFor("reddit.com", keys))
        assertEquals("web:reddit.com", webKeyFor("m.reddit.com", keys))
        assertEquals("web:old.reddit.com", webKeyFor("old.reddit.com", keys))
        assertNull(webKeyFor("notreddit.com", keys))
        assertNull(webKeyFor("reddit.co", keys))
    }

    @Test
    fun instagramKeepsMessagesStoriesAndSentReels() {
        fun v(s: InstagramScreen, reels: Boolean = true, feed: Boolean = true) = instagramVerdict(s, reels, feed, screenBottom = 2400)
        val home = InstagramScreen(tab = "feed_tab", posts = true, headerBottom = 200, storiesBottom = 480, tabBarTop = 2200)
        // Home: posts covered from below the stories row to the tab bar; once the row scrolls away, from the header.
        assertEquals(InstagramVerdict.Cover(480, 2200, search = false), v(home))
        assertEquals(InstagramVerdict.Cover(200, 2200, search = false), v(home.copy(storiesBottom = null)))
        assertEquals(InstagramVerdict.Allow, v(home, feed = false))
        // A story is never covered.
        assertEquals(InstagramVerdict.Allow, v(home.copy(storyViewer = true)))
        // Search is covered whole.
        assertEquals(InstagramVerdict.Cover(0, 2200, search = true), v(InstagramScreen(tab = "search_tab", tabBarTop = 2200)))
        // The Reels tab goes back; a reel sent in a message plays once.
        assertEquals(InstagramVerdict.Leave, v(InstagramScreen(tab = "clips_tab", reelViewer = true)))
        assertEquals(InstagramVerdict.OneReel, v(InstagramScreen(tab = "direct_tab", reelViewer = true)))
        assertEquals(InstagramVerdict.OneReel, v(InstagramScreen(reelViewer = true)))
        assertEquals(InstagramVerdict.Allow, v(InstagramScreen(reelViewer = true), reels = false))
        // Messages and profiles stay.
        assertEquals(InstagramVerdict.Allow, v(InstagramScreen(tab = "direct_tab")))
        assertEquals(InstagramVerdict.Allow, v(InstagramScreen(tab = "profile_tab", posts = true)))
    }
}
