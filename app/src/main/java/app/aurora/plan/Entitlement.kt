package app.aurora.plan

import app.aurora.data.AuroraJson
import kotlinx.serialization.Serializable
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/*
 * Turning what Google Play and the verification server report into a plan (docs/BILLING.md). Unit tested
 * (EntitlementTest). Plus comes only from a snapshot the server signed after asking Google: a purchase
 * token held by this phone proves nothing, and no stored flag can grant it.
 */

/** The server's answer as received: [payload] is the exact JSON that [signature] covers. Stored for offline use. */
@Serializable
data class SignedEntitlement(val payload: String = "", val signature: String = "")

/** What the server verified. [paidThrough] is Google's expiry time. */
@Serializable
data class EntitlementSnapshot(
    val tier: String = "free",
    val state: String = "",
    val productId: String? = null,
    val paidThrough: Long = 0,
    val autoRenewing: Boolean = false,
    val issuedAt: Long = 0,
    val installId: String = "",
    val packageName: String = "",
)

/** How long a verified snapshot is trusted without reaching the server again. */
const val OfflineGraceMs = 7L * 24 * 60 * 60 * 1000

/** A snapshot newer than now by more than this means the clock was set back. */
private const val ClockSkewMs = 5 * 60_000L

/** The snapshot in [signed] if [publicKey] (Base64 X.509 RSA) signed it, otherwise null. */
fun verifiedSnapshot(signed: SignedEntitlement, publicKey: String): EntitlementSnapshot? = runCatching {
    if (publicKey.isBlank() || signed.payload.isBlank()) return null
    val key = KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(publicKey)))
    val check = Signature.getInstance("SHA256withRSA").apply { initVerify(key); update(signed.payload.toByteArray()) }
    if (!check.verify(Base64.getDecoder().decode(signed.signature))) return null
    AuroraJson.decodeFromString(EntitlementSnapshot.serializer(), signed.payload)
}.getOrNull()

/**
 * The plan aurora enforces now. Plus needs a signed snapshot for this install and this app that is at most
 * [OfflineGraceMs] old. A subscription that won't renew ends at its paid-through date. One that renews may
 * pass that date while offline, within the same seven days, because the renewal can't be seen yet.
 */
fun planFrom(snapshot: EntitlementSnapshot?, installId: String, packageName: String, now: Long): Plan = when {
    snapshot == null || snapshot.tier != "plus" -> Plan.Free
    snapshot.installId != installId || snapshot.packageName != packageName -> Plan.Free
    now < snapshot.issuedAt - ClockSkewMs || now - snapshot.issuedAt > OfflineGraceMs -> Plan.Free
    !snapshot.autoRenewing && now >= snapshot.paidThrough -> Plan.Free
    else -> Plan.Plus
}

/** A purchase as Play reports it, without Android types. */
data class PurchaseFacts(
    val products: List<String>,
    val purchased: Boolean,
    val pending: Boolean,
    val acknowledged: Boolean,
    val autoRenewing: Boolean,
    val token: String,
)

/** The purchases to verify with the server. Pending purchases are never verified and never grant Plus. */
data class Verdict(val purchased: List<PurchaseFacts>, val pending: Boolean)

fun evaluate(purchases: List<PurchaseFacts>, plusProducts: Set<String>): Verdict {
    val ours = purchases.filter { p -> p.products.any { it in plusProducts } }
    return Verdict(ours.filter { it.purchased }, ours.any { it.pending })
}
