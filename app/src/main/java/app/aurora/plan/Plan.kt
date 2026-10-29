package app.aurora.plan

import java.net.IDN

/*
 * The entitlement core (docs/BUSINESS_MODEL.md section 5). Pure Kotlin with no Android, so every rule
 * is unit tested. Features ask here when the person adds or enables something; the protection engine
 * never reads the plan, so both plans get the same protection strength.
 */

enum class Plan { Free, Plus }

enum class Allowance { ProtectedItems, IntentApps, DailyLimitRules, Schedules, FocusProfiles, AlwaysBlockMinutesPerDay }

enum class Feature { AdvancedInsights, Challenges }

/** Every limit number lives here and nowhere else. aurora plus has no limits. */
val FreeLimits: Map<Allowance, Int> = mapOf(
    Allowance.ProtectedItems to 5,
    Allowance.IntentApps to 3,
    Allowance.DailyLimitRules to 3,
    Allowance.Schedules to 1,
    Allowance.FocusProfiles to 1,
    Allowance.AlwaysBlockMinutesPerDay to 120,
)

/** null means unlimited. */
fun limit(plan: Plan, allowance: Allowance): Int? = if (plan == Plan.Plus) null else FreeLimits.getValue(allowance)

/** Whether one more can be added when [current] already exist. Removing something never needs a check. */
fun canAdd(plan: Plan, allowance: Allowance, current: Int): Boolean = limit(plan, allowance)?.let { current < it } ?: true

fun has(plan: Plan, feature: Feature): Boolean = when (feature) {
    Feature.AdvancedInsights, Feature.Challenges -> plan == Plan.Plus
}

/**
 * An app or website aurora watches. Apps and websites share one pool, and each item is normalised
 * when it is created, so the same app or domain referenced by several rules is one item.
 */
sealed interface ProtectedItem {

    @ConsistentCopyVisibility
    data class App private constructor(val packageName: String) : ProtectedItem {
        companion object {
            private val PackageName = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")

            /** null for anything that isn't an Android package name. Package names are case-sensitive. */
            fun of(packageName: String): App? = packageName.trim().takeIf(PackageName::matches)?.let(::App)
        }
    }

    @ConsistentCopyVisibility
    data class Website private constructor(val domain: String) : ProtectedItem {
        companion object {
            /**
             * A bare host name such as "Example.com." or "www.example.com", stored as "example.com".
             * Unicode names are stored in their ASCII form. URLs, paths, ports and single labels are rejected.
             */
            fun of(host: String): Website? {
                val name = host.trim().trimEnd('.').lowercase().removePrefix("www.")
                val ascii = try {
                    IDN.toASCII(name, IDN.USE_STD3_ASCII_RULES)
                } catch (_: IllegalArgumentException) {
                    return null
                }
                val valid = ascii.length <= 253 && ascii.split('.').let { labels -> labels.size >= 2 && labels.none(String::isEmpty) }
                return if (valid) Website(ascii) else null
            }
        }
    }
}

/**
 * Whether [candidate] can be protected alongside [current], the items across every rule, repeats
 * included. An item that is already protected never needs a new slot, so replacing one is remove, then add.
 */
fun canProtect(plan: Plan, current: Collection<ProtectedItem>, candidate: ProtectedItem): Boolean {
    val distinct = current.toSet()
    return candidate in distinct || canAdd(plan, Allowance.ProtectedItems, distinct.size)
}

/**
 * After a downgrade, which of [items] (oldest first) stay active under [plan]'s [allowance]
 * (BUSINESS_MODEL.md 5.4). The person's [chosen] items come first, then the oldest. The rest pause:
 * nothing is deleted, and renewing makes them all active again.
 */
fun <T> keepActive(plan: Plan, allowance: Allowance, items: List<T>, chosen: Collection<T> = emptyList()): List<T> {
    val distinct = items.distinct()
    val max = limit(plan, allowance) ?: return distinct
    val kept = (distinct.filter { it in chosen } + distinct.filterNot { it in chosen }).take(max).toSet()
    return distinct.filter { it in kept }
}
