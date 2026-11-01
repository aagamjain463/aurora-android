package app.aurora.account

import android.app.Activity
import android.content.Context
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.NoCredentialException
import app.aurora.BuildConfig
import com.google.android.gms.tasks.Task
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseNetworkException
import com.google.firebase.FirebaseOptions
import com.google.firebase.FirebaseTooManyRequestsException
import com.google.firebase.auth.ActionCodeSettings
import com.google.firebase.auth.AuthCredential
import com.google.firebase.auth.EmailAuthProvider
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthActionCodeException
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException
import com.google.firebase.auth.FirebaseAuthInvalidUserException
import com.google.firebase.auth.FirebaseAuthUserCollisionException
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.auth.UserProfileChangeRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Firebase Authentication with Google, through Credential Manager, and email links. Firebase holds the session. */
class FirebaseIdentity(private val context: Context, scope: CoroutineScope) : Identity {
    override val configured = BuildConfig.FIREBASE_API_KEY.isNotBlank() && BuildConfig.FIREBASE_APP_ID.isNotBlank() && BuildConfig.FIREBASE_PROJECT_ID.isNotBlank()
    private val _state = MutableStateFlow<AuthState>(AuthState.Loading)
    override val state: StateFlow<AuthState> = _state
    private var auth: FirebaseAuth? = null

    init {
        if (!configured) _state.value = AuthState.SignedOut
        // Firebase reads its stored session from disk: off the main thread, and the gate waits for the answer.
        else scope.launch(Dispatchers.IO) {
            val app = FirebaseApp.getApps(context).firstOrNull() ?: FirebaseApp.initializeApp(
                context,
                FirebaseOptions.Builder()
                    .setApiKey(BuildConfig.FIREBASE_API_KEY)
                    .setApplicationId(BuildConfig.FIREBASE_APP_ID)
                    .setProjectId(BuildConfig.FIREBASE_PROJECT_ID)
                    .build(),
            )
            val a = FirebaseAuth.getInstance(app)
            if (BuildConfig.DEBUG && BuildConfig.AUTH_EMULATOR_HOST.isNotBlank()) a.useEmulator(BuildConfig.AUTH_EMULATOR_HOST, 9099)
            auth = a
            a.addAuthStateListener { _state.value = it.currentUser?.let { u -> AuthState.SignedIn(u.toAuthUser()) } ?: AuthState.SignedOut }
        }
    }

    private fun FirebaseUser.toAuthUser() = AuthUser(uid, email, isEmailVerified, displayName, photoUrl?.toString(), providerData.map { it.providerId }.filter { it != "firebase" }.toSet())

    private fun publish() {
        auth?.currentUser?.let { _state.value = AuthState.SignedIn(it.toAuthUser()) }
    }

    override suspend fun token(forceRefresh: Boolean): TokenResult {
        val user = auth?.currentUser ?: return TokenResult.Rejected
        return try {
            user.getIdToken(forceRefresh).await().token?.let { TokenResult.Ok(it) } ?: TokenResult.Offline
        } catch (_: FirebaseNetworkException) {
            TokenResult.Offline
        } catch (_: FirebaseAuthInvalidUserException) {
            TokenResult.Rejected
        } catch (_: FirebaseAuthInvalidCredentialsException) {
            TokenResult.Rejected
        } catch (_: Exception) {
            TokenResult.Offline
        }
    }

    /** The Google account the person picks, as a Firebase credential. Second: why not. */
    private suspend fun googleCredential(activity: Activity): Pair<AuthCredential?, AuthFailure?> {
        if (BuildConfig.GOOGLE_WEB_CLIENT_ID.isBlank()) return null to AuthFailure.NotConfigured
        val request = GetCredentialRequest.Builder().addCredentialOption(GetSignInWithGoogleOption.Builder(BuildConfig.GOOGLE_WEB_CLIENT_ID).build()).build()
        val credential = try {
            CredentialManager.create(activity).getCredential(activity, request).credential
        } catch (_: GetCredentialCancellationException) {
            return null to AuthFailure.Cancelled
        } catch (_: NoCredentialException) {
            return null to AuthFailure.NoGoogleAccount
        } catch (_: GetCredentialException) {
            return null to AuthFailure.Other
        }
        if (credential !is CustomCredential || credential.type != GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) return null to AuthFailure.Other
        return GoogleAuthProvider.getCredential(GoogleIdTokenCredential.createFrom(credential.data).idToken, null) to null
    }

