package app.aurora.ui.protect

import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.aurora.R
import app.aurora.auroraViewModel
import app.aurora.data.formatDuration
import app.aurora.plan.Allowance
import app.aurora.plan.ProtectedItem
import app.aurora.protect.IntentConfig
import app.aurora.protect.IntentExpiry
import app.aurora.protect.WebPrefix
import app.aurora.ui.components.AuroraButton
import app.aurora.ui.components.AuroraDialog
import app.aurora.ui.components.AuroraIcons
import app.aurora.ui.components.AuroraRow
import app.aurora.ui.components.AuroraScreen
import app.aurora.ui.components.AuroraTextField
import app.aurora.ui.components.ButtonKind
import app.aurora.ui.components.Chevron
import app.aurora.ui.components.ChoiceCard
import app.aurora.ui.components.Chip
import app.aurora.ui.components.DurationPicker
import app.aurora.ui.components.EmptyState
import app.aurora.ui.components.ProgressLine
import app.aurora.ui.components.RowDivider
import app.aurora.ui.components.RowGroup
import app.aurora.ui.components.SectionLabel
import app.aurora.ui.components.ToggleRow
import app.aurora.ui.components.allowanceText
import app.aurora.ui.components.clockTime
import app.aurora.ui.components.minutesText
import app.aurora.ui.components.secondsText
import app.aurora.ui.theme.Aurora
import app.aurora.ui.theme.AuroraType
import app.aurora.ui.theme.Radius
import app.aurora.ui.theme.Space

val ReflectionPresets = listOf(0, 5, 15, 30, 60)
val IntentCooldownPresets = listOf(5, 15, 30, 60)

/**
 * Intentional App Opening: Pause → Decide → Set a time → Use intentionally → Follow through. The overview
 * explains it in one line per step, shows sessions in progress with their measured time, and lists the
 * apps it's on for.
 */
