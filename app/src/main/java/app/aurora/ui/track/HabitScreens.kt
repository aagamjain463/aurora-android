package app.aurora.ui.track

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.aurora.R
import app.aurora.auroraViewModel
import app.aurora.data.formatDuration
import app.aurora.deleteHabit
import app.aurora.newId
import app.aurora.progress.Habit
import app.aurora.progress.HabitCriterion
import app.aurora.progress.HabitTarget
import app.aurora.progress.habitProgress
import app.aurora.protect.Timing
import app.aurora.protect.UsageConfig
import app.aurora.protect.intentOf
import app.aurora.protect.shieldOf
import app.aurora.saveHabit
import app.aurora.setHabitArchived
import app.aurora.ui.components.AuroraButton
import app.aurora.ui.components.AuroraScreen
import app.aurora.ui.components.AuroraTextField
import app.aurora.ui.components.ButtonKind
import app.aurora.ui.components.Chip
import app.aurora.ui.components.ChipFlow
import app.aurora.ui.components.ChoiceCard
import app.aurora.ui.components.MetricGrid
import app.aurora.ui.components.SectionLabel
import app.aurora.ui.components.auroraSurface
import app.aurora.ui.library.PickRow
import app.aurora.ui.library.StepFlow
import app.aurora.ui.library.stepTitle
import app.aurora.ui.protect.ProtectModel
import app.aurora.ui.protect.dayShort
import app.aurora.ui.protect.daysText
import app.aurora.ui.theme.Aurora
import app.aurora.ui.theme.AuroraType
import app.aurora.ui.theme.Space
import java.time.LocalDate
import java.time.ZoneId

private enum class HabitStep { Name, Config, Target, Success }
private enum class TargetKind { Inherit, Days, PerWeek, OnDemand }

/** The configuration's recurring days, if it has an enabled schedule. */
private fun scheduledDays(rules: app.aurora.protect.Rules, configId: String?): Set<Int>? =
    rules.activations.filter { it.configId == configId && it.enabled }.mapNotNull { it.timing as? Timing.Recurring }.flatMap { it.days }.toSet().ifEmpty { null }

