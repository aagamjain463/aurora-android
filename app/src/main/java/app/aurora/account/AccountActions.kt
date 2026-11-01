package app.aurora.account

import app.aurora.AuroraApplication
import app.aurora.R
import app.aurora.data.AuroraJson
import app.aurora.deleteAllData
import app.aurora.plan.Plan
import app.aurora.protect.DeviceTime
import app.aurora.protect.Mode
import app.aurora.protect.Runtime
import kotlinx.coroutines.sync.withLock
import java.time.ZoneId

/*
 * Connecting a verified account to what is on this phone. Authentication and enforcement are separate on purpose:
 * nothing here ends a commitment, and a commitment never asks who is signed in.
 */

val Mode.serverName: String get() = if (this == Mode.Cooldown) "cooldown" else "always_block"

/** What a commitment made under [this] leaves running, with nothing that says what it was for. */
fun Runtime.carriedCommitments(t: DeviceTime, label: String): Runtime = Runtime(
    sessions = sessions.filter { !it.cancellable && !it.time.isOver(t) },
    runs = runs.filter { !it.cancellable(t) && !it.isOver(t) }.map { run ->
        run.copy(
            name = label, configId = "", activationId = null, custom = false, intent = null, focus = null, carried = true,
            shield = run.shield?.copy(name = label),
        )
    },
)

/** The incoming account's runtime, plus what must keep running from the last one. Its ledger and the rest stay behind. */
fun mergeCarried(active: Runtime?, incoming: Runtime?, t: DeviceTime, label: String): Runtime {
    val carried = active?.carriedCommitments(t, label) ?: Runtime()
    val base = incoming ?: Runtime()
    return base.copy(
        sessions = base.sessions + carried.sessions.filter { c -> base.sessions.none { it.id == c.id } },
        runs = base.runs + carried.runs.filter { c -> base.runs.none { it.id == c.id } },
    )
}

/** Runs once for each signed-in user, before anything behind the gate can draw. */
suspend fun AuroraApplication.bindProfile(uid: String) {
    val bound = entitlements.withLock {
        val owner = vault.owner()
        when {
            owner == uid -> true
            // Data here from before accounts, or set aside then: this is the person's to decide, once.
            owner == null && vault.hasLocalData() -> if (!vault.hasDeclined(uid)) {
                account.setProfile(ProfileState.NeedsDecision)
                false
            } else {
                switchProfile(uid, fromUnclaimed = false)
                true
            }
            vault.hasUnclaimed() && !vault.hasArchive(uid) && !vault.hasDeclined(uid) -> {
                account.setProfile(ProfileState.NeedsDecision)
                false
            }
            owner == null && !vault.hasArchive(uid) -> {
                vault.claim(uid)
                true
            }
            else -> {
                switchProfile(uid, fromUnclaimed = false)
                true
            }
        }.also { ready -> if (ready) account.setProfile(ProfileState.Ready) }
    }
    if (bound) afterBound()
}

/** The one-time answer to "keep what is on this phone?". */
suspend fun AuroraApplication.decideLocalData(uid: String, keep: Boolean) {
    entitlements.withLock {
        val owner = vault.owner()
        val ledgerSince = System.currentTimeMillis()
        when {
            keep && owner == null -> vault.claim(uid, ledgerSince)
            keep -> {
                switchProfile(uid, fromUnclaimed = true)
                vault.claim(uid, ledgerSince)
            }
            else -> {
                vault.rememberDeclined(uid)
                switchProfile(uid, fromUnclaimed = false)
            }
        }
        account.setProfile(ProfileState.Ready)
    }
    afterBound()
}

private suspend fun AuroraApplication.switchProfile(uid: String, fromUnclaimed: Boolean) {
    val t = now()
    val label = getString(R.string.account_carried_commitment)
    vault.switchTo(uid, fromUnclaimed) { active, incoming ->
        val merged = mergeCarried(active?.let(::decodeRuntime), incoming?.let(::decodeRuntime), t, label)
        AuroraJson.encodeToString(Runtime.serializer(), merged)
    }
    rules.reload()
    runtime.reload()
    history.reload()
    billing.reloadCache()
}

private fun decodeRuntime(raw: String): Runtime? = runCatching { AuroraJson.decodeFromString(Runtime.serializer(), raw) }.getOrNull()

private suspend fun AuroraApplication.afterBound() {
    account.sync()
    runCatching { billing.refresh() }
    importLedgerIfPending()
}

/** Committed time from before this account joins its ledger. Only ever adds, and the server takes each entry once. */
suspend fun AuroraApplication.importLedgerIfPending() {
    val since = vault.ledgerImportPending() ?: return
    val entries = runtime.value?.ledger.orEmpty().filter { it.start < since }.map { Triple(it.start, it.end, it.zone ?: ZoneId.systemDefault().id) }
    if (entries.isEmpty() || account.importLedger(entries)) vault.ledgerImported()
}

/**
 * The account is scheduled for deletion. This phone's copy goes now, except what is running: deleting an account
 * isn't a way out of a commitment.
 */
suspend fun AuroraApplication.afterDeletionRequested() {
    val uid = account.uid
    val t = now()
    val label = getString(R.string.account_carried_commitment)
    val carried = runtime.value?.carriedCommitments(t, label) ?: Runtime()
    deleteAllData()
    if (uid != null) vault.forget(uid)
    if (carried.runs.isNotEmpty() || carried.sessions.isNotEmpty()) {
        runCatching { runtime.update { carried } }
    } else {
        runCatching { runtime.reset() }
    }
    account.signOut()
}

/** Free's minutes left today: the fewer of what this phone and the server count. Null on Plus. */
fun AuroraApplication.serverAdjustedRemainingMs(localRemainingMs: Long?): Long? {
    if (localRemainingMs == null || plan == Plan.Plus) return localRemainingMs
    return minOf(localRemainingMs, account.serverRemainingMs() ?: localRemainingMs)
}

/** A committed schedule due now that the server hasn't confirmed yet: nothing is enforced until it does. */
fun AuroraApplication.isWaitingForServer(a: app.aurora.protect.Activation, d: app.aurora.ui.protect.ProtectData): Boolean {
    if (d.plan == Plan.Plus) return false
    return app.aurora.protect.occurrencesToAuthorize(d.rules, d.runtime, d.plan, d.time, ZoneId.systemDefault(), 0)
        .any { it.run.activationId == a.id && it.run.covers(d.time) }
}
