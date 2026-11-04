package app.aurora.protect

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.PointF
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.TextView
import androidx.annotation.StringRes
import app.aurora.AuroraApplication
import app.aurora.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.abs

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
    /** Blanks over held-back parts of a screen (Home's feed, the suggestions under a video, a profile's grid). */
    private val covers = mutableListOf<Cover>()
    /** Lets taps through and swallows swipes up and down, so a list can't bring anything new into view. */
    private var gate: Gate? = null
    /**
     * Clear covers over held-back buttons (Instagram's Reels and Search tabs and its share buttons, YouTube's
     * Shorts tab). The button stays in sight; a tap on it opens nothing and only says why.
     */
    private val buttonCovers = mutableListOf<Cover>()
    /** A story was just tapped: until it opens (or this time passes), no cover sits over it. */
    private var storyOpeningUntil = 0L
    /** On a post opened from a chat: where the next post (more from that profile) last was, null before it shows. */
    private var nextPostTop: Int? = null
    /** A plain veil over the whole app while a held-back screen is left, so it's never seen going. */
    private var veil: Cover? = null
    private var veilAt = 0L
    private val liftVeil = Runnable { veil?.hide(fade = true) }
    /** When Aurora last stopped YouTube from playing its suggested next video. */
    private var lastAutoplayStop = 0L
    /** Whether the person reached the post on screen from a chat ([InstagramScreen.fromChat]). */
    private var fromChat = false
    private val onCoverTap = { key: String, decision: Decision.Shield, owner: String, says: Int ->
        lastKey = null
        // A tap only leads anywhere when the Shield itself can: Intent's deliberate decision.
        if (decision.reason.canContinue) present(key, decision, owner, SystemClock.uptimeMillis(), back = false)
        else say(if (says != 0) says else coverSays(key))
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
            if (!anyCover()) return
            requestLook()
            handler.removeCallbacks(this)
            handler.postDelayed(this, CheckEveryMs)
        }
    }

    /**
     * Reading Instagram or YouTube takes many calls into the app (a third of a second on a mid-range phone), so it
     * happens here, off the main thread: the main thread only moves windows, and a blank is drawn the moment it's
     * asked for. Looks are coalesced: only the latest waiting one runs.
     */
    private val worker = Handler(android.os.HandlerThread("aurora-look").apply { start() }.looper)
    private var lookTries = 0
    /** The app last seen in front, on [worker]. */
    private var front: String? = null
    /** Instagram just came to the front: until it shows which screen it's on, it stays blank (at most [OpeningMs]). */
    private var igOpeningUntil = 0L
    private val look = Runnable { lookNow() }

    /** A look at whichever app is in front, on [worker]. */
    private fun requestLook() {
        worker.removeCallbacks(look)
        worker.post(look)
    }

    private fun lookNow() {
        val root = rootInActiveWindow
        val app = application as AuroraApplication
        val now = SystemClock.uptimeMillis()
        val pkg = root?.packageName?.toString()
        if (pkg == Instagram && front != Instagram) igOpeningUntil = now + OpeningMs
        if (pkg != null && pkg != packageName) front = pkg
        when (pkg) {
            Instagram -> checkInstagram(app, root, now)
            YouTube -> checkYouTube(app, root, now)
            packageName -> Unit // The person is touching a cover itself.
            else -> {
                ui { hideCovers() }
                // An app opening sends its first events while the window in front is still the last one: look
                // again in a moment, so a held-back part is hidden from the first frame instead of half a second in.
                if (++lookTries < LookAgainTries) worker.postDelayed(look, LookAgainMs)
                return
            }
        }
        lookTries = 0
    }

    private fun ui(block: () -> Unit) {
        handler.post(block)
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
        gate = Gate(this)
        veil = Cover(this, { _, _, _, _ -> }, translucent = true)
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
        // Any event from another app means that app is in front: covers left from the last one go at once,
        // so Instagram's tab catchers never sit over YouTube's tab bar.
        if (pkg != packageName && coversOwner().let { it != null && it != pkg }) hideCovers()
        if (event.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED) {
            // A story opens under the cover on Home or a profile: the cover goes at once, so the story shows
            // the moment it opens instead of after the next look.
            if (pkg == Instagram && event.source?.let(::inStoryTray) == true) {
                storyOpeningUntil = now + StoryOpenMs
                covers.forEach { it.hide() }
            }
            return
        }
        // Content changes arrive in floods; one look per app every 120 ms keeps covers on what they hide.
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED && now - (lastCheck[pkg] ?: 0L) < CheckEveryMs) return
        lastCheck[pkg] = now
        if (pkg == Instagram || pkg == YouTube) {
            lookTries = 0
            requestLook()
            return
        }
        val root = rootInActiveWindow ?: return
        if (root.packageName?.toString() != pkg) return
        BrowserAddressBars[pkg]?.let { checkSite(application as AuroraApplication, pkg, root, it, now) }
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

    /** A held-back button: where it is, what holds it back, and what a tap on it says. */
    private class Button(val bounds: Rect, val key: String, val decision: Decision.Shield, @StringRes val says: Int = 0)

    /** Keeps one clear cover on each of [buttons], and takes the rest away. */
    private fun keepButtons(owner: String, buttons: List<Button>) {
        val shown = buttons.filterNot { it.bounds.isEmpty }
        while (buttonCovers.size < shown.size) buttonCovers += Cover(this, onCoverTap, clear = true)
        buttonCovers.forEachIndexed { i, c -> shown.getOrNull(i)?.let { c.show(it.bounds, owner, it.key, it.decision, it.says) } ?: c.hide() }
        if (shown.isNotEmpty()) watchSoon()
    }

    private fun anyCover() = coversOwner() != null

    /** The app the covers, catchers and gate now showing are over, null when none shows. */
    private fun coversOwner(): String? = (covers + buttonCovers).firstOrNull { it.showing }?.owner ?: gate?.owner

    /** Whether [node] is inside Instagram's row of stories or a profile's highlights. */
    private fun inStoryTray(node: AccessibilityNodeInfo): Boolean {
        var n: AccessibilityNodeInfo? = node
        repeat(12) {
            if (n?.viewIdResourceName in StoryTrayIds) return true
            n = n?.parent ?: return false
        }
        return false
    }

    /** Shows [text] in the small dark line at the foot of the screen, then takes it away. */
    private fun say(@StringRes text: Int) {
        val n = notice ?: Notice(this).also { notice = it }
        n.show(text)
        handler.removeCallbacks(hideNotice)
        handler.postDelayed(hideNotice, NoticeMs)
    }

    private fun hideCovers() {
        covers.forEach { it.hide() }
        buttonCovers.forEach { it.hide() }
        gate?.hide()
    }

    /** Keeps one blank on each of [areas] for [key], and takes the rest away. */
    private fun keepCovers(owner: String, areas: List<Rect>, key: String?, decision: Decision.Shield?) {
        val shown = if (key == null || decision == null) emptyList() else areas.filterNot(Rect::isEmpty)
        while (covers.size < shown.size) covers += Cover(this, onCoverTap)
        covers.forEachIndexed { i, c -> shown.getOrNull(i)?.let { c.show(it, owner, key!!, decision!!) } ?: c.hide() }
        if (shown.isNotEmpty()) watchSoon()
    }

    /** Keeps [gate] on [bounds], or takes it away. A swipe it swallows says [says]. */
    private fun keepGate(owner: String, bounds: Rect?, @StringRes says: Int) {
        if (bounds == null || bounds.isEmpty) {
            gate?.hide()
            return
        }
        gate?.show(bounds, owner) { say(says) }
        watchSoon()
    }

    private fun watchSoon() {
        handler.removeCallbacks(watchCover)
        handler.postDelayed(watchCover, CheckEveryMs)
    }

    private fun checkInstagram(app: AuroraApplication, root: AccessibilityNodeInfo, now: Long) {
        val state = app.policySnapshot(emptySet(), emptyMap())
        val shields = listOf(PartReels, PartInstagramMessages, PartInstagramFeed, PartInstagramSearch).associateWith { shieldFor(state, it) }
        val messages = shields[PartInstagramMessages]
        val screen = readInstagram(root, legacyFeed = shields[PartInstagramFeed] != null)
        if (screen.storyViewer) storyOpeningUntil = 0L
        val verdict = if (shields.values.any { it != null }) {
            instagramVerdict(
                screen,
                reels = shields[PartReels] != null, messages = messages != null,
                feed = shields[PartInstagramFeed] != null, search = shields[PartInstagramSearch] != null,
                screenBottom = resources.displayMetrics.heightPixels,
            )
        } else InstagramVerdict.Allow
        // A story being opened isn't covered while it opens.
        val width = resources.displayMetrics.widthPixels
        // While Instagram opens, Home's posts draw before its tab bar does: blank until the screen is known.
        val known = screen.tab != null || screen.chat || screen.reelViewer || screen.storyViewer || screen.profileGridTop != null
        if (known) igOpeningUntil = 0L
        val opening = messages != null && now < igOpeningUntil && verdict == InstagramVerdict.Allow
        val c = (verdict as? InstagramVerdict.Cover ?: if (opening) InstagramVerdict.Cover(0, screen.tabBarTop ?: resources.displayMetrics.heightPixels, PartInstagramMessages) else null)
            ?.takeIf { now >= storyOpeningUntil }
        // Never over the status bar: the clock and battery stay.
        val statusBar = resources.getIdentifier("status_bar_height", "dimen", "android").takeIf { it != 0 }?.let(resources::getDimensionPixelSize) ?: 0
        val covered = listOfNotNull(c?.let { Rect(0, maxOf(it.top, statusBar), width, it.bottom) })
        val g = verdict as? InstagramVerdict.Gate
        fun buttons(id: String, key: String, says: Int = 0) =
            shields[key]?.let { d -> shown(root, IgId + id).map { Button(boundsOf(it), key, d, says) } }.orEmpty()
        fun tab(id: String, key: String, says: Int) =
            listOfNotNull(shields[key]?.let { d -> screen.tabBounds[id]?.let { Button(Rect(it[0], it[1], it[2], it[3]), key, d, says) } })
        val tabKey = if (messages != null) PartInstagramMessages else PartReels
        val searchKey = if (messages != null) PartInstagramMessages else PartInstagramSearch
        val buttons = buildList {
            // Home (all but messages), the Reels and Search tabs stay in sight, but a tap opens nothing: it only says why.
            if (messages != null) addAll(tab("feed_tab", PartInstagramMessages, R.string.part_no_home))
            addAll(tab("clips_tab", tabKey, R.string.part_no_reels))
            addAll(tab("search_tab", searchKey, R.string.part_no_search))
            // A friend's reel or post can be watched, not passed on: every share and forward button holds back.
            if (messages != null && (screen.reelViewer || screen.posts)) IgShareIds.forEach { addAll(buttons(it, PartInstagramMessages)) }
            // A profile's highlights and picture open stories.
            if (messages != null && screen.profileGridTop != null) IgStoryIds.forEach { addAll(buttons(it, PartInstagramMessages, R.string.part_no_stories)) }
        }
        ui {
            keepCovers(Instagram, covered, c?.key, c?.let { shields[it.key] })
            keepGate(Instagram, g?.let { Rect(0, it.top, width, it.bottom) }, g?.says ?: 0)
            keepButtons(Instagram, buttons)
            if (verdict is InstagramVerdict.Leave) present(verdict.key, shields[verdict.key]!!, Instagram, now, says = verdict.says) else settled(now)
        }
    }

    /** One look at Instagram. Each lookup is a call into Instagram, so only what this screen can need is asked. */
    private fun readInstagram(root: AccessibilityNodeInfo, legacyFeed: Boolean): InstagramScreen {
        fun shown(id: String) = shown(root, IgId + id)
        fun bounds(id: String) = shown(id).firstOrNull()?.let(::boundsOf)
        // The tab bar's own children are the tabs: one lookup gives which is chosen and where each is. Instagram
        // marks the chosen tab itself, or (older versions) its icon inside.
        val tabBar = shown("tab_bar").firstOrNull()
        val tabs = tabBar?.let { bar -> (0 until bar.childCount).mapNotNull(bar::getChild) }.orEmpty()
        fun idOf(n: AccessibilityNodeInfo) = n.viewIdResourceName?.removePrefix(IgId)
        val tab = (tabs.firstOrNull { it.isSelected } ?: tabs.firstOrNull { n -> (0 until n.childCount).any { n.getChild(it)?.isSelected == true } })?.let(::idOf)
        val posts = FeedPostIds.any { shown(it).isNotEmpty() }
        val profileGrid = bounds("profile_tabs_container") ?: bounds("profile_viewpager")
        val reelPager = bounds("clips_viewer_view_pager")
        // A post opened from a chat has more of its profile's posts under it. The next post starts at the second
        // post header; once that has gone off the top, every post on screen is a later one.
        val headers = if (posts) shown("row_feed_profile_header").map { boundsOf(it).top }.sorted() else emptyList()
        if (!posts) nextPostTop = null
        val laterPosts = when {
            headers.size >= 2 -> headers[1].also { nextPostTop = it }
            nextPostTop?.let { it < resources.displayMetrics.heightPixels / 2 } == true -> 0
            else -> null
        }
        // Where the person came from: a post counts as sent only when they opened it from a chat. A profile
        // or comments on the way don't change that; any other screen does.
        val chat = shown("thread_fragment_container").isNotEmpty()
        when {
            chat -> fromChat = true
            profileGrid != null || posts && tab != "feed_tab" -> Unit
            else -> fromChat = false
        }
        return InstagramScreen(
            tab = tab,
            chat = chat,
            reelViewer = reelPager != null,
            reelArea = reelPager?.let { it.top to it.bottom },
            busy = reelPager != null && (root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.isEditable == true || IgSheetIds.any { shown(it).isNotEmpty() }),
            sentInChat = reelPager != null && shown("sender_username_or_fullname").isNotEmpty(),
            storyViewer = shown("reel_viewer_root").isNotEmpty(),
            posts = posts,
            fromChat = fromChat,
            reelsPage = shown("clips_pivot_page_fragment").isNotEmpty(),
            profileGridTop = profileGrid?.top,
            laterPostsTop = laterPosts,
            storiesBottom = if (legacyFeed) bounds("reels_tray_container")?.bottom else null,
            headerBottom = bounds("action_bar_container")?.bottom,
            tabBarTop = tabBar?.let(::boundsOf)?.top,
            tabBounds = tabs.associate { (idOf(it) ?: "") to boundsOf(it).let { b -> listOf(b.left, b.top, b.right, b.bottom) } },
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
        fun rect(b: List<Int>) = Rect(b[0], b[1], b[2], b[3])
        // The video that ends counts down to a suggested one: stop it, so a recommendation never plays itself.
        if (feed != null && now - lastAutoplayStop > RepeatMs && shownIn(root, YouTube, "info_panel").isNotEmpty()) {
            shownIn(root, YouTube, "cancel_button").firstOrNull()?.let {
                lastAutoplayStop = now
                it.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }
        }
        // The Shorts tab stays in sight, but a tap opens nothing: it only says why. Found by its name, so a YouTube in another language
        // keeps the button and leaves the tab once it opens.
        val tabShield = shorts ?: feed
        val shortsTab = tabShield?.let {
            shownIn(root, YouTube, "pivot_bar").firstOrNull()?.let(::pivotTabs)?.firstOrNull { textOf(it).equals("Shorts", ignoreCase = true) }?.let(::boundsOf)
        }
        val buttons = listOfNotNull(shortsTab?.let { Button(it, if (shorts != null) PartShorts else PartYouTubeFeed, tabShield, R.string.part_no_shorts) })
        if (verdict !is YouTubeVerdict.NotFromChannel) otherChannelSince = 0L
        // The page may still show the last video's channel for a moment: only a settled look counts.
        val notFromChannel = verdict is YouTubeVerdict.NotFromChannel && when {
            otherChannelSince == 0L -> { otherChannelSince = now; false }
            now - otherChannelSince >= SettleMs -> { otherChannelSince = 0L; true }
            else -> false
        }
        ui {
            keepCovers(YouTube, c?.areas.orEmpty().map(::rect), c?.key, feed)
            keepGate(YouTube, c?.gate?.let(::rect), R.string.yt_cover_feed)
            keepButtons(YouTube, buttons)
            if (verdict !is YouTubeVerdict.Leave && verdict !is YouTubeVerdict.NotFromChannel) settled(now)
            when {
                verdict is YouTubeVerdict.Leave -> present(verdict.key, (if (verdict.key == PartShorts) shorts else feed)!!, YouTube, now, says = verdict.says)
                notFromChannel && verdict is YouTubeVerdict.NotFromChannel -> present(verdict.key, channelShields.first().second, YouTube, now, says = verdict.says)
            }
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
        val chosenTab = pivot?.let(::pivotTabs)?.indexOfFirst(::selectedWithin)?.takeIf { it >= 0 }
        // Home has its own row of tabs (Home, Subscriptions): only its first is the feed.
        val topTabs = shown("tabs_bar").firstOrNull()
        val topTab = topTabs?.let { t -> (0 until t.childCount).firstOrNull { t.getChild(it)?.let(::selectedWithin) == true } } ?: 0
        val home = pivot?.takeIf { chosenTab == 0 && topTab == 0 }
        val searching = YtSearchIds.any { shown(it).isNotEmpty() }
        val feed = if (home != null && !searching && shortsPlayer == null) shown("results").firstOrNull()?.let(::bounds) else null
        // Under the video (beside it on a wide screen): its details, comments, then every suggestion. Checked against
        // YouTube 21.41: the details and comments are short rows; a suggestion is a tall card, or the last row,
        // cut off at the foot of the list.
        val watchList = if (shortsPlayer != null || pivot != null) null else shown("watch_list").firstOrNull()
        val listBounds = watchList?.let(::bounds)?.takeUnless(Rect::isEmpty)
        val suggestionsTop = listBounds?.let { list ->
            (0 until watchList!!.childCount).mapNotNull(watchList::getChild).filter { it.isVisibleToUser }.map(::bounds)
                .firstOrNull { it.height() > list.width() / 3 || (it.bottom >= list.bottom && it.top > list.top) }?.top
        }
        fun ltrb(r: Rect) = listOf(r.left, r.top, r.right, r.bottom)
        // Everything else YouTube suggests around the open video.
        val related = if (shortsPlayer != null || pivot != null) emptyList() else buildList {
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
            // Down to the tab bar, or the miniplayer when a video is minimised over Home.
            homeFeed = feed?.let { it.top to minOf(it.bottom, bounds(pivot!!).top, player?.top ?: Int.MAX_VALUE) },
            watchList = listBounds?.let(::ltrb),
            suggestionsTop = suggestionsTop,
            playlistBar = listBounds?.let { shown("playlist_entry_point_container").firstOrNull()?.let(::bounds)?.let(::ltrb) },
            panelOpen = shown("engagement_panel").isNotEmpty(),
            watch = expanded?.let { listOf(it.left, it.top, it.right, it.bottom) },
            related = related,
            texts = texts,
        )
    }

    /**
     * YouTube's tab buttons (Home, Shorts, Create, You), left to right. They sit inside a container in the tab bar,
     * so they're found as its tappable nodes, never as its children: a child can be the whole bar.
     */
    private fun pivotTabs(pivot: AccessibilityNodeInfo): List<AccessibilityNodeInfo> =
        nodes(pivot).filter { it !== pivot && it.isClickable }.sortedBy { boundsOf(it).left }

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
        worker.removeCallbacksAndMessages(null)
        worker.looper.quitSafely()
        hideCovers()
        gate = null
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
        private const val LookAgainMs = 40L
        private const val LookAgainTries = 25
        private const val OpeningMs = 1_500L
        private const val SettleMs = 1_000L
        private const val MaxNodes = 600
        /** How long a held-back part's line stays, how often a blocked page is checked, and how many Backs at most. */
        private const val NoticeMs = 2_500L
        private const val ClearEveryMs = 600L
        private const val ClearBackMax = 6
        private const val ClearDeadlineMs = 4_000L
        private val FeedPostIds = listOf("row_feed_profile_header", "row_feed_photo_profile_name", "media_group")
        /** Share on a reel, share on a post, forward in a chat. Checked against Instagram 453. */
        private val IgShareIds = listOf("direct_share_button", "row_feed_button_share", "forwarding_shortcut_button")
        /** YouTube view IDs. They change with app updates: experimental. */
        private val YtShortsIds = listOf("reel_player_page_container", "reel_recycler", "reel_watch_player")
        private val YtPlayerIds = listOf("watch_player", "player_view", "player_fragment_container")
        private val YtSearchIds = listOf("search_query", "search_edit_text", "search_box")
        /** Instagram's stories row on Home and a profile's highlights. Checked against Instagram 453. */
        private val StoryTrayIds = setOf(IgId + "reels_tray_container", IgId + "highlights_tray")
        /** A profile's highlights and picture, which open stories. Checked against Instagram 453. */
        private val IgStoryIds = listOf("highlights_tray", "row_profile_header_imageview_frame_layout")
        /** Sheets Instagram opens over a reel (comments, shares). */
        private val IgSheetIds = listOf("bottom_sheet_container", "bottom_sheet_container_view", "bottom_sheet_drag_handle", "bottom_sheet_drag_handle_frame")
        /** How long a tapped story has to open before covers may come back over it. */
        private const val StoryOpenMs = 1_000L
        private val connectedState = MutableStateFlow(false)
        val connected: StateFlow<Boolean> = connectedState
    }
}

