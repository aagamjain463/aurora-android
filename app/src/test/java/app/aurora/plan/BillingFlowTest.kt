package app.aurora.plan

import android.os.Looper
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import android.content.Context
import app.aurora.data.JsonValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import java.io.File
import java.security.KeyPairGenerator
import java.security.Signature
import java.util.Base64
import java.util.UUID

/** Purchases through the real repository and store, with the server answering from a test key. */
@RunWith(AndroidJUnit4::class)
class BillingFlowTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val keys = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
    private val day = 86_400_000L
    private val scope = MainScope()
    private val store: DataStore<Preferences> = PreferenceDataStoreFactory.create(
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
    ) { File(context.cacheDir, "billing-${UUID.randomUUID()}.preferences_pb") }
    private val cache = JsonValue(store, "entitlement", SignedEntitlement.serializer(), SignedEntitlement(), scope, strict = false)
    private val acknowledged = mutableListOf<String>()
    private var answer: (String) -> SignedEntitlement? = { null }
    private val billing = BillingRepository(
        context, scope, cache,
        publicKey = Base64.getEncoder().encodeToString(keys.public.encoded),
        verify = { _, _, install, _ -> answer(install) },
        configured = true,
    )

    private fun flow(block: suspend () -> Unit) {
        val job = scope.async { block() }
        val deadline = System.nanoTime() + 30_000_000_000L
        while (!job.isCompleted) {
            check(System.nanoTime() < deadline) { "Timed out" }
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(2)
        }
        job.getCompletionExceptionOrNull()?.let { throw it }
    }

    private fun signed(tier: String, install: String, state: String = "active", paidThrough: Long = System.currentTimeMillis() + 30 * day, renews: Boolean = true): SignedEntitlement {
        val payload = """{"tier":"$tier","state":"$state","productId":"aurora_plus_monthly","paidThrough":$paidThrough,"autoRenewing":$renews,"issuedAt":${System.currentTimeMillis()},"installId":"$install","packageName":"${context.packageName}"}"""
        val s = Signature.getInstance("SHA256withRSA").apply { initSign(keys.private); update(payload.toByteArray()) }
        return SignedEntitlement(payload, Base64.getEncoder().encodeToString(s.sign()))
    }

    private fun purchase(purchased: Boolean = true, pending: Boolean = false, acknowledged: Boolean = false) =
        PurchaseFacts(listOf("aurora_plus_monthly"), purchased, pending, acknowledged, true, "token")

    private suspend fun apply(vararg p: PurchaseFacts, complete: Boolean = false) {
        billing.awaitLoaded()
        billing.applyFacts(p.toList(), complete) { acknowledged += it }
    }

    @Test
    fun aPendingPurchaseUnlocksNothingAndAsksNobody() = flow {
        answer = { error("A pending purchase must not be sent for verification") }
        apply(purchase(purchased = false, pending = true))
        assertEquals(Plan.Free, billing.planNow())
        assertTrue(billing.state.value.pending)
        assertTrue(acknowledged.isEmpty())
    }

    @Test
    fun aVerifiedPurchaseUnlocksPlusAtOnceAndIsAcknowledged() = flow {
        answer = { signed("plus", it) }
        apply(purchase())
        // No restart: the same process sees it, through the flow the screens watch too.
        assertEquals(Plan.Plus, billing.planNow())
        assertEquals(listOf("token"), acknowledged)
        assertFalse(billing.state.value.verifying)
    }

    @Test
    fun anUnverifiedPurchaseStaysFreeAndIsNotAcknowledged() = flow {
        answer = { null }
        apply(purchase())
        assertEquals(Plan.Free, billing.planNow())
        assertTrue(acknowledged.isEmpty())
        assertTrue(billing.state.value.verifying)
    }

    @Test
    fun aForgedAnswerUnlocksNothing() = flow {
        answer = { signed("plus", it).copy(signature = Base64.getEncoder().encodeToString(ByteArray(256))) }
        apply(purchase())
        assertEquals(Plan.Free, billing.planNow())
        assertTrue(acknowledged.isEmpty())
    }

    @Test
    fun anAnswerForAnotherInstallUnlocksNothing() = flow {
        answer = { signed("plus", "someone-elses-install") }
        apply(purchase())
        assertEquals(Plan.Free, billing.planNow())
    }

    @Test
    fun theServerSayingNoEndsPlus() = flow {
        answer = { signed("plus", it) }
        apply(purchase())
        assertEquals(Plan.Plus, billing.planNow())
        answer = { signed("free", it, state = "revoked") }
        apply(purchase(acknowledged = true))
        assertEquals(Plan.Free, billing.planNow())
    }

    @Test
    fun cancelledKeepsPlusUntilThePaidDate() = flow {
        answer = { signed("plus", it, state = "canceled", renews = false) }
        apply(purchase(acknowledged = true))
        assertEquals(Plan.Plus, billing.planNow())
    }

    @Test
    fun beingOfflineKeepsWhatWasVerified() = flow {
        answer = { signed("plus", it) }
        apply(purchase())
        answer = { null }
        apply(purchase(acknowledged = true))
        assertEquals(Plan.Plus, billing.planNow())
    }

    @Test
    fun playReportingNoPurchaseClearsIt() = flow {
        answer = { signed("plus", it) }
        apply(purchase())
        apply(complete = true)
        assertEquals(Plan.Free, billing.planNow())
    }

    @Test
    fun aPartialReportWithNoPurchaseChangesNothing() = flow {
        answer = { signed("plus", it) }
        apply(purchase())
        apply(complete = false)
        assertEquals(Plan.Plus, billing.planNow())
    }

    @Test
    fun aDeliveryTwiceIsTheSameAsOnce() = flow {
        answer = { signed("plus", it) }
        apply(purchase())
        apply(purchase(acknowledged = true))
        assertEquals(Plan.Plus, billing.planNow())
        assertEquals(1, acknowledged.size)
    }
}
