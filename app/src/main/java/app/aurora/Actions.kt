package app.aurora

import app.aurora.protect.sessionRunning
import androidx.datastore.preferences.core.edit
import app.aurora.data.dataStore
import app.aurora.plan.EntitlementPolicy
import app.aurora.plan.Plan
import app.aurora.plan.ProtectedItem
import app.aurora.progress.DayStats
import app.aurora.progress.FocusRecord
import app.aurora.progress.ProtectionRecord
import app.aurora.protect.AddResult
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
import app.aurora.protect.settledIntents
import app.aurora.protect.granted
import app.aurora.protect.quietAfter
import app.aurora.protect.relapsed
import app.aurora.protect.ActivationProblem
import app.aurora.protect.Refusal
import app.aurora.protect.activeTargets
import app.aurora.account.serverAdjustedRemainingMs
import app.aurora.protect.ledgerEntry
import app.aurora.protect.quotaToday
import app.aurora.protect.targetsRefusal
import app.aurora.protect.usesQuota
import app.aurora.protect.waitFor
import app.aurora.protect.windowAt
import java.io.IOException
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.sync.withLock

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
 * Removes [items] from the protected apps. They leave every cancellable session too; a Strict
 * session keeps them until it ends, because removing an app must never end a commitment.
 */
suspend fun AuroraApplication.unprotect(items: Collection<ProtectedItem>) {
    protection.remove(items, plan)
    val keys = items.map { it.key }.toSet()
    runtime.update { r ->
        r.copy(sessions = r.sessions.mapNotNull { s -> if (!s.cancellable) s else s.copy(keys = s.keys - keys).takeIf { it.keys.isNotEmpty() } })
    }
}

enum class StartResult { Started, OverAllowance, NeedsConnection, OverTargets, NothingSelected }

/**
 * Starts a [mode] session over [keys] for [minutes]. It must fit the plan's targets and, for Cooldown and
 * Strict, the day's shared minutes. Its reservation is stored with the session, in one transaction.
 */
suspend fun AuroraApplication.startProtection(
    keys: Set<String>,
    mode: Mode,
    minutes: Int,
    friction: FrictionSettings = FrictionSettings(),
    cooldownWaitMinutes: Int = 15,
): StartResult = entitlements.withLock {
    if (keys.isEmpty()) return@withLock StartResult.NothingSelected
    if (minutes <= 0) return@withLock StartResult.NothingSelected
    val r = rules.value ?: throw IOException("Rules can't be read")
    val rt = runtime.value ?: throw IOException("Runtime can't be read")
    val t = now()
    val zone = ZoneId.systemDefault()
    targetsRefusal(plan, activeTargets(r, rt, t, zone, plan), keys, emptyList())?.let {
        explain(it)
        return@withLock StartResult.OverTargets
    }
    val entry = rt.ledgerEntry(t.wall, t.wall + minutes * 60_000L, t, zone)
    if (mode.usesQuota) {
        val decision = EntitlementPolicy.canStartCommittedProtection(plan, rt.ledger, entry.start, entry.end, ZoneId.of(entry.zone))
        if (!decision.allowed) {
            explain(Refusal(ActivationProblem.OverCommittedAllowance, decision))
            return@withLock StartResult.OverAllowance
        }
    }
    val session = ProtectionSession(UUID.randomUUID().toString(), keys, mode, Session.start(t, minutes), friction, cooldownWaitMinutes)
    if (mode.usesQuota) serverRefusal(session.id, mode, minutes, null)?.let { refusal ->
        explain(refusal)
        return@withLock if (refusal.problem == ActivationProblem.NeedsConnection) StartResult.NeedsConnection else StartResult.OverAllowance
    }
    runtime.update { x ->
        val base = x.pruned(t)
        base.copy(sessions = base.sessions + session, ledger = if (mode.usesQuota) base.ledger + entry else base.ledger)
    }
    history.update { it.withProtection(ProtectionRecord(session.id, t.wall, t.wall + minutes * 60_000L, keys)) }
    if (mode == Mode.AlwaysBlock) history.update { it.withAlwaysBlock(dayOf(t.wall), t.wall, minutes) }
    StartResult.Started
}

/** Committed minutes left today, or null when unlimited. */
fun AuroraApplication.committedMinutesLeft(): Int? =
    serverAdjustedRemainingMs(runtime.value?.quotaToday(plan, now(), ZoneId.systemDefault())?.remainingMs)?.let { (it / 60_000).toInt() }

/**
 * Turns off a cancellable session. A Strict session has no in-app early end: this refuses it,
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

/** Turns off today's window of a Flexible, Friction or Cooldown schedule. A committed Strict window can't be. */
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
 * Commits every Strict schedule window that is running now into a Strict session, once.
 * From then on, editing or deleting the schedule can't end that window early. Only for a Phase 3 schedule
 * in the moment before the start-up migration turns it into an activation. A window the day's allowance
 * can't cover isn't committed. Called before any schedule edit, and whenever the snapshot is built.
 */
