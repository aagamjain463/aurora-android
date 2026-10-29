package app.aurora.protect

import app.aurora.plan.Allowance
import app.aurora.plan.Plan
import app.aurora.plan.limit
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/*
 * The four-pillar product model (docs/CONFIGURATIONS.md). Pure Kotlin, unit tested (ConfigurationsTest).
 *
 * - A Shield preset says which apps and sites are restricted, and how (one of the four modes).
 * - An Intent preset says which apps ask for a deliberate opening, and how.
 * - A usage configuration combines at most one of each, plus optional Focus. Saving any of these never
 *   protects anything.
 * - An activation says when a configuration applies: later, on a recurring schedule, or always. Starting
 *   now creates a run directly.
 * - A run ([ConfigRun]) is one occurrence that is happening: it holds frozen copies of the presets, so
 *   editing a preset never changes protection that is already running.
 */

@Serializable
data class ShieldPreset(
    val id: String,
    val name: String,
    /** Package names and "web:<domain>" keys. */
    val keys: List<String> = emptyList(),
    val mode: Mode = Mode.Flexible,
    val friction: FrictionSettings = FrictionSettings(),
    val cooldownWaitMinutes: Int = 1,
    val createdAt: Long = 0,
)

@Serializable
data class IntentPreset(
    val id: String,
    val name: String,
    val keys: List<String> = emptyList(),
    val config: IntentConfig = IntentConfig(),
    val createdAt: Long = 0,
)

/** Focus tracked inside a configuration. [minutes] null: the configuration's own time window is the target. */
@Serializable
data class FocusPlan(val task: String, val description: String = "", val goal: String = "", val minutes: Int? = null)

/**
 * A complete setup made on Home. A custom configuration ([custom]) references saved presets by id and can
 * be linked to a Habit. Normal Use carries its own copies of the built-in presets ([shield], [intent]),
 * set for that use without changing the built-in defaults.
 */
@Serializable
data class UsageConfig(
    val id: String,
    val name: String,
    val custom: Boolean = true,
    val shieldId: String? = null,
    val intentId: String? = null,
    val shield: ShieldPreset? = null,
    val intent: IntentPreset? = null,
    val focus: FocusPlan? = null,
    val createdAt: Long = 0,
)

/** When an activation applies. [Now] is only an instruction: it becomes a run at once and isn't stored. */
@Serializable
sealed interface Timing {
    @Serializable @SerialName("now")
    data class Now(val minutes: Int) : Timing

    /** One window from [start] (wall clock, an absolute instant: a time-zone change doesn't move it). */
    @Serializable @SerialName("later")
    data class Later(val start: Long, val minutes: Int) : Timing

    /**
     * Weekly windows in local time wherever the phone is ([days] are ISO days, 1 = Monday). A window
     * belongs to the day it starts, so overnight windows work. [until] is the last start date, inclusive.
     */
    @Serializable @SerialName("recurring")
    data class Recurring(val days: Set<Int>, val startMinute: Int, val minutes: Int, val until: String? = null) : Timing

    @Serializable @SerialName("always")
    data object Always : Timing
}

@Serializable
data class Activation(
    val id: String,
    val configId: String,
    val timing: Timing,
    val enabled: Boolean = true,
    val createdAt: Long = 0,
)

@Serializable
enum class FocusOutcome { Completed, Partial, NotCompleted }

/**
 * One occurrence of a configuration that is running or about to. [start]/[end] are wall clock; a run
 * started now also has [time], measured on the monotonic clock, so changing the clock can't end or extend
 * it. [end] null: runs until turned off (Always).
 */
