package app.aurora.spike

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.io.File
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/*
 * G1 device harness: on-device half. tools/g1/g1.sh drives the phone and reads the rows back.
 * Rows go to external files (Android/data/app.aurora/files/g1.csv), which adb can read without run-as,
 * so the non-debuggable g1 build type works too. Only package names and clock values are written:
 * never window content. All times are CLOCK_MONOTONIC (SystemClock.uptimeMillis / System.nanoTime),
 * except the service rows' elapsedRealtime, which matches /proc/uptime for the restart and reboot tests.
 *
 *   svc,<connected|unbind|destroyed>,<elapsedRealtimeMs>,<uptimeMs>
 *   trial,<id>,<pkg>,<eventUptimeMs>,<receivedUptimeMs>,<startUptimeMs>,<frameEndUptimeMs>,<ok|timeout>
 */
object G1 {
    /** A Shield frame later than this counts as a failure, not a slow success. */
    private const val TimeoutMs = 2_000L
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val pending = ConcurrentHashMap<Long, String>()

    fun service(context: Context, event: String) =
        write(context, "svc,$event,${SystemClock.elapsedRealtime()},${SystemClock.uptimeMillis()}")

    /** Opens a trial for a fresh open of [pkg]. Every trial ends as exactly one row: ok or timeout. */
    fun start(context: Context, pkg: String, eventUptime: Long, receivedUptime: Long): Long {
        val id = System.nanoTime()
        pending[id] = "$id,$pkg,$eventUptime,$receivedUptime,${SystemClock.uptimeMillis()}"
        val app = context.applicationContext
        main.postDelayed({ pending.remove(id)?.let { write(app, "trial,$it,,timeout") } }, TimeoutMs)
        return id
    }

    /** The first Shield frame for trial [id] finished at [frameEndNanos] (System.nanoTime base). */
    fun shown(context: Context, id: Long, frameEndNanos: Long) {
        val row = pending.remove(id) ?: return
        write(context, "trial,$row,${String.format(Locale.ROOT, "%.2f", frameEndNanos / 1e6)},ok")
    }

    private fun write(context: Context, row: String) {
        val dir = context.getExternalFilesDir(null) ?: return
        io.execute { File(dir, "g1.csv").appendText(row + "\n") }
    }
}
