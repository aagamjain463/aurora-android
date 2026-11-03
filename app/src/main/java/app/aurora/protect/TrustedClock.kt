package app.aurora.protect

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.SystemClock
import android.provider.Settings
import androidx.core.content.edit
import kotlin.math.abs

/**
 * The wall clock every protection decision reads. While a strict commitment runs (Strict, Cooldown,
 * Friction), it counts on Android's monotonic clock from an anchor, so moving the phone's date or time
 * neither ends a session nor finishes a wait. Across a restart only the system clock is left; the offset
 * between the two, saved whenever it moves, carries the correction over, so time while the phone was off
 * still counts and a clock moved before the restart is still undone.
 *
 * With no commitment running, [relax] puts it back on the system clock, so a corrected clock is followed.
 * Before [init] (pure unit tests) it is just the system clock.
 *
 * ponytail: a clock moved after a restart but before aurora's process starts again isn't seen. The Shield
 * locks Settings as soon as Android reconnects it, so there is little time to try.
 */
object TrustedClock {
    private var prefs: SharedPreferences? = null
    private var anchorWall = 0L
    private var anchorElapsed = 0L
    private var offset = 0L

    @Synchronized
    fun init(context: Context) {
        if (prefs != null) return
        val p = context.getSharedPreferences("trusted_clock", Context.MODE_PRIVATE)
        prefs = p
        val boot = Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1)
        offset = p.getLong("offset", 0L)
        if (boot >= 0 && p.getInt("boot", -2) == boot) {
            anchorWall = p.getLong("wall", 0L)
            anchorElapsed = p.getLong("elapsed", 0L)
        } else {
            anchorWall = System.currentTimeMillis() + offset
            anchorElapsed = SystemClock.elapsedRealtime()
            p.edit { putInt("boot", boot).putLong("wall", anchorWall).putLong("elapsed", anchorElapsed) }
        }
    }

    @Synchronized
    fun wall(): Long {
        val p = prefs ?: return System.currentTimeMillis()
        val trusted = anchorWall + (SystemClock.elapsedRealtime() - anchorElapsed)
        val now = trusted - System.currentTimeMillis()
        if (abs(now - offset) > 1_000L) {
            offset = now
            p.edit { putLong("offset", offset) }
        }
        return trusted
    }

    /** No strict commitment is running: follow the system clock again. */
    @Synchronized
    fun relax() {
        val p = prefs ?: return
        val wall = System.currentTimeMillis()
        if (abs(anchorWall + (SystemClock.elapsedRealtime() - anchorElapsed) - wall) <= 1_000L && offset == 0L) return
        anchorWall = wall
        anchorElapsed = SystemClock.elapsedRealtime()
        offset = 0L
        p.edit { putLong("wall", anchorWall).putLong("elapsed", anchorElapsed).putLong("offset", 0L) }
    }
}

/**
 * Wakes aurora after a restart, so running commitments, the notification and the Shield's state come back
 * at once, and saves the clock offset the moment the date or time is changed ([TrustedClock]).
 */
class StrictReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in ACTIONS) return
        // Application.onCreate has already initialised the clock and resumed protection.
        TrustedClock.wall()
    }

    private companion object {
        val ACTIONS = setOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_TIME_CHANGED, Intent.ACTION_MY_PACKAGE_REPLACED)
    }
}
