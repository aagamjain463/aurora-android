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
import app.aurora.ui.components.glassCard
import androidx.compose.ui.text.withStyle
import app.aurora.ui.components.glassFill
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
            .padding(horizontal = Space.Inner, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(Space.XS),
    ) {
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

/** The permissions [ShieldGate] would still ask for: only the ones not yet allowed. */
@Composable
private fun gateMissing(hasSites: Boolean): List<Access> = listOfNotNull(
    Access.Accessibility.takeIf { rememberAccess(it) != AccessState.Granted },
    Access.Content.takeIf { hasSites && rememberAccess(it) != AccessState.Granted },
)

/** Whether [ShieldGate] has something to say. */
@Composable
fun shieldGateNeeded(hasSites: Boolean): Boolean = gateMissing(hasSites).isNotEmpty()

/** Before anything starts: only the permissions still off that it needs, in the one permission card. */
@Composable
fun ShieldGate(hasSites: Boolean) {
    val missing = gateMissing(hasSites)
    if (missing.isNotEmpty()) PermissionCard(missing)
}

/** How a permission is named and why, in a few words, wherever it is asked for. */
val Access.icon: androidx.compose.ui.graphics.vector.ImageVector
    get() = when (this) {
        Access.Accessibility -> app.aurora.ui.components.AuroraIcons.Protect
        Access.Content -> app.aurora.ui.components.AuroraIcons.Globe
        Access.Usage -> app.aurora.ui.components.AuroraIcons.Insights
        Access.Notifications -> app.aurora.ui.components.AuroraIcons.Bell
    }

@Composable
fun Access.name(): String = when (this) {
    Access.Accessibility -> stringResource(R.string.onb_shield_eyebrow)
    Access.Content -> stringResource(R.string.onb_permission_web_title)
    else -> title
}

@Composable
fun Access.line(): String = stringResource(
    when (this) {
        Access.Accessibility -> R.string.onb_permission_apps
        Access.Content -> R.string.onb_permission_web
        Access.Usage -> R.string.onb_permission_usage
        Access.Notifications -> R.string.onb_permission_notifications
    },
)

/** What a permission's disclosure says: one or two short sentences, what is read, why, and that it stays on the phone. */
private val Access.disclosure: Int
    get() = when (this) {
        Access.Accessibility -> R.string.perm_ask_accessibility
        Access.Content -> R.string.perm_ask_content
        Access.Usage -> R.string.perm_ask_usage
        Access.Notifications -> R.string.perm_ask_notifications
    }

/**
 * Asking for a permission, everywhere: first a short disclosure (Google Play's prominent disclosure: what is read,
 * why, that it stays on the phone), then on Continue the switch itself. Notifications are asked in place once on
 * Android 13+; after that, and for everything else, the switch lives in Settings.
 */
@Composable
fun rememberPermissionRequest(): (Access) -> Unit {
    val context = androidx.compose.ui.platform.LocalContext.current
    var asked by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(false) }
    var asking by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf<Access?>(null) }
    val launcher = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { }
    asking?.let { access ->
        val viaAccessibility = access == Access.Accessibility || access == Access.Content
        PermissionAsk(
            stringResource(R.string.perm_ask_title, access.name()), stringResource(access.disclosure), access.icon, onDismiss = { asking = null },
            confirm = stringResource(if (viaAccessibility) R.string.disclosure_confirm else R.string.action_continue),
            guide = if (viaAccessibility) ({ SettingsPath(access) }) else null,
        ) {
            asking = null
            if (access == Access.Notifications && Build.VERSION.SDK_INT >= 33 && !asked) {
                asked = true
                runCatching { launcher.launch(android.Manifest.permission.POST_NOTIFICATIONS) }.onFailure { openAccessSettings(context, access) }
            } else openAccessSettings(context, access)
        }
    }
    return { asking = it }
}

/** A tap that asks for [access]: its disclosure first, then its switch. */
@Composable
fun askFor(access: Access): () -> Unit {
    val ask = rememberPermissionRequest()
    return { ask(access) }
}

/** Uninstall protection's disclosure, then Android's own device admin screen through [launch]. */
@Composable
fun rememberUninstallRequest(launch: () -> Unit): () -> Unit {
    var asking by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(false) }
    if (asking) PermissionAsk(
        stringResource(R.string.perm_ask_title, stringResource(R.string.strict_admin_title)), stringResource(R.string.perm_ask_uninstall),
        app.aurora.ui.components.AuroraIcons.Lock, onDismiss = { asking = false },
    ) { asking = false; launch() }
    return { asking = true }
}

