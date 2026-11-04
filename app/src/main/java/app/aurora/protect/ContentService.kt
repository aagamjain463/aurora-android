package app.aurora.protect

import android.accessibilityservice.AccessibilityService
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.TypedValue
import android.view.Gravity
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.TextView
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.aurora.AuroraApplication
import app.aurora.R
import app.aurora.ui.theme.AuroraTheme
import app.aurora.ui.theme.AuroraType
import app.aurora.ui.theme.Space
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The optional Web & content Shield (res/xml/content_service.xml). Separate from [ShieldService], so the
 * broader access it needs is never on unless the person turns this one on, after aurora's disclosure.
 *
 * - Websites: in the browsers in [BrowserAddressBars], it reads the address field and asks [decide]
 *   about "web:<domain>". Pages in other browsers, in-app browsers and private tabs a browser hides from
 *   accessibility aren't covered, and the screens say so.
 * - Parts of apps (experimental, [AppParts]): Shield targets like apps and sites, so every mode works.
 *   A held-back screen (the Reels tab, Shorts, a video from another channel) brings the Shield; leaving it
 *   goes back inside the app. A held-back band (Instagram posts, YouTube's recommendations) is covered;
 *   tapping the cover shows the Shield. See [instagramVerdict] and [youTubeVerdict]. Detection uses view
 *   IDs those apps can change at any update.
 *
 * Nothing it reads is stored, logged or sent.
 */
class ContentService : AccessibilityService() {
    private var lastKey: String? = null
    private var lastAt = 0L
    private var lastCheck = HashMap<String, Long>()
    private var presenter: OverlayShieldPresenter? = null
    /** The app a Shield is showing over, so leaving it takes the Shield away. */
    private var shownOver: String? = null
    /** Whether leaving the Shield now showing goes back inside the app (a held-back screen) or just closes it (a cover). */
    private var leaveBack = true
    private var cover: Cover? = null
    /** Over a held-back tab button (Instagram's Reels, YouTube's Shorts), so the tab is simply not there. */
    private var tabCover: Cover? = null
    /** A plain veil over the whole app while a held-back screen is left, so it's never seen going. */
    private var veil: Cover? = null
    private var veilAt = 0L
    private val liftVeil = Runnable { veil?.hide(fade = true) }
    /** When Aurora last stopped YouTube from playing its suggested next video. */
    private var lastAutoplayStop = 0L
    /** Instagram's share and forward buttons, under "only messages and stories": one cover each. */
    private val shareCovers = mutableListOf<Cover>()
    /** Whether the person reached the post on screen from a chat ([InstagramScreen.fromChat]). */
    private var fromChat = false
    private val onCoverTap = { key: String, decision: Decision.Shield, owner: String ->
        lastKey = null
        // A tap only leads anywhere when the Shield itself can: Intent's deliberate decision.
        if (decision.reason.canContinue) present(key, decision, owner, SystemClock.uptimeMillis(), back = false)
        else say(coverSays(key))
    }
    /** When YouTube started showing a video from a channel that isn't chosen, 0 when it isn't. */
    private var otherChannelSince = 0L
    private val handler = Handler(Looper.getMainLooper())
    /** The small dark line at the foot of the screen for a held-back part of an app. */
    private var notice: Notice? = null
    private val hideNotice = Runnable { notice?.hide() }
    /** After Return home at a website Shield: the browser whose blocked page is being closed. */
    private var clearing: String? = null
    private var clearingUntil = 0L
    private var clearingBacks = 0
    private var lastClearBack = 0L
    /** An app's events stop when the person leaves it, so the cover watches for that itself. */
    private val watchCover = object : Runnable {
        override fun run() {
            if (listOfNotNull(cover, tabCover).none { it.showing }) return
            val root = rootInActiveWindow
            val app = application as AuroraApplication
            when (root?.packageName?.toString()) {
                Instagram -> checkInstagram(app, root, SystemClock.uptimeMillis())
                YouTube -> checkYouTube(app, root, SystemClock.uptimeMillis())
                packageName -> Unit // The person is touching the cover itself.
                else -> hideCovers()
            }
            handler.removeCallbacks(this)
            if (listOfNotNull(cover, tabCover).any { it.showing }) handler.postDelayed(this, CheckEveryMs)
        }
    }

    /**
     * Closing a blocked page after Return home. Back can leave a tab with no history on the site, so this
     * keeps pressing Back while the bar still names a blocked host, then goes home. It runs on its own
     * because a static page sends no more content events.
     */
    private val clearTicker = object : Runnable {
        override fun run() {
            val browser = clearing ?: return
            val now = SystemClock.uptimeMillis()
            val root = rootInActiveWindow
            val barId = BrowserAddressBars[browser]
            when {
                // No bar to read (a browser update changed its ID): don't press Back blindly.
                barId == null -> finishClearing(home = false)
                root == null -> if (now >= clearingUntil) finishClearing(home = true) else handler.postDelayed(this, ClearEveryMs)
                root.packageName?.toString() != browser -> finishClearing(home = false)
                else -> {
                    checkSite(application as AuroraApplication, browser, root, barId, now)
                    if (clearing != null) handler.postDelayed(this, ClearEveryMs)
                }
            }
        }
    }

    override fun onServiceConnected() {
        presenter = OverlayShieldPresenter(this) { request, close -> ShieldContent(request, close) }.apply {
            // Leaving a part keeps the app: back out of a held-back screen; a covered part keeps its cover.
            // A website goes back too, and [clearTicker] takes the blocked page down with it.
            leaveAction = { key ->
                when {
                    key.startsWith(WebPrefix) -> {
                        startClearing(shownOver)
                        GLOBAL_ACTION_BACK
                    }
                    !key.startsWith(PartPrefix) -> GLOBAL_ACTION_HOME
                    leaveBack -> GLOBAL_ACTION_BACK
                    else -> null
                }
            }
        }
        cover = Cover(this, onCoverTap)
        tabCover = Cover(this, onCoverTap)
        veil = Cover(this, { _, _, _ -> }, translucent = true)
        connectedState.value = true
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val pkg = event.packageName?.toString() ?: return
        val now = SystemClock.uptimeMillis()
        // After Return home at a website Shield, the browser leaving the front means the person is already
        // somewhere else: clearing is done, and there is no need to send them home from there.
        if (clearing != null && event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && pkg != clearing && pkg != packageName && pkg != "com.android.systemui") {
            finishClearing(home = false)
        }
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && shownOver != null && pkg != shownOver && pkg != packageName && pkg != "com.android.systemui") {
            presenter?.dismiss()
            shownOver = null
        }
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && pkg != packageName && pkg != (cover?.owner ?: tabCover?.owner)) hideCovers()
        // Content changes arrive in floods; one look per app every 120 ms keeps covers on what they hide.
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED && now - (lastCheck[pkg] ?: 0L) < CheckEveryMs) return
        lastCheck[pkg] = now
        val root = rootInActiveWindow ?: return
        if (root.packageName?.toString() != pkg) return
        val app = application as AuroraApplication
        BrowserAddressBars[pkg]?.let { checkSite(app, pkg, root, it, now) }
        if (pkg == Instagram) checkInstagram(app, root, now)
        if (pkg == YouTube) checkYouTube(app, root, now)
    }

    private fun checkSite(app: AuroraApplication, browser: String, root: AccessibilityNodeInfo, barId: String, now: Long) {
        // A page is being closed after Return home: keep going until the bar no longer names a blocked host.
        if (clearing == browser) {
            clearSite(app, browser, root, barId, now)
            return
        }
        val bar = root.findAccessibilityNodeInfosByViewId(barId).firstOrNull() ?: return
        // While the person is typing in the address bar, the text isn't a page they're on.
        if (bar.isFocused) return
        val host = hostOf(bar.text?.toString() ?: return) ?: return
        val state = app.policySnapshot(emptySet(), emptyMap()) ?: return
        val key = webKeyFor(host, state.protectedPackages) ?: return
        val decision = decide(key, TrustedClock.wall(), state) as? Decision.Shield ?: return
        present(key, decision, browser, now)
    }

    /** Return home at a website Shield: remember the browser so [clearTicker] can take the page down. */
    private fun startClearing(browser: String?) {
        if (browser == null) return
        val now = SystemClock.uptimeMillis()
        clearing = browser
        clearingUntil = now + ClearDeadlineMs
        clearingBacks = 0
        // The Return home action already sent one Back; give the page time to leave before the next.
        lastClearBack = now
        handler.removeCallbacks(clearTicker)
        handler.postDelayed(clearTicker, ClearEveryMs)
    }

    /**
     * One look at the browser while clearing. The blocked page is gone once the bar is empty, a different
     * page, or a page Aurora allows: then home, so the browser opens somewhere usable next time.
     */
    private fun clearSite(app: AuroraApplication, browser: String, root: AccessibilityNodeInfo, barId: String, now: Long) {
        val bar = root.findAccessibilityNodeInfosByViewId(barId).firstOrNull()
        val host = bar?.text?.toString()?.let(::hostOf)
        val state = app.policySnapshot(emptySet(), emptyMap())
        val blocked = host != null && state != null && webKeyFor(host, state.protectedPackages)?.let { decide(it, TrustedClock.wall(), state) is Decision.Shield } == true
        when {
            now >= clearingUntil || !blocked -> finishClearing(home = true)
            clearingBacks >= ClearBackMax || now - lastClearBack < ClearEveryMs -> Unit
            else -> {
                lastClearBack = now
                clearingBacks++
                performGlobalAction(GLOBAL_ACTION_BACK)
            }
        }
    }

    private fun finishClearing(home: Boolean) {
        clearing = null
        clearingBacks = 0
        handler.removeCallbacks(clearTicker)
        shownOver = null
        if (home) performGlobalAction(GLOBAL_ACTION_HOME)
    }

    /** The Shield [key] is under right now, or null when nothing holds it back. */
    private fun shieldFor(state: PolicyState?, key: String): Decision.Shield? =
        state?.let { decide(key, TrustedClock.wall(), it) as? Decision.Shield }

    /**
     * The Shield for [key] over [owner]. [back]: leaving it goes back inside the app. [says] is the toast
     * for a part of an app: held-back screens never take the full screen, only Intent may.
     */
    private fun present(key: String, decision: Decision.Shield, owner: String, now: Long, back: Boolean = true, @StringRes says: Int = 0) {
        if (key == lastKey && now - lastAt < RepeatMs) return
        lastKey = key
        lastAt = now
        // A part of an app says why in a line and goes back; only Intent's deliberate decision fills the screen.
        if (key.startsWith(PartPrefix) && decision.reason != ShieldReason.Intent) {
            if (back) showVeil(owner, key, decision, now)
            say(if (says != 0) says else coverSays(key))
            if (back) performGlobalAction(GLOBAL_ACTION_BACK)
            return
        }
        leaveBack = back
        if (presenter?.present(ShieldRequest(key, decision.reason, decision.until, now, now, now, decision = decision)) == true) shownOver = owner
    }

    /** The veil goes up at once, and comes off (fading) as soon as the app has moved on, or after [VeilMs]. */
    private fun showVeil(owner: String, key: String, decision: Decision.Shield, now: Long) {
        val windows = getSystemService(WindowManager::class.java)
        val screen = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) windows.maximumWindowMetrics.bounds
        else android.util.DisplayMetrics().also { @Suppress("DEPRECATION") windows.defaultDisplay.getRealMetrics(it) }.let { Rect(0, 0, it.widthPixels, it.heightPixels) }
        veil?.show(screen, owner, key, decision)
        veilAt = now
        handler.removeCallbacks(liftVeil)
        handler.postDelayed(liftVeil, VeilMs)
    }

    /** The app has left the held-back screen: the veil can come off. */
    private fun settled(now: Long) {
        if (veil?.showing == true && now - veilAt >= MinVeilMs) {
            handler.removeCallbacks(liftVeil)
            veil?.hide(fade = true)
        }
    }

    /** Keeps [tabCover] on a held-back tab button, or takes it away. */
    private fun keepTabCover(owner: String, bounds: Rect?, key: String, decision: Decision.Shield?) {
        if (bounds == null || bounds.isEmpty || decision == null) {
            tabCover?.hide()
            return
        }
        tabCover?.show(bounds, owner, key, decision)
        handler.removeCallbacks(watchCover)
        handler.postDelayed(watchCover, CheckEveryMs)
    }

    /** Shows [text] in the small dark line at the foot of the screen, then takes it away. */
    private fun say(@StringRes text: Int) {
        val n = notice ?: Notice(this).also { notice = it }
        n.show(text)
        handler.removeCallbacks(hideNotice)
        handler.postDelayed(hideNotice, NoticeMs)
    }

    private fun hideCovers() {
        cover?.hide()
        tabCover?.hide()
        shareCovers.forEach(Cover::hide)
    }

    /** Keeps [cover] on [bounds] for [key], or takes it away. */
    private fun keepCover(owner: String, bounds: Rect?, key: String?, decision: Decision.Shield?) {
        if (bounds == null || key == null || decision == null) {
            cover?.hide()
            return
        }
        cover?.show(bounds, owner, key, decision)
        handler.removeCallbacks(watchCover)
        handler.postDelayed(watchCover, CheckEveryMs)
    }

    private fun checkInstagram(app: AuroraApplication, root: AccessibilityNodeInfo, now: Long) {
        val state = app.policySnapshot(emptySet(), emptyMap())
        val shields = listOf(PartReels, PartInstagramMessages, PartInstagramFeed, PartInstagramSearch).associateWith { shieldFor(state, it) }
        val messages = shields[PartInstagramMessages]
        val verdict = if (shields.values.any { it != null }) {
            instagramVerdict(
                readInstagram(root),
                reels = shields[PartReels] != null, messages = messages != null,
                feed = shields[PartInstagramFeed] != null, search = shields[PartInstagramSearch] != null,
                screenBottom = resources.displayMetrics.heightPixels,
            )
        } else InstagramVerdict.Allow
        val c = verdict as? InstagramVerdict.Cover
        keepCover(Instagram, c?.let { Rect(0, it.top, resources.displayMetrics.widthPixels, it.bottom) }, c?.key, c?.let { shields[it.key] })
        // A friend's reel or post can be watched, not passed on: every share and forward button is covered.
        val shares = if (messages != null) IgShareIds.flatMap { id -> shown(root, IgId + id) }.map(::boundsOf).filterNot(Rect::isEmpty) else emptyList()
        while (shareCovers.size < shares.size) shareCovers += Cover(this, onCoverTap)
        shareCovers.forEachIndexed { i, cover -> shares.getOrNull(i)?.let { cover.show(it, Instagram, PartInstagramMessages, messages!!) } ?: cover.hide() }
        // The Reels tab isn't there: its button is covered, and a tap says why.
        val reelsKey = if (messages != null) PartInstagramMessages else PartReels
        val reelsTab = shields[reelsKey]?.let { shown(root, IgId + "clips_tab").firstOrNull()?.let(::boundsOf) }
        keepTabCover(Instagram, reelsTab, reelsKey, shields[reelsKey])
        if (verdict is InstagramVerdict.Leave) present(verdict.key, shields[verdict.key]!!, Instagram, now, says = verdict.says) else settled(now)
    }

    private fun readInstagram(root: AccessibilityNodeInfo): InstagramScreen {
        fun shown(id: String) = shown(root, IgId + id)
        fun bounds(id: String) = shown(id).firstOrNull()?.let(::boundsOf)
        // Instagram marks the chosen tab on its icon, inside the tab.
        fun selected(n: AccessibilityNodeInfo) = n.isSelected || (0 until n.childCount).any { n.getChild(it)?.isSelected == true }
        val tab = InstagramTabs.firstOrNull { id -> shown(id).any(::selected) }
        val posts = FeedPostIds.any { shown(it).isNotEmpty() }
        val profileGrid = bounds("profile_tabs_container") ?: bounds("profile_viewpager")
        // Where the person came from: a post counts as sent only when they opened it from a chat. A profile
        // or comments on the way don't change that; any other screen does.
        when {
            shown("thread_fragment_container").isNotEmpty() -> fromChat = true
            profileGrid != null || posts && tab != "feed_tab" -> Unit
            else -> fromChat = false
        }
        return InstagramScreen(
            tab = tab,
            reelViewer = shown("clips_viewer_view_pager").isNotEmpty(),
            sentInChat = shown("sender_username_or_fullname").isNotEmpty(),
            storyViewer = shown("reel_viewer_root").isNotEmpty(),
            posts = posts,
            fromChat = fromChat,
            reelsPage = shown("clips_pivot_page_fragment").isNotEmpty(),
            profileGridTop = profileGrid?.top,
            storiesBottom = bounds("reels_tray_container")?.bottom,
            headerBottom = bounds("action_bar_container")?.bottom,
            tabBarTop = bounds("tab_bar")?.top,
        )
    }

    private fun shown(root: AccessibilityNodeInfo, id: String) = root.findAccessibilityNodeInfosByViewId(id).filter { it.isVisibleToUser }

    private fun shownIn(root: AccessibilityNodeInfo, pkg: String, id: String) = shown(root, "$pkg:id/$id")

    private fun boundsOf(node: AccessibilityNodeInfo) = Rect().also(node::getBoundsInScreen)

    private fun checkYouTube(app: AuroraApplication, root: AccessibilityNodeInfo, now: Long) {
        val state = app.policySnapshot(emptySet(), emptyMap())
        val shorts = shieldFor(state, PartShorts)
        val feed = shieldFor(state, PartYouTubeFeed)
        // Every running "only these channels" Shield: a video must be from a channel all of them chose.
        val channelShields = state?.protectedPackages.orEmpty()
            .filter { it.startsWith("$PartYouTubeChannels=") }
            .mapNotNull { key -> shieldFor(state, key)?.let { key to it } }
        val channels = channelShields.firstOrNull()?.let { (key, _) ->
            key to channelShields.map { channelsOf(it.first) }.reduce { a, b -> a.filter { c -> b.any { it.equals(c, ignoreCase = true) } } }
        }
        val m = resources.displayMetrics
        val verdict = if (shorts != null || feed != null || channels != null) {
            youTubeVerdict(readYouTube(root), shorts != null, feed != null, channels, m.widthPixels, m.heightPixels)
        } else YouTubeVerdict.Allow
        val c = verdict as? YouTubeVerdict.Cover
        keepCover(YouTube, c?.bounds?.let { (l, t, r, b) -> Rect(l, t, r, b) }, c?.key, feed)
        // The video that ends counts down to a suggested one: stop it, so a recommendation never plays itself.
        if (feed != null && now - lastAutoplayStop > RepeatMs && shownIn(root, YouTube, "info_panel").isNotEmpty()) {
            shownIn(root, YouTube, "cancel_button").firstOrNull()?.let {
                lastAutoplayStop = now
                it.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }
        }
        // The Shorts tab isn't there: its button is covered. Found by its name, so a YouTube in another language
        // keeps the button and leaves the tab once it opens.
        val tabShield = shorts ?: feed
        val shortsTab = tabShield?.let {
            shownIn(root, YouTube, "pivot_bar").firstOrNull()?.let { p ->
                (0 until p.childCount).mapNotNull(p::getChild).firstOrNull { tab -> nodes(tab).any { textOf(it).equals("Shorts", ignoreCase = true) } }
            }?.let(::boundsOf)
        }
        keepTabCover(YouTube, shortsTab, if (shorts != null) PartShorts else PartYouTubeFeed, tabShield)
        if (verdict !is YouTubeVerdict.Leave && verdict !is YouTubeVerdict.NotFromChannel) settled(now)
        if (verdict !is YouTubeVerdict.NotFromChannel) otherChannelSince = 0L
        when (verdict) {
            is YouTubeVerdict.Leave -> present(verdict.key, (if (verdict.key == PartShorts) shorts else feed)!!, YouTube, now, says = verdict.says)
            // The page may still show the last video's channel for a moment: only a settled look counts.
            is YouTubeVerdict.NotFromChannel -> when {
                otherChannelSince == 0L -> otherChannelSince = now
                now - otherChannelSince >= SettleMs -> {
                    otherChannelSince = 0L
                    present(verdict.key, channelShields.first().second, YouTube, now, says = verdict.says)
                }
            }
            else -> Unit
        }
    }

    private fun readYouTube(root: AccessibilityNodeInfo): YouTubeScreen {
        val m = resources.displayMetrics
        fun shown(id: String) = root.findAccessibilityNodeInfosByViewId("$YouTube:id/$id").filter { it.isVisibleToUser }
        fun bounds(node: AccessibilityNodeInfo) = Rect().also(node::getBoundsInScreen)
        val pivot = shown("pivot_bar").firstOrNull()
        val shortsPlayer = YtShortsIds.firstNotNullOfOrNull { shown(it).firstOrNull() }
        // The open video, full size at the top with the tab bar gone; or the miniplayer, small above the tab bar.
        // A preview playing inside a list is full width with the tab bar showing: neither.
        val player = YtPlayerIds.flatMap(::shown).map(::bounds).firstOrNull { b ->
            if (pivot == null) b.top < m.heightPixels / 3 && b.width() > m.widthPixels * 2 / 5
            else b.height() < m.heightPixels / 6 && b.width() < m.widthPixels / 2
        }
        val expanded = player?.takeIf { pivot == null }
        val texts = when {
            shortsPlayer != null -> nodes(shortsPlayer).mapNotNull(::textOf)
            // Under the open video, its title and channel; beside the miniplayer, the same.
            player != null -> nodes(root).filter { it.isVisibleToUser }.mapNotNull { n ->
                val b = bounds(n)
                val near = if (expanded != null) b.top >= player.bottom && b.top < player.bottom + m.heightPixels / 3 && b.left < player.right
                else b.centerY() in player.top..player.bottom && b.left >= player.right
                if (near) textOf(n) else null
            }
            else -> emptyList()
        }
        // Home is the tab bar's first tab, Shorts its second.
        val chosenTab = pivot?.let { p -> (0 until p.childCount).firstOrNull { p.getChild(it)?.let(::selectedWithin) == true } }
        val home = pivot?.takeIf { chosenTab == 0 }
        val searching = YtSearchIds.any { shown(it).isNotEmpty() }
        val feed = if (home != null && !searching && shortsPlayer == null) shown("results").firstOrNull()?.let(::bounds) else null
        // Everything YouTube suggests around the open video, as YouTube itself lays it out (checked against YouTube 20.44).
        val related = if (shortsPlayer != null || pivot != null) emptyList() else buildList {
            // Under the video (beside it on a wide screen): its details, comments and every suggestion after them.
            shown("watch_list").map(::bounds).forEach(::add)
            // The "Suggested video" that ends a video.
            if (shown("info_panel").isNotEmpty() && shown("cancel_button").isNotEmpty()) shown("watch_player").map(::bounds).forEach(::add)
            // Full screen: the row of videos that slides up from the bottom.
            if (expanded != null && expanded.height() >= m.heightPixels * 9 / 10) {
                nodes(root).filter { it.isVisibleToUser && it.className?.endsWith("RecyclerView") == true }
                    .map(::bounds).filter { it.height() < m.heightPixels / 2 }.forEach(::add)
            }
        }.filterNot(Rect::isEmpty).map { listOf(it.left, it.top, it.right, it.bottom) }
        return YouTubeScreen(
            shorts = shortsPlayer != null,
            shortsTab = shortsPlayer != null && chosenTab == 1,
            homeFeed = feed?.let { it.top to minOf(it.bottom, bounds(pivot!!).top) },
            watch = expanded?.let { listOf(it.left, it.top, it.right, it.bottom) },
            related = related,
            texts = texts,
        )
    }

    private fun selectedWithin(node: AccessibilityNodeInfo): Boolean = node.isSelected || nodes(node).any { it.isSelected }

    private fun textOf(n: AccessibilityNodeInfo): String? = (n.text ?: n.contentDescription)?.toString()?.takeIf(String::isNotBlank)

    /** [root] and everything under it, breadth first, at most [MaxNodes]. */
    private fun nodes(root: AccessibilityNodeInfo): List<AccessibilityNodeInfo> {
        val out = ArrayList<AccessibilityNodeInfo>()
        val queue = ArrayDeque(listOf(root))
        while (queue.isNotEmpty() && out.size < MaxNodes) {
            val n = queue.removeFirst()
            out += n
            for (i in 0 until n.childCount) n.getChild(i)?.let(queue::addLast)
        }
        return out
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        handler.removeCallbacks(watchCover)
        handler.removeCallbacks(clearTicker)
        handler.removeCallbacks(hideNotice)
        handler.removeCallbacks(liftVeil)
        hideCovers()
        veil?.hide()
        notice?.hide()
        notice = null
        presenter?.dismiss()
        connectedState.value = false
        super.onDestroy()
    }

    companion object {
        /** Covers follow a scrolling list: a look every 120 ms keeps them on it. */
        private const val CheckEveryMs = 120L
        /** The veil stays at least this long, so Back has time to land, and at most [VeilMs]. */
        private const val MinVeilMs = 250L
        private const val VeilMs = 1_200L
        private const val RepeatMs = 1_500L
        private const val SettleMs = 1_000L
        private const val MaxNodes = 600
        /** How long a held-back part's line stays, how often a blocked page is checked, and how many Backs at most. */
        private const val NoticeMs = 2_500L
        private const val ClearEveryMs = 600L
        private const val ClearBackMax = 6
        private const val ClearDeadlineMs = 4_000L
        private val InstagramTabs = listOf("feed_tab", "search_tab", "clips_tab", "direct_tab", "profile_tab")
        private val FeedPostIds = listOf("row_feed_profile_header", "row_feed_photo_profile_name", "media_group")
        /** Share on a reel, share on a post, forward in a chat. Checked against Instagram 453. */
        private val IgShareIds = listOf("direct_share_button", "row_feed_button_share", "forwarding_shortcut_button")
        /** YouTube view IDs. They change with app updates: experimental. */
        private val YtShortsIds = listOf("reel_player_page_container", "reel_recycler", "reel_watch_player")
        private val YtPlayerIds = listOf("watch_player", "player_view", "player_fragment_container")
        private val YtSearchIds = listOf("search_query", "search_edit_text", "search_box")
        private val connectedState = MutableStateFlow(false)
        val connected: StateFlow<Boolean> = connectedState
    }
}

