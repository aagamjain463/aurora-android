package app.aurora.ui.protect

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.aurora.LockdownResult
import app.aurora.auroraViewModel
import app.aurora.data.formatClock
import app.aurora.data.spokenDuration
import app.aurora.lockdownLeft
import app.aurora.plan.Allowance
import app.aurora.protect.DailyLimit
import app.aurora.protect.Group
import app.aurora.protect.Schedule
import app.aurora.protect.cover
import app.aurora.protect.key
import app.aurora.startLockdown
import app.aurora.ui.components.AuroraButton
import app.aurora.ui.components.AuroraDialog
import app.aurora.ui.components.AuroraIcons
import app.aurora.ui.components.AuroraRow
import app.aurora.ui.components.AuroraScreen
import app.aurora.ui.components.AuroraSwitch
import app.aurora.ui.components.AuroraTextField
import app.aurora.ui.components.ButtonKind
import app.aurora.ui.components.Chevron
import app.aurora.ui.components.ChipFlow
import app.aurora.ui.components.ChoicePills
import app.aurora.ui.components.EmptyState
import app.aurora.ui.components.HorizonArc
import app.aurora.ui.components.HorizonGlow
import app.aurora.ui.components.Notice
import app.aurora.ui.components.RowDivider
import app.aurora.ui.components.RowGroup
import app.aurora.ui.components.SectionLabel
import app.aurora.ui.components.SkeletonRows
import app.aurora.ui.components.allowanceText
import app.aurora.ui.home.clockTime
import app.aurora.ui.theme.Aurora
import app.aurora.ui.theme.AuroraType
import app.aurora.ui.theme.Space
import kotlinx.coroutines.launch
import java.io.IOException
import java.time.DayOfWeek
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle
import java.util.Locale
import java.util.UUID

private val Week = (1..7).toList()
private fun dayShort(d: Int) = DayOfWeek.of(d).getDisplayName(TextStyle.SHORT, Locale.getDefault())
private fun minuteText(m: Int) = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).format(LocalTime.of(m / 60 % 24, m % 60))

fun Schedule.daysText(): String = when (days) {
    setOf(1, 2, 3, 4, 5) -> "Weekdays"
    setOf(6, 7) -> "Weekends"
    Week.toSet() -> "Every day"
    else -> days.sorted().joinToString(" ") { dayShort(it) }
}

fun Schedule.timeText() = "${minuteText(startMinute)}–${minuteText(endMinute)}"

private fun groupName(groups: List<Group>, id: String?) = id?.let { g -> groups.find { it.id == g }?.name } ?: "Everything protected"

