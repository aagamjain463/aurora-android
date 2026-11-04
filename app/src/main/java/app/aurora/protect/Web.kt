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
data class AppPart(val key: String, val owner: String, @StringRes val name: Int, @StringRes val body: Int, @StringRes val short: Int = name)

/** The five parts a Shield can hold back, in the order the picker shows them. */
val AppParts = listOf(
    AppPart(PartReels, Instagram, R.string.part_reels, R.string.part_reels_body, R.string.part_reels_short),
    AppPart(PartInstagramMessages, Instagram, R.string.part_ig_messages, R.string.part_ig_messages_body, R.string.part_ig_messages_short),
    AppPart(PartShorts, YouTube, R.string.part_shorts, R.string.part_shorts_body, R.string.part_shorts_short),
    AppPart(PartYouTubeFeed, YouTube, R.string.part_yt_feed, R.string.part_yt_feed_body, R.string.part_yt_feed_short),
    AppPart(PartYouTubeChannels, YouTube, R.string.part_yt_channels, R.string.part_yt_channels_body, R.string.part_yt_channels_short),
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

/** The channel row under a video: "jawed 67.3 lakh subscribers", "Rick Astley, Official Artist Channel 45.5 lakh subscribers". */
private val SubscriberRow = Regex("^(.+?) [\\d.,]+\\s*(?:[KMB]|lakh|crore|thousand|million|billion)? subscribers?$", RegexOption.IGNORE_CASE)

/**
 * The channels [texts] name for certain: a handle ("@veritasium"), YouTube's "Subscribe to Veritasium." (or
 * "Unsubscribe from") button, or the channel row with its subscriber count (English). A title, a view count or an
 * ad names none: a page still loading isn't judged by them.
 */
fun channelsNamed(texts: List<String>): List<String> = texts.mapNotNull { text ->
    val t = text.trim()
    when {
        Regex("^@[\\p{L}\\p{N}._\\-]+$").matches(t) -> t
        t.startsWith("Subscribe to ") -> t.removePrefix("Subscribe to ").trimEnd('.').trim().takeIf(String::isNotEmpty)
        t.startsWith("Unsubscribe from ") -> t.removePrefix("Unsubscribe from ").trimEnd('.').trim().takeIf(String::isNotEmpty)
        else -> SubscriberRow.find(t)?.groupValues?.get(1)?.trim()?.takeIf(String::isNotEmpty)
    }
}

/** How a video card's label ends in YouTube's lists (YouTube 21.41, English). */
const val PlayVideo = " – play video"
const val PlayShort = " – play Short"

/**
 * The channel a video card in YouTube's lists names in its own label, or null when it names none (a channel's own
 * page and its shelves leave it out). Checked against YouTube 21.41, English:
 * - a video: "<title> – <length> – [Go to channel X] – X[, verified] - <views> - <age> – play video";
 * - a Short: "<title>, <views> views[, X, <age>] – play Short".
 */
fun cardChannel(label: String): String? {
    val t = label.trim()
    val name = when {
        t.endsWith(PlayVideo) -> t.removeSuffix(PlayVideo).split(" – ").takeIf { it.size >= 3 }?.last()?.substringBefore(" - ")
        t.endsWith(PlayShort) -> {
            val parts = t.removeSuffix(PlayShort).split(", ")
            val views = parts.indexOfLast { it.endsWith(" views") || it.endsWith(" view") }
            if (views >= 0 && views == parts.size - 3) parts[views + 1] else null
        }
        else -> null
    }
    return name?.removeSuffix(", verified")?.removeSuffix(", Official Artist Channel")?.trim()?.takeIf(String::isNotEmpty)
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
    /** A chat is open. */
    val chat: Boolean = false,
    /** A reel is playing full screen. */
    val reelViewer: Boolean = false,
    /** The reel viewer's swipeable area, top to bottom. */
    val reelArea: Pair<Int, Int>? = null,
    /** Something is open over the screen (a sheet of comments or shares, the keyboard): nothing is gated. */
    val busy: Boolean = false,
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
    /** On a post opened from a chat: more of that profile's posts start here, under the one that was sent. */
    val laterPostsTop: Int? = null,
    val storiesBottom: Int? = null,
    val headerBottom: Int? = null,
    val tabBarTop: Int? = null,
    /** Where each tab's button is, by its ID name: left, top, right, bottom. */
    val tabBounds: Map<String, List<Int>> = emptyMap(),
)

sealed interface InstagramVerdict {
    data object Allow : InstagramVerdict
    /** The Shield for [key], then back. [says] is the one-line reason a toast shows. */
    data class Leave(val key: String, @StringRes val says: Int) : InstagramVerdict
    /** Cover this band of the screen, so what [key] holds back can't be seen or touched. */
    data class Cover(val top: Int, val bottom: Int, val key: String) : InstagramVerdict
    /** Taps go through this band, swipes up and down don't: the list under it stays where it is. */
    data class Gate(val top: Int, val bottom: Int, val key: String, @StringRes val says: Int) : InstagramVerdict
}

/**
 * What Aurora does on this Instagram screen, given the parts a Shield holds back right now.
 * [reels]: the Reels tab (its button doesn't answer, see ContentService). [messages]: all of Instagram except
 * messages. The one reel or post a friend sent opens from the chat, and a sent reel can't be swiped on to the
 * "Suggested" ones after it; every other reel, post and story is held back (Home, Search, the Reels tab, audio
 * pages, every profile's grid). Profiles themselves stay.
 * [feed] and [search] are the older separate parts: Home posts, and Search.
 */
fun instagramVerdict(s: InstagramScreen, reels: Boolean, messages: Boolean, feed: Boolean, search: Boolean, screenBottom: Int): InstagramVerdict {
    val bottom = s.tabBarTop ?: screenBottom
    // Below the stories row (or the header, once the row has scrolled away), down to the tab bar.
    val belowStories = maxOf(s.storiesBottom ?: 0, s.headerBottom ?: 0)
    fun cover(top: Int, key: String) = if (top < bottom) InstagramVerdict.Cover(top, bottom, key) else InstagramVerdict.Allow
    fun leave(says: Int) = InstagramVerdict.Leave(PartInstagramMessages, says)
    return when {
        messages -> when {
            s.storyViewer -> leave(R.string.part_no_stories)
            s.tab == "clips_tab" -> leave(R.string.part_no_reels)
            s.reelViewer && !s.sentInChat -> leave(R.string.part_no_other_reels)
            s.reelViewer -> s.reelArea?.takeUnless { s.busy }?.let { (top, bottom) ->
                InstagramVerdict.Gate(top, bottom, PartInstagramMessages, R.string.part_no_other_reels)
            } ?: InstagramVerdict.Allow
            s.reelsPage -> leave(R.string.part_no_reels)
            s.profileGridTop != null -> cover(s.profileGridTop, PartInstagramMessages)
            // Stories too: all of Home under its header.
            s.tab == "feed_tab" -> cover(s.headerBottom ?: 0, PartInstagramMessages)
            s.tab == "search_tab" -> leave(R.string.part_no_search)
            // Blanked, never Back: while Instagram opens, Home's posts show before its tab bar does, and Back
            // there would close the app.
            s.posts && !s.fromChat -> cover(s.headerBottom ?: 0, PartInstagramMessages)
            s.posts && s.laterPostsTop != null -> cover(maxOf(s.laterPostsTop, s.headerBottom ?: 0), PartInstagramMessages)
            else -> InstagramVerdict.Allow
        }
        s.storyViewer -> InstagramVerdict.Allow
        reels && s.tab == "clips_tab" -> InstagramVerdict.Leave(PartReels, R.string.part_no_reels)
        search && s.tab == "search_tab" -> InstagramVerdict.Leave(PartInstagramSearch, R.string.part_no_search)
        feed && s.tab == "feed_tab" && s.posts -> cover(belowStories, PartInstagramFeed)
        else -> InstagramVerdict.Allow
    }
}

/** One look at YouTube, reduced to what [youTubeVerdict] needs. Bounds are screen pixels. */
data class YouTubeScreen(
    /** The Shorts player is open. */
    val shorts: Boolean = false,
    /** The Shorts tab is the chosen tab (not a Short opened from Home, Search or a channel). */
    val shortsTab: Boolean = false,
    /** The Home tab's feed, top to bottom, when Home is the chosen tab. */
    val homeFeed: Pair<Int, Int>? = null,
    /** The list under (or beside) the open video: its details, comments, then every suggestion. */
    val watchList: List<Int>? = null,
    /** Where the first suggested video in [watchList] starts, null before any shows. */
    val suggestionsTop: Int? = null,
    /** The bar of the playlist the video plays from (Watch later, a playlist): it stays usable. */
    val playlistBar: List<Int>? = null,
    /** A panel is open over the video's page (description, comments, the playlist). */
    val panelOpen: Boolean = false,
    /** The open video's player (not the miniplayer, not a preview in a list): left, top, right, bottom. */
    val watch: List<Int>? = null,
    /** The small player above the tab bar, when a video is minimised: left, top, right, bottom. */
    val miniplayer: List<Int>? = null,
    /**
     * What else YouTube recommends around the open video: the "Suggested video" that ends a video, the row
     * that slides up in full screen. Each is left, top, right, bottom.
     */
    val related: List<List<Int>> = emptyList(),
    /** What YouTube shows around the playing video or Short: its title, channel, handle. */
    val texts: List<String> = emptyList(),
    /** The open video's row of actions (like, dislike, share) is in sight: its channel row has loaded beside it. */
    val actionsShown: Boolean = false,
)

sealed interface YouTubeVerdict {
    data object Allow : YouTubeVerdict
    /** The Shield for [key], then back. [says] is the one-line reason a toast shows. */
    data class Leave(val key: String, @StringRes val says: Int) : YouTubeVerdict
    /** Not from a chosen channel: the Shield for [key] once YouTube has settled on it. */
    data class NotFromChannel(val key: String, @StringRes val says: Int) : YouTubeVerdict
    /**
     * Blank out [areas] (each left, top, right, bottom) so they show nothing. [gate]: taps go through it,
     * swipes up and down don't, so nothing can be scrolled out from under the blanks.
     */
    data class Cover(val areas: List<List<Int>>, val key: String, val gate: List<Int>? = null) : YouTubeVerdict
}

/**
 * What Aurora does on this YouTube screen. [shorts]: the Shorts tab. [feed]: every Short, Home's feed and
 * everything YouTube suggests around a video; Subscriptions, Search and the video itself stay.
 * [channels]: the channels key and its channels, when only those may play.
 */
fun youTubeVerdict(s: YouTubeScreen, shorts: Boolean, feed: Boolean, channels: Pair<String, List<String>>?, screenWidth: Int, screenBottom: Int): YouTubeVerdict {
    val playing = s.shorts || s.watch != null
    return when {
        s.shorts && shorts && s.shortsTab -> YouTubeVerdict.Leave(PartShorts, R.string.part_no_shorts)
        s.shorts && feed -> YouTubeVerdict.Leave(PartYouTubeFeed, R.string.part_no_shorts)
        // Judged only once the page names its channel for certain: while it loads (only a title, a view count,
        // an ad) an allowed video is never held back.
        // Only what names a channel counts: a title that mentions a chosen one ("Reacting to Veritasium") doesn't.
        channels != null && playing && channelsNamed(s.texts).let { it.isNotEmpty() && !fromChannels(it, channels.second) } ->
            YouTubeVerdict.NotFromChannel(channels.first, R.string.part_no_video)
        // Under the video: its details and comments stay, every suggestion after them is blank, the playlist bar
        // stays usable, and the list can't be scrolled. An open panel (description, comments) is left alone.
        feed && s.watchList != null && !s.panelOpen -> {
            val (left, top, right, bottom) = s.watchList
            val from = (s.suggestionsTop ?: bottom).coerceIn(top, bottom)
            val blank = s.playlistBar?.takeIf { it[1] < bottom && it[3] > from }?.let { bar ->
                listOf(listOf(left, from, right, bar[1]), listOf(left, bar[3], right, bottom))
            } ?: listOf(listOf(left, from, right, bottom))
            YouTubeVerdict.Cover(blank.filter { it[1] < it[3] } + s.related, PartYouTubeFeed, gate = listOf(left, top, right, from).takeIf { from > top })
        }
        feed && s.related.isNotEmpty() -> YouTubeVerdict.Cover(s.related, PartYouTubeFeed)
        // An older YouTube without those views: whatever is below or beside the player. An open panel (the
        // playlist, comments) is left alone.
        feed && s.watch != null && !s.panelOpen -> {
            val (_, _, right, bottom) = s.watch
            when {
                // Side by side (landscape, tablets): what's beside the video.
                right < screenWidth * 4 / 5 -> YouTubeVerdict.Cover(listOf(listOf(right, 0, screenWidth, screenBottom)), PartYouTubeFeed)
                bottom < screenBottom -> YouTubeVerdict.Cover(listOf(listOf(0, bottom, screenWidth, screenBottom)), PartYouTubeFeed)
                else -> YouTubeVerdict.Allow
            }
        }
        // Home's feed is blank. Its header stays free, so it can be pulled down to show Home and Subscriptions.
        feed && s.homeFeed != null && s.homeFeed.first < s.homeFeed.second ->
            YouTubeVerdict.Cover(listOf(listOf(0, s.homeFeed.first, screenWidth, s.homeFeed.second)), PartYouTubeFeed)
        else -> YouTubeVerdict.Allow
    }
}