    private suspend fun attempt(block: suspend () -> Unit): AuthFailure? = try {
        block()
        publish()
        null
    } catch (e: Exception) {
        failureOf(e)
    }

    override suspend fun signInWithGoogle(activity: Activity): AuthFailure? {
        val a = auth ?: return AuthFailure.NotConfigured
        val (credential, failure) = googleCredential(activity)
        return failure ?: attempt { a.signInWithCredential(credential!!).await() }
    }

    override suspend fun sendEmailLink(email: String): AuthFailure? {
        val a = auth ?: return AuthFailure.NotConfigured
        val settings = ActionCodeSettings.newBuilder()
            .setUrl("https://${BuildConfig.EMAIL_LINK_HOST}/finish-sign-in")
            .setHandleCodeInApp(true)
            .setAndroidPackageName(context.packageName, true, null)
            .apply { if (!BuildConfig.EMAIL_LINK_HOST.endsWith(".firebaseapp.com")) setLinkDomain(BuildConfig.EMAIL_LINK_HOST) }
            .build()
        return attempt { a.sendSignInLinkToEmail(email, settings).await() }
    }

    override fun isEmailLink(link: String): Boolean = auth?.isSignInWithEmailLink(link) == true

    override suspend fun completeEmailLink(email: String, link: String): AuthFailure? {
        val a = auth ?: return AuthFailure.NotConfigured
        return attempt { a.signInWithEmailLink(email, link).await() }
    }

    override suspend fun linkGoogle(activity: Activity): AuthFailure? {
        val user = auth?.currentUser ?: return AuthFailure.NotConfigured
        val (credential, failure) = googleCredential(activity)
        return failure ?: attempt { user.linkWithCredential(credential!!).await() }
    }

    override suspend fun linkEmail(email: String, link: String): AuthFailure? {
        val user = auth?.currentUser ?: return AuthFailure.NotConfigured
        return attempt { user.linkWithCredential(EmailAuthProvider.getCredentialWithLink(email, link)).await() }
    }

    override suspend fun reauthenticateWithGoogle(activity: Activity): AuthFailure? {
        val user = auth?.currentUser ?: return AuthFailure.NotConfigured
        val (credential, failure) = googleCredential(activity)
        // Firebase refuses a different Google account here, so this can't swap who is signed in.
        return failure ?: attempt { user.reauthenticate(credential!!).await() }
    }

    override suspend fun updateDisplayName(name: String): AuthFailure? {
        val user = auth?.currentUser ?: return AuthFailure.NotConfigured
        return attempt {
            user.updateProfile(UserProfileChangeRequest.Builder().setDisplayName(name).build()).await()
            user.getIdToken(true).await()
        }
    }

    override suspend fun signOut() {
        auth?.signOut()
        // So the next "Continue with Google" asks again instead of reusing this account.
        runCatching { CredentialManager.create(context).clearCredentialState(ClearCredentialStateRequest()) }
    }

    private fun failureOf(e: Exception): AuthFailure = when (e) {
        is FirebaseNetworkException -> AuthFailure.Network
        is FirebaseTooManyRequestsException -> AuthFailure.TooManyRequests
        is FirebaseAuthUserCollisionException -> AuthFailure.Collision
        is FirebaseAuthActionCodeException -> if (e.errorCode == "ERROR_EXPIRED_ACTION_CODE") AuthFailure.ExpiredLink else AuthFailure.InvalidLink
        is FirebaseAuthInvalidCredentialsException -> if (e.errorCode == "ERROR_INVALID_EMAIL") AuthFailure.WrongEmail else AuthFailure.InvalidLink
        is FirebaseAuthInvalidUserException -> AuthFailure.Other
        else -> AuthFailure.Other
    }
}

private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { c ->
    addOnCompleteListener { task ->
        if (!c.isActive) return@addOnCompleteListener
        val error = task.exception
        if (error != null) c.resumeWithException(error) else c.resume(task.result)
    }
}
