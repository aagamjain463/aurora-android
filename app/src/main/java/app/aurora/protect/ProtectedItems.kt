package app.aurora.protect

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import app.aurora.plan.Allowance
import app.aurora.plan.Plan
import app.aurora.plan.ProtectedItem
import app.aurora.plan.canAdd
import app.aurora.plan.limit
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
 *
 * [held] are items the person's plan paused and that stay paused until the person resumes them. They
 * stay in [items], in place, and are never deleted.
 */
data class ProtectedList(
    val items: List<ProtectedItem> = emptyList(),
    val skipped: Int = 0,
    val readable: Boolean = true,
    val held: Set<ProtectedItem> = emptySet(),
) {
    val apps: List<String> get() = items.mapNotNull { (it as? ProtectedItem.App)?.packageName }

    /**
     * The items aurora enforces under [plan]: the oldest items that aren't held, up to the allowance
     * (BUSINESS_MODEL.md 5.4). aurora plus enforces everything, so renewing restores every item.
     */
    fun active(plan: Plan): List<ProtectedItem> =
        if (limit(plan, Allowance.ProtectedItems) == null) items
        else keepActive(plan, Allowance.ProtectedItems, items.filterNot { it in held })

    /** Saved but not enforced: held, or past the allowance. */
    fun paused(plan: Plan): List<ProtectedItem> = active(plan).toSet().let { kept -> items.filterNot { it in kept } }
}

/** The decision snapshot for this list. A list that couldn't be read gives null: [Decision.Unavailable]. */
fun ProtectedList.toPolicyState(plan: Plan, exempt: Set<String>, grants: Map<String, Long> = emptyMap()): PolicyState? =
    if (!readable) null
    else PolicyState(active(plan).mapNotNull { (it as? ProtectedItem.App)?.packageName }.toSet(), exempt, grants)

enum class AddResult { Added, AlreadyProtected, AtLimit }

/*
 * Stored as one string, one item per line ("app:<package>" or "web:<domain>"), in the order they were
 * added, with "paused:" in front of a held item. Order matters: under a limit the oldest items stay
 * active. Unknown prefixes from a newer version are skipped, not fatal, so an older aurora still
 * enforces the items it understands.
 */
fun encodeItems(items: List<ProtectedItem>, held: Set<ProtectedItem> = emptySet()): String = items.joinToString("\n") {
    val line = when (it) {
        is ProtectedItem.App -> "app:${it.packageName}"
        is ProtectedItem.Website -> "web:${it.domain}"
    }
    if (it in held) "$Held$line" else line
}

fun decodeItems(raw: String): ProtectedList {
    var skipped = 0
    val held = mutableSetOf<ProtectedItem>()
    val items = raw.lineSequence().filter(String::isNotBlank).mapNotNull { stored ->
        val line = stored.removePrefix(Held)
        val item = when {
            line.startsWith("app:") -> ProtectedItem.App.of(line.removePrefix("app:"))
            line.startsWith("web:") -> ProtectedItem.Website.of(line.removePrefix("web:"))
            else -> null
        }
        if (item == null) skipped++ else if (line != stored) held += item
        item
    }.toList().distinct()
    return ProtectedList(items, skipped, held = held.filterTo(mutableSetOf()) { it in items })
}

private const val Held = "paused:"

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

    /**
     * Protects [item] if [plan] allows one more *active* item. Paused items don't take a slot, because
     * nothing enforces them. The check runs inside the transaction.
     */
    suspend fun add(item: ProtectedItem, plan: Plan): AddResult = change { list ->
        when {
            item in list.items -> AddResult.AlreadyProtected to null
            !canAdd(plan, Allowance.ProtectedItems, list.active(plan).size) -> AddResult.AtLimit to null
            else -> AddResult.Added to list.copy(items = list.items + item)
        }
    }

    /** Makes a paused [item] active again, if [plan] has a free slot. The person chooses which one. */
    suspend fun resume(item: ProtectedItem, plan: Plan): AddResult = change { list ->
        when {
            item !in list.paused(plan) -> AddResult.AlreadyProtected to null
            !canAdd(plan, Allowance.ProtectedItems, list.active(plan).size) -> AddResult.AtLimit to null
            // Everything else still paused stays held, so resuming this one can't wake another.
            else -> AddResult.Added to list.copy(held = list.held + list.paused(plan) - item)
        }
    }

    /**
     * Removes [items]. Items paused under [plan] are held first, so a freed slot stays free until the
     * person resumes the paused item they choose. Nothing becomes active on its own.
     */
    suspend fun remove(items: Collection<ProtectedItem>, plan: Plan) {
        change { list ->
            Unit to list.takeIf { current -> current.items.any { it in items } }?.let {
                // Existing holds are kept too: on aurora plus nothing is paused, but holds from Free stay.
                it.copy(items = it.items - items.toSet(), held = it.held + it.paused(plan) - items.toSet())
            }
        }
    }

    /** One transaction. [block] returns a result and the new list, or null to write nothing. */
    private suspend fun <T> change(block: (ProtectedList) -> Pair<T, ProtectedList?>): T {
        var result: T? = null
        store.edit { prefs ->
            val (value, next) = block(prefs.protectedList())
            result = value
            if (next != null) {
                prefs[Items] = encodeItems(next.items, next.held)
                prefs.remove(Legacy)
            }
        }
        @Suppress("UNCHECKED_CAST")
        return result as T
    }
}
