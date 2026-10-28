package app.aurora.plan

import kotlinx.serialization.Serializable

/*
 * Turning what Google Play reports into a plan (docs/PRODUCT.md "Billing"). Pure Kotlin, unit tested
 * (EntitlementTest). Play is the only source of Plus: there is no code path that grants it otherwise.
 */

/** What Play last confirmed, and when (wall clock). Stored so aurora knows the plan offline. */
@Serializable
data class EntitlementCache(
    val plus: Boolean = false,
    val checkedAt: Long = 0L,
    val productId: String? = null,
    val autoRenewing: Boolean = false,
)

/**
 * How long a confirmed Plus is trusted without reaching Play again. Long enough for a week offline,
 * short enough that a cancelled subscription can't last on a phone that never reconnects.
 */
const val OfflineGraceMs = 7L * 24 * 60 * 60 * 1000

/** The plan aurora enforces now. A clock set back before the last check counts as unconfirmed. */
fun planFrom(cache: EntitlementCache, now: Long): Plan =
    if (cache.plus && now - cache.checkedAt in 0..OfflineGraceMs) Plan.Plus else Plan.Free

/** A purchase as Play reports it, without Android types. */
data class PurchaseFacts(
    val products: List<String>,
    val purchased: Boolean,
    val pending: Boolean,
    val acknowledged: Boolean,
    val autoRenewing: Boolean,
    val token: String,
)

/** Plus when any purchased subscription is a Plus product. Pending purchases never grant Plus. */
data class Verdict(val plus: Boolean, val pending: Boolean, val productId: String?, val autoRenewing: Boolean, val toAcknowledge: List<String>)

fun evaluate(purchases: List<PurchaseFacts>, plusProducts: Set<String>): Verdict {
    val ours = purchases.filter { p -> p.products.any { it in plusProducts } }
    val active = ours.filter { it.purchased }
    return Verdict(
        plus = active.isNotEmpty(),
        pending = ours.any { it.pending },
        productId = active.firstOrNull()?.products?.firstOrNull { it in plusProducts },
        autoRenewing = active.any { it.autoRenewing },
        // Play refunds a purchase that isn't acknowledged within three days.
        toAcknowledge = active.filterNot { it.acknowledged }.map { it.token },
    )
}
