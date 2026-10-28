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

    override fun onServiceConnected() {
        val app = application as AuroraApplication
        val main = Handler(Looper.getMainLooper())
        val presenter = ActivityShieldPresenter(this) { this.interceptor?.shieldHidden() }
        val exempt = exemptPackages(this)
        val interceptor = Interceptor(
            presenter, trace, packageName, overlayPackages(this),
            wallClock = System::currentTimeMillis, uptime = SystemClock::uptimeMillis,
            schedule = { delay, block -> main.postDelayed(block, delay) },
        )
        this.interceptor = interceptor
        scope?.cancel()
        scope = MainScope().apply {
            launch { app.protection.list.collect { interceptor.state = it.toPolicyState(app.plan, exempt) } }
            launch { app.preferences.preferences.collect { presenter.preferences = it } }
        }
        connectedState.value = true
        trace.lifecycle("connected", SystemClock.uptimeMillis())
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val received = SystemClock.uptimeMillis()
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        interceptor?.onWindow(event.packageName?.toString(), event.eventTime, received)
    }

    override fun onInterrupt() = Unit

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
