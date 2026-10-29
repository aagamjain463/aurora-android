package app.aurora.protect

import android.accessibilityservice.AccessibilityService
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import app.aurora.AuroraApplication
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The optional Web & content Shield (res/xml/content_service.xml). Separate from [ShieldService], so the
 * broader access it needs is never on unless the person turns this one on, after aurora's disclosure.
 *
 * - Websites: in the browsers in [BrowserAddressBars], it reads the address field and asks [decide]
 *   about "web:<domain>". Pages in other browsers, in-app browsers and private tabs a browser hides from
 *   accessibility aren't covered, and the screens say so.
 * - Content (experimental): when YouTube Shorts or Instagram Reels is on screen and the person turned that
 *   on, it goes back once. Detection uses view IDs those apps can change at any update.
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

    override fun onServiceConnected() {
        presenter = OverlayShieldPresenter(this) { request, close -> ShieldContent(request, close) }
        connectedState.value = true
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val pkg = event.packageName?.toString() ?: return
        val now = SystemClock.uptimeMillis()
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && shownOver != null && pkg != shownOver && pkg != packageName && pkg != "com.android.systemui") {
            presenter?.dismiss()
            shownOver = null
        }
        // Content changes arrive in floods; one look per app every 300 ms is plenty.
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED && now - (lastCheck[pkg] ?: 0L) < CheckEveryMs) return
        lastCheck[pkg] = now
        val root = rootInActiveWindow ?: return
        if (root.packageName?.toString() != pkg) return
        val app = application as AuroraApplication
        BrowserAddressBars[pkg]?.let { checkSite(app, pkg, root, it, now) }
        checkContent(app, pkg, root, now)
    }

    private fun checkSite(app: AuroraApplication, browser: String, root: AccessibilityNodeInfo, barId: String, now: Long) {
        val bar = root.findAccessibilityNodeInfosByViewId(barId).firstOrNull() ?: return
        // While the person is typing in the address bar, the text isn't a page they're on.
        if (bar.isFocused) return
        val host = hostOf(bar.text?.toString() ?: return) ?: return
        val state = app.policySnapshot(emptySet(), emptyMap()) ?: return
        val key = webKeyFor(host, state.protectedPackages) ?: return
        val decision = decide(key, System.currentTimeMillis(), state) as? Decision.Shield ?: return
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
            Toast.makeText(this, if (surface == ContentShorts) "Shorts are off in aurora" else "Reels are off in aurora", Toast.LENGTH_SHORT).show()
            return
        }
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        presenter?.dismiss()
        connectedState.value = false
        super.onDestroy()
    }

    companion object {
        private const val CheckEveryMs = 300L
        private const val RepeatMs = 1_500L
        private val connectedState = MutableStateFlow(false)
        val connected: StateFlow<Boolean> = connectedState
    }
}
