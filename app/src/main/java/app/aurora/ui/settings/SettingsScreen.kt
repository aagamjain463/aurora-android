package app.aurora.ui.settings

import app.aurora.ui.theme.Radius
import app.aurora.ui.components.pressable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.ui.res.stringResource
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.aurora.R
import app.aurora.AuroraApplication
import app.aurora.BuildConfig
import app.aurora.auroraViewModel
import app.aurora.data.Access
import app.aurora.data.AccessState
import app.aurora.data.Loaded
import app.aurora.data.ProtectionStatus
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
import app.aurora.ui.components.sunlit
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
import app.aurora.ui.protect.modeDescription
import app.aurora.ui.protect.rememberProtectionStatus
import app.aurora.ui.protect.summary
import app.aurora.ui.protect.modeName
import app.aurora.ui.theme.Aurora
import app.aurora.ui.theme.AuroraType
import app.aurora.ui.theme.Space
import app.aurora.ui.theme.systemAnimationsOff
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import app.aurora.ui.components.TwoToneHeadline
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.draw.drawBehind
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import app.aurora.ui.components.auroraSurface
import app.aurora.ui.components.bleed
import app.aurora.ui.components.glass
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.shape.RoundedCornerShape

class SettingsViewModel(val app: AuroraApplication) : ViewModel() {
    val prefs: StateFlow<UserPreferences?> =
        app.preferences.preferences.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val list: StateFlow<ProtectedList?> = app.protectedList
    val rules: StateFlow<Loaded<Rules>?> = app.rules.state
    val history: StateFlow<Loaded<History>?> = app.history.state
    val plan: StateFlow<Plan> = app.billing.plan

    fun setReduceMotion(value: Boolean) = launch { app.preferences.setReduceMotion(value) }
    fun setThemeMode(mode: app.aurora.data.ThemeMode) = launch { app.preferences.setThemeMode(mode) }
    fun setDefaultMode(mode: Mode) = launch { app.rules.update { it.copy(defaultRule = it.defaultRule.copy(mode = mode)) } }
    /** Goals restart with the history they are judged from, so nothing cleared reads as failed. */
    fun clearHistory() = launch {
        app.history.reset()
        val now = System.currentTimeMillis()
        app.rules.update { r -> r.copy(trackGoals = r.trackGoals.map { it.copy(createdAt = now) }) }
    }
    fun deleteAll(then: () -> Unit) = launch {
        app.deleteAllData()
        then()
    }

    private fun launch(block: suspend () -> Unit) {
        viewModelScope.launch { app.saving(block) }
    }
}

private enum class Sheet { WebDisclosure, DefaultMode, Privacy, Stored, ClearHistory, DeleteAll, Help, Licenses, Disclosure, Diagnostics }

