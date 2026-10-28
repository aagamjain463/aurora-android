package app.aurora.data

import android.Manifest
import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Process
import android.provider.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.ZoneId

/**
 * Today's screen time, computed on the device from Android's own usage log.
 * Requires Usage access, which only the person can grant in system Settings.
 */
class ScreenTimeRepository(private val context: Context) {

    @Suppress("DEPRECATION") // Both op-check APIs are deprecated at some level in 26..36; behaviour is unchanged.
    fun hasAccess(): Boolean {
        val appOps = context.getSystemService(AppOpsManager::class.java)
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        } else {
            appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        }
        return when (mode) {
            AppOpsManager.MODE_ALLOWED -> true
            AppOpsManager.MODE_DEFAULT ->
                context.checkSelfPermission(Manifest.permission.PACKAGE_USAGE_STATS) == PackageManager.PERMISSION_GRANTED
            else -> false
        }
    }

    /** Foreground time per package since local midnight, excluding the home screen. */
    suspend fun today(now: Long = System.currentTimeMillis()): Map<String, Long> = withContext(Dispatchers.IO) {
        if (!hasAccess()) return@withContext emptyMap()
        val from = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val events = context.getSystemService(UsageStatsManager::class.java).queryEvents(from, now)
        val event = UsageEvents.Event()
        val signals = ArrayList<UsageSignal>()
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            val kind = when (event.eventType) {
                RESUMED -> UsageSignal.Kind.Start
                PAUSED -> UsageSignal.Kind.Stop
                SCREEN_OFF, SHUTDOWN -> UsageSignal.Kind.StopAll
                else -> null
            } ?: continue
            signals += UsageSignal(kind, event.packageName, event.timeStamp)
        }
        foregroundTime(signals, from, now) - setOfNotNull(homePackage())
    }

    private fun homePackage(): String? {
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        @Suppress("DEPRECATION")
        return context.packageManager.resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName
    }

    private companion object {
        // Raw values so they work on every supported API level (some constants were added in 28/29).
        const val RESUMED = 1 // ACTIVITY_RESUMED / MOVE_TO_FOREGROUND
        const val PAUSED = 2 // ACTIVITY_PAUSED / MOVE_TO_BACKGROUND
        const val SCREEN_OFF = 16 // SCREEN_NON_INTERACTIVE
        const val SHUTDOWN = 26 // DEVICE_SHUTDOWN
    }
}

data class UsageSignal(val kind: Kind, val packageName: String, val at: Long) {
    enum class Kind { Start, Stop, StopAll }
}

/**
 * Sums foreground time per package within [from, to]. One app is in the foreground at a time;
 * an app already open when the window starts is counted from [from], one still open from [to].
 */
fun foregroundTime(signals: List<UsageSignal>, from: Long, to: Long): Map<String, Long> {
    val totals = HashMap<String, Long>()
    var current: String? = null
    var since = from
    var sawStart = false

    fun add(pkg: String, ms: Long) {
        if (ms > 0) totals[pkg] = (totals[pkg] ?: 0L) + ms
    }

    fun close(at: Long) {
        current?.let { add(it, at - since) }
        current = null
    }

    for (s in signals) {
        when (s.kind) {
            UsageSignal.Kind.Start -> {
                sawStart = true
                if (s.packageName != current) {
                    close(s.at)
                    current = s.packageName
                    since = s.at
                }
            }
            UsageSignal.Kind.Stop -> when {
                s.packageName == current -> close(s.at)
                !sawStart -> add(s.packageName, s.at - from)
            }
            UsageSignal.Kind.StopAll -> close(s.at)
        }
    }
    close(to)
    return totals
}

/** Opens Android's Usage access screen, deep-linked to aurora where the device supports it. */
fun openUsageAccessSettings(context: Context) {
    val direct = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS, Uri.fromParts("package", context.packageName, null))
    try {
        context.startActivity(direct)
    } catch (_: ActivityNotFoundException) {
        context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
    } catch (_: SecurityException) {
        context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
    }
}
