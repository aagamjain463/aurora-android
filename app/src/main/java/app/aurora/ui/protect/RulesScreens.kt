package app.aurora.ui.protect

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.aurora.R
import app.aurora.auroraViewModel
import app.aurora.plan.Allowance
import app.aurora.plan.Plan
import app.aurora.protect.Difficulty
import app.aurora.protect.FrictionSettings
import app.aurora.protect.FrictionTask
import app.aurora.protect.Group
import app.aurora.protect.Mode
import app.aurora.protect.Rules
import app.aurora.protect.Schedule
import app.aurora.protect.cover
import app.aurora.protect.covers
import app.aurora.protect.key
import app.aurora.protect.nextStart
import app.aurora.protect.windowAt
import app.aurora.ui.components.AuroraButton
import app.aurora.ui.components.AuroraDialog
import app.aurora.ui.components.AuroraIconButton
import app.aurora.ui.components.AuroraIcons
import app.aurora.ui.components.AuroraRow
import app.aurora.ui.components.AuroraScreen
import app.aurora.ui.components.AuroraSwitch
import app.aurora.ui.components.AuroraTextField
import app.aurora.ui.components.ButtonKind
import app.aurora.ui.components.Chevron
import app.aurora.ui.components.ChipFlow
import app.aurora.ui.components.ChoiceCard
import app.aurora.ui.components.Chip
import app.aurora.ui.components.DurationPicker
import app.aurora.ui.components.EmptyState
import app.aurora.ui.components.ModeGlyph
import app.aurora.ui.components.RowDivider
import app.aurora.ui.components.RowGroup
import app.aurora.ui.components.SectionLabel
import app.aurora.ui.components.SelectionMark
import app.aurora.ui.components.SkeletonRows
import app.aurora.ui.components.allowanceText
import app.aurora.ui.components.clockTime
import app.aurora.ui.components.minutesText
import app.aurora.ui.components.secondsText
import app.aurora.ui.theme.Aurora
import app.aurora.ui.theme.AuroraType
import app.aurora.ui.theme.Radius
import app.aurora.ui.theme.Space
import java.time.DayOfWeek
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle
import java.util.Locale
import java.util.UUID

private val Week = (1..7).toList()
private val Weekdays = setOf(1, 2, 3, 4, 5)

fun dayShort(day: Int): String = DayOfWeek.of(day).getDisplayName(TextStyle.SHORT, Locale.getDefault())

/** "9:00 AM", in the person's own clock format. */
fun minuteOfDay(minute: Int): String = LocalTime.of(minute / 60 % 24, minute % 60).format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT))

/** "Every weekday", "Every day", "Mon, Wed, Fri". */
@Composable
fun daysText(days: Set<Int>): String = when (days) {
    Week.toSet() -> stringResource(R.string.schedule_every_day)
    Weekdays -> stringResource(R.string.schedule_weekdays)
    setOf(6, 7) -> stringResource(R.string.schedule_weekends)
    else -> days.sorted().joinToString(", ") { dayShort(it) }
}

/** The plain-language summary of a schedule, shown on the list and as the editor's last step. */
@Composable
fun scheduleSentence(s: Schedule, rules: Rules, active: Set<String>, label: (String) -> String): String {
    val names = rules.covers(s, active).map(label).filter { it.isNotBlank() }
    val what = when {
        names.isEmpty() -> stringResource(R.string.schedule_no_apps)
        names.size <= 3 -> names.joinToString(", ")
        else -> stringResource(R.string.schedule_apps_more, names.take(2).joinToString(", "), names.size - 2)
    }
    return stringResource(R.string.schedule_sentence, what, modeName(s.mode), daysText(s.days).replaceFirstChar { it.lowercase() }, minuteOfDay(s.startMinute), minuteOfDay(s.endMinute))
}

