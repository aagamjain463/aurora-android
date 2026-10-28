package app.aurora.plan

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/*
 * Lockdown's daily budget on aurora Free (docs/BUSINESS_MODEL.md 3.4). A Lockdown reserves its planned
 * minutes when it starts. The ledger of reservations is stored by the Lockdown engine (Roadmap Phase 6);
 * these functions only read it, so the budget after a restart is whatever the stored ledger says.
 */

/**
 * One Lockdown's claim on the budget. [day] is the local date it started, in the device's time zone at
 * that moment, and all of its minutes count toward that day, even past midnight. [end] is the planned
 * end until an early end outside aurora's control moves it earlier (see [endedEarlyAt]).
 */
data class LockdownReservation(val day: LocalDate, val start: Instant, val end: Instant)

/**
 * Minutes charged to [day]. Reservations are charged in start order, and time an earlier Lockdown
 * already covers is never charged again, so overlapping Lockdowns can't double-count. Each charge
 * rounds up to whole minutes.
 */
fun lockdownMinutesUsed(reservations: List<LockdownReservation>, day: LocalDate): Int {
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

/** Lockdown minutes left on [day], or null when unlimited. Never negative, even after a downgrade. */
fun lockdownMinutesLeft(plan: Plan, reservations: List<LockdownReservation>, day: LocalDate): Int? =
    limit(plan, Allowance.LockdownMinutesPerDay)?.let { (it - lockdownMinutesUsed(reservations, day)).coerceAtLeast(0) }

/**
 * Reserves a Lockdown of [minutes] from [start], or returns null when [minutes] isn't positive or the
 * day's budget can't cover it. The budget's day is [start]'s local date in [zone], the device's zone
 * now. Time already covered by a running Lockdown costs nothing. A schedule that wants more than is
 * left asks [lockdownMinutesLeft] and reserves that instead.
 */
fun reserveLockdown(
    plan: Plan,
    reservations: List<LockdownReservation>,
    start: Instant,
    minutes: Int,
    zone: ZoneId,
): LockdownReservation? {
    if (minutes <= 0) return null
    val day = start.atZone(zone).toLocalDate()
    val reservation = LockdownReservation(day, start, start.plus(minutes.toLong(), ChronoUnit.MINUTES))
    val max = limit(plan, Allowance.LockdownMinutesPerDay) ?: return reservation
    return reservation.takeIf { lockdownMinutesUsed(reservations + it, day) <= max }
}

/**
 * The reservation after an early end at [at] for a reason outside aurora's control, such as the
 * accessibility service being turned off. The unused minutes return to the budget of its day.
 */
fun LockdownReservation.endedEarlyAt(at: Instant): LockdownReservation = copy(end = at.coerceIn(start, end))
