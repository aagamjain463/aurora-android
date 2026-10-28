package app.aurora.data

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import app.aurora.protect.ContentService
import app.aurora.protect.ShieldService

/**
 * Special access aurora can use. Only the person grants it, in Android Settings: aurora explains
 * why, opens the right screen, and re-checks when they come back. It never turns anything on itself.
 */
enum class Access(val title: String, val why: String) {
    Usage("Usage access", "Powers Insights. Read on this phone only."),
    Accessibility("Accessibility", "Lets aurora notice when a protected app opens."),
    Notifications("Notifications", "Tells you when a focus session ends."),
    Content("Web & content Shield", "Lets aurora see the address in supported browsers, and when Shorts or Reels is open."),
}

/** [Revoked] is access that was granted earlier in this visit and has since been turned off. */
enum class AccessState { Granted, Missing, Revoked }

fun accessState(granted: Boolean, previous: AccessState?): AccessState = when {
    granted -> AccessState.Granted
    previous == AccessState.Granted || previous == AccessState.Revoked -> AccessState.Revoked
    else -> AccessState.Missing
}

/**
 * Whether protection actually works, which is more than having permission. Only [Active] may be
 * described as protected.
 *
 * - [Off]: nothing to protect.
 * - [Blocked]: Android or the device maker won't let the service run (Advanced Protection mode,
 *   restricted settings, an OEM policy). Sending the person to a switch they can't use helps no one.
 * - [NeedsPermission]: the accessibility switch is off.
 * - [NotRunning]: the switch is on, but the service isn't connected (force-stopped, crashed, killed,
 *   or not yet bound after a reboot).
 * - [ConfigError]: aurora can't read which items are protected, so it shields nothing (protect/Policy.kt).
 */
enum class ProtectionStatus { Off, Blocked, NeedsPermission, NotRunning, ConfigError, Active }

fun protectionStatus(
    hasItems: Boolean,
    blocked: Boolean,
    accessibility: AccessState,
    connected: Boolean,
    readable: Boolean = true,
): ProtectionStatus = when {
    !readable -> ProtectionStatus.ConfigError
    !hasItems -> ProtectionStatus.Off
    blocked -> ProtectionStatus.Blocked
    accessibility != AccessState.Granted -> ProtectionStatus.NeedsPermission
    !connected -> ProtectionStatus.NotRunning
    else -> ProtectionStatus.Active
}

/** True when a service is in Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, by its long or short flattened name. */
fun isListedInEnabledServices(setting: String?, component: String, shortComponent: String): Boolean =
    setting.orEmpty().split(':').any { it.equals(component, ignoreCase = true) || it.equals(shortComponent, ignoreCase = true) }

/** The one place that reads aurora's access. Screens re-check it on resume, through `rememberAccess()`. */
class AccessRepository(private val context: Context, private val screenTime: ScreenTimeRepository) {

    private val shieldService = ComponentName(context, ShieldService::class.java)
    private val contentService = ComponentName(context, ContentService::class.java)

    fun isGranted(access: Access): Boolean = when (access) {
        Access.Usage -> screenTime.hasAccess()
        Access.Accessibility -> enabled(shieldService)
        Access.Content -> enabled(contentService)
        Access.Notifications -> NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    private fun enabled(service: ComponentName) = isListedInEnabledServices(
        Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES),
        service.flattenToString(),
        service.flattenToShortString(),
    )
}

/** Opens the Settings screen for [access], or Android's main Settings where the device lacks that screen. */
fun openAccessSettings(context: Context, access: Access) {
    val intent = when (access) {
        Access.Usage -> return openUsageAccessSettings(context)
        Access.Accessibility, Access.Content -> Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        Access.Notifications -> Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
    }
    try {
        context.startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        context.startActivity(Intent(Settings.ACTION_SETTINGS))
    }
}
