package app.aurora.progress

import app.aurora.data.unionLength
import app.aurora.protect.FocusOutcome
import app.aurora.protect.Mode
import kotlinx.serialization.Serializable

/*
 * What each configuration run achieved, from evidence only (docs/CONFIGURATIONS.md "Success"). Pure,
 * unit tested (RunsTest). A timer that reached zero proves nothing by itself: protection counts as
 * verified only while the Shield service was actually bound, and Focus counts only when the person says
 * how the task went.
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

/** A span when the Shield service was bound: from [start] to when it was last [seen] (wall clock). */
@Serializable
data class ServiceSpan(val start: Long, val seen: Long, val closed: Boolean = false)

/** Time within [from, to) the service was bound, overlaps counted once. */
fun verifiedWithin(spans: List<ServiceSpan>, from: Long, to: Long): Long =
    unionLength(spans.mapNotNull { s -> (maxOf(s.start, from) to minOf(s.seen, to)).takeIf { it.second > it.first } })

/**
 * Protection over a planned window: completed with the service verified for 90% of it, partly verified,
 * or turned off early. Below half verified, Aurora can't say: unverified, never a guessed success.
 */
fun protectionOutcome(plannedMs: Long, verifiedMs: Long, turnedOffEarly: Boolean): Outcome = when {
    turnedOffEarly -> Outcome.NotAchieved
    plannedMs <= 0 -> Outcome.Unverified
    verifiedMs >= plannedMs * 0.9 -> Outcome.Succeeded
    verifiedMs >= plannedMs * 0.5 -> Outcome.Partial
    else -> Outcome.Unverified
}

/** Intent: stayed within the chosen time unless they continued past a reminder. Needs the service, like protection. */
fun intentOutcome(plannedMs: Long, verifiedMs: Long, overruns: Int): Outcome = when {
    plannedMs <= 0 || verifiedMs < plannedMs * 0.5 -> Outcome.Unverified
    overruns > 0 -> Outcome.Partial
    else -> Outcome.Succeeded
}

fun FocusOutcome?.outcome(): Outcome = when (this) {
    FocusOutcome.Completed -> Outcome.Succeeded
    FocusOutcome.Partial -> Outcome.Partial
    FocusOutcome.NotCompleted -> Outcome.NotAchieved
    null -> Outcome.Unverified
}

/** One finished (or turned off) configuration run, as Track and Habits read it. */
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
    val focus: FocusOutcome? = null,
    val intentOverruns: Int = 0,
) {
    val hasFocus: Boolean get() = focusTask != null
    val awaitingReport: Boolean get() = hasFocus && focus == null
    val outcome: Outcome get() = combine(listOfNotNull(protection, intent, if (hasFocus) focus.outcome() else null))

    /** Focus time counted once the person reported on it: the planned length, cut short if the run was. */
    val focusMs: Long get() = if (focus == null || focus == FocusOutcome.NotCompleted) 0 else minOf(focusPlannedMs, end - start).coerceAtLeast(0)
}

/** The record for a run that ended at [end] (turned off early when [turnedOff] and before its planned end). */
fun record(
    id: String, configId: String, name: String, custom: Boolean,
    shieldName: String?, shieldMode: Mode?, intentName: String?, keys: Set<String>,
    start: Long, plannedEnd: Long?, end: Long, spans: List<ServiceSpan>, turnedOff: Boolean,
    focusTask: String?, focusPlannedMs: Long, focus: FocusOutcome?, overruns: Int,
): RunRecord {
    val planned = (plannedEnd ?: end) - start
    val verified = verifiedWithin(spans, start, end)
    val early = turnedOff && plannedEnd != null && end < plannedEnd
    return RunRecord(
        id, configId, name, custom, shieldName, shieldMode, intentName, keys, start, plannedEnd, end, verified, early,
        protection = if (shieldName != null) protectionOutcome(planned, verified, early) else null,
        intent = if (intentName != null) (if (early) Outcome.NotAchieved else intentOutcome(end - start, verified, overruns)) else null,
        focusTask = focusTask, focusPlannedMs = focusPlannedMs, focus = focus, intentOverruns = overruns,
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
