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
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
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
 * - Content (experimental): when YouTube Shorts or the Instagram Reels tab is on screen and the person
 *   turned that on, it goes back once. A reel opened from a message plays, but swiping to the next one goes
 *   back. Instagram Home posts and Search can be covered, leaving stories and messages. Detection uses view
 *   IDs those apps can change at any update.
 *
 * Nothing it reads is stored, logged or sent.
 */
class ContentService : AccessibilityService() {
    private var lastKey: String? = null
    private var lastAt = 0L
    private var lastCheck = HashMap<String, Long>()
    private var presenter: OverlayShieldPresenter? = null
    /** The browser a site Shield is showing over, so leaving it takes the Shield away. */
    private var shownOver: String? = null
    private var cover: Cover? = null
    /** The reel someone sent, while it plays: a different one on screen means the person swiped on. */
    private var oneReel: AccessibilityNodeInfo? = null
    private var oneReelSince = 0L
    private val handler = Handler(Looper.getMainLooper())
    /** Instagram's events stop when the person leaves it, so the cover watches for that itself. */
    private val watchCover = object : Runnable {
        override fun run() {
            val c = cover?.takeIf { it.showing } ?: return
            val root = rootInActiveWindow
            when (root?.packageName?.toString()) {
                Instagram -> checkInstagram(application as AuroraApplication, root, SystemClock.uptimeMillis())
                packageName -> Unit // The person is touching the cover itself.
                else -> c.hide()
            }
            handler.removeCallbacks(this)
            if (c.showing) handler.postDelayed(this, CheckEveryMs)
        }
    }

    override fun onServiceConnected() {
        presenter = OverlayShieldPresenter(this) { request, close -> ShieldContent(request, close) }
        cover = Cover(this)
        connectedState.value = true
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val pkg = event.packageName?.toString() ?: return
        val now = SystemClock.uptimeMillis()
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && shownOver != null && pkg != shownOver && pkg != packageName && pkg != "com.android.systemui") {
            presenter?.dismiss()
            shownOver = null
        }
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && pkg != Instagram && pkg != packageName) cover?.hide()
        // Content changes arrive in floods; one look per app every 300 ms is plenty.
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED && now - (lastCheck[pkg] ?: 0L) < CheckEveryMs) return
        lastCheck[pkg] = now
        val root = rootInActiveWindow ?: return
        if (root.packageName?.toString() != pkg) return
        val app = application as AuroraApplication
        BrowserAddressBars[pkg]?.let { checkSite(app, pkg, root, it, now) }
        checkContent(app, pkg, root, now)
        if (pkg == Instagram) checkInstagram(app, root, now)
    }

    private fun checkSite(app: AuroraApplication, browser: String, root: AccessibilityNodeInfo, barId: String, now: Long) {
        val bar = root.findAccessibilityNodeInfosByViewId(barId).firstOrNull() ?: return
        // While the person is typing in the address bar, the text isn't a page they're on.
        if (bar.isFocused) return
        val host = hostOf(bar.text?.toString() ?: return) ?: return
        val state = app.policySnapshot(emptySet(), emptyMap()) ?: return
        val key = webKeyFor(host, state.protectedPackages) ?: return
        val decision = decide(key, TrustedClock.wall(), state) as? Decision.Shield ?: return
        if (key == lastKey && now - lastAt < RepeatMs) return
        lastKey = key
        lastAt = now
        if (presenter?.present(ShieldRequest(key, decision.reason, decision.until, now, now, now, decision = decision)) == true) shownOver = browser
    }

    private fun checkContent(app: AuroraApplication, pkg: String, root: AccessibilityNodeInfo, now: Long) {
        val wanted = app.rules.value?.content.orEmpty()
        for ((surface, target) in ContentSurfaces) {
            val (owner, ids) = target
            if (owner != pkg || surface !in wanted) continue
            if (ids.none { root.findAccessibilityNodeInfosByViewId(it).isNotEmpty() }) continue
            if (surface == lastKey && now - lastAt < RepeatMs) return
            lastKey = surface
            lastAt = now
            performGlobalAction(GLOBAL_ACTION_BACK)
            Toast.makeText(this, if (surface == ContentShorts) "Shorts are off in Aurora" else "Reels are off in Aurora", Toast.LENGTH_SHORT).show()
            return
        }
    }

    private fun checkInstagram(app: AuroraApplication, root: AccessibilityNodeInfo, now: Long) {
        val wanted = app.rules.value?.content.orEmpty()
        val reels = ContentReels in wanted
        val feed = ContentInstagramFeed in wanted
        val verdict = if (reels || feed) instagramVerdict(readInstagram(root), reels, feed, resources.displayMetrics.heightPixels) else InstagramVerdict.Allow
        if (verdict is InstagramVerdict.Cover) {
            cover?.show(verdict.top, verdict.bottom, verdict.search)
            handler.removeCallbacks(watchCover)
            handler.postDelayed(watchCover, CheckEveryMs)
        } else {
            cover?.hide()
        }
        if (verdict != InstagramVerdict.OneReel) oneReel = null
        when (verdict) {
            InstagramVerdict.Leave -> leave(ContentReels, now, "Reels are off in Aurora")
            InstagramVerdict.OneReel -> {
                val current = reelOnScreen(root) ?: return
                val first = oneReel
                // The first second is the reel loading in; it may swap its view. After that, a new view is a new reel.
                if (first == null || now - oneReelSince < SettleMs) {
                    if (first == null) oneReelSince = now
                    oneReel = current
                } else if (current != first) {
                    oneReel = null
                    leave(ContentReels, now, getString(R.string.ig_one_reel))
                }
            }
            else -> Unit
        }
    }

    private fun leave(surface: String, now: Long, message: String) {
        if (surface == lastKey && now - lastAt < RepeatMs) return
        lastKey = surface
        lastAt = now
        performGlobalAction(GLOBAL_ACTION_BACK)
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
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

    /** The reel filling the middle of the screen. */
    private fun reelOnScreen(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val m = resources.displayMetrics
        return root.findAccessibilityNodeInfosByViewId(IgId + "clips_video_container").firstOrNull { node ->
            Rect().also(node::getBoundsInScreen).contains(m.widthPixels / 2, m.heightPixels / 2)
        }
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        handler.removeCallbacks(watchCover)
        cover?.hide()
        presenter?.dismiss()
        connectedState.value = false
        super.onDestroy()
    }

    companion object {
        private const val CheckEveryMs = 300L
        private const val RepeatMs = 1_500L
        private const val SettleMs = 1_000L
        private val InstagramTabs = listOf("feed_tab", "search_tab", "clips_tab", "direct_tab", "profile_tab")
        private val FeedPostIds = listOf("row_feed_profile_header", "row_feed_photo_profile_name", "media_group")
        private val connectedState = MutableStateFlow(false)
        val connected: StateFlow<Boolean> = connectedState
    }
}

