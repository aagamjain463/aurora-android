package app.aurora

import androidx.datastore.preferences.core.edit
import app.aurora.data.dataStore
import app.aurora.plan.Allowance
import app.aurora.plan.Plan
import app.aurora.plan.ProtectedItem
import app.aurora.plan.alwaysBlockMinutesLeft
import app.aurora.plan.reserveAlwaysBlock
import app.aurora.progress.DayStats
import app.aurora.progress.FocusRecord
import app.aurora.progress.ProtectionRecord
import app.aurora.protect.AddResult
import app.aurora.protect.CooldownWait
import app.aurora.protect.FrictionSettings
import app.aurora.protect.Grant
import app.aurora.protect.IntentConfig
import app.aurora.protect.IntentExpiry
import app.aurora.protect.IntentUse
import app.aurora.protect.Mode
import app.aurora.protect.ProtectionSession
import app.aurora.protect.Restriction
import app.aurora.protect.Rules
import app.aurora.protect.Runtime
import app.aurora.protect.Session
import app.aurora.protect.cover
import app.aurora.protect.covers
import app.aurora.protect.key
import app.aurora.protect.migrated
import app.aurora.protect.toEntry
import app.aurora.protect.windowAt
import java.io.IOException
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

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

/** Adds [item] to the protected apps, if the plan has a slot. Saving an item never starts protection. */
suspend fun AuroraApplication.protect(item: ProtectedItem): AddResult {
    val result = protection.add(item, plan)
    if (result == AddResult.Added) rules.update { r -> if (item.key in r.apps) r else r.copy(apps = r.apps + (item.key to r.defaultRule)) }
    return result
}

/**
 * Removes [items] from the protected apps. They leave every cancellable session too; an Always Block
 * session keeps them until it ends, because removing an app must never end a commitment.
 */
suspend fun AuroraApplication.unprotect(items: Collection<ProtectedItem>) {
    protection.remove(items, plan)
    val keys = items.map { it.key }.toSet()
    runtime.update { r ->
        r.copy(sessions = r.sessions.mapNotNull { s -> if (!s.cancellable) s else s.copy(keys = s.keys - keys).takeIf { it.keys.isNotEmpty() } })
    }
}

enum class StartResult { Started, OverAllowance, NothingSelected }

/**
 * Starts a [mode] session over [keys] for [minutes]. Always Block on Free must fit the day's remaining
 * Always Block minutes; its reservation is stored with the session, in one transaction.
 */
suspend fun AuroraApplication.startProtection(
    keys: Set<String>,
    mode: Mode,
    minutes: Int,
    friction: FrictionSettings = FrictionSettings(),
    cooldownWaitMinutes: Int = 15,
): StartResult {
    if (keys.isEmpty()) return StartResult.NothingSelected
    if (minutes <= 0) return StartResult.NothingSelected
    var result = StartResult.Started
    val t = now()
    val session = ProtectionSession(UUID.randomUUID().toString(), keys, mode, Session.start(t, minutes), friction, cooldownWaitMinutes)
    runtime.update { r ->
        if (mode != Mode.AlwaysBlock) return@update r.pruned(t).copy(sessions = r.pruned(t).sessions + session)
        val reservation = reserveAlwaysBlock(plan, r.ledger.map { it.toReservation() }, Instant.ofEpochMilli(t.wall), minutes, ZoneId.systemDefault())
        if (reservation == null) {
            result = StartResult.OverAllowance
            r
        } else {
            r.pruned(t).copy(sessions = r.pruned(t).sessions + session, ledger = r.ledger + reservation.toEntry())
        }
    }
    if (result == StartResult.Started) {
        history.update { it.withProtection(ProtectionRecord(session.id, t.wall, t.wall + minutes * 60_000L, keys)) }
        if (mode == Mode.AlwaysBlock) history.update { it.withAlwaysBlock(dayOf(t.wall), t.wall, minutes) }
    }
    return result
}

