package app.aurora.protect

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import app.aurora.AuroraApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * The production foreground-app adapter. It receives window-state changes only
 * (res/xml/shield_service.xml: no window content, no key events, no gestures), and passes the package
 * name to [Interceptor]. Only the person turns it on, in Android's Accessibility settings.
 *
 * It holds no work between events: no polling, no wake locks, no timers. The decision snapshot is kept
 * current by collecting the repositories, so no event reads storage.
 */
class ShieldService : AccessibilityService() {
    private var scope: CoroutineScope? = null
    private var interceptor: Interceptor? = null

    /** Today's use of apps with a daily limit, re-measured while one is open. Empty without usage access. */
    @Volatile private var usedToday: Map<String, Long> = emptyMap()

    override fun onServiceConnected() {
        val app = application as AuroraApplication
        val main = Handler(Looper.getMainLooper())
        val presenter = ActivityShieldPresenter(this) { this.interceptor?.shieldHidden() }
        val exempt = exemptPackages(this)
        val interceptor = Interceptor(
            presenter, trace, packageName, overlayPackages(this),
            wallClock = System::currentTimeMillis, uptime = SystemClock::uptimeMillis,
            schedule = { delay, block -> main.postDelayed(block, delay) },
            onAllowed = ::measure,
        )
        // Built from memory for every event: grants, schedules and Focus are never stale.
        interceptor.snapshot = { app.policySnapshot(exempt, usedToday) }
        this.interceptor = interceptor
        scope?.cancel()
        scope = MainScope().apply {
            launch { app.preferences.preferences.collect { presenter.preferences = it } }
            // A rule that starts while an app is open (Lockdown or Focus from a notification, a new limit)
            // applies to it at once.
            launch { combine(app.rules.state, app.runtime.state, app.protectedList) { _, _, _ -> }.collect { interceptor.recheck() } }
        }
        measure(null)
        connectedState.value = true
        trace.lifecycle("connected", SystemClock.uptimeMillis())
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val received = SystemClock.uptimeMillis()
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        interceptor?.onWindow(event.packageName?.toString(), event.eventTime, received)
    }

    override fun onInterrupt() = Unit

    /** Re-measures today's use of limited apps when [key] is one of them (or always, for null), then decides again. */
    private fun measure(key: String?) {
        val app = application as AuroraApplication
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
    }
}
