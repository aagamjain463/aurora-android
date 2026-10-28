package app.aurora.ui.protect

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.aurora.data.Access
import app.aurora.data.ProtectionStatus
import app.aurora.data.protectionStatus
import app.aurora.protect.ProtectedList
import app.aurora.protect.ShieldService
import app.aurora.protect.ShieldTrace
import app.aurora.rememberAccess
import app.aurora.ui.components.AuroraButton
import app.aurora.ui.components.AuroraDialog
import app.aurora.ui.components.ButtonKind
import app.aurora.ui.components.StatusPill
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
fun ProtectionStatus.summary(): String = when (this) {
    ProtectionStatus.Off -> "No apps protected"
    ProtectionStatus.Active -> "Shield is on"
    ProtectionStatus.NeedsPermission -> "Not protected yet · turn on Shield"
    ProtectionStatus.NotRunning -> "Shield is allowed but not running"
    ProtectionStatus.ConfigError -> "Shield can't read your protected apps"
    ProtectionStatus.Blocked -> "Shield can't run on this phone"
}

/** The Protect tab's status card: the truth about protection, and the one step that fixes it. */
@Composable
fun ShieldStatusCard(status: ProtectionStatus, count: Int, allowed: Boolean, onTurnOn: () -> Unit, onOpenSettings: () -> Unit) {
    val colors = Aurora.colors
    val (title, body) = when (status) {
        ProtectionStatus.Off -> "Shield is ready" to
            if (allowed) "Choose an app below and Shield protects it straight away."
            else "Choose the apps that pull you away. aurora asks before it turns Shield on."
        ProtectionStatus.Active -> "Shield is on" to
            if (count == 1) "aurora steps in whenever your protected app opens." else "aurora steps in whenever one of your $count items opens."
        ProtectionStatus.NeedsPermission -> "Turn on Shield" to
            (if (count == 1) "Your protected item isn't protected yet." else "Your $count items aren't protected yet.") +
                " Shield needs Android's accessibility permission to notice when one opens."
        ProtectionStatus.NotRunning -> "Shield isn't running" to
            "It's allowed in Android settings, but Android hasn't started it. Turn aurora Shield off and on again in Accessibility settings."
        ProtectionStatus.ConfigError -> "Shield can't read your apps" to
            "Nothing is shielded until it can. Close aurora and open it again."
        ProtectionStatus.Blocked -> "Shield can't run here" to "Android or your device's settings don't let accessibility services run."
    }
    val good = status == ProtectionStatus.Active
    Column(
        Modifier
            .padding(horizontal = Space.M)
            .fillMaxWidth()
            .clip(Radius.L)
            .background(if (good) colors.accentSoft else colors.dawn.copy(alpha = if (colors.isDark) 0.09f else 0.14f))
            .padding(Space.M),
        verticalArrangement = Arrangement.spacedBy(Space.XS),
    ) {
        StatusPill(if (good) "Active" else "Not active", live = good)
        Text(title, Modifier.padding(top = 4.dp).semantics { heading() }, color = colors.textPrimary, style = AuroraType.Title)
        Text(body, color = colors.textSecondary, style = AuroraType.Secondary)
        when (status) {
            ProtectionStatus.NeedsPermission -> AuroraButton("Turn on Shield", onTurnOn, Modifier.padding(top = Space.XS))
            ProtectionStatus.NotRunning -> AuroraButton("Open Accessibility settings", onOpenSettings, Modifier.padding(top = Space.XS), kind = ButtonKind.Secondary)
            else -> Unit
        }
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
        title = "Turn on Shield",
        body = "To pause you before an app you chose, aurora uses an Android accessibility service called aurora Shield.\n\n" +
            "It reads only the name of the app in front. It doesn't read your screen, your messages or what you type, " +
            "and nothing leaves your phone.\n\n" +
            "Android will ask you to confirm. You can turn it off at any time in Settings › Accessibility.",
        confirmLabel = "Open settings",
        onConfirm = onContinue,
        dismissLabel = "Not now",
        onDismiss = onDismiss,
    )
}

/** What aurora knows about Shield right now. Counts and timings come from this process only. */
@Composable
fun ShieldDiagnostics(status: ProtectionStatus?, list: ProtectedList?, active: Int, trace: ShieldTrace.Snapshot, onDismiss: () -> Unit) {
    val exposure = trace.exposure
    val connected by ShieldService.connected.collectAsStateWithLifecycle()
    val lines = buildList {
        add("Status: ${status?.summary() ?: "Checking…"}")
        add("Service: ${if (connected) "connected" else "not connected"}")
        if (list != null) add(if (list.readable) "Protected apps: $active active, ${list.items.size - active} paused" else "Protected apps: can't be read")
        if (list != null && list.skipped > 0) add("Unreadable saved entries: ${list.skipped}")
        add("Since aurora started: ${trace.events} window events, ${trace.shields} Shield requests (${trace.repeats} repeats, ${trace.recovered} re-shown after a silent return), ${trace.failures} refused")
        add(
            if (exposure == null) "Shield time: none measured yet"
            else "Shield time (app opened to first Shield frame): median ${exposure.first} ms, slowest ${exposure.second} ms, last ${trace.timings.size}",
        )
        if (trace.lifecycle.isNotEmpty()) add("Service: ${trace.lifecycle.takeLast(3).joinToString("; ")}")
    }
    AuroraDialog(
        title = "Shield diagnostics",
        body = lines.joinToString("\n") + "\n\nMeasured on this phone and kept only until aurora closes. Nothing is sent anywhere.",
        confirmLabel = "Close",
        onConfirm = onDismiss,
        onDismiss = onDismiss,
    )
}
