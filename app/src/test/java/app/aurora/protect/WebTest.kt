package app.aurora.protect

import app.aurora.R
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
    fun instagramKeepsChatsStoriesAndOnlyWhatFriendsSend() {
        fun v(s: InstagramScreen) = instagramVerdict(s, reels = false, messages = true, feed = false, search = false, screenBottom = 2400)
        fun leave(says: Int) = InstagramVerdict.Leave(PartInstagramMessages, says)
        val home = InstagramScreen(tab = "feed_tab", posts = true, headerBottom = 200, storiesBottom = 480, tabBarTop = 2200)
        // Home: everything under the stories row is covered; once the row scrolls away, from the header.
        assertEquals(InstagramVerdict.Cover(480, 2200, PartInstagramMessages), v(home))
        assertEquals(InstagramVerdict.Cover(200, 2200, PartInstagramMessages), v(home.copy(storiesBottom = null, posts = false)))
        // Search is held back like the Reels tab: a toast, not a covered screen.
        assertEquals(leave(R.string.part_no_search), v(InstagramScreen(tab = "search_tab", tabBarTop = 2200)))
        assertEquals(leave(R.string.part_no_reels), v(InstagramScreen(tab = "clips_tab", reelViewer = true)))
        // The reel a friend sent plays; the "Suggested" reels after it, or any other reel, don't.
        assertEquals(InstagramVerdict.Allow, v(InstagramScreen(reelViewer = true, sentInChat = true)))
        assertEquals(leave(R.string.part_no_other_reels), v(InstagramScreen(reelViewer = true)))
        assertEquals(leave(R.string.part_no_reels), v(InstagramScreen(reelsPage = true)))
        // A profile shows its header, never its grid: someone else's, or your own.
        assertEquals(InstagramVerdict.Cover(774, 2400, PartInstagramMessages), v(InstagramScreen(profileGridTop = 774)))
        assertEquals(InstagramVerdict.Cover(907, 2200, PartInstagramMessages), v(InstagramScreen(tab = "profile_tab", profileGridTop = 907, tabBarTop = 2200)))
        // A post opens only from a chat.
        assertEquals(InstagramVerdict.Allow, v(InstagramScreen(posts = true, fromChat = true)))
        assertEquals(leave(R.string.part_no_posts), v(InstagramScreen(posts = true)))
        // Stories, chats and the inbox stay.
        assertEquals(InstagramVerdict.Allow, v(home.copy(storyViewer = true)))
        assertEquals(InstagramVerdict.Allow, v(InstagramScreen(tab = "direct_tab")))
        assertEquals(InstagramVerdict.Allow, v(InstagramScreen()))
    }

    @Test
    fun instagramReelsTabIsJustTheTab() {
        fun v(s: InstagramScreen) = instagramVerdict(s, reels = true, messages = false, feed = false, search = false, screenBottom = 2400)
        assertEquals(InstagramVerdict.Leave(PartReels, R.string.part_no_reels), v(InstagramScreen(tab = "clips_tab", reelViewer = true)))
        assertEquals(InstagramVerdict.Allow, v(InstagramScreen(reelViewer = true)))
        assertEquals(InstagramVerdict.Allow, v(InstagramScreen(tab = "feed_tab", posts = true, tabBarTop = 2200)))
        assertEquals(InstagramVerdict.Allow, v(InstagramScreen(profileGridTop = 913)))
        // The older parts still hold: posts, and Search.
        val home = InstagramScreen(tab = "feed_tab", posts = true, headerBottom = 200, storiesBottom = 480, tabBarTop = 2200)
        assertEquals(InstagramVerdict.Cover(480, 2200, PartInstagramFeed), instagramVerdict(home, false, false, feed = true, search = false, screenBottom = 2400))
        assertEquals(
            InstagramVerdict.Leave(PartInstagramSearch, R.string.part_no_search),
            instagramVerdict(InstagramScreen(tab = "search_tab", tabBarTop = 2200), false, false, false, search = true, screenBottom = 2400),
        )
    }

    @Test
    fun youTubeWithoutRecommendations() {
        fun v(s: YouTubeScreen, shorts: Boolean = false, feed: Boolean = true) = youTubeVerdict(s, shorts, feed, null, screenWidth = 1080, screenBottom = 2400)
        // The Shorts tab: only a Short in the Shorts tab leaves; one opened from Home or Search plays.
        assertEquals(YouTubeVerdict.Leave(PartShorts, R.string.part_no_shorts), v(YouTubeScreen(shorts = true, shortsTab = true), shorts = true, feed = false))
        assertEquals(YouTubeVerdict.Allow, v(YouTubeScreen(shorts = true), shorts = true, feed = false))
        // Shorts, feed and recommendations: every Short leaves.
        assertEquals(YouTubeVerdict.Leave(PartYouTubeFeed, R.string.part_no_shorts), v(YouTubeScreen(shorts = true)))
        // The list under the video, as YouTube lays it out, and the "Suggested video" over the ended player: one cover.
        assertEquals(
            YouTubeVerdict.Cover(listOf(0, 76, 1080, 2278), PartYouTubeFeed),
            v(YouTubeScreen(watch = listOf(0, 76, 1080, 684), related = listOf(listOf(0, 684, 1080, 2278), listOf(0, 76, 1080, 684)))),
        )
        // Full screen: the row that slides up is covered, the video isn't.
        assertEquals(
            YouTubeVerdict.Cover(listOf(130, 700, 2278, 1080), PartYouTubeFeed),
            youTubeVerdict(YouTubeScreen(watch = listOf(0, 0, 2408, 1080), related = listOf(listOf(130, 700, 2278, 1080))), false, true, null, 2408, 1080),
        )
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
        assertEquals(YouTubeVerdict.NotFromChannel(key, R.string.part_only_channels), v(YouTubeScreen(watch = watch, texts = listOf("Some video", "Other Channel"))))
        assertEquals(YouTubeVerdict.NotFromChannel(key, R.string.part_only_channels), v(YouTubeScreen(shorts = true, texts = listOf("@mkbhdclips"))))
        // Still loading, or full screen: nothing to judge yet.
        assertEquals(YouTubeVerdict.Allow, v(YouTubeScreen(watch = watch)))
        // Browsing isn't watching.
        assertEquals(YouTubeVerdict.Allow, v(YouTubeScreen(texts = listOf("Other Channel"))))
        // Whole names only.
        assertFalse(fromChannels(listOf("United Nations"), listOf("ted")))
        assertTrue(fromChannels(listOf("TED · 25M subscribers"), listOf("TED")))
    }
}