/**
 * Create or edit a habit: a name, one saved custom configuration, how often, and what counts. Creating
 * starts nothing. [createdConfig] is a configuration the person just built on Home from here.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HabitEditorScreen(id: String?, createdConfig: String?, onBack: () -> Unit, onSaved: (String) -> Unit, onCreateConfig: () -> Unit, startStep: Int = 0) {
    val vm = auroraViewModel { ProtectModel(it) }
    val data by vm.data.collectAsStateWithLifecycle()
    val d = data ?: return
    val colors = Aurora.colors
    val existing = id?.let { i -> d.rules.habits.find { it.id == i } }
    val configs = d.rules.configs.filter { it.custom }.sortedByDescending { it.createdAt }

    var name by rememberSaveable { mutableStateOf(existing?.name.orEmpty()) }
    var configId by rememberSaveable { mutableStateOf(existing?.configId) }
    var kind by rememberSaveable { mutableStateOf(existing?.target?.let { if (it.perWeek != null) TargetKind.PerWeek else if (it.onDemand) TargetKind.OnDemand else TargetKind.Days }) }
    var days by rememberSaveable { mutableStateOf(existing?.target?.days ?: setOf(1, 2, 3, 4, 5)) }
    var perWeek by rememberSaveable { mutableIntStateOf(existing?.target?.perWeek ?: 3) }
    var criterion by rememberSaveable { mutableStateOf(existing?.criterion ?: HabitCriterion.All) }
    var step by rememberSaveable { mutableStateOf(HabitStep.entries[startStep]) }
    LaunchedEffect(createdConfig) { if (createdConfig != null) configId = createdConfig }

    val config = configs.find { it.id == configId }
    val scheduled = scheduledDays(d.rules, configId)
    val chosenDays = if (kind == TargetKind.Inherit) scheduled.orEmpty() else days
    val steps = HabitStep.entries
    val index = steps.indexOf(step)
    val back = { if (index == 0) onBack() else step = steps[index - 1] }
    val canNext = when (step) {
        HabitStep.Name -> name.isNotBlank()
        HabitStep.Config -> config != null
        HabitStep.Target -> kind != null && (kind != TargetKind.Days || days.isNotEmpty()) && (kind != TargetKind.Inherit || scheduled != null)
        HabitStep.Success -> true
    }
    StepFlow(
        steps.size, index, back,
        primary = stringResource(if (step == HabitStep.Success) R.string.habit_save else R.string.action_next),
        primaryEnabled = canNext,
        onPrimary = {
            if (step != HabitStep.Success) step = steps[index + 1]
            else {
                val c = config ?: return@StepFlow
                val target = when (kind) {
                    TargetKind.PerWeek -> HabitTarget(LocalDate.now().toString(), perWeek = perWeek)
                    TargetKind.OnDemand -> HabitTarget(LocalDate.now().toString())
                    else -> HabitTarget(LocalDate.now().toString(), days = chosenDays)
                }
                // Editing adds a target from today, so earlier days keep the target they had.
                val targets = existing?.targets?.let { old -> if (old.last().copy(since = target.since) == target) old else old.filterNot { it.since == target.since } + target } ?: listOf(target)
                val habit = Habit(existing?.id ?: newId(), name.trim(), c.id, c.name, targets, criterion, existing?.createdAt ?: System.currentTimeMillis(), existing?.archived ?: false)
                vm.run {
                    vm.app.saveHabit(habit)
                    onSaved(habit.id)
                }
            }
        },
        footer = if (step == HabitStep.Success) ({ Text(stringResource(R.string.habit_save_note), color = colors.textTertiary, style = AuroraType.Caption) }) else null,
    ) {
        when (step) {
            HabitStep.Name -> {
                stepTitle(R.string.habit_name_title, null)
                item(key = "name") { AuroraTextField(name, { name = it.take(40) }, stringResource(R.string.habit_name_placeholder), capitalization = KeyboardCapitalization.Sentences) }
                item(key = "ideas") {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.XS), verticalArrangement = Arrangement.spacedBy(Space.XS)) {
                        listOf("Morning without social media", "Daily Study", "No Scrolling at Night", "Intentional YouTube", "Weekend Digital Detox").forEach { Chip(it, name == it) { name = it } }
                    }
                }
            }
            HabitStep.Config -> {
                stepTitle(R.string.habit_config_title, R.string.habit_config_body)
                if (configs.isEmpty()) item(key = "none") { Text(stringResource(R.string.habit_config_none), color = colors.textSecondary, style = AuroraType.Body) }
                items(configs, key = { "c-" + it.id }) { c -> PickRow(c.name, configSummary(d.rules, c), configId == c.id, { configId = c.id }) }
                item(key = "create") { AuroraButton(stringResource(R.string.habit_config_create), onCreateConfig, Modifier.fillMaxWidth().padding(top = Space.S), kind = ButtonKind.Secondary) }
            }
            HabitStep.Target -> {
                stepTitle(R.string.habit_target_title, null)
                if (scheduled != null) item(key = "inherit") {
                    ChoiceCard(stringResource(R.string.habit_target_inherit), daysText(scheduled), kind == TargetKind.Inherit, { kind = TargetKind.Inherit })
                }
                item(key = "days") { ChoiceCard(stringResource(R.string.habit_target_days), daysText(days), kind == TargetKind.Days, { kind = TargetKind.Days }) }
                if (kind == TargetKind.Days) item(key = "days-pick") {
                    Column(verticalArrangement = Arrangement.spacedBy(Space.XS)) {
                        ChipFlow((1..7).toList(), days, { dd -> days = if (dd in days) days - dd else days + dd }, { dayShort(it) })
                        val off = scheduled?.let { days - it }.orEmpty()
                        if (scheduled == null) Text(stringResource(R.string.habit_target_unscheduled), color = colors.dawnText, style = AuroraType.Secondary)
                        else if (off.isNotEmpty()) Text(stringResource(R.string.habit_target_mismatch, daysText(scheduled)), color = colors.dawnText, style = AuroraType.Secondary)
                    }
                }
                item(key = "per-week") { ChoiceCard(stringResource(R.string.habit_target_per_week), pluralStringResource(R.plurals.habit_per_week_value, perWeek, perWeek), kind == TargetKind.PerWeek, { kind = TargetKind.PerWeek }) }
                if (kind == TargetKind.PerWeek) item(key = "per-week-pick") {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.XS), verticalArrangement = Arrangement.spacedBy(Space.XS)) {
                        (1..7).forEach { n -> Chip("$n", perWeek == n) { perWeek = n } }
                    }
                }
                item(key = "on-demand") { ChoiceCard(stringResource(R.string.habit_target_on_demand), stringResource(R.string.habit_target_on_demand_desc), kind == TargetKind.OnDemand, { kind = TargetKind.OnDemand }) }
            }
            HabitStep.Success -> {
                stepTitle(R.string.habit_success_title, null)
                val c = config
                val options = buildList {
                    add(Triple(HabitCriterion.All, R.string.habit_success_all, R.string.habit_success_all_desc))
                    if (c != null && d.rules.shieldOf(c) != null) add(Triple(HabitCriterion.Protection, R.string.habit_success_protection, R.string.habit_success_protection_desc))
                    if (c != null && d.rules.intentOf(c) != null) add(Triple(HabitCriterion.Intent, R.string.habit_success_intent, R.string.habit_success_intent_desc))
                    if (c?.focus != null) add(Triple(HabitCriterion.Focus, R.string.habit_success_focus, R.string.habit_success_focus_desc))
                }
                items(options, key = { "crit-" + it.first.name }) { (crit, title, body) ->
                    ChoiceCard(stringResource(title), stringResource(body), criterion == crit, { criterion = crit })
                }
            }
        }
    }
}

@Composable
private fun configSummary(rules: app.aurora.protect.Rules, c: UsageConfig): String =
    listOfNotNull(rules.shieldOf(c)?.name, rules.intentOf(c)?.name, c.focus?.task).joinToString(" · ")

/** One habit, in full: ring, streaks, the calendar of the last five weeks, weekly trend, and its rules. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HabitScreen(id: String, onBack: () -> Unit, onEdit: () -> Unit) {
    val vm = auroraViewModel { ProtectModel(it) }
    val data by vm.data.collectAsStateWithLifecycle()
    val colors = Aurora.colors
    val d = data
    val habit = d?.rules?.habits?.find { it.id == id }
    AuroraScreen(title = habit?.name.orEmpty(), subtitle = habit?.let { stringResource(R.string.habit_linked, it.configName) }, onBack = onBack) {
        if (d == null || habit == null) return@AuroraScreen
        val p = habitProgress(habit, d.history.runs, LocalDate.now(), ZoneId.systemDefault())
        val linked = d.rules.configs.any { it.id == habit.configId }
        item(key = "ring") {
            Row(
                Modifier.padding(horizontal = Space.M).fillMaxWidth().auroraSurface(colors, warm = true).padding(Space.L),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.L),
            ) {
                HabitRing(p.rate, 132.dp, stringResource(R.string.habit_rate))
                Column(verticalArrangement = Arrangement.spacedBy(Space.XS)) {
                    Text(stringResource(R.string.habit_streak), color = colors.textSecondary, style = AuroraType.Caption)
                    Text(pluralStringResource(if (p.streakUnitWeeks) R.plurals.habit_weeks_unit else R.plurals.habit_days_unit, p.currentStreak, p.currentStreak), color = colors.sun, style = AuroraType.Headline)
                    Text(stringResource(R.string.habit_longest) + " · " + pluralStringResource(if (p.streakUnitWeeks) R.plurals.habit_weeks_unit else R.plurals.habit_days_unit, p.longestStreak, p.longestStreak), color = colors.textSecondary, style = AuroraType.Secondary)
                    Text(targetText(habit.target), color = colors.textTertiary, style = AuroraType.Caption)
                }
            }
        }
        if (!linked) item(key = "unlinked") { Text(stringResource(R.string.habit_unlinked), Modifier.padding(Space.Gutter), color = colors.dawnText, style = AuroraType.Secondary) }
        item(key = "metrics") {
            Column(Modifier.padding(top = Space.M)) {
                MetricGrid(
                    listOf(
                        p.successes.toString() to stringResource(R.string.habit_successes),
                        (if (p.expected == null) null else p.missed.toString()) to stringResource(R.string.habit_missed),
                        (if (p.verifiedMs == 0L) "0m" else formatDuration(p.verifiedMs)) to stringResource(R.string.habit_verified),
                        (if (p.focusMs == 0L) "0m" else formatDuration(p.focusMs)) to stringResource(R.string.habit_focus),
                    ),
                )
            }
        }
        item(key = "calendar") {
            SectionLabel(stringResource(R.string.habit_calendar))
            Column(Modifier.padding(horizontal = Space.M).fillMaxWidth().auroraSurface(colors).padding(Space.L), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    p.calendar.take(7).forEach { (date, _) -> Text(dayShort(date.dayOfWeek.value).take(2), Modifier.weight(1f), color = colors.textTertiary, style = AuroraType.Caption) }
                }
                p.calendar.chunked(7).forEach { week ->
                    Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        week.forEach { (_, m) -> Row(Modifier.weight(1f)) { DayDot(m, 22.dp) } }
                    }
                }
            }
        }
        item(key = "trend") {
            SectionLabel(stringResource(R.string.habit_trend))
            WeekBars(p.weeks)
        }
        item(key = "rules") { Text(stringResource(R.string.habit_rules), Modifier.padding(Space.Gutter), color = colors.textTertiary, style = AuroraType.Caption) }
        item(key = "actions") {
            Column(Modifier.padding(horizontal = Space.M), verticalArrangement = Arrangement.spacedBy(Space.XS)) {
                if (!habit.archived && linked) AuroraButton(stringResource(R.string.habit_edit), onEdit, Modifier.fillMaxWidth(), kind = ButtonKind.Secondary)
                AuroraButton(
                    stringResource(if (habit.archived) R.string.habit_restore else R.string.habit_archive),
                    { vm.run { vm.app.setHabitArchived(habit.id, !habit.archived) } },
                    Modifier.fillMaxWidth(), kind = ButtonKind.Quiet,
                    enabled = !habit.archived || linked,
                )
                if (habit.archived) AuroraButton(stringResource(R.string.habit_delete), { vm.run { vm.app.deleteHabit(habit.id) }; onBack() }, Modifier.fillMaxWidth(), kind = ButtonKind.Danger)
            }
        }
    }
}

@Composable
private fun targetText(t: HabitTarget): String = when {
    t.perWeek != null -> pluralStringResource(R.plurals.habit_per_week_value, t.perWeek, t.perWeek)
    t.onDemand -> stringResource(R.string.habit_target_on_demand)
    else -> daysText(t.days)
}

/** Successes per week, quiet bars with the current week lit. */
@Composable
private fun WeekBars(weeks: List<Int>) {
    val colors = Aurora.colors
    val max = (weeks.maxOrNull() ?: 0).coerceAtLeast(1)
    Column(Modifier.padding(horizontal = Space.M).fillMaxWidth().auroraSurface(colors).padding(Space.L).semantics { contentDescription = weeks.joinToString(", ") }) {
        Canvas(Modifier.fillMaxWidth().height(96.dp)) {
            val slot = size.width / weeks.size
            val bar = (slot * 0.5f).coerceAtMost(22.dp.toPx())
            weeks.forEachIndexed { i, n ->
                val h = if (n == 0) 3.dp.toPx() else (size.height - 4.dp.toPx()) * n / max
                val x = slot * i + (slot - bar) / 2
                drawRoundRect(
                    if (i == weeks.lastIndex) colors.sun else if (n == 0) colors.hairline else colors.accent.copy(alpha = 0.75f),
                    Offset(x, size.height - h), Size(bar, h), CornerRadius(bar / 2),
                )
            }
        }
    }
}