/** Recurring protection: study hours, nights, mornings. It starts and ends on its own. */
@Composable
fun SchedulesScreen(onBack: () -> Unit, onPlus: () -> Unit) {
    val vm = auroraViewModel { ProtectModel(it) }
    val data by vm.data.collectAsStateWithLifecycle()
    val notice by vm.notice.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<Schedule?>(null) }
    val d = data
    AuroraScreen(
        title = "Schedules",
        subtitle = "Protection that starts on its own. Times follow your phone's clock and time zone.",
        onBack = onBack,
    ) {
        if (d == null) {
            item { SkeletonRows(3) }
            return@AuroraScreen
        }
        val active = d.rules.activeSchedules(d.plan)
        if (d.rules.schedules.isEmpty()) item(key = "empty") {
            EmptyState(
                icon = AuroraIcons.Calendar,
                title = "No schedules yet",
                body = "For example: weekdays 9 to 5 for work, or every night from 22:00 so mornings start calmly.",
            )
        } else item(key = "list") {
            SectionLabel("Your schedules · ${allowanceText(active.size, d.max(Allowance.Schedules))} on")
            RowGroup {
                d.rules.schedules.forEachIndexed { i, s ->
                    if (i > 0) RowDivider()
                    val paused = s.enabled && s !in active
                    AuroraRow(
                        title = s.name,
                        subtitle = "${s.daysText()} · ${s.timeText()} · ${groupName(d.rules.groups, s.groupId)}" + if (paused) " · paused on Free" else "",
                        onClick = { editing = s },
                        trailing = {
                            Box(
                                Modifier.semantics { contentDescription = if (s.enabled) "On" else "Off" },
                            ) { AuroraSwitch(s.enabled) }
                        },
                    )
                }
            }
            Hint("Tap a schedule to change it. While one is running, its apps can't be opened, even with Intent.")
        }
        item(key = "add") {
            Row(Modifier.fillMaxWidth().padding(top = Space.L), horizontalArrangement = Arrangement.Center) {
                AuroraButton("New schedule", {
                    editing = Schedule(UUID.randomUUID().toString(), "Study hours", setOf(1, 2, 3, 4, 5), 9 * 60, 17 * 60, enabled = false)
                }, kind = ButtonKind.Secondary, icon = AuroraIcons.Plus)
            }
        }
    }
    editing?.let { s ->
        ScheduleEditor(
            schedule = s,
            groups = d?.rules?.groups.orEmpty(),
            isNew = d?.rules?.schedules?.none { it.id == s.id } ?: true,
            onSave = { saved ->
                editing = null
                val r = d?.rules ?: return@ScheduleEditor
                val others = r.schedules.filterNot { it.id == saved.id }
                val wanted = saved.copy(enabled = true)
                vm.enableWithin(Allowance.Schedules, r.activeSchedules(d.plan).count { it.id != saved.id }) { rules ->
                    rules.copy(schedules = if (r.schedules.any { it.id == saved.id }) rules.schedules.map { if (it.id == saved.id) wanted else it } else others + wanted)
                }
            },
            onTurnOff = { off ->
                editing = null
                vm.edit { r -> r.copy(schedules = r.schedules.map { if (it.id == off.id) off.copy(enabled = false) else it }) }
            },
            onDelete = { gone ->
                editing = null
                vm.edit { r -> r.copy(schedules = r.schedules.filterNot { it.id == gone.id }) }
            },
            onDismiss = { editing = null },
        )
    }
    LimitDialog(notice, vm::dismissNotice, onPlus)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ScheduleEditor(
    schedule: Schedule,
    groups: List<Group>,
    isNew: Boolean,
    onSave: (Schedule) -> Unit,
    onTurnOff: (Schedule) -> Unit,
    onDelete: (Schedule) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by rememberSaveable { mutableStateOf(schedule.name) }
    var days by remember { mutableStateOf(schedule.days) }
    var start by rememberSaveable { mutableIntStateOf(schedule.startMinute) }
    var end by rememberSaveable { mutableIntStateOf(schedule.endMinute) }
    var group by remember { mutableStateOf(schedule.groupId) }
    var picking by remember { mutableStateOf<Boolean?>(null) } // true: start, false: end
    val colors = Aurora.colors
    AuroraDialog(
        title = if (isNew) "New schedule" else "Edit schedule",
        confirmLabel = if (schedule.enabled || isNew) "Save" else "Save and turn on",
        onConfirm = { if (days.isNotEmpty()) onSave(schedule.copy(name = name.trim().ifBlank { "Schedule" }, days = days, startMinute = start, endMinute = end, groupId = group)) },
        dismissLabel = "Cancel",
        onDismiss = onDismiss,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(Space.S)) {
            AuroraTextField(name, { if (it.length <= 30) name = it }, "Name", capitalization = KeyboardCapitalization.Sentences)
            ChipFlow(Week, days, { d -> days = if (d in days) days - d else days + d }, label = { dayShort(it) }, spokenLabel = { DayOfWeek.of(it).getDisplayName(TextStyle.FULL, Locale.getDefault()) })
            Row(horizontalArrangement = Arrangement.spacedBy(Space.XS)) {
                AuroraButton("From ${minuteText(start)}", { picking = true }, Modifier.weight(1f), kind = ButtonKind.Secondary)
                AuroraButton("To ${minuteText(end)}", { picking = false }, Modifier.weight(1f), kind = ButtonKind.Secondary)
            }
            if (end <= start) Text("Runs past midnight, until ${minuteText(end)} the next day.", color = colors.textTertiary, style = AuroraType.Caption)
            ChoicePills(listOf<String?>(null) + groups.map { it.id }, group, { group = it }, label = { groupName(groups, it) })
            if (!isNew) Row {
                if (schedule.enabled) AuroraButton("Turn off", { onTurnOff(schedule) }, kind = ButtonKind.Quiet)
                AuroraButton("Delete", { onDelete(schedule) }, kind = ButtonKind.Quiet)
            }
        }
    }
    picking?.let { isStart ->
        val initial = if (isStart) start else end
        val state = rememberTimePickerState(initial / 60, initial % 60)
        AuroraDialog(
            title = if (isStart) "Starts at" else "Ends at",
            confirmLabel = "Done",
            onConfirm = {
                val m = state.hour * 60 + state.minute
                if (isStart) start = m else end = m
                picking = null
            },
            dismissLabel = "Cancel",
            onDismiss = { picking = null },
        ) { TimePicker(state) }
    }
}