/** Always Block minutes left today, or null when unlimited. */
fun AuroraApplication.alwaysBlockLeft(): Int? =
    alwaysBlockMinutesLeft(plan, runtime.value?.ledger.orEmpty().map { it.toReservation() }, LocalDate.now())

/**
 * Turns off a cancellable session. An Always Block session has no in-app early end: this refuses it,
 * whatever calls it.
 */
suspend fun AuroraApplication.turnOff(sessionId: String): Boolean {
    var done = false
    val t = now()
    runtime.update { r ->
        val s = r.sessions.find { it.id == sessionId }
        if (s == null || !s.cancellable) r
        else {
            done = true
            r.copy(sessions = r.sessions - s, cooldownWaits = r.cooldownWaits.filterValues { it.restrictionId != s.id })
        }
    }
    if (done) history.update { it.endProtection(sessionId, t.wall) }
    return done
}

/** Turns off today's window of a Flexible, Friction or Cooldown schedule. A committed Always Block window can't be. */
suspend fun AuroraApplication.skipWindow(scheduleId: String) {
    val schedule = rules.value?.schedules?.find { it.id == scheduleId } ?: return
    if (schedule.mode == Mode.AlwaysBlock && plan == Plan.Plus) return
    val (start, _) = schedule.windowAt(now().wall, ZoneId.systemDefault()) ?: return
    runtime.update { r ->
        if (schedule.mode == Mode.AlwaysBlock && r.sessions.any { it.scheduleId == scheduleId && it.time.startWall >= start }) r
        else r.copy(skipped = r.skipped + (scheduleId to start))
    }
}

/**
 * Commits every Always Block schedule window that is running now into an Always Block session, once.
 * From then on, editing or deleting the schedule can't end that window early. On Free the committed part
 * is what the day's Always Block allowance still covers; the rest of the window stays a full block that
 * can be turned off. Called before any schedule edit, and whenever the snapshot is built.
 */
suspend fun AuroraApplication.commitScheduleWindows() {
    val r = rules.value ?: return
    val list = protectedList.value ?: return
    val t = now()
    val zone = ZoneId.systemDefault()
    val active = list.active(plan).map { it.key }.toSet()
    val due = r.activeSchedules(plan).filter { it.mode == Mode.AlwaysBlock }.mapNotNull { s -> s.windowAt(t.wall, zone)?.let { s to it } }
    if (due.isEmpty()) return
    val started = mutableListOf<ProtectionSession>()
    runtime.update { rt ->
        var next = rt
        for ((schedule, window) in due) {
            val (start, end) = window
            if (next.sessions.any { it.scheduleId == schedule.id && it.time.startWall >= start }) continue
            val wantMinutes = ((end - t.wall + 59_999) / 60_000).toInt()
            val minutes = minOf(wantMinutes, alwaysBlockMinutesLeft(plan, next.ledger.map { it.toReservation() }, dayOf(t.wall)) ?: wantMinutes)
            if (minutes <= 0) continue
            val reservation = reserveAlwaysBlock(plan, next.ledger.map { it.toReservation() }, Instant.ofEpochMilli(t.wall), minutes, zone) ?: continue
            val session = ProtectionSession(
                "schedule-${schedule.id}-$start", r.covers(schedule, active), Mode.AlwaysBlock,
                Session(t.wall, t.elapsed, t.boot, minOf(minutes * 60_000L, end - t.wall)), scheduleId = schedule.id,
            )
            started += session
            next = next.copy(sessions = next.sessions + session, ledger = next.ledger + reservation.toEntry())
        }
        next
    }
    for (s in started) {
        history.update { it.withAlwaysBlock(dayOf(s.time.startWall), s.time.startWall, (s.time.durationMs / 60_000).toInt()) }
    }
}

/** Changes the rules, after committing any running Always Block window, so an edit never cancels one. */
suspend fun AuroraApplication.editRules(transform: (Rules) -> Rules) {
    commitScheduleWindows()
    rules.update(transform)
}

