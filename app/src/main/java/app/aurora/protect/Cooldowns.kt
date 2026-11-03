package app.aurora.protect

/*
 * Both cooldowns, as pure transitions on Runtime (CooldownTest). They are separate features with separate
 * state: the Shield Cooldown wait (before access, under a Cooldown Shield) lives in [Runtime.cooldownWaits];
 * the Intent post-usage cooldown (after the chosen time ran out) lives in [Runtime.intentCooldowns].
 *
 * Both restart on every genuine new attempt to open the app: the full wait counts from the latest
 * attempt. What counts as genuine is decided once, in the Interceptor ([Interceptor.onWindow]): a new
 * opening after the person left the app, never a repeat event, a recomposition, a recheck or a reconnect.
 */

/**
 * After an Intent app's chosen time (and its cooldown) runs out, opening it again within this long means the
 * intentional use didn't hold: the runs covering it are not achieved.
 */
// ponytail: one fixed gap for every app; scale it with the chosen minutes if people ask.
const val IntentQuietMs = 15 * 60_000L

/** A finished Shield Cooldown wait stays usable this long, so stepping away before choosing a time doesn't mean waiting again. */
const val ReadyGraceMs = 5 * 60_000L

/**
 * A genuine new attempt to open [key] that [decision] shields, at [now]. The Shield Cooldown wait starts
 * again (unless it has just finished), and an Intent post-usage cooldown starts its full length again.
 * Nothing else changes, so an attempt can never open anything.
 */
fun Runtime.attempted(key: String, decision: Decision.Shield, now: Long): Runtime = when (decision.reason) {
    ShieldReason.Cooldown -> {
        val r = decision.restriction
        val w = cooldownWaits[key]?.takeIf { r != null && it.restrictionId == r.id }
        val justReady = w != null && now >= w.readyAt && now - w.readyAt < ReadyGraceMs
        if (r == null || justReady) this
        else copy(cooldownWaits = cooldownWaits + (key to CooldownWait(now + r.cooldownWaitMs, r.id, now)))
    }
    ShieldReason.IntentCooldown -> settledIntents(now).let { s ->
        val length = s.intentCooldownLengths[key]
        if (length == null || (s.intentCooldowns[key] ?: 0) <= now) s
        else s.copy(intentCooldowns = s.intentCooldowns + (key to now + length), intentQuiet = s.intentQuiet + (key to now + length + IntentQuietMs))
    }
    else -> this
}

/**
 * The Shield Cooldown wait the Shield shows for [key] under [restriction]: the one running, or one that
 * finished within [ReadyGraceMs]; otherwise a new one from [now]. Showing the Shield never restarts a
 * running wait: only [attempted] does.
 */
fun Runtime.waitFor(key: String, restriction: Restriction, now: Long): Runtime {
    val w = cooldownWaits[key]?.takeIf { it.restrictionId == restriction.id }
    if (w != null && now - w.readyAt < ReadyGraceMs) return this
    return copy(cooldownWaits = cooldownWaits + (key to CooldownWait(now + restriction.cooldownWaitMs, restriction.id, now)))
}

/**
 * Every intentional session whose chosen time is used up and that ends in a cooldown becomes that
 * cooldown, stored, from [now]. Idempotent. This is the only way such a session ends, so leaving the app,
 * a fresh-each-opening setting or a new reflection can never skip the cooldown.
 */
fun Runtime.settledIntents(now: Long): Runtime {
    val spent = intentUses.filterValues { it.expiry == IntentExpiry.Cooldown && it.spent(now) }
    if (spent.isEmpty()) return this
    return copy(
        intentUses = intentUses - spent.keys,
        intentCooldowns = intentCooldowns + spent.mapValues { (_, u) -> now + u.cooldownMinutes * 60_000L },
        intentCooldownLengths = intentCooldownLengths + spent.mapValues { (_, u) -> u.cooldownMinutes * 60_000L },
        intentQuiet = intentQuiet + spent.mapValues { (_, u) -> now + u.cooldownMinutes * 60_000L + IntentQuietMs },
    )
}

/**
 * The intentional session for [key] ended at [now] with its time used up (no cooldown): reopening the app
 * within [IntentQuietMs] is a relapse.
 */
fun Runtime.quietAfter(key: String, now: Long): Runtime = copy(intentQuiet = intentQuiet + (key to now + IntentQuietMs))

/**
 * An intentional session for [key] starts at [now]. If it is within the quiet gap after the last one ran
 * out, every run covering [key] with Intent counts a relapse.
 */
fun Runtime.relapsed(key: String, t: DeviceTime): Runtime =
    if ((intentQuiet[key] ?: 0) <= t.wall) this
    else copy(runs = runs.map { if (it.covers(t) && key in it.intent?.keys.orEmpty()) it.copy(intentRelapses = it.intentRelapses + 1) else it })

/** A Friction task or Cooldown wait opened [key] at [t]: every run shielding it counts the grant. */
fun Runtime.granted(key: String, t: DeviceTime): Runtime =
    copy(runs = runs.map { if (it.covers(t) && key in it.shield?.keys.orEmpty()) it.copy(accessGrants = it.accessGrants + 1) else it })
