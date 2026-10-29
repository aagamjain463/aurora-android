package app.aurora.ui.protect

import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.aurora.R
import app.aurora.auroraViewModel
import app.aurora.turnOff
import app.aurora.plan.Allowance
import app.aurora.protect.AppRule
import app.aurora.protect.DailyLimit
import app.aurora.protect.Difficulty
import app.aurora.protect.FrictionTask
import app.aurora.protect.Mode
import app.aurora.protect.WebPrefix
import app.aurora.protect.itemOf
import app.aurora.ui.components.AuroraButton
import app.aurora.ui.components.AuroraDialog
import app.aurora.ui.components.AuroraIcons
import app.aurora.ui.components.AuroraRow
import app.aurora.ui.components.AuroraScreen
import app.aurora.ui.components.ButtonKind
import app.aurora.ui.components.Chevron
import app.aurora.ui.components.ChoiceCard
import app.aurora.ui.components.Chip
import app.aurora.ui.components.DurationPicker
import app.aurora.ui.components.RowGroup
import app.aurora.ui.components.SectionLabel
import app.aurora.ui.components.ToggleRow
import app.aurora.ui.components.secondsText
import app.aurora.ui.theme.Aurora
import app.aurora.ui.theme.AuroraType
import app.aurora.ui.theme.Space

val LimitPresets = listOf(15, 30, 60, 90)

