package app.aurora.protect

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import app.aurora.plan.Allowance
import app.aurora.plan.Plan
import app.aurora.plan.ProtectedItem
import app.aurora.plan.canProtect
import app.aurora.plan.keepActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import java.io.IOException

/**
 * The person's protected items, oldest first: the one list that Protect, Today, Insights and the
 * Shield service all read. [skipped] counts stored entries that couldn't be read. When [readable] is
 * false the store itself couldn't be read, and [items] is empty because it's unknown, not because
 * nothing is protected.
 */
data class ProtectedList(
    val items: List<ProtectedItem> = emptyList(),
    val skipped: Int = 0,
    val readable: Boolean = true,
) {
    val apps: List<String> get() = items.mapNotNull { (it as? ProtectedItem.App)?.packageName }

    /** The items the plan lets aurora enforce. The rest are paused, never deleted (BUSINESS_MODEL.md 5.4). */
    fun active(plan: Plan): List<ProtectedItem> = keepActive(plan, Allowance.ProtectedItems, items)

    fun paused(plan: Plan): List<ProtectedItem> = active(plan).toSet().let { kept -> items.filterNot { it in kept } }
}

/** The decision snapshot for this list. A list that couldn't be read gives null: [Decision.Unavailable]. */
fun ProtectedList.toPolicyState(plan: Plan, exempt: Set<String>, grants: Map<String, Long> = emptyMap()): PolicyState? =
    if (!readable) null
    else PolicyState(active(plan).mapNotNull { (it as? ProtectedItem.App)?.packageName }.toSet(), exempt, grants)

enum class AddResult { Added, AlreadyProtected, AtLimit }

/*
 * Stored as one string, one item per line ("app:<package>" or "web:<domain>"), in the order they were
 * added. Order matters: after a downgrade the oldest items stay active. Unknown prefixes from a newer
 * version are skipped, not fatal, so an older aurora still enforces the items it understands.
 */
fun encodeItems(items: List<ProtectedItem>): String = items.joinToString("\n") {
    when (it) {
        is ProtectedItem.App -> "app:${it.packageName}"
        is ProtectedItem.Website -> "web:${it.domain}"
    }
}

fun decodeItems(raw: String): ProtectedList {
    var skipped = 0
    val items = raw.lineSequence().filter(String::isNotBlank).mapNotNull { line ->
        val item = when {
            line.startsWith("app:") -> ProtectedItem.App.of(line.removePrefix("app:"))
            line.startsWith("web:") -> ProtectedItem.Website.of(line.removePrefix("web:"))
            else -> null
        }
        if (item == null) skipped++
        item
    }.toList()
    return ProtectedList(items.distinct(), skipped)
}

/**
 * Phase 1 and 2 saved chosen apps as an unordered set under "protected_apps". It's read until the first
 * change, which writes the new list and removes the old key in the same transaction. A set has no order,
 * so migrated apps are ordered by package name: deterministic, so the same apps stay active every time.
 */
private fun Preferences.protectedList(): ProtectedList = this[Items]?.let(::decodeItems)
    ?: this[Legacy].orEmpty().sorted().let { legacy ->
        val apps = legacy.mapNotNull(ProtectedItem.App::of)
        ProtectedList(apps, skipped = legacy.size - apps.size)
    }

private val Items = stringPreferencesKey("protected_items")
private val Legacy = stringSetPreferencesKey("protected_apps")

/** Reads and changes the protected items. Every change is one DataStore transaction, so rapid taps can't race. */
class ProtectionRepository(private val store: DataStore<Preferences>) {

    val list: Flow<ProtectedList> = store.data
        .map { it.protectedList() }
        // Unreadable storage is reported, not treated as "nothing protected" (Decision.Unavailable).
        .catch { if (it is IOException) emit(ProtectedList(readable = false)) else throw it }
        .distinctUntilChanged()

    /** Protects [item] if [plan] allows one more. The check runs inside the transaction. */
    suspend fun add(item: ProtectedItem, plan: Plan): AddResult {
        var result = AddResult.Added
        store.edit { prefs ->
            val items = prefs.protectedList().items
            result = when {
                item in items -> AddResult.AlreadyProtected
                !canProtect(plan, items, item) -> AddResult.AtLimit
                else -> AddResult.Added.also { prefs.write(items + item) }
            }
        }
        return result
    }

    suspend fun remove(items: Collection<ProtectedItem>) {
        store.edit { prefs ->
            val current = prefs.protectedList().items
            if (current.any { it in items }) prefs.write(current - items.toSet())
        }
    }

    private fun MutablePreferences.write(items: List<ProtectedItem>) {
        this[Items] = encodeItems(items)
        remove(Legacy)
    }
}
