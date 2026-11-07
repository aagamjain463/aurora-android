package app.aurora.ui.protect

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.aurora.R
import app.aurora.data.Access
import app.aurora.data.AccessState
import app.aurora.data.ProtectionStatus
import app.aurora.data.openAccessSettings
import app.aurora.data.protectionStatus
import app.aurora.protect.ProtectedList
import app.aurora.protect.ShieldService
import app.aurora.protect.ShieldTrace
import app.aurora.rememberAccess
import app.aurora.ui.components.AuroraButton
import app.aurora.ui.components.AuroraDialog
import app.aurora.ui.components.ButtonKind
import app.aurora.ui.components.auroraSurface
import app.aurora.ui.components.ModeGlyph
import app.aurora.ui.theme.Aurora
import app.aurora.ui.theme.AuroraType
import app.aurora.ui.theme.Radius
import app.aurora.ui.theme.Space

/**
 * Whether Shield can protect right now, from Android's real state: the accessibility switch (re-checked
 * on every resume) and the live service connection, never from a stored "should be on". Null while
 * [list] is loading.
 */
@Composable
fun rememberProtectionStatus(list: ProtectedList?): ProtectionStatus? {
    val access = rememberAccess(Access.Accessibility)
    val connected by ShieldService.connected.collectAsStateWithLifecycle()
    if (list == null) return null
    // ponytail: Blocked (Advanced Protection, restricted settings) isn't detected yet; it reads as NeedsPermission.
    return protectionStatus(list.items.isNotEmpty(), blocked = false, access, connected, list.readable)
}

/** A short line for Today and Settings. */
@Composable
fun ProtectionStatus.summary(): String = stringResource(
    when (this) {
        ProtectionStatus.Off -> R.string.status_off_short
        ProtectionStatus.Active -> R.string.status_active_short
        ProtectionStatus.NeedsPermission -> R.string.status_permission_short
        ProtectionStatus.NotRunning -> R.string.status_not_running_short
        ProtectionStatus.ConfigError -> R.string.status_config_short
        ProtectionStatus.Blocked -> R.string.status_blocked_short
    },
)

/** True on Xiaomi, Redmi and POCO phones, which stop background apps and their services aggressively. */
val isXiaomi: Boolean get() = Build.MANUFACTURER.equals("xiaomi", ignoreCase = true) || Build.BRAND.lowercase() in setOf("xiaomi", "redmi", "poco")

/** The Protect tab's status card: the truth about Shield, and the one step that fixes it. */
@Composable
fun ShieldStatusCard(status: ProtectionStatus, count: Int, onTurnOn: () -> Unit, onOpenSettings: () -> Unit) {
    val colors = Aurora.colors
    val context = androidx.compose.ui.platform.LocalContext.current
    val (title, body) = when (status) {
        ProtectionStatus.Off -> stringResource(R.string.status_off_title) to stringResource(R.string.status_off_body)
        ProtectionStatus.Active -> stringResource(R.string.status_active_title) to stringResource(R.string.status_active_body)
        ProtectionStatus.NeedsPermission -> stringResource(R.string.status_permission_title) to stringResource(R.string.status_permission_body)
        ProtectionStatus.NotRunning -> stringResource(R.string.status_not_running_title) to
            stringResource(if (isXiaomi) R.string.status_not_running_body_xiaomi else R.string.status_not_running_body)
        ProtectionStatus.ConfigError -> stringResource(R.string.status_config_title) to stringResource(R.string.status_config_body)
        ProtectionStatus.Blocked -> stringResource(R.string.status_blocked_title) to stringResource(R.string.status_blocked_body)
    }
    val good = status == ProtectionStatus.Active || status == ProtectionStatus.Off
    Column(
        Modifier
            .padding(horizontal = Space.Gutter)
            .fillMaxWidth()
            .auroraSurface(colors, warm = !good)
            .padding(Space.L),
        verticalArrangement = Arrangement.spacedBy(Space.XS),
    ) {
        if (!good) app.aurora.ui.components.LitBadge(app.aurora.ui.components.AuroraIcons.Protect, size = 44, tint = colors.textSecondary)
        Text(title, Modifier.semantics { heading() }, color = colors.textPrimary, style = if (good) AuroraType.Title else AuroraType.Headline)
        Text(body, color = if (good) colors.textSecondary else colors.textPrimary, style = if (good) AuroraType.Secondary else AuroraType.Body)
        when (status) {
            ProtectionStatus.NeedsPermission -> AuroraButton(stringResource(R.string.status_turn_on), onTurnOn, Modifier.padding(top = Space.XS).fillMaxWidth(), kind = ButtonKind.Secondary)
            ProtectionStatus.NotRunning -> {
                AuroraButton(stringResource(R.string.status_open_accessibility), onOpenSettings, Modifier.padding(top = Space.XS).fillMaxWidth(), kind = ButtonKind.Secondary)
                if (isXiaomi) AuroraButton(stringResource(R.string.keep_running_autostart), { openAutostart(context) }, Modifier.fillMaxWidth(), kind = ButtonKind.Secondary)
            }
            else -> Unit
        }
    }
}