/**
 * An opaque rectangle over part of an app (Instagram's posts, YouTube's recommendations), so it can't be seen
 * or touched. It never takes focus: the keyboard, back and everything outside it stay the app's.
 */
private class Cover(
    private val service: AccessibilityService,
    private val onTap: (key: String, decision: Decision.Shield, owner: String) -> Unit,
    /** Can fade away ([hide] with fade): the veil. A cover that follows a list stays opaque. */
    translucent: Boolean = false,
) {
    private val windows = service.getSystemService(WindowManager::class.java)
    private var host: OverlayShieldPresenter.Host? = null
    private val key = mutableStateOf("")
    private val decision = mutableStateOf<Decision.Shield?>(null)
    /** The app it covers part of. */
    var owner: String? = null
        private set
    private val params = WindowManager.LayoutParams(
        0,
        0,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        if (translucent) PixelFormat.TRANSLUCENT else PixelFormat.OPAQUE,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        // Accessibility bounds are screen pixels: no inset or cutout may shift the cover.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) fitInsetsTypes = 0
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        title = "Aurora cover"
    }

    val showing: Boolean get() = host != null

    fun show(bounds: Rect, owner: String, key: String, decision: Decision.Shield) {
        this.owner = owner
        this.key.value = key
        this.decision.value = decision
        val h = host
        if (h != null && params.x == bounds.left && params.y == bounds.top && params.width == bounds.width() && params.height == bounds.height()) return
        params.x = bounds.left
        params.y = bounds.top
        params.width = bounds.width()
        params.height = bounds.height()
        if (h != null) {
            try { windows.updateViewLayout(h.view, params) } catch (_: RuntimeException) { hide() }
            return
        }
        val fresh = OverlayShieldPresenter.Host(service) {}
        fresh.view.addView(ComposeView(service).apply { setContent {
            val d = this@Cover.decision.value
            val k = this@Cover.key.value
            CoverContent(k) { d?.let { onTap(k, it, owner) } }
        } })
        try {
            windows.addView(fresh.view, params)
            fresh.start()
            host = fresh
        } catch (_: RuntimeException) {
            // The service was unbound or the window refused: nothing shows.
        }
    }

    fun hide(fade: Boolean = false) {
        val h = host ?: return
        host = null
        owner = null
        val remove = {
            h.stop()
            try { windows.removeViewImmediate(h.view) } catch (_: RuntimeException) { }
        }
        if (fade) h.view.animate().alpha(0f).setDuration(FadeMs).withEndAction { remove() }.start() else remove()
    }

    private companion object {
        const val FadeMs = 220L
    }
}

