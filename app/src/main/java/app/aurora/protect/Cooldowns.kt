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
        else s.copy(intentCooldowns = s.intentCooldowns + (key to now + length))
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
    )
}