/** Recurring protection windows, each with its own apps and mode. */
@Composable
fun SchedulesScreen(onBack: () -> Unit, onEdit: (String?) -> Unit, onPlus: () -> Unit) {
    val vm = auroraViewModel { ProtectModel(it) }
    val data by vm.data.collectAsStateWithLifecycle()
    val notice by vm.notice.collectAsStateWithLifecycle()
    val d = data
    val colors = Aurora.colors
    AuroraScreen(title = stringResource(R.string.schedules_name), subtitle = stringResource(R.string.schedules_subtitle), onBack = onBack) {
        if (d == null) {
            item { SkeletonRows(3) }
            return@AuroraScreen
        }
        val zone = ZoneId.systemDefault()
        if (d.rules.schedules.isEmpty()) item(key = "empty") {
            EmptyState(icon = AuroraIcons.Calendar, title = stringResource(R.string.schedules_empty_title), body = stringResource(R.string.schedules_empty_body))
        }
        d.rules.schedules.forEach { s ->
            item(key = s.id) {
                val active = s in d.rules.activeSchedules(d.plan)
                val running = active && s.windowAt(d.time.wall, zone) != null
                val next = if (active) s.nextStart(d.time.wall, zone) else null
                Column(
                    Modifier.padding(horizontal = Space.M).padding(bottom = Space.S).fillMaxWidth().clip(Radius.L).background(colors.surface)
                        .border(1.dp, if (running) modeColor(s.mode) else colors.hairline, Radius.L)
                        .clickable(role = Role.Button) { onEdit(s.id) }.padding(Space.L),
                    verticalArrangement = Arrangement.spacedBy(Space.XS),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.XS)) {
                        ModeGlyph(modeColor(s.mode), size = 12)
                        Text(modeName(s.mode), Modifier.weight(1f), color = modeColor(s.mode), style = AuroraType.Eyebrow)
                        val toggleLabel = stringResource(if (s.enabled) R.string.schedule_on else R.string.schedule_off)
                        Box(Modifier.semantics { stateDescription = toggleLabel }.clickable(role = Role.Switch) {
                            if (s.enabled) vm.edit { r -> r.copy(schedules = r.schedules.map { if (it.id == s.id) it.copy(enabled = false) else it }) }
                            else vm.enableWithin(Allowance.Schedules, d.rules.activeSchedules(d.plan).size) { r -> r.copy(schedules = r.schedules.map { if (it.id == s.id) it.copy(enabled = true) else it }) }
                        }) { AuroraSwitch(s.enabled) }
                    }
                    Text(s.name, color = colors.textPrimary, style = AuroraType.Title)
                    Text(scheduleSentence(s, d.rules, d.activeKeys, d::label), color = colors.textSecondary, style = AuroraType.Secondary)
                    Text(
                        when {
                            !s.enabled -> stringResource(R.string.schedule_off)
                            !active -> stringResource(R.string.schedule_paused_free)
                            running -> stringResource(R.string.schedule_running_until, clockTime(s.windowAt(d.time.wall, zone)!!.second))
                            next != null -> stringResource(R.string.schedule_next, clockTime(next))
                            else -> ""
                        },
                        color = if (running) modeColor(s.mode) else colors.textTertiary,
                        style = AuroraType.Label,
                    )
                }
            }
        }
        item(key = "add") {
            AuroraButton(stringResource(R.string.schedule_new), {
                if (vm.app.canEnableSchedule(d)) onEdit(null) else vm.atLimit(Allowance.Schedules)
            }, Modifier.padding(Space.M).fillMaxWidth(), icon = AuroraIcons.Plus)
        }
        item(key = "note") {
            Text(stringResource(R.string.schedules_note), Modifier.padding(horizontal = Space.Gutter), color = colors.textTertiary, style = AuroraType.Caption)
        }
    }
    notice?.let { n ->
        AuroraDialog(n.title, vm::dismissNotice, if (n.plus) stringResource(R.string.plus_see) else stringResource(R.string.action_ok), { vm.dismissNotice(); if (n.plus) onPlus() }, n.body, if (n.plus) stringResource(R.string.action_not_now) else null)
    }
}

private fun app.aurora.AuroraApplication.canEnableSchedule(d: ProtectData) = app.aurora.plan.canAdd(plan, Allowance.Schedules, d.rules.activeSchedules(d.plan).size)

private enum class ScheduleStep { Name, Days, Times, Apps, Mode, Settings, Summary }

