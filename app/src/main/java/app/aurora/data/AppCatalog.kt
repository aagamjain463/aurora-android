package app.aurora.data

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.Collator
import kotlin.math.roundToInt

data class InstalledApp(val packageName: String, val label: String, val icon: ImageBitmap)

/** Real apps installed on this device. Reads the launcher list only; no QUERY_ALL_PACKAGES. */
class AppCatalog(private val context: Context) {
    private val pm: PackageManager get() = context.packageManager
    private val iconPx = (48 * context.resources.displayMetrics.density).roundToInt()

    /** Every app the person can open from their launcher, alphabetically, excluding aurora. */
    // ponytail: reloads per screen (~100 icons, a few hundred ms); cache + package-change receiver if it shows up in profiles.
    suspend fun launchableApps(): List<InstalledApp> = withContext(Dispatchers.IO) {
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        @Suppress("DEPRECATION")
        pm.queryIntentActivities(launcher, 0)
            .map { it.activityInfo.applicationInfo }
            .distinctBy { it.packageName }
            .filter { it.packageName != context.packageName }
            .map { it.toInstalledApp() }
            .sortedWith(compareBy(Collator.getInstance()) { it.label })
    }

    /** One app by package, or null if it has been uninstalled. */
    suspend fun app(packageName: String): InstalledApp? = withContext(Dispatchers.IO) {
        try {
            pm.getApplicationInfo(packageName, 0).toInstalledApp()
        } catch (_: PackageManager.NameNotFoundException) {
            null
        }
    }

    /** Just the name, which is much faster than the icon: the Shield shows it first. */
    suspend fun label(packageName: String): String? = withContext(Dispatchers.IO) {
        try {
            pm.getApplicationInfo(packageName, 0).loadLabel(pm).toString()
        } catch (_: PackageManager.NameNotFoundException) {
            null
        }
    }

    private fun ApplicationInfo.toInstalledApp() = InstalledApp(
        packageName = packageName,
        label = loadLabel(pm).toString(),
        icon = loadIcon(pm).toBitmap(iconPx, iconPx).asImageBitmap(),
    )
}
