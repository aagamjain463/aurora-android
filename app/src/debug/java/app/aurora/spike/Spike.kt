package app.aurora.spike

/*
 * Phase 2A enforcement spike (gate G1). Debug builds only; never merged into release.
 * The pure parts live here so they can be unit tested without Android. Phase 3 replaces them
 * with protect/Policy.kt and ProtectionStatus (see docs/ENFORCEMENT_FEASIBILITY.md).
 */

sealed interface SpikeDecision {
    data object Allow : SpikeDecision
    data class Shield(val packageName: String) : SpikeDecision
}

/** What to do when [packageName] comes to the front. Exemptions always win, then grants. */
fun spikeDecide(
    packageName: String,
    protected: Set<String>,
    exempt: Set<String>,
    grants: Map<String, Long>,
    now: Long,
): SpikeDecision = when {
    packageName in exempt -> SpikeDecision.Allow
    packageName !in protected -> SpikeDecision.Allow
    (grants[packageName] ?: 0L) > now -> SpikeDecision.Allow
    else -> SpikeDecision.Shield(packageName)
}

/**
 * Never shielded, whatever the person chose: the system UI, Settings (so the service can always be
 * turned off), package installers (so aurora can always be uninstalled), and phone and emergency flows.
 * The device's launchers, keyboard and default dialer are added at runtime.
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

enum class SpikeStatus { Off, NeedsPermission, NotRunning, Active }

/**
 * "Active" needs both the person's switch in Accessibility settings and a live connection to the
 * service. Enabled-but-not-connected (force-stopped, crashed, killed by an OEM) is not protection.
 */
fun spikeStatus(hasItems: Boolean, enabledInSettings: Boolean, connected: Boolean): SpikeStatus = when {
    !hasItems -> SpikeStatus.Off
    !enabledInSettings -> SpikeStatus.NeedsPermission
    !connected -> SpikeStatus.NotRunning
    else -> SpikeStatus.Active
}

/** True when the flattened [component] is in Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES. */
fun isListedInEnabledServices(setting: String?, component: String, shortComponent: String): Boolean =
    setting.orEmpty().split(':').any { it.equals(component, ignoreCase = true) || it.equals(shortComponent, ignoreCase = true) }