suspend fun AuroraApplication.commitScheduleWindows() = entitlements.withLock {
    val r = rules.value ?: return@withLock
    val list = protectedList.value ?: return@withLock
    if (!billing.loaded) return@withLock
    val t = now()
    val zone = ZoneId.systemDefault()
    val active = list.active(plan).map { it.key }.toSet()
    val due = r.activeSchedules().filter { it.mode == Mode.AlwaysBlock }.mapNotNull { s -> s.windowAt(t.wall, zone)?.let { s to it } }
    if (due.isEmpty()) return@withLock
    // A window the server hasn't signed off isn't committed; the next snapshot asks again.
    val confirmed = due.filter { (schedule, window) ->
        runtime.value?.sessions.orEmpty().none { it.scheduleId == schedule.id && it.time.startWall >= window.first } &&
            serverRefusal("schedule-${schedule.id}-${window.first}", Mode.AlwaysBlock, ((window.second - t.wall) / 60_000).toInt().coerceAtLeast(1), window.first, silent = true) == null
    }
    if (confirmed.isEmpty()) return@withLock
    val started = mutableListOf<ProtectionSession>()
    runtime.update { rt ->
        var next = rt
        for ((schedule, window) in confirmed) {
            val (start, end) = window
            if (next.sessions.any { it.scheduleId == schedule.id && it.time.startWall >= start }) continue
            val entry = next.ledgerEntry(t.wall, end, t, zone)
            if (!EntitlementPolicy.canStartCommittedProtection(plan, next.ledger, entry.start, entry.end, ZoneId.of(entry.zone)).allowed) continue
            val session = ProtectionSession(
                "schedule-${schedule.id}-$start", r.covers(schedule, active), Mode.AlwaysBlock,
                Session(t.wall, t.elapsed, t.boot, end - t.wall), scheduleId = schedule.id,
            )
            started += session
            next = next.copy(sessions = next.sessions + session, ledger = next.ledger + entry)
        }
        next
    }
    for (s in started) {
        history.update { it.withAlwaysBlock(dayOf(s.time.startWall), s.time.startWall, (s.time.durationMs / 60_000).toInt()) }
    }
}

/** Changes the rules, after committing any running Strict window, so an edit never cancels one. */
suspend fun AuroraApplication.editRules(transform: (Rules) -> Rules): Refusal? {
    commitScheduleWindows()
    return changeRules(transform)
}

/** Phase 3 → reinvention: a running Lockdown becomes a Strict session. Idempotent. */
suspend fun AuroraApplication.migrateRuntime() {
    val r = rules.value ?: return
    val list = protectedList.value ?: return
    if (runtime.value?.lockdown == null) return
    runtime.update { it.migrated(r, list.active(plan).map { i -> i.key }.toSet()) }
}

// ---- At the Shield -------------------------------------------------------------------------------

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

/**
 * Starts an intentional session: [minutes] of foreground time with [key], under its current settings.
 * Refused (false) while a post-usage cooldown for [key] is running: a new decision can't skip it.
 */
suspend fun AuroraApplication.startIntent(key: String, minutes: Int, config: IntentConfig): Boolean {
    val t = now()
    val now = t.wall
    var refused = false
    runtime.update { r0 ->
        val r = r0.settledIntents(now)
        if ((r.intentCooldowns[key] ?: 0) > now) {
            refused = true
            return@update r
        }
        r.relapsed(key, t).copy(
            intentUses = r.intentUses + (key to IntentUse(minutes * 60_000L, 0, now, config.expiry, config.cooldownMinutes, config.freshEachOpen, now)),
            reflections = r.reflections - key,
        )
    }
    if (refused) return false
    record { it.copy(intentSessions = it.intentSessions + 1, intentChosenMinutes = it.intentChosenMinutes + minutes) }
    return true
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
    runtime.update { it.settledIntents(now) }
    record {
        it.copy(
            intentCooldowns = it.intentCooldowns + 1,
            intentCooldownMinutes = it.intentCooldownMinutes + use.cooldownMinutes,
            intentUsedMinutes = it.intentUsedMinutes + (use.used(now) / 60_000).toInt(),
        )
    }
}

/**
 * Corrects [key]'s intentional session from Android's usage records, when usage access is on. Window
 * events alone can mislead: an app can send a late event after the person has already gone home, which
 * would count time they didn't spend in it. The usage records say exactly when the app was in front.
 * Without usage access the window-event estimate stands, and Protect says it's an estimate.
 */
