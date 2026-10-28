package app.aurora.plan

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.core.net.toUri
import app.aurora.BuildConfig
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/** One way to buy Plus: a base plan of the Plus subscription with its price as Play formats it. */
data class Offer(val basePlan: String, val price: String, val yearly: Boolean, internal val details: ProductDetails, internal val token: String)

data class BillingState(
    val connection: Connection = Connection.Connecting,
    val offers: List<Offer> = emptyList(),
    /** A purchase is waiting for payment (for example cash or UPI collect). It doesn't grant Plus until it completes. */
    val pending: Boolean = false,
    val message: String? = null,
) {
    enum class Connection { Connecting, Ready, Unavailable }
}

/**
 * Google Play Billing for aurora plus. The plan comes only from purchases Play reports, cached for
 * offline use ([EntitlementCache], [planFrom]). There is no server: purchases are checked on the phone
 * only, which is enough to unlock features but not proof against a modified app. Server-side
 * verification is a launch dependency (docs/PRODUCT.md).
 */
class BillingRepository(
    context: Context,
    private val scope: CoroutineScope,
    private val cache: JsonValue<EntitlementCache>,
    private val wallClock: () -> Long = System::currentTimeMillis,
) {
    private val plusProducts = setOf(BuildConfig.PLUS_PRODUCT_ID)
    private val _state = MutableStateFlow(BillingState())
    val state: StateFlow<BillingState> = _state

    val plan: StateFlow<Plan> = cache.state
        .map { planFrom(it?.value ?: EntitlementCache(), wallClock()) }
        .stateIn(scope, SharingStarted.Eagerly, Plan.Free)

    private val listener = PurchasesUpdatedListener { result, purchases ->
        when (result.responseCode) {
            BillingResponseCode.OK -> scope.launch { apply(purchases.orEmpty()) }
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

    /** Asks Play again for purchases and prices: "Restore purchases" and every app start. */
    suspend fun refresh() {
        val (purchasesResult, purchases) = suspendCancellableCoroutine { c ->
            client.queryPurchasesAsync(QueryPurchasesParams.newBuilder().setProductType(ProductType.SUBS).build()) { r, list -> c.resume(r to list) }
        }
        if (purchasesResult.responseCode == BillingResponseCode.OK) apply(purchases)
        val offers = queryOffers()
        _state.value = _state.value.copy(
            connection = if (purchasesResult.responseCode == BillingResponseCode.OK) BillingState.Connection.Ready else BillingState.Connection.Unavailable,
            offers = offers,
            message = when {
                purchasesResult.responseCode != BillingResponseCode.OK -> describe(purchasesResult)
                offers.isEmpty() -> "aurora plus isn't available to buy in this version of aurora yet."
                else -> null
            },
        )
    }

    private suspend fun queryOffers(): List<Offer> {
        val params = QueryProductDetailsParams.newBuilder().setProductList(
            plusProducts.map { QueryProductDetailsParams.Product.newBuilder().setProductId(it).setProductType(ProductType.SUBS).build() },
        ).build()
        val details = suspendCancellableCoroutine { c ->
            client.queryProductDetailsAsync(params) { r, result -> c.resume(if (r.responseCode == BillingResponseCode.OK) result.productDetailsList else emptyList()) }
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

    private suspend fun apply(purchases: List<Purchase>) {
        val verdict = evaluate(
            purchases.map {
                PurchaseFacts(
                    it.products, it.purchaseState == Purchase.PurchaseState.PURCHASED, it.purchaseState == Purchase.PurchaseState.PENDING,
                    it.isAcknowledged, it.isAutoRenewing, it.purchaseToken,
                )
            },
            plusProducts,
        )
        for (token in verdict.toAcknowledge) {
            suspendCancellableCoroutine { c ->
                client.acknowledgePurchase(AcknowledgePurchaseParams.newBuilder().setPurchaseToken(token).build()) { c.resume(it) }
            }
        }
        runCatching {
            cache.update { EntitlementCache(verdict.plus, wallClock(), verdict.productId, verdict.autoRenewing) }
        }
        _state.value = _state.value.copy(pending = verdict.pending)
    }

    /** Opens Play's purchase sheet. Returns false (with a message) if it couldn't be shown. */
    fun buy(activity: Activity, offer: Offer): Boolean {
        val params = BillingFlowParams.newBuilder().setProductDetailsParamsList(
            listOf(BillingFlowParams.ProductDetailsParams.newBuilder().setProductDetails(offer.details).setOfferToken(offer.token).build()),
        ).build()
        val result = client.launchBillingFlow(activity, params)
        if (result.responseCode != BillingResponseCode.OK) _state.value = _state.value.copy(message = describe(result))
        return result.responseCode == BillingResponseCode.OK
    }

    fun clearMessage() {
        _state.value = _state.value.copy(message = null)
    }

    private fun describe(result: BillingResult): String = when (result.responseCode) {
        BillingResponseCode.BILLING_UNAVAILABLE -> "Google Play billing isn't available on this phone or account."
        BillingResponseCode.SERVICE_UNAVAILABLE, BillingResponseCode.NETWORK_ERROR -> "Google Play can't be reached right now. Try again when you're online."
        BillingResponseCode.SERVICE_DISCONNECTED -> "aurora couldn't connect to Google Play on this phone. Make sure the Play Store is installed and signed in, then try again."
        BillingResponseCode.ITEM_UNAVAILABLE -> "aurora plus isn't available to buy in this version of aurora yet."
        BillingResponseCode.DEVELOPER_ERROR -> "This version of aurora can't sell aurora plus."
        else -> "Google Play couldn't complete that (code ${result.responseCode})."
    }
}

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