/**
 * The schedule editor, one decision per step: name, days, start and end, apps or groups, mode, that
 * mode's settings, and a plain summary. Saving an edit commits a running Always Block window first,
 * so changing or deleting a schedule never ends one early.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ScheduleEditorScreen(id: String?, onBack: () -> Unit) {
    val vm = auroraViewModel { ProtectModel(it) }
    val data by vm.data.collectAsStateWithLifecycle()
    val d = data ?: return
    val colors = Aurora.colors
    val original = d.rules.schedules.find { it.id == id }
    val base = original ?: Schedule(UUID.randomUUID().toString(), "", Weekdays, 9 * 60, 17 * 60, keys = d.activeKeys.toList())
    // A Phase 3 schedule covered everything or a group: show what it covers as explicit choices.
    val startKeys = if (original != null && original.legacy) d.rules.covers(original, d.activeKeys).toList() else base.keys

    var step by rememberSaveable { mutableStateOf(ScheduleStep.Name) }
    var name by rememberSaveable { mutableStateOf(base.name) }
    var days by rememberSaveable { mutableStateOf(base.days.toList()) }
    var start by rememberSaveable { mutableStateOf(base.startMinute) }
    var end by rememberSaveable { mutableStateOf(base.endMinute) }
    var keys by rememberSaveable { mutableStateOf(startKeys) }
    var groupIds by rememberSaveable { mutableStateOf(base.groupIds) }
    var mode by rememberSaveable { mutableStateOf(base.mode) }
    var task by rememberSaveable { mutableStateOf(base.friction.task) }
    var difficulty by rememberSaveable { mutableStateOf(base.friction.difficulty) }
    var pause by rememberSaveable { mutableStateOf(base.friction.reflectionSeconds) }
    var wait by rememberSaveable { mutableStateOf<Int?>(base.cooldownWaitMinutes) }
    var deleting by remember { mutableStateOf(false) }

    val steps = ScheduleStep.entries.filter { it != ScheduleStep.Settings || mode == Mode.Friction || mode == Mode.Cooldown }
    val index = steps.indexOf(step).coerceAtLeast(0)
    val back = { if (index == 0) onBack() else step = steps[index - 1] }
    BackHandler(onBack = back)
    val draft = base.copy(
        name = name.trim().ifBlank { "Schedule" }, days = days.toSet(), startMinute = start, endMinute = end,
        groupId = null, keys = keys, groupIds = groupIds, mode = mode,
        friction = FrictionSettings(task, difficulty, pause), cooldownWaitMinutes = wait ?: 15,
    )
    val runningCommitted = original?.let { it.mode == Mode.AlwaysBlock && it.windowAt(d.time.wall, ZoneId.systemDefault()) != null } == true

    Column(Modifier.fillMaxSize().background(colors.background).statusBarsPadding().navigationBarsPadding().imePadding()) {
        Row(Modifier.fillMaxWidth().padding(Space.XS), verticalAlignment = Alignment.CenterVertically) {
            AuroraIconButton(AuroraIcons.Back, stringResource(R.string.action_back), back)
            Row(Modifier.weight(1f).padding(horizontal = Space.S), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                steps.forEachIndexed { i, _ -> Box(Modifier.weight(1f).height(4.dp).clip(Radius.Pill).background(if (i <= index) colors.accent else colors.hairline)) }
            }
            Text("${index + 1}/${steps.size}", Modifier.padding(end = Space.M), color = colors.textTertiary, style = AuroraType.Caption)
        }
        AnimatedContent(step, Modifier.weight(1f), transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "schedule") { s ->
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = Space.Gutter, vertical = Space.M), verticalArrangement = Arrangement.spacedBy(Space.S)) {
                when (s) {
                    ScheduleStep.Name -> {
                        Title(stringResource(R.string.schedule_step_name), stringResource(R.string.schedule_step_name_body))
                        AuroraTextField(name, { if (it.length <= 32) name = it }, stringResource(R.string.schedule_name_placeholder), capitalization = KeyboardCapitalization.Sentences)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.XS), verticalArrangement = Arrangement.spacedBy(Space.XS)) {
                            listOf(R.string.schedule_name_study, R.string.schedule_name_work, R.string.schedule_name_sleep, R.string.schedule_name_morning).forEach { res ->
                                val n = stringResource(res)
                                Chip(n, name == n) { name = n }
                            }
                        }
                    }
                    ScheduleStep.Days -> {
                        Title(stringResource(R.string.schedule_step_days), null)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.XS), verticalArrangement = Arrangement.spacedBy(Space.XS)) {
                            Week.forEach { day -> Chip(dayShort(day), day in days, spoken = DayOfWeek.of(day).getDisplayName(TextStyle.FULL, Locale.getDefault())) { days = if (day in days) days - day else (days + day).sorted() } }
                        }
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.XS)) {
                            AuroraButton(stringResource(R.string.schedule_weekdays), { days = Weekdays.toList() }, kind = ButtonKind.Quiet)
                            AuroraButton(stringResource(R.string.schedule_every_day), { days = Week }, kind = ButtonKind.Quiet)
                        }
                    }
                    ScheduleStep.Times -> {
                        Title(stringResource(R.string.schedule_step_times), stringResource(R.string.schedule_step_times_body))
                        TimeEntry(stringResource(R.string.schedule_start), start) { start = it }
                        TimeEntry(stringResource(R.string.schedule_end), end) { end = it }
                        val length = ((end - start) % 1440 + 1440) % 1440
                        Text(
                            if (length == 0) stringResource(R.string.schedule_length_day)
                            else stringResource(if (end <= start) R.string.schedule_length_overnight else R.string.schedule_length, minutesText(length)),
                            color = colors.accent, style = AuroraType.BodyStrong,
                        )
                    }
                    ScheduleStep.Apps -> {
                        Title(stringResource(R.string.schedule_step_apps), stringResource(R.string.schedule_step_apps_body))
                        d.list.items.map { it.key }.forEach { key ->
                            val on = key in keys
                            Row(
                                Modifier.fillMaxWidth().clip(Radius.M).background(if (on) colors.elevated else colors.surface)
                                    .border(1.dp, if (on) colors.accent else colors.hairline, Radius.M)
                                    .clickable(role = Role.Checkbox) { keys = if (on) keys - key else keys + key }.padding(Space.S),
                                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.M),
                            ) {
                                ItemIcon(key, d, 36.dp)
                                Text(d.label(key), Modifier.weight(1f), color = colors.textPrimary, style = AuroraType.BodyStrong)
                                SelectionMark(on)
                            }
                        }
                        if (d.rules.groups.isNotEmpty()) {
                            Text(stringResource(R.string.groups_name), Modifier.padding(top = Space.S), color = colors.textPrimary, style = AuroraType.Title)
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.XS), verticalArrangement = Arrangement.spacedBy(Space.XS)) {
                                d.rules.groups.forEach { g -> Chip(g.name, g.id in groupIds) { groupIds = if (g.id in groupIds) groupIds - g.id else groupIds + g.id } }
                            }
                        }
                    }
                    ScheduleStep.Mode -> {
                        Title(stringResource(R.string.schedule_step_mode), stringResource(R.string.schedule_step_mode_body))
                        Mode.entries.forEach { m -> ChoiceCard(modeName(m), scheduleModeNote(m, d.plan), mode == m, { mode = m }, swatch = modeColor(m)) }
                    }
                    ScheduleStep.Settings -> if (mode == Mode.Friction) {
                        Title(stringResource(R.string.start_friction_title), stringResource(R.string.start_friction_body))
                        FrictionTask.entries.forEach { t -> ChoiceCard(taskName(t), taskDescription(t), task == t, { task = t }) }
                        if (task == FrictionTask.Math) FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.XS)) {
                            Difficulty.entries.forEach { dif -> Chip(difficultyName(dif), difficulty == dif) { difficulty = dif } }
                        }
                        if (task == FrictionTask.Reflection) FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.XS)) {
                            ReflectionPauses.forEach { p -> Chip(secondsText(p), pause == p) { pause = p } }
                        }
                        Text(stringResource(R.string.schedule_friction_usage_note), color = colors.textTertiary, style = AuroraType.Caption)
                    } else {
                        Title(stringResource(R.string.duration_cooldown_prompt), stringResource(R.string.duration_cooldown_hint))
                        DurationPicker(wait, { wait = it }, CooldownWaitPresets, max = 12 * 60)
                    }
                    ScheduleStep.Summary -> {
                        Title(stringResource(R.string.schedule_step_summary), null)
                        Column(
                            Modifier.fillMaxWidth().clip(Radius.L).background(colors.surface).border(1.dp, modeColor(mode).copy(alpha = 0.5f), Radius.L).padding(Space.L),
                            verticalArrangement = Arrangement.spacedBy(Space.XS),
                        ) {
                            Text(draft.name, color = modeColor(mode), style = AuroraType.Eyebrow)
                            Text(scheduleSentence(draft, d.rules, d.activeKeys, d::label), color = colors.textPrimary, style = AuroraType.Title)
                            Text(scheduleModeNote(mode, d.plan), color = colors.textSecondary, style = AuroraType.Secondary)
                        }
                        if (runningCommitted) Text(stringResource(R.string.schedule_edit_committed), color = colors.dawnText, style = AuroraType.Secondary)
                        if (original != null) AuroraButton(stringResource(R.string.schedule_delete), { deleting = true }, Modifier.fillMaxWidth(), kind = ButtonKind.Quiet, icon = AuroraIcons.Trash)
                    }
                }
            }
        }
        val canNext = when (step) {
            ScheduleStep.Name -> name.isNotBlank()
            ScheduleStep.Days -> days.isNotEmpty()
            ScheduleStep.Apps -> keys.isNotEmpty() || groupIds.isNotEmpty()
            ScheduleStep.Settings -> mode != Mode.Cooldown || wait != null
            else -> true
        }
        Column(Modifier.padding(Space.M)) {
            if (step == ScheduleStep.Summary) {
                AuroraButton(stringResource(R.string.schedule_save), {
                    vm.edit { r -> r.copy(schedules = if (original == null) r.schedules + draft else r.schedules.map { if (it.id == draft.id) draft else it }) }
                    onBack()
                }, Modifier.fillMaxWidth(), enabled = canNext)
            } else {
                AuroraButton(stringResource(R.string.action_next), { step = steps[index + 1] }, Modifier.fillMaxWidth(), enabled = canNext)
            }
        }
    }
    if (deleting) {
        AuroraDialog(
            title = stringResource(R.string.schedule_delete_title),
            body = stringResource(if (runningCommitted) R.string.schedule_delete_body_committed else R.string.schedule_delete_body),
            confirmLabel = stringResource(R.string.schedule_delete),
            onConfirm = { deleting = false; vm.edit { r -> r.copy(schedules = r.schedules.filterNot { it.id == base.id }) }; onBack() },
            dismissLabel = stringResource(R.string.action_cancel),
            onDismiss = { deleting = false },
        )
    }
}

/** What a mode means for a schedule window. */
@Composable
private fun scheduleModeNote(mode: Mode, plan: Plan): String = when (mode) {
    Mode.Flexible -> stringResource(R.string.schedule_mode_flexible)
    Mode.Friction -> stringResource(R.string.schedule_mode_friction)
    Mode.Cooldown -> stringResource(R.string.schedule_mode_cooldown)
    Mode.AlwaysBlock -> stringResource(if (plan == Plan.Plus) R.string.schedule_mode_always_block else R.string.schedule_mode_always_block_free)
}

