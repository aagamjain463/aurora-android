package app.aurora.protect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
        fun v(s: InstagramScreen, reels: Boolean = false, messages: Boolean = true, feed: Boolean = false, search: Boolean = false) =
            instagramVerdict(s, reels, messages, feed, search, screenBottom = 2400)
        val home = InstagramScreen(tab = "feed_tab", posts = true, headerBottom = 200, storiesBottom = 480, tabBarTop = 2200)
        // Home: everything under the stories row is covered; once the row scrolls away, from the header.
        assertEquals(InstagramVerdict.Cover(480, 2200, PartInstagramMessages), v(home))
        assertEquals(InstagramVerdict.Cover(200, 2200, PartInstagramMessages), v(home.copy(storiesBottom = null, posts = false)))
        // Search, and your own grid under the profile header, are covered; the Reels tab goes back.
        assertEquals(InstagramVerdict.Cover(0, 2200, PartInstagramMessages), v(InstagramScreen(tab = "search_tab", tabBarTop = 2200)))
        assertEquals(InstagramVerdict.Cover(200, 2200, PartInstagramMessages), v(InstagramScreen(tab = "profile_tab", headerBottom = 200, tabBarTop = 2200)))
        assertEquals(InstagramVerdict.Leave(PartInstagramMessages), v(InstagramScreen(tab = "clips_tab", reelViewer = true)))
        // A friend's reel plays once; stories and messages stay.
        assertEquals(InstagramVerdict.OneReel, v(InstagramScreen(reelViewer = true)))
        assertEquals(InstagramVerdict.Allow, v(home.copy(storyViewer = true)))
        assertEquals(InstagramVerdict.Allow, v(InstagramScreen(tab = "direct_tab")))
        assertEquals(InstagramVerdict.Allow, v(InstagramScreen()))
    }

    @Test
    fun instagramReelsAloneIsJustTheTab() {
        fun v(s: InstagramScreen) = instagramVerdict(s, reels = true, messages = false, feed = false, search = false, screenBottom = 2400)
        assertEquals(InstagramVerdict.Leave(PartReels), v(InstagramScreen(tab = "clips_tab", reelViewer = true)))
        assertEquals(InstagramVerdict.Allow, v(InstagramScreen(reelViewer = true)))
        assertEquals(InstagramVerdict.Allow, v(InstagramScreen(tab = "feed_tab", posts = true, tabBarTop = 2200)))
        // The older parts still hold: posts, and Search.
        val home = InstagramScreen(tab = "feed_tab", posts = true, headerBottom = 200, storiesBottom = 480, tabBarTop = 2200)
        assertEquals(InstagramVerdict.Cover(480, 2200, PartInstagramFeed), instagramVerdict(home, false, false, feed = true, search = false, screenBottom = 2400))
        assertEquals(InstagramVerdict.Cover(0, 2200, PartInstagramSearch), instagramVerdict(InstagramScreen(tab = "search_tab", tabBarTop = 2200), false, false, false, search = true, screenBottom = 2400))
    }

    @Test
    fun youTubeWithoutRecommendations() {
        fun v(s: YouTubeScreen, shorts: Boolean = false, feed: Boolean = true) = youTubeVerdict(s, shorts, feed, null, screenWidth = 1080, screenBottom = 2400)
        assertEquals(YouTubeVerdict.Leave(PartShorts), v(YouTubeScreen(shorts = true), shorts = true, feed = false))
        assertEquals(YouTubeVerdict.Leave(PartYouTubeFeed), v(YouTubeScreen(shorts = true)))
        assertEquals(YouTubeVerdict.Allow, v(YouTubeScreen(shorts = true), feed = false))
        // Home's feed, under the video, beside it; full screen has nothing to cover.
        assertEquals(YouTubeVerdict.Cover(listOf(0, 300, 1080, 2250), PartYouTubeFeed), v(YouTubeScreen(homeFeed = 300 to 2250)))
        assertEquals(YouTubeVerdict.Cover(listOf(0, 700, 1080, 2400), PartYouTubeFeed), v(YouTubeScreen(watch = listOf(0, 90, 1080, 700))))
        assertEquals(YouTubeVerdict.Cover(listOf(640, 0, 1080, 2400), PartYouTubeFeed), v(YouTubeScreen(watch = listOf(0, 0, 640, 400))))
        assertEquals(YouTubeVerdict.Allow, v(YouTubeScreen(watch = listOf(0, 0, 1080, 2400))))
        // Subscriptions and Search: nothing to do.
        assertEquals(YouTubeVerdict.Allow, v(YouTubeScreen()))
    }

    @Test
    fun youTubeOnlyFromChosenChannels() {
        val key = channelsKey(listOf(" Veritasium ", "@mkbhd", "a|b", "Veritasium"))
        assertEquals("part:youtube.channels=Veritasium|@mkbhd|ab", key)
        assertEquals(listOf("Veritasium", "@mkbhd", "ab"), channelsOf(key))
        assertEquals(PartYouTubeChannels, partOf(key)?.key)
        val chosen = key to channelsOf(key)
        fun v(s: YouTubeScreen) = youTubeVerdict(s, shorts = false, feed = false, channels = chosen, screenWidth = 1080, screenBottom = 2400)
        val watch = listOf(0, 90, 1080, 700)
        assertEquals(YouTubeVerdict.Allow, v(YouTubeScreen(watch = watch, texts = listOf("Why gravity is not a force", "Veritasium · 17M subscribers"))))
        assertEquals(YouTubeVerdict.Allow, v(YouTubeScreen(shorts = true, texts = listOf("@mkbhd", "Subscribe"))))
        assertEquals(YouTubeVerdict.NotFromChannel(key), v(YouTubeScreen(watch = watch, texts = listOf("Some video", "Other Channel"))))
        assertEquals(YouTubeVerdict.NotFromChannel(key), v(YouTubeScreen(shorts = true, texts = listOf("@mkbhdclips"))))
        // Still loading, or full screen: nothing to judge yet.
        assertEquals(YouTubeVerdict.Allow, v(YouTubeScreen(watch = watch)))
        // Browsing isn't watching.
        assertEquals(YouTubeVerdict.Allow, v(YouTubeScreen(texts = listOf("Other Channel"))))
        // Whole names only.
        assertFalse(fromChannels(listOf("United Nations"), listOf("ted")))
        assertTrue(fromChannels(listOf("TED · 25M subscribers"), listOf("TED")))
    }
}
