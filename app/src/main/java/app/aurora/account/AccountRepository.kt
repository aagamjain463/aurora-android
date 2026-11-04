package app.aurora.account

import android.app.Activity
import app.aurora.data.AuroraJson
import app.aurora.plan.SignedEntitlement
import app.aurora.plan.verifiedPayload
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.time.ZoneId

/** Where the email sign-in stands. Held here, not in a screen, so it survives the trip to the mail app. */
sealed interface EmailStep {
    data object Idle : EmailStep
    data class Sent(val email: String, val sentAt: Long) : EmailStep
    /** A link arrived on a phone that didn't ask for it: the address has to be typed, as proof. */
    data class NeedsAddress(val link: String) : EmailStep
    data object Completing : EmailStep
    data object Verified : EmailStep
    data class Failed(val failure: AuthFailure, val email: String?) : EmailStep
}

/** What the server said about a new committed session. */
sealed interface Authorization {
    /** The signed go-ahead. Null [reservationId] means Plus: nothing was reserved. */
    data class Granted(val reservationId: String?, val start: Long, val end: Long) : Authorization
    data class Denied(val quota: RemoteQuota) : Authorization
    /** No decision could be made. New commitments wait; existing ones are untouched. */
    data object Unreachable : Authorization
}

enum class ProfileState { Checking, NeedsDecision, Ready, Failed }

/** Why the last session ended without the person asking. */
enum class SessionEnd { Expired, DeviceRemoved }

enum class ReauthResult { Done, CheckEmail, Failed }

/**
 * The verified account behind this installation: the session from [identity], what the server knows, and
 * every call to it. Everything sent is scoped by the server to the token's account; a uid is never sent.
 */
