package app.aurora.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.aurora.AuroraApplication
import app.aurora.BuildConfig
import app.aurora.auroraViewModel
import app.aurora.data.FocusLengths
import app.aurora.data.ThemeMode
import app.aurora.data.UserPreferences
import app.aurora.data.Access
import app.aurora.data.AccessState
import app.aurora.data.ProtectionStatus
import app.aurora.protect.ProtectedList
import app.aurora.protect.ShieldService
import app.aurora.ui.protect.ShieldDiagnostics
import app.aurora.ui.protect.ShieldDisclosure
import app.aurora.ui.protect.rememberProtectionStatus
import app.aurora.ui.protect.summary
import app.aurora.data.openAccessSettings
import app.aurora.rememberAccess
import app.aurora.ui.components.AuroraDialog
import app.aurora.ui.components.AuroraIcons
import app.aurora.ui.components.AuroraMark
import app.aurora.ui.components.AuroraRow
import app.aurora.ui.components.AuroraScreen
import app.aurora.ui.components.Chevron
import app.aurora.ui.components.ChoicePills
import app.aurora.ui.components.RowDivider
import app.aurora.ui.components.RowGroup
import app.aurora.ui.components.SectionLabel
import app.aurora.ui.components.ToggleRow
import app.aurora.ui.theme.Aurora
import app.aurora.ui.theme.AuroraType
import app.aurora.ui.theme.Space
import app.aurora.ui.theme.systemAnimationsOff
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(private val app: AuroraApplication) : ViewModel() {
    val prefs: StateFlow<UserPreferences?> =
        app.preferences.preferences.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val list: StateFlow<ProtectedList?> = app.protection.list.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val plan = app.plan

    fun setTheme(mode: ThemeMode) = launch { app.preferences.setThemeMode(mode) }
    fun setReduceMotion(value: Boolean) = launch { app.preferences.setReduceMotion(value) }
    fun setDefaultMinutes(minutes: Int) = launch { app.preferences.setDefaultFocusMinutes(minutes) }

    private fun launch(block: suspend () -> Unit) {
        viewModelScope.launch { block() }
    }
}

private enum class Sheet { Length, Privacy, Licenses, Disclosure, Diagnostics }