/**
 * A blank over part of an app (Home's feed, the suggestions under a video, a profile's grid), painted the app's
 * own page colour with one grey line, so the part looks gone rather than covered. It can't be seen through or
 * touched, and never takes focus. Plain views, not Compose, so it's up in the same frame it's asked for.
 */
private class Cover(
    private val service: AccessibilityService,
    private val onTap: (key: String, decision: Decision.Shield, owner: String, says: Int) -> Unit,
    /** Can fade away ([hide] with fade): the veil. A cover that follows a list stays opaque. */
    translucent: Boolean = false,
    /** Invisible: the button under it stays in sight, and only the tap is caught. */
    private val clear: Boolean = false,
) {
    private val windows = service.getSystemService(WindowManager::class.java)
    private var key = ""
    private var decision: Decision.Shield? = null
    @StringRes private var says = 0
    private var added = false
    /** The app it covers part of. */
    var owner: String? = null
        private set
    private val line = TextView(service).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        gravity = Gravity.CENTER
        val pad = (24 * resources.displayMetrics.density).toInt()
        setPadding(pad, 0, pad, 0)
    }
    private val view = android.widget.FrameLayout(service).apply {
        if (!clear) addView(line, android.widget.FrameLayout.LayoutParams(-1, -2, Gravity.CENTER))
        setOnClickListener { decision?.let { onTap(key, it, owner ?: return@let, says) } }
    }
    private val params = WindowManager.LayoutParams(
        0,
        0,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        if (translucent || clear) PixelFormat.TRANSLUCENT else PixelFormat.OPAQUE,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        // Accessibility bounds are screen pixels: no inset or cutout may shift the cover.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) fitInsetsTypes = 0
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        title = "Aurora cover"
    }

    val showing: Boolean get() = added

    fun show(bounds: Rect, owner: String, key: String, decision: Decision.Shield, @StringRes says: Int = 0) {
        this.owner = owner
        this.says = says
        this.key = key
        this.decision = decision
        if (!clear) {
            val dark = darkTheme(service)
            view.setBackgroundColor(if (!dark) 0xFFFFFFFF.toInt() else if (owner == YouTube) 0xFF0F0F0F.toInt() else 0xFF000000.toInt())
            line.setTextColor(if (dark) 0xFF9A9AA0.toInt() else 0xFF6E6E73.toInt())
            line.setText(coverSays(key))
            // A button-sized cover (Instagram's share) says nothing; there's no room.
            line.visibility = if (bounds.height() < 120 * service.resources.displayMetrics.density) android.view.View.GONE else android.view.View.VISIBLE
        }
        if (added && params.x == bounds.left && params.y == bounds.top && params.width == bounds.width() && params.height == bounds.height()) return
        params.x = bounds.left
        params.y = bounds.top
        params.width = bounds.width()
        params.height = bounds.height()
        try {
            if (added) windows.updateViewLayout(view, params)
            else {
                view.animate().cancel()
                view.alpha = 1f
                windows.addView(view, params)
                added = true
            }
        } catch (_: RuntimeException) {
            // The service was unbound or the window refused: nothing shows.
            if (added) hide()
        }
    }

    fun hide(fade: Boolean = false) {
        if (!added) return
        added = false
        owner = null
        val remove = { try { windows.removeViewImmediate(view) } catch (_: RuntimeException) { } }
        if (fade) view.animate().alpha(0f).setDuration(FadeMs).withEndAction { if (!added) remove() }.start() else remove()
    }

    private companion object {
        const val FadeMs = 220L
    }
}

