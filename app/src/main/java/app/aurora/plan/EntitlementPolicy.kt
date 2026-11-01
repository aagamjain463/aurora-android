package app.aurora.plan

import java.time.ZoneId

/*
 * The one authority on what a plan allows. Screens may ask it to explain things; the domain operations in
 * Actions.kt and ConfigActions.kt ask it again before anything is stored. Pure, unit tested (PolicyLimitsTest).
 * Focus, Daily Limits, schedules, presets and Flexible and Friction Shields have no commercial limit, so
 * they have no check here: they only meet these limits through the targets and modes they activate.
 */

enum class LimitReason { ShieldTargets, IntentApps, ActiveGoal, CommittedProtection, AdvancedAnalytics }

enum class Remedy { FreeASlot, ChooseShorter, WaitForReset, Upgrade }

/**
 * [used], [limit] and [remaining] count items, or milliseconds for [LimitReason.CommittedProtection].
 * [resetAt] is when a daily allowance starts again. A null [limit] means no limit.
 */
data class LimitDecision(
    val allowed: Boolean,
    val reason: LimitReason? = null,
    val used: Long = 0,
    val limit: Long? = null,
    val remaining: Long? = null,
    val remedy: Remedy? = null,
    val upgradeResolves: Boolean = false,
    val resetAt: Long? = null,
)

object EntitlementPolicy {
    private val Allowed = LimitDecision(true)

    /**
     * Whether [requested] can join [active], which is every target active once this change is made.
     * Reusing an active target needs no slot; replacing one is checked on the union after the change.
     */
    private fun targets(plan: Plan, allowance: Allowance, reason: LimitReason, active: Set<String>, requested: Set<String>): LimitDecision {
        val max = limit(plan, allowance) ?: return Allowed
        val union = active + requested
        if (union.size <= max || (requested - active).isEmpty()) return LimitDecision(true, used = union.size.toLong(), limit = max.toLong(), remaining = (max - union.size).coerceAtLeast(0).toLong())
        return LimitDecision(false, reason, active.size.toLong(), max.toLong(), (max - active.size).coerceAtLeast(0).toLong(), Remedy.FreeASlot, upgradeResolves = true)
    }

    fun canActivateShieldTarget(plan: Plan, active: Set<String>, requested: Set<String>) =
        targets(plan, Allowance.ProtectedItems, LimitReason.ShieldTargets, active, requested)

    fun canActivateIntentApp(plan: Plan, active: Set<String>, requested: Set<String>) =
        targets(plan, Allowance.IntentApps, LimitReason.IntentApps, active, requested)

    /** [active] is how many other Goals are active now. */
    fun canActivateGoal(plan: Plan, active: Int): LimitDecision {
        val max = limit(plan, Allowance.ActiveGoals) ?: return Allowed
        return if (active < max) LimitDecision(true, used = active.toLong(), limit = max.toLong(), remaining = (max - active).toLong())
        else LimitDecision(false, LimitReason.ActiveGoal, active.toLong(), max.toLong(), 0, Remedy.FreeASlot, upgradeResolves = true)
    }

    /** Whether committed protection from [start] to [end] fits today's shared allowance, counting overlaps once. */
    fun canStartCommittedProtection(plan: Plan, ledger: List<LedgerEntry>, start: Long, end: Long, zone: ZoneId): LimitDecision {
        val status = quotaStatus(plan, ledger, start, zone)
        if (committedFits(plan, ledger, start, end, zone)) return LimitDecision(true, used = status.usedMs, limit = status.limitMs, remaining = status.remainingMs, resetAt = status.resetAt)
        val left = status.remainingMs ?: 0
        return LimitDecision(
            false, LimitReason.CommittedProtection, status.usedMs, status.limitMs, left,
            if (left >= 60_000L) Remedy.ChooseShorter else Remedy.WaitForReset, upgradeResolves = true, resetAt = status.resetAt,
        )
    }

    fun canAccessAdvancedAnalytics(plan: Plan): LimitDecision =
        if (plan == Plan.Plus) Allowed else LimitDecision(false, LimitReason.AdvancedAnalytics, remedy = Remedy.Upgrade, upgradeResolves = true)
}
