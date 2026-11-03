package app.aurora.protect

import androidx.annotation.StringRes
import app.aurora.R

/*
 * Website and app-part matching for the Web & content Shield. Pure Kotlin, unit tested (WebTest).
 */

const val PartPrefix = "part:"
const val YouTube = "com.google.android.youtube"
const val PartShorts = "part:youtube.shorts"
const val PartYouTubeFeed = "part:youtube.feed"
/**
 * YouTube only from chosen channels: "part:youtube.channels=<name>|<name>". The list lives in the key, so a
 * running Shield keeps the list it started with and a strict one can't be loosened by editing it.
 */
const val PartYouTubeChannels = "part:youtube.channels"
const val PartReels = "part:instagram.reels"
const val PartInstagramMessages = "part:instagram.messages"
/** No longer offered; Shields saved with them keep working ([LegacyParts]). */
const val PartInstagramFeed = "part:instagram.feed"
const val PartInstagramSearch = "part:instagram.search"

/**
 * A part of an app that a Shield holds back on its own, like an app or a site, while the rest of [owner]
 * stays open. Experimental: detection reads view IDs those apps can change at any update.
 */
data class AppPart(val key: String, val owner: String, @StringRes val name: Int, @StringRes val body: Int)

/** The five parts a Shield can hold back, in the order the picker shows them. */
val AppParts = listOf(
    AppPart(PartReels, Instagram, R.string.part_reels, R.string.part_reels_body),
    AppPart(PartInstagramMessages, Instagram, R.string.part_ig_messages, R.string.part_ig_messages_body),
    AppPart(PartShorts, YouTube, R.string.part_shorts, R.string.part_shorts_body),
    AppPart(PartYouTubeFeed, YouTube, R.string.part_yt_feed, R.string.part_yt_feed_body),
    AppPart(PartYouTubeChannels, YouTube, R.string.part_yt_channels, R.string.part_yt_channels_body),
)

private val LegacyParts = listOf(
    AppPart(PartInstagramFeed, Instagram, R.string.part_ig_feed, R.string.part_ig_feed_body),
    AppPart(PartInstagramSearch, Instagram, R.string.part_ig_search, R.string.part_ig_search_body),
)

fun partOf(key: String): AppPart? = (AppParts + LegacyParts).find { key == it.key || key.startsWith(it.key + "=") }

/** The key for YouTube from only [channels]. */
fun channelsKey(channels: List<String>): String =
    PartYouTubeChannels + "=" + channels.map(::cleanChannel).filter(String::isNotEmpty).distinct().joinToString("|")

/** The channels in a [PartYouTubeChannels] key. */
fun channelsOf(key: String): List<String> = key.substringAfter('=', "").split('|').filter(String::isNotBlank)

/** A channel as the person typed it, safe inside a key. */
fun cleanChannel(name: String): String = name.replace("|", "").replace("=", "").trim().take(100)

private fun normal(text: String) = text.lowercase().replace(Regex("\\s+"), " ").trim()

/**
 * Whether any of [texts] (what YouTube shows around a video) names one of [channels], as a whole name:
 * "Veritasium" or "@veritasium" matches "Veritasium · 15M subscribers", "ted" doesn't match "united".
 */
fun fromChannels(texts: List<String>, channels: List<String>): Boolean = channels.any { channel ->
    val name = normal(channel).removePrefix("@").takeIf(String::isNotEmpty) ?: return@any false
    val whole = Regex("(^|[^\\p{L}\\p{N}@])@?" + Regex.escape(name) + "($|[^\\p{L}\\p{N}])")
    texts.any { whole.containsMatchIn(normal(it)) }
}

/** The old global switches ([Rules.content]) and the parts each one becomes. */
val LegacyContent = mapOf(
    "youtube_shorts" to listOf(PartShorts),
    "instagram_reels" to listOf(PartReels),
    "instagram_feed" to listOf(PartInstagramFeed, PartInstagramSearch),
)

/** Websites and parts of apps are only seen once the Web & content Shield is on. */
fun needsContentShield(keys: Collection<String>): Boolean = keys.any { it.startsWith(WebPrefix) || it.startsWith(PartPrefix) }