@Serializable
data class ConfigRun(
    val id: String,
    val configId: String,
    val name: String,
    val activationId: String? = null,
    val custom: Boolean = true,
    val shield: ShieldPreset? = null,
    val intent: IntentPreset? = null,
    val focus: FocusPlan? = null,
    val start: Long,
    val end: Long? = null,
    val time: Session? = null,
    /** Free plan: Always Block is committed until here (the day's allowance); after it the Shield is Flexible. */
    val committedUntil: Long? = null,
    val focusOutcome: FocusOutcome? = null,
    /** Times the person continued past their chosen time at an Intent reminder. */
    val intentOverruns: Int = 0,
) {
    fun startAt(t: DeviceTime): Long = time?.startedWall(t) ?: start
    fun endAt(t: DeviceTime): Long? = time?.endWall(t) ?: end
    fun isOver(t: DeviceTime): Boolean = time?.isOver(t) ?: (end != null && t.wall >= end)
    fun covers(t: DeviceTime): Boolean = t.wall >= startAt(t) && !isOver(t)

    /** Always Block can't be turned off in Aurora until its committed part is over. */
    fun cancellable(t: DeviceTime): Boolean = shield?.mode != Mode.AlwaysBlock || committedUntil?.let { t.wall >= it } == true

    fun restrictions(t: DeviceTime): List<Restriction> {
        val s = shield ?: return emptyList()
        val from = startAt(t)
        val to = endAt(t) ?: Long.MAX_VALUE
        val base = Restriction(
            s.mode.reason, s.keys.toSet(), from, to, id,
            friction = s.friction, cooldownWaitMs = s.cooldownWaitMinutes * 60_000L, sessionId = id, label = name,
        )
        val c = committedUntil
        return if (s.mode == Mode.AlwaysBlock && c != null && c < to) {
            listOf(base.copy(end = maxOf(from, c)), base.copy(reason = ShieldReason.Flexible, start = maxOf(from, c), id = "$id:flex"))
        } else listOf(base)
    }
}

/** The run id of the occurrence of [a] that starts at [start]. */
fun occurrenceId(a: Activation, start: Long): String = when (a.timing) {
    is Timing.Later -> "later:${a.id}"
    // Re-enabling Always starts a new occurrence (createdAt moves), so a turned-off one never returns.
    is Timing.Always -> "always:${a.id}:${a.createdAt}"
    else -> "rec:${a.id}:$start"
}

private fun Timing.Recurring.asSchedule(id: String) =
    Schedule(id, "", days, startMinute, (startMinute + minutes.coerceIn(1, 1440)) % 1440, keys = listOf(id))

/** The window of [this] containing [now], or the next one to start, as (start, end). End MAX for Always. */
fun Activation.windows(now: Long, zone: ZoneId): List<Pair<Long, Long>> = when (val t = timing) {
    is Timing.Now -> emptyList()
    is Timing.Later -> listOf(t.start to t.start + t.minutes * 60_000L).filter { it.second > now }
    // An Always activation applies from when it was made (never before: 0 would mean 1970).
    is Timing.Always -> listOf((if (createdAt > 0) createdAt else now) to Long.MAX_VALUE)
    is Timing.Recurring -> {
        val s = t.asSchedule(id)
        val last = t.until?.let { LocalDate.parse(it) }
        listOfNotNull(s.windowAt(now, zone), s.nextStart(now, zone)?.let { s.windowAt(it, zone) })
            .filter { (start, _) -> start >= createdAt && (last == null || !Instant.ofEpochMilli(start).atZone(zone).toLocalDate().isAfter(last)) }
    }
}

/** Windows of [this] that started at or after [from] and ended by [to]: occurrences that are over. */
fun Activation.endedWindows(from: Long, to: Long, zone: ZoneId): List<Pair<Long, Long>> = when (val t = timing) {
    is Timing.Later -> listOf(t.start to t.start + t.minutes * 60_000L)
    is Timing.Recurring -> {
        val last = t.until?.let { LocalDate.parse(it) }
        t.asSchedule(id).windowsBetween(from, to, zone).filter { (start, _) ->
            start >= createdAt && (last == null || !Instant.ofEpochMilli(start).atZone(zone).toLocalDate().isAfter(last))
        }
    }
    else -> emptyList()
}.filter { (start, end) -> start >= from && end <= to }

/** The next start after [now], within a week, or null. */
fun Activation.nextStart(now: Long, zone: ZoneId): Long? = windows(now, zone).map { it.first }.firstOrNull { it > now }

/**
 * The activations [plan] enforces. On Free, the oldest that fit come first: one recurring schedule, five
 * protected items and three Intent apps across them, counting an app once however many reference it.
 * The rest stay saved and paused: a downgrade never deletes anything.
 */
