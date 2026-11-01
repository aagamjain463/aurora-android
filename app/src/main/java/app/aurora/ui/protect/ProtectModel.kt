package app.aurora.ui.protect

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.aurora.AuroraApplication
import app.aurora.R
import app.aurora.data.InstalledApp
import app.aurora.data.clockTicks
import app.aurora.plan.Allowance
import app.aurora.plan.Plan
import app.aurora.progress.History
import app.aurora.plan.ProtectedItem
import app.aurora.plan.limit
import app.aurora.protect
import app.aurora.protect.AddResult
import app.aurora.protect.DeviceTime
import app.aurora.protect.Mode
import app.aurora.protect.ProtectedList
import app.aurora.protect.Rules
import app.aurora.protect.Runtime
import app.aurora.protect.ProtectionSession
import app.aurora.protect.WebPrefix
import app.aurora.protect.exemptPackages
import app.aurora.protect.key
import app.aurora.unprotect
import app.aurora.editRules
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
    /** Names of protected apps, read directly so a name never waits for the full app list. */
    val labels: Map<String, String> = emptyMap(),
    val history: History = History(),
) {
    val active: List<ProtectedItem> get() = list.active(plan)
    val paused: List<ProtectedItem> get() = list.paused(plan)
    val activeKeys: Set<String> get() = active.map { it.key }.toSet()
    fun max(allowance: Allowance): Int? = limit(plan, allowance)

    /** The app's own name, a site's domain, or "" while the name loads. Never a raw package name. */
    fun label(key: String): String = when {
        key.startsWith(WebPrefix) -> key.removePrefix(WebPrefix)
        else -> apps[key]?.label ?: labels[key].orEmpty()
    }

    /** Sessions still running, soonest end first. */
    val sessions: List<ProtectionSession> get() = runtime.sessions.filterNot { it.time.isOver(time) }.sortedBy { it.time.remaining(time) }
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

    private val labels = MutableStateFlow<Map<String, String>>(emptyMap())

    val data: StateFlow<ProtectData?> = combine(
        combine(app.protectedList, app.rules.state, app.runtime.state) { list, rules, runtime -> Triple(list, rules, runtime) },
        combine(app.billing.plan, labels, app.history.state) { plan, names, history -> Triple(plan, names, history) },
        installed,
        clockTicks(1_000),
    ) { (list, rules, runtime), (plan, names, history), apps, _ ->
        if (list == null || rules == null || runtime == null) null
        else ProtectData(list, rules.value, rules.readable, runtime.value, plan, apps.orEmpty().associateBy { it.packageName }, app.now(), names, history?.value ?: History())
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    init {
        viewModelScope.launch {
            // Names of the apps on screen first (a few lookups), then the full list for pickers.
            combine(app.protectedList, app.rules.state) { list, rules -> list to rules?.value }.collect { (list, rules) ->
                val keys = (list?.items.orEmpty().map { it.key } +
                    app.runtime.value?.sessions.orEmpty().flatMap { it.keys } +
                    app.runtime.value?.runs.orEmpty().flatMap { it.shield?.keys.orEmpty() + it.intent?.keys.orEmpty() } +
                    rules?.shields.orEmpty().flatMap { it.keys } + rules?.intentPresets.orEmpty().flatMap { it.keys } +
                    rules?.configs.orEmpty().flatMap { it.shield?.keys.orEmpty() + it.intent?.keys.orEmpty() })
                    .filterNot { it.startsWith(WebPrefix) }.distinct().filterNot { it in labels.value }
                if (keys.isNotEmpty()) labels.value = labels.value + keys.associateWith { app.catalog.label(it) ?: "" }
            }
        }
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
            in list.items -> app.unprotect(listOf(item))
            else -> if (app.protect(item) == AddResult.AtLimit) atLimit(Allowance.ProtectedItems)
        }
    }

    fun add(item: ProtectedItem) = write {
        if (app.protect(item) == AddResult.AtLimit) atLimit(Allowance.ProtectedItems)
    }

    fun remove(item: ProtectedItem) = write { app.unprotect(listOf(item)) }

    fun resume(item: ProtectedItem) = write {
        if (app.protection.resume(item, app.plan) == AddResult.AtLimit) atLimit(Allowance.ProtectedItems)
    }

    /** Changes the rules in one transaction, if the plan allows the result. A running Always Block window is committed first. */
    fun edit(transform: (Rules) -> Rules) = write { app.editRules(transform) }

    /** Runs an action, handling storage errors like every other write. */
    fun run(block: suspend () -> Unit) = write(block)

    fun atLimit(allowance: Allowance) {
        val max = limit(app.plan, allowance) ?: return
        _notice.value = when (allowance) {
            Allowance.ProtectedItems -> LimitNotice(app.getString(R.string.limit_items_title), app.resources.getQuantityString(R.plurals.limit_items_body, max, max), plus = true)
            Allowance.IntentApps -> LimitNotice(app.resources.getQuantityString(R.plurals.limit_intent_title, max, max), app.resources.getQuantityString(R.plurals.limit_intent_body, max, max), true)
            Allowance.ActiveGoals -> LimitNotice(app.getString(R.string.goal_plan_title), app.getString(R.string.goal_plan_body), true)
            Allowance.CommittedMinutesPerDay -> LimitNotice(app.getString(R.string.limit_always_title), app.resources.getQuantityString(R.plurals.limit_always_body, max, max), true)
        }
    }

    private fun write(block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                block()
            } catch (_: IOException) {
                // The screens read the stored state back, so they keep showing the truth.
                app.saveFailed.value = true
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
        if (gone.isNotEmpty()) app.unprotect(gone)
    }
}

/** A short line describing an item's saved preferences, for lists. */
fun Rules.summary(key: String, plan: Plan, website: Boolean, modeName: (Mode) -> String): String {
    val rule = rule(key)
    val extras = buildList {
        add(modeName(rule.mode))
        if (key in activeIntents(plan)) add("Intentional opening")
        if (!website) activeLimits().find { it.key == key }?.let { add("${app.aurora.ui.components.minutesText(it.minutes)} a day") }
    }
    return extras.joinToString(" · ")
}