/**
 * An opaque band over part of Instagram (Home posts or Search), so they can't be seen or touched. It never
 * takes focus: the keyboard, back and everything outside the band stay Instagram's.
 */
private class Cover(private val service: AccessibilityService) {
    private val windows = service.getSystemService(WindowManager::class.java)
    private var host: OverlayShieldPresenter.Host? = null
    private val search = mutableStateOf(false)
    private val params = WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT,
        0,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.OPAQUE,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        // Accessibility bounds are screen pixels: no inset or cutout may shift the band.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) fitInsetsTypes = 0
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        title = "Aurora cover"
    }

    val showing: Boolean get() = host != null

    fun show(top: Int, bottom: Int, search: Boolean) {
        this.search.value = search
        val h = host
        if (h != null && params.y == top && params.height == bottom - top) return
        params.y = top
        params.height = bottom - top
        if (h != null) {
            try { windows.updateViewLayout(h.view, params) } catch (_: RuntimeException) { hide() }
            return
        }
        val fresh = OverlayShieldPresenter.Host(service) {}
        fresh.view.addView(ComposeView(service).apply { setContent { CoverContent(this@Cover.search.value) } })
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
        h.stop()
        try { windows.removeViewImmediate(h.view) } catch (_: RuntimeException) { }
    }
}

@androidx.compose.runtime.Composable
private fun CoverContent(search: Boolean) = AuroraTheme(reduceMotion = true) {
    Box(Modifier.fillMaxSize().background(Aurora.colors.sky), contentAlignment = Alignment.Center) {
        Text(
            stringResource(if (search) R.string.ig_cover_search else R.string.ig_cover_posts),
            Modifier.padding(horizontal = Space.Gutter),
            color = Aurora.colors.textSecondary,
            style = AuroraType.Body,
            textAlign = TextAlign.Center,
        )
    }
}