class AccountRepository(
    val identity: Identity,
    private val api: AccountApi?,
    val installId: String,
    private val deviceLabel: String,
    private val publicKey: String,
    private val packageName: String,
    private val pendingEmailFile: File,
    private val storeEntitlement: suspend (SignedEntitlement) -> Unit,
    private val wallClock: () -> Long = System::currentTimeMillis,
    /** A Play Integrity token for a nonce, or null when there is none (DeviceCredential.kt). */
    private val integrityToken: suspend (nonce: String) -> String? = { null },
    /** The server won't accept this install id any more: the next start begins a new installation. */
    private val onDeviceRefused: () -> Unit = {},
) {
    val state: StateFlow<AuthState> get() = identity.state
    val serverConfigured: Boolean get() = api != null

    val uid: String? get() = (state.value as? AuthState.SignedIn)?.user?.uid
    val resolved: Boolean get() = state.value != AuthState.Loading

    private val _profile = MutableStateFlow(ProfileState.Checking)
    val profile: StateFlow<ProfileState> = _profile
    fun setProfile(state: ProfileState) {
        _profile.value = state
    }

    private val _remote = MutableStateFlow<SyncResponse?>(null)
    val remote: StateFlow<SyncResponse?> = _remote

    private val _email = MutableStateFlow<EmailStep>(EmailStep.Idle)
    val email: StateFlow<EmailStep> = _email

    private val _sessionEnded = MutableStateFlow<SessionEnd?>(null)
    val sessionEnded: StateFlow<SessionEnd?> = _sessionEnded
    fun clearSessionEnded() {
        _sessionEnded.value = null
    }

    /** A commitment is waiting on the server. The app's root shows it and holds further taps. */
    val authorizing = MutableStateFlow(false)

    private var lastSync = 0L

    // ---- Calls ---------------------------------------------------------------------------------------------

    /** Runs [call] with a current token. A token the server rejects is refreshed once; a session it still rejects has ended. */
    private suspend fun <T> authed(call: suspend (AccountApi, String) -> ApiResult<T>): ApiResult<T> {
        val server = api ?: return ApiResult.Unreachable
        suspend fun attempt(force: Boolean): ApiResult<T>? = when (val t = identity.token(force)) {
            is TokenResult.Ok -> call(server, t.token)
            TokenResult.Offline -> ApiResult.Unreachable
            TokenResult.Rejected -> null
        }
        var result = attempt(false)
        if (result is ApiResult.Refused && result.status == 401 && result.code == "unauthenticated") result = attempt(true)
        when {
            result == null || (result is ApiResult.Refused && result.status == 401 && result.code in setOf("unauthenticated", "revoked")) -> endSession(SessionEnd.Expired)
            result is ApiResult.Refused && result.code == "installation_removed" -> endSession(SessionEnd.DeviceRemoved)
            result is ApiResult.Refused && result.code == "device_proof_required" -> onDeviceRefused()
        }
        return result ?: ApiResult.Refused(401, "revoked")
    }

    private suspend fun endSession(reason: SessionEnd) {
        _sessionEnded.value = reason
        signOut()
    }

    /** Asks the server for the account, its entitlement and the shared allowance. Without a network, what is stored stands. */
    suspend fun sync(): Boolean {
        uid ?: return false
        return when (val r = authed { server, token -> server.sync(token, installId, deviceLabel, ZoneId.systemDefault().id) }) {
            is ApiResult.Ok -> {
                _remote.value = r.value
                lastSync = wallClock()
                storeEntitlement(r.value.entitlement)
                r.value.device?.attestNonce?.let { attest(it) }
                true
            }
            else -> false
        }
    }

    /** Answers the server's occasional Play Integrity check. No token, or no answer, changes nothing. */
    private suspend fun attest(nonce: String) {
        val token = integrityToken(nonce) ?: return
        authed { server, t -> server.attest(t, installId, token) }
    }

    /** Coming back to the app after a while. */
    suspend fun syncIfStale() {
        if (wallClock() - lastSync > StaleMs) sync()
    }

    suspend fun verifyPurchase(purchaseToken: String, productId: String, packageName: String): SignedEntitlement? =
        (authed { server, token -> server.verify(token, purchaseToken, productId, installId, packageName) } as? ApiResult.Ok)?.value

    /** Moves a purchase held on this phone to this account. "reauth_required" comes back as Refused. */
    suspend fun transferPurchase(purchaseToken: String): ApiResult<SignedEntitlement> = authed { server, token -> server.transfer(token, purchaseToken, installId) }

    /**
     * Asks to commit [minutes]. [commitmentId] is also the idempotency key, so asking twice reserves once.
     * Only an answer signed for this account, this install and this commitment counts.
     */
    suspend fun authorize(commitmentId: String, serverMode: String, minutes: Int, startAt: Long?, zone: ZoneId): Authorization {
        uid ?: return Authorization.Unreachable
        val reply = authed { server, token -> server.reserve(token, installId, commitmentId, serverMode, minutes, startAt, zone.id) }
        val answer = (reply as? ApiResult.Ok)?.value ?: return Authorization.Unreachable
        if (!answer.allowed) {
            val quota = RemoteQuota(answer.usedMs, answer.limitMs, answer.remainingMs, answer.resetAt)
            updateQuota(quota)
            return Authorization.Denied(quota)
        }
        val signed = answer.authorization ?: return Authorization.Unreachable
        val payload = verifiedPayload(signed, publicKey) ?: return Authorization.Unreachable
        val r = runCatching { AuroraJson.decodeFromString(Reservation.serializer(), payload) }.getOrNull() ?: return Authorization.Unreachable
        if (r.type != "reservation" || r.uid != uid || r.installId != installId || r.commitmentId != commitmentId || r.packageName != packageName) return Authorization.Unreachable
        if (r.tier != "plus") updateQuota(RemoteQuota(r.usedMs, r.limitMs, r.remainingMs, r.resetAt))
        return Authorization.Granted(r.reservationId, r.start, r.end)
    }

    private fun updateQuota(quota: RemoteQuota) {
        _remote.value = _remote.value?.copy(quota = quota)
    }

    /** The server's minutes left today, when it has told us for today. Null for Plus or before the first answer. */
    fun serverRemainingMs(): Long? = _remote.value?.quota?.takeIf { it.resetAt > wallClock() }?.remainingMs

    suspend fun release(reservationId: String): Boolean = authed { server, token -> server.release(token, reservationId) } is ApiResult.Ok

    suspend fun importLedger(entries: List<Triple<Long, Long, String>>): Boolean =
        authed { server, token -> server.importLedger(token, installId, entries) } is ApiResult.Ok

    suspend fun removeDevice(id: String): Boolean {
        val r = authed { server, token -> server.removeDevice(token, id, installId) }
        if (r is ApiResult.Ok) _remote.value = _remote.value?.copy(devices = r.value.devices)
        return r is ApiResult.Ok
    }

    suspend fun requestDeletion(): ApiResult<DeletionResponse> = authed { server, token -> server.requestDeletion(token) }

    suspend fun cancelDeletion(): Boolean {
        val r = authed { server, token -> server.cancelDeletion(token) }
        if (r is ApiResult.Ok) sync()
        return r is ApiResult.Ok
    }

    // ---- Sign-in ---------------------------------------------------------------------------------------------

    private enum class LinkPurpose { SignIn, Link }

    /** The address and purpose of the link on its way. On this phone only, and only so the link can be finished here. */
    private fun rememberPending(email: String, purpose: LinkPurpose) = runCatching { pendingEmailFile.writeText("${purpose.name}\n$email") }
    private fun pending(): Pair<LinkPurpose, String>? = runCatching {
        val lines = pendingEmailFile.readLines()
        LinkPurpose.valueOf(lines[0]) to lines[1]
    }.getOrNull()

    /** Sends the link. Answers the same whether or not the address has an account, so this reveals nothing. */
    suspend fun sendEmailLink(email: String, linking: Boolean = false): AuthFailure? {
        val address = email.trim()
        if (!looksLikeEmail(address)) return AuthFailure.WrongEmail
        val failure = identity.sendEmailLink(address)
        if (failure == null) {
            rememberPending(address, if (linking) LinkPurpose.Link else LinkPurpose.SignIn)
            _email.value = EmailStep.Sent(address, wallClock())
        } else {
            _email.value = EmailStep.Failed(failure, address)
        }
        return failure
    }

    fun changeEmail() {
        pendingEmailFile.delete()
        _email.value = EmailStep.Idle
    }

    /** A link opened from an email. Anything else is ignored. */
    suspend fun handleLink(link: String) {
        if (!identity.isEmailLink(link)) return
        val (purpose, address) = pending() ?: run {
            _email.value = EmailStep.NeedsAddress(link)
            return
        }
        finishLink(purpose, address, link)
    }

    suspend fun finishLinkWithAddress(email: String, link: String) {
        val address = email.trim()
        if (!looksLikeEmail(address)) {
            _email.value = EmailStep.Failed(AuthFailure.WrongEmail, address)
            return
        }
        finishLink(LinkPurpose.SignIn, address, link)
    }

    private suspend fun finishLink(purpose: LinkPurpose, address: String, link: String) {
        _email.value = EmailStep.Completing
        val failure = if (purpose == LinkPurpose.Link) identity.linkEmail(address, link) else identity.completeEmailLink(address, link)
        _email.value = failure?.let { EmailStep.Failed(it, address) } ?: EmailStep.Verified
        if (failure == null) pendingEmailFile.delete()
    }

    fun clearEmailStep() {
        _email.value = EmailStep.Idle
    }

    /** A fresh sign-in as this same person: Google asks again, and email sends a new link. */
    suspend fun reauthenticate(activity: Activity): ReauthResult {
        val user = (state.value as? AuthState.SignedIn)?.user ?: return ReauthResult.Failed
        if (user.hasGoogle) return if (identity.reauthenticateWithGoogle(activity) == null) ReauthResult.Done else ReauthResult.Failed
        val address = user.email ?: return ReauthResult.Failed
        return if (sendEmailLink(address) == null) ReauthResult.CheckEmail else ReauthResult.Failed
    }

    /** Ends this session. Protection on this phone goes on: enforcement never asks who is signed in. */
    suspend fun signOut() {
        identity.signOut()
        pendingEmailFile.delete()
        _remote.value = null
        _email.value = EmailStep.Idle
        _profile.value = ProfileState.Checking
        lastSync = 0
    }

    private companion object {
        const val StaleMs = 15 * 60_000L
    }
}
