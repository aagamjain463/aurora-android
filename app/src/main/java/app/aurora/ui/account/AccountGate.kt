package app.aurora.ui.account

import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.asPaddingValues
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.aurora.AuroraApplication
import app.aurora.BuildConfig
import app.aurora.R
import app.aurora.account.AuthFailure
import app.aurora.account.AuthState
import app.aurora.account.AuthUser
import app.aurora.account.EmailStep
import app.aurora.account.ProfileState
import app.aurora.account.SessionEnd
import app.aurora.account.bindProfile
import app.aurora.account.decideLocalData
import app.aurora.account.looksLikeEmail
import app.aurora.ui.components.ArtTier
import app.aurora.ui.components.AuroraButton
import app.aurora.ui.components.AuroraDialog
import app.aurora.ui.components.AuroraLogo
import app.aurora.ui.components.AuroraTextField
import app.aurora.ui.components.ButtonKind
import app.aurora.ui.components.ContentMaxWidth
import app.aurora.ui.components.LoadingSurface
import app.aurora.ui.components.Notice
import app.aurora.ui.components.TwoToneHeadline
import app.aurora.ui.onboarding.Welcome
import app.aurora.ui.theme.Aurora
import app.aurora.ui.theme.AuroraType
import app.aurora.ui.theme.Space
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

internal tailrec fun Context.activity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.activity()
    else -> null
}

/** Terms and privacy live on the web, where they can be read without the app too. */
internal fun openLegal(context: Context, page: String) {
    if (BuildConfig.LEGAL_BASE_URL.isBlank()) return
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, "${BuildConfig.LEGAL_BASE_URL.trimEnd('/')}/$page".toUri())) }
}

const val TermsPage = "terms.html"
const val PrivacyPage = "privacy-policy.html"

/**
 * Nothing in [content] exists until a verified account is signed in and this phone's data is bound to it. There is no
 * route around this: the nav graph isn't composed behind it, so no deep link or back stack can reach a screen.
 */
@Composable
fun AccountGate(firstRunOnboarded: Boolean, content: @Composable (onboarded: Boolean) -> Unit) {
    val app = LocalContext.current.applicationContext as AuroraApplication
    val auth by app.account.state.collectAsStateWithLifecycle()
    val profile by app.account.profile.collectAsStateWithLifecycle()
    val remote by app.account.remote.collectAsStateWithLifecycle()
    when (val a = auth) {
        AuthState.Loading -> LoadingSurface()
        AuthState.SignedOut -> SignInFlow(showWelcome = !firstRunOnboarded)
        is AuthState.SignedIn -> when {
            profile == ProfileState.NeedsDecision -> KeepYourSetup(a.user)
            profile == ProfileState.Failed -> BindFailed(a.user)
            profile != ProfileState.Ready -> LoadingSurface()
            remote?.account?.deletionRequestedAt != null -> DeletionScheduled(remote!!.account.deletionRequestedAt!!)
            else -> key(a.user.uid) {
                // Read fresh: this account's data has only just been put in place.
                val onboarded by produceState<Boolean?>(null) { value = app.preferences.preferences.first().onboarded }
                onboarded?.let { content(it) } ?: LoadingSurface()
            }
        }
    }
}

@Composable
private fun GatePage(tier: ArtTier = ArtTier.Immersive, content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().background(Aurora.colors.background)) {
        Column(
            Modifier.align(Alignment.TopCenter).widthIn(max = ContentMaxWidth).fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()
                .verticalScroll(rememberScrollState()).padding(horizontal = Space.Gutter, vertical = Space.M),
            verticalArrangement = Arrangement.spacedBy(Space.S),
        ) { content() }
    }
}

private enum class Step { Welcome, Login, Email }

@Composable
private fun SignInFlow(showWelcome: Boolean) {
    val app = LocalContext.current.applicationContext as AuroraApplication
    var step by rememberSaveable { mutableStateOf(if (showWelcome) Step.Welcome else Step.Login) }
    val email by app.account.email.collectAsStateWithLifecycle()
    BackHandler(enabled = step == Step.Email && email !is EmailStep.Completing) {
        app.account.changeEmail()
        step = Step.Login
    }
    when {
        // A link arrived: whatever screen this was, it is about that link now.
        email !is EmailStep.Idle -> EmailFlow(email) { step = Step.Login }
        step == Step.Welcome -> WelcomeStep {
            app.scope.launch { runCatching { app.preferences.setOnboardingStep(1) } }
            step = Step.Login
        }
        step == Step.Login -> Login { step = Step.Email }
        else -> EnterEmail(null) { step = Step.Login }
    }
}

