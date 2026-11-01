package app.aurora.protect

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import android.telecom.TelecomManager
import androidx.core.net.toUri

/**
 * [SystemExempt] plus this device's own recovery surfaces: its launchers, Settings and Accessibility
 * settings apps (OEMs use their own packages), keyboard and default dialer, and aurora itself.
 * Read when the service connects. ponytail: a launcher or keyboard switched while it runs is picked up
 * at the next connect; listen for package and setting changes if that shows up.
 */
fun exemptPackages(context: Context): Set<String> {
    val pm = context.packageManager
    @Suppress("DEPRECATION")
    fun handlers(intent: Intent) = pm.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY).map { it.activityInfo.packageName }
    val launchers = handlers(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME))
    val settings = handlers(Intent(Settings.ACTION_SETTINGS)) + handlers(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    val dialer = context.getSystemService(TelecomManager::class.java)?.defaultDialerPackage
    return SystemExempt + LockedSystem + installers(context) + launchers + settings + listOfNotNull(keyboard(context), dialer, context.packageName)
}

/**
 * Locked while a strict commitment runs ([PolicyState.locked]): this device's Settings apps, where the
 * Shield service, permissions, uninstall protection, force stop and the date and time live, and the
 * package installers that uninstall apps. Never the phone or emergency flows.
 */
fun lockedPackages(context: Context): Set<String> {
    @Suppress("DEPRECATION")
    fun handlers(intent: Intent) = context.packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY).map { it.activityInfo.packageName }
    val settings = handlers(Intent(Settings.ACTION_SETTINGS)) + handlers(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) + handlers(Intent(Settings.ACTION_DATE_SETTINGS))
    return (settings + installers(context) + LockedSystem - context.packageName).toSet()
}

/** Whatever handles "uninstall this app" here. Device makers ship their own (Xiaomi: com.miui.global.packageinstaller). */
private fun installers(context: Context): List<String> {
    @Suppress("DEPRECATION")
    fun handlers(action: String) = context.packageManager
        .queryIntentActivities(Intent(action, "package:${context.packageName}".toUri()), PackageManager.MATCH_DEFAULT_ONLY)
        .map { it.activityInfo.packageName }
    @Suppress("DEPRECATION")
    return handlers(Intent.ACTION_DELETE) + handlers(Intent.ACTION_UNINSTALL_PACKAGE)
}

val LockedSystem = setOf(
    "com.android.settings",
    "com.android.packageinstaller",
    "com.google.android.packageinstaller",
    "com.miui.packageinstaller",
    "com.miui.global.packageinstaller",
    "com.samsung.android.packageinstaller",
    "com.miui.securitycenter",
    "com.google.android.apps.safetyhub",
)

/** Windows that can sit over the Shield without replacing it: the notification shade, system dialogs, the keyboard. */
fun overlayPackages(context: Context): Set<String> = setOfNotNull("com.android.systemui", "android", keyboard(context))

private fun keyboard(context: Context): String? =
    Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)?.substringBefore('/')