/**
 * One protected app or site: its saved preferences (the mode a session starts with, and that mode's
 * settings), a daily limit, Intentional App Opening, groups, and starting protection now. Changing a
 * preference never changes a session that is already running.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ItemScreen(key: String, onBack: () -> Unit, onStart: (String) -> Unit, onIntent: (String) -> Unit, onPlus: () -> Unit) {
    val vm = auroraViewModel { ProtectModel(it) }
    val data by vm.data.collectAsStateWithLifecycle()
    val notice by vm.notice.collectAsStateWithLifecycle()
    var removing by remember { mutableStateOf(false) }
    val d = data
    val website = key.startsWith(WebPrefix)
    val colors = Aurora.colors

    AuroraScreen(
        title = d?.label(key).orEmpty(),
        subtitle = if (website) stringResource(R.string.item_website) else null,
        onBack = onBack,
        header = {
            Row(Modifier.padding(horizontal = Space.Gutter, vertical = Space.S), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.M)) {
                ItemIcon(key, d, size = 64.dp)
                Column {
                    Text(d?.label(key).orEmpty(), color = colors.textPrimary, style = AuroraType.Display)
                    if (website) Text(stringResource(R.string.item_website), color = colors.textSecondary, style = AuroraType.Secondary)
                }
            }
        },
    ) {
        if (d == null) return@AuroraScreen
        val rule = d.rules.rule(key)
        fun save(change: (AppRule) -> AppRule) = vm.edit { r -> r.copy(apps = r.apps + (key to change(r.rule(key)))) }
        val running = d.sessions.filter { key in it.keys }

        items(running, key = { "s-" + it.id }) { s -> SessionCard(s, d, onTurnOff = { vm.run { vm.app.turnOff(s.id) } }, Modifier.padding(bottom = Space.S)) }
        item(key = "start") {
            AuroraButton(stringResource(R.string.item_start, d.label(key)), { onStart(key) }, Modifier.padding(horizontal = Space.M, vertical = Space.M).fillMaxWidth(), icon = AuroraIcons.Protect)
        }

        item(key = "mode-label") { SectionLabel(stringResource(R.string.item_preferred_mode)) }
        item(key = "mode") {
            Column(Modifier.padding(horizontal = Space.M), verticalArrangement = Arrangement.spacedBy(Space.S)) {
                Text(stringResource(R.string.item_preferred_mode_hint), color = colors.textSecondary, style = AuroraType.Secondary)
                Mode.entries.forEach { m -> ChoiceCard(modeName(m), modeDescription(m), rule.mode == m, { save { it.copy(mode = m) } }, swatch = modeColor(m)) }
                when (rule.mode) {
                    Mode.Friction -> {
                        Text(stringResource(R.string.item_task), Modifier.padding(top = Space.S), color = colors.textPrimary, style = AuroraType.Title)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.XS), verticalArrangement = Arrangement.spacedBy(Space.XS)) {
                            FrictionTask.entries.forEach { t -> Chip(taskName(t), rule.friction.task == t) { save { it.copy(friction = it.friction.copy(task = t)) } } }
                        }
                        if (rule.friction.task == FrictionTask.Math) FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.XS)) {
                            Difficulty.entries.forEach { dif -> Chip(difficultyName(dif), rule.friction.difficulty == dif) { save { it.copy(friction = it.friction.copy(difficulty = dif)) } } }
                        }
                        if (rule.friction.task == FrictionTask.Reflection) FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.XS)) {
                            ReflectionPauses.forEach { p -> Chip(secondsText(p), rule.friction.reflectionSeconds == p) { save { it.copy(friction = it.friction.copy(reflectionSeconds = p)) } } }
                        }
                    }
                    Mode.Cooldown -> DurationPicker(
                        rule.cooldownWaitMinutes, { m -> if (m != null) save { it.copy(cooldownWaitMinutes = m) } }, CooldownWaitPresets,
                        Modifier.padding(top = Space.S), title = stringResource(R.string.duration_cooldown_prompt), hint = stringResource(R.string.duration_cooldown_hint), max = 12 * 60,
                    )
                    else -> Unit
                }
            }
        }

        if (!website) {
            item(key = "intent") {
                Column(Modifier.padding(top = Space.L)) {
                    RowGroup {
                        val on = key in d.rules.activeIntents(d.plan)
                        AuroraRow(
                            stringResource(R.string.intent_name),
                            subtitle = stringResource(if (on) R.string.item_intent_on else R.string.item_intent_off),
                            icon = AuroraIcons.Quote,
                            onClick = { onIntent(key) },
                            trailing = { Chevron() },
                        )
                    }
                }
            }
            item(key = "limit-label") { SectionLabel(stringResource(R.string.limits_name)) }
            item(key = "limit") {
                val limit = d.rules.limits.find { it.key == key }
                val on = limit?.enabled == true
                Column(verticalArrangement = Arrangement.spacedBy(Space.S)) {
                    RowGroup {
                        ToggleRow(stringResource(R.string.item_limit_toggle), stringResource(R.string.item_limit_hint), on, { enable ->
                            if (!enable) vm.edit { r -> r.copy(limits = r.limits.map { if (it.key == key) it.copy(enabled = false) else it }) }
                            else vm.enableWithin(Allowance.DailyLimitRules, d.rules.activeLimits(d.plan).size) { r ->
                                r.copy(limits = r.limits.filterNot { it.key == key } + (limit?.copy(enabled = true) ?: DailyLimit(key, 30)))
                            }
                        })
                    }
                    if (on) DurationPicker(
                        limit!!.minutes, { m -> if (m != null) vm.edit { r -> r.copy(limits = r.limits.map { if (it.key == key) it.copy(minutes = m) else it }) } },
                        LimitPresets, Modifier.padding(horizontal = Space.Gutter), title = stringResource(R.string.item_limit_prompt), max = 23 * 60,
                    )
                }
            }
        }

        if (d.rules.groups.isNotEmpty()) {
            item(key = "groups-label") { SectionLabel(stringResource(R.string.groups_name)) }
            item(key = "groups") {
                FlowRow(Modifier.padding(horizontal = Space.Gutter), horizontalArrangement = Arrangement.spacedBy(Space.XS), verticalArrangement = Arrangement.spacedBy(Space.XS)) {
                    d.rules.groups.forEach { g ->
                        Chip(g.name, key in g.keys) {
                            vm.edit { r -> r.copy(groups = r.groups.map { if (it.id == g.id) it.copy(keys = if (key in it.keys) it.keys - key else it.keys + key) else it }) }
                        }
                    }
                }
            }
        }

        item(key = "remove") {
            AuroraButton(stringResource(R.string.item_remove), { removing = true }, Modifier.padding(Space.M).fillMaxWidth(), kind = ButtonKind.Quiet, icon = AuroraIcons.Trash)
        }
    }

    if (removing && d != null) {
        val committed = d.sessions.any { key in it.keys && !it.cancellable }
        AuroraDialog(
            title = stringResource(R.string.item_remove_title, d.label(key)),
            body = stringResource(if (committed) R.string.item_remove_body_committed else R.string.item_remove_body),
            confirmLabel = stringResource(R.string.item_remove),
            onConfirm = { removing = false; itemOf(key)?.let(vm::remove); onBack() },
            dismissLabel = stringResource(R.string.action_cancel),
            onDismiss = { removing = false },
        )
    }
    notice?.let { n ->
        AuroraDialog(n.title, vm::dismissNotice, if (n.plus) stringResource(R.string.plus_see) else stringResource(R.string.action_ok), { vm.dismissNotice(); if (n.plus) onPlus() }, n.body, if (n.plus) stringResource(R.string.action_not_now) else null)
    }
}