/** Whether [ShieldGate] has something to say. */
@Composable
fun shieldGateNeeded(hasSites: Boolean): Boolean {
    val connected by ShieldService.connected.collectAsStateWithLifecycle()
    return rememberAccess(Access.Accessibility) != AccessState.Granted || !connected || (hasSites && rememberAccess(Access.Content) != AccessState.Granted)
}

/**
 * Before anything starts: what would stop it being enforced, and the one step that fixes it. Screens
 * refuse to start without the Accessibility switch; a service still connecting only gets a note, since a
 * fresh process reads as not connected for a moment.
 */
@Composable
fun ShieldGate(hasSites: Boolean, onOpenWeb: () -> Unit) {
    val colors = Aurora.colors
    val context = androidx.compose.ui.platform.LocalContext.current
    val access = rememberAccess(Access.Accessibility)
    val connected by ShieldService.connected.collectAsStateWithLifecycle()
    val content = rememberAccess(Access.Content)
    var disclosure by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(Space.S)) {
        if (access != AccessState.Granted) GateCard(R.string.status_permission_title, R.string.status_permission_body, R.string.status_turn_on, primary = true) { disclosure = true }
        else if (!connected) GateCard(R.string.status_not_running_title, if (isXiaomi) R.string.status_not_running_body_xiaomi else R.string.status_not_running_body, R.string.status_open_accessibility, onAction = { openAccessSettings(context, Access.Accessibility) })
        if (hasSites && content != AccessState.Granted) GateCard(R.string.gate_sites_title, R.string.gate_sites_body, R.string.web_turn_on, onAction = onOpenWeb)
    }
    if (disclosure) ShieldDisclosure(onContinue = { disclosure = false; openAccessSettings(context, Access.Accessibility) }, onDismiss = { disclosure = false })
}

@Composable
private fun GateCard(title: Int, body: Int, action: Int, primary: Boolean = false, onAction: () -> Unit) {
    val colors = Aurora.colors
    Column(Modifier.fillMaxWidth().auroraSurface(colors, warm = true).padding(horizontal = 22.dp, vertical = 20.dp), verticalArrangement = Arrangement.spacedBy(Space.XS)) {
        Text(stringResource(title), Modifier.semantics { heading() }, color = colors.textPrimary, style = AuroraType.Title)
        Text(stringResource(body), color = colors.textSecondary, style = AuroraType.Secondary)
        AuroraButton(stringResource(action), onAction, Modifier.padding(top = Space.XS).fillMaxWidth(), kind = if (primary) ButtonKind.Primary else ButtonKind.Secondary, compact = true)
    }
}

/** Why something wasn't added, from [ProtectModel.notice], with the way to Plus where it applies. */
@Composable
fun LimitNoticeDialog(vm: ProtectModel, onPlus: (() -> Unit)?) {
    val notice by vm.notice.collectAsStateWithLifecycle()
    notice?.let { n ->
        val plus = onPlus.takeIf { n.plus }
        AuroraDialog(
            n.title, vm::dismissNotice,
            stringResource(if (plus != null) R.string.plus_see else R.string.action_ok),
            { vm.dismissNotice(); plus?.invoke() },
            n.body,
            if (plus != null) stringResource(R.string.action_not_now) else null,
        )
    }
}

