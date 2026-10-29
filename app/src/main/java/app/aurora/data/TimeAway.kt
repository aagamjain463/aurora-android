package app.aurora.data

/*
 * Time away from protected apps: Today's main number (docs/PRODUCT.md "Time away"). Pure, unit tested
 * (TimeAwayTest).
 *
 * Definition. Take every span today when protection covered some apps (sessions, schedule windows,
 * Focus), clipped to [from, now). Their union is the protected time; overlapping protection is counted
 * once. Inside each span, subtract the moments one of *that span's* apps was in front, from Android's
 * usage records, including use during an access grant or an intentional session. What remains is time
 * away. Screen-off time counts as away: the protected apps weren't being used. It is not "time saved"
 * or "productive time", and the screen never calls it that.
 */

/** A protection span over [keys]. */
data class ProtectedSpan(val start: Long, val end: Long, val keys: Set<String>)

data class TimeAway(
    /** Protected time today, overlaps counted once. */
    val protectedMs: Long,
    /** Of that, time a covered app was in front. */
    val usedMs: Long,
) {
    val awayMs: Long get() = (protectedMs - usedMs).coerceAtLeast(0)
}

/** Total length of the union of [spans]. */
fun unionLength(spans: List<Pair<Long, Long>>): Long {
    var total = 0L
    var curStart = Long.MIN_VALUE
    var curEnd = Long.MIN_VALUE
    for ((s, e) in spans.filter { it.second > it.first }.sortedBy { it.first }) {
        if (s > curEnd) {
            if (curEnd > curStart) total += curEnd - curStart
            curStart = s
            curEnd = e
        } else if (e > curEnd) {
            curEnd = e
        }
    }
    if (curEnd > curStart) total += curEnd - curStart
    return total
}

/** Time away within [from, to) from [protection] spans and [usage] (null when usage can't be read). */
fun timeAway(protection: List<ProtectedSpan>, usage: List<UsageInterval>, from: Long, to: Long): TimeAway {
    val clipped = protection.mapNotNull { p ->
        val s = maxOf(p.start, from)
        val e = minOf(p.end, to)
        if (e > s && p.keys.isNotEmpty()) p.copy(start = s, end = e) else null
    }
    val used = clipped.flatMap { p ->
        usage.filter { it.packageName in p.keys }.mapNotNull { u ->
            val s = maxOf(u.start, p.start)
            val e = minOf(u.end, p.end)
            if (e > s) s to e else null
        }
    }
    return TimeAway(unionLength(clipped.map { it.start to it.end }), unionLength(used))
}
