package app.aurora.protect

/*
 * The protection decision (docs/SHIELD.md, docs/PRODUCT.md "Rule precedence"). Pure Kotlin with no
 * Android, so every rule is unit tested. The accessibility services only turn events into a key (a
 * package name, or "web:<domain>" for a site), ask [decide], and act on the answer.
 *
 * decide() never reads the plan. The plan decides which items, schedules, limits and Intent notes are
 * *active* before the snapshot is built, so Free and Plus get the same protection strength for the
 * items they have.
 */

/**
 * Why the Shield appears, strongest first. The first four are timed or budgeted restrictions with no way
 * through; [Cooldown] and [Blocked] have no way through either; only [Choice] lets the person continue.
 */
enum class ShieldReason {
    Lockdown,
    Schedule,
    Focus,
    Limit,
    Cooldown,

    /** Always blocked (the item's mode is Block). */
    Blocked,

    /** Flexible, Friction or Cooldown mode, outside any restriction: the person may choose to continue. */
    Choice,
    ;

    /** True when the Shield offers a way through (after any pause the item asks for). */
    val canContinue: Boolean get() = this == Choice
}

sealed interface Decision {
    /** Not protected, or protected but free to use right now (the item's mode is RulesOnly). */
    data object Allow : Decision

    /** Never shielded, whatever the person chose: aurora, the system, phone and recovery surfaces. */
    data object Exempt : Decision

    /** Protected, but the person chose time with it until [until] (wall clock). */
    data class TemporarilyAllowed(val until: Long) : Decision

    /** [until] is when the restriction ends, when that is known (wall clock). */
    data class Shield(val packageName: String, val reason: ShieldReason, val until: Long? = null) : Decision

    /**
     * The configuration isn't known: not loaded yet, or unreadable. aurora doesn't guess in either
     * direction. It shields nothing, and every status surface says protection isn't working.
     */
    data object Unavailable : Decision

    /** The event carried no package. */
    data object Invalid : Decision
}

/** A restriction over [keys] from [start] to [end] (wall clock): a Lockdown, a schedule window or Focus. */
data class TimedBlock(val reason: ShieldReason, val keys: Set<String>, val start: Long, val end: Long) {
    fun covers(key: String, now: Long) = key in keys && now >= start && now < end
}

/**
 * A plain snapshot of everything [decide] needs. Keys are package names, or "web:<domain>" for sites.
 *
 * - [protectedPackages]: the *active* protected items, after the plan's allowance.
 * - [modes]: each item's everyday mode. A missing entry is [Mode.Block], the Phase 3 behaviour.
 * - [blocks]: Lockdown, Focus and schedule windows, current and upcoming.
 * - [limits] and [usedToday]: active daily budgets and today's measured use, in ms.
 * - [grants]: time the person chose, ending at the wall-clock value.
 * - [cooldowns]: when a Cooldown item can be opened again.
 * - [midnight]: the next local midnight, when daily budgets reset.
 */
data class PolicyState(
    val protectedPackages: Set<String>,
    val exempt: Set<String>,
    val grants: Map<String, Long> = emptyMap(),
    val modes: Map<String, Mode> = emptyMap(),
    val blocks: List<TimedBlock> = emptyList(),
    val limits: Map<String, Long> = emptyMap(),
    val usedToday: Map<String, Long> = emptyMap(),
    val cooldowns: Map<String, Long> = emptyMap(),
    val midnight: Long = Long.MAX_VALUE,
)

/**
 * What to do when [packageName] comes to the front. A null [state] means the configuration isn't known.
 *
 * Precedence, strongest first: Lockdown, schedule, Focus, daily limit, then the person's own grant,
 * then a running cooldown, then the item's mode. A grant never lifts a Lockdown, schedule, Focus
 * session or spent budget.
 */
fun decide(packageName: String?, now: Long, state: PolicyState?): Decision {
    if (packageName.isNullOrBlank()) return Decision.Invalid
    if (state == null) return Decision.Unavailable
    if (packageName in state.exempt) return Decision.Exempt
    if (packageName !in state.protectedPackages) return Decision.Allow

    state.blocks.filter { it.covers(packageName, now) }.minByOrNull { it.reason.ordinal }
        ?.let { return Decision.Shield(packageName, it.reason, it.end) }
    if (state.limitSpent(packageName)) return Decision.Shield(packageName, ShieldReason.Limit, state.midnight)
    state.grants[packageName]?.takeIf { it > now }?.let { return Decision.TemporarilyAllowed(it) }
    state.cooldowns[packageName]?.takeIf { it > now }?.let { return Decision.Shield(packageName, ShieldReason.Cooldown, it) }
    return when (state.modes[packageName] ?: Mode.Block) {
        Mode.RulesOnly -> Decision.Allow
        Mode.Block -> Decision.Shield(packageName, ShieldReason.Blocked)
        Mode.Flexible, Mode.Friction, Mode.Cooldown -> Decision.Shield(packageName, ShieldReason.Choice)
    }
}

private fun PolicyState.limitSpent(key: String): Boolean = limits[key]?.let { (usedToday[key] ?: 0L) >= it } ?: false

/**
 * The next moment [key]'s decision can change on its own (a grant or cooldown ending, a window opening
 * or closing, a budget running out, midnight), or null if nothing is due. The interceptor re-checks the
 * app in front then, so a grant that ends while the app is open brings the Shield back.
 */
fun nextChange(key: String, now: Long, state: PolicyState): Long? {
    if (key !in state.protectedPackages || key in state.exempt) return null
    val times = buildList {
        state.grants[key]?.let(::add)
        state.cooldowns[key]?.let(::add)
        state.blocks.filter { key in it.keys }.forEach { add(it.start); add(it.end) }
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
)