@Composable
private fun WelcomeStep(onBegin: () -> Unit) {
    val colors = Aurora.colors
    val navInset = androidx.compose.foundation.layout.WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    Box(Modifier.fillMaxSize().background(colors.sky)) {
        Column(Modifier.align(Alignment.TopCenter).widthIn(max = ContentMaxWidth).fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            Box(Modifier.weight(1f)) { Welcome() }
            Column(Modifier.padding(horizontal = Space.Gutter).padding(top = 34.dp, bottom = (58.dp - navInset).coerceAtLeast(Space.M))) {
                AuroraButton(stringResource(R.string.action_continue), onBegin, Modifier.fillMaxWidth())
                Text(
                    stringResource(R.string.onb_welcome_privacy), Modifier.fillMaxWidth().padding(top = 16.dp),
                    color = colors.textTertiary, style = app.aurora.ui.theme.AuroraType.Caption, textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }
        }
    }
}

@Composable
internal fun failureText(f: AuthFailure): String = stringResource(
    when (f) {
        AuthFailure.Network -> R.string.auth_error_network
        AuthFailure.NoGoogleAccount -> R.string.auth_error_no_google
        AuthFailure.TooManyRequests -> R.string.auth_error_rate
        AuthFailure.NotConfigured -> R.string.auth_not_configured
        AuthFailure.WrongEmail -> R.string.auth_email_invalid
        AuthFailure.ExpiredLink -> R.string.auth_link_expired_body
        AuthFailure.InvalidLink -> R.string.auth_link_invalid_body
        AuthFailure.Collision -> R.string.account_link_collision
        AuthFailure.Cancelled, AuthFailure.Other -> R.string.auth_error_other
    },
)

@Composable
internal fun Login(onEmail: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as AuroraApplication
    val colors = Aurora.colors
    val scope = rememberCoroutineScope()
    val ended by app.account.sessionEnded.collectAsStateWithLifecycle()
    var failure by remember { mutableStateOf<AuthFailure?>(null) }
    var busy by remember { mutableStateOf(false) }
    var recover by remember { mutableStateOf(false) }
    val available = app.account.identity.configured
    GatePage {
        Spacer(Modifier.height(Space.M))
        AuroraLogo(104.dp)
        Spacer(Modifier.height(Space.L))
        TwoToneHeadline(stringResource(R.string.today_headline))
        Text(stringResource(R.string.auth_login_body), color = colors.textSecondary, style = AuroraType.Body)
        Spacer(Modifier.height(Space.M))
        ended?.let { Notice(stringResource(R.string.account_title), stringResource(if (it == SessionEnd.DeviceRemoved) R.string.auth_device_removed else R.string.auth_session_expired)) }
        if (!available) Notice(stringResource(R.string.account_title), stringResource(R.string.auth_not_configured))
        failure?.takeUnless { it == AuthFailure.Cancelled }?.let { Text(failureText(it), color = colors.danger, style = AuroraType.Secondary) }
        AuroraButton(
            stringResource(R.string.auth_continue_google),
            {
                val activity = context.activity() ?: return@AuroraButton
                busy = true
                failure = null
                app.account.clearSessionEnded()
                scope.launch {
                    failure = app.account.identity.signInWithGoogle(activity)
                    busy = false
                }
            },
            Modifier.fillMaxWidth(), enabled = available && !busy,
        )
        AuroraButton(stringResource(R.string.auth_continue_email), { app.account.clearSessionEnded(); onEmail() }, Modifier.fillMaxWidth(), kind = ButtonKind.Secondary, enabled = available && !busy)
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
            AuroraButton(stringResource(R.string.auth_terms), { openLegal(context, TermsPage) }, kind = ButtonKind.Quiet, compact = true)
            AuroraButton(stringResource(R.string.auth_privacy), { openLegal(context, PrivacyPage) }, kind = ButtonKind.Quiet, compact = true)
        }
        AuroraButton(stringResource(R.string.auth_trouble), { recover = true }, Modifier.fillMaxWidth(), kind = ButtonKind.Quiet)
    }
    if (recover) RecoverDialog { recover = false }
}

@Composable
private fun RecoverDialog(onDismiss: () -> Unit) {
    AuroraDialog(
        title = stringResource(R.string.auth_recover_title), onDismiss = onDismiss, confirmLabel = stringResource(R.string.action_close), onConfirm = onDismiss,
        body = stringResource(R.string.auth_recover_body, BuildConfig.SUPPORT_EMAIL),
    )
}

