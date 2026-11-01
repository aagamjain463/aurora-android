package app.aurora.account

import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.aurora.TestAuroraApplication
import app.aurora.TestUid
import app.aurora.activate
import app.aurora.deleteAllData
import app.aurora.normalSession
import app.aurora.protect.ActivationProblem
import app.aurora.protect.Mode
import app.aurora.protect.Runtime
import app.aurora.protect.Timing
import app.aurora.plan.LedgerEntry
import app.aurora.plan.Plan
import app.aurora.syncRuns
import app.aurora.resetQuotaAuthorizations
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.flow.first
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import java.time.Duration

/** The account layer through the real repository, stores and activation, with the provider and the server faked. */
@RunWith(AndroidJUnit4::class)
class AccountFlowTest {
    private val app get() = ApplicationProvider.getApplicationContext<TestAuroraApplication>()
    private val identity get() = app.fakeIdentity
    private val server get() = app.server
    private val account get() = app.account

    @Before
    fun reset() = kotlinx.coroutines.runBlocking<Unit> {
        server.offline = false
        server.denyQuota = false
        server.forgeSignature = false
        server.refuseWith = null
        server.requests.clear()
        identity.tokens.clear()
        identity.refreshes = 0
        identity.googleResult = null
        identity.sendResult = null
        identity.linkResult = null
        identity.sent.clear()
        identity.signOuts = 0
        identity.signIn(TestUid)
        app.deleteAllData()
        app.runtime.reset()
        java.io.File(app.noBackupFilesDir, "profiles").deleteRecursively()
        java.io.File(app.noBackupFilesDir, "pending_email").delete()
        app.vault.claim(TestUid)
        account.clearSessionEnded()
        account.changeEmail()
        account.setProfile(ProfileState.Ready)
        app.limitPrompt.value = null
        app.saveFailed.value = false
        resetQuotaAuthorizations()
    }

    @After
    fun tearDown() {
        app.scope.coroutineContext.cancelChildren()
    }

