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
    suspend fun today(now: Long = System.currentTimeMillis()): Map<String, Long> =
        between(LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli(), now) ?: emptyMap()

    /**
     * Foreground time per package on [date] (local), up to now for today. Null when Android has no
     * records for that day (they're kept for a limited time) or usage access is off: unavailable, not zero.
     */
    suspend fun day(date: LocalDate, now: Long = System.currentTimeMillis()): Map<String, Long>? {
        val zone = ZoneId.systemDefault()
        val from = date.atStartOfDay(zone).toInstant().toEpochMilli()
        val to = minOf(date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli(), now)
        return if (from >= to) null else between(from, to)
    }

    private suspend fun between(from: Long, to: Long): Map<String, Long>? = withContext(Dispatchers.IO) {
        if (!hasAccess()) return@withContext null
        val events = context.getSystemService(UsageStatsManager::class.java).queryEvents(from, to)
        val event = UsageEvents.Event()
        val signals = ArrayList<UsageSignal>()
        var any = false
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            any = true
            val kind = when (event.eventType) {
                RESUMED -> UsageSignal.Kind.Start
                PAUSED -> UsageSignal.Kind.Stop
                SCREEN_OFF, SHUTDOWN -> UsageSignal.Kind.StopAll
                else -> null
            } ?: continue
            signals += UsageSignal(kind, event.packageName, event.timeStamp)
        }
        // No events at all: Android has nothing for this window. Today counts as zero (a quiet morning).
        if (!any && to - from > 86_000_000L) return@withContext null
        foregroundTime(signals, from, to) - setOfNotNull(homePackage())
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