@Composable
private fun EnterEmail(prefill: String?, onBack: () -> Unit) {
    val app = LocalContext.current.applicationContext as AuroraApplication
    val colors = Aurora.colors
    val scope = rememberCoroutineScope()
    var address by rememberSaveable { mutableStateOf(prefill.orEmpty()) }
    var invalid by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    fun send() {
        if (!looksLikeEmail(address.trim())) {
            invalid = true
            return
        }
        busy = true
        scope.launch {
            app.account.sendEmailLink(address)
            busy = false
        }
    }
    GatePage(ArtTier.Balanced) {
        Spacer(Modifier.heightIn(min = 96.dp))
        Text(stringResource(R.string.auth_email_title), Modifier.semantics { heading() }, color = colors.textPrimary, style = AuroraType.DisplayS)
        Text(stringResource(R.string.auth_email_body), color = colors.textSecondary, style = AuroraType.Body)
        AuroraTextField(
            address, { address = it; invalid = false }, stringResource(R.string.auth_email_label), Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Done, autoCorrectEnabled = false), onDone = ::send,
        )
        if (invalid) Text(stringResource(R.string.auth_email_invalid), color = colors.danger, style = AuroraType.Secondary)
        AuroraButton(stringResource(R.string.auth_email_send), ::send, Modifier.fillMaxWidth(), enabled = !busy && address.isNotBlank())
        AuroraButton(stringResource(R.string.action_back_short), onBack, Modifier.fillMaxWidth(), kind = ButtonKind.Quiet)
    }
}

@Composable
private fun EmailFlow(step: EmailStep, onBack: () -> Unit) {
    val app = LocalContext.current.applicationContext as AuroraApplication
    val colors = Aurora.colors
    val scope = rememberCoroutineScope()
    when (step) {
        EmailStep.Idle -> Unit
        is EmailStep.Sent -> {
            var recover by remember { mutableStateOf(false) }
            var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
            LaunchedEffect(step.sentAt) {
                while (true) {
                    now = System.currentTimeMillis()
                    delay(1_000)
                }
            }
            val wait = ((step.sentAt + ResendAfterMs - now + 999) / 1000).coerceAtLeast(0).toInt()
            GatePage(ArtTier.Balanced) {
                Spacer(Modifier.heightIn(min = 96.dp))
                Text(stringResource(R.string.auth_inbox_title), Modifier.semantics { heading() }, color = colors.textPrimary, style = AuroraType.DisplayS)
                Text(stringResource(R.string.auth_inbox_body, step.email), color = colors.textSecondary, style = AuroraType.Body)
                AuroraButton(
                    if (wait > 0) stringResource(R.string.auth_inbox_resend_wait, wait) else stringResource(R.string.auth_inbox_resend),
                    { scope.launch { app.account.sendEmailLink(step.email) } },
                    Modifier.fillMaxWidth(), kind = ButtonKind.Secondary, enabled = wait == 0,
                )
                AuroraButton(stringResource(R.string.auth_inbox_change), { app.account.changeEmail() }, Modifier.fillMaxWidth(), kind = ButtonKind.Quiet)
                AuroraButton(stringResource(R.string.auth_inbox_recover), { recover = true }, Modifier.fillMaxWidth(), kind = ButtonKind.Quiet)
            }
            if (recover) RecoverDialog { recover = false }
        }
        is EmailStep.NeedsAddress -> {
            var address by rememberSaveable { mutableStateOf("") }
            GatePage(ArtTier.Balanced) {
                Spacer(Modifier.heightIn(min = 96.dp))
                Text(stringResource(R.string.auth_address_title), Modifier.semantics { heading() }, color = colors.textPrimary, style = AuroraType.DisplayS)
                Text(stringResource(R.string.auth_address_body), color = colors.textSecondary, style = AuroraType.Body)
                AuroraTextField(address, { address = it }, stringResource(R.string.auth_email_label), Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Done, autoCorrectEnabled = false))
                AuroraButton(stringResource(R.string.auth_address_continue), { scope.launch { app.account.finishLinkWithAddress(address, step.link) } }, Modifier.fillMaxWidth(), enabled = address.isNotBlank())
            }
        }
        EmailStep.Completing, EmailStep.Verified -> GatePage(ArtTier.Balanced) {
            Spacer(Modifier.heightIn(min = 96.dp))
            Text(
                stringResource(if (step == EmailStep.Verified) R.string.auth_verified_title else R.string.auth_verifying),
                Modifier.semantics { heading() }, color = colors.textPrimary, style = AuroraType.DisplayS,
            )
            if (step == EmailStep.Verified) Text(stringResource(R.string.auth_verified_body), color = colors.textSecondary, style = AuroraType.Body)
        }
        is EmailStep.Failed -> GatePage(ArtTier.Balanced) {
            Spacer(Modifier.heightIn(min = 96.dp))
            val title = when (step.failure) {
                AuthFailure.ExpiredLink -> R.string.auth_link_expired_title
                AuthFailure.InvalidLink, AuthFailure.WrongEmail -> R.string.auth_link_invalid_title
                else -> R.string.auth_email_title
            }
            Text(stringResource(title), Modifier.semantics { heading() }, color = colors.textPrimary, style = AuroraType.DisplayS)
            Text(failureText(step.failure), color = colors.textSecondary, style = AuroraType.Body)
            step.email?.takeIf { looksLikeEmail(it) }?.let { address ->
                AuroraButton(stringResource(R.string.auth_link_new), { scope.launch { app.account.sendEmailLink(address) } }, Modifier.fillMaxWidth())
            }
            AuroraButton(stringResource(R.string.auth_inbox_change), { app.account.changeEmail(); onBack() }, Modifier.fillMaxWidth(), kind = ButtonKind.Quiet)
        }
    }
}

