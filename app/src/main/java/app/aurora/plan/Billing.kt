package app.aurora.plan

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.core.net.toUri
import app.aurora.BuildConfig
import app.aurora.R
import app.aurora.data.AuroraJson
import app.aurora.data.JsonValue
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClient.BillingResponseCode
import com.android.billingclient.api.BillingClient.ProductType
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import kotlin.coroutines.resume

/** One way to buy Plus: a base plan of a Plus subscription with its price as Play formats it. */
data class Offer(val basePlan: String, val price: String, val yearly: Boolean, internal val details: ProductDetails, internal val token: String)

data class BillingState(
    val connection: Connection = Connection.Connecting,
    val offers: List<Offer> = emptyList(),
    /** A purchase is waiting for payment (for example cash or UPI collect). It doesn't grant Plus until it completes. */
    val pending: Boolean = false,
    /** Play reports a purchase and the server hasn't confirmed it yet. Plus starts when it does. */
    val verifying: Boolean = false,
    /** This phone's purchase belongs to another Aurora account. Nothing moves until the person chooses to. */
    val conflict: PurchaseConflict? = null,
    val message: String? = null,
) {
    enum class Connection { Connecting, Ready, Unavailable }
}

/** [hint] is the other account's address, masked. [token] goes nowhere except back to the server, if the person moves it. */
data class PurchaseConflict(val hint: String?, internal val token: String)

/** This install's random id, in storage no backup or transfer copies: a snapshot restored from elsewhere doesn't match it. */
fun installId(context: Context): String {
    val file = File(context.noBackupFilesDir, "install_id")
    if (!file.exists()) file.writeText(UUID.randomUUID().toString())
    return file.readText()
}

/**
 * Google Play Billing for aurora plus. Play sells it and reports purchases; the verification server asks
 * Google whether each purchase is real and signs the answer; only a signed answer unlocks Plus. The answer
 * is stored for offline use ([planFrom] says for how long). Everything is checked again at every start,
 * and at most a day apart while the process lives, because the Shield service can keep it alive for weeks.
 */