@Composable
fun SettingsScreen(onBack: () -> Unit, onReplayIntro: () -> Unit) {
    val vm = auroraViewModel { SettingsViewModel(it) }
    val prefsOrNull by vm.prefs.collectAsStateWithLifecycle()
    val prefs = prefsOrNull ?: UserPreferences()
    val context = LocalContext.current
    val usageAccess = rememberAccess(Access.Usage)
    val shieldAccess = rememberAccess(Access.Accessibility)
    val list by vm.list.collectAsStateWithLifecycle()
    val protection = rememberProtectionStatus(list)
    val systemReduced = remember { systemAnimationsOff(context) }
    var sheet by remember { mutableStateOf<Sheet?>(null) }
    val colors = Aurora.colors

    AuroraScreen(title = "Settings", onBack = onBack) {
        item(key = "appearance") {
            SectionLabel("Appearance")
            RowGroup {
                Column(Modifier.padding(Space.M), verticalArrangement = Arrangement.spacedBy(Space.S)) {
                    Text("Theme", color = colors.textPrimary, style = AuroraType.BodyStrong)
                    ChoicePills(
                        options = ThemeMode.entries,
                        selected = prefs.themeMode,
                        onSelect = vm::setTheme,
                        fill = true,
                        label = { it.name },
                    )
                }
            }
        }
        item(key = "preferences") {
            SectionLabel("Preferences")
            RowGroup {
                AuroraRow(
                    title = "Default focus length",
                    subtitle = "Used when you start focus from Today.",
                    icon = AuroraIcons.Focus,
                    onClick = { sheet = Sheet.Length },
                    trailing = {
                        Text("${prefs.defaultFocusMinutes} min", color = colors.textSecondary, style = AuroraType.Secondary)
                        Chevron()
                    },
                )
            }
        }
        item(key = "accessibility") {
            SectionLabel("Accessibility")
            RowGroup {
                ToggleRow(
                    title = "Reduce motion",
                    subtitle = if (systemReduced) "On, because animations are off in Android settings."
                    else "No ambient movement or sliding transitions.",
                    checked = prefs.reduceMotion || systemReduced,
                    enabled = !systemReduced,
                    onCheckedChange = vm::setReduceMotion,
                )
            }
        }
        item(key = "permissions") {
            SectionLabel("Permissions")
            RowGroup {
                val usageGranted = usageAccess == AccessState.Granted
                AuroraRow(
                    title = Access.Usage.title,
                    subtitle = if (usageAccess == AccessState.Revoked) "Turned off in Android Settings. Insights needs it to show your day."
                    else Access.Usage.why,
                    icon = AuroraIcons.Insights,
                    onClick = { openAccessSettings(context, Access.Usage) },
                    trailing = {
                        Text(
                            if (usageGranted) "Allowed" else "Off",
                            color = if (usageGranted) colors.accent else colors.textSecondary,
                            style = AuroraType.Secondary,
                        )
                        Chevron()
                    },
                )
                RowDivider(56.dp)
                val shieldOn = shieldAccess == AccessState.Granted
                AuroraRow(
                    title = "Shield",
                    subtitle = when {
                        protection == null -> Access.Accessibility.why
                        protection == ProtectionStatus.Off && !shieldOn -> Access.Accessibility.why
                        else -> protection.summary()
                    },
                    icon = AuroraIcons.Lock,
                    // Before sending anyone to Accessibility settings, aurora says what it would read.
                    onClick = { if (shieldOn) openAccessSettings(context, Access.Accessibility) else sheet = Sheet.Disclosure },
                    trailing = {
                        Text(
                            if (shieldOn) "Allowed" else "Off",
                            color = if (shieldOn) colors.accent else colors.textSecondary,
                            style = AuroraType.Secondary,
                        )
                        Chevron()
                    },
                )
                RowDivider(56.dp)
                AuroraRow(
                    title = "Shield diagnostics",
                    subtitle = "What aurora can see about Shield on this phone.",
                    icon = AuroraIcons.Insights,
                    onClick = { sheet = Sheet.Diagnostics },
                    trailing = { Chevron() },
                )
            }
        }
        item(key = "about") {
            SectionLabel("About")
            RowGroup {
                AuroraRow("Privacy", subtitle = "Local-first. No account, no tracking.", onClick = { sheet = Sheet.Privacy }, trailing = { Chevron() })
                RowDivider()
                AuroraRow("Replay introduction", onClick = onReplayIntro, trailing = { Chevron() })
                RowDivider()
                AuroraRow("Open-source licenses", onClick = { sheet = Sheet.Licenses }, trailing = { Chevron() })
                RowDivider()
                AuroraRow("Version", trailing = {
                    Text(BuildConfig.VERSION_NAME, color = colors.textSecondary, style = AuroraType.Secondary)
                })
            }
        }
        item(key = "signature") {
            Column(
                Modifier.fillMaxWidth().padding(top = Space.XXL),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                AuroraMark(28.dp)
                Spacer(Modifier.height(Space.S))
                Text("technology on your terms", color = colors.textTertiary, style = AuroraType.Caption)
            }
        }
    }

    when (sheet) {
        Sheet.Length -> AuroraDialog(
            title = "Default focus length",
            body = "You can always change it before a session begins.",
            confirmLabel = "Done",
            onConfirm = { sheet = null },
            onDismiss = { sheet = null },
        ) {
            ChoicePills(FocusLengths, prefs.defaultFocusMinutes, vm::setDefaultMinutes, label = { "$it" }, fill = true, spokenLabel = { "$it minutes" })
        }
        Sheet.Privacy -> AuroraDialog(
            title = "Your data stays yours",
            body = "Your chosen apps, focus sessions and preferences are stored only on this phone. " +
                "There's no account, no analytics and no ads, and aurora doesn't request internet access.\n\n" +
                "Screen time is read from Android when you open Insights. It isn't copied, stored or shared.\n\n" +
                "Shield reads only the name of the app in front, to know when to pause you. It isn't stored or shared.",
            confirmLabel = "Got it",
            onConfirm = { sheet = null },
            onDismiss = { sheet = null },
        )
        Sheet.Licenses -> AuroraDialog(
            title = "Open-source licenses",
            body = "Manrope typeface, © The Manrope Project Authors. SIL Open Font License 1.1.\n\n" +
                "Jetpack Compose, AndroidX and Kotlin. Apache License 2.0.",
            confirmLabel = "Close",
            onConfirm = { sheet = null },
            onDismiss = { sheet = null },
        )
        Sheet.Disclosure -> ShieldDisclosure(
            onContinue = {
                sheet = null
                openAccessSettings(context, Access.Accessibility)
            },
            onDismiss = { sheet = null },
        )
        Sheet.Diagnostics -> ShieldDiagnostics(
            status = protection,
            list = list,
            active = list?.active(vm.plan)?.size ?: 0,
            trace = remember { ShieldService.trace.snapshot() },
            onDismiss = { sheet = null },
        )
        null -> Unit
    }
}
