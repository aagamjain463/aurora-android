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
import app.aurora.data.Access
import app.aurora.data.AccessState
import app.aurora.data.Loaded
import app.aurora.data.ProtectionStatus
import app.aurora.data.ThemeMode
import app.aurora.data.UserPreferences
import app.aurora.data.openAccessSettings
import app.aurora.deleteAllData
import app.aurora.plan.Plan
import app.aurora.progress.History
import app.aurora.protect.Mode
import app.aurora.protect.ProtectedList
import app.aurora.protect.Rules
import app.aurora.protect.ShieldService
import app.aurora.rememberAccess
import app.aurora.ui.components.AuroraDialog
import app.aurora.ui.components.AuroraIcons
import app.aurora.ui.components.AuroraMark
import app.aurora.ui.components.AuroraRow
import app.aurora.ui.components.AuroraScreen
import app.aurora.ui.components.Chevron
import app.aurora.ui.components.ChoicePills
import app.aurora.ui.components.RadioRow
import app.aurora.ui.components.RowDivider
import app.aurora.ui.components.RowGroup
import app.aurora.ui.components.SectionLabel
import app.aurora.ui.components.Tag
import app.aurora.ui.components.ToggleRow
import app.aurora.ui.protect.ShieldDiagnostics
import app.aurora.ui.protect.ShieldDisclosure
import app.aurora.ui.protect.explain
import app.aurora.ui.protect.rememberProtectionStatus
import app.aurora.ui.protect.summary
import app.aurora.ui.protect.title
import app.aurora.ui.theme.Aurora
import app.aurora.ui.theme.AuroraType
import app.aurora.ui.theme.Space
import app.aurora.ui.theme.systemAnimationsOff
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(val app: AuroraApplication) : ViewModel() {
    val prefs: StateFlow<UserPreferences?> =
        app.preferences.preferences.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val list: StateFlow<ProtectedList?> = app.protectedList
    val rules: StateFlow<Loaded<Rules>?> = app.rules.state
    val history: StateFlow<Loaded<History>?> = app.history.state
    val plan: StateFlow<Plan> = app.billing.plan

    fun setTheme(mode: ThemeMode) = launch { app.preferences.setThemeMode(mode) }
    fun setReduceMotion(value: Boolean) = launch { app.preferences.setReduceMotion(value) }
    fun setDefaultMode(mode: Mode) = launch { app.rules.update { it.copy(defaultRule = it.defaultRule.copy(mode = mode)) } }
    fun clearHistory() = launch { app.history.reset() }
    fun deleteAll(then: () -> Unit) = launch {
        app.deleteAllData()
        then()
    }

    private fun launch(block: suspend () -> Unit) {
        viewModelScope.launch { runCatching { block() } }
    }
}

private enum class Sheet { DefaultMode, Privacy, Stored, ClearHistory, DeleteAll, Help, Licenses, Disclosure, Diagnostics, Notifications }

