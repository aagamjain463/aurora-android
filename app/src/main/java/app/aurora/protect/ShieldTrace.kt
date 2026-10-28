package app.aurora.protect

/**
 * Lightweight, in-memory performance and lifecycle record for the Shield, shown in Settings ›
 * Shield diagnostics. It holds counts and the last [Keep] timings only: no package names, nothing
 * written to disk, nothing sent anywhere, and it's gone with the process.
 *
 * Times are CLOCK_MONOTONIC milliseconds (SystemClock.uptimeMillis), so they never jump with the wall clock.
 */
class ShieldTrace {
    data class Timing(val decideMs: Long, val presentMs: Long, val exposureMs: Long)

    data class Snapshot(
        val events: Int,
        val shields: Int,
        val repeats: Int,
        val failures: Int,
        val unavailable: Int,
        val timings: List<Timing>,
        val lifecycle: List<String>,
    ) {
        /** Median and worst exposure (event to the Shield's first frame), or null before any Shield. */
        val exposure: Pair<Long, Long>? get() = timings.map { it.exposureMs }.sorted().takeIf { it.isNotEmpty() }
            ?.let { it[(it.size - 1) / 2] to it.last() }
    }

    private var events = 0
    private var shields = 0
    private var repeats = 0
    private var failures = 0
    private var unavailable = 0
    private val timings = ArrayDeque<Timing>()
    private val lifecycle = ArrayDeque<String>()

    @Synchronized fun decided(decision: Decision) {
        events++
        if (decision == Decision.Unavailable) unavailable++
    }

    @Synchronized fun repeat() {
        repeats++
    }

    @Synchronized fun presented(ok: Boolean) {
        if (ok) shields++ else failures++
    }

    /** The Shield's first frame for a request, [frameEndUptime] being when it reached the compositor. */
    @Synchronized fun shown(request: ShieldRequest, frameEndUptime: Long) {
        timings.addLast(
            Timing(
                decideMs = request.decidedUptime - request.receivedUptime,
                presentMs = frameEndUptime - request.decidedUptime,
                exposureMs = frameEndUptime - request.eventUptime,
            ),
        )
        if (timings.size > Keep) timings.removeFirst()
    }

    /** A service lifecycle change, for example "connected at 12.4 s". */
    @Synchronized fun lifecycle(event: String, uptime: Long) {
        lifecycle.addLast("$event at ${uptime / 100 / 10.0} s")
        if (lifecycle.size > Keep) lifecycle.removeFirst()
    }

    @Synchronized fun snapshot() = Snapshot(events, shields, repeats, failures, unavailable, timings.toList(), lifecycle.toList())

    companion object {
        const val Keep = 20
    }
}
