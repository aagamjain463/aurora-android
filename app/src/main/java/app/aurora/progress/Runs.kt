package app.aurora.progress

import app.aurora.data.ProtectedSpan
import app.aurora.data.UsageInterval
import app.aurora.data.unionLength
import app.aurora.protect.FocusOutcome
import app.aurora.protect.Mode
import kotlinx.serialization.Serializable

/*
 * What each configuration run achieved, from evidence only (docs/CONFIGURATIONS.md "Success"). Pure,
 * unit tested (RunsTest). Aurora never asks: what the person says could be anything, so the outcome is
 * decided from what happened. A timer that reached zero proves nothing by itself: protection counts as
 * verified only while the Shield service was actually bound. A run is not achieved when it was ended
 * early, when the Shield was switched off from outside Aurora (Accessibility turned off, or a protected
 * app used while the service was down: safe mode, a force stop), when a Friction task or Cooldown wait
 * was used to open a protected app, or when an Intent app was used past its time or again too soon.
 */

@Serializable
enum class Outcome { Succeeded, Partial, NotAchieved, Unverified }

/** The weakest outcome wins: one part not achieved makes the run not achieved; one unverified part makes it unverified. */
fun combine(parts: List<Outcome>): Outcome = when {
    parts.isEmpty() -> Outcome.Unverified
    Outcome.NotAchieved in parts -> Outcome.NotAchieved
    Outcome.Unverified in parts -> Outcome.Unverified
    Outcome.Partial in parts -> Outcome.Partial
    else -> Outcome.Succeeded
}

/**
 * A span when the Shield service was bound: from [start] to when it was last [seen] (wall clock). [closed]:
 * the service was unbound at [seen] (Accessibility turned off); otherwise it was still bound then.
 */
@Serializable
data class ServiceSpan(val start: Long, val seen: Long, val closed: Boolean = false)

/** Time within [from, to) the service was bound, overlaps counted once. */
fun verifiedWithin(spans: List<ServiceSpan>, from: Long, to: Long): Long =
    unionLength(spans.mapNotNull { s -> (maxOf(s.start, from) to minOf(s.seen, to)).takeIf { it.second > it.first } })

/** While the service is still bound at [now], its open span reaches now, however old its last heartbeat is. */
fun List<ServiceSpan>.reaching(now: Long, connected: Boolean): List<ServiceSpan> {
    val last = lastOrNull()
    return if (!connected || last == null || last.closed || last.seen >= now) this else dropLast(1) + last.copy(seen = now)
}

/** Heartbeats are at most a minute apart, so an open span was bound up to a minute past its last one. */
const val HeartbeatMs = 60_000L

/** Accessibility back within this long was an update or a restart, not someone switching the Shield off. */
const val ServiceGraceMs = 2 * 60_000L

/** Use of a protected app while the service was down, beyond this, is use: less is event-timing noise. */
const val GapUseMs = 15_000L

/** When the service was bound, as intervals: a span the service closed ends exactly at [ServiceSpan.seen]. */
fun List<ServiceSpan>.bound(): List<Pair<Long, Long>> = map { it.start to if (it.closed) it.seen else it.seen + HeartbeatMs }

/** The parts of [from, to) not covered by [covered]. */
fun gaps(covered: List<Pair<Long, Long>>, from: Long, to: Long): List<Pair<Long, Long>> {
    val out = mutableListOf<Pair<Long, Long>>()
    var at = from
    for ((s, e) in covered.sortedBy { it.first }) {
        if (s > at) out += at to minOf(s, to)
        at = maxOf(at, e)
        if (at >= to) break
    }
    if (at < to) out += at to to
    return out.filter { it.second > it.first }
}

/**
 * The Shield was switched off from outside Aurora during [from, to): Accessibility turned off and not back
 * within [ServiceGraceMs], or one of [keys] used for over [GapUseMs] while the service was down (safe mode,
 * a force stop, a permission removed). [usage] is null without usage access: then only the first counts.
 * Time the phone was off or the service restarted, with nothing used, is not a bypass.
 */
