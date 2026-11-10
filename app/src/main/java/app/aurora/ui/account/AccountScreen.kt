package app.aurora.ui.account

import app.aurora.ui.components.PlusBadge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.aurora.AuroraApplication
import app.aurora.R
import app.aurora.account.ApiResult
import app.aurora.account.AuthFailure
import app.aurora.account.AuthState
import app.aurora.account.ReauthResult
import app.aurora.account.RemoteDevice
import app.aurora.account.afterDeletionRequested
import app.aurora.plan.Plan
import app.aurora.plan.openSubscriptionSettings
import app.aurora.ui.components.AuroraButton
import app.aurora.ui.components.AuroraDialog
import app.aurora.ui.components.AuroraIcons
import app.aurora.ui.components.AuroraRow
import app.aurora.ui.components.AuroraScreen
import app.aurora.ui.components.AuroraTextField
import app.aurora.ui.components.ButtonKind
import app.aurora.ui.components.Chevron
import app.aurora.ui.components.Notice
import app.aurora.ui.components.RowDivider
import app.aurora.ui.components.RowGroup
import app.aurora.ui.components.SectionLabel
import app.aurora.ui.theme.Aurora
import app.aurora.ui.theme.AuroraType
import app.aurora.ui.theme.Space
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

private sealed interface Sheet {
    data object Name : Sheet
    data object LinkEmail : Sheet
    data object SignOut : Sheet
    data object Delete : Sheet
    data object Reauth : Sheet
    data class RemoveDevice(val device: RemoteDevice) : Sheet
}