/**
 * What a covered part says. The same line is used for a tap's toast, so the cover and the toast never
 * disagree about why the area is blank.
 */
@StringRes
private fun coverSays(key: String): Int = when (key) {
    PartReels -> R.string.part_no_reels
    PartShorts -> R.string.part_no_shorts
    PartYouTubeFeed -> R.string.yt_cover_feed
    PartInstagramSearch -> R.string.part_no_search
    else -> R.string.ig_cover_posts
}

/**
 * A covered part looks like the app around it: plain white in light mode, black at night, with one grey
 * line. It never takes focus: the keyboard, back and everything outside it stay the app's.
 */
@androidx.compose.runtime.Composable
private fun CoverContent(key: String, onTap: () -> Unit) = AuroraTheme(reduceMotion = true) {
    val dark = isSystemInDarkTheme()
    // Tapping leads to Intent's deliberate decision, or says why the part is held back.
    BoxWithConstraints(
        Modifier.fillMaxSize().background(if (dark) Color.Black else Color.White).clickable(onClick = onTap),
        contentAlignment = Alignment.Center,
    ) {
        // A button-sized cover (Instagram's share) says nothing; there's no room.
        if (maxHeight < 120.dp) return@BoxWithConstraints
        Column(Modifier.padding(horizontal = Space.Gutter), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                stringResource(coverSays(key)),
                color = if (dark) Color(0xFF9A9AA0) else Color(0xFF6E6E73),
                style = AuroraType.Body,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/**
 * The small dark line at the foot of the screen for a held-back part of an app. It never takes touch or
 * focus, so the app underneath stays usable; it disappears on its own after [ContentService]'s delay.
 */
private class Notice(private val service: AccessibilityService) {
    private val windows = service.getSystemService(WindowManager::class.java)
    private var shown = false
    private val view = TextView(service).apply {
        setTextColor(0xFFFFFFFF.toInt())
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        gravity = Gravity.CENTER
        val h = (16 * resources.displayMetrics.density).toInt()
        val v = (10 * resources.displayMetrics.density).toInt()
        setPadding(h, v, h, v)
        background = GradientDrawable().apply {
            cornerRadius = 22 * resources.displayMetrics.density
            setColor(0xE6202124.toInt())
        }
    }
    private val params = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        y = (110 * service.resources.displayMetrics.density).toInt()
        title = "Aurora notice"
    }

    fun show(@StringRes text: Int) {
        view.setText(text)
        if (shown) {
            try { windows.updateViewLayout(view, params) } catch (_: RuntimeException) { hide() }
            return
        }
        try {
            windows.addView(view, params)
            shown = true
        } catch (_: RuntimeException) {
            // The service was unbound or the window refused: nothing shows.
        }
    }

    fun hide() {
        if (!shown) return
        shown = false
        try { windows.removeViewImmediate(view) } catch (_: RuntimeException) { }
    }
}
