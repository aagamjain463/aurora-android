package app.aurora.protect

/*
 * The protection decision (docs/SHIELD.md, docs/PRODUCT.md "Rule precedence"). Pure Kotlin with no
 * Android, so every rule is unit tested. The accessibility services only turn events into a key (a
 * package name, or "web:<domain>" for a site), ask [decide], and act on the answer.
 *
 * decide() never reads the plan. The plan decides which items, schedules, limits and Intent apps are
 * *active* before the snapshot is built, so Free and Plus get the same protection strength.
 */

/**
 * Why the Shield appears, strongest first. Precedence follows this order: the strongest restriction that
 * covers an app decides, and nothing weaker can lift it.
 *
 * - [AlwaysBlock], [Flexible], [Focus], [Limit]: no way through from the Shield.
 * - [Cooldown]: a wait, then timed access. [Friction]: a task, then timed access.
 * - [IntentCooldown]: closed after intentional use, until its timer ends.
 * - [Intent]: a deliberate decision and a chosen amount of time.
 */
enum class ShieldReason {
    AlwaysBlock,
    Flexible,
    Focus,
    Limit,
    Cooldown,
    Friction,
    IntentCooldown,
    Intent,
    ;

    /** True when the Shield itself can lead to access (after a wait, a task or a decision). */
    val canContinue: Boolean get() = this == Cooldown || this == Friction || this == Intent
}

val Mode.reason: ShieldReason
    get() = when (this) {
        Mode.Flexible -> ShieldReason.Flexible
        Mode.Friction -> ShieldReason.Friction
        Mode.Cooldown -> ShieldReason.Cooldown
        Mode.AlwaysBlock -> ShieldReason.AlwaysBlock
    }

/**
 * One active or upcoming restriction over [keys] from [start] to [end] (wall clock): a session, a
 * schedule window, Focus, or a migrated Lockdown. [id] identifies this instance, so a Cooldown wait
 * belongs to one window and a new window needs a new wait.
 */
data class Restriction(
    val reason: ShieldReason,
    val keys: Set<String>,
    val start: Long,
    val end: Long,
    val id: String,
    val friction: FrictionSettings = FrictionSettings(),
    val cooldownWaitMs: Long = 0,
    val sessionId: String? = null,
    val scheduleId: String? = null,
    val label: String = "",
) {
    fun covers(key: String, now: Long) = key in keys && now >= start && now < end
}

sealed interface Decision {
    /** Not protected, or nothing applies right now. */
    data object Allow : Decision

    /** Never shielded, whatever the person chose: aurora, the system, phone and recovery surfaces. */
    data object Exempt : Decision

    /** Protected, but the person has timed access until [until] (wall clock; for Intent, an estimate). */
    data class TemporarilyAllowed(val until: Long) : Decision

    /**
     * [until] is when the restriction ends, when known (wall clock). [restriction] is the one that
     * decided, null for limits and Intent. [readyAt] is when a Cooldown wait ends, once one is running.
     */
    data class Shield(
        val packageName: String,
        val reason: ShieldReason,
        val until: Long? = null,
        val restriction: Restriction? = null,
        val readyAt: Long? = null,
        /** For [ShieldReason.Intent]: the chosen time ran out while the app was open (Reminder). */
        val timeUp: Boolean = false,
    ) : Decision

    /**
     * The configuration isn't known: not loaded yet, or unreadable. aurora doesn't guess in either
     * direction. It shields nothing, and every status surface says protection isn't working.
     */
    data object Unavailable : Decision

    /** The event carried no package. */
    data object Invalid : Decision
}

/**
 * A plain snapshot of everything [decide] needs. Keys are package names, or "web:<domain>" for sites.
 *
 * - [protectedPackages]: every key something could apply to; the interceptor watches these.
 * - [restrictions]: sessions, schedule windows and Focus, current and upcoming.
 * - [access]: Friction and Cooldown grants. [cooldownWaits]: running Shield Cooldown waits.
 * - [limits] and [usedToday]: active daily budgets and today's measured use, in ms.
 * - [intents], [intentUses], [intentCooldowns]: Intentional App Opening.
 * - [midnight]: the next local midnight, when daily budgets reset.
 */
data class PolicyState(
    val protectedPackages: Set<String>,
    val exempt: Set<String>,
    val restrictions: List<Restriction> = emptyList(),
    val access: Map<String, Grant> = emptyMap(),
    val cooldownWaits: Map<String, CooldownWait> = emptyMap(),
    val limits: Map<String, Long> = emptyMap(),
    val usedToday: Map<String, Long> = emptyMap(),
    val intents: Map<String, IntentConfig> = emptyMap(),
    val intentUses: Map<String, IntentUse> = emptyMap(),
    val intentCooldowns: Map<String, Long> = emptyMap(),
    val midnight: Long = Long.MAX_VALUE,
)