fun bypassed(spans: List<ServiceSpan>, from: Long, to: Long, keys: Set<String>, usage: List<UsageInterval>?): Boolean {
    val switchedOff = spans.any { s ->
        s.closed && s.seen >= from && s.seen < to &&
            (spans.filter { it.start >= s.seen }.minOfOrNull { it.start } ?: to).coerceAtMost(to) - s.seen > ServiceGraceMs
    }
    if (switchedOff || usage == null) return switchedOff
    val down = gaps(spans.bound(), from, to)
    val used = usage.filter { it.packageName in keys }.flatMap { u ->
        down.mapNotNull { (s, e) -> (maxOf(s, u.start) to minOf(e, u.end)).takeIf { it.second > it.first } }
    }
    return unionLength(used) > GapUseMs
}

/**
 * [this] protection, only while the service was bound: Time Reclaimed is time the Shield was really on,
 * never time someone set. Before [evidenceFrom] (no service record yet) it stands as it is.
 */
fun ProtectedSpan.whileBound(bound: List<Pair<Long, Long>>, evidenceFrom: Long): List<ProtectedSpan> {
    val before = if (start < evidenceFrom) listOf(copy(end = minOf(end, evidenceFrom))) else emptyList()
    val after = bound.mapNotNull { (s, e) -> (maxOf(s, start, evidenceFrom) to minOf(e, end)).takeIf { it.second > it.first } }
    return (before + after.map { copy(start = it.first, end = it.second) }).filter { it.end > it.start }
}

/**
 * Protection over a planned window: completed with the service verified for 90% of it, partly verified,
 * or [broken] (ended early, bypassed, or used through a Friction task or Cooldown wait). Below half
 * verified, Aurora can't say: unverified, never a guessed success.
 */
fun protectionOutcome(plannedMs: Long, verifiedMs: Long, broken: Boolean): Outcome = when {
    broken -> Outcome.NotAchieved
    plannedMs <= 0 -> Outcome.Unverified
    verifiedMs >= plannedMs * 0.9 -> Outcome.Succeeded
    verifiedMs >= plannedMs * 0.5 -> Outcome.Partial
    else -> Outcome.Unverified
}

/**
 * Intent: [broken] when the person continued past their chosen time, or opened the app again within
 * [app.aurora.protect.IntentQuietMs] of its time (and cooldown) running out. Needs the service, like protection.
 */
fun intentOutcome(plannedMs: Long, verifiedMs: Long, broken: Boolean): Outcome = when {
    broken -> Outcome.NotAchieved
    plannedMs <= 0 || verifiedMs < plannedMs * 0.5 -> Outcome.Unverified
    else -> Outcome.Succeeded
}

fun FocusOutcome?.outcome(): Outcome = when (this) {
    FocusOutcome.Completed -> Outcome.Succeeded
    FocusOutcome.Partial -> Outcome.Partial
    FocusOutcome.NotCompleted -> Outcome.NotAchieved
    null -> Outcome.Unverified
}