/** Every daily budget in one place. Limits are set per app, on the app's own page. */
@Composable
fun LimitsScreen(onBack: () -> Unit, onOpenItem: (String) -> Unit, onOpenApps: () -> Unit) {
    val vm = auroraViewModel { ProtectModel(it) }
    val data by vm.data.collectAsStateWithLifecycle()
    val d = data
    AuroraScreen(title = "Daily limits", subtitle = "How long an app deserves each day. Budgets reset at midnight.", onBack = onBack) {
        if (d == null) {
            item { SkeletonRows(3) }
            return@AuroraScreen
        }
        val active = d.rules.activeLimits(d.plan)
        val apps = d.list.apps.filter { it in d.active.map { a -> a.key } }
        if (apps.isEmpty()) {
            item(key = "none") {
                EmptyState(
                    icon = AuroraIcons.Hourglass,
                    title = "Protect an app first",
                    body = "Limits apply to apps you protect. Choose one, then give it a daily budget.",
                    action = { AuroraButton("Choose apps", onOpenApps) },
                )
            }
            return@AuroraScreen
        }
        item(key = "list") {
            SectionLabel("Your apps · ${allowanceText(active.size, d.max(Allowance.DailyLimitRules))} limits on")
            RowGroup {
                apps.forEachIndexed { i, pkg ->
                    if (i > 0) RowDivider(72.dp)
                    val limit: DailyLimit? = d.rules.limits.find { it.key == pkg && it.enabled }
                    AuroraRow(
                        title = d.label(pkg),
                        subtitle = when {
                            limit == null -> "No limit"
                            limit !in active -> "${limit.minutes} min a day · paused on Free"
                            else -> "${limit.minutes} min a day"
                        },
                        leading = { KeyIcon(d, pkg) },
                        onClick = { onOpenItem(pkg) },
                        trailing = { Chevron() },
                    )
                }
            }
            Hint("Time is measured from Android's usage records, so it needs usage access. aurora checks again at least once a minute while a limited app is open.")
        }
    }
}

