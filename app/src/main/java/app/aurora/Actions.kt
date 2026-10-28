package app.aurora

import androidx.datastore.preferences.core.edit
import app.aurora.data.dataStore
import app.aurora.plan.Allowance
import app.aurora.plan.ProtectedItem
import app.aurora.plan.lockdownMinutesLeft
import app.aurora.plan.reserveLockdown
import app.aurora.progress.DayStats
import app.aurora.progress.FocusRecord
import app.aurora.protect.AddResult
import app.aurora.protect.Session
import app.aurora.protect.key
import app.aurora.protect.toEntry
import java.io.IOException
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/*
 * The things a person does that change protection or progress. Each is one or two transactions and
 * records only what actually happened. Callers handle IOException (storage unavailable); the screens
 * keep reading the stored truth either way.
 */

private fun today(): LocalDate = LocalDate.now()

private fun dayOf(wall: Long): LocalDate = Instant.ofEpochMilli(wall).atZone(ZoneId.systemDefault()).toLocalDate()

suspend fun AuroraApplication.record(change: (DayStats) -> DayStats) {
    history.update { it.record(today(), change) }
}

/** Protects [item] with the person's default mode, if the plan has a slot. */
suspend fun AuroraApplication.protect(item: ProtectedItem): AddResult {
    val result = protection.add(item, plan)
    if (result == AddResult.Added) rules.update { r -> if (item.key in r.apps) r else r.copy(apps = r.apps + (item.key to r.defaultRule)) }
    return result
}

/** The person chose time with [key] at a Shield that offered it. */
suspend fun AuroraApplication.grant(key: String, minutes: Int) {
    val rule = rules.value?.rule(key) ?: throw IOException("Rules not loaded")
    runtime.update { it.granted(key, minutes, rule, now().wall) }
    record { it.copy(continued = it.continued + 1, grantedMinutes = it.grantedMinutes + minutes) }
}

/** The pause for [key]: continues one already running, or starts one. Returns its start (wall clock). */
suspend fun AuroraApplication.pauseStart(key: String): Long {
    var start = 0L
    runtime.update { r -> r.waitFor(key, now().wall).let { (next, s) -> start = s; next } }
    return start
}

suspend fun AuroraApplication.startFocus(minutes: Int, profileId: String?, groupId: String?, intention: String) {
    runtime.update { it.copy(focus = Session.start(now(), minutes, groupId, profileId, intention)) }
}

/**
 * Counts a Focus session that is over (or ended now, early) in the history, once. A completed session
 * stays in runtime until the person closes its summary, so the summary survives a restart.
 */
suspend fun AuroraApplication.settleFocus(endNow: Boolean) {
    val t = now()
    val session = runtime.value?.focus ?: return
    val over = session.isOver(t)
    if (!over && !endNow) return
    val profile = rules.value?.profiles?.find { it.id == session.profileId }?.name.orEmpty()
    history.update {
        it.withFocus(dayOf(session.startWall), FocusRecord(session.startWall, session.durationMs, session.elapsed(t).coerceAtMost(session.durationMs), over, profile, session.intention))
    }
    if (endNow) runtime.update { r -> if (r.focus?.startWall == session.startWall) r.copy(focus = null) else r }
}

/** Closes the completed session's summary. */
suspend fun AuroraApplication.dismissFocus() {
    settleFocus(endNow = false)
    runtime.update { r -> if (r.focus?.isOver(now()) == true) r.copy(focus = null) else r }
}

enum class LockdownResult { Started, OverBudget, AlreadyRunning }

/**
 * Starts a Lockdown of [minutes] over [groupId] (null: every protected item). On Free it must fit the
 * day's remaining Lockdown minutes; the reservation is stored with the session, in one transaction.
 */
suspend fun AuroraApplication.startLockdown(minutes: Int, groupId: String?): LockdownResult {
    var result = LockdownResult.Started
    val t = now()
    runtime.update { r ->
        if (r.lockdown != null && !r.lockdown.isOver(t)) {
            result = LockdownResult.AlreadyRunning
            return@update r
        }
        val reservation = reserveLockdown(plan, r.ledger.map { it.toReservation() }, Instant.ofEpochMilli(t.wall), minutes, ZoneId.systemDefault())
        if (reservation == null) {
            result = LockdownResult.OverBudget
            r
        } else {
            r.copy(lockdown = Session.start(t, minutes, groupId), ledger = r.ledger + reservation.toEntry())
        }
    }
    return result
}

/** Lockdown minutes left today, or null when unlimited. */
fun AuroraApplication.lockdownLeft(): Int? =
    lockdownMinutesLeft(plan, runtime.value?.ledger.orEmpty().map { it.toReservation() }, LocalDate.now())

/** Counts a finished Lockdown once and clears it. */
suspend fun AuroraApplication.settleLockdown() {
    val t = now()
    val session = runtime.value?.lockdown?.takeIf { it.isOver(t) } ?: return
    history.update { it.withLockdown(dayOf(session.startWall), session.startWall, (session.durationMs / 60_000).toInt()) }
    runtime.update { r -> if (r.lockdown?.startWall == session.startWall) r.copy(lockdown = null) else r }
}

/** Whether one more of [allowance] can be enabled when [current] are enabled now. */
fun AuroraApplication.canEnable(allowance: Allowance, current: Int) = app.aurora.plan.canAdd(plan, allowance, current)

/**
 * Settings › Delete all data: every preference, protected item, rule, session and history entry on this
 * phone. Purchases live with Google Play and come back on the next check.
 */
suspend fun AuroraApplication.deleteAllData() {
    dataStore.edit { it.clear() }
    rules.reset()
    runtime.reset()
    history.reset()
}