/** Settings › Account: who you are, how you sign in, your plan, your devices, and the way out. */
@Composable
fun AccountScreen(onBack: () -> Unit, onPlus: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as AuroraApplication
    val colors = Aurora.colors
    val auth by app.account.state.collectAsStateWithLifecycle()
    val remote by app.account.remote.collectAsStateWithLifecycle()
    val plan by app.billing.plan.collectAsStateWithLifecycle()
    val subscription by app.billing.subscription.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var sheet by remember { mutableStateOf<Sheet?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    val user = (auth as? AuthState.SignedIn)?.user ?: return
    val offline = stringResource(R.string.account_offline)
    val restored = stringResource(R.string.account_restore_done)
    val collision = stringResource(R.string.account_link_collision)
    val genericError = stringResource(R.string.auth_error_other)
    val reauthEmail = stringResource(R.string.account_reauth_email)

    LaunchedEffect(Unit) { app.account.sync() }

    AuroraScreen(title = stringResource(R.string.account_title), onBack = onBack) {
        message?.let { m -> item(key = "message") { Notice(stringResource(R.string.account_title), m, Modifier.padding(horizontal = Space.Gutter)) } }
        item(key = "profile") {
            SectionLabel(stringResource(R.string.account_profile))
            RowGroup {
                AuroraRow(
                    title = stringResource(R.string.account_name),
                    subtitle = user.displayName?.takeIf { it.isNotBlank() } ?: stringResource(R.string.account_name_unset),
                    onClick = { sheet = Sheet.Name }, trailing = { Chevron() },
                )
                RowDivider()
                AuroraRow(
                    title = stringResource(R.string.account_email),
                    subtitle = user.email.orEmpty(),
                    trailing = { if (user.emailVerified) Text(stringResource(R.string.account_email_verified), color = colors.accent, style = AuroraType.Secondary) },
                )
            }
        }
        item(key = "providers") {
            SectionLabel(stringResource(R.string.account_signin_methods))
            RowGroup {
                AuroraRow(
                    title = stringResource(R.string.account_provider_google),
                    onClick = if (user.hasGoogle) null else ({
                        scope.launch {
                            val activity = context.activity() ?: return@launch
                            val failure = app.account.identity.linkGoogle(activity)
                            message = when (failure) {
                                null, AuthFailure.Cancelled -> null
                                AuthFailure.Collision -> collision
                                AuthFailure.Network -> offline
                                else -> genericError
                            }
                        }
                    }),
                    trailing = { Text(stringResource(if (user.hasGoogle) R.string.account_provider_on else R.string.account_provider_add), color = if (user.hasGoogle) colors.accent else colors.textSecondary, style = AuroraType.Secondary) },
                )
                RowDivider()
                AuroraRow(
                    title = stringResource(R.string.account_provider_email),
                    onClick = if (user.hasEmail) null else ({ sheet = Sheet.LinkEmail }),
                    trailing = { Text(stringResource(if (user.hasEmail) R.string.account_provider_on else R.string.account_provider_add), color = if (user.hasEmail) colors.accent else colors.textSecondary, style = AuroraType.Secondary) },
                )
            }
        }
        item(key = "subscription") {
            SectionLabel(stringResource(R.string.account_subscription))
            RowGroup {
                AuroraRow(
                    title = stringResource(R.string.account_manage_plus),
                    subtitle = stringResource(if (plan == Plan.Plus) R.string.plus_name else R.string.settings_aurora_free),
                    icon = AuroraIcons.Lock, onClick = onPlus,
                    trailing = { if (plan == Plan.Plus) PlusBadge(); Chevron() },
                )
                RowDivider()
                AuroraRow(
                    title = stringResource(R.string.account_restore),
                    onClick = {
                        scope.launch {
                            runCatching { app.billing.refresh() }
                            message = if (app.account.sync()) restored else offline
                        }
                    },
                )
            }
        }
        item(key = "devices") {
            SectionLabel(stringResource(R.string.account_devices))
            val devices = remote?.devices
            RowGroup {
                if (devices == null) AuroraRow(title = stringResource(R.string.account_devices_empty))
                devices?.forEachIndexed { i, device ->
                    if (i > 0) RowDivider()
                    AuroraRow(
                        title = device.label?.takeIf { it.isNotBlank() } ?: stringResource(R.string.account_device_unknown),
                        subtitle = if (device.current) stringResource(R.string.account_device_this)
                        else stringResource(R.string.account_device_seen, DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(device.lastSeenAt))),
                        trailing = {
                            if (!device.current) AuroraButton(stringResource(R.string.account_device_remove), { sheet = Sheet.RemoveDevice(device) }, kind = ButtonKind.Quiet, compact = true)
                        },
                    )
                }
            }
        }
        item(key = "legal") {
            SectionLabel(stringResource(R.string.account_legal))
            RowGroup {
                AuroraRow(title = stringResource(R.string.auth_privacy), onClick = { openLegal(context, PrivacyPage) }, trailing = { Chevron() })
                RowDivider()
                AuroraRow(title = stringResource(R.string.auth_terms), onClick = { openLegal(context, TermsPage) }, trailing = { Chevron() })
            }
        }
        item(key = "leave") {
            Column(Modifier.padding(horizontal = Space.Gutter).padding(top = Space.L).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Space.XS)) {
                AuroraButton(stringResource(R.string.account_sign_out), { sheet = Sheet.SignOut }, Modifier.fillMaxWidth(), kind = ButtonKind.Secondary)
                AuroraButton(stringResource(R.string.account_delete), { sheet = Sheet.Delete }, Modifier.fillMaxWidth(), kind = ButtonKind.Quiet)
            }
        }
    }

    val close = { sheet = null }
    when (val s = sheet) {
        null -> Unit
        Sheet.Name -> {
            var name by remember { mutableStateOf(user.displayName.orEmpty()) }
            AuroraDialog(
                title = stringResource(R.string.account_name_edit), onDismiss = close,
                confirmLabel = stringResource(R.string.settings_done),
                onConfirm = {
                    close()
                    scope.launch {
                        val failure = app.account.identity.updateDisplayName(name.trim().take(80))
                        if (failure == null) app.account.sync() else message = if (failure == AuthFailure.Network) offline else genericError
                    }
                },
                dismissLabel = stringResource(R.string.action_cancel),
            ) { AuroraTextField(name, { name = it }, stringResource(R.string.account_name), Modifier.fillMaxWidth()) }
        }
        Sheet.LinkEmail -> {
            val address = user.email.orEmpty()
            val sentText = stringResource(R.string.account_link_email_sent, address)
            AuroraDialog(
                title = stringResource(R.string.account_link_email_title), onDismiss = close,
                confirmLabel = stringResource(R.string.auth_email_send),
                onConfirm = {
                    close()
                    scope.launch {
                        val failure = app.account.sendEmailLink(address, linking = true)
                        message = when (failure) {
                            null -> sentText
                            AuthFailure.Network -> offline
                            else -> genericError
                        }
                    }
                },
                body = address,
                dismissLabel = stringResource(R.string.action_cancel),
            )
        }
        Sheet.SignOut -> AuroraDialog(
            title = stringResource(R.string.account_sign_out_title), onDismiss = close,
            confirmLabel = stringResource(R.string.account_sign_out),
            onConfirm = { close(); app.scope.launch { app.account.signOut() } },
            body = stringResource(R.string.account_sign_out_body),
            dismissLabel = stringResource(R.string.action_cancel),
        )
        Sheet.Reauth -> AuroraDialog(
            title = stringResource(R.string.account_reauth_title), onDismiss = close,
            confirmLabel = stringResource(R.string.account_reauth_action),
            onConfirm = {
                close()
                scope.launch {
                    val activity = context.activity() ?: return@launch
                    message = when (app.account.reauthenticate(activity)) {
                        ReauthResult.Done -> null
                        ReauthResult.CheckEmail -> reauthEmail
                        ReauthResult.Failed -> genericError
                    }
                    app.account.clearEmailStep()
                }
            },
            body = stringResource(R.string.account_reauth_body),
            dismissLabel = stringResource(R.string.action_cancel),
        )
        Sheet.Delete -> AuroraDialog(
            title = stringResource(R.string.account_delete_title), onDismiss = close,
            confirmLabel = stringResource(R.string.account_delete_confirm),
            onConfirm = {
                close()
                app.scope.launch {
                    when (val r = app.account.requestDeletion()) {
                        is ApiResult.Ok -> app.saving { app.afterDeletionRequested() }
                        is ApiResult.Refused -> if (r.code == "reauth_required") sheet = Sheet.Reauth else message = genericError
                        ApiResult.Unreachable -> message = offline
                    }
                }
            },
            body = stringResource(R.string.account_delete_body),
            dismissLabel = stringResource(R.string.action_cancel),
        ) {
            AuroraButton(stringResource(R.string.account_delete_manage), { openSubscriptionSettings(context, subscription?.productId) }, Modifier.fillMaxWidth(), kind = ButtonKind.Quiet)
        }
        is Sheet.RemoveDevice -> AuroraDialog(
            title = stringResource(R.string.account_device_remove_title), onDismiss = close,
            confirmLabel = stringResource(R.string.account_device_remove),
            onConfirm = {
                close()
                scope.launch { if (!app.account.removeDevice(s.device.id)) message = offline }
            },
            body = stringResource(R.string.account_device_remove_body),
            dismissLabel = stringResource(R.string.action_cancel),
        )
    }
}
