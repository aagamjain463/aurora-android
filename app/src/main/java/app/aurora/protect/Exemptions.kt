package app.aurora.protect

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import android.telecom.TelecomManager
import android.view.accessibility.AccessibilityManager
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
 * Locked whole while a strict commitment runs ([PolicyState.locked]): the package installers that uninstall
 * apps, and OEM security apps that uninstall and force stop. Settings itself stays open; only its
 * Accessibility and Date & time pages are locked ([lockedScreens]). Never the phone or emergency flows.
 */
fun lockedPackages(context: Context): Set<String> = (installers(context) + LockedSystem - context.packageName).toSet()

/**
 * The Settings pages a strict commitment locks ([LockedScreens]). Titles come from this device's own
 * Settings, in its language, plus the names of aurora's accessibility services, because Settings search
 * opens a service's page directly.
 */
fun lockedScreens(context: Context): LockedScreens {
    val pm = context.packageManager
    @Suppress("DEPRECATION")
    fun handlers(action: String) = pm.queryIntentActivities(Intent(action), PackageManager.MATCH_DEFAULT_ONLY)
    val pages = handlers(Settings.ACTION_ACCESSIBILITY_SETTINGS) + handlers(Settings.ACTION_DATE_SETTINGS)
    // A page without its own label reports the Settings app's name, which would lock all of Settings.
    val pageTitles = pages.map { it.loadLabel(pm).toString() }.filter { title -> pages.none { it.activityInfo.applicationInfo.loadLabel(pm).toString() == title } }
    val services = context.getSystemService(AccessibilityManager::class.java)?.installedAccessibilityServiceList.orEmpty()
        .filter { it.resolveInfo.serviceInfo.packageName == context.packageName }
        .map { it.resolveInfo.loadLabel(pm).toString() }
    val packages = (handlers(Settings.ACTION_SETTINGS) + pages).map { it.activityInfo.packageName } + "com.android.settings"
    return LockedScreens(
        packages = packages.toSet() - context.packageName,
        titles = (pageTitles + services + LockedTitles).map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet(),
    )
}

/** English titles OEM Settings use for these pages, in case a page's label differs from its activity's. */
private val LockedTitles = listOf("Accessibility", "Date & time", "Date and time")

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
