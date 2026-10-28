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
    /**
     * False for a re-assertion of the Shield already requested for this opening. It is still shown, but
     * not timed, because its first frame may come much later or not at all.
     */
    val fresh: Boolean = true,
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
    /** Runs [block] on this thread after [delayMs]. */
    private val schedule: (delayMs: Long, block: () -> Unit) -> Unit,
) {
    @Volatile var state: PolicyState? = null

    private var shownFor: String? = null
    private var shownAt = 0L
    /** True once anything else (including the Shield's own window) came to the front after the last present. */
    private var interrupted = false
    /** The last app (not aurora, not an overlay) whose window came to the front. */
    private var lastApp: String? = null
    /** Counts every window event except overlays, so a delayed check can tell whether anything happened. */
    private var windows = 0L

    fun onWindow(packageName: String?, eventUptime: Long, receivedUptime: Long) {
        val decision = decide(packageName, wallClock(), state)
        trace.decided(decision)
        if (packageName != null && packageName !in overlays) {
            windows++
            if (packageName != ownPackage) lastApp = packageName
        }
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
        // Every protected event re-asserts the Shield, because any of them can mean the app is in front
        // again. Dropping repeats left Clock in front on the emulator when opens came 0.3 s apart. The
        // presenter makes a repeat cheap (singleTask: onNewIntent). One opening sends 2–3 events; those
        // count as repeats and aren't timed.
        val repeat = pkg == shownFor && !interrupted && now - shownAt < RepeatWindowMs
        if (repeat) trace.repeat()
        val ok = presenter.present(ShieldRequest(pkg, decision.reason, eventUptime, receivedUptime, decidedUptime = now, fresh = !repeat))
        trace.presented(ok)
        shownFor = pkg
        shownAt = now
        interrupted = false
        confirm(pkg, attempt = 0)
    }

    /** The presenter reports that the Shield left the screen without the person closing it. */
    fun shieldHidden() {
        shownFor?.let { confirm(it, attempt = 0) }
    }

    /**
     * An app brought back while the Shield is starting, or over it, can send no window event: its window
     * never stopped being the focused one (seen on the emulator with opens 0.1–0.3 s apart). So after
     * each request, and whenever the Shield is covered, aurora checks again after [ConfirmMs]. If no
     * window came to the front since, not even the Shield's own, and the protected app was the last in
     * front, the Shield is asked for again, at most [ConfirmRetries] times.
     */
    private fun confirm(pkg: String, attempt: Int) {
        val mark = windows
        schedule(ConfirmMs) {
            if (attempt >= ConfirmRetries || windows != mark || shownFor != pkg || lastApp != pkg) return@schedule
            val decision = decide(pkg, wallClock(), state) as? Decision.Shield ?: return@schedule
            val now = uptime()
            trace.recovered()
            trace.presented(presenter.present(ShieldRequest(pkg, decision.reason, now, now, now, fresh = false)))
            confirm(pkg, attempt + 1)
        }
    }

    companion object {
        // Only decides what counts as a repeat for diagnostics and timing; nothing is dropped.
        const val RepeatWindowMs = 400L

        // ponytail: fixed wait. A Shield slower than this (the emulator's median is ~1.2 s) just gets one
        // cheap extra request. Tune on phones from the "re-shown" count in diagnostics.
        const val ConfirmMs = 1_000L
        const val ConfirmRetries = 3
    }
}