@Composable
fun SettingsScreen(onBack: () -> Unit, onReplayIntro: () -> Unit, onAccount: () -> Unit = {}, onPlus: () -> Unit = {}, onOpenWeb: () -> Unit = {}, onOpenStrict: () -> Unit = {}, onOpenGuide: () -> Unit = {}) {
    val vm = auroraViewModel { SettingsViewModel(it) }
    val prefs = vm.prefs.collectAsStateWithLifecycle().value ?: UserPreferences()
    val list by vm.list.collectAsStateWithLifecycle()
    val rules = vm.rules.collectAsStateWithLifecycle().value?.value ?: Rules()
    val history = vm.history.collectAsStateWithLifecycle().value?.value ?: History()
    val plan by vm.plan.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val usageAccess = rememberAccess(Access.Usage)
    var notificationsOn by remember { mutableStateOf(true) }
    var uninstallOn by remember { mutableStateOf(false) }
    androidx.lifecycle.compose.LifecycleResumeEffect(Unit) {
        notificationsOn = androidx.core.app.NotificationManagerCompat.from(context).areNotificationsEnabled()
        uninstallOn = app.aurora.protect.UninstallGuard.active(context)
        onPauseOrDispose { }
    }
    val shieldAccess = rememberAccess(Access.Accessibility)
    val contentAccess = rememberAccess(Access.Content)
    val protection = rememberProtectionStatus(list)
    val systemReduced = remember { systemAnimationsOff(context) }
    var sheet by remember { mutableStateOf<Sheet?>(null) }
    val colors = Aurora.colors

    @Composable
    fun Value(text: String, on: Boolean = false) {
        Text(text, color = if (on) colors.accent else colors.textTertiary, style = AuroraType.Secondary)
        Chevron()
    }

    @Composable
    fun Allowed(on: Boolean) = Value(if (on) stringResource(R.string.settings_allowed) else stringResource(R.string.intent_off), on)

    val authState by (context.applicationContext as AuroraApplication).account.state.collectAsStateWithLifecycle()
    val user = (authState as? app.aurora.account.AuthState.SignedIn)?.user
    val userName = user?.displayName?.takeIf { it.isNotBlank() } ?: user?.email.orEmpty()

    AuroraScreen(
        title = stringResource(R.string.settings_title),
        onBack = onBack,
        header = {
            Text(
                stringResource(R.string.settings_title),
                Modifier.padding(horizontal = Space.Gutter).padding(top = Space.XS, bottom = Space.L).semantics { heading() },
                color = colors.textPrimary,
                style = AuroraType.DisplayXL,
            )
        },
    ) {
        item(key = "profile") {
            // Who is signed in, then the plan: the page opens on the person, and Plus is the one lit card.
            Row(
                Modifier.padding(horizontal = Space.Gutter).fillMaxWidth().pressable(Radius.L, onClick = onAccount).padding(vertical = Space.XS),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Space.M),
            ) {
                Box(Modifier.size(56.dp).glass(colors, Radius.Pill), contentAlignment = Alignment.Center) {
                    Text(
                        userName.firstOrNull()?.uppercase() ?: "A",
                        color = colors.textPrimary,
                        style = AuroraType.DisplayS.copy(fontSize = 28.sp, lineHeight = 30.sp),
                    )
                }
                Column(Modifier.weight(1f)) {
                    Text(userName.ifBlank { stringResource(R.string.account_title) }, color = colors.textPrimary, style = AuroraType.Headline, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(stringResource(R.string.account_row_subtitle), Modifier.padding(top = 2.dp), color = colors.textTertiary, style = AuroraType.Secondary)
                }
                Chevron()
            }
        }
        item(key = "plan") {
            val plus = plan == Plan.Plus
            Row(
                Modifier
                    .padding(horizontal = Space.Gutter)
                    .padding(top = Space.M)
                    .fillMaxWidth()
                    .pressable(Radius.L, onClick = onPlus)
                    .clip(Radius.L)
                    .sunlit(colors)
                    .padding(horizontal = 20.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                androidx.compose.material3.Icon(
                    androidx.compose.ui.res.painterResource(R.drawable.ic_aurora_mark), null,
                    Modifier.size(width = 26.dp, height = 20.dp), tint = colors.onAccent,
                )
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.plus_name), color = colors.onAccent, style = AuroraType.DisplayS.copy(fontSize = 24.sp, lineHeight = 28.sp))
                    Text(
                        stringResource(if (plus) R.string.settings_plus_card_active else R.string.settings_plus_card_body),
                        color = colors.onAccent.copy(alpha = 0.7f), style = AuroraType.Secondary,
                    )
                }
                androidx.compose.material3.Icon(AuroraIcons.Chevron, null, tint = colors.onAccent, modifier = Modifier.size(18.dp))
            }
        }
        item(key = "protection") {
            SectionLabel(stringResource(R.string.today_protection_title), Modifier.padding(top = Space.S))
            RowGroup {
                SettingRow(
                    title = stringResource(R.string.settings_preferred_mode_for_new_apps),
                    icon = app.aurora.ui.protect.modeIcon(rules.defaultRule.mode),
                    onClick = { sheet = Sheet.DefaultMode },
                    trailing = { Value(modeName(rules.defaultRule.mode)) },
                )
                RowDivider(Inset)
                SettingRow(stringResource(R.string.settings_strict_row), AuroraIcons.Lock, onClick = onOpenStrict)
            }
        }
        item(key = "permissions") {
            // Every permission Aurora can use, in one place.
            SectionLabel(stringResource(R.string.settings_permissions))
            RowGroup {
                SettingRow(
                    title = stringResource(R.string.onb_shield_eyebrow),
                    subtitle = when {
                        protection == null -> Access.Accessibility.why
                        protection == ProtectionStatus.Off && shieldAccess != AccessState.Granted -> Access.Accessibility.why
                        else -> protection.summary()
                    },
                    icon = AuroraIcons.Protect,
                    // Before sending anyone to Accessibility settings, aurora says what it would read.
                    onClick = { if (shieldAccess == AccessState.Granted) openAccessSettings(context, Access.Accessibility) else sheet = Sheet.Disclosure },
                    trailing = { Allowed(shieldAccess == AccessState.Granted) },
                )
                RowDivider(Inset)
                SettingRow(
                    title = Access.Content.title,
                    subtitle = if (contentAccess == AccessState.Granted) null else Access.Content.why,
                    icon = AuroraIcons.Globe,
                    onClick = { if (contentAccess == AccessState.Granted) openAccessSettings(context, Access.Content) else sheet = Sheet.WebDisclosure },
                    trailing = { Allowed(contentAccess == AccessState.Granted) },
                )
                RowDivider(Inset)
                SettingRow(
                    title = stringResource(R.string.strict_admin_title),
                    subtitle = stringResource(if (uninstallOn) R.string.strict_admin_on else R.string.strict_admin_off),
                    icon = AuroraIcons.Trash,
                    // Turning it off lives with Strict protection, where it is locked during a commitment.
                    onClick = { if (uninstallOn) onOpenStrict() else runCatching { context.startActivity(app.aurora.protect.UninstallGuard.enableIntent(context)) } },
                    trailing = { Allowed(uninstallOn) },
                )
                RowDivider(Inset)
                SettingRow(
                    title = Access.Usage.title,
                    subtitle = if (usageAccess == AccessState.Revoked) stringResource(R.string.settings_turned_off_in_android_settings_insights_and_daily_limits_need_it) else null,
                    icon = AuroraIcons.Insights,
                    onClick = { openAccessSettings(context, Access.Usage) },
                    trailing = { Allowed(usageAccess == AccessState.Granted) },
                )
                RowDivider(Inset)
                SettingRow(
                    title = stringResource(R.string.settings_notifications),
                    icon = AuroraIcons.Bell,
                    onClick = {
                        context.startActivity(
                            android.content.Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName),
                        )
                    },
                    trailing = { Allowed(notificationsOn) },
                )
            }
        }
        item(key = "appearance") {
            SectionLabel(stringResource(R.string.settings_appearance))
            RowGroup {
                Column(Modifier.padding(horizontal = 18.dp, vertical = Space.M), verticalArrangement = Arrangement.spacedBy(Space.S)) {
                    Text(stringResource(R.string.settings_theme), color = colors.textPrimary, style = AuroraType.BodyStrong)
                    val themeNames = mapOf(
                        app.aurora.data.ThemeMode.Dark to stringResource(R.string.settings_theme_dark),
                        app.aurora.data.ThemeMode.Light to stringResource(R.string.settings_theme_light),
                        app.aurora.data.ThemeMode.System to stringResource(R.string.settings_theme_system),
                    )
                    ChoicePills(
                        options = themeNames.keys.toList(), selected = prefs.themeMode, onSelect = vm::setThemeMode,
                        label = { themeNames.getValue(it) }, fill = true, segmented = true,
                    )
                }
                RowDivider()
                ToggleRow(
                    title = stringResource(R.string.settings_reduce_motion),
                    subtitle = if (systemReduced) stringResource(R.string.settings_on_because_animations_are_off_in_android_settings) else null,
                    checked = prefs.reduceMotion || systemReduced,
                    enabled = !systemReduced,
                    onCheckedChange = vm::setReduceMotion,
                )
            }
        }
        item(key = "data") {
            SectionLabel(stringResource(R.string.settings_privacy_data))
            RowGroup {
                SettingRow(stringResource(R.string.settings_your_data_stays_yours), AuroraIcons.Data, onClick = { sheet = Sheet.Privacy })
                RowDivider(Inset)
                SettingRow(stringResource(R.string.settings_what_aurora_stores), AuroraIcons.Layers, onClick = { sheet = Sheet.Stored })
                RowDivider(Inset)
                SettingRow(stringResource(R.string.settings_clear_history), AuroraIcons.Clock, onClick = { sheet = Sheet.ClearHistory })
            }
        }
        item(key = "about") {
            SectionLabel(stringResource(R.string.settings_help_about))
            RowGroup {
                SettingRow(stringResource(R.string.guide_see_all), AuroraIcons.Info, onClick = onOpenGuide)
                RowDivider(Inset)
                SettingRow(stringResource(R.string.settings_troubleshooting), AuroraIcons.Help, onClick = { sheet = Sheet.Help })
                RowDivider(Inset)
                SettingRow(stringResource(R.string.diagnostics_title), AuroraIcons.Insights, onClick = { sheet = Sheet.Diagnostics })
                RowDivider(Inset)
                SettingRow(stringResource(R.string.settings_replay_introduction), AuroraIcons.Play, onClick = onReplayIntro)
                RowDivider(Inset)
                SettingRow(stringResource(R.string.settings_open_source_licenses), AuroraIcons.Quote, onClick = { sheet = Sheet.Licenses })
            }
        }
        item(key = "danger") {
            // Set apart so it is never tapped on the way to something else.
            RowGroup(Modifier.padding(top = Space.L)) {
                SettingRow(stringResource(R.string.settings_delete_all_data), AuroraIcons.Trash, onClick = { sheet = Sheet.DeleteAll }, tint = colors.danger, trailing = {})
            }
        }
        item(key = "signature") {
            Column(Modifier.fillMaxWidth().padding(top = Space.XXL), horizontalAlignment = Alignment.CenterHorizontally) {
                AuroraMark(36.dp, lit = true)
                Spacer(Modifier.height(Space.S))
                Text(stringResource(R.string.settings_tagline), color = colors.textSecondary, style = AuroraType.DisplayS.copy(fontSize = 20.sp, lineHeight = 24.sp))
                Text(
                    stringResource(R.string.settings_version) + " " + BuildConfig.VERSION_NAME,
                    Modifier.padding(top = Space.XS), color = colors.textTertiary, style = AuroraType.Caption,
                )
            }
        }
    }

    val close = { sheet = null }
    when (sheet) {
        Sheet.DefaultMode -> AuroraDialog(
            title = stringResource(R.string.settings_preferred_mode_for_new_apps),
            body = stringResource(R.string.settings_the_mode_suggested_when_you_start_protection_for_an_app_you_add),
            confirmLabel = stringResource(R.string.settings_done),
            onConfirm = close,
            onDismiss = close,
        ) {
            Column {
                for (mode in Mode.entries) RadioRow(modeName(mode), modeDescription(mode), rules.defaultRule.mode == mode, onClick = { vm.setDefaultMode(mode) })
            }
        }
        Sheet.Privacy -> AuroraDialog(
            title = stringResource(R.string.settings_your_data_stays_yours),
            confirmLabel = stringResource(R.string.settings_got_it),
            onConfirm = close,
            onDismiss = close,
        ) {
            Facts(
                AuroraIcons.Data to (R.string.privacy_local_title to R.string.privacy_local_body),
                AuroraIcons.Protect to (R.string.privacy_shield_title to R.string.privacy_shield_body),
                AuroraIcons.Insights to (R.string.privacy_screen_title to R.string.privacy_screen_body),
                AuroraIcons.Globe to (R.string.privacy_account_title to R.string.privacy_account_body),
            )
        }
        Sheet.Stored -> AuroraDialog(
            title = stringResource(R.string.settings_what_aurora_stores),
            body = stringResource(R.string.settings_it_s_kept_in_aurora_s_private_storage_and_isn_t_included_in_clou),
            confirmLabel = stringResource(R.string.action_close),
            onConfirm = close,
            onDismiss = close,
        ) {
            app.aurora.ui.components.MetricGrid(
                listOf(
                    "${list?.items?.size ?: 0}" to stringResource(R.string.stored_apps),
                    "${rules.shields.size}" to stringResource(R.string.stored_shields),
                    "${rules.configs.count { it.custom }}" to stringResource(R.string.stored_sessions),
                    "${rules.limits.size}" to stringResource(R.string.stored_limits),
                    "${history.days.size}" to stringResource(R.string.stored_days),
                    "${history.focus.size}" to stringResource(R.string.stored_focus),
                ),
                Modifier.bleed(Space.Gutter),
            )
        }
        Sheet.ClearHistory -> AuroraDialog(
            title = stringResource(R.string.settings_clear_history_2),
            body = stringResource(R.string.settings_daily_counts_focus_history_xp_and_aurora_light_start_again_from),
            confirmLabel = stringResource(R.string.settings_clear_history),
            onConfirm = { vm.clearHistory(); close() },
            dismissLabel = stringResource(R.string.action_cancel),
            onDismiss = close,
        )
        Sheet.DeleteAll -> AuroraDialog(
            title = stringResource(R.string.settings_delete_all_data_2),
            body = stringResource(R.string.settings_this_removes_everything_aurora_keeps_on_this_phone_protected_app) +
                stringResource(R.string.settings_a_running_always_block_session_is_kept_until_it_ends_aurora_plus),
            confirmLabel = stringResource(R.string.settings_delete_everything),
            onConfirm = { close(); vm.deleteAll(onReplayIntro) },
            dismissLabel = stringResource(R.string.action_cancel),
            onDismiss = close,
        )
        Sheet.Help -> AuroraDialog(
            title = stringResource(R.string.settings_if_shield_isn_t_stepping_in_2),
            confirmLabel = stringResource(R.string.help_run_diagnostics),
            onConfirm = { sheet = Sheet.Diagnostics },
            dismissLabel = stringResource(R.string.action_close),
            onDismiss = close,
        ) {
            Facts(
                AuroraIcons.Home to (R.string.help_home_title to R.string.help_home_body),
                AuroraIcons.Protect to (R.string.help_restart_title to R.string.help_restart_body),
                AuroraIcons.Hourglass to (R.string.help_battery_title to R.string.help_battery_body),
                AuroraIcons.Lock to (R.string.help_restricted_title to R.string.help_restricted_body),
            )
        }
        Sheet.Licenses -> AuroraDialog(
            title = stringResource(R.string.settings_open_source_licenses),
            body = stringResource(R.string.settings_manrope_typeface_the_manrope_project_authors_sil_open_font_licen) +
                stringResource(R.string.settings_jetpack_compose_androidx_kotlin_kotlinx_serialization_and_google),
            confirmLabel = stringResource(R.string.action_close),
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
        Sheet.WebDisclosure -> AuroraDialog(
            title = stringResource(R.string.web_disclosure_title),
            body = stringResource(R.string.web_disclosure_body),
            confirmLabel = stringResource(R.string.web_open_settings),
            onConfirm = { close(); openAccessSettings(context, Access.Content) },
            dismissLabel = stringResource(R.string.action_not_now),
            onDismiss = close,
            icon = AuroraIcons.Globe,
        )
        Sheet.Diagnostics -> ShieldDiagnostics(trace = remember { ShieldService.trace.snapshot() }, onDismiss = close)
        null -> Unit
    }
}