/** Named sets of protected items. Schedules, Focus and Lockdown can cover a group instead of everything. */
@Composable
fun GroupsScreen(onBack: () -> Unit) {
    val vm = auroraViewModel { ProtectModel(it) }
    val data by vm.data.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<Group?>(null) }
    val d = data
    AuroraScreen(title = "Groups", subtitle = "Protect related apps together.", onBack = onBack) {
        if (d == null) {
            item { SkeletonRows(3) }
            return@AuroraScreen
        }
        if (d.rules.groups.isEmpty()) item(key = "empty") {
            EmptyState(icon = AuroraIcons.Layers, title = "No groups yet", body = "Start with one of these, or make your own.")
            Row(Modifier.fillMaxWidth().padding(horizontal = Space.M), horizontalArrangement = Arrangement.spacedBy(Space.XS, Alignment.CenterHorizontally)) {
                for (name in listOf("Social", "Entertainment", "Games")) {
                    AuroraButton(name, { editing = Group(UUID.randomUUID().toString(), name) }, kind = ButtonKind.Secondary)
                }
            }
        } else item(key = "list") {
            SectionLabel("Your groups")
            RowGroup {
                d.rules.groups.forEachIndexed { i, g ->
                    if (i > 0) RowDivider()
                    val covered = d.rules.cover(g.id, d.active.map { it.key }.toSet())
                    AuroraRow(
                        title = g.name,
                        subtitle = if (covered.isEmpty()) "No protected items yet" else covered.joinToString(", ") { d.label(it) },
                        icon = AuroraIcons.Layers,
                        onClick = { editing = g },
                        trailing = { Chevron() },
                    )
                }
            }
        }
        item(key = "add") {
            Row(Modifier.fillMaxWidth().padding(top = Space.L), horizontalArrangement = Arrangement.Center) {
                AuroraButton("New group", { editing = Group(UUID.randomUUID().toString(), "") }, kind = ButtonKind.Secondary, icon = AuroraIcons.Plus)
            }
        }
    }
    val dd = d ?: return
    editing?.let { g ->
        var name by rememberSaveable(g.id) { mutableStateOf(g.name) }
        var keys by remember(g.id) { mutableStateOf(g.keys.toSet()) }
        val isNew = dd.rules.groups.none { it.id == g.id }
        AuroraDialog(
            title = if (isNew) "New group" else "Edit group",
            confirmLabel = "Save",
            onConfirm = {
                editing = null
                val saved = g.copy(name = name.trim().ifBlank { "Group" }, keys = dd.list.items.map { it.key }.filter { it in keys })
                vm.edit { r -> r.copy(groups = if (isNew) r.groups + saved else r.groups.map { if (it.id == saved.id) saved else it }) }
            },
            dismissLabel = "Cancel",
            onDismiss = { editing = null },
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(Space.S)) {
                AuroraTextField(name, { if (it.length <= 24) name = it }, "Name", capitalization = KeyboardCapitalization.Words)
                val all = dd.list.items.map { it.key }
                if (all.isEmpty()) Text("Protect some apps first, then add them here.", color = Aurora.colors.textSecondary, style = AuroraType.Secondary)
                ChipFlow(all, keys, { k -> keys = if (k in keys) keys - k else keys + k }, label = { dd.label(it) })
                if (!isNew) AuroraButton("Delete group", {
                    editing = null
                    vm.edit { r ->
                        r.copy(
                            groups = r.groups.filterNot { it.id == g.id },
                            // Rules that covered this group fall back to everything protected rather than nothing.
                            schedules = r.schedules.map { if (it.groupId == g.id) it.copy(groupId = null) else it },
                            profiles = r.profiles.map { if (it.groupId == g.id) it.copy(groupId = null) else it },
                        )
                    }
                }, kind = ButtonKind.Quiet)
            }
        }
    }
}

private val LockdownLengths = listOf(15, 30, 60, 90, 120, 180)

/**
 * Lockdown: a firm commitment for a set time. Nothing in aurora ends it early. It isn't absolute (Android
 * can always turn aurora's Shield off, and aurora never hides that), and the screen says so before it starts.
 */