suspend fun AuroraApplication.correctIntentUse(key: String) {
    val use = runtime.value?.intentUses?.get(key) ?: return
    if (!screenTime.hasAccess()) return
    val now = now().wall
    val spans = screenTime.intervals(use.startedAt, now)?.filter { it.packageName == key } ?: return
    val exact = spans.sumOf { it.end - it.start }
    val inFront = spans.any { it.end >= now - 1_000 }
    runtime.update { r ->
        val u = r.intentUses[key]?.takeIf { it.startedAt == use.startedAt } ?: return@update r
        r.copy(intentUses = r.intentUses + (key to u.copy(usedMs = exact, runningSince = if (inFront) now else null)))
    }
}

/** Ends [key]'s intentional session and records it. [within]: it ended inside the chosen time. */
suspend fun AuroraApplication.endIntent(key: String, within: Boolean) {
    val now = now().wall
    val use = runtime.value?.intentUses?.get(key) ?: return
    runtime.update { r -> r.copy(intentUses = r.intentUses - key).let { if (use.spent(now)) it.quietAfter(key, now) else it } }
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
    val settled = mutableListOf<IntentUse>()
    runtime.update { r0 ->
        if (r0.intentUses.isEmpty()) return@update r0
        // Time used up with a cooldown becomes that cooldown first, so leaving the app can never drop it.
        val r = r0.settledIntents(now)
        settled += (r0.intentUses - r.intentUses.keys).values
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
        if (next == r.intentUses) r
        else ended.filter { (_, use) -> use.spent(now) }.fold(r.copy(intentUses = next)) { acc, (key, _) -> acc.quietAfter(key, now) }
    }
    if (settled.isNotEmpty()) record { s ->
        settled.fold(s) { acc, use ->
            acc.copy(
                intentCooldowns = acc.intentCooldowns + 1,
                intentCooldownMinutes = acc.intentCooldownMinutes + use.cooldownMinutes,
                intentUsedMinutes = acc.intentUsedMinutes + (use.used(now) / 60_000).toInt(),
            )
        }
    }
    if (ended.isNotEmpty()) record { s ->
        ended.fold(s) { acc, (_, use) ->
            acc.copy(intentUsedMinutes = acc.intentUsedMinutes + (use.used(now) / 60_000).toInt(), intentWithin = acc.intentWithin + if (use.spent(now)) 0 else 1)
        }
    }
}

// ---- Focus -----------------------------------------------------------------------------------------

/**
 * Starts Focus. Focus itself is unlimited, but the Shield targets it activates answer to the plan: refused
 * (false, and explained) when they would pass the limit.
 */
suspend fun AuroraApplication.startFocus(minutes: Int, profileId: String?, groupId: String?, intention: String): Boolean = entitlements.withLock {
    val t = now()
    val session = Session.start(t, minutes, groupId, profileId, intention)
    val r = rules.value ?: throw IOException("Rules can't be read")
    val rt = runtime.value ?: throw IOException("Runtime can't be read")
    val keys = r.cover(groupId, protectedList.value?.active(plan)?.map { it.key }?.toSet().orEmpty())
    if (sessionRunning(r, rt, plan, t, ZoneId.systemDefault())) return@withLock false
    targetsRefusal(plan, activeTargets(r, rt, t, ZoneId.systemDefault(), plan), keys, emptyList())?.let {
        explain(it)
        return@withLock false
    }
    runtime.update { it.copy(focus = session) }
    history.update { it.withProtection(ProtectionRecord("focus-${t.wall}", t.wall, t.wall + minutes * 60_000L, keys)) }
    true
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

/**
 * Settings › Delete all data: every preference, protected item, rule, session and history entry on this
 * phone. Running Strict sessions are kept: deleting data is not a way to end a commitment.
 * Purchases live with Google Play and come back on the next check.
 */
suspend fun AuroraApplication.deleteAllData() {
    val t = now()
    val owner = vault.owner()
    val committed = runtime.value?.sessions.orEmpty().filter { !it.cancellable && !it.time.isOver(t) }
    val committedRuns = runtime.value?.runs.orEmpty().filter { !it.cancellable(t) && !it.isOver(t) }
    // Deleting data isn't a way to get today's committed minutes back, either.
    val ledger = runtime.value?.ledger.orEmpty()
    dataStore.edit { it.clear() }
    // Whose this phone's (now empty) data is doesn't change, or the next start would ask again.
    if (owner != null) vault.claim(owner)
    rules.reset()
    runtime.reset()
    history.reset()
    billing.forget()
    if (committed.isNotEmpty() || committedRuns.isNotEmpty() || ledger.isNotEmpty()) {
        try {
            runtime.update { Runtime(sessions = committed, runs = committedRuns, ledger = ledger) }
        } catch (_: IOException) {
            // Storage failed; nothing more can be kept.
        }
    }
}
