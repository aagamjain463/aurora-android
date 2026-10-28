package app.aurora.protect

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import android.telecom.TelecomManager

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
    return SystemExempt + launchers + settings + listOfNotNull(keyboard(context), dialer, context.packageName)
}

/** Windows that can sit over the Shield without replacing it: the notification shade, system dialogs, the keyboard. */
fun overlayPackages(context: Context): Set<String> = setOfNotNull("com.android.systemui", "android", keyboard(context))

private fun keyboard(context: Context): String? =
    Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)?.substringBefore('/')
