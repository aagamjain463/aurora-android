package app.aurora.protect

/*
 * The protection decision (docs/ROADMAP.md 3.1, docs/SHIELD.md). Pure Kotlin with no Android, so every
 * rule is unit tested. The accessibility service only turns window events into a package name, asks
 * [decide], and acts on the answer. Later phases add inputs to [PolicyState] (modes, Focus, schedules,
 * limits, Lockdown) and reasons to [ShieldReason]; nothing else has to change shape.
 *
 * decide() never reads the plan. The plan decides which items are *active* before the snapshot is built
 * (protectedPackages), so Free and Plus get the same protection strength for the items they have.
 */

/** Why the Shield appears. Phase 3 has one baseline behaviour: a protected app is always shielded. */
enum class ShieldReason { Protected }

sealed interface Decision {
    /** Not protected. */
    data object Allow : Decision

    /** Never shielded, whatever the person chose: aurora, the system, phone and recovery surfaces. */
    data object Exempt : Decision

    /** Protected, but the person was given time with it until [until] (wall clock). Created from Phase 4. */
    data class TemporarilyAllowed(val until: Long) : Decision

    data class Shield(val packageName: String, val reason: ShieldReason) : Decision

    /**
     * The protected items aren't known: not loaded yet, or unreadable. aurora doesn't guess in either
     * direction. It shields nothing, and every status surface says protection isn't working.
     */
    data object Unavailable : Decision

    /** The event carried no package. */
    data object Invalid : Decision
}

/**
 * A plain snapshot of everything [decide] needs. [protectedPackages] are the *active* protected apps,
 * after the plan's allowance. [grants] maps a package to the wall-clock time its access ends.
 */
data class PolicyState(
    val protectedPackages: Set<String>,
    val exempt: Set<String>,
    val grants: Map<String, Long> = emptyMap(),
)

/** What to do when [packageName] comes to the front. A null [state] means the configuration isn't known. */
fun decide(packageName: String?, now: Long, state: PolicyState?): Decision = when {
    packageName.isNullOrBlank() -> Decision.Invalid
    state == null -> Decision.Unavailable
    packageName in state.exempt -> Decision.Exempt
    packageName !in state.protectedPackages -> Decision.Allow
    else -> state.grants[packageName]?.takeIf { it > now }?.let(Decision::TemporarilyAllowed)
        ?: Decision.Shield(packageName, ShieldReason.Protected)
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