/** Where each supported browser shows its address. Only these browsers are covered. */
val BrowserAddressBars = mapOf(
    "com.android.chrome" to "com.android.chrome:id/url_bar",
    "com.chrome.beta" to "com.chrome.beta:id/url_bar",
    "com.sec.android.app.sbrowser" to "com.sec.android.app.sbrowser:id/location_bar_edit_text",
    "org.mozilla.firefox" to "org.mozilla.firefox:id/mozac_browser_toolbar_url_view",
    "com.microsoft.emmx" to "com.microsoft.emmx:id/url_bar",
    "com.brave.browser" to "com.brave.browser:id/url_bar",
    "com.opera.browser" to "com.opera.browser:id/url_field",
    "com.duckduckgo.mobile.android" to "com.duckduckgo.mobile.android:id/omnibarTextInput",
)

val BrowserNames = listOf("Chrome", "Samsung Internet", "Firefox", "Edge", "Brave", "Opera", "DuckDuckGo")

/**
 * The host in an address bar's text, which browsers show with or without the scheme ("reddit.com/r/x",
 * "https://m.youtube.com"). Null for search terms and anything that isn't a host.
 */
fun hostOf(address: String): String? {
    val text = address.trim().lowercase()
    if (text.isEmpty() || ' ' in text) return null
    val host = text.substringAfter("://").substringBefore('/').substringBefore('?').substringBefore('#').substringAfter('@').substringBefore(':').trimEnd('.')
    return host.takeIf { '.' in it && it.split('.').none(String::isEmpty) }
}

/**
 * The protected key for [host]: a protected domain matches itself and its subdomains ("m.reddit.com" is
 * reddit.com, "notreddit.com" is not). The longest match wins.
 */
fun webKeyFor(host: String, keys: Collection<String>): String? = keys
    .filter { it.startsWith(WebPrefix) }
    .filter { key -> key.removePrefix(WebPrefix).let { host == it || host.endsWith(".$it") } }
    .maxByOrNull { it.length }

const val Instagram = "com.instagram.android"

/** Instagram view IDs, checked against Instagram 453. They change with app updates: experimental. */
const val IgId = "$Instagram:id/"

/** One look at Instagram, reduced to what [instagramVerdict] needs. Bounds are screen pixels. */
data class InstagramScreen(
    /** The selected tab's ID name ("feed_tab", "search_tab", "clips_tab"...), null when no tab bar shows. */
    val tab: String? = null,
    /** A reel is playing full screen. */
    val reelViewer: Boolean = false,
    /** The reel came in a chat: its viewer names who sent it. Instagram's "Suggested" reels after it don't. */
    val sentInChat: Boolean = false,
    /** Someone's story is open. */
    val storyViewer: Boolean = false,
    /** A post is on screen (Home's, or one opened on its own). */
    val posts: Boolean = false,
    /** The last screen before this one, other than a post or a profile, was a chat. */
    val fromChat: Boolean = false,
    /** An audio, effect or similar page: a grid of other people's reels. */
    val reelsPage: Boolean = false,
    /** A profile's grid of posts and reels starts here; its header (name, bio, follow, message) is above. */
    val profileGridTop: Int? = null,
    val storiesBottom: Int? = null,
    val headerBottom: Int? = null,
    val tabBarTop: Int? = null,
)

sealed interface InstagramVerdict {
    data object Allow : InstagramVerdict
    /** The Shield for [key], then back. */
    data class Leave(val key: String) : InstagramVerdict
    /** Cover this band of the screen, so what [key] holds back can't be seen or touched. */
    data class Cover(val top: Int, val bottom: Int, val key: String) : InstagramVerdict
}

/**
 * What Aurora does on this Instagram screen, given the parts a Shield holds back right now.
 * [reels]: only the Reels tab. [messages]: only chats and stories. The one reel or post a friend sent
 * opens from the chat; every other reel and post is held back (Home, Search, the Reels tab, the
 * "Suggested" reels after a sent one, audio pages, every profile's grid). Profiles themselves stay.
 * [feed] and [search] are the older separate parts: Home posts, and Search.
 */