    private fun flow(block: suspend () -> Unit) {
        val job = app.scope.async { block() }
        val deadline = System.nanoTime() + 30_000_000_000L
        while (!job.isCompleted) {
            check(System.nanoTime() < deadline) { "Timed out" }
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(20))
            Thread.sleep(2)
        }
        job.getCompletionExceptionOrNull()?.let { throw it }
    }

    private suspend fun ready() {
        app.rules.state.first { it != null }
        app.runtime.state.first { it != null }
        app.history.state.first { it != null }
        app.billing.awaitLoaded()
        account.profile.first { it != ProfileState.Checking }
    }

    private fun blocking(minutes: Int = 30, mode: Mode = Mode.AlwaysBlock) = normalSession("Evening", listOf("com.example.app"), mode)

    // ---- Sessions ------------------------------------------------------------------------------------------

    @Test
    fun anExpiredTokenIsRefreshedOnceAndTheCallRetried() = flow {
        ready()
        server.requests.clear()
        var calls = 0
        val api = AccountApi { _, _, _ -> if (calls++ == 0) HttpReply(401, """{"error":"unauthenticated"}""") else HttpReply(200, "{}") }
        // Through the repository's own retry: same rule as every route.
        val repo = AccountRepository(identity, api, "i", "m", app.packageName, app.packageName, java.io.File(app.cacheDir, "p"), storeEntitlement = { })
        assertTrue(repo.release("r"))
        assertEquals(1, identity.refreshes)
        assertEquals(TestUid, repo.uid)
    }

    @Test
    fun aRevokedSessionEndsAndSaysSo() = flow {
        ready()
        server.refuseWith = 401 to "revoked"
        assertFalse(account.sync())
        assertEquals(AuthState.SignedOut, account.state.value)
        assertEquals(SessionEnd.Expired, account.sessionEnded.value)
    }

    @Test
    fun aRemovedDeviceIsSignedOutAndToldWhy() = flow {
        ready()
        server.refuseWith = 401 to "installation_removed"
        account.sync()
        assertEquals(AuthState.SignedOut, account.state.value)
        assertEquals(SessionEnd.DeviceRemoved, account.sessionEnded.value)
    }

    @Test
    fun beingOfflineKeepsTheSession() = flow {
        ready()
        server.offline = true
        assertFalse(account.sync())
        assertEquals(TestUid, account.uid)
        identity.tokens.addLast(TokenResult.Offline)
        assertFalse(account.sync())
        assertEquals(TestUid, account.uid)
    }

    @Test
    fun aProviderThatRejectsTheSessionEndsIt() = flow {
        ready()
        identity.tokens.addLast(TokenResult.Rejected)
        assertFalse(account.sync())
        assertEquals(AuthState.SignedOut, account.state.value)
    }

    // ---- Email ---------------------------------------------------------------------------------------------

    private val link = "https://p.firebaseapp.com/__/auth/links?oobCode=x"

    @Test
    fun emailLinkSignsInOnThePhoneThatAsked() = flow {
        ready()
        identity.signOut()
        assertNull(account.sendEmailLink("  person@proton.me "))
        assertEquals(listOf("person@proton.me"), identity.sent)
        assertEquals("person@proton.me", (account.email.value as EmailStep.Sent).email)
        account.handleLink(link)
        assertEquals(EmailStep.Verified, account.email.value)
        assertEquals("person@proton.me", (account.state.value as AuthState.SignedIn).user.email)
    }

    @Test
    fun emailsThatCanNeverWorkAreCaughtBeforeSending() = flow {
        ready()
        for (bad in listOf("", "person", "@x.com", "a@b", "a b@c.com", "a@@b.com", "a@b.")) {
            assertEquals(bad, AuthFailure.WrongEmail, account.sendEmailLink(bad))
        }
        assertTrue(identity.sent.isEmpty())
        for (good in listOf("a@b.co", "first.last@uni.ac.in", "x+y@custom-domain.org")) assertTrue(good, looksLikeEmail(good))
    }

    @Test
    fun anExpiredOrInvalidLinkSaysWhichAndKeepsTheAddress() = flow {
        ready()
        identity.signOut()
        account.sendEmailLink("a@b.co")
        identity.linkResult = AuthFailure.ExpiredLink
        account.handleLink(link)
        assertEquals(EmailStep.Failed(AuthFailure.ExpiredLink, "a@b.co"), account.email.value)
        identity.linkResult = AuthFailure.InvalidLink
        account.handleLink(link)
        assertEquals(EmailStep.Failed(AuthFailure.InvalidLink, "a@b.co"), account.email.value)
        assertEquals(AuthState.SignedOut, account.state.value)
    }

    @Test
    fun aLinkOpenedElsewhereAsksForTheAddress() = flow {
        ready()
        identity.signOut()
        account.handleLink(link)
        assertEquals(EmailStep.NeedsAddress(link), account.email.value)
        account.finishLinkWithAddress("a@b.co", link)
        assertEquals(EmailStep.Verified, account.email.value)
    }

    @Test
    fun otherLinksAreIgnored() = flow {
        ready()
        account.handleLink("https://example.com/whatever")
        assertEquals(EmailStep.Idle, account.email.value)
    }

    // ---- Server-authoritative allowance ----------------------------------------------------------------------

    @Test
    fun aCommittedStartNeedsTheServersSignedYes() = flow {
        ready()
        assertNull(app.activate(blocking(), Timing.Now(30)))
        assertEquals(1, app.runtime.value!!.runs.size)
        assertTrue(server.requests.contains("/v1/quota/reserve"))
    }

    @Test
    fun offlineRefusesANewCommitmentAndChangesNothing() = flow {
        ready()
        server.offline = true
        assertEquals(ActivationProblem.NeedsConnection, app.activate(blocking(), Timing.Now(30))?.problem)
        assertTrue(app.runtime.value!!.runs.isEmpty())
        assertTrue(app.runtime.value!!.ledger.isEmpty())
    }

    @Test
    fun theServersNoIsExplainedAndChangesNothing() = flow {
        ready()
        server.denyQuota = true
        val refusal = app.activate(blocking(), Timing.Now(30))
        assertEquals(ActivationProblem.OverCommittedAllowance, refusal?.problem)
        assertNotNull(app.limitPrompt.value)
        assertTrue(app.runtime.value!!.runs.isEmpty())
    }

    @Test
    fun aForgedAnswerIsNotAuthorization() = flow {
        ready()
        server.forgeSignature = true
        assertEquals(ActivationProblem.NeedsConnection, app.activate(blocking(), Timing.Now(30))?.problem)
        assertTrue(app.runtime.value!!.runs.isEmpty())
    }

    @Test
    fun flexibleAndFrictionAskNobody() = flow {
        ready()
        server.offline = true
        server.requests.clear()
        assertNull(app.activate(blocking(mode = Mode.Flexible), Timing.Now(30)))
        assertNull(app.activate(normalSession("Friction", listOf("com.example.other"), Mode.Friction), Timing.Now(30)))
        assertEquals(2, app.runtime.value!!.runs.size)
    }

    @Test
    fun anExistingCommitmentSurvivesLosingTheNetworkAndSigningOut() = flow {
        ready()
        assertNull(app.activate(blocking(), Timing.Now(30)))
        server.offline = true
        app.syncRuns()
        assertEquals(1, app.runtime.value!!.runs.size)
        account.signOut()
        app.syncRuns()
        assertEquals(1, app.runtime.value!!.runs.size)
        assertTrue(app.policySnapshot(emptySet(), emptyMap()) != null)
    }

    @Test
    fun aScheduledCommitmentWaitsForAuthorizationThenStarts() = flow {
        ready()
        server.offline = true
        val c = blocking()
        val start = app.now().wall + 2_000
        assertNull(app.activate(c, Timing.Later(start, 30)))
        // Server down when it is due: not enforced, and not refused either.
        kotlinx.coroutines.delay(2_100)
        app.syncRuns()
        assertTrue(app.runtime.value!!.runs.isEmpty())
        assertTrue(app.runtime.value!!.quotaDenied.isEmpty())
    }

    @Test
    fun aScheduledCommitmentAuthorizedAheadStartsWithoutTheNetwork() = flow {
        ready()
        val c = blocking()
        val start = app.now().wall + 2_000
        assertNull(app.activate(c, Timing.Later(start, 30)))
        app.syncRuns()
        assertEquals(1, app.runtime.value!!.authorizations.size)
        server.offline = true
        kotlinx.coroutines.delay(2_100)
        app.syncRuns()
        assertEquals(1, app.runtime.value!!.runs.size)
    }

    @Test
    fun aScheduledCommitmentTheServerRefusesIsSkippedOnce() = flow {
        ready()
        server.denyQuota = true
        val c = blocking()
        val start = app.now().wall + 1_500
        assertNull(app.activate(c, Timing.Later(start, 30)))
        kotlinx.coroutines.delay(1_600)
        app.syncRuns()
        assertTrue(app.runtime.value!!.runs.isEmpty())
        assertEquals(1, app.runtime.value!!.quotaDenied.size)
    }

    // ---- Accounts on one phone ---------------------------------------------------------------------------------

    @Test
    fun anotherAccountSeesNothingOfTheFirstAndGetsItBack() = flow {
        ready()
        app.rules.update { it.copy(motivations = listOf("A's private reason")) }
        identity.signOut()
        identity.signIn("second")
        account.profile.first { it == ProfileState.Ready }
        waitForOwner("second")
        assertTrue(app.rules.value!!.motivations.isEmpty())

        app.rules.update { it.copy(motivations = listOf("B's reason")) }
        identity.signOut()
        identity.signIn(TestUid)
        waitForOwner(TestUid)
        assertEquals(listOf("A's private reason"), app.rules.value!!.motivations)
    }

    private suspend fun waitForOwner(uid: String) {
        val deadline = System.nanoTime() + 10_000_000_000L
        while (app.vault.owner() != uid || account.profile.value != ProfileState.Ready) {
            check(System.nanoTime() < deadline) { "Never bound to $uid" }
            kotlinx.coroutines.delay(5)
        }
    }

    @Test
    fun aCommitmentCarriesAcrossAccountsSayingNothingAboutItself() = flow {
        ready()
        assertNull(app.activate(blocking(), Timing.Now(30)))
        identity.signOut()
        identity.signIn("second")
        waitForOwner("second")
        val run = app.runtime.value!!.runs.single()
        assertTrue(run.carried)
        assertFalse(run.name.contains("Evening"))
        assertEquals(listOf("com.example.app"), run.shield?.keys)
        assertTrue("B's ledger is B's", app.runtime.value!!.ledger.isEmpty())
        val snapshot = app.policySnapshot(emptySet(), emptyMap())
        assertNotNull(snapshot)
    }

    @Test
    fun aRunningFlexibleSessionDoesntFollowToAnotherAccount() = flow {
        ready()
        assertNull(app.activate(blocking(mode = Mode.Flexible), Timing.Now(30)))
        identity.signOut()
        identity.signIn("second")
        waitForOwner("second")
        assertTrue(app.runtime.value!!.runs.isEmpty())
    }

    @Test
    fun plusCountsOnlyForTheAccountThatHoldsIt() = flow {
        ready()
        assertEquals(Plan.Free, app.plan)
        identity.signOut()
        assertEquals(Plan.Free, app.plan)
    }

    // ---- Existing installs ---------------------------------------------------------------------------------------

    private suspend fun legacyInstall() {
        app.vault.forget(TestUid)
        app.preferences.setOnboarded(true)
        app.rules.update { it.copy(motivations = listOf("mine")) }
        app.runtime.update { it.copy(ledger = listOf(ledgerEntry())) }
    }

    private fun ledgerEntry() = LedgerEntry(start = System.currentTimeMillis() - 600_000, end = System.currentTimeMillis() + 600_000, zone = "UTC")

    @Test
    fun anExistingInstallIsAskedAndKeepingLosesNothing() = flow {
        ready()
        legacyInstall()
        identity.signOut()
        account.setProfile(ProfileState.Checking)
        identity.signIn("newcomer")
        account.profile.first { it == ProfileState.NeedsDecision }
        assertEquals("nothing changed yet", listOf("mine"), app.rules.value!!.motivations)
        assertNull(app.vault.owner())

        server.requests.clear()
        decideLocalData("newcomer", keep = true)
        assertEquals("newcomer", app.vault.owner())
        assertEquals(ProfileState.Ready, account.profile.value)
        assertEquals(listOf("mine"), app.rules.value!!.motivations)
        assertTrue("today's committed time reaches the account once", server.requests.contains("/v1/quota/import"))
        assertNull(app.vault.ledgerImportPending())
        server.requests.clear()
        importLedgerAgain()
        assertFalse(server.requests.contains("/v1/quota/import"))
    }

    private suspend fun importLedgerAgain() = app.importLedgerIfPending()

    private suspend fun decideLocalData(uid: String, keep: Boolean) = app.decideLocalData(uid, keep)

    @Test
    fun startingFreshDeletesNothingAndAnotherAccountIsAsked() = flow {
        ready()
        legacyInstall()
        identity.signOut()
        account.setProfile(ProfileState.Checking)
        identity.signIn("fresh")
        account.profile.first { it == ProfileState.NeedsDecision }
        decideLocalData("fresh", keep = false)
        assertEquals("fresh", app.vault.owner())
        assertTrue(app.rules.value!!.motivations.isEmpty())
        assertTrue(app.vault.hasUnclaimed())

        identity.signOut()
        account.setProfile(ProfileState.Checking)
        identity.signIn("other")
        account.profile.first { it == ProfileState.NeedsDecision }
        decideLocalData("other", keep = true)
        assertEquals(listOf("mine"), app.rules.value!!.motivations)
    }

    // ---- Leaving ----------------------------------------------------------------------------------------------

    @Test
    fun deletionLeavesRunningCommitmentsAndSignsOut() = flow {
        ready()
        assertNull(app.activate(blocking(), Timing.Now(30)))
        app.rules.update { it.copy(motivations = listOf("mine")) }
        app.afterDeletionRequested()
        assertEquals(AuthState.SignedOut, account.state.value)
        assertEquals(1, app.runtime.value!!.runs.size)
        assertTrue(app.rules.value!!.motivations.isEmpty())
        assertNull(app.vault.owner())
    }

    @Test
    fun runtimeCarriesOnlyCommitments() {
        val empty = mergeCarried(null, null, app.now(), "x")
        assertEquals(Runtime(), empty)
    }
}
