package app.aurora.account

import android.app.Activity
import kotlinx.coroutines.flow.StateFlow

/*
 * Who is signed in, as the authentication provider says. Only FirebaseIdentity talks to a real provider; tests
 * use a fake. There is no flag anywhere that means "logged in": a session is what the provider holds.
 */

data class AuthUser(
    val uid: String,
    val email: String?,
    val emailVerified: Boolean,
    val displayName: String?,
    val photoUrl: String?,
    val providers: Set<String>,
) {
    val hasGoogle: Boolean get() = GoogleProvider in providers
    val hasEmail: Boolean get() = EmailProvider in providers
}

const val GoogleProvider = "google.com"
const val EmailProvider = "password"

sealed interface AuthState {
    /** The provider hasn't answered yet. Nothing behind the gate may draw. */
    data object Loading : AuthState
    data object SignedOut : AuthState
    data class SignedIn(val user: AuthUser) : AuthState
}

enum class AuthFailure { Cancelled, Network, NoGoogleAccount, InvalidLink, ExpiredLink, WrongEmail, Collision, TooManyRequests, NotConfigured, Other }

sealed interface TokenResult {
    data class Ok(val token: String) : TokenResult
    /** Couldn't reach the provider. The session stands, and nothing server-controlled can happen until it can. */
    data object Offline : TokenResult
    /** The provider refused this session: disabled, deleted or revoked. */
    data object Rejected : TokenResult
}

interface Identity {
    /** False when this build has no Firebase project: sign-in isn't possible, and the gate says so. */
    val configured: Boolean
    val state: StateFlow<AuthState>
    suspend fun token(forceRefresh: Boolean = false): TokenResult
    suspend fun signInWithGoogle(activity: Activity): AuthFailure?
    suspend fun sendEmailLink(email: String): AuthFailure?
    fun isEmailLink(link: String): Boolean
    suspend fun completeEmailLink(email: String, link: String): AuthFailure?
    /** Adds Google to the signed-in account. Collision: that Google account already has its own Aurora account. */
    suspend fun linkGoogle(activity: Activity): AuthFailure?
    /** Adds email sign-in, with a link the person proved they received. */
    suspend fun linkEmail(email: String, link: String): AuthFailure?
    /** A fresh proof of identity for this same user, for deleting the account or moving a purchase. */
    suspend fun reauthenticateWithGoogle(activity: Activity): AuthFailure?
    suspend fun updateDisplayName(name: String): AuthFailure?
    suspend fun signOut()
}

/** Email as far as a form can tell: the link proves the rest. */
fun looksLikeEmail(email: String): Boolean {
    val at = email.indexOf('@')
    return email.length in 3..254 && at > 0 && at == email.lastIndexOf('@') && email.none { it.isWhitespace() } &&
        email.indexOf('.', at) in (at + 2) until email.length - 1
}