@Composable
fun SettingsScreen(onBack: () -> Unit, onReplayIntro: () -> Unit, onPlus: () -> Unit = {}, onOpenProtect: () -> Unit = {}) {
    val vm = auroraViewModel { SettingsViewModel(it) }
    val prefs = vm.prefs.collectAsStateWithLifecycle().value ?: UserPreferences()
    val list by vm.list.collectAsStateWithLifecycle()
    val rules = vm.rules.collectAsStateWithLifecycle().value?.value ?: Rules()
    val history = vm.history.collectAsStateWithLifecycle().value?.value ?: History()
    val plan by vm.plan.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val usageAccess = rememberAccess(Access.Usage)
    val shieldAccess = rememberAccess(Access.Accessibility)
    val contentAccess = rememberAccess(Access.Content)
    val protection = rememberProtectionStatus(list)
    val systemReduced = remember { systemAnimationsOff(context) }
    var sheet by remember { mutableStateOf<Sheet?>(null) }
    val colors = Aurora.colors

    @Composable
    fun Allowed(on: Boolean) {
        Text(if (on) "Allowed" else "Off", color = if (on) colors.accent else colors.textSecondary, style = AuroraType.Secondary)
        Chevron()
    }

    AuroraScreen(title = "Settings", onBack = onBack) {
        item(key = "plan") {
            SectionLabel("Plan")
            RowGroup {
                AuroraRow(
                    title = if (plan == Plan.Plus) "aurora plus" else "aurora Free",
                    subtitle = if (plan == Plan.Plus) "Everything unlocked. Manage or restore your subscription." else "Full-strength protection, with a few limits. See what plus adds.",
                    icon = AuroraIcons.Spark,
                    onClick = onPlus,
                    trailing = { if (plan == Plan.Plus) Tag("plus", accent = true); Chevron() },
                )
            }
        }
        item(key = "appearance") {
            SectionLabel("Appearance")
            RowGroup {
                Column(Modifier.padding(Space.M), verticalArrangement = Arrangement.spacedBy(Space.S)) {
                    Text("Theme", color = colors.textPrimary, style = AuroraType.BodyStrong)
                    ChoicePills(ThemeMode.entries, prefs.themeMode, vm::setTheme, fill = true, label = { it.name })
                }
                RowDivider()
                ToggleRow(
                    title = "Reduce motion",
                    subtitle = if (systemReduced) "On, because animations are off in Android settings." else "No ambient movement or sliding transitions.",
                    checked = prefs.reduceMotion || systemReduced,
                    enabled = !systemReduced,
                    onCheckedChange = vm::setReduceMotion,
                )
            }
        }
        item(key = "protection") {
            SectionLabel("Protection")
            RowGroup {
                AuroraRow(
                    title = "Default for new apps",
                    subtitle = rules.defaultRule.mode.title(),
                    icon = AuroraIcons.Protect,
                    onClick = { sheet = Sheet.DefaultMode },
                    trailing = { Chevron() },
                )
                RowDivider(56.dp)
                AuroraRow(
                    title = "Shield",
                    subtitle = when {
                        protection == null -> Access.Accessibility.why
                        protection == ProtectionStatus.Off && shieldAccess != AccessState.Granted -> Access.Accessibility.why
                        else -> protection.summary()
                    },
                    icon = AuroraIcons.Lock,
                    // Before sending anyone to Accessibility settings, aurora says what it would read.
                    onClick = { if (shieldAccess == AccessState.Granted) openAccessSettings(context, Access.Accessibility) else sheet = Sheet.Disclosure },
                    trailing = { Allowed(shieldAccess == AccessState.Granted) },
                )
                RowDivider(56.dp)
                AuroraRow(
                    title = "Web & content Shield",
                    subtitle = "Optional. Websites and Shorts/Reels.",
                    icon = AuroraIcons.Globe,
                    onClick = onOpenProtect,
                    trailing = { Allowed(contentAccess == AccessState.Granted) },
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
        item(key = "permissions") {
            SectionLabel("Permissions")
            RowGroup {
                AuroraRow(
                    title = Access.Usage.title,
                    subtitle = if (usageAccess == AccessState.Revoked) "Turned off in Android Settings. Insights and daily limits need it." else "Powers Insights, goals and daily limits. Read on this phone only.",
                    icon = AuroraIcons.Insights,
                    onClick = { openAccessSettings(context, Access.Usage) },
                    trailing = { Allowed(usageAccess == AccessState.Granted) },
                )
                RowDivider(56.dp)
                AuroraRow(
                    title = "Notifications",
                    subtitle = "aurora doesn't send notifications yet.",
                    icon = AuroraIcons.Bell,
                    onClick = { sheet = Sheet.Notifications },
                    trailing = { Chevron() },
                )
            }
        }
        item(key = "data") {
            SectionLabel("Privacy & data")
            RowGroup {
                AuroraRow("Your data stays yours", subtitle = "Local-first. No account, no tracking, no internet permission.", icon = AuroraIcons.Data, onClick = { sheet = Sheet.Privacy }, trailing = { Chevron() })
                RowDivider(56.dp)
                AuroraRow("What aurora stores", subtitle = "See everything kept on this phone.", onClick = { sheet = Sheet.Stored }, trailing = { Chevron() }, leading = { Spacer(Modifier.height(1.dp).padding(start = 24.dp)) })
                RowDivider(56.dp)
                AuroraRow("Clear history", subtitle = "Daily counts, Focus history and XP.", onClick = { sheet = Sheet.ClearHistory }, leading = { Spacer(Modifier.padding(start = 24.dp)) })
                RowDivider(56.dp)
                AuroraRow("Delete all data", subtitle = "Start again from the beginning.", onClick = { sheet = Sheet.DeleteAll }, leading = { Spacer(Modifier.padding(start = 24.dp)) })
            }
        }
        item(key = "about") {
            SectionLabel("Help & about")
            RowGroup {
                AuroraRow("Troubleshooting", subtitle = "If Shield isn't stepping in.", icon = AuroraIcons.Help, onClick = { sheet = Sheet.Help }, trailing = { Chevron() })
                RowDivider(56.dp)
                AuroraRow("Replay introduction", onClick = onReplayIntro, trailing = { Chevron() }, leading = { Spacer(Modifier.padding(start = 24.dp)) })
                RowDivider(56.dp)
                AuroraRow("Open-source licenses", onClick = { sheet = Sheet.Licenses }, trailing = { Chevron() }, leading = { Spacer(Modifier.padding(start = 24.dp)) })
                RowDivider(56.dp)
                AuroraRow("Version", leading = { Spacer(Modifier.padding(start = 24.dp)) }, trailing = {
                    Text(BuildConfig.VERSION_NAME, color = colors.textSecondary, style = AuroraType.Secondary)
                })
            }
        }
        item(key = "signature") {
            Column(Modifier.fillMaxWidth().padding(top = Space.XXL), horizontalAlignment = Alignment.CenterHorizontally) {
                AuroraMark(28.dp)
                Spacer(Modifier.height(Space.S))
                Text("take back your attention", color = colors.textTertiary, style = AuroraType.Caption)
            }
        }
    }

    val close = { sheet = null }
    when (sheet) {
        Sheet.DefaultMode -> AuroraDialog(
            title = "Default for new apps",
            body = "How aurora protects an app you add from now on. You can change any app on its own page.",
            confirmLabel = "Done",
            onConfirm = close,
            onDismiss = close,
        ) {
            Column {
                for (mode in Mode.entries) RadioRow(mode.title(), mode.explain(), rules.defaultRule.mode == mode, onClick = { vm.setDefaultMode(mode) })
            }
        }
        Sheet.Privacy -> AuroraDialog(
            title = "Your data stays yours",
            body = "Everything aurora keeps is stored only on this phone: your protected apps and sites, rules, sessions, daily counts and preferences. " +
                "There's no account, no analytics and no ads, and aurora doesn't have internet access.\n\n" +
                "Screen time is read from Android when you open Insights or Today, and when a daily limit needs it. It isn't copied or shared.\n\n" +
                "Shield reads only the name of the app in front. The optional Web & content Shield reads the address in supported browsers. Neither stores nor sends what it sees.\n\n" +
                "Purchases go through Google Play, which aurora asks whether you have plus.",
            confirmLabel = "Got it",
            onConfirm = close,
            onDismiss = close,
        )
        Sheet.Stored -> AuroraDialog(
            title = "What aurora stores",
            body = buildString {
                append("• ${list?.items?.size ?: 0} protected apps and sites\n")
                append("• ${rules.apps.size} app settings, ${rules.groups.size} groups, ${rules.schedules.size} schedules, ${rules.limits.size} daily limits\n")
                append("• ${rules.profiles.size} Focus profiles, ${rules.goals.size} goals\n")
                append("• ${history.days.size} days of counts and ${history.focus.size} Focus sessions\n")
                if (rules.motivations.isNotEmpty()) append("• What you want more time for: ${rules.motivations.joinToString(", ")}\n")
                append("• Theme and motion preferences\n\n")
                append("It's kept in aurora's private storage and isn't included in cloud backups.")
            },
            confirmLabel = "Close",
            onConfirm = close,
            onDismiss = close,
        )
        Sheet.ClearHistory -> AuroraDialog(
            title = "Clear history?",
            body = "Daily counts, Focus history, XP and Aurora Light start again from today. Your protected apps and rules stay.",
            confirmLabel = "Clear history",
            onConfirm = { vm.clearHistory(); close() },
            dismissLabel = "Cancel",
            onDismiss = close,
        )
        Sheet.DeleteAll -> AuroraDialog(
            title = "Delete all data?",
            body = "This removes everything aurora keeps on this phone: protected apps and sites, rules, sessions, history and preferences. It can't be undone. " +
                "A running Lockdown ends too. aurora plus stays with your Google account.",
            confirmLabel = "Delete everything",
            onConfirm = { close(); vm.deleteAll(onReplayIntro) },
            dismissLabel = "Cancel",
            onDismiss = close,
        )
        Sheet.Help -> AuroraDialog(
            title = "If Shield isn't stepping in",
            body = "1. Open Protect and check the status at the top. It says exactly what's missing.\n\n" +
                "2. If it says Shield isn't running, turn aurora Shield off and on again in Settings › Accessibility.\n\n" +
                "3. Some phones stop background apps to save battery. Allow aurora to run without battery restrictions in its Android app info.\n\n" +
                "4. On Android 13 and later, apps installed outside the Play Store may show “Restricted setting”. Open aurora's app info, tap ⋮, then “Allow restricted settings”.\n\n" +
                "5. After a restart, Android starts Shield a few moments after you unlock.\n\n" +
                "Shield diagnostics shows what aurora can see on this phone.",
            confirmLabel = "Close",
            onConfirm = close,
            onDismiss = close,
        )
        Sheet.Notifications -> AuroraDialog(
            title = "Notifications",
            body = "aurora doesn't send any notifications yet, so there's nothing to allow. Focus and Lockdown keep time on their own and show their result the next time you open aurora.",
            confirmLabel = "Got it",
            onConfirm = close,
            onDismiss = close,
        )
        Sheet.Licenses -> AuroraDialog(
            title = "Open-source licenses",
            body = "Manrope typeface, © The Manrope Project Authors. SIL Open Font License 1.1.\n\n" +
                "Jetpack Compose, AndroidX, Kotlin, kotlinx.serialization and Google Play Billing Library. Apache License 2.0 and the Android SDK License.",
            confirmLabel = "Close",
            onConfirm = close,
            onDismiss = close,
        )
        Sheet.Disclosure -> ShieldDisclosure(
            onContinue = {
                close()
                openAccessSettings(context, Access.Accessibility)
            },
            onDismiss = close,
        )
        Sheet.Diagnostics -> ShieldDiagnostics(
            status = protection,
            list = list,
            active = list?.active(plan)?.size ?: 0,
            trace = remember { ShieldService.trace.snapshot() },
            onDismiss = close,
        )
        null -> Unit
    }
}