@Composable
fun IntentScreen(onBack: () -> Unit, onOpenApp: (String) -> Unit, onAddApps: () -> Unit, onPlus: () -> Unit) {
    val vm = auroraViewModel { ProtectModel(it) }
    val data by vm.data.collectAsStateWithLifecycle()
    val notice by vm.notice.collectAsStateWithLifecycle()
    val colors = Aurora.colors
    val d = data
    AuroraScreen(
        title = stringResource(R.string.intent_name),
        onBack = onBack,
        header = {
            Column(Modifier.padding(horizontal = Space.Gutter).padding(bottom = Space.M), verticalArrangement = Arrangement.spacedBy(Space.XS)) {
                Text(stringResource(R.string.intent_card_eyebrow), color = colors.dawnText, style = AuroraType.Eyebrow)
                Text(stringResource(R.string.intent_name), color = colors.textPrimary, style = AuroraType.Display)
                Text(stringResource(R.string.intent_overview_body), color = colors.textSecondary, style = AuroraType.Body)
            }
        },
    ) {
        item(key = "steps") { IntentSteps() }
        if (d == null) return@AuroraScreen
        val now = d.time.wall
        val uses = d.runtime.intentUses
        val cooldowns = d.runtime.intentCooldowns.filterValues { it > now }
        if (uses.isNotEmpty() || cooldowns.isNotEmpty()) {
            item(key = "live") { SectionLabel(stringResource(R.string.intent_now)) }
            items(uses.entries.toList(), key = { "u-" + it.key }) { (key, use) ->
                Column(
                    Modifier.padding(horizontal = Space.M).padding(bottom = Space.S).fillMaxWidth().clip(Radius.L).background(colors.surface)
                        .border(1.dp, colors.hairline, Radius.L).padding(Space.L),
                    verticalArrangement = Arrangement.spacedBy(Space.XS),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.S)) {
                        ItemIcon(key, d, 36.dp)
                        Text(d.label(key), Modifier.weight(1f), color = colors.textPrimary, style = AuroraType.Title)
                        Text(
                            stringResource(if (use.runningSince != null) R.string.intent_state_in_use else R.string.intent_state_paused),
                            color = if (use.runningSince != null) colors.accent else colors.textTertiary, style = AuroraType.Label,
                        )
                    }
                    ProgressLine((use.used(now).toFloat() / use.allowanceMs).coerceIn(0f, 1f), Modifier.padding(vertical = Space.XS))
                    Text(
                        stringResource(R.string.intent_session_line, formatDuration(use.used(now)), minutesText((use.allowanceMs / 60_000).toInt()), formatDuration(use.left(now))),
                        color = colors.textSecondary, style = AuroraType.Secondary,
                    )
                    Text(
                        when (use.expiry) {
                            IntentExpiry.Reminder -> stringResource(R.string.intent_expiry_note_reminder)
                            IntentExpiry.Cooldown -> stringResource(R.string.intent_expiry_note_cooldown, minutesText(use.cooldownMinutes))
                        },
                        color = colors.textTertiary, style = AuroraType.Caption,
                    )
                }
            }
            items(cooldowns.entries.toList(), key = { "c-" + it.key }) { (key, until) ->
                AuroraRow(
                    d.label(key),
                    Modifier.padding(horizontal = Space.M),
                    subtitle = stringResource(R.string.intent_cooldown_until, clockTime(until)),
                    leading = { ItemIcon(key, d, 36.dp) },
                )
            }
            item(key = "tracking") {
                Text(stringResource(R.string.intent_tracking_note), Modifier.padding(horizontal = Space.Gutter, vertical = Space.XS), color = colors.textTertiary, style = AuroraType.Caption)
            }
        }

        val apps = d.list.items.filterIsInstance<ProtectedItem.App>().map { it.packageName }
        val on = d.rules.activeIntents(d.plan)
        item(key = "apps-label") { SectionLabel(stringResource(R.string.intent_apps, allowanceText(on.size, d.max(Allowance.IntentApps)))) }
        if (apps.isEmpty()) {
            item(key = "empty") {
                EmptyState(
                    icon = AuroraIcons.Quote,
                    title = stringResource(R.string.intent_empty_title),
                    body = stringResource(R.string.intent_empty_body),
                    action = { AuroraButton(stringResource(R.string.protect_add_apps), onAddApps) },
                )
            }
        } else {
            item(key = "apps") {
                RowGroup {
                    apps.forEachIndexed { i, key ->
                        if (i > 0) RowDivider(72.dp)
                        val config = d.rules.apps[key]?.intent
                        AuroraRow(
                            d.label(key),
                            subtitle = when {
                                config?.enabled != true -> stringResource(R.string.intent_off)
                                key !in on -> stringResource(R.string.intent_paused_free)
                                else -> intentSummary(config)
                            },
                            leading = { ItemIcon(key, d) },
                            onClick = { onOpenApp(key) },
                            trailing = { Chevron() },
                        )
                    }
                    RowDivider(72.dp)
                    AuroraRow(stringResource(R.string.protect_add_apps), leading = { app.aurora.ui.components.IconBadge(AuroraIcons.Plus) }, onClick = onAddApps)
                }
            }
        }
        item(key = "insights") {
            val today = remember(d.time.wall / 60_000) { vm.app.history.value?.day(java.time.LocalDate.now()) }
            if (today != null && today.intentPrompts > 0) {
                Column(Modifier.padding(top = Space.L)) {
                    SectionLabel(stringResource(R.string.intent_today))
                    RowGroup {
                        AuroraRow(stringResource(R.string.intent_stat_prompts, today.intentPrompts), subtitle = stringResource(R.string.intent_stat_let_go, today.intentLetGo, today.intentSessions))
                        RowDivider()
                        AuroraRow(stringResource(R.string.intent_stat_minutes, today.intentChosenMinutes, today.intentUsedMinutes), subtitle = stringResource(R.string.intent_stat_within, today.intentWithin, today.remindersShown, today.intentCooldowns))
                    }
                }
            }
        }
    }
    notice?.let { n ->
        AuroraDialog(n.title, vm::dismissNotice, if (n.plus) stringResource(R.string.plus_see) else stringResource(R.string.action_ok), { vm.dismissNotice(); if (n.plus) onPlus() }, n.body, if (n.plus) stringResource(R.string.action_not_now) else null)
    }
}

@Composable
private fun intentSummary(c: IntentConfig): String = listOf(
    if (c.reflectionSeconds == 0) stringResource(R.string.intent_no_pause) else stringResource(R.string.intent_pause_of, secondsText(c.reflectionSeconds)),
    when (c.expiry) {
        IntentExpiry.Reminder -> stringResource(R.string.intent_expiry_reminder)
        IntentExpiry.Cooldown -> stringResource(R.string.intent_expiry_cooldown_short, minutesText(c.cooldownMinutes))
    },
).joinToString(" · ")