/** The strongest restriction over [key] at [now], or null. Limits count as a restriction from [ShieldReason.Limit]. */
fun PolicyState.strongest(key: String, now: Long): Restriction? =
    restrictions.filter { it.covers(key, now) }.minByOrNull { it.reason.ordinal }

/**
 * What to do when [packageName] comes to the front. A null [state] means the configuration isn't known.
 *
 * Precedence, strongest first: Always Block, Flexible, Focus, a spent daily limit, Cooldown, Friction,
 * an Intent cooldown, then Intent. A grant lifts only its own level and weaker: a Cooldown grant lifts
 * Cooldown and Friction, a Friction grant lifts Friction. Any grant also covers Intent, so the person
 * isn't asked twice. Nothing lifts Always Block, Flexible, Focus or a spent limit.
 */
fun decide(packageName: String?, now: Long, state: PolicyState?): Decision {
    if (packageName.isNullOrBlank()) return Decision.Invalid
    if (state == null) return Decision.Unavailable
    if (packageName in state.exempt) return Decision.Exempt

    val top = state.strongest(packageName, now)
    val limitSpent = state.limits[packageName]?.let { (state.usedToday[packageName] ?: 0L) >= it } ?: false
    val grant = state.access[packageName]?.takeIf { it.until > now }

    if (top != null && top.reason.ordinal < ShieldReason.Limit.ordinal) return Decision.Shield(packageName, top.reason, top.end, top)
    if (limitSpent) return Decision.Shield(packageName, ShieldReason.Limit, state.midnight)
    if (top != null) {
        // Cooldown or Friction.
        if (grant != null && grant.level.reason.ordinal <= top.reason.ordinal) return Decision.TemporarilyAllowed(minOf(grant.until, top.end))
        val wait = state.cooldownWaits[packageName]?.takeIf { top.reason == ShieldReason.Cooldown && it.restrictionId == top.id }
        return Decision.Shield(packageName, top.reason, top.end, top, readyAt = wait?.readyAt)
    }
    if (grant != null) return Decision.TemporarilyAllowed(grant.until)

    state.intentCooldowns[packageName]?.takeIf { it > now }?.let { return Decision.Shield(packageName, ShieldReason.IntentCooldown, it) }
    if (packageName !in state.intents) return Decision.Allow
    val use = state.intentUses[packageName]
    return when {
        use == null -> Decision.Shield(packageName, ShieldReason.Intent)
        !use.spent(now) -> Decision.TemporarilyAllowed(now + use.left(now))
        // Time's up with a cooldown: closed now. The service stores the exact end (settleIntent).
        use.expiry == IntentExpiry.Cooldown -> Decision.Shield(packageName, ShieldReason.IntentCooldown, now + use.cooldownMinutes * 60_000L)
        else -> Decision.Shield(packageName, ShieldReason.Intent, timeUp = true)
    }
}

/**
 * The next moment [key]'s decision can change on its own (a grant, wait or cooldown ending, a window
 * opening or closing, a budget or an intentional allowance running out, midnight), or null if nothing
 * is due. The interceptor re-checks the app in front then, so time that ends while the app is open
 * brings the Shield back.
 */
fun nextChange(key: String, now: Long, state: PolicyState): Long? {
    if (key !in state.protectedPackages || key in state.exempt) return null
    val times = buildList {
        state.access[key]?.let { add(it.until) }
        state.cooldownWaits[key]?.let { add(it.readyAt) }
        state.intentCooldowns[key]?.let(::add)
        state.intentUses[key]?.takeIf { it.runningSince != null }?.let { add(now + it.left(now)) }
        state.restrictions.filter { key in it.keys }.forEach { add(it.start); add(it.end) }
        state.limits[key]?.let { budget ->
            add(state.midnight)
            // Assumes the app is in use from now on; the caller re-measures use at every check.
            val left = budget - (state.usedToday[key] ?: 0L)
            if (left > 0) add(now + left)
        }
    }
    return times.filter { it > now }.minOrNull()
}

/**
 * Never shielded: the system UI, Settings (so the service can always be turned off), permission
 * screens, package installers (so aurora can always be uninstalled), and phone and emergency flows.
 * The device's launchers, keyboard, default dialer and Settings app are added at runtime (Exemptions.kt).
 */
val SystemExempt = setOf(
    "android",
    "com.android.systemui",
    "com.android.settings",
    "com.android.packageinstaller",
    "com.google.android.packageinstaller",
    "com.android.permissioncontroller",
    "com.google.android.permissioncontroller",
    "com.android.phone",
    "com.android.server.telecom",
    "com.android.emergency",
    "com.google.android.apps.safetyhub",
    "com.miui.securitycenter",
)
