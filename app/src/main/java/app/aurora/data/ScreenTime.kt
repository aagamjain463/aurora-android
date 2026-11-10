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
import android.os.BadParcelableException
import android.os.Build
import android.os.Process
import android.provider.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.ZoneId

/**
 * Today's screen time, computed on the device from Android's own usage log.
 * Requires Usage access, which only the person can grant in system Settings.
 */
class ScreenTimeRepository(private val context: Context) {

    /** Usage reads are blocking Binder calls: one at a time, so a burst of them can't take every IO thread. */
    private val usageIo = Dispatchers.IO.limitedParallelism(1)

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
        between(LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli(), now, finished = false) ?: emptyMap()

    /**
     * Foreground time per package on [date] (local), up to now for today. Null when Android has no
     * records for that day (they're kept for a limited time) or usage access is off: unavailable, not zero.
     */
    suspend fun day(date: LocalDate, now: Long = System.currentTimeMillis()): Map<String, Long>? {
        val zone = ZoneId.systemDefault()
        val from = date.atStartOfDay(zone).toInstant().toEpochMilli()
        val end = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val to = minOf(end, now)
        return if (from >= to) null else between(from, to, finished = end <= now)
    }

    /**
     * Foreground spans per app within [from, to): what Time away subtracts. Null without usage access, so
     * callers show "unavailable" instead of a made-up number.
     */
    suspend fun intervals(from: Long, to: Long): List<UsageInterval>? = withContext(usageIo) {
        if (!hasAccess() || from >= to) return@withContext null
        // Shield doesn't pause the app beneath it, so seed its earlier resume before clipping.
        // shortcut: one prior day seeds foreground; extend for uninterrupted multi-day app use.
        records(from, to, seedMs = 86_400_000L)?.let { foregroundIntervals(it.signals, from, to) }
    }

    private data class Records(val signals: List<UsageSignal>, val any: Boolean)

    /**
     * Hour-sized reads avoid Android 16's large UsageEvents Binder transfer failure. [seedMs] reads that much
     * earlier too, to find the app already in front at [from]; [Records.any] still means records within the window.
     */
    private suspend fun records(from: Long, to: Long, seedMs: Long): Records? {
        val manager = context.getSystemService(UsageStatsManager::class.java)
        val event = UsageEvents.Event()
        val signals = ArrayList<UsageSignal>()
        var any = false
        var cursor = (from - seedMs).coerceAtLeast(0)
        try {
            while (cursor < to) {
                currentCoroutineContext().ensureActive()
                val end = minOf(cursor + 3_600_000L, to)
                val events = manager.queryEvents(cursor, end) ?: return null
                while (events.hasNextEvent()) {
                    events.getNextEvent(event)
                    if (event.timeStamp >= from) any = true
                    val kind = when (event.eventType) {
                        RESUMED -> UsageSignal.Kind.Start
                        PAUSED -> UsageSignal.Kind.Stop
                        SCREEN_OFF, SHUTDOWN -> UsageSignal.Kind.StopAll
                        else -> null
                    } ?: continue
                    signals += UsageSignal(kind, event.packageName, event.timeStamp)
                }
                cursor = end
            }
        } catch (_: BadParcelableException) {
            return null
        } catch (_: SecurityException) {
            return null
        }
        return Records(signals, any)
    }

    private suspend fun between(from: Long, to: Long, finished: Boolean): Map<String, Long>? = withContext(usageIo) {
        if (!hasAccess()) return@withContext null
        // ponytail: six hours finds an app open across midnight; one left open longer starts at its next event.
        val records = records(from, to, seedMs = 6 * 3_600_000L) ?: return@withContext null
        // No events at all: Android has nothing for this finished day. Today counts as zero (a quiet morning).
        if (!records.any && finished) return@withContext null
        foregroundTime(records.signals, from, to) - setOfNotNull(homePackage(), context.packageName)
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

/** One app in front from [start] to [end] (wall clock). */
data class UsageInterval(val packageName: String, val start: Long, val end: Long)

/**
 * Sums foreground time per package within [from, to]. One app is in the foreground at a time;
 * an app already open when the window starts is counted from [from], one still open from [to].
 */
fun foregroundTime(signals: List<UsageSignal>, from: Long, to: Long): Map<String, Long> =
    foregroundIntervals(signals, from, to).groupBy { it.packageName }.mapValues { (_, spans) -> spans.sumOf { it.end - it.start } }

/** The spans behind [foregroundTime]. */
fun foregroundIntervals(signals: List<UsageSignal>, from: Long, to: Long): List<UsageInterval> {
    val totals = ArrayList<UsageInterval>()
    var current: String? = null
    var since = from
    var sawStart = false

    fun add(pkg: String, ms: Long, end: Long) {
        val start = (end - ms).coerceAtLeast(from)
        val clippedEnd = end.coerceAtMost(to)
        if (clippedEnd > start) totals += UsageInterval(pkg, start, clippedEnd)
    }

    fun close(at: Long) {
        current?.let { add(it, at - since, at) }
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
                !sawStart -> add(s.packageName, s.at - from, s.at)
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
