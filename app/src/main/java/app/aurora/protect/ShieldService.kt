package app.aurora.protect

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import androidx.core.content.ContextCompat
import app.aurora.AuroraApplication
import app.aurora.commitScheduleWindows
import app.aurora.correctIntentUse
import app.aurora.foregroundChanged
import app.aurora.settleIntent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * The production foreground-app adapter. It receives window-state changes only
 * (res/xml/shield_service.xml: no window content, no key events, no gestures), and passes the package
 * name to [Interceptor]. Only the person turns it on, in Android's Accessibility settings.
 *
 * The Shield is an accessibility overlay it owns (ShieldOverlay.kt). It also tells aurora which app is
 * in front, so Intentional App Opening counts real foreground time, and pauses that count while the
 * screen is off.
 */
class ShieldService : AccessibilityService() {
    private var scope: CoroutineScope? = null
    private var interceptor: Interceptor? = null
    private var presenter: OverlayShieldPresenter? = null
    private var lastCommitCheck = 0L
    private var screenReceiver: BroadcastReceiver? = null

    /** Today's use of apps with a daily limit, re-measured while one is open. Empty without usage access. */
    @Volatile private var usedToday: Map<String, Long> = emptyMap()

    override fun onServiceConnected() {
        val app = application as AuroraApplication
        val main = Handler(Looper.getMainLooper())
        val presenter = OverlayShieldPresenter(this) { request, close -> ShieldContent(request, close) }
        presenter.onClosed = { home -> interceptor?.closedByPerson(home) }
        this.presenter = presenter
        val exempt = exemptPackages(this)
        val interceptor = Interceptor(
            presenter, trace, packageName, overlayPackages(this),
            wallClock = System::currentTimeMillis, uptime = SystemClock::uptimeMillis,
            schedule = { delay, block -> main.postDelayed(block, delay) },
            onAllowed = ::measure,
            onForeground = { pkg -> scope?.launch { runCatching { app.foregroundChanged(pkg) } } },
        )
        // Built from memory for every event: grants, schedules and Focus are never stale.
        interceptor.snapshot = { app.policySnapshot(exempt, usedToday) }
        this.interceptor = interceptor
        scope?.cancel()
        scope = MainScope().apply {
            // A rule that starts while an app is open (Always Block, Focus, a new limit) applies to it at once.
            launch { combine(app.rules.state, app.runtime.state, app.protectedList) { _, _, _ -> }.collect { interceptor.recheck() } }
        }
        screenReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                // Screen off: nobody is using the app in front. Screen on: decide again for it.
                if (intent.action == Intent.ACTION_SCREEN_OFF) scope?.launch { runCatching { app.foregroundChanged(null) } }
                else interceptor.recheck()
            }
        }.also {
            val filter = IntentFilter(Intent.ACTION_SCREEN_OFF).apply { addAction(Intent.ACTION_SCREEN_ON) }
            ContextCompat.registerReceiver(this, it, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        }
        measure(null)
        connectedState.value = true
        trace.lifecycle("connected", SystemClock.uptimeMillis())
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val received = SystemClock.uptimeMillis()
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        // aurora's own screens (not this service's overlay) count as leaving the protected app.
        val ownApp = event.packageName?.toString() == packageName && event.className?.toString()?.startsWith("app.aurora.") == true
        interceptor?.onWindow(event.packageName?.toString(), event.eventTime, received, ownApp)
        if (received - lastCommitCheck > CommitCheckMs) {
            lastCommitCheck = received
            val app = application as AuroraApplication
            scope?.launch { runCatching { app.commitScheduleWindows() } }
        }
    }

    override fun onInterrupt() = Unit

    /**
     * A protected app is in front and allowed for now. Re-measures today's use when it has a daily limit,
     * and settles an intentional session whose time is up (a cooldown starts), then decides again.
     */
    private fun measure(key: String?) {
        val app = application as AuroraApplication
        if (key != null) scope?.launch {
            runCatching { app.correctIntentUse(key) }
            runCatching { app.settleIntent(key) }
            interceptor?.recheck()
        }
        val limited = app.rules.value?.limits?.filter { it.enabled }?.map { it.key }?.toSet().orEmpty()
        if (limited.isEmpty() || key != null && key !in limited) return
        scope?.launch {
            usedToday = app.screenTime.today().filterKeys { it in limited }
            interceptor?.recheck()
        }
    }

    override fun onUnbind(intent: Intent?): Boolean {
        connectedState.value = false
        trace.lifecycle("unbound", SystemClock.uptimeMillis())
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        connectedState.value = false
        trace.lifecycle("destroyed", SystemClock.uptimeMillis())
        screenReceiver?.let { runCatching { unregisterReceiver(it) } }
        presenter?.dismiss()
        scope?.cancel()
        scope = null
        interceptor = null
        super.onDestroy()
    }

    companion object {
        /** In this process only: Settings › Shield diagnostics reads it. */
        val trace = ShieldTrace()

        private val connectedState = MutableStateFlow(false)

        /**
         * Whether Android has this service connected right now. The service runs in aurora's process, so
         * a fresh process reads false until Android binds it, which is the truth.
         */
        val connected: StateFlow<Boolean> = connectedState

        private const val CommitCheckMs = 30_000L
    }
}
