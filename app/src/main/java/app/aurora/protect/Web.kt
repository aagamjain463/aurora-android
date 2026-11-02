package app.aurora.protect

/*
 * Website matching for the Web & content Shield. Pure Kotlin, unit tested (WebTest).
 */

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

/** View IDs that mean a short-video feed is open. They change with app updates: experimental. */
val ContentSurfaces = mapOf(
    ContentShorts to ("com.google.android.youtube" to listOf(
        "com.google.android.youtube:id/reel_player_page_container",
        "com.google.android.youtube:id/reel_recycler",
        "com.google.android.youtube:id/reel_watch_player",
    )),
)

const val Instagram = "com.instagram.android"

/** Instagram view IDs, checked against Instagram 453. They change with app updates: experimental. */
const val IgId = "$Instagram:id/"

/** One look at Instagram, reduced to what [instagramVerdict] needs. Bounds are screen pixels. */
data class InstagramScreen(
    /** The selected tab's ID name ("feed_tab", "search_tab", "clips_tab"...), null when no tab bar shows. */
    val tab: String? = null,
    /** A reel is playing full screen. */
    val reelViewer: Boolean = false,
    /** Someone's story is open. */
    val storyViewer: Boolean = false,
    /** A Home post is on screen. */
    val posts: Boolean = false,
    val storiesBottom: Int? = null,
    val headerBottom: Int? = null,
    val tabBarTop: Int? = null,
)

sealed interface InstagramVerdict {
    data object Allow : InstagramVerdict
    /** The Reels tab: go back. */
    data object Leave : InstagramVerdict
    /** A reel opened from a message or a post: it plays, but moving on to another reel goes back. */
    data object OneReel : InstagramVerdict
    /** Cover this band of the screen, so posts or Search can't be seen or touched. */
    data class Cover(val top: Int, val bottom: Int, val search: Boolean) : InstagramVerdict
}

/**
 * What Aurora does on this Instagram screen. [reels]: the Reels tab is off and other reels play one at a
 * time. [feed]: Home posts and Search are covered; stories, messages and profiles stay.
 */
fun instagramVerdict(s: InstagramScreen, reels: Boolean, feed: Boolean, screenBottom: Int): InstagramVerdict {
    val bottom = s.tabBarTop ?: screenBottom
    return when {
        s.storyViewer -> InstagramVerdict.Allow
        reels && s.tab == "clips_tab" -> InstagramVerdict.Leave
        s.reelViewer -> if (reels) InstagramVerdict.OneReel else InstagramVerdict.Allow
        feed && s.tab == "search_tab" -> InstagramVerdict.Cover(0, bottom, search = true)
        // Below the stories row (or the header, once the row has scrolled away), down to the tab bar.
        feed && s.tab == "feed_tab" && s.posts -> maxOf(s.storiesBottom ?: 0, s.headerBottom ?: 0)
            .let { top -> if (top < bottom) InstagramVerdict.Cover(top, bottom, search = false) else InstagramVerdict.Allow }
        else -> InstagramVerdict.Allow
    }
}