/** A few (icon, title, one line) facts, spaced for a dialog. */
@Composable
private fun Facts(vararg facts: Pair<ImageVector, Pair<Int, Int>>) {
    Column(verticalArrangement = Arrangement.spacedBy(Space.M)) {
        for ((icon, text) in facts) app.aurora.ui.components.FactRow(icon, stringResource(text.first), body = stringResource(text.second))
    }
}

/** Where row hairlines start: past the icon tile, so each group reads as one list. */
private val Inset = 64.dp

/** A settings row: its icon in a small tile, the title, and a value or a chevron on the right. */
@Composable
private fun SettingRow(
    title: String,
    icon: ImageVector,
    subtitle: String? = null,
    onClick: () -> Unit,
    tint: Color = Aurora.colors.textPrimary,
    trailing: @Composable RowScope.() -> Unit = { Chevron() },
) {
    val colors = Aurora.colors
    Row(
        Modifier.fillMaxWidth().heightIn(min = 58.dp)
            .clickable(role = androidx.compose.ui.semantics.Role.Button, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(Modifier.size(32.dp).clip(RoundedCornerShape(10.dp)).background(tint.copy(alpha = if (colors.isDark) 0.08f else 0.06f)), contentAlignment = Alignment.Center) {
            androidx.compose.material3.Icon(icon, null, tint = tint, modifier = Modifier.size(17.dp))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, color = tint, style = AuroraType.BodyStrong)
            if (subtitle != null) Text(subtitle, color = colors.textTertiary, style = AuroraType.Secondary.copy(fontSize = 12.5.sp, lineHeight = 17.sp))
        }
        trailing()
    }
}
