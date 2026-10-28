package app.aurora.protect

/*
 * Between the accessibility service and the Shield surface: event in, decision, present or dismiss.
 * No Android types, so the event handling (repeats, rapid switching, stale Shields) is unit tested.
 * Runs on the service's main thread. The snapshot can be swapped from any thread.
 */

/** Everything a surface needs to show one Shield. Times are SystemClock.uptimeMillis(). */
data class ShieldRequest(
    val packageName: String,
    val reason: ShieldReason,
    /** When the protected app's window came to the front (AccessibilityEvent.eventTime). */
    val eventUptime: Long,
    /** When the event reached the service. */
    val receivedUptime: Long,
    /** When the decision was made. */
    val decidedUptime: Long,
)

/**
 * How a Shield is shown. Phase 3 ships one implementation, a full-screen activity in its own task
 * (ActivityShieldPresenter). Another mechanism, if G1 on real phones calls for one, replaces only this.
 */
interface ShieldPresenter {
    /** Shows, or re-asserts, the Shield for [request]. False if the platform refused. */
    fun present(request: ShieldRequest): Boolean

    /** The person is somewhere else now: drop any Shield that is showing or about to show. */
    fun dismiss()
}

class Interceptor(
    private val presenter: ShieldPresenter,
    private val trace: ShieldTrace,
    private val ownPackage: String,
    /** Windows that can sit over a Shield without replacing it: the keyboard, the notification shade. */
    private val overlays: Set<String>,
    private val wallClock: () -> Long,
    private val uptime: () -> Long,
) {
    @Volatile var state: PolicyState? = null

    private var shownFor: String? = null
    private var shownAt = 0L
    /** True once anything else (including the Shield's own window) came to the front after the last present. */
    private var interrupted = false

    fun onWindow(packageName: String?, eventUptime: Long, receivedUptime: Long) {
        val decision = decide(packageName, wallClock(), state)
        trace.decided(decision)
        when {
            decision is Decision.Shield -> shield(decision, eventUptime, receivedUptime)
            packageName == null -> Unit
            packageName == ownPackage || packageName in overlays -> interrupted = true
            // A real app or the launcher is in front. A Shield started for an earlier app must not
            // appear over it, or stay behind it.
            shownFor != null -> {
                presenter.dismiss()
                shownFor = null
            }
        }
    }

    private fun shield(decision: Decision.Shield, eventUptime: Long, receivedUptime: Long) {
        val pkg = decision.packageName
        val now = uptime()
        // One opening sends 2–3 window events before the Shield shows. Those are one opening. An event
        // after the Shield's own window is the app coming back over it, and is re-asserted at once.
        if (pkg == shownFor && !interrupted && now - shownAt < RepeatWindowMs) {
            trace.repeat()
            return
        }
        val ok = presenter.present(ShieldRequest(pkg, decision.reason, eventUptime, receivedUptime, decidedUptime = now))
        trace.presented(ok)
        shownFor = pkg
        shownAt = now
        interrupted = false
    }

    companion object {
        // ponytail: fixed window. Tune per device from ShieldTrace repeats if real phones space their events wider.
        const val RepeatWindowMs = 400L
    }
}