/** The phone's theme, asked of the system: a service's own configuration can be stale. */
private fun darkTheme(context: android.content.Context): Boolean =
    when (context.getSystemService(android.app.UiModeManager::class.java)?.nightMode) {
        android.app.UiModeManager.MODE_NIGHT_YES -> true
        android.app.UiModeManager.MODE_NIGHT_NO -> false
        else -> context.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
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
 * A clear window that lets taps, long presses and sideways swipes through to the app under it, and swallows
 * swipes up and down: the list under it doesn't move at all, so nothing new comes into view. Each gesture is
 * caught whole, then played back to the app with the window briefly untouchable.
 */
private class Gate(private val service: AccessibilityService) {
    private val windows = service.getSystemService(WindowManager::class.java)
    private val slop = ViewConfiguration.get(service).scaledTouchSlop
    private val points = ArrayList<PointF>()
    private var downAt = 0L
    private var onBlocked: () -> Unit = {}
    /** The app it's over, null when it isn't showing. */
    var owner: String? = null
        private set
    private val params = WindowManager.LayoutParams(
        0,
        0,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) fitInsetsTypes = 0
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        title = "Aurora gate"
    }
    @android.annotation.SuppressLint("ClickableViewAccessibility")
    private val view = android.view.View(service).apply { setOnTouchListener { _, e -> touch(e); true } }

    fun show(bounds: Rect, owner: String, onBlocked: () -> Unit) {
        this.onBlocked = onBlocked
        val was = this.owner
        this.owner = owner
        if (was != null && params.x == bounds.left && params.y == bounds.top && params.width == bounds.width() && params.height == bounds.height()) return
        params.x = bounds.left
        params.y = bounds.top
        params.width = bounds.width()
        params.height = bounds.height()
        try {
            if (was != null) windows.updateViewLayout(view, params) else windows.addView(view, params)
        } catch (_: RuntimeException) {
            this.owner = null
        }
    }

    fun hide() {
        if (owner == null) return
        owner = null
        try { windows.removeViewImmediate(view) } catch (_: RuntimeException) { }
    }

    private fun touch(e: MotionEvent) {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                points.clear()
                downAt = e.eventTime
                points += PointF(e.rawX, e.rawY)
            }
            MotionEvent.ACTION_MOVE -> points += PointF(e.rawX, e.rawY)
            MotionEvent.ACTION_UP -> {
                points += PointF(e.rawX, e.rawY)
                val dx = points.last().x - points.first().x
                val dy = points.last().y - points.first().y
                if (abs(dy) > slop && abs(dy) >= abs(dx)) onBlocked() else pass(e.eventTime - downAt, moved = abs(dx) > slop)
            }
        }
    }

    /** Plays the caught tap, long press or sideways swipe back to the app. */
    private fun pass(duration: Long, moved: Boolean) {
        val path = Path().apply {
            moveTo(points.first().x.coerceAtLeast(0f), points.first().y.coerceAtLeast(0f))
            if (moved) points.drop(1).forEach { lineTo(it.x.coerceAtLeast(0f), it.y.coerceAtLeast(0f)) }
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, duration.coerceIn(1L, GestureDescription.getMaxGestureDuration())))
            .build()
        // ponytail: a second finger landing during the replay (a few ms) reaches the app directly.
        touchable(false)
        val back = object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) = touchable(true)
            override fun onCancelled(gestureDescription: GestureDescription?) = touchable(true)
        }
        if (!service.dispatchGesture(gesture, back, null)) touchable(true)
    }

    private fun touchable(on: Boolean) {
        if (owner == null) return
        params.flags = if (on) params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        else params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        try { windows.updateViewLayout(view, params) } catch (_: RuntimeException) { }
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
