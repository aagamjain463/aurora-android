package app.aurora.ui.settings

import app.aurora.ui.theme.Radius
import app.aurora.ui.components.pressable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import app.aurora.ui.components.auroraSurface

class SettingsViewModel(val app: AuroraApplication) : ViewModel() {
    val prefs: StateFlow<UserPreferences?> =
        app.preferences.preferences.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val list: StateFlow<ProtectedList?> = app.protectedList
    val rules: StateFlow<Loaded<Rules>?> = app.rules.state
    val history: StateFlow<Loaded<History>?> = app.history.state
    val plan: StateFlow<Plan> = app.billing.plan

    fun setReduceMotion(value: Boolean) = launch { app.preferences.setReduceMotion(value) }
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

private enum class Sheet { DefaultMode, Privacy, Stored, ClearHistory, DeleteAll, Help, Licenses, Disclosure, Diagnostics }

@Composable
fun SettingsScreen(onBack: () -> Unit, onReplayIntro: () -> Unit, onPlus: () -> Unit = {}, onOpenWeb: () -> Unit = {}, onOpenStrict: () -> Unit = {}) {
    val vm = auroraViewModel { SettingsViewModel(it) }
    val prefs = vm.prefs.collectAsStateWithLifecycle().value ?: UserPreferences()
    val list by vm.list.collectAsStateWithLifecycle()
    val rules = vm.rules.collectAsStateWithLifecycle().value?.value ?: Rules()
    val history = vm.history.collectAsStateWithLifecycle().value?.value ?: History()
    val plan by vm.plan.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val usageAccess = rememberAccess(Access.Usage)
    var notificationsOn by remember { mutableStateOf(true) }
    androidx.lifecycle.compose.LifecycleResumeEffect(Unit) {
        notificationsOn = androidx.core.app.NotificationManagerCompat.from(context).areNotificationsEnabled()
        onPauseOrDispose { }
    }
    val shieldAccess = rememberAccess(Access.Accessibility)
    val contentAccess = rememberAccess(Access.Content)
    val protection = rememberProtectionStatus(list)
    val systemReduced = remember { systemAnimationsOff(context) }
    var sheet by remember { mutableStateOf<Sheet?>(null) }
    val colors = Aurora.colors

    @Composable
    fun Allowed(on: Boolean) {
        Text(if (on) stringResource(R.string.settings_allowed) else stringResource(R.string.intent_off), color = if (on) colors.accent else colors.textSecondary, style = AuroraType.Secondary)
        Chevron()
    }

    AuroraScreen(title = stringResource(R.string.settings_title), onBack = onBack) {
        item(key = "plan") {
            SectionLabel(stringResource(R.string.settings_plan))
            // The plan is a warm card carrying the mark, not one more settings row.
            Row(
                Modifier.padding(horizontal = Space.M).fillMaxWidth().pressable(Radius.L, onClick = onPlus).auroraSurface(colors, warm = true).padding(Space.L),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Space.M),
            ) {
                app.aurora.ui.components.AuroraMark(36.dp)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(if (plan == Plan.Plus) stringResource(R.string.plus_name) else stringResource(R.string.settings_aurora_free), color = colors.textPrimary, style = AuroraType.Title)
                    Text(
                        if (plan == Plan.Plus) stringResource(R.string.settings_everything_unlocked_manage_or_restore_your_subscription) else stringResource(R.string.settings_full_strength_protection_with_a_few_limits_see_what_plus_adds),
                        color = colors.textSecondary, style = AuroraType.Secondary,
                    )
                }
                if (plan == Plan.Plus) Tag(stringResource(R.string.settings_plus), accent = true)
                Chevron()
            }
        }
        item(key = "appearance") {
            SectionLabel(stringResource(R.string.settings_appearance))
            RowGroup {
                ToggleRow(
                    title = stringResource(R.string.settings_reduce_motion),
                    subtitle = if (systemReduced) stringResource(R.string.settings_on_because_animations_are_off_in_android_settings) else stringResource(R.string.settings_no_ambient_movement_or_sliding_transitions),
                    checked = prefs.reduceMotion || systemReduced,
                    enabled = !systemReduced,
                    onCheckedChange = vm::setReduceMotion,
                )
            }
        }
        item(key = "protection") {
            SectionLabel(stringResource(R.string.today_protection_title))
            RowGroup {
                AuroraRow(
                    title = stringResource(R.string.settings_preferred_mode_for_new_apps),
                    subtitle = modeName(rules.defaultRule.mode),
                    icon = AuroraIcons.Protect,
                    onClick = { sheet = Sheet.DefaultMode },
                    trailing = { Chevron() },
                )
                RowDivider(56.dp)
                AuroraRow(
                    title = stringResource(R.string.onb_shield_eyebrow),
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
                    title = stringResource(R.string.settings_web_content_shield),
                    subtitle = stringResource(R.string.settings_optional_websites_and_shorts_reels),
                    icon = AuroraIcons.Globe,
                    onClick = onOpenWeb,
                    trailing = { Allowed(contentAccess == AccessState.Granted) },
                )
                RowDivider(56.dp)
                AuroraRow(
                    title = stringResource(R.string.diagnostics_title),
                    subtitle = stringResource(R.string.settings_what_aurora_can_see_about_shield_on_this_phone),
                    icon = AuroraIcons.Insights,
                    onClick = { sheet = Sheet.Diagnostics },
                    trailing = { Chevron() },
                )
                RowDivider(56.dp)
                AuroraRow(
                    title = stringResource(R.string.settings_strict_row),
                    subtitle = stringResource(R.string.strict_body),
                    icon = AuroraIcons.Lock,
                    onClick = onOpenStrict,
                    trailing = { Chevron() },
                )
            }
        }
        item(key = "permissions") {
            SectionLabel(stringResource(R.string.settings_permissions))
            RowGroup {
                AuroraRow(
                    title = Access.Usage.title,
                    subtitle = if (usageAccess == AccessState.Revoked) stringResource(R.string.settings_turned_off_in_android_settings_insights_and_daily_limits_need_it) else stringResource(R.string.settings_powers_insights_goals_and_daily_limits_read_on_this_phone_only),
                    icon = AuroraIcons.Insights,
                    onClick = { openAccessSettings(context, Access.Usage) },
                    trailing = { Allowed(usageAccess == AccessState.Granted) },
                )
                RowDivider(56.dp)
                AuroraRow(
                    title = stringResource(R.string.settings_notifications),
                    subtitle = stringResource(R.string.settings_aurora_doesn_t_send_notifications_yet),
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
        item(key = "data") {
            SectionLabel(stringResource(R.string.settings_privacy_data))
            RowGroup {
                AuroraRow(stringResource(R.string.settings_your_data_stays_yours), subtitle = stringResource(R.string.settings_local_first_no_account_no_tracking_no_internet_permission), icon = AuroraIcons.Data, onClick = { sheet = Sheet.Privacy }, trailing = { Chevron() })
                RowDivider(56.dp)
                AuroraRow(stringResource(R.string.settings_what_aurora_stores), subtitle = stringResource(R.string.settings_see_everything_kept_on_this_phone), onClick = { sheet = Sheet.Stored }, trailing = { Chevron() }, leading = { Spacer(Modifier.height(1.dp).padding(start = 24.dp)) })
                RowDivider(56.dp)
                AuroraRow(stringResource(R.string.settings_clear_history), subtitle = stringResource(R.string.settings_daily_counts_focus_history_and_xp), onClick = { sheet = Sheet.ClearHistory }, leading = { Spacer(Modifier.padding(start = 24.dp)) })
                RowDivider(56.dp)
                AuroraRow(stringResource(R.string.settings_delete_all_data), subtitle = stringResource(R.string.settings_start_again_from_the_beginning), onClick = { sheet = Sheet.DeleteAll }, leading = { Spacer(Modifier.padding(start = 24.dp)) })
            }
        }
        item(key = "about") {
            SectionLabel(stringResource(R.string.settings_help_about))
            RowGroup {
                AuroraRow(stringResource(R.string.settings_troubleshooting), subtitle = stringResource(R.string.settings_if_shield_isn_t_stepping_in), icon = AuroraIcons.Help, onClick = { sheet = Sheet.Help }, trailing = { Chevron() })
                RowDivider(56.dp)
                AuroraRow(stringResource(R.string.settings_replay_introduction), onClick = onReplayIntro, trailing = { Chevron() }, leading = { Spacer(Modifier.padding(start = 24.dp)) })
                RowDivider(56.dp)
                AuroraRow(stringResource(R.string.settings_open_source_licenses), onClick = { sheet = Sheet.Licenses }, trailing = { Chevron() }, leading = { Spacer(Modifier.padding(start = 24.dp)) })
                RowDivider(56.dp)
                AuroraRow(stringResource(R.string.settings_version), leading = { Spacer(Modifier.padding(start = 24.dp)) }, trailing = {
                    Text(BuildConfig.VERSION_NAME, color = colors.textSecondary, style = AuroraType.Secondary)
                })
            }
        }
        item(key = "signature") {
            Column(Modifier.fillMaxWidth().padding(top = Space.XXL), horizontalAlignment = Alignment.CenterHorizontally) {
                app.aurora.ui.components.AuroraLogo(112.dp)
                Spacer(Modifier.height(Space.S))
                Text(stringResource(R.string.settings_take_back_your_attention), color = colors.textTertiary, style = AuroraType.Caption)
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
            body = stringResource(R.string.settings_everything_aurora_keeps_is_stored_only_on_this_phone_your_protec) +
                stringResource(R.string.settings_there_s_no_account_no_analytics_and_no_ads_and_aurora_doesn_t_ha) +
                stringResource(R.string.settings_screen_time_is_read_from_android_when_you_open_insights_or_today) +
                stringResource(R.string.settings_shield_reads_only_the_name_of_the_app_in_front_the_optional_web) +
                stringResource(R.string.settings_purchases_go_through_google_play_which_aurora_asks_whether_you_h),
            confirmLabel = stringResource(R.string.settings_got_it),
            onConfirm = close,
            onDismiss = close,
        )
        Sheet.Stored -> AuroraDialog(
            title = stringResource(R.string.settings_what_aurora_stores),
            body = buildString {
                append(stringResource(R.string.settings_stored_items , list?.items?.size ?: 0))
                append(stringResource(R.string.settings_stored_rules , rules.shields.size, rules.intentPresets.size, rules.configs.count { it.custom }, rules.limits.size))
                append(stringResource(R.string.settings_stored_profiles , rules.profiles.size, rules.trackGoals.size))
                append(stringResource(R.string.settings_stored_history , history.days.size, history.focus.size))
                if (rules.motivations.isNotEmpty()) append(stringResource(R.string.settings_stored_motivations , rules.motivations.joinToString(", ")))
                append(stringResource(R.string.settings_theme_and_motion_preferences))
                append(stringResource(R.string.settings_it_s_kept_in_aurora_s_private_storage_and_isn_t_included_in_clou))
            },
            confirmLabel = stringResource(R.string.action_close),
            onConfirm = close,
            onDismiss = close,
        )
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
            body = stringResource(R.string.settings_1_open_protect_and_check_the_status_at_the_top_it_says_exactly_w) +
                stringResource(R.string.settings_2_if_it_says_shield_isn_t_running_turn_aurora_shield_off_and_on) +
                stringResource(R.string.settings_3_some_phones_stop_background_apps_to_save_battery_allow_aurora) +
                stringResource(R.string.settings_4_on_android_13_and_later_apps_installed_outside_the_play_store) +
                stringResource(R.string.settings_5_after_a_restart_android_starts_shield_a_few_moments_after_you) +
                stringResource(R.string.settings_shield_diagnostics_shows_what_aurora_can_see_on_this_phone),
            confirmLabel = stringResource(R.string.action_close),
            onConfirm = close,
            onDismiss = close,
        )
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