class BillingRepository(
    private val context: Context,
    private val scope: CoroutineScope,
    private val cache: JsonValue<SignedEntitlement>,
    private val wallClock: () -> Long = System::currentTimeMillis,
    private val publicKey: String = BuildConfig.ENTITLEMENT_PUBLIC_KEY,
    private val verify: suspend (String, String, String, String) -> SignedEntitlement?,
    private val configured: Boolean = BuildConfig.SERVER_URL.isNotBlank() && BuildConfig.ENTITLEMENT_PUBLIC_KEY.isNotBlank(),
    /** Who is signed in. A purchase is only ever verified for, and a snapshot only ever counts for, a signed-in account. */
    private val currentUid: () -> String?,
    /** False until the authentication provider has answered, so a Plus person doesn't look Free for a moment. */
    private val identityResolved: () -> Boolean = { true },
    private val identityChanges: Flow<*> = flowOf(Unit),
) {
    private val plusProducts = BuildConfig.PLUS_PRODUCT_IDS.split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
    private val install by lazy { installId(context) }
    private val _state = MutableStateFlow(BillingState())
    val state: StateFlow<BillingState> = _state
    private var retries: Job? = null

    /** The stored snapshot, if it really is the server's. Checking a signature is slow, so the last answer is kept. */
    private var checked: Pair<SignedEntitlement, EntitlementSnapshot?>? = null
    private fun snapshot(signed: SignedEntitlement?): EntitlementSnapshot? {
        if (signed == null) return null
        checked?.takeIf { it.first == signed }?.let { return it.second }
        return verifiedSnapshot(signed, publicKey).also { checked = signed to it }
    }

    val loaded: Boolean get() = cache.state.value != null && identityResolved()

    suspend fun awaitLoaded() {
        cache.state.first { it != null }
    }

    /** The account's data replaced what was active: [cache] holds the new account's snapshot now. */
    suspend fun reloadCache() = cache.reload()

    /** A snapshot from the account service, if it really is for this install and this account. */
    suspend fun store(signed: SignedEntitlement) {
        val answer = verifiedSnapshot(signed, publicKey) ?: return
        if (answer.installId != install || answer.packageName != context.packageName || answer.uid != currentUid()) return
        runCatching { cache.update { signed } }
    }

    /** The plan at this moment, read fresh: expiry is a matter of the clock, not of anything changing. */
    fun planNow(): Plan = planFrom(snapshot(cache.value), currentUid(), install, context.packageName, wallClock())

    val subscription: StateFlow<EntitlementSnapshot?> = cache.state
        .map { snapshot(it?.value) }
        .stateIn(scope, SharingStarted.Eagerly, null)

    val plan: StateFlow<Plan> = combine(cache.state, identityChanges, flow { while (true) { emit(Unit); delay(60_000L) } }) { _, _, _ -> planNow() }
        .stateIn(scope, SharingStarted.Eagerly, Plan.Free)

    private val listener = PurchasesUpdatedListener { result, purchases ->
        when (result.responseCode) {
            BillingResponseCode.OK -> scope.launch { apply(purchases.orEmpty(), complete = false) }
            BillingResponseCode.USER_CANCELED -> Unit
            BillingResponseCode.ITEM_ALREADY_OWNED -> scope.launch { refresh() }
            else -> _state.value = _state.value.copy(message = describe(result))
        }
    }

    private val client = BillingClient.newBuilder(context.applicationContext)
        .setListener(listener)
        .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().enablePrepaidPlans().build())
        .enableAutoServiceReconnection()
        .build()

    init {
        scope.launch {
            while (true) {
                delay(6 * 60 * 60_000L)
                val age = wallClock() - (subscription.value?.issuedAt ?: 0)
                if (client.isReady && age > 24 * 60 * 60_000L) runCatching { refresh() }
            }
        }
    }

    fun connect() {
        if (client.isReady) {
            scope.launch { refresh() }
            return
        }
        client.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(result: BillingResult) {
                if (result.responseCode == BillingResponseCode.OK) {
                    scope.launch { refresh() }
                } else {
                    _state.value = _state.value.copy(connection = BillingState.Connection.Unavailable, message = describe(result))
                }
            }

            override fun onBillingServiceDisconnected() {
                _state.value = _state.value.copy(connection = BillingState.Connection.Connecting)
            }
        })
    }

    /** Asks Play again for purchases and prices, and the server about any purchase: "Restore purchases" and every app start. */
    suspend fun refresh() {
        // A timeout and a late answer can both call back: only the first counts.
        val (purchasesResult, purchases) = suspendCancellableCoroutine { c ->
            client.queryPurchasesAsync(QueryPurchasesParams.newBuilder().setProductType(ProductType.SUBS).build()) { r, list -> if (c.isActive) c.resume(r to list) }
        }
        val offers = queryOffers()
        _state.value = _state.value.copy(
            connection = if (purchasesResult.responseCode == BillingResponseCode.OK) BillingState.Connection.Ready else BillingState.Connection.Unavailable,
            offers = if (configured) offers else emptyList(),
            message = when {
                purchasesResult.responseCode != BillingResponseCode.OK -> describe(purchasesResult)
                offers.isEmpty() || !configured -> context.getString(R.string.billing_not_for_sale)
                else -> null
            },
        )
        if (purchasesResult.responseCode == BillingResponseCode.OK) apply(purchases, complete = true)
    }

    private suspend fun queryOffers(): List<Offer> {
        val params = QueryProductDetailsParams.newBuilder().setProductList(
            plusProducts.map { QueryProductDetailsParams.Product.newBuilder().setProductId(it).setProductType(ProductType.SUBS).build() },
        ).build()
        val details = suspendCancellableCoroutine { c ->
            client.queryProductDetailsAsync(params) { r, result -> if (c.isActive) c.resume(if (r.responseCode == BillingResponseCode.OK) result.productDetailsList else emptyList()) }
        }
        return details.flatMap { product ->
            product.subscriptionOfferDetails.orEmpty()
                // Base plans only: no introductory offers or urgency tricks.
                .filter { it.offerId == null }
                .mapNotNull { offer ->
                    val phase = offer.pricingPhases.pricingPhaseList.lastOrNull() ?: return@mapNotNull null
                    Offer(offer.basePlanId, phase.formattedPrice, phase.billingPeriod.endsWith("Y"), product, offer.offerToken)
                }
        }.sortedBy { it.yearly }
    }

    private suspend fun apply(purchases: List<Purchase>, complete: Boolean) {
        applyFacts(purchases.map {
            PurchaseFacts(
                it.products, it.purchaseState == Purchase.PurchaseState.PURCHASED, it.purchaseState == Purchase.PurchaseState.PENDING,
                it.isAcknowledged, it.isAutoRenewing, it.purchaseToken,
            )
        }, complete) { token ->
            suspendCancellableCoroutine<Unit> { c ->
                client.acknowledgePurchase(AcknowledgePurchaseParams.newBuilder().setPurchaseToken(token).build()) { if (c.isActive) c.resume(Unit) }
            }
        }
    }

    /** [complete]: this is every purchase Play knows of, so none at all means no subscription. */
    internal suspend fun applyFacts(purchases: List<PurchaseFacts>, complete: Boolean, acknowledge: suspend (String) -> Unit) {
        val verdict = evaluate(purchases, plusProducts)
        _state.value = _state.value.copy(pending = verdict.pending)
        if (verdict.purchased.isEmpty()) {
            // Play's list is only this phone's Play account. The subscription belongs to the Aurora account, which
            // may have bought it elsewhere, so an empty list here takes nothing away: the account service decides.
            _state.value = _state.value.copy(verifying = false, conflict = null)
            return
        }
        if (!configured || currentUid() == null) return
        _state.value = _state.value.copy(verifying = true)
        for (p in verdict.purchased) {
            val product = p.products.first { it in plusProducts }
            val signed = verify(p.token, product, install, context.packageName)
            val answer = signed?.let { verifiedSnapshot(it, publicKey) }?.takeIf { it.installId == install && it.packageName == context.packageName }
            if (signed == null || answer == null || answer.uid != currentUid()) {
                // Unreachable or nonsense: keep what is stored, and try again soon. Nothing is decided here.
                retryLater()
                return
            }
            runCatching { cache.update { signed } }
            _state.value = _state.value.copy(conflict = if (signed.conflict) PurchaseConflict(signed.conflictHint, p.token) else null)
            // Play refunds a purchase that isn't acknowledged within three days, so only one Google confirmed is.
            if ((answer.tier == "plus" || answer.purchaseValid) && !signed.conflict && !p.acknowledged) acknowledge(p.token)
            if (answer.tier == "plus") break
        }
        retries?.cancel()
        _state.value = _state.value.copy(verifying = false)
    }

    private fun retryLater() {
        if (retries?.isActive == true) return
        retries = scope.launch {
            for (wait in listOf(30_000L, 120_000L, 600_000L)) {
                delay(wait)
                runCatching { if (client.isReady) refresh() }
                if (!_state.value.verifying) return@launch
            }
        }
    }

    /** The person chose to move this phone's purchase to this account, and the server agreed. */
    fun clearConflict() {
        _state.value = _state.value.copy(conflict = null)
    }

    /** Settings › Delete all data: the stored answer goes, and Play is asked again. */
    suspend fun forget() {
        cache.reset()
        connect()
    }

    /** Opens Play's purchase sheet. Returns false (with a message) if it couldn't be shown. */
    fun buy(activity: Activity, offer: Offer): Boolean {
        val uid = currentUid() ?: return false
        val params = BillingFlowParams.newBuilder().setProductDetailsParamsList(
            listOf(BillingFlowParams.ProductDetailsParams.newBuilder().setProductDetails(offer.details).setOfferToken(offer.token).build()),
        ).setObfuscatedAccountId(obfuscatedAccountId(uid)).build()
        val result = client.launchBillingFlow(activity, params)
        if (result.responseCode != BillingResponseCode.OK) _state.value = _state.value.copy(message = describe(result))
        return result.responseCode == BillingResponseCode.OK
    }

    fun clearMessage() {
        _state.value = _state.value.copy(message = null)
    }

    private fun describe(result: BillingResult): String = when (result.responseCode) {
        BillingResponseCode.BILLING_UNAVAILABLE -> context.getString(R.string.billing_unavailable)
        BillingResponseCode.SERVICE_UNAVAILABLE, BillingResponseCode.NETWORK_ERROR -> context.getString(R.string.billing_unreachable)
        BillingResponseCode.SERVICE_DISCONNECTED -> context.getString(R.string.billing_disconnected)
        BillingResponseCode.ITEM_UNAVAILABLE -> context.getString(R.string.billing_not_for_sale)
        BillingResponseCode.DEVELOPER_ERROR -> context.getString(R.string.billing_cant_sell)
        else -> context.getString(R.string.billing_other, result.responseCode)
    }
}

/** Ties a purchase to the Aurora account it was bought under, in the form Play accepts, without giving Play the uid. */
fun obfuscatedAccountId(uid: String): String = MessageDigest.getInstance("SHA-256").digest(uid.toByteArray()).joinToString("") { "%02x".format(it) }

/** Play's own subscription screen, where the person cancels or changes plan. aurora never cancels for them. */
fun openSubscriptionSettings(context: Context, productId: String?) {
    val uri = "https://play.google.com/store/account/subscriptions".toUri().buildUpon()
        .apply { if (productId != null) appendQueryParameter("sku", productId) }
        .appendQueryParameter("package", context.packageName)
        .build()
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: ActivityNotFoundException) {
        // No Play Store and no browser: nothing to open.
    }
}