/** One finished (or turned off) configuration run, as Track and Goals read it. */
@Serializable
data class RunRecord(
    val id: String,
    val configId: String,
    val name: String,
    val custom: Boolean = true,
    val shieldName: String? = null,
    val shieldMode: Mode? = null,
    val intentName: String? = null,
    val keys: Set<String> = emptySet(),
    val start: Long,
    /** Null: ran until turned off (Always). */
    val plannedEnd: Long? = null,
    val end: Long,
    val verifiedMs: Long = 0,
    val turnedOff: Boolean = false,
    val protection: Outcome? = null,
    val intent: Outcome? = null,
    val focusTask: String? = null,
    val focusPlannedMs: Long = 0,
    /** Decided from the run's evidence when it ends (older records: what the person said). */
    val focus: FocusOutcome? = null,
    val intentOverruns: Int = 0,
    /** Times a Friction task or Cooldown wait was used to open a protected app during the run. */
    val accessGrants: Int = 0,
    /** Times an Intent app was opened again too soon after its time ran out. */
    val intentRelapses: Int = 0,
    /** The Shield was switched off from outside Aurora during the run ([bypassed]). */
    val bypassed: Boolean = false,
) {
    val hasFocus: Boolean get() = focusTask != null
    val outcome: Outcome get() = combine(listOfNotNull(protection, intent, if (hasFocus) focus.outcome() else null))

    /** Focus time unless the run broke: the planned length, cut short if the run was. */
    val focusMs: Long get() = if (focus == null || focus == FocusOutcome.NotCompleted) 0 else minOf(focusPlannedMs, end - start).coerceAtLeast(0)
    val focusMinutes: Int get() = (focusMs / 60_000).toInt()
    /** Only Focus the person called done earns XP, as a Focus session that ran to the end does. */
    val completedFocusMinutes: Int get() = if (focus == FocusOutcome.Completed) focusMinutes else 0
}

/**
 * The record for a run that ended at [end] (turned off early when [turnedOff] and before its planned end).
 * [usage] is Android's usage records over the run, null without usage access ([bypassed]).
 */
fun record(
    id: String, configId: String, name: String, custom: Boolean,
    shieldName: String?, shieldMode: Mode?, intentName: String?, keys: Set<String>,
    start: Long, plannedEnd: Long?, end: Long, spans: List<ServiceSpan>, turnedOff: Boolean,
    focusTask: String?, focusPlannedMs: Long, overruns: Int,
    grants: Int = 0, relapses: Int = 0, usage: List<UsageInterval>? = null,
): RunRecord {
    val planned = (plannedEnd ?: end) - start
    val verified = verifiedWithin(spans, start, end)
    val early = turnedOff && plannedEnd != null && end < plannedEnd
    val bypass = bypassed(spans, start, end, keys, usage)
    val protection = if (shieldName != null) protectionOutcome(planned, verified, early || bypass || grants > 0) else null
    val intent = if (intentName != null) intentOutcome(end - start, verified, early || bypass || overruns > 0 || relapses > 0) else null
    val parts = listOfNotNull(protection, intent)
    val focus = when {
        focusTask == null -> null
        early || bypass || Outcome.NotAchieved in parts -> FocusOutcome.NotCompleted
        parts.all { it == Outcome.Succeeded } -> FocusOutcome.Completed
        else -> FocusOutcome.Partial
    }
    return RunRecord(
        id, configId, name, custom, shieldName, shieldMode, intentName, keys, start, plannedEnd, end, verified, early,
        protection = protection, intent = intent,
        focusTask = focusTask, focusPlannedMs = focusPlannedMs, focus = focus, intentOverruns = overruns,
        accessGrants = grants, intentRelapses = relapses, bypassed = bypass,
    )
}

/** Track's configuration performance over [records] (and runs still going, counted as activations only). */
data class Performance(
    val activations: Int,
    val completed: Int,
    val turnedOff: Int,
    val succeeded: Int,
    val verifiedMs: Long,
    val focusMs: Long,
)

fun performance(records: List<RunRecord>, running: Int, spans: List<ServiceSpan>): Performance = Performance(
    activations = records.size + running,
    completed = records.count { !it.turnedOff },
    turnedOff = records.count { it.turnedOff },
    succeeded = records.count { it.outcome == Outcome.Succeeded },
    // Overlapping runs are protection once: the union of each run's verified spans.
    verifiedMs = unionLength(records.flatMap { r -> spans.mapNotNull { s -> (maxOf(s.start, r.start) to minOf(s.seen, r.end)).takeIf { it.second > it.first } } }),
    focusMs = records.sumOf { it.focusMs },
)
