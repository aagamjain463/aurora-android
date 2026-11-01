package app.aurora.plan

import app.aurora.data.unionLength
import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.ZoneId

/*
 * Free's shared allowance for committed protection: Cooldown and Always Block together get 120 minutes per
 * local calendar day (docs/BUSINESS_MODEL.md 3.4). Pure Kotlin, unit tested (CommittedQuotaTest).
 *
 * The ledger holds absolute time intervals, one per committed run, written in the same transaction as the
 * run itself. What a day has used is the length of the union of those intervals inside that day, so
 * overlapping sessions count once and a session past midnight charges each day its own part. The ledger
 * is stored data, so process death and reboots change nothing.
 *
 * Clock and time zone policy. A regular app can't make device time tamper-proof, so this closes the easy
 * resets and says what is left open:
 * - Within one boot, "now" comes from the monotonic clock anchored on the newest entry ([quotaNow]), so
 *   changing the date or time changes nothing.
 * - After a restart only the wall clock is left. Setting it back up to two days can't reopen an earlier
 *   day. Further back counts as correcting a wrong clock. Setting it forward across a restart is not seen.
 * - A zone change takes effect when the local day of the newest entry ends ([quotaZone]), so changing the
 *   zone can't start a new day. Every entry records the zone it was charged in.
 */

/** One committed interval. [elapsed] and [boot] anchor the monotonic clock at its start. [day] is unused, kept for stored entries. */
@Serializable
data class LedgerEntry(
    val day: String = "",
    val start: Long,
    val end: Long,
    val zone: String? = null,
    val elapsed: Long? = null,
    val boot: Int? = null,
)

/** Today's allowance as shown to the person. [limitMs] and [remainingMs] are null when unlimited. */
data class QuotaStatus(val usedMs: Long, val limitMs: Long?, val remainingMs: Long?, val resetAt: Long)

private const val DayMs = 86_400_000L
private const val ClockToleranceMs = 2 * DayMs

/** The time the quota reads: see the policy above. */
fun quotaNow(ledger: List<LedgerEntry>, wall: Long, elapsed: Long, boot: Int): Long {
    val last = ledger.maxByOrNull { it.start } ?: return wall
    val anchor = last.elapsed
    if (anchor != null && boot >= 0 && last.boot == boot && elapsed - anchor in 0 until ClockToleranceMs) return last.start + (elapsed - anchor)
    return if (wall < last.start && last.start - wall <= ClockToleranceMs) last.start else wall
}

/** The zone whose calendar applies at [now]: the newest entry's until its local day is over, then [current]. */
fun quotaZone(ledger: List<LedgerEntry>, now: Long, current: ZoneId): ZoneId {
    val last = ledger.maxByOrNull { it.end } ?: return current
    val zone = last.zone?.let { runCatching { ZoneId.of(it) }.getOrNull() } ?: return current
    return if (now < dayWindow(last.end - 1, zone).second) zone else current
}

/** The local calendar day containing [at], as start and end. A day can be 23 or 25 hours long. */
fun dayWindow(at: Long, zone: ZoneId): Pair<Long, Long> {
    val date = Instant.ofEpochMilli(at).atZone(zone).toLocalDate()
    return date.atStartOfDay(zone).toInstant().toEpochMilli() to date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
}

private fun usedIn(ledger: List<LedgerEntry>, from: Long, to: Long): Long =
    unionLength(ledger.map { maxOf(it.start, from) to minOf(it.end, to) })

/** Used and reserved time on the day of [now], including time still ahead in entries already reserved. */
fun quotaStatus(plan: Plan, ledger: List<LedgerEntry>, now: Long, zone: ZoneId): QuotaStatus {
    val (from, to) = dayWindow(now, zone)
    val used = usedIn(ledger, from, to)
    val limit = limit(plan, Allowance.CommittedMinutesPerDay)?.let { it * 60_000L }
    return QuotaStatus(used, limit, limit?.let { (it - used).coerceAtLeast(0) }, to)
}

/**
 * Whether [start] to [end] fits on every day it touches. A session that adds no new coverage always
 * fits, even over the limit, because it costs nothing.
 */
fun committedFits(plan: Plan, ledger: List<LedgerEntry>, start: Long, end: Long, zone: ZoneId): Boolean {
    val max = limit(plan, Allowance.CommittedMinutesPerDay)?.let { it * 60_000L } ?: return true
    val after = ledger + LedgerEntry(start = start, end = end)
    var cursor = start
    while (cursor < end) {
        val (from, to) = dayWindow(cursor, zone)
        val used = usedIn(after, from, to)
        if (used > max && used > usedIn(ledger, from, to)) return false
        cursor = to
    }
    return true
}