/** Xiaomi's Autostart list, or aurora's App info where that screen doesn't exist. */
fun openAutostart(context: Context) {
    val autostart = Intent().setComponent(ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        context.startActivity(autostart)
    } catch (_: RuntimeException) {
        openAppInfo(context)
    }
}

/** aurora's App info screen, where battery restrictions are set. */
fun openAppInfo(context: Context) {
    try {
        context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: ActivityNotFoundException) {
        context.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

/**
 * The prominent disclosure shown before aurora sends the person to Accessibility settings. It says what
 * the service reads, what it doesn't, where data goes, and how to turn it off. Play's declaration uses
 * the same words (docs/SHIELD.md).
 */
@Composable
fun ShieldDisclosure(onContinue: () -> Unit, onDismiss: () -> Unit) {
    AuroraDialog(
        title = stringResource(R.string.disclosure_title),
        body = stringResource(R.string.disclosure_body),
        confirmLabel = stringResource(R.string.disclosure_confirm),
        onConfirm = onContinue,
        dismissLabel = stringResource(R.string.action_not_now),
        onDismiss = onDismiss,
    )
}

/**
 * Shield diagnostics: each link of the chain, from permission to a Shield actually on screen, as a
 * checklist, so a failure shows exactly where it is. Counts come from this process only and are never
 * sent anywhere.
 */
@Composable
fun ShieldDiagnostics(trace: ShieldTrace.Snapshot, onDismiss: () -> Unit) {
    val colors = Aurora.colors
    val access = rememberAccess(Access.Accessibility)
    val connected by ShieldService.connected.collectAsStateWithLifecycle()
    val granted = access == AccessState.Granted
    val requested = trace.shields + trace.failures
    // (title, value, ok): ok stays null until the link before it has been reached.
    val steps = listOf(
        Triple(stringResource(R.string.diag_permission), stringResource(if (granted) R.string.settings_allowed else R.string.intent_off), granted),
        Triple(stringResource(R.string.diag_service), stringResource(if (connected) R.string.diag_connected else R.string.diag_not_connected), connected),
        Triple(stringResource(R.string.diag_events), "${trace.events}", if (connected) trace.events > 0 else null),
        Triple(stringResource(R.string.diag_watched), "${trace.watched}", if (trace.events > 0) trace.watched > 0 else null),
        Triple(
            stringResource(R.string.diag_shown),
            if (trace.failures > 0) stringResource(R.string.diag_shown_refused, trace.shields, trace.failures) else "${trace.shields}",
            if (trace.restricted > 0) requested > 0 && trace.failures == 0 else null,
        ),
    )
    val broken = steps.firstOrNull { it.third == false }
    val summary = when {
        broken != null -> stringResource(R.string.diag_broken, broken.first)
        steps.any { it.third == null } -> stringResource(R.string.diag_waiting)
        else -> stringResource(R.string.diag_ok)
    }
    AuroraDialog(
        title = stringResource(R.string.diagnostics_title),
        body = summary,
        confirmLabel = stringResource(R.string.action_close),
        onConfirm = onDismiss,
        onDismiss = onDismiss,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(Space.M)) {
            steps.forEach { (title, value, ok) ->
                app.aurora.ui.components.FactRow(
                    icon = when (ok) {
                        true -> app.aurora.ui.components.AuroraIcons.Check
                        false -> app.aurora.ui.components.AuroraIcons.Close
                        null -> app.aurora.ui.components.AuroraIcons.Clock
                    },
                    title = title,
                    tint = when (ok) {
                        true -> colors.accent
                        false -> colors.danger
                        null -> colors.textTertiary
                    },
                ) { Text(value, color = colors.textSecondary, style = AuroraType.Secondary) }
            }
            val speed = trace.exposure?.let { stringResource(R.string.diag_speed, it.first) }
            Text(
                listOfNotNull(speed, "${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE}", stringResource(R.string.diagnostics_note)).joinToString("\n"),
                Modifier.padding(top = Space.XS), color = colors.textTertiary, style = AuroraType.Caption,
            )
        }
    }
}
