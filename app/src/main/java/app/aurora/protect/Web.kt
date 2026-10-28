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
    ContentReels to ("com.instagram.android" to listOf(
        "com.instagram.android:id/clips_viewer_view_pager",
        "com.instagram.android:id/clips_video_container",
    )),
)