fun Rules.entitledActivations(plan: Plan): Set<String> {
    val enabled = activations.filter { it.enabled }.sortedBy { it.createdAt }
    if (plan == Plan.Plus) return enabled.map { it.id }.toSet()
    val items = mutableSetOf<String>()
    val intentApps = mutableSetOf<String>()
    var recurring = 0
    val kept = mutableSetOf<String>()
    for (a in enabled) {
        val c = configs.find { it.id == a.configId } ?: continue
        val shieldKeys = shieldOf(c)?.keys.orEmpty()
        val intentKeys = intentOf(c)?.keys.orEmpty()
        val r = if (a.timing is Timing.Recurring) 1 else 0
        if (recurring + r > limit(plan, Allowance.Schedules)!!) continue
        if ((items + shieldKeys).size > limit(plan, Allowance.ProtectedItems)!!) continue
        if ((intentApps + intentKeys).size > limit(plan, Allowance.IntentApps)!!) continue
        recurring += r
        items += shieldKeys
        intentApps += intentKeys
        kept += a.id
    }
    return kept
}

fun Rules.shieldOf(c: UsageConfig): ShieldPreset? = c.shield ?: c.shieldId?.let { id -> shields.find { it.id == id } }
fun Rules.intentOf(c: UsageConfig): IntentPreset? = c.intent ?: c.intentId?.let { id -> intentPresets.find { it.id == id } }

/** A run for the occurrence of [a] in [window], from the presets as they are now. */
fun Rules.runFor(a: Activation, window: Pair<Long, Long>): ConfigRun? {
    val c = configs.find { it.id == a.configId } ?: return null
    return ConfigRun(
        occurrenceId(a, window.first), c.id, c.name, a.id, c.custom, shieldOf(c), intentOf(c), c.focus,
        start = window.first, end = window.second.takeIf { it != Long.MAX_VALUE },
    )
}

/**
 * Every run that applies now or starts next: the stored runs, plus the occurrences of entitled
 * activations not stored yet (computed from the current presets until [materialize] freezes them).
 * [closed] occurrences were turned off or settled and never come back.
 */
fun liveRuns(rules: Rules, runtime: Runtime, plan: Plan, t: DeviceTime, zone: ZoneId): List<ConfigRun> {
    val stored = runtime.runs.filterNot { it.isOver(t) }
    val known = runtime.runs.map { it.id }.toSet() + runtime.closed.keys
    val entitled = rules.entitledActivations(plan)
    val virtual = rules.activations.filter { it.id in entitled }.flatMap { a ->
        a.windows(t.wall, zone).mapNotNull { w ->
            val id = occurrenceId(a, w.first)
            // A Phase 3 schedule migrated with its id: its committed Always Block window is already a session.
            val legacy = runtime.sessions.any { it.scheduleId == a.id && !it.time.isOver(t) && it.time.startWall >= w.first }
            if (id in known || legacy) null else rules.runFor(a, w)
        }
    }
    return stored + virtual
}

/** The virtual runs that cover [t] now: these are stored, freezing their presets. */
fun runsToMaterialize(rules: Rules, runtime: Runtime, plan: Plan, t: DeviceTime, zone: ZoneId): List<ConfigRun> {
    val stored = runtime.runs.map { it.id }.toSet()
    return liveRuns(rules, runtime, plan, t, zone).filter { it.id !in stored && it.covers(t) }
}

/** For two active Intent presets on one app, the stricter decides: a cooldown, then the longer pause. */
fun stricter(a: IntentConfig, b: IntentConfig): IntentConfig = listOf(a, b).maxWith(
    compareBy<IntentConfig> { it.expiry == IntentExpiry.Cooldown }.thenBy { it.reflectionSeconds }.thenBy { it.cooldownMinutes },
)

/** The Intent settings that apply to each app now, from every run covering now. */
fun intentsNow(runs: List<ConfigRun>, t: DeviceTime): Map<String, IntentConfig> {
    val out = mutableMapOf<String, IntentConfig>()
    for (r in runs) {
        if (!r.covers(t)) continue
        val p = r.intent ?: continue
        for (k in p.keys) out[k] = out[k]?.let { stricter(it, p.config) } ?: p.config
    }
    return out
}

/** Why a configuration can't be activated as asked, or null when it can. */
enum class ActivationProblem { Empty, AlwaysWithAlwaysBlock, InThePast, NoDays, TooShort, OverItems, OverIntentApps, OverSchedules, OverAlwaysBlockAllowance }

/**
 * Checks [timing] for configuration [c]. Always scheduling isn't offered for an Always Block Shield: a
 * permanent commitment must never be created by accident. Free limits count what is actually enforced:
 * running runs and entitled activations, plus this one.
 */
