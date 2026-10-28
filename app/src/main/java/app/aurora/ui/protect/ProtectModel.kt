package app.aurora.ui.protect

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.aurora.AuroraApplication
import app.aurora.data.InstalledApp
import app.aurora.data.clockTicks
import app.aurora.plan.Allowance
import app.aurora.plan.Plan
import app.aurora.plan.ProtectedItem
import app.aurora.plan.canAdd
import app.aurora.plan.limit
import app.aurora.protect
import app.aurora.protect.AddResult
import app.aurora.protect.DeviceTime
import app.aurora.protect.Mode
import app.aurora.protect.ProtectedList
import app.aurora.protect.Rules
import app.aurora.protect.Runtime
import app.aurora.protect.exemptPackages
import app.aurora.protect.key
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

/** Everything the Protect screens show, from the same in-memory state the Shield reads. */
data class ProtectData(
    val list: ProtectedList,
    val rules: Rules,
    /** False when the stored rules can't be read: nothing can be changed until they can. */
    val rulesReadable: Boolean,
    val runtime: Runtime,
    val plan: Plan,
    val apps: Map<String, InstalledApp>,
    val time: DeviceTime,
) {
    val active: List<ProtectedItem> get() = list.active(plan)
    val paused: List<ProtectedItem> get() = list.paused(plan)
    fun max(allowance: Allowance): Int? = limit(plan, allowance)
    fun label(key: String): String = apps[key]?.label ?: key.removePrefix(app.aurora.protect.WebPrefix)
}

/** Why something wasn't added, shown as a calm dialog. Never shown during active protection. */
data class LimitNotice(val title: String, val body: String, val plus: Boolean)

/**
 * Shared by the Protect tab and its detail screens. Each screen has its own instance; they all read the
 * application's in-memory state, so a change on one screen shows everywhere at once.
 */
class ProtectModel(val app: AuroraApplication) : ViewModel() {
    private val installed = MutableStateFlow<List<InstalledApp>?>(null)
    private val _exempt = MutableStateFlow<Set<String>>(emptySet())
    private val _notice = MutableStateFlow<LimitNotice?>(null)

    /** Real launchable apps on this device; null while loading. */
    val apps: StateFlow<List<InstalledApp>?> = installed

    /** Apps Shield never covers on this phone (Settings, the dialer, the launcher...). */
    val exempt: StateFlow<Set<String>> = _exempt

    val notice: StateFlow<LimitNotice?> = _notice

    val data: StateFlow<ProtectData?> = combine(
        combine(app.protectedList, app.rules.state, app.runtime.state) { list, rules, runtime -> Triple(list, rules, runtime) },
        app.billing.plan,
        installed,
        clockTicks(15_000),
    ) { (list, rules, runtime), plan, apps, _ ->
        if (list == null || rules == null || runtime == null) null
        else ProtectData(list, rules.value, rules.readable, runtime.value, plan, apps.orEmpty().associateBy { it.packageName }, app.now())
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    init {
        viewModelScope.launch {
            _exempt.value = withContext(Dispatchers.IO) { exemptPackages(app) }
            installed.value = app.catalog.launchableApps()
        }
    }

    fun dismissNotice() {
        _notice.value = null
    }

    fun showNotice(notice: LimitNotice) {
        _notice.value = notice
    }

    /** Toggles protection for [item]. A paused item is never removed by a tap: the person chooses (see [resume]). */
    fun toggle(item: ProtectedItem, onPaused: () -> Unit = {}) = write {
        val list = app.protection.list.first()
        when (item) {
            in list.paused(app.plan) -> onPaused()
            in list.items -> app.protection.remove(listOf(item), app.plan)
            else -> if (app.protect(item) == AddResult.AtLimit) atLimit(Allowance.ProtectedItems)
        }
    }

    fun add(item: ProtectedItem) = write {
        if (app.protect(item) == AddResult.AtLimit) atLimit(Allowance.ProtectedItems)
    }

    fun remove(item: ProtectedItem) = write { app.protection.remove(listOf(item), app.plan) }

    fun resume(item: ProtectedItem) = write {
        if (app.protection.resume(item, app.plan) == AddResult.AtLimit) atLimit(Allowance.ProtectedItems)
    }

    /** Changes the rules in one transaction. */
    fun edit(transform: (Rules) -> Rules) = write { app.rules.update(transform) }

    fun setMode(key: String, mode: Mode) = edit { r -> r.copy(apps = r.apps + (key to r.rule(key).copy(mode = mode))) }

    /**
     * Enables something counted by [allowance] when the plan allows one more, given [enabledNow]
     * already enabled. Otherwise explains, once, and changes nothing.
     */
    fun enableWithin(allowance: Allowance, enabledNow: Int, transform: (Rules) -> Rules) {
        if (canAdd(app.plan, allowance, enabledNow)) edit(transform) else atLimit(allowance)
    }

    fun atLimit(allowance: Allowance) {
        val max = limit(app.plan, allowance) ?: return
        _notice.value = when (allowance) {
            Allowance.ProtectedItems -> LimitNotice(
                "That's the most for now",
                "You're protecting $max items, the most aurora Free allows. Remove one to add another, or protect everything with aurora plus.",
                plus = true,
            )
            Allowance.IntentApps -> LimitNotice("Intent is on for $max apps", "aurora Free reminds you why on up to $max apps. Turn it off for one, or use it everywhere with aurora plus.", true)
            Allowance.DailyLimitRules -> LimitNotice("$max daily limits are on", "aurora Free includes $max daily limits. Turn one off, or set as many as you like with aurora plus.", true)
            Allowance.Schedules -> LimitNotice("Your schedule is on", "aurora Free includes $max schedule. Turn it off to use another, or add more with aurora plus.", true)
            Allowance.FocusProfiles -> LimitNotice("One Focus profile on Free", "aurora plus adds profiles for each kind of focus.", true)
            Allowance.LockdownMinutesPerDay -> LimitNotice("Not enough Lockdown time left", "aurora Free includes $max Lockdown minutes a day. Choose a shorter Lockdown, or try again tomorrow.", true)
        }
    }

    private fun write(block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                block()
            } catch (_: IOException) {
                // The screens read the stored state back, so they keep showing the truth.
            }
        }
    }

    /**
     * Uninstalled apps can't be shielded, and they'd hold a slot, so they're removed. Only apps Android
     * says don't exist: a disabled or hidden app is kept.
     */
    fun pruneUninstalled() = write {
        val list = app.protection.list.first()
        if (!list.readable) return@write
        val gone = list.items.filterIsInstance<ProtectedItem.App>().filter { app.catalog.app(it.packageName) == null }
        if (gone.isNotEmpty()) app.protection.remove(gone, app.plan)
    }
}

/** A short line describing how an item is protected, for lists. */
fun Rules.summary(key: String, plan: Plan, website: Boolean): String {
    val rule = rule(key)
    val mode = when (rule.mode) {
        Mode.Flexible -> "Flexible"
        Mode.Friction -> "Friction · ${rule.waitSeconds} s pause"
        Mode.Cooldown -> "Cooldown · ${rule.cooldownMinutes} min rest"
        Mode.Block -> "Always blocked"
        Mode.RulesOnly -> "Only during rules"
    }
    val extras = buildList {
        if (key in activeIntents(plan)) add("Intent")
        if (!website) activeLimits(plan).find { it.key == key }?.let { add("${it.minutes} min a day") }
    }
    return (listOf(mode) + extras).joinToString(" · ")
}
