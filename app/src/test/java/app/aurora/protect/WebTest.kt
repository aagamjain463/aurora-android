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
    fun instagramKeepsOnlyChatsAndWhatFriendsSend() {
        fun v(s: InstagramScreen) = instagramVerdict(s, reels = false, messages = true, feed = false, search = false, screenBottom = 2400)
        fun leave(says: Int) = InstagramVerdict.Leave(PartInstagramMessages, says)
        val home = InstagramScreen(tab = "feed_tab", posts = true, headerBottom = 200, storiesBottom = 480, tabBarTop = 2200)
        // Home, stories too: all of it under the header.
        assertEquals(InstagramVerdict.Cover(200, 2200, PartInstagramMessages), v(home))
        assertEquals(InstagramVerdict.Cover(0, 2200, PartInstagramMessages), v(home.copy(headerBottom = null, posts = false)))
        // Search is held back like the Reels tab: a toast, not a covered screen.
        assertEquals(leave(R.string.part_no_search), v(InstagramScreen(tab = "search_tab", tabBarTop = 2200)))
        assertEquals(leave(R.string.part_no_reels), v(InstagramScreen(tab = "clips_tab", reelViewer = true)))
        // The reel a friend sent plays, but can't be swiped on to the "Suggested" ones; any other reel doesn't play.
        val sent = InstagramScreen(reelViewer = true, sentInChat = true, reelArea = 76 to 2113)
        assertEquals(InstagramVerdict.Gate(76, 2113, PartInstagramMessages, R.string.part_no_other_reels), v(sent))
        // With comments or the keyboard open over it, nothing is gated.
        assertEquals(InstagramVerdict.Allow, v(sent.copy(busy = true)))
        assertEquals(leave(R.string.part_no_other_reels), v(InstagramScreen(reelViewer = true)))
        assertEquals(leave(R.string.part_no_reels), v(InstagramScreen(reelsPage = true)))
        // A profile shows its header, never its grid: someone else's, or your own.
        assertEquals(InstagramVerdict.Cover(774, 2400, PartInstagramMessages), v(InstagramScreen(profileGridTop = 774)))
        assertEquals(InstagramVerdict.Cover(907, 2200, PartInstagramMessages), v(InstagramScreen(tab = "profile_tab", profileGridTop = 907, tabBarTop = 2200)))
        // A post opens only from a chat.
        assertEquals(InstagramVerdict.Allow, v(InstagramScreen(posts = true, fromChat = true)))
        assertEquals(InstagramVerdict.Cover(0, 2400, PartInstagramMessages), v(InstagramScreen(posts = true)))
        // Only the sent post: the profile's other posts under it are covered, below the header at the least.
        assertEquals(InstagramVerdict.Cover(1500, 2200, PartInstagramMessages), v(InstagramScreen(posts = true, fromChat = true, laterPostsTop = 1500, headerBottom = 230, tabBarTop = 2200)))
        assertEquals(InstagramVerdict.Cover(230, 2400, PartInstagramMessages), v(InstagramScreen(posts = true, fromChat = true, laterPostsTop = 0, headerBottom = 230)))
        // Stories are held back; chats and the inbox stay.
        assertEquals(leave(R.string.part_no_stories), v(home.copy(storyViewer = true)))
        assertEquals(InstagramVerdict.Allow, v(InstagramScreen(chat = true)))
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
        // Under the video: details and comments stay (taps only, no scrolling), every suggestion is blank, and the
        // playlist bar stays usable. The "Suggested video" over the ended player is blank too.
        val watchPage = YouTubeScreen(watch = listOf(0, 76, 1080, 684), watchList = listOf(0, 684, 1080, 2278), suggestionsTop = 1302)
        assertEquals(
            YouTubeVerdict.Cover(listOf(listOf(0, 1302, 1080, 2278)), PartYouTubeFeed, gate = listOf(0, 684, 1080, 1302)),
            v(watchPage),
        )
        assertEquals(
            YouTubeVerdict.Cover(listOf(listOf(0, 1302, 1080, 1950), listOf(0, 2148, 1080, 2278)), PartYouTubeFeed, gate = listOf(0, 684, 1080, 1302)),
            v(watchPage.copy(playlistBar = listOf(0, 1950, 1080, 2148))),
        )
        assertEquals(
            YouTubeVerdict.Cover(listOf(listOf(0, 1302, 1080, 2278), listOf(0, 76, 1080, 684)), PartYouTubeFeed, gate = listOf(0, 684, 1080, 1302)),
            v(watchPage.copy(related = listOf(listOf(0, 76, 1080, 684)))),
        )
        // Before any suggestion loads, the whole list is gated, so it can't be scrolled to bring one in.
        assertEquals(YouTubeVerdict.Cover(emptyList(), PartYouTubeFeed, gate = listOf(0, 684, 1080, 2278)), v(watchPage.copy(suggestionsTop = null)))
        // A panel open over the page (the playlist, comments, the description) is left alone.
        assertEquals(YouTubeVerdict.Allow, v(watchPage.copy(panelOpen = true)))
        // Full screen: the row that slides up is covered, the video isn't.
        assertEquals(
            YouTubeVerdict.Cover(listOf(listOf(130, 700, 2278, 1080)), PartYouTubeFeed),
            youTubeVerdict(YouTubeScreen(watch = listOf(0, 0, 2408, 1080), related = listOf(listOf(130, 700, 2278, 1080))), false, true, null, 2408, 1080),
        )
        assertEquals(YouTubeVerdict.Allow, v(YouTubeScreen(shorts = true), feed = false))
        // Home's feed, under the video, beside it; full screen has nothing to cover.
        assertEquals(YouTubeVerdict.Cover(listOf(listOf(0, 300, 1080, 2250)), PartYouTubeFeed), v(YouTubeScreen(homeFeed = 300 to 2250)))
        assertEquals(YouTubeVerdict.Cover(listOf(listOf(0, 700, 1080, 2400)), PartYouTubeFeed), v(YouTubeScreen(watch = listOf(0, 90, 1080, 700))))
        assertEquals(YouTubeVerdict.Cover(listOf(listOf(640, 0, 1080, 2400)), PartYouTubeFeed), v(YouTubeScreen(watch = listOf(0, 0, 640, 400))))
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
        assertEquals(YouTubeVerdict.NotFromChannel(key, R.string.part_no_video), v(YouTubeScreen(watch = watch, texts = listOf("Some video", "@otherchannel"))))
        assertEquals(YouTubeVerdict.NotFromChannel(key, R.string.part_no_video), v(YouTubeScreen(watch = watch, texts = listOf("Some video", "Subscribe to Other Channel."))))
        // While the page loads (a title, a view count, an ad panel), nothing names the channel: never held back.
        assertEquals(YouTubeVerdict.Allow, v(YouTubeScreen(watch = watch, texts = listOf("What Everyone Gets Wrong About Gravity", "1.5 crore"))))
        assertEquals(YouTubeVerdict.Allow, v(YouTubeScreen(watch = watch, texts = listOf("Close ad panel", "Amazon Music: Songs & Podcasts", "Install"))))
        assertEquals(listOf("@veritasium", "Veritasium"), channelsNamed(listOf("@veritasium  ", "Subscribe to Veritasium.", "4.6 lakh likes")))
        assertEquals(YouTubeVerdict.NotFromChannel(key, R.string.part_no_video), v(YouTubeScreen(shorts = true, texts = listOf("@mkbhdclips"))))
        // Still loading, or full screen: nothing to judge yet.
        assertEquals(YouTubeVerdict.Allow, v(YouTubeScreen(watch = watch)))
        // Browsing isn't watching.
        assertEquals(YouTubeVerdict.Allow, v(YouTubeScreen(texts = listOf("Other Channel"))))
        // Whole names only.
        assertFalse(fromChannels(listOf("United Nations"), listOf("ted")))
        assertTrue(fromChannels(listOf("TED · 25M subscribers"), listOf("TED")))
    }

    /** Card labels as YouTube 21.41 (English) writes them, from a real phone. */
    @Test fun cardsNameTheirChannel() {
        assertEquals("Veritasium", cardChannel("Fiber Optics are so much cooler than I thought – 34 minutes, 32 seconds –  – Veritasium, verified - 1.4 crore views - 3 weeks ago – play video"))
        assertEquals("HollyGood – HollyGood", cardChannel("Celebrities Surprise Fans When They Least Expect It! – 17 minutes, 42 seconds – Go to channel HollyGood – HollyGood - 18 lakh views - 3 months ago – play video"))
        assertEquals("Neon Man – Neon Man", cardChannel("LAFDA LAFDA LAFDA Kaashvi Vs Bigg Boss, MC Stan Vs Srushti, Salman Khan, Ramayana | – 13 minutes, 5 seconds – Go to channel Neon Man – Neon Man, verified - 5 lakh views - 18 hours ago – play video"))
        assertEquals("IShowSpeed", cardChannel("HALLOWEEN SPIDER-MAN SPEED-VERSE IRL STREAM – 2 hours, 20 seconds –  – IShowSpeed, verified - 38 lakh views - Streamed 3 days ago – play video"))
        assertEquals("StarTalk", cardChannel("Is It Possible That Gravity Doesn’t Exist!?, 54 lakh views, StarTalk, 2 years ago – play Short"))
        assertEquals("Sauce +", cardChannel("Veritasium proves that sometimes you’ve gotta take a literal leap of faith, 10 lakh views, Sauce +, 2 years ago – play Short"))
        val dashed = cardChannel("Why School Trained You to Be a Cog – 3 minutes, 13 seconds – Go to channel Ali Abdaal – Lifestyle Business Academy – Ali Abdaal – Lifestyle Business Academy - 308 views - 2 hours ago – play video")!!
        assertTrue(fromChannels(listOf(dashed), listOf("Ali Abdaal – Lifestyle Business Academy")))
        assertFalse(fromChannels(listOf(dashed), listOf("IShowSpeed")))
        // A channel's own shelf names no channel: left to the check when it opens.
        assertNull(cardChannel("SPIDERMAN BACKFLIP RACE!, 33 lakh views – play Short"))
        assertNull(cardChannel("Play video"))
    }

    @Test fun videoPageNamesItsChannel() {
        assertEquals(listOf("jawed"), channelsNamed(listOf("jawed 67.3 lakh subscribers")))
        assertEquals(listOf("Rick Astley, Official Artist Channel"), channelsNamed(listOf("Rick Astley, Official Artist Channel 45.5 lakh subscribers")))
        assertEquals(listOf("IShowSpeed"), channelsNamed(listOf("Unsubscribe from IShowSpeed.")))
        val key = channelsKey(listOf("IShowSpeed"))
        val v = { s: YouTubeScreen -> youTubeVerdict(s, shorts = false, feed = false, channels = key to listOf("IShowSpeed"), screenWidth = 1080, screenBottom = 2400) }
        val watch = listOf(0, 76, 1080, 684)
        // Subscribed to another channel: no Subscribe button, but its row still names it.
        assertEquals(YouTubeVerdict.NotFromChannel(key, R.string.part_no_video), v(YouTubeScreen(watch = watch, texts = listOf("Rick Astley, Official Artist Channel 45.5 lakh subscribers"))))
        assertEquals(YouTubeVerdict.Allow, v(YouTubeScreen(watch = watch, texts = listOf("IShowSpeed, verified 3.9 crore subscribers", "Subscribe to IShowSpeed."))))
        // A title that mentions the chosen channel doesn't make another channel's video its.
        assertEquals(YouTubeVerdict.NotFromChannel(key, R.string.part_no_video), v(YouTubeScreen(watch = watch, texts = listOf("I met @IShowSpeed in Tokyo", "Subscribe to Other."))))
    }
}