/** The one disclosure dialog: an icon, "Allow …", one short line, Continue or Not now. */
@Composable
private fun PermissionAsk(
    title: String, body: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onDismiss: () -> Unit,
    confirm: String = stringResource(R.string.action_continue), guide: (@Composable () -> Unit)? = null, onContinue: () -> Unit,
) {
    AuroraDialog(
        title = title, onDismiss = onDismiss,
        confirmLabel = confirm, onConfirm = onContinue,
        body = body, dismissLabel = stringResource(R.string.action_not_now), icon = icon, content = guide,
    )
}

/**
 * Where the switch is, before Accessibility settings opens: Android lists apps from outside the system under
 * "Downloaded apps" (some makers say "Installed apps"), and people get lost in the long list above it. Three numbered
 * steps on a quiet glass well, the words to look for set in bold, then the way past a greyed-out switch.
 */
@Composable
private fun SettingsPath(access: Access) {
    val colors = Aurora.colors
    val service = stringResource(if (access == Access.Content) R.string.content_service_label else R.string.shield_service_label)
    val steps = listOf(
        bold(stringResource(R.string.perm_path_open), stringResource(R.string.perm_path_downloaded)) to stringResource(R.string.perm_path_open_note),
        bold(stringResource(R.string.perm_path_tap), service) to null,
        bold(stringResource(R.string.perm_path_turn_on), stringResource(R.string.perm_allow)) to null,
    )
    Column(
        // Room below for the shadow: the dialog's scroll would cut it flat.
        Modifier.padding(bottom = Space.S).fillMaxWidth().glassCard(colors, Radius.L, fill = colors.glassFill(field = true), edge = true).padding(horizontal = 18.dp, vertical = Space.M),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(stringResource(R.string.perm_path_eyebrow), color = colors.textTertiary, style = AuroraType.Eyebrow)
        steps.forEachIndexed { i, (line, note) ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                androidx.compose.foundation.layout.Box(
                    Modifier.size(24.dp).clip(Radius.Pill).background(colors.accent.copy(alpha = 0.12f)).border(1.dp, colors.accent.copy(alpha = 0.35f), Radius.Pill),
                    contentAlignment = Alignment.Center,
                ) { Text("${i + 1}", color = colors.accent, style = AuroraType.Caption.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)) }
                Column(Modifier.weight(1f).padding(top = 2.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(line, color = colors.textSecondary, style = AuroraType.Secondary)
                    if (note != null) Text(note, color = colors.textTertiary, style = AuroraType.Subtext)
                }
            }
        }
        app.aurora.ui.components.RowDivider(0.dp)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
            androidx.compose.material3.Icon(app.aurora.ui.components.AuroraIcons.Lock, null, Modifier.padding(top = 1.dp).size(16.dp), tint = colors.textTertiary)
            Text(stringResource(R.string.perm_path_restricted), color = colors.textTertiary, style = AuroraType.Subtext)
        }
    }
}

/** [template] with its one %1$s filled by [word] in the strong weight and primary colour. */
@Composable
private fun bold(template: String, word: String) = androidx.compose.ui.text.buildAnnotatedString {
    val at = template.indexOf("%1\$s")
    if (at < 0) return@buildAnnotatedString append(template)
    append(template.substring(0, at))
    withStyle(androidx.compose.ui.text.SpanStyle(color = Aurora.colors.textPrimary, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)) { append(word) }
    append(template.substring(at + 4))
}

/**
 * The one way a permission that is off is shown, everywhere: one glass card, a row per permission (its badge, name,
 * one line) and Allow beside it. Only permissions still off are passed in; allowed ones are never shown or asked for.
 */
@Composable
fun PermissionCard(missing: List<Access>, modifier: Modifier = Modifier) {
    val colors = Aurora.colors
    val request = rememberPermissionRequest()
    val allow = stringResource(R.string.perm_allow)
    Column(modifier.fillMaxWidth().auroraSurface(colors).padding(vertical = Space.XS)) {
        missing.forEachIndexed { i, access ->
            if (i > 0) app.aurora.ui.components.RowDivider(76.dp)
            Row(
                Modifier.fillMaxWidth().padding(start = Space.Inner, end = 14.dp, top = 14.dp, bottom = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                app.aurora.ui.components.LitBadge(access.icon, size = 44, lit = false)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(access.name(), color = colors.textPrimary, style = AuroraType.Title)
                    Text(access.line(), color = colors.textSecondary, style = AuroraType.Secondary)
                }
                AuroraButton(allow, { request(access) }, kind = ButtonKind.Secondary, compact = true)
            }
        }
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
            plus = plus != null,
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
