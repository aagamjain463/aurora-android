package app.aurora.protect

import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import app.aurora.R

/**
 * Uninstall protection: a device administrator with no policies (res/xml/device_admin.xml). Android
 * refuses to uninstall an active administrator, from the launcher, the Play Store or a computer, until
 * it is deactivated in Settings, and Settings is locked while a strict commitment runs. Outside one, the
 * person turns it off in aurora (or in Settings) and can uninstall as usual.
 */
class UninstallGuard : DeviceAdminReceiver() {
    override fun onDisableRequested(context: Context, intent: Intent): CharSequence =
        context.getString(R.string.admin_disable_warning)

    companion object {
        fun component(context: Context) = ComponentName(context, UninstallGuard::class.java)

        fun active(context: Context): Boolean =
            context.getSystemService(DevicePolicyManager::class.java)?.isAdminActive(component(context)) == true

        /** Android's own confirmation screen for turning it on. */
        fun enableIntent(context: Context): Intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
            .putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, component(context))
            .putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, context.getString(R.string.admin_explanation))

        fun disable(context: Context) {
            context.getSystemService(DevicePolicyManager::class.java)?.removeActiveAdmin(component(context))
        }
    }
}
