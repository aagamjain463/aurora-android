package app.aurora.data

/** "2h 14m", "14m", "<1m". Calm and glanceable; never seconds. */
fun formatDuration(ms: Long): String {
    val minutes = ms / 60_000
    val h = minutes / 60
    val m = minutes % 60
    return when {
        minutes < 1 -> "<1m"
        h == 0L -> "${m}m"
        m == 0L -> "${h}h"
        else -> "${h}h ${m}m"
    }
}

/** Countdown clock, rounded up so a fresh 25-minute session reads 25:00 and the last second 00:01. */
fun formatClock(ms: Long): String {
    val total = (ms.coerceAtLeast(0) + 999) / 1000
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
}

/** Screen-reader phrasing for a remaining duration, at minute precision. */
fun spokenDuration(ms: Long): String {
    val minutes = (ms + 59_999) / 60_000
    val h = minutes / 60
    val m = minutes % 60
    val parts = buildList {
        if (h > 0) add(if (h == 1L) "1 hour" else "$h hours")
        if (m > 0 || h == 0L) add(if (m == 1L) "1 minute" else "$m minutes")
    }
    return parts.joinToString(" ")
}