/** The five moments, as a quiet numbered sequence. */
@Composable
private fun IntentSteps() {
    val colors = Aurora.colors
    val steps = listOf(
        R.string.intent_step_pause to R.string.intent_step_pause_body,
        R.string.intent_step_decide to R.string.intent_step_decide_body,
        R.string.intent_step_time to R.string.intent_step_time_body,
        R.string.intent_step_use to R.string.intent_step_use_body,
        R.string.intent_step_follow to R.string.intent_step_follow_body,
    )
    Column(
        Modifier.padding(horizontal = Space.M).fillMaxWidth().clip(Radius.L).background(colors.surface).border(1.dp, colors.hairline, Radius.L).padding(Space.L),
        verticalArrangement = Arrangement.spacedBy(Space.S),
    ) {
        steps.forEachIndexed { i, (title, body) ->
            Row(horizontalArrangement = Arrangement.spacedBy(Space.M)) {
                Box(Modifier.size(28.dp).clip(Radius.Pill).background(if (i == 4) colors.dawnSoft else colors.accentSoft), contentAlignment = Alignment.Center) {
                    Text("${i + 1}", color = if (i == 4) colors.dawnText else colors.accent, style = AuroraType.Label)
                }
                Column(Modifier.weight(1f)) {
                    Text(stringResource(title), color = colors.textPrimary, style = AuroraType.BodyStrong)
                    Text(stringResource(body), color = colors.textSecondary, style = AuroraType.Secondary)
                }
            }
        }
    }
}

