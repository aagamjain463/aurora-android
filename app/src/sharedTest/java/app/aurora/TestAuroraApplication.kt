package app.aurora

import android.app.Activity
import app.aurora.account.AuthFailure
import app.aurora.account.AuthState
import app.aurora.account.AuthUser
import app.aurora.account.EmailProvider
import app.aurora.account.GoogleProvider
import app.aurora.account.HttpReply
import app.aurora.account.Identity
import app.aurora.account.TokenResult
import app.aurora.account.Transport
import app.aurora.plan.Plan
import app.aurora.plan.committedFits
import app.aurora.plan.quotaStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.security.KeyPairGenerator
import java.security.Signature
import java.time.ZoneId
import java.util.Base64
import java.util.UUID

const val TestUid = "test-user"

/** Fakes for automated tests only. Results from these prove the app's logic, never that real sign-in works. */
class FakeIdentity(user: AuthUser? = testUser(TestUid)) : Identity {
    override val configured = true
    override val state = MutableStateFlow<AuthState>(user?.let { AuthState.SignedIn(it) } ?: AuthState.SignedOut)
    var tokens = ArrayDeque<TokenResult>()
    var refreshes = 0
    var googleResult: AuthFailure? = null
    var googleUser: AuthUser = testUser("google-user", GoogleProvider)
    var sendResult: AuthFailure? = null
    var linkResult: AuthFailure? = null
    val sent = mutableListOf<String>()
    var signOuts = 0

    fun signIn(uid: String) {
        state.value = AuthState.SignedIn(testUser(uid))
    }

    override suspend fun token(forceRefresh: Boolean): TokenResult {
        if (forceRefresh) refreshes++
        return tokens.removeFirstOrNull() ?: TokenResult.Ok("token")
    }

    override suspend fun signInWithGoogle(activity: Activity): AuthFailure? {
        if (googleResult == null) state.value = AuthState.SignedIn(googleUser)
        return googleResult
    }

    override suspend fun sendEmailLink(email: String): AuthFailure? {
        if (sendResult == null) sent += email
        return sendResult
    }

    override fun isEmailLink(link: String) = link.contains("/__/auth/links")

    override suspend fun completeEmailLink(email: String, link: String): AuthFailure? {
        if (linkResult == null) state.value = AuthState.SignedIn(testUser(email, EmailProvider).copy(email = email))
        return linkResult
    }

    override suspend fun linkGoogle(activity: Activity): AuthFailure? = googleResult
    override suspend fun linkEmail(email: String, link: String): AuthFailure? = linkResult
    override suspend fun reauthenticateWithGoogle(activity: Activity): AuthFailure? = googleResult
    override suspend fun updateDisplayName(name: String): AuthFailure? = null

    override suspend fun signOut() {
        signOuts++
        state.value = AuthState.SignedOut
    }
}

fun testUser(uid: String, provider: String = GoogleProvider) = AuthUser(uid, "$uid@example.com", true, uid, null, setOf(provider))

/** Signs like the real server, with a key made for the test run. */
object TestKeys {
    private val pair by lazy { KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair() }
    val public: String get() = Base64.getEncoder().encodeToString(pair.public.encoded)
    fun signature(payload: String): String =
        Base64.getEncoder().encodeToString(Signature.getInstance("SHA256withRSA").apply { initSign(pair.private); update(payload.toByteArray()) }.sign())
}

/** The account service, scripted. Quota decisions follow this phone's ledger unless a test says otherwise. */
class FakeServer(private val app: () -> AuroraApplication) {
    var offline = false
    var denyQuota = false
    var forgeSignature = false
    /** Rewrites a reservation's signed payload: a genuine authorization, but for something else. */
    var tamper: (String) -> String = { it }
    /** Status and error code for the next requests, for testing refusals. */
    var refuseWith: Pair<Int, String>? = null
    val requests = mutableListOf<String>()
    private fun signed(payload: String) = """{"payload":${Json.encodeToString(kotlinx.serialization.serializer<String>(), payload)},"signature":"${if (forgeSignature) "AAAA" else TestKeys.signature(payload)}"}"""

    val transport: Transport = { path, _, body, _ ->
        requests += path
        val refuse = refuseWith
        when {
            offline -> null
            refuse != null -> HttpReply(refuse.first, """{"error":"${refuse.second}"}""")
            path == "/v1/account/sync" -> {
                val a = app().account
                val payload = """{"tier":"free","state":"none","issuedAt":${System.currentTimeMillis()},"installId":"${a.installId}","uid":"${a.uid}","packageName":"${app().packageName}"}"""
                HttpReply(200, """{"account":{"id":"a","email":"${a.uid}@example.com"},"entitlement":${signed(payload)},"devices":[{"id":"${a.installId}","label":"Pixel","current":true}]}""")
            }
            path == "/v1/quota/reserve" -> reserve(body)
            else -> HttpReply(200, "{}")
        }
    }

    private fun reserve(body: String): HttpReply {
        val j = Json.parseToJsonElement(body).jsonObject
        val a = app()
        val now = a.now().wall
        val startAt = j["startAt"]?.jsonPrimitive?.long ?: now
        val start = maxOf(startAt, now)
        val end = startAt + j.getValue("minutes").jsonPrimitive.long * 60_000
        val zone = ZoneId.of(j.getValue("zone").jsonPrimitive.content)
        val ledger = a.runtime.value?.ledger.orEmpty()
        if (denyQuota) return HttpReply(200, """{"allowed":false,"usedMs":7200000,"limitMs":7200000,"remainingMs":0,"resetAt":${now + 3_600_000}}""")
        if (!committedFits(Plan.Free, ledger, start, end, zone)) {
            // Like the real service: a refusal reports what is actually left today, not zero.
            val q = quotaStatus(Plan.Free, ledger, now, zone)
            return HttpReply(200, """{"allowed":false,"usedMs":${q.usedMs},"limitMs":${q.limitMs},"remainingMs":${q.remainingMs},"resetAt":${q.resetAt}}""")
        }
        val payload = """{"type":"reservation","reservationId":"${UUID.randomUUID()}","uid":"${a.account.uid}","installId":"${j.getValue("installId").jsonPrimitive.content}","commitmentId":"${j.getValue("commitmentId").jsonPrimitive.content}","start":$start,"end":$end,"tier":"free","usedMs":0,"limitMs":7200000,"remainingMs":7200000,"resetAt":${now + 3_600_000},"packageName":"${a.packageName}"}"""
        return HttpReply(200, """{"allowed":true,"tier":"free","authorization":${signed(tamper(payload))}}""")
    }
}

/** The app with a signed-in fake account, so a test sees what a person sees past the gate. Tests of the gate sign out. */
class TestAuroraApplication : AuroraApplication() {
    val fakeIdentity by lazy { FakeIdentity() }
    val server by lazy { FakeServer { this } }
    override fun createIdentity(): Identity = fakeIdentity
    override val accountServer: Transport get() = server.transport
    override val entitlementPublicKey: String get() = TestKeys.public

    override fun onCreate() {
        // This phone's data already belongs to the test account: no migration question unless a test asks for one.
        runBlocking { vault.claim(TestUid) }
        super.onCreate()
    }
}
