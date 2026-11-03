package app.aurora.protect

import android.accessibilityservice.AccessibilityService
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.aurora.AuroraApplication
import app.aurora.R
import app.aurora.ui.theme.Aurora
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
    /** Instagram's share button on a sent reel or post, under "only messages and stories". */
    private var shareCover: Cover? = null
    /** The reel someone sent, while it plays: a different one on screen means the person swiped on. */
    private var oneReel: AccessibilityNodeInfo? = null
    private var oneReelSince = 0L
    /** When YouTube started showing a video from a channel that isn't chosen, 0 when it isn't. */
    private var otherChannelSince = 0L
    private val handler = Handler(Looper.getMainLooper())
    /** An app's events stop when the person leaves it, so the cover watches for that itself. */
    private val watchCover = object : Runnable {
        override fun run() {
            val c = cover?.takeIf { it.showing } ?: return
            val root = rootInActiveWindow
            val app = application as AuroraApplication
            when (root?.packageName?.toString()) {
                Instagram -> checkInstagram(app, root, SystemClock.uptimeMillis())
                YouTube -> checkYouTube(app, root, SystemClock.uptimeMillis())
                packageName -> Unit // The person is touching the cover itself.
                else -> hideCovers()
            }
            handler.removeCallbacks(this)
            if (c.showing) handler.postDelayed(this, CheckEveryMs)
        }
    }

    override fun onServiceConnected() {
        presenter = OverlayShieldPresenter(this) { request, close -> ShieldContent(request, close) }.apply {
            // Leaving a part keeps the app: back out of a held-back screen; a covered part keeps its cover.
            leaveAction = { key ->
                when {
                    !key.startsWith(PartPrefix) -> GLOBAL_ACTION_HOME
                    leaveBack -> GLOBAL_ACTION_BACK
                    else -> null
                }
            }
        }
        val onTap = { key: String, decision: Decision.Shield, owner: String ->
            lastKey = null
            present(key, decision, owner, SystemClock.uptimeMillis(), back = false)
        }
        cover = Cover(this, onTap)
        shareCover = Cover(this, onTap)
        connectedState.value = true
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val pkg = event.packageName?.toString() ?: return
        val now = SystemClock.uptimeMillis()
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && shownOver != null && pkg != shownOver && pkg != packageName && pkg != "com.android.systemui") {
            presenter?.dismiss()
            shownOver = null
        }
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && pkg != packageName && pkg != cover?.owner) hideCovers()
        // Content changes arrive in floods; one look per app every 300 ms is plenty.
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
        val bar = root.findAccessibilityNodeInfosByViewId(barId).firstOrNull() ?: return
        // While the person is typing in the address bar, the text isn't a page they're on.
        if (bar.isFocused) return
        val host = hostOf(bar.text?.toString() ?: return) ?: return
        val state = app.policySnapshot(emptySet(), emptyMap()) ?: return
        val key = webKeyFor(host, state.protectedPackages) ?: return
        val decision = decide(key, TrustedClock.wall(), state) as? Decision.Shield ?: return
        present(key, decision, browser, now)
    }

    /** The Shield [key] is under right now, or null when nothing holds it back. */
    private fun shieldFor(state: PolicyState?, key: String): Decision.Shield? =
        state?.let { decide(key, TrustedClock.wall(), it) as? Decision.Shield }

    /** The Shield for [key] over [owner]. [back]: leaving it goes back inside the app. */
    private fun present(key: String, decision: Decision.Shield, owner: String, now: Long, back: Boolean = true) {
        if (key == lastKey && now - lastAt < RepeatMs) return
        lastKey = key
        lastAt = now
        leaveBack = back
        if (presenter?.present(ShieldRequest(key, decision.reason, decision.until, now, now, now, decision = decision)) == true) shownOver = owner
    }

    private fun hideCovers() {
        cover?.hide()
        shareCover?.hide()
    }

    /** Keeps [cover] on [bounds] for [key], or takes it away. */
    private fun keepCover(owner: String, bounds: Rect?, key: String?, decision: Decision.Shield?) {
        if (bounds == null || key == null || decision == null) return cover?.hide() ?: Unit
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
        // A friend's reel or post can be watched, not passed on.
        val share = if (messages != null && c == null) readInstagramShare(root) else null
        if (share != null) shareCover?.show(share, Instagram, PartInstagramMessages, messages!!) else shareCover?.hide()
        if (verdict != InstagramVerdict.OneReel) oneReel = null
        when (verdict) {
            is InstagramVerdict.Leave -> present(verdict.key, shields[verdict.key]!!, Instagram, now)
            InstagramVerdict.OneReel -> {
                val current = reelOnScreen(root) ?: return
                val first = oneReel
                // The first second is the reel loading in; it may swap its view. After that, a new view is a new reel.
                if (first == null || now - oneReelSince < SettleMs) {
                    if (first == null) oneReelSince = now
                    oneReel = current
                } else if (current != first) {
                    oneReel = null
                    present(PartInstagramMessages, messages!!, Instagram, now)
                }
            }
            else -> Unit
        }
    }

    private fun readInstagram(root: AccessibilityNodeInfo): InstagramScreen {
        fun shown(id: String) = root.findAccessibilityNodeInfosByViewId(IgId + id).filter { it.isVisibleToUser }
        fun bounds(id: String) = shown(id).firstOrNull()?.let { node -> Rect().also(node::getBoundsInScreen) }
        return InstagramScreen(
            tab = InstagramTabs.firstOrNull { id -> shown(id).any { it.isSelected } },
            reelViewer = shown("clips_viewer_view_pager").isNotEmpty() || shown("clips_video_container").isNotEmpty(),
            storyViewer = shown("reel_viewer_root").isNotEmpty(),
            posts = FeedPostIds.any { shown(it).isNotEmpty() },
            storiesBottom = bounds("reels_tray_container")?.bottom,
            headerBottom = bounds("action_bar_container")?.bottom,
            tabBarTop = bounds("tab_bar")?.top,
        )
    }

    /** Instagram's share button for the reel or post on screen, if one shows. */
    private fun readInstagramShare(root: AccessibilityNodeInfo): Rect? {
        val node = IgShareIds.firstNotNullOfOrNull { id -> root.findAccessibilityNodeInfosByViewId(IgId + id).firstOrNull { it.isVisibleToUser } }
            ?: nodes(root).firstOrNull { n -> n.isVisibleToUser && n.contentDescription?.toString()?.lowercase() in IgShareLabels }
        return node?.let { Rect().also(it::getBoundsInScreen) }?.takeUnless { it.isEmpty }
    }

    /** The reel filling the middle of the screen. */
    private fun reelOnScreen(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val m = resources.displayMetrics
        return root.findAccessibilityNodeInfosByViewId(IgId + "clips_video_container").firstOrNull { node ->
            Rect().also(node::getBoundsInScreen).contains(m.widthPixels / 2, m.heightPixels / 2)
        }
    }

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
        if (verdict !is YouTubeVerdict.NotFromChannel) otherChannelSince = 0L
        when (verdict) {
            is YouTubeVerdict.Leave -> present(verdict.key, (if (verdict.key == PartShorts) shorts else feed)!!, YouTube, now)
            // The page may still show the last video's channel for a moment: only a settled look counts.
            is YouTubeVerdict.NotFromChannel -> when {
                otherChannelSince == 0L -> otherChannelSince = now
                now - otherChannelSince >= SettleMs -> {
                    otherChannelSince = 0L
                    present(verdict.key, channelShields.first().second, YouTube, now)
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
        // Home is the tab bar's first tab.
        val home = pivot?.takeIf { p -> (0 until p.childCount).firstOrNull { p.getChild(it)?.let(::selectedWithin) == true } == 0 }
        val searching = YtSearchIds.any { shown(it).isNotEmpty() }
        val feed = if (home != null && !searching && shortsPlayer == null) shown("results").firstOrNull()?.let(::bounds) else null
        return YouTubeScreen(
            shorts = shortsPlayer != null,
            homeFeed = feed?.let { it.top to minOf(it.bottom, bounds(pivot!!).top) },
            watch = expanded?.let { listOf(it.left, it.top, it.right, it.bottom) },
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
        hideCovers()
        presenter?.dismiss()
        connectedState.value = false
        super.onDestroy()
    }

    companion object {
        private const val CheckEveryMs = 300L
        private const val RepeatMs = 1_500L
        private const val SettleMs = 1_000L
        private const val MaxNodes = 600
        private val InstagramTabs = listOf("feed_tab", "search_tab", "clips_tab", "direct_tab", "profile_tab")
        private val FeedPostIds = listOf("row_feed_profile_header", "row_feed_photo_profile_name", "media_group")
        /** Unverified against a device yet; the content description below is the fallback. */
        private val IgShareIds = listOf("row_feed_button_share", "clips_ufi_share_button", "direct_share_button")
        private val IgShareLabels = setOf("share", "send post", "share post", "send reel")
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
private class Cover(private val service: AccessibilityService, private val onTap: (key: String, decision: Decision.Shield, owner: String) -> Unit) {
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
        PixelFormat.OPAQUE,
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
            CoverContent(k, d?.reason?.canContinue == true) { d?.let { onTap(k, it, owner) } }
        } })
        try {
            windows.addView(fresh.view, params)
            fresh.start()
            host = fresh
        } catch (_: RuntimeException) {
            // The service was unbound or the window refused: nothing shows.
        }
    }

    fun hide() {
        val h = host ?: return
        host = null
        owner = null
        h.stop()
        try { windows.removeViewImmediate(h.view) } catch (_: RuntimeException) { }
    }
}

@androidx.compose.runtime.Composable
private fun CoverContent(key: String, canContinue: Boolean, onTap: () -> Unit) = AuroraTheme(reduceMotion = true) {
    // Tapping shows the Shield, which offers the mode's way through (a wait or a task) or says it's blocked.
    BoxWithConstraints(Modifier.fillMaxSize().background(Aurora.colors.sky).clickable(onClick = onTap), contentAlignment = Alignment.Center) {
        // A button-sized cover (Instagram's share) says nothing; there's no room.
        if (maxHeight < 120.dp) return@BoxWithConstraints
        Column(Modifier.padding(horizontal = Space.Gutter), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                stringResource(
                    when (key) {
                        PartInstagramSearch -> R.string.ig_cover_search
                        PartYouTubeFeed -> R.string.yt_cover_feed
                        else -> R.string.ig_cover_posts
                    },
                ),
                color = Aurora.colors.textSecondary,
                style = AuroraType.Body,
                textAlign = TextAlign.Center,
            )
            if (canContinue) Text(
                stringResource(R.string.part_cover_tap),
                Modifier.padding(top = Space.XS),
                color = Aurora.colors.textTertiary,
                style = AuroraType.Caption,
                textAlign = TextAlign.Center,
            )
        }
    }
}