fun instagramVerdict(s: InstagramScreen, reels: Boolean, messages: Boolean, feed: Boolean, search: Boolean, screenBottom: Int): InstagramVerdict {
    val bottom = s.tabBarTop ?: screenBottom
    // Below the stories row (or the header, once the row has scrolled away), down to the tab bar.
    val belowStories = maxOf(s.storiesBottom ?: 0, s.headerBottom ?: 0)
    fun cover(top: Int, key: String) = if (top < bottom) InstagramVerdict.Cover(top, bottom, key) else InstagramVerdict.Allow
    val leave = InstagramVerdict.Leave(PartInstagramMessages)
    return when {
        s.storyViewer -> InstagramVerdict.Allow
        messages -> when {
            s.tab == "clips_tab" -> leave
            s.reelViewer -> if (s.sentInChat) InstagramVerdict.Allow else leave
            s.reelsPage -> leave
            s.profileGridTop != null -> cover(s.profileGridTop, PartInstagramMessages)
            s.tab == "feed_tab" -> cover(belowStories, PartInstagramMessages)
            s.tab == "search_tab" -> cover(0, PartInstagramMessages)
            s.posts && !s.fromChat -> leave
            else -> InstagramVerdict.Allow
        }
        reels && s.tab == "clips_tab" -> InstagramVerdict.Leave(PartReels)
        search && s.tab == "search_tab" -> cover(0, PartInstagramSearch)
        feed && s.tab == "feed_tab" && s.posts -> cover(belowStories, PartInstagramFeed)
        else -> InstagramVerdict.Allow
    }
}

/** One look at YouTube, reduced to what [youTubeVerdict] needs. Bounds are screen pixels. */
data class YouTubeScreen(
    /** The Shorts player is open. */
    val shorts: Boolean = false,
    /** The Home tab's feed, top to bottom, when Home is the chosen tab. */
    val homeFeed: Pair<Int, Int>? = null,
    /** The open video's player (not the miniplayer, not a preview in a list): left, top, right, bottom. */
    val watch: List<Int>? = null,
    /** What YouTube shows around the playing video or Short: its title, channel, handle. */
    val texts: List<String> = emptyList(),
)

sealed interface YouTubeVerdict {
    data object Allow : YouTubeVerdict
    /** The Shield for [key], then back. */
    data class Leave(val key: String) : YouTubeVerdict
    /** Not from a chosen channel: the Shield for [key] once YouTube has settled on it. */
    data class NotFromChannel(val key: String) : YouTubeVerdict
    /** Cover this rectangle: left, top, right, bottom. */
    data class Cover(val bounds: List<Int>, val key: String) : YouTubeVerdict
}

/**
 * What Aurora does on this YouTube screen. [shorts]: Shorts. [feed]: every recommendation (Home's feed,
 * what's beside or below a video, Shorts); Subscriptions and Search stay. [channels]: the channels key
 * and its channels, when only those may play.
 */
fun youTubeVerdict(s: YouTubeScreen, shorts: Boolean, feed: Boolean, channels: Pair<String, List<String>>?, screenWidth: Int, screenBottom: Int): YouTubeVerdict {
    val playing = s.shorts || s.watch != null
    return when {
        s.shorts && shorts -> YouTubeVerdict.Leave(PartShorts)
        s.shorts && feed -> YouTubeVerdict.Leave(PartYouTubeFeed)
        // Nothing to read yet (still loading, or full screen): judged once it shows.
        channels != null && playing && s.texts.isNotEmpty() && !fromChannels(s.texts, channels.second) -> YouTubeVerdict.NotFromChannel(channels.first)
        feed && s.watch != null -> {
            val (_, _, right, bottom) = s.watch
            when {
                // Side by side (landscape, tablets): what's beside the video.
                right < screenWidth * 4 / 5 -> YouTubeVerdict.Cover(listOf(right, 0, screenWidth, screenBottom), PartYouTubeFeed)
                bottom < screenBottom -> YouTubeVerdict.Cover(listOf(0, bottom, screenWidth, screenBottom), PartYouTubeFeed)
                else -> YouTubeVerdict.Allow
            }
        }
        feed && s.homeFeed != null && s.homeFeed.first < s.homeFeed.second ->
            YouTubeVerdict.Cover(listOf(0, s.homeFeed.first, screenWidth, s.homeFeed.second), PartYouTubeFeed)
        else -> YouTubeVerdict.Allow
    }
}
