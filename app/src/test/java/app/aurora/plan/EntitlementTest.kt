package app.aurora.plan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.Signature
import java.util.Base64

class EntitlementTest {
    private val plus = setOf("aurora_plus_monthly", "aurora_plus_annual")
    private val keys = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
    private val publicKey = Base64.getEncoder().encodeToString(keys.public.encoded)
    private val now = 1_800_000_000_000L
    private val day = 86_400_000L

    private fun signed(payload: String): SignedEntitlement {
        val s = Signature.getInstance("SHA256withRSA").apply { initSign(keys.private); update(payload.toByteArray()) }
        return SignedEntitlement(payload, Base64.getEncoder().encodeToString(s.sign()))
    }

    private fun snapshot(tier: String = "plus", paidThrough: Long = now + 30 * day, renews: Boolean = true, issued: Long = now, install: String = "i1", pkg: String = "app.aurora") =
        EntitlementSnapshot(tier, "active", "aurora_plus_monthly", paidThrough, renews, issued, install, pkg, uid = "u1")

    private fun plan(s: EntitlementSnapshot?, at: Long = now) = planFrom(s, "u1", "i1", "app.aurora", at)

    private fun facts(purchased: Boolean = false, pending: Boolean = false, product: String = "aurora_plus_monthly") =
        PurchaseFacts(listOf(product), purchased, pending, acknowledged = false, autoRenewing = true, token = "t")

    @Test
    fun aPendingPurchaseDoesNotUnlockPlus() {
        val v = evaluate(listOf(facts(pending = true)), plus)
        assertTrue(v.pending)
        assertTrue(v.purchased.isEmpty())
        assertEquals(Plan.Free, plan(null))
    }

    @Test
    fun onlyPlusProductsCount() {
        assertTrue(evaluate(listOf(facts(purchased = true, product = "something_else")), plus).purchased.isEmpty())
        assertEquals(1, evaluate(listOf(facts(purchased = true, product = "aurora_plus_annual")), plus).purchased.size)
    }

    @Test
    fun aVerifiedPurchaseUnlocksPlus() {
        val s = signed("""{"tier":"plus","state":"active","paidThrough":${now + day},"autoRenewing":true,"issuedAt":$now,"installId":"i1","packageName":"app.aurora","uid":"u1"}""")
        assertEquals(Plan.Plus, plan(verifiedSnapshot(s, publicKey)))
    }

    @Test
    fun aSnapshotFromTheRealServerCodeVerifiesInKotlin() {
        // Produced by server/src/worker.js. Proves the two languages agree on the signature format.
        val fixture = SignedEntitlement(FixturePayload, FixtureSignature)
        val snapshot = verifiedSnapshot(fixture, FixtureKey)
        assertNotNull(snapshot)
        assertEquals("plus", snapshot!!.tier)
        assertEquals("install-1", snapshot.installId)
        assertEquals(true, snapshot.autoRenewing)
    }

    @Test
    fun aForgedOrTamperedSnapshotUnlocksNothing() {
        val real = signed("""{"tier":"free","installId":"i1","packageName":"app.aurora"}""")
        assertNull(verifiedSnapshot(real.copy(payload = real.payload.replace("free", "plus")), publicKey))
        assertNull(verifiedSnapshot(SignedEntitlement("""{"tier":"plus"}""", "AAAA"), publicKey))
        assertNull(verifiedSnapshot(SignedEntitlement(), publicKey))
        // A build with no key can't verify anything.
        assertNull(verifiedSnapshot(real, ""))
    }

    @Test
    fun cancelledKeepsAccessThroughThePaidDateThenEnds() {
        val s = snapshot(paidThrough = now + 3 * day, renews = false)
        assertEquals(Plan.Plus, plan(s, now + 3 * day - 1))
        assertEquals(Plan.Free, plan(s, now + 3 * day))
    }

    @Test
    fun aSnapshotFromAnotherInstallOrAppGrantsNothing() {
        // Restored from a backup, copied, or reinstalled: this install's id is new.
        assertEquals(Plan.Free, plan(snapshot(install = "another")))
        assertEquals(Plan.Free, plan(snapshot(pkg = "other.app")))
    }

    @Test
    fun aFreeSnapshotIsFree() {
        assertEquals(Plan.Free, plan(snapshot(tier = "free")))
    }

    @Test
    fun offlinePolicyIsSevenDaysFromTheLastVerification() {
        val s = snapshot(paidThrough = now + 60 * day)
        assertEquals(Plan.Plus, plan(s, now + OfflineGraceMs))
        assertEquals(Plan.Free, plan(s, now + OfflineGraceMs + 1))
        // A clock set back before the verification doesn't stretch it.
        assertEquals(Plan.Free, plan(s, now - day))
    }

    @Test
    fun aRenewalDueWhileOfflineDoesNotDropPlusWithinTheGrace() {
        val s = snapshot(paidThrough = now + day)
        assertEquals(Plan.Plus, plan(s, now + 2 * day))
        assertEquals(Plan.Free, plan(s, now + 8 * day))
    }

    private companion object {
        const val FixtureKey = "MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAmewB1xEh0wczr/wQJAFKn3RgjHhnfpsTZWNzFYJNSASjQfxrPDF4aLbz0yPMb8hr/mSHM1lgppZ99fWV7MOwuaIhNekEG8hpZqQ76C+lUoB5ilSwibzkx3uBr73/kmIcqilynYcheLEUeRSMZ0oPX/aYYKW3n38UMM+YM1Tqcc4nhQybyWA8VruexSaAGMsQU5w7XxEgy2OHLGXqV9w3KrkXP+oiWE6v6yXVnoJbm0yOVv8y4jXNaYQYzHeCusCzs2ySavWM6oQx2x6SgOpqAH+KadaUXVYsWAhPA33idmZHuDlutaGFVfsuHO1eOMq+g/lAm9+ByJeMcs3vu+ilPQIDAQAB"
        const val FixturePayload = "{\"tier\":\"plus\",\"state\":\"active\",\"productId\":\"aurora_plus_monthly\",\"paidThrough\":2082758400000,\"autoRenewing\":true,\"issuedAt\":1793516783943,\"installId\":\"install-1\",\"packageName\":\"app.aurora\"}"
        const val FixtureSignature = "AhUUfk2R+Z7w1EzhxUQmJVn0b1lOdwNCM62yuHTRRuZArpVhFUX7tCqcnc0D7LGVLaqDq6s8jm2fEICaByyXHzGIW9IQjxoCoReXmBtrs7JZZcAE3C1vBCi8cnug9u9BegDLeafj9n74YbMLqsYgzRPKrQeMFfvGpF5IzHVauftBgD6prjwfhP6eD1mxX4bPv+FQiqVZXu4DJjAbaY5NMd8VQ1fbIu6gQYkwv/GmCFDL5yKHT5mwYCMybtUymso1l9DG0sAfdQ0aFkzusu8UxlHnn3uvrjxc3vlJmDYSGu6fWrMvuA/eFp8lf2Nm7mYp6kfxmivRBeXNJBmKrff5Cg=="
    }

    @Test
    fun plusBelongsToTheAccountItWasIssuedTo() {
        val s = EntitlementSnapshot("plus", "active", "aurora_plus_monthly", now + 86_400_000, true, now, "i1", "app.aurora", uid = "u1")
        assertEquals(Plan.Plus, planFrom(s, "u1", "i1", "app.aurora", now))
        assertEquals(Plan.Free, planFrom(s, "u2", "i1", "app.aurora", now))
        assertEquals(Plan.Free, planFrom(s, null, "i1", "app.aurora", now))
    }
}