/**
 * One app's Intentional App Opening: on or off, the pause, the person's own reason and alternative, a
 * default time, and what happens when the time is up. Changes apply from the next opening; a session or
 * cooldown already running keeps the settings it started with.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun IntentAppScreen(key: String, onBack: () -> Unit, onPlus: () -> Unit) {
    val vm = auroraViewModel { ProtectModel(it) }
    val data by vm.data.collectAsStateWithLifecycle()
    val notice by vm.notice.collectAsStateWithLifecycle()
    val colors = Aurora.colors
    val d = data
    AuroraScreen(title = d?.label(key).orEmpty(), subtitle = stringResource(R.string.intent_name), onBack = onBack) {
        if (d == null || key.startsWith(WebPrefix)) return@AuroraScreen
        val stored = d.rules.apps[key]?.intent
        val c = stored ?: IntentConfig(enabled = false)
        fun save(change: (IntentConfig) -> IntentConfig) = vm.edit { r ->
            val rule = r.rule(key)
            r.copy(apps = r.apps + (key to rule.copy(intent = change(rule.intent ?: IntentConfig(enabled = false)))))
        }
        item(key = "toggle") {
            RowGroup {
                ToggleRow(stringResource(R.string.intent_toggle), stringResource(R.string.intent_toggle_hint), c.enabled, { enable ->
                    if (!enable) save { it.copy(enabled = false) }
                    else vm.enableWithin(Allowance.IntentApps, d.rules.activeIntents(d.plan).size) { r ->
                        val rule = r.rule(key)
                        r.copy(apps = r.apps + (key to rule.copy(intent = (rule.intent ?: IntentConfig()).copy(enabled = true))))
                    }
                    if (enable && key !in d.list.apps) ProtectedItem.App.of(key)?.let(vm::add)
                })
            }
        }
        item(key = "reflect") {
            Column(Modifier.padding(horizontal = Space.Gutter, vertical = Space.L), verticalArrangement = Arrangement.spacedBy(Space.S)) {
                Text(stringResource(R.string.intent_reflection_title), color = colors.textPrimary, style = AuroraType.Title)
                Text(stringResource(R.string.intent_reflection_hint), color = colors.textSecondary, style = AuroraType.Secondary)
                SecondsPicker(c.reflectionSeconds) { s -> save { it.copy(reflectionSeconds = s) } }
            }
        }
        item(key = "words") {
            Column(Modifier.padding(horizontal = Space.Gutter), verticalArrangement = Arrangement.spacedBy(Space.S)) {
                Text(stringResource(R.string.intent_reason_title), color = colors.textPrimary, style = AuroraType.Title)
                var reason by rememberSaveable(key) { mutableStateOf(c.reason) }
                AuroraTextField(reason, { reason = it.take(140); save { s -> s.copy(reason = reason.trim()) } }, stringResource(R.string.intent_reason_placeholder),
                    capitalization = KeyboardCapitalization.Sentences, singleLine = false)
                Text(stringResource(R.string.intent_alternative_title), Modifier.padding(top = Space.S), color = colors.textPrimary, style = AuroraType.Title)
                var alt by rememberSaveable(key) { mutableStateOf(c.alternative) }
                AuroraTextField(alt, { alt = it.take(140); save { s -> s.copy(alternative = alt.trim()) } }, stringResource(R.string.intent_alternative_placeholder),
                    capitalization = KeyboardCapitalization.Sentences, singleLine = false)
            }
        }
        item(key = "default") {
            Column(Modifier.padding(horizontal = Space.Gutter, vertical = Space.L), verticalArrangement = Arrangement.spacedBy(Space.S)) {
                DurationPicker(
                    c.defaultUsageMinutes, { m -> save { it.copy(defaultUsageMinutes = m) } }, listOf(5, 10, 15, 30),
                    title = stringResource(R.string.intent_default_title), hint = stringResource(R.string.intent_default_hint), max = 8 * 60,
                )
                if (c.defaultUsageMinutes != null) AuroraButton(stringResource(R.string.intent_default_clear), { save { it.copy(defaultUsageMinutes = null) } }, kind = ButtonKind.Quiet)
            }
        }
        item(key = "expiry") {
            Column(Modifier.padding(horizontal = Space.M), verticalArrangement = Arrangement.spacedBy(Space.S)) {
                Text(stringResource(R.string.intent_expiry_title), Modifier.padding(horizontal = Space.XS), color = colors.textPrimary, style = AuroraType.Title)
                ChoiceCard(stringResource(R.string.intent_expiry_reminder), stringResource(R.string.intent_expiry_reminder_desc), c.expiry == IntentExpiry.Reminder, { save { it.copy(expiry = IntentExpiry.Reminder) } })
                ChoiceCard(stringResource(R.string.intent_expiry_cooldown), stringResource(R.string.intent_expiry_cooldown_desc), c.expiry == IntentExpiry.Cooldown, { save { it.copy(expiry = IntentExpiry.Cooldown) } })
                if (c.expiry == IntentExpiry.Cooldown) {
                    DurationPicker(
                        c.cooldownMinutes, { m -> if (m != null) save { it.copy(cooldownMinutes = m) } }, IntentCooldownPresets,
                        Modifier.padding(horizontal = Space.XS, vertical = Space.S),
                        title = stringResource(R.string.intent_cooldown_title), hint = stringResource(R.string.intent_cooldown_hint), max = 12 * 60,
                    )
                }
            }
        }
        item(key = "fresh") {
            Column(Modifier.padding(top = Space.L)) {
                RowGroup {
                    ToggleRow(stringResource(R.string.intent_fresh_title), stringResource(R.string.intent_fresh_hint), c.freshEachOpen, { v -> save { it.copy(freshEachOpen = v) } })
                }
            }
        }
        item(key = "note") {
            Text(stringResource(R.string.intent_settings_note), Modifier.padding(Space.Gutter), color = colors.textTertiary, style = AuroraType.Caption)
        }
    }
    notice?.let { n ->
        AuroraDialog(n.title, vm::dismissNotice, if (n.plus) stringResource(R.string.plus_see) else stringResource(R.string.action_ok), { vm.dismissNotice(); if (n.plus) onPlus() }, n.body, if (n.plus) stringResource(R.string.action_not_now) else null)
    }
}

/** Reflection pause: presets in seconds, and a custom number of seconds typed directly. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SecondsPicker(value: Int, onChange: (Int) -> Unit) {
    var custom by rememberSaveable { mutableStateOf(value !in ReflectionPresets) }
    var text by rememberSaveable { mutableStateOf(if (value !in ReflectionPresets) value.toString() else "") }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.XS), verticalArrangement = Arrangement.spacedBy(Space.XS)) {
        ReflectionPresets.forEach { s -> Chip(secondsText(s), !custom && value == s) { custom = false; onChange(s) } }
        Chip(stringResource(R.string.duration_custom), custom) { custom = true }
    }
    if (custom) {
        val n = text.toIntOrNull()
        val valid = n != null && n in 1..600
        AuroraTextField(text, { text = it.filter(Char::isDigit).take(3); text.toIntOrNull()?.takeIf { s -> s in 1..600 }?.let(onChange) },
            stringResource(R.string.intent_seconds_placeholder), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
        Text(
            if (valid || text.isEmpty()) stringResource(R.string.intent_seconds_hint) else stringResource(R.string.intent_seconds_problem),
            color = if (valid || text.isEmpty()) Aurora.colors.textTertiary else Aurora.colors.danger,
            style = AuroraType.Caption,
        )
    }
    Box(Modifier.height(0.dp))
}