@Composable
fun LockdownScreen(onBack: () -> Unit, onPlus: () -> Unit) {
    val vm = auroraViewModel { ProtectModel(it) }
    val data by vm.data.collectAsStateWithLifecycle()
    val notice by vm.notice.collectAsStateWithLifecycle()
    var minutes by rememberSaveable { mutableIntStateOf(60) }
    var group by rememberSaveable { mutableStateOf<String?>(null) }
    var confirm by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val colors = Aurora.colors
    val d = data
    val session = d?.runtime?.lockdown?.takeUnless { it.isOver(d.time) }

    AuroraScreen(title = "Lockdown", subtitle = if (session == null) "A firm commitment for a set time." else null, onBack = onBack) {
        if (d == null) {
            item { SkeletonRows(3) }
            return@AuroraScreen
        }
        val activeKeys = d.active.map { it.key }.toSet()
        if (session != null) {
            item(key = "live") {
                val remaining = session.remaining(d.time)
                Box(Modifier.fillMaxWidth().padding(horizontal = Space.Gutter, vertical = Space.L), contentAlignment = Alignment.BottomCenter) {
                    HorizonGlow(0.9f, Modifier.matchParentSize(), warmth = 0.1f, horizon = true)
                    HorizonArc(session.progress(d.time), Modifier.fillMaxWidth().padding(top = Space.XL))
                    Column(Modifier.padding(bottom = Space.L), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(formatClock(remaining), Modifier.clearAndSetSemantics { contentDescription = "${spokenDuration(remaining)} of Lockdown left" }, color = colors.textPrimary, style = AuroraType.Hero)
                        Text("until ${clockTime(session.endWall(d.time))}", color = colors.textSecondary, style = AuroraType.Caption)
                    }
                }
                val covered = d.rules.cover(session.groupId, activeKeys)
                Text(
                    "${covered.size} ${if (covered.size == 1) "item is" else "items are"} locked: ${covered.joinToString(", ") { d.label(it) }}.",
                    Modifier.padding(horizontal = Space.Gutter),
                    color = colors.textSecondary,
                    style = AuroraType.Body,
                    textAlign = TextAlign.Center,
                )
                Hint("You chose this, and it ends on its own. Calls, emergency services and Android's settings are never locked.")
            }
            return@AuroraScreen
        }
        val left = vm.app.lockdownLeft()
        item(key = "length") {
            SectionLabel("How long")
            ChoicePills(LockdownLengths, minutes, { minutes = it }, label = { if (it < 60) "$it min" else if (it % 60 == 0) "${it / 60} h" else "${it / 60}h ${it % 60}" }, spokenLabel = { "$it minutes" }, modifier = Modifier.padding(horizontal = Space.M))
            if (left != null) Hint("aurora Free includes 120 Lockdown minutes a day. $left left today.")
        }
        item(key = "what") {
            SectionLabel("What to lock")
            ChoicePills(listOf<String?>(null) + d.rules.groups.map { it.id }, group, { group = it }, label = { groupName(d.rules.groups, it) }, modifier = Modifier.padding(horizontal = Space.M))
            val covered = d.rules.cover(group, activeKeys)
            Hint(if (covered.isEmpty()) "Nothing to lock yet: protect some apps first." else covered.joinToString(", ") { d.label(it) })
        }
        item(key = "commit") {
            Notice(
                "Before you start",
                "Once it starts, a Lockdown can't be ended early in aurora, and grants or Intent can't open these apps. " +
                    "It isn't a trap: Android lets you turn Shield off in its settings at any time, and calls and emergencies always work.",
                Modifier.padding(top = Space.L),
            )
            Spacer(Modifier.height(Space.L))
            Box(Modifier.fillMaxWidth().padding(horizontal = Space.Gutter), contentAlignment = Alignment.Center) {
                AuroraButton(
                    "Start Lockdown",
                    { confirm = true },
                    Modifier.widthIn(max = 480.dp).fillMaxWidth(),
                    icon = AuroraIcons.Lock,
                    enabled = d.rules.cover(group, activeKeys).isNotEmpty(),
                )
            }
        }
    }
    if (confirm) AuroraDialog(
        title = "Lock for $minutes minutes?",
        body = "Until ${clockTime(System.currentTimeMillis() + minutes * 60_000L)}. You won't be able to end it early in aurora.",
        confirmLabel = "Start",
        onConfirm = {
            confirm = false
            scope.launch {
                val result = try { vm.app.startLockdown(minutes, group) } catch (_: IOException) { null }
                if (result == LockdownResult.Started) haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                if (result == LockdownResult.OverBudget) vm.atLimit(Allowance.LockdownMinutesPerDay)
            }
        },
        dismissLabel = "Not now",
        onDismiss = { confirm = false },
    )
    LimitDialog(notice, vm::dismissNotice, onPlus)
}