@Composable
private fun Title(title: String, body: String?) {
    Text(title, Modifier.semantics { heading() }, color = Aurora.colors.textPrimary, style = AuroraType.Headline)
    if (body != null) Text(body, Modifier.padding(bottom = Space.S), color = Aurora.colors.textSecondary, style = AuroraType.Body)
}

/** A time of day typed as hour and minute, with an AM/PM switch in 12-hour locales. */
@Composable
private fun TimeEntry(label: String, minute: Int, onChange: (Int) -> Unit) {
    val colors = Aurora.colors
    val context = androidx.compose.ui.platform.LocalContext.current
    val h24 = android.text.format.DateFormat.is24HourFormat(context)
    var hourText by remember(minute, h24) { mutableStateOf((if (h24) minute / 60 else (minute / 60 + 11) % 12 + 1).toString()) }
    var minText by remember(minute) { mutableStateOf("%02d".format(minute % 60)) }
    val pm = minute / 60 >= 12
    fun commit(h: String, m: String, isPm: Boolean = pm) {
        val hour = h.toIntOrNull() ?: return
        val min = m.toIntOrNull() ?: return
        if (min !in 0..59) return
        val h24value = if (h24) hour.takeIf { it in 0..23 } else hour.takeIf { it in 1..12 }?.let { it % 12 + if (isPm) 12 else 0 }
        h24value?.let { onChange(it * 60 + min) }
    }
    Column(verticalArrangement = Arrangement.spacedBy(Space.XS)) {
        Text(label, color = colors.textPrimary, style = AuroraType.Title)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.S)) {
            AuroraTextField(hourText, { hourText = it.filter(Char::isDigit).take(2); commit(hourText, minText) }, "9", Modifier.weight(1f),
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number))
            Text(":", color = colors.textPrimary, style = AuroraType.Headline)
            AuroraTextField(minText, { minText = it.filter(Char::isDigit).take(2); commit(hourText, minText) }, "00", Modifier.weight(1f),
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number))
            if (!h24) {
                Chip("AM", !pm) { commit(hourText, minText, isPm = false) }
                Chip("PM", pm) { commit(hourText, minText, isPm = true) }
            }
        }
        Text(minuteOfDay(minute), color = colors.textSecondary, style = AuroraType.Caption)
    }
}