/** The one-time question for a phone that was already set up before accounts. */
@Composable
private fun KeepYourSetup(user: AuthUser) {
    val app = LocalContext.current.applicationContext as AuroraApplication
    val colors = Aurora.colors
    var busy by remember { mutableStateOf(false) }
    fun decide(keep: Boolean) {
        busy = true
        app.scope.launch { app.saving { app.decideLocalData(user.uid, keep) }; busy = false }
    }
    GatePage(ArtTier.Balanced) {
        Spacer(Modifier.heightIn(min = 96.dp))
        Text(stringResource(R.string.migrate_title), Modifier.semantics { heading() }, color = colors.textPrimary, style = AuroraType.DisplayS)
        Text(stringResource(R.string.migrate_body, user.email.orEmpty()), color = colors.textSecondary, style = AuroraType.Body)
        Text(stringResource(R.string.migrate_stays), color = colors.textSecondary, style = AuroraType.Secondary)
        AuroraButton(stringResource(R.string.migrate_keep), { decide(true) }, Modifier.fillMaxWidth(), enabled = !busy)
        AuroraButton(stringResource(R.string.migrate_fresh), { decide(false) }, Modifier.fillMaxWidth(), kind = ButtonKind.Quiet, enabled = !busy)
        Text(stringResource(R.string.migrate_fresh_note), color = colors.textTertiary, style = AuroraType.Caption)
    }
}

@Composable
private fun BindFailed(user: AuthUser) {
    val app = LocalContext.current.applicationContext as AuroraApplication
    val colors = Aurora.colors
    var busy by remember { mutableStateOf(false) }
    GatePage(ArtTier.Balanced) {
        Spacer(Modifier.height(Space.L))
        Text(stringResource(R.string.account_title), Modifier.semantics { heading() }, color = colors.textPrimary, style = AuroraType.DisplayS)
        Text(stringResource(R.string.account_bind_failed), color = colors.textSecondary, style = AuroraType.Body)
        Spacer(Modifier.height(Space.M))
        AuroraButton(
            stringResource(R.string.action_retry),
            {
                busy = true
                app.scope.launch {
                    app.account.setProfile(ProfileState.Checking)
                    runCatching { app.bindProfile(user.uid) }.onFailure { app.account.setProfile(ProfileState.Failed) }
                    busy = false
                }
            },
            Modifier.fillMaxWidth(),
            enabled = !busy,
        )
        AuroraButton(
            stringResource(R.string.account_sign_out),
            { app.scope.launch { app.account.signOut() } },
            Modifier.fillMaxWidth(),
            kind = ButtonKind.Quiet,
            compact = true,
        )
    }
}

@Composable
private fun DeletionScheduled(requestedAt: Long) {
    val app = LocalContext.current.applicationContext as AuroraApplication
    val colors = Aurora.colors
    val scope = rememberCoroutineScope()
    var failed by remember { mutableStateOf(false) }
    val date = DateFormat.getDateInstance(DateFormat.LONG).format(Date(requestedAt + DeletionGraceMs))
    GatePage(ArtTier.Balanced) {
        Spacer(Modifier.heightIn(min = 96.dp))
        Text(stringResource(R.string.deleting_title), Modifier.semantics { heading() }, color = colors.textPrimary, style = AuroraType.DisplayS)
        Text(stringResource(R.string.deleting_body, date), color = colors.textSecondary, style = AuroraType.Body)
        if (failed) Text(stringResource(R.string.account_offline), color = colors.danger, style = AuroraType.Secondary)
        AuroraButton(stringResource(R.string.deleting_keep), { scope.launch { failed = !app.account.cancelDeletion() } }, Modifier.fillMaxWidth())
        AuroraButton(stringResource(R.string.deleting_continue), { scope.launch { app.account.signOut() } }, Modifier.fillMaxWidth(), kind = ButtonKind.Quiet)
    }
}

/** Over everything while the server decides a commitment. It can't be dismissed: the answer comes within seconds. */
@Composable
fun ConfirmingDialog() {
    AuroraDialog(
        title = stringResource(R.string.confirming_title), onDismiss = { }, confirmLabel = "", onConfirm = { },
        body = stringResource(R.string.confirming_body),
    )
}

private const val ResendAfterMs = 60_000L
private const val DeletionGraceMs = 7 * 86_400_000L