fun Rules.problem(c: UsageConfig, timing: Timing, plan: Plan, running: List<ConfigRun>, now: Long): ActivationProblem? {
    val shield = shieldOf(c)
    val intent = intentOf(c)
    if ((shield == null || shield.keys.isEmpty()) && (intent == null || intent.keys.isEmpty()) && c.focus == null) return ActivationProblem.Empty
    if (timing is Timing.Always && shield?.mode == Mode.AlwaysBlock) return ActivationProblem.AlwaysWithAlwaysBlock
    when (timing) {
        is Timing.Now -> if (timing.minutes <= 0) return ActivationProblem.TooShort
        is Timing.Later -> {
            if (timing.minutes <= 0) return ActivationProblem.TooShort
            if (timing.start <= now) return ActivationProblem.InThePast
        }
        is Timing.Recurring -> {
            if (timing.days.isEmpty()) return ActivationProblem.NoDays
            if (timing.minutes <= 0) return ActivationProblem.TooShort
        }
        Timing.Always -> Unit
    }
    if (plan == Plan.Plus) return null
    val entitled = entitledActivations(plan)
    val enforced = activations.filter { it.id in entitled }.mapNotNull { a -> configs.find { it.id == a.configId } }
    val items = (enforced.flatMap { shieldOf(it)?.keys.orEmpty() } + running.flatMap { it.shield?.keys.orEmpty() }).toSet()
    val apps = (enforced.flatMap { intentOf(it)?.keys.orEmpty() } + running.flatMap { it.intent?.keys.orEmpty() }).toSet()
    if ((items + shield?.keys.orEmpty()).size > limit(plan, Allowance.ProtectedItems)!!) return ActivationProblem.OverItems
    if ((apps + intent?.keys.orEmpty()).size > limit(plan, Allowance.IntentApps)!!) return ActivationProblem.OverIntentApps
    if (timing is Timing.Recurring && activations.count { it.id in entitled && it.timing is Timing.Recurring } >= limit(plan, Allowance.Schedules)!!) {
        return ActivationProblem.OverSchedules
    }
    return null
}

/**
 * Apps and sites that two parts of a configuration, or this configuration and one already running,
 * both cover. Both apply; the stronger one decides (Policy.kt), so this is shown, not refused.
 */
fun Rules.overlaps(c: UsageConfig, running: List<ConfigRun>): Set<String> {
    val mine = shieldOf(c)?.keys.orEmpty().toSet() + intentOf(c)?.keys.orEmpty()
    val inside = shieldOf(c)?.keys.orEmpty().toSet() intersect intentOf(c)?.keys.orEmpty().toSet()
    val others = running.flatMap { it.shield?.keys.orEmpty() + it.intent?.keys.orEmpty() }.toSet()
    return inside + (mine intersect others)
}

/**
 * Built-in presets for Normal Use. Templates only: choosing one copies it into the configuration, so the
 * defaults never change. [suggested] packages are preselected when installed.
 */
data class BuiltIn(val id: String, val name: String, val suggested: List<String>)

val BuiltInShields = listOf(
    BuiltIn("builtin:social", "Social Media", listOf("com.instagram.android", "com.zhiliaoapp.musically", "com.ss.android.ugc.trill", "com.facebook.katana", "com.snapchat.android", "com.twitter.android", "com.reddit.frontpage", "com.pinterest")),
    BuiltIn("builtin:entertainment", "Entertainment", listOf("com.google.android.youtube", "com.netflix.mediaclient", "com.amazon.avod.thirdpartyclient", "com.disney.disneyplus", "tv.twitch.android.app", "com.spotify.music")),
    BuiltIn("builtin:gaming", "Gaming", listOf("com.supercell.clashofclans", "com.supercell.clashroyale", "com.king.candycrushsaga", "com.roblox.client", "com.mojang.minecraftpe", "com.activision.callofduty.shooter")),
    BuiltIn("builtin:news", "News & Browsing", listOf("com.google.android.apps.magazines", "flipboard.app", "com.android.chrome")),
)

val BuiltInIntents = listOf(
    BuiltIn("builtin:mindful-social", "Mindful Social Media", BuiltInShields[0].suggested),
    BuiltIn("builtin:intentional-video", "Intentional Video", listOf("com.google.android.youtube", "com.netflix.mediaclient", "tv.twitch.android.app")),
)