/** Phase 3 → reinvention: a running Lockdown becomes an Always Block session. Idempotent. */
suspend fun AuroraApplication.migrateRuntime() {
    val r = rules.value ?: return
    val list = protectedList.value ?: return
    if (runtime.value?.lockdown == null) return
    runtime.update { it.migrated(r, list.active(plan).map { i -> i.key }.toSet()) }
}

// ---- At the Shield -------------------------------------------------------------------------------

/** A Friction task was completed, or a Cooldown wait finished: timed access at [level] for [minutes]. */
suspend fun AuroraApplication.grantAccess(key: String, minutes: Int, level: Mode) {
    val t = now()
    runtime.update { r -> r.copy(access = r.access + (key to Grant(t.wall + minutes * 60_000L, level)), cooldownWaits = r.cooldownWaits - key).pruned(t) }
    record { it.copy(continued = it.continued + 1, grantedMinutes = it.grantedMinutes + minutes) }
}

/**
 * The Cooldown wait for [key] under [restriction]: the one already running, or a new one starting now.
 * Reopening the app, switching apps or recreating the Shield never restarts or skips it.
 */
suspend fun AuroraApplication.cooldownWait(key: String, restriction: Restriction): Long {
    var ready = 0L
    val now = now().wall
    runtime.update { r ->
        val existing = r.cooldownWaits[key]?.takeIf { it.restrictionId == restriction.id }
        if (existing != null) {
            ready = existing.readyAt
            r
        } else {
            ready = now + restriction.cooldownWaitMs
            r.copy(cooldownWaits = r.cooldownWaits + (key to CooldownWait(ready, restriction.id)))
        }
    }
    return ready
}

/** The reflection pause for [key]: continues one already running, or starts one. Returns its start. */
suspend fun AuroraApplication.reflectionStart(key: String): Long {
    var start = 0L
    runtime.update { r -> r.reflectionFor(key, now().wall).let { (next, s) -> start = s; next } }
    return start
}

/** The person chose to go back at a Shield that offered a way through. */
suspend fun AuroraApplication.wentBack(intent: Boolean) {
    record { if (intent) it.copy(intentLetGo = it.intentLetGo + 1) else it.copy(wentBack = it.wentBack + 1) }
}

/** Starts an intentional session: [minutes] of foreground time with [key], under its current settings. */
suspend fun AuroraApplication.startIntent(key: String, minutes: Int, config: IntentConfig) {
    val now = now().wall
    runtime.update { r ->
        r.copy(
            intentUses = r.intentUses + (key to IntentUse(minutes * 60_000L, 0, now, config.expiry, config.cooldownMinutes, config.freshEachOpen, now)),
            reflections = r.reflections - key,
        )
    }
    record { it.copy(intentSessions = it.intentSessions + 1, intentChosenMinutes = it.intentChosenMinutes + minutes) }
}

/** At the reminder: the person is done. The session ends within what they chose. */
suspend fun AuroraApplication.finishIntent(key: String) {
    endIntent(key, within = true)
}

/**
 * Settles [key]'s intentional session when its time is up: with a cooldown, the app closes for the
 * cooldown the person chose when the session began. Idempotent.
 */
suspend fun AuroraApplication.settleIntent(key: String) {
    val now = now().wall
    val use = runtime.value?.intentUses?.get(key) ?: return
    if (!use.spent(now) || use.expiry != IntentExpiry.Cooldown) return
    runtime.update { r ->
        val u = r.intentUses[key] ?: return@update r
        r.copy(intentUses = r.intentUses - key, intentCooldowns = r.intentCooldowns + (key to now + u.cooldownMinutes * 60_000L))
    }
    record {
        it.copy(
            intentCooldowns = it.intentCooldowns + 1,
            intentCooldownMinutes = it.intentCooldownMinutes + use.cooldownMinutes,
            intentUsedMinutes = it.intentUsedMinutes + (use.used(now) / 60_000).toInt(),
        )
    }
}