/** Every daily budget in one place. Limits are measured from real app use, not from protection time. */
@Composable
fun LimitsScreen(onBack: () -> Unit, onOpenItem: (String) -> Unit, onOpenApps: () -> Unit) {
    val vm = auroraViewModel { ProtectModel(it) }
    val data by vm.data.collectAsStateWithLifecycle()
    val d = data
    AuroraScreen(title = stringResource(R.string.limits_name), subtitle = stringResource(R.string.limits_subtitle), onBack = onBack) {
        if (d == null) {
            item { SkeletonRows(3) }
            return@AuroraScreen
        }
        val active = d.rules.activeLimits(d.plan)
        val apps = d.list.apps.filter { it in d.activeKeys }
        if (apps.isEmpty()) {
            item(key = "none") {
                EmptyState(
                    icon = AuroraIcons.Hourglass,
                    title = stringResource(R.string.limits_empty_title),
                    body = stringResource(R.string.limits_empty_body),
                    action = { AuroraButton(stringResource(R.string.protect_add_apps), onOpenApps) },
                )
            }
            return@AuroraScreen
        }
        item(key = "list") {
            SectionLabel(stringResource(R.string.limits_count, allowanceText(active.size, d.max(Allowance.DailyLimitRules))))
            RowGroup {
                apps.forEachIndexed { i, pkg ->
                    if (i > 0) RowDivider(72.dp)
                    val limit = d.rules.limits.find { it.key == pkg && it.enabled }
                    AuroraRow(
                        title = d.label(pkg),
                        subtitle = when {
                            limit == null -> stringResource(R.string.limits_none)
                            limit !in active -> stringResource(R.string.limits_paused, minutesText(limit.minutes))
                            else -> stringResource(R.string.limits_per_day, minutesText(limit.minutes))
                        },
                        leading = { ItemIcon(pkg, d) },
                        onClick = { onOpenItem(pkg) },
                        trailing = { Chevron() },
                    )
                }
            }
            Text(stringResource(R.string.limits_note), Modifier.padding(Space.Gutter), color = Aurora.colors.textTertiary, style = AuroraType.Caption)
        }
    }
}

