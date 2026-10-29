package app.aurora.plan

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/*
 * Always Block's daily allowance on aurora Free (docs/BUSINESS_MODEL.md 3.4; Phase 3 called this the
 * Lockdown budget, and the 120 minutes carry over unchanged). An Always Block session, or the committed
 * part of a scheduled Always Block window, reserves its planned minutes when it starts. The ledger is
 * stored in Runtime; these functions only read it, so the allowance after a restart is whatever the
 * stored ledger says.
 */

/**
 * One Always Block session's claim on the budget. [day] is the local date it started, in the device's time zone at
 * that moment, and all of its minutes count toward that day, even past midnight. [end] is the planned
 * end until an early end outside aurora's control moves it earlier (see [endedEarlyAt]).
 */
data class AlwaysBlockReservation(val day: LocalDate, val start: Instant, val end: Instant)

/**
 * Minutes charged to [day]. Reservations are charged in start order, and time an earlier Always Block session
 * already covers is never charged again, so overlapping Always Block sessions can't double-count. Each charge
 * rounds up to whole minutes.
 */
fun alwaysBlockMinutesUsed(reservations: List<AlwaysBlockReservation>, day: LocalDate): Int {
    var used = 0L
    // Every earlier reservation starts at or before this one, so together they cover exactly
    // [start, coveredUntil) of it.
    var coveredUntil = Instant.MIN
    for (r in reservations.sortedBy { it.start }) {
        val from = maxOf(r.start, coveredUntil)
        if (r.day == day && r.end > from) used += (ChronoUnit.MILLIS.between(from, r.end) + 59_999) / 60_000
        coveredUntil = maxOf(coveredUntil, r.end)
    }
    return used.toInt()
}

/** Always Block session minutes left on [day], or null when unlimited. Never negative, even after a downgrade. */
fun alwaysBlockMinutesLeft(plan: Plan, reservations: List<AlwaysBlockReservation>, day: LocalDate): Int? =
    limit(plan, Allowance.AlwaysBlockMinutesPerDay)?.let { (it - alwaysBlockMinutesUsed(reservations, day)).coerceAtLeast(0) }

/**
 * Reserves an Always Block session of [minutes] from [start], or returns null when [minutes] isn't positive or the
 * day's budget can't cover it. The budget's day is [start]'s local date in [zone], the device's zone
 * now. Time already covered by a running Always Block session costs nothing. A schedule that wants more than is
 * left asks [alwaysBlockMinutesLeft] and reserves that instead.
 */
fun reserveAlwaysBlock(
    plan: Plan,
    reservations: List<AlwaysBlockReservation>,
    start: Instant,
    minutes: Int,
    zone: ZoneId,
): AlwaysBlockReservation? {
    if (minutes <= 0) return null
    val day = start.atZone(zone).toLocalDate()
    val reservation = AlwaysBlockReservation(day, start, start.plus(minutes.toLong(), ChronoUnit.MINUTES))
    val max = limit(plan, Allowance.AlwaysBlockMinutesPerDay) ?: return reservation
    return reservation.takeIf { alwaysBlockMinutesUsed(reservations + it, day) <= max }
}

/**
 * The reservation after an early end at [at] for a reason outside aurora's control, such as the
 * accessibility service being turned off. The unused minutes return to the budget of its day.
 */
fun AlwaysBlockReservation.endedEarlyAt(at: Instant): AlwaysBlockReservation = copy(end = at.coerceIn(start, end))