/** Ends [key]'s intentional session and records it. [within]: it ended inside the chosen time. */
suspend fun AuroraApplication.endIntent(key: String, within: Boolean) {
    val now = now().wall
    val use = runtime.value?.intentUses?.get(key) ?: return
    runtime.update { r -> r.copy(intentUses = r.intentUses - key) }
    record { it.copy(intentUsedMinutes = it.intentUsedMinutes + (use.used(now) / 60_000).toInt(), intentWithin = it.intentWithin + if (within) 1 else 0) }
}

/**
 * Foreground accounting for intentional sessions (called by the Shield service). [pkg] is the app now in
 * front, or null when none is (a Shield, aurora, the screen off). Time counts only while an app with a
 * session is in front. Leaving an app ends its session if it asks for a fresh decision every opening,
 * or if its time already ran out with a reminder.
 */
suspend fun AuroraApplication.foregroundChanged(pkg: String?) {
    val now = now().wall
    val ended = mutableListOf<Pair<String, IntentUse>>()
    runtime.update { r ->
        if (r.intentUses.isEmpty()) return@update r
        val next = r.intentUses.mapValues { (key, use) ->
            when {
                key == pkg && use.runningSince == null -> use.copy(runningSince = now)
                key != pkg && use.runningSince != null -> use.copy(usedMs = use.used(now), runningSince = null)
                else -> use
            }
        }.filter { (key, use) ->
            val leave = key != pkg && (use.freshEachOpen || (use.spent(now) && use.expiry == IntentExpiry.Reminder))
            if (leave) ended += key to use
            !leave
        }
        if (next == r.intentUses) r else r.copy(intentUses = next)
    }
    if (ended.isNotEmpty()) record { s ->
        ended.fold(s) { acc, (_, use) ->
            acc.copy(intentUsedMinutes = acc.intentUsedMinutes + (use.used(now) / 60_000).toInt(), intentWithin = acc.intentWithin + if (use.spent(now)) 0 else 1)
        }
    }
}

// ---- Focus -----------------------------------------------------------------------------------------

suspend fun AuroraApplication.startFocus(minutes: Int, profileId: String?, groupId: String?, intention: String) {
    val t = now()
    val session = Session.start(t, minutes, groupId, profileId, intention)
    runtime.update { it.copy(focus = session) }
    val keys = rules.value?.cover(groupId, protectedList.value?.active(plan)?.map { it.key }?.toSet().orEmpty()).orEmpty()
    history.update { it.withProtection(ProtectionRecord("focus-${t.wall}", t.wall, t.wall + minutes * 60_000L, keys)) }
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
            .let { h -> if (endNow) h.endProtection("focus-${session.startWall}", t.wall) else h }
    }
    if (endNow) runtime.update { r -> if (r.focus?.startWall == session.startWall) r.copy(focus = null) else r }
}

/** Closes the completed session's summary. */
suspend fun AuroraApplication.dismissFocus() {
    settleFocus(endNow = false)
    runtime.update { r -> if (r.focus?.isOver(now()) == true) r.copy(focus = null) else r }
}

/** Whether one more of [allowance] can be enabled when [current] are enabled now. */
fun AuroraApplication.canEnable(allowance: Allowance, current: Int) = app.aurora.plan.canAdd(plan, allowance, current)

/**
 * Settings › Delete all data: every preference, protected item, rule, session and history entry on this
 * phone. Running Always Block sessions are kept: deleting data is not a way to end a commitment.
 * Purchases live with Google Play and come back on the next check.
 */
suspend fun AuroraApplication.deleteAllData() {
    val t = now()
    val committed = runtime.value?.sessions.orEmpty().filter { !it.cancellable && !it.time.isOver(t) }
    dataStore.edit { it.clear() }
    rules.reset()
    runtime.reset()
    history.reset()
    if (committed.isNotEmpty()) {
        try {
            runtime.update { Runtime(sessions = committed) }
        } catch (_: IOException) {
            // Storage failed; nothing more can be kept.
        }
    }
}