/** Named sets of protected items. Schedules and Focus can cover groups. */
@Composable
fun GroupsScreen(onBack: () -> Unit) {
    val vm = auroraViewModel { ProtectModel(it) }
    val data by vm.data.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<Group?>(null) }
    val d = data
    AuroraScreen(title = stringResource(R.string.groups_name), subtitle = stringResource(R.string.groups_subtitle), onBack = onBack) {
        if (d == null) {
            item { SkeletonRows(3) }
            return@AuroraScreen
        }
        if (d.rules.groups.isEmpty()) item(key = "empty") {
            EmptyState(icon = AuroraIcons.Layers, title = stringResource(R.string.groups_empty_title), body = stringResource(R.string.groups_empty_body))
            Row(Modifier.fillMaxWidth().padding(horizontal = Space.M), horizontalArrangement = Arrangement.spacedBy(Space.XS, Alignment.CenterHorizontally)) {
                for (res in listOf(R.string.group_social, R.string.group_entertainment, R.string.group_games)) {
                    val name = stringResource(res)
                    AuroraButton(name, { editing = Group(UUID.randomUUID().toString(), name) }, kind = ButtonKind.Secondary)
                }
            }
        } else item(key = "list") {
            SectionLabel(stringResource(R.string.groups_yours))
            RowGroup {
                d.rules.groups.forEachIndexed { i, g ->
                    if (i > 0) RowDivider()
                    val covered = d.rules.cover(g.id, d.activeKeys)
                    AuroraRow(
                        title = g.name,
                        subtitle = if (covered.isEmpty()) stringResource(R.string.groups_no_items) else covered.joinToString(", ") { d.label(it) },
                        icon = AuroraIcons.Layers,
                        onClick = { editing = g },
                        trailing = { Chevron() },
                    )
                }
            }
        }
        item(key = "add") {
            Row(Modifier.fillMaxWidth().padding(top = Space.L), horizontalArrangement = Arrangement.Center) {
                AuroraButton(stringResource(R.string.groups_new), { editing = Group(UUID.randomUUID().toString(), "") }, kind = ButtonKind.Secondary, icon = AuroraIcons.Plus)
            }
        }
    }
    val dd = d ?: return
    editing?.let { g ->
        var name by rememberSaveable(g.id) { mutableStateOf(g.name) }
        var keys by remember(g.id) { mutableStateOf(g.keys.toSet()) }
        val isNew = dd.rules.groups.none { it.id == g.id }
        val fallbackName = stringResource(R.string.groups_default_name)
        AuroraDialog(
            title = stringResource(if (isNew) R.string.groups_new else R.string.groups_edit),
            confirmLabel = stringResource(R.string.action_save),
            onConfirm = {
                editing = null
                val saved = g.copy(name = name.trim().ifBlank { fallbackName }, keys = dd.list.items.map { it.key }.filter { it in keys })
                vm.edit { r -> r.copy(groups = if (isNew) r.groups + saved else r.groups.map { if (it.id == saved.id) saved else it }) }
            },
            dismissLabel = stringResource(R.string.action_cancel),
            onDismiss = { editing = null },
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(Space.S)) {
                AuroraTextField(name, { if (it.length <= 24) name = it }, stringResource(R.string.groups_name_placeholder), capitalization = KeyboardCapitalization.Words)
                val all = dd.list.items.map { it.key }
                if (all.isEmpty()) Text(stringResource(R.string.groups_protect_first), color = Aurora.colors.textSecondary, style = AuroraType.Secondary)
                ChipFlow(all, keys, { k -> keys = if (k in keys) keys - k else keys + k }, label = { dd.label(it) })
                if (!isNew) AuroraButton(stringResource(R.string.groups_delete), {
                    editing = null
                    vm.edit { r ->
                        val gone = r.groups.find { it.id == g.id }?.keys.orEmpty()
                        r.copy(
                            groups = r.groups.filterNot { it.id == g.id },
                            // A schedule that covered this group keeps covering its apps: deleting a group never
                            // widens or silently empties a schedule.
                            schedules = r.schedules.map {
                                when {
                                    it.groupId == g.id -> it.copy(groupId = null, keys = gone)
                                    g.id in it.groupIds -> it.copy(groupIds = it.groupIds - g.id, keys = (it.keys + gone).distinct())
                                    else -> it
                                }
                            },
                            profiles = r.profiles.map { if (it.groupId == g.id) it.copy(groupId = null) else it },
                        )
                    }
                }, kind = ButtonKind.Quiet)
            }
        }
    }
}
