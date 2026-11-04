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
    /** When the restriction ends (wall clock), if known. */
    val until: Long? = null,
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
    /** The full decision, for the Shield's content (the restriction, a Cooldown wait, Intent's time-up). */
    val decision: Decision.Shield? = null,
)

/** Why the person closed the Shield. Each ends differently at [Interceptor.closedByPerson]. */
enum class ShieldClose {
    /** They left for the home screen (or went back inside a part of an app). */
    Home,

    /** They chose to use the app now: it is allowed, and watched so the Shield returns when that ends. */
    Granted,

    /** They went into aurora to end a session: no Shield may come back over aurora. */
    Aurora,
}

/**
 * How a Shield is shown: an accessibility overlay over the protected app (OverlayShieldPresenter).
 * Phase 3 used a full-screen activity, which Xiaomi HyperOS refused to start from the background.
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
    /** A protected app is in front and allowed for now; the service re-measures its daily use. */
    private val onAllowed: (String) -> Unit = {},
    /**
     * Which app the person is actually using: a package, or null while a Shield or aurora covers it.
     * Intentional App Opening counts foreground time from this (UsageMeter).
     */
    private val onForeground: (String?) -> Unit = {},
    /**
     * A genuine new attempt to open a shielded app: the person had left it and opened it again. Repeat
     * events of one opening, the Shield's own window, rechecks, retries and reconnects never count. Both
     * cooldowns restart from it (Cooldowns.kt).
     */
    private val onAttempt: (Decision.Shield) -> Unit = {},
    /**
     * The screen is off or locked. Windows then (always-on display, the lock screen) are not the person
     * leaving the app: no Shield is dropped, no cooldown restarts and no intentional session ends. Waking
     * back into the same app continues where it was ([awake]).
     */
    private val asleep: () -> Boolean = { false },
) {
    @Volatile var state: PolicyState? = null

    /** The decision snapshot, built fresh for every event. The service builds it from in-memory state. */
    var snapshot: () -> PolicyState? = { state }

    /** Invalidates a pending [watch] when anything newer happens. */
    private var watchToken = 0L

    private var shownFor: String? = null
    private var shownAt = 0L
    /** True once anything else (including the Shield's own window) came to the front after the last present. */
    private var interrupted = false
    /** The last app (not aurora, not an overlay) whose window came to the front. */
    private var lastApp: String? = null
    /** Whether [lastApp]'s window was a locked Settings page. */
    private var lastLocked = false
    /** Counts every window event except overlays, so a delayed check can tell whether anything happened. */
    private var windows = 0L

    /**
     * [ownApp]: one of aurora's own screens came to the front, so the person left the protected app.
     * [lockedScreen]: the window is a Settings page a strict commitment locks ([LockedScreens]).
     */
    fun onWindow(packageName: String?, eventUptime: Long, receivedUptime: Long, ownApp: Boolean = false, lockedScreen: Boolean = false) {
        // A late event from the app the person just left with Return home (one opening sends 2–3, spread
        // over half a second) is not a new opening. Without this the Shield reappeared over the home screen.
        if (packageName != null && packageName == leftApp && uptime() - leftAt < LeftGraceMs &&
            (leftObservedAt == null || eventUptime <= leftObservedAt!!)) return
        if (leftApp != null && packageName != null && packageName != leftApp && packageName !in overlays &&
            (packageName != ownPackage || ownApp)) leftObservedAt = eventUptime
        val current = snapshot()
        val decision = decide(packageName, wallClock(), current, lockedScreen)
        trace.decided(decision, receivedUptime)
        val sleeping = asleep()
        // Asleep, only a Shield still matters (an app shown over the lock screen), and it is never a new attempt.
        if (sleeping && decision !is Decision.Shield) return
        if (packageName != null && packageName !in overlays) {
            windows++
            if (packageName != ownPackage) {
                lastApp = packageName
                lastLocked = lockedScreen
            }
        }
        when {
            decision is Decision.Shield -> {
                // A Shield already up for this app means the same opening (repeats, the Shield's own window,
                // the shade). A burst within AttemptDedupMs is one opening too, even if a launcher event
                // came between. Uncertain cases only ever lengthen a wait, never shorten it.
                val pkg = decision.packageName
                if (!sleeping && pkg != shownFor && (pkg != lastAttempt || eventUptime - lastAttemptAt >= AttemptDedupMs)) {
                    lastAttempt = pkg
                    lastAttemptAt = eventUptime
                    onAttempt(decision)
                }
                shield(decision, eventUptime, receivedUptime)
            }
            packageName == null -> Unit
            packageName in overlays -> interrupted = true
            packageName == ownPackage -> {
                interrupted = true
                onForeground(null)
                if (ownApp && shownFor != null) {
                    presenter.dismiss()
                    leftApp = shownFor
                    leftAt = uptime()
                    leftObservedAt = eventUptime
                    shownFor = null
                }
                if (ownApp) lastApp = null
            }
            // A real app or the launcher is in front. A Shield started for an earlier app must not
            // appear over it, or stay behind it.
            shownFor != null -> {
                presenter.dismiss()
                shownFor = null
            }
        }
        if (packageName != null && packageName !in overlays && packageName != ownPackage) {
            watchToken++
            if (decision !is Decision.Shield) onForeground(packageName)
        }
        if (decision is Decision.TemporarilyAllowed || decision == Decision.Allow && current != null && packageName in current.protectedPackages) {
            watch(packageName!!)
            onAllowed(packageName)
        }
    }

    /**
     * A protected app is open with no Shield: a grant, a RulesOnly item, or budget left. Its decision can
     * change while it stays in front (the grant ends, a schedule starts, the budget runs out), and no
     * window event would say so. So aurora checks again at the next change, and at least every
     * [MaxWatchMs] because the uptime clock pauses in deep sleep and the wall clock can move.
     */
    private fun watch(pkg: String) {
        val current = snapshot() ?: return
        val wall = wallClock()
        val next = nextChange(pkg, wall, current) ?: return
        val token = ++watchToken
        schedule((next - wall).coerceIn(MinWatchMs, MaxWatchMs)) {
            if (token == watchToken && lastApp == pkg) {
                onAllowed(pkg)
                recheck()
            }
        }
    }

    /**
     * The person unlocked the phone. The app they were in is in front again, often without any window
     * event: its intentional time counts again, and its decision is checked.
     */
    fun awake() {
        if (asleep()) return
        val pkg = lastApp ?: return
        if (pkg != shownFor) onForeground(pkg)
        recheck()
    }

    /** Decides again for the app in front, e.g. after its daily use was re-measured. */
    fun recheck() {
        val pkg = lastApp ?: return
        if (pkg == shownFor) return
        val current = snapshot() ?: return
        when (val decision = decide(pkg, wallClock(), current, lastLocked)) {
            is Decision.Shield -> uptime().let { shield(decision, it, it) }
            is Decision.TemporarilyAllowed, Decision.Allow -> if (pkg in current.protectedPackages) watch(pkg)
            else -> Unit
        }
    }

    private fun shield(decision: Decision.Shield, eventUptime: Long, receivedUptime: Long) {
        val pkg = decision.packageName
        val now = uptime()
        onForeground(null)
        // Every protected event re-asserts the Shield, because any of them can mean the app is in front
        // again. Dropping repeats left Clock in front on the emulator when opens came 0.3 s apart. The
        // presenter makes a repeat cheap (singleTask: onNewIntent). One opening sends 2–3 events; those
        // count as repeats and aren't timed.
        val repeat = pkg == shownFor && !interrupted && now - shownAt < RepeatWindowMs
        if (repeat) trace.repeat()
        watchToken++
        val ok = presenter.present(
            ShieldRequest(pkg, decision.reason, decision.until, eventUptime, receivedUptime, decidedUptime = now, fresh = !repeat, decision = decision),
        )
        trace.presented(ok)
        shownFor = pkg
        shownAt = now
        interrupted = false
        confirm(pkg, attempt = 0)
    }

    /**
     * The person closed the Shield. Home: they left the app, so nothing is re-checked until the next
     * window. Granted: the app is allowed now, and is watched so the Shield returns when it ends.
     * Aurora: aurora is opening to end the session, so nothing is put back over it; if its window never
     * arrives (a refused background start), the Shield returns rather than leave the app open.
     */
    fun closedByPerson(how: ShieldClose) {
        val pkg = shownFor
        shownFor = null
        watchToken++
        when (how) {
            ShieldClose.Home -> {
                leftApp = pkg
                leftAt = uptime()
                leftObservedAt = null
                lastApp = null
            }
            ShieldClose.Granted -> {
                // The app is right there under the dismissed Shield: its intentional time starts now even
                // if it sends no window event, which some apps don't when an overlay goes. Measuring also
                // re-checks after that time is stored, so the end of the allowance is scheduled.
                pkg?.let { onForeground(it); onAllowed(it) }
                recheck()
            }
            ShieldClose.Aurora -> schedule(AuroraGraceMs) {
                // Aurora's own window event clears lastApp. If it is still the protected app, aurora never
                // came to the front, and leaving the app unguarded would be worse than a brief re-shield.
                if (shownFor == null && pkg != null && lastApp == pkg) recheck()
            }
        }
    }

    private var lastAttempt: String? = null
    private var lastAttemptAt = 0L

    private var leftApp: String? = null
    private var leftAt = 0L
    /** A real window after leaving confirms that a newer protected event is a genuine reopening. */
    private var leftObservedAt: Long? = null

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
            val decision = decide(pkg, wallClock(), snapshot(), lastLocked) as? Decision.Shield ?: return@schedule
            val now = uptime()
            trace.recovered()
            trace.presented(presenter.present(ShieldRequest(pkg, decision.reason, decision.until, now, now, now, fresh = false, decision = decision)))
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

        const val MinWatchMs = 250L
        const val LeftGraceMs = 1_000L
        const val MaxWatchMs = 60_000L
        const val AttemptDedupMs = 1_500L

        // Long enough for a cold aurora start to send its window event, short enough that a refused start
        // does not leave a blocked app open.
        const val AuroraGraceMs = 2_000L
    }
}
