package app.aurora.ui.configure

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.aurora.R
import app.aurora.activate
import app.aurora.account.serverAdjustedRemainingMs
import app.aurora.committedMinutesLeft
import app.aurora.protect.strict
import app.aurora.ui.strict.UninstallProtection
import app.aurora.auroraViewModel
import app.aurora.newId
import app.aurora.protect.ActivationProblem
import app.aurora.protect.quotaToday
import app.aurora.protect.usesQuota
import app.aurora.protect.FocusPlan
import app.aurora.protect.IntentPreset
import app.aurora.protect.Mode
import app.aurora.protect.ShieldPreset
import app.aurora.protect.Timing
import app.aurora.protect.UsageConfig
import app.aurora.protect.intentOf
import app.aurora.protect.overlaps
import app.aurora.protect.shieldOf
import app.aurora.runsNow
import app.aurora.saveConfig
import app.aurora.ui.components.AuroraButton
import app.aurora.ui.components.AuroraDialog
import app.aurora.ui.components.AuroraIconButton
import app.aurora.ui.components.AuroraIcons
import app.aurora.deleteConfig
import androidx.compose.runtime.remember
import app.aurora.ui.components.AuroraSwitch
import app.aurora.ui.components.AuroraTextField
import app.aurora.ui.components.ButtonKind
import app.aurora.ui.components.Chip
import app.aurora.ui.components.ChipFlow
import app.aurora.ui.components.ChoiceCard
import app.aurora.ui.components.DurationPicker
import app.aurora.ui.components.SelectionMark
import app.aurora.ui.components.auroraSurface
import app.aurora.ui.components.clockTime
import app.aurora.ui.components.minutesText
import app.aurora.ui.library.PickRow
import app.aurora.ui.library.StepFlow
import app.aurora.ui.library.shieldDetail
import app.aurora.ui.library.stepTitle
import app.aurora.ui.protect.IconStack
import app.aurora.ui.protect.ModeChip
import app.aurora.ui.protect.ProtectData
import app.aurora.ui.protect.ProtectModel
import app.aurora.ui.protect.ProtectionPresets
import app.aurora.ui.protect.TimeEntry
import app.aurora.ui.protect.dayShort
import app.aurora.ui.protect.daysText
import app.aurora.ui.protect.intentSummary
import app.aurora.ui.protect.minuteOfDay
import app.aurora.ui.protect.modeColor
import app.aurora.ui.theme.Aurora
import app.aurora.ui.theme.AuroraType
import app.aurora.ui.theme.Space
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

enum class When { Now, Later, Recurring, Always }

/**
 * What (contents, and a name for a custom configuration), When (the timing and its settings on one
 * screen), then Review (where Focus is an optional addition). A configuration made for a Goal is saved
 * after What and Focus.
 */
private enum class Step { Saved, Contents, When, Focus, Review }

/**
 * Home's one activation flow. Normal Session ([custom] false) and Custom Session build from the same saved
 * presets in the same steps; only a custom one is named and saved, to start again and link to a Goal.
 * Contents, when, how long, optional Focus, review. [createdShield]/[createdIntent] are presets the person
 * just made from here, selected on return. [forGoal]: save without starting is the main action.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ConfigureScreen(
    custom: Boolean,
    configId: String?,
    forGoal: Boolean,
    createdShield: String?,
    createdIntent: String?,
    onBack: () -> Unit,
    onDone: (configId: String) -> Unit,
    onCreateShield: () -> Unit,
    onCreateIntent: () -> Unit,
    onStrict: () -> Unit,
    onPlus: () -> Unit,
    onOpenWeb: () -> Unit = {},
    /** Screenshots only: open on a later step, with a timing chosen. */
    startStep: String? = null,
    startWhen: When? = null,
) {
    val vm = auroraViewModel { ProtectModel(it) }
    val data by vm.data.collectAsStateWithLifecycle()
    val d = data ?: return
    val colors = Aurora.colors
    val zone = ZoneId.systemDefault()
    val saved = d.rules.configs.filter { it.custom }.sortedByDescending { it.createdAt }
    val start = d.rules.configs.find { it.id == configId }

    var id by rememberSaveable { mutableStateOf(start?.id ?: newId()) }
    var name by rememberSaveable { mutableStateOf(start?.name.orEmpty()) }
    var shieldId by rememberSaveable { mutableStateOf(start?.shieldId) }
    var intentId by rememberSaveable { mutableStateOf(start?.intentId) }

    var whenKind by rememberSaveable { mutableStateOf(startWhen) }
    var minutes by rememberSaveable { mutableStateOf<Int?>(60) }
    var laterDay by rememberSaveable { mutableStateOf(LocalDate.now().plusDays(1).toString()) }
    var laterMinute by rememberSaveable { mutableIntStateOf(7 * 60) }
    var days by rememberSaveable { mutableStateOf(setOf(1, 2, 3, 4, 5)) }
    var recurMinute by rememberSaveable { mutableIntStateOf(9 * 60) }
    var until by rememberSaveable { mutableStateOf<String?>(null) }

    var focusOn by rememberSaveable { mutableStateOf(start?.focus != null) }
    var task by rememberSaveable { mutableStateOf(start?.focus?.task.orEmpty()) }
    var description by rememberSaveable { mutableStateOf(start?.focus?.description.orEmpty()) }
    var goal by rememberSaveable { mutableStateOf(start?.focus?.goal.orEmpty()) }
    var focusWindow by rememberSaveable { mutableStateOf(start?.focus?.minutes == null) }
    var focusMinutes by rememberSaveable { mutableStateOf(start?.focus?.minutes) }
    var committed by rememberSaveable { mutableStateOf(false) }
    var problem by rememberSaveable { mutableStateOf<ActivationProblem?>(null) }
    var step by rememberSaveable { mutableStateOf(startStep?.let { Step.valueOf(it) } ?: if (custom && start == null && saved.isNotEmpty() && !forGoal) Step.Saved else Step.Contents) }

    var deleting by remember { mutableStateOf<UsageConfig?>(null) }
    LaunchedEffect(createdShield) { if (createdShield != null) shieldId = createdShield }
    LaunchedEffect(createdIntent) { if (createdIntent != null) intentId = createdIntent }

    // The configuration as it stands. A Normal Session carries its own copies, so it leaves nothing saved behind.
    val shield: ShieldPreset? = shieldId?.let { s -> d.rules.shields.find { it.id == s } }
    val intent: IntentPreset? = intentId?.let { s -> d.rules.intentPresets.find { it.id == s } }
    val focus = if (focusOn && whenKind != When.Always && task.isNotBlank()) FocusPlan(task.trim(), description.trim(), goal.trim(), if (focusWindow) null else focusMinutes) else null
    val configName = if (custom) name.trim() else listOfNotNull(shield?.name, intent?.name).joinToString(" + ").ifEmpty { stringResource(R.string.home_normal_title) }
    val config = UsageConfig(
        id, configName, custom,
        shieldId = if (custom) shield?.id else null, intentId = if (custom) intent?.id else null,
        shield = if (custom) null else shield, intent = if (custom) null else intent,
        focus = focus, createdAt = start?.createdAt ?: System.currentTimeMillis(),
    )
    val alwaysBlock = shield?.mode == Mode.AlwaysBlock
    // Always Block, Cooldown and Friction: no early exit, no Always timing, and a commitment to confirm.
    val strict = shield?.mode?.strict == true
    val laterStart = LocalDateTime.of(LocalDate.parse(laterDay), java.time.LocalTime.of(laterMinute / 60, laterMinute % 60)).atZone(zone).toInstant().toEpochMilli()
    val timing: Timing? = when (whenKind) {
        When.Now -> minutes?.let { Timing.Now(it) }
        When.Later -> minutes?.let { Timing.Later(laterStart, it) }
        When.Recurring -> minutes?.let { Timing.Recurring(days, recurMinute, it, until) }
        When.Always -> Timing.Always
        null -> null
    }

    val shieldOn = app.aurora.rememberAccess(app.aurora.data.Access.Accessibility) == app.aurora.data.AccessState.Granted
    // Free's committed minutes left today: the pre-picked hour shrinks to fit.
    val blockLeft = if (shield?.mode?.usesQuota == true && whenKind == When.Now) vm.app.committedMinutesLeft() else null
    androidx.compose.runtime.LaunchedEffect(blockLeft) { if (blockLeft != null && (minutes ?: 0) > blockLeft) minutes = blockLeft.takeIf { it > 0 } }
    val hasSites = shield?.keys.orEmpty().any { it.startsWith(app.aurora.protect.WebPrefix) }
    val gate = app.aurora.ui.protect.shieldGateNeeded(hasSites)
    val steps = buildList {
        if (custom && saved.isNotEmpty() && !forGoal) add(Step.Saved)
        add(Step.Contents)
        if (!forGoal) { add(Step.When); add(Step.Review) } else add(Step.Focus)
    }
    val index = steps.indexOf(step).coerceAtLeast(0)
    val back = { problem = null; if (index == 0) onBack() else step = steps[index - 1] }
    val hasContent = (shield?.keys?.isNotEmpty() == true) || (intent?.keys?.isNotEmpty() == true)
    val focusValid = !focusOn || (task.isNotBlank() && (focusWindow || focusMinutes != null))
    val canNext = when (step) {
        Step.Saved -> true
        Step.Contents -> (shield != null || intent != null) && (!custom || name.isNotBlank())
        Step.When -> when (whenKind) {
            null -> false
            When.Always -> !strict
            else -> minutes != null && (whenKind != When.Later || laterStart > System.currentTimeMillis()) && (whenKind != When.Recurring || days.isNotEmpty())
        }
        Step.Focus -> focusValid
        // Without Shield this would only be a timer, so nothing starts until it's on.
        Step.Review -> hasContent && shieldOn && (!strict || committed) && (whenKind == When.Always || focusValid)
    }
    // Both run in the ViewModel's scope (the main thread), so navigating afterwards is always safe.
    val saveOnly = {
        vm.run {
            if (vm.app.saveConfig(config.copy(focus = if (focusOn && task.isNotBlank()) FocusPlan(task.trim(), description.trim(), goal.trim(), if (focusWindow) null else focusMinutes) else null)) == null) onDone(config.id)
        }
    }
    // The first time protection starts, ask to show it in the notification shade (Android 13+).
    val context = androidx.compose.ui.platform.LocalContext.current
    val notifications = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { }
    val confirm = {
        val t = timing
        if (t != null) vm.run {
            problem = vm.app.activate(config, t)?.problem
            if (problem == null) {
                if (android.os.Build.VERSION.SDK_INT >= 33 && !app.aurora.SessionNotification.allowed(context)) {
                    runCatching { notifications.launch(android.Manifest.permission.POST_NOTIFICATIONS) }
                }
                onDone(config.id)
            }
        }
    }

    // Focus: optional, offered at Review (or as its own step when saving for a Goal).
    val focusItems: androidx.compose.foundation.lazy.LazyListScope.() -> Unit = {
        item(key = "focus-on") {
            Row(
                Modifier.fillMaxWidth().auroraSurface(colors).toggleable(focusOn, role = Role.Switch) { focusOn = it }.padding(Space.M),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(R.string.cfg_focus_on), Modifier.weight(1f), color = colors.textPrimary, style = AuroraType.BodyStrong)
                AuroraSwitch(focusOn)
            }
        }
        if (focusOn) {
            item(key = "task") { AuroraTextField(task, { task = it.take(80) }, stringResource(R.string.cfg_focus_task), capitalization = KeyboardCapitalization.Sentences) }
            item(key = "desc") { AuroraTextField(description, { description = it.take(200) }, stringResource(R.string.cfg_focus_desc), capitalization = KeyboardCapitalization.Sentences, singleLine = false) }
            item(key = "goal") { AuroraTextField(goal, { goal = it.take(120) }, stringResource(R.string.cfg_focus_goal), capitalization = KeyboardCapitalization.Sentences) }
            item(key = "window") {
                Row(
                    Modifier.fillMaxWidth().auroraSurface(colors).toggleable(focusWindow, role = Role.Checkbox) { focusWindow = it }.padding(Space.M),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.M),
                ) {
                    SelectionMark(focusWindow)
                    Text(stringResource(R.string.cfg_focus_use_window), color = colors.textPrimary, style = AuroraType.BodyStrong)
                }
            }
            if (!focusWindow) item(key = "focus-target") {
                DurationPicker(focusMinutes, { focusMinutes = it }, listOf(25, 45, 60, 90), title = stringResource(R.string.cfg_focus_custom_target), max = minutes ?: (24 * 60))
            }
        }
    }

    StepFlow(
        steps.size, index, back,
        primary = when {
            step == Step.Focus && forGoal -> stringResource(R.string.action_save)
            step != Step.Review -> stringResource(R.string.action_next)
            whenKind == When.Now -> stringResource(R.string.cfg_start)
            whenKind == When.Always -> stringResource(R.string.cfg_turn_on)
            else -> stringResource(R.string.cfg_schedule)
        },
        primaryEnabled = canNext,
        whyDisabled = when (step) {
            Step.Saved -> null
            Step.Contents -> stringResource(if (shield == null && intent == null) R.string.cfg_problem_empty else R.string.need_name)
            Step.When -> when {
                whenKind == null -> stringResource(R.string.need_when)
                whenKind == When.Always -> stringResource(R.string.when_always_blocked)
                minutes == null -> stringResource(R.string.duration_problem_empty)
                whenKind == When.Recurring -> stringResource(R.string.cfg_problem_days)
                else -> null // A start in the past is already said beside the date.
            }
            Step.Focus -> stringResource(R.string.need_task)
            Step.Review -> stringResource(
                when {
                    !hasContent -> R.string.cfg_problem_empty
                    !shieldOn -> R.string.need_shield
                    strict && !committed -> R.string.need_commit
                    else -> R.string.need_task
                },
            )
        },
        onPrimary = {
            when {
                step == Step.Focus && forGoal -> saveOnly()
                step == Step.Review -> confirm()
                else -> step = steps[index + 1]
            }
        },
        footer = {
            // Plan limits are explained at the app's root.
            problem?.takeUnless { it.overLimit }?.let { p -> Text(problemText(p), color = colors.danger, style = AuroraType.Secondary) }
            if (custom && !forGoal && (step == Step.Contents || step == Step.Review) && name.isNotBlank() && (shield != null || intent != null)) {
                AuroraButton(stringResource(R.string.cfg_save_for_later), { saveOnly() }, Modifier.fillMaxWidth(), kind = ButtonKind.Quiet)
            }
        },
    ) {
        when (step) {
            Step.Saved -> {
                stepTitle(R.string.cfg_saved_title, R.string.cfg_saved_body)
                items(saved, key = { "cfg-" + it.id }) { c ->
                    val s = d.rules.shieldOf(c)
                    val i = d.rules.intentOf(c)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                    PickRow(c.name, listOfNotNull(s?.name, i?.name, c.focus?.task).joinToString(" · "), id == c.id, modifier = Modifier.weight(1f), onClick = {
                        id = c.id; name = c.name; shieldId = c.shieldId; intentId = c.intentId
                        focusOn = c.focus != null; task = c.focus?.task.orEmpty(); description = c.focus?.description.orEmpty()
                        goal = c.focus?.goal.orEmpty(); focusWindow = c.focus?.minutes == null; focusMinutes = c.focus?.minutes
                        step = Step.When
                    }, leading = { IconStack((s?.keys.orEmpty() + i?.keys.orEmpty()).distinct(), d, max = 3, size = 28.dp) })
                    AuroraIconButton(AuroraIcons.Trash, stringResource(R.string.config_delete_title, c.name), { deleting = c })
                    }
                }
                item(key = "new") {
                    AuroraButton(stringResource(R.string.cfg_new), { id = newId(); name = ""; shieldId = null; intentId = null; step = Step.Contents }, Modifier.fillMaxWidth().padding(top = Space.S), kind = ButtonKind.Secondary)
                }
            }
            Step.Contents -> {
                stepTitle(R.string.cfg_contents_title, R.string.cfg_contents_body)
                if (custom) {
                    item(key = "name-label") { Label(stringResource(R.string.cfg_name_title)) }
                    item(key = "name") { AuroraTextField(name, { name = it.take(40) }, stringResource(R.string.cfg_name_placeholder), capitalization = KeyboardCapitalization.Words) }
                }
                item(key = "shield-label") { Label(stringResource(R.string.cfg_shield), none = shieldId == null) { shieldId = null } }
                items(d.rules.shields.sortedByDescending { it.createdAt }, key = { "s-" + it.id }) { p ->
                    PickRow(p.name, shieldDetail(p), shieldId == p.id, { shieldId = if (shieldId == p.id) null else p.id }, tag = { ModeChip(p.mode) })
                }
                item(key = "shield-create") {
                    Column(verticalArrangement = Arrangement.spacedBy(Space.XS)) {
                        if (d.rules.shields.isEmpty()) Text(stringResource(R.string.cfg_no_shields), color = colors.textSecondary, style = AuroraType.Secondary)
                        AuroraButton(stringResource(R.string.cfg_create_shield), onCreateShield, Modifier.fillMaxWidth(), kind = ButtonKind.Secondary, compact = true)
                    }
                }
                item(key = "intent-label") { Label(stringResource(R.string.cfg_intent), none = intentId == null) { intentId = null } }
                items(d.rules.intentPresets.sortedByDescending { it.createdAt }, key = { "i-" + it.id }) { p ->
                    PickRow(p.name, intentSummary(p.config), intentId == p.id, { intentId = if (intentId == p.id) null else p.id })
                }
                item(key = "intent-create") {
                    Column(verticalArrangement = Arrangement.spacedBy(Space.XS)) {
                        if (d.rules.intentPresets.isEmpty()) Text(stringResource(R.string.cfg_no_intents), color = colors.textSecondary, style = AuroraType.Secondary)
                        AuroraButton(stringResource(R.string.cfg_create_intent), onCreateIntent, Modifier.fillMaxWidth(), kind = ButtonKind.Secondary, compact = true)
                    }
                }
            }
            Step.When -> {
                stepTitle(R.string.cfg_when_title, null)
                item(key = "now") { ChoiceCard(stringResource(R.string.when_now), stringResource(R.string.when_now_desc), whenKind == When.Now, { whenKind = When.Now }) }
                item(key = "later") { ChoiceCard(stringResource(R.string.when_later), stringResource(R.string.when_later_desc), whenKind == When.Later, { whenKind = When.Later }) }
                item(key = "recurring") { ChoiceCard(stringResource(R.string.when_recurring), stringResource(R.string.when_recurring_desc), whenKind == When.Recurring, { whenKind = When.Recurring }) }
                item(key = "always") {
                    if (strict) {
                        // A strict Shield can't be scheduled as Always: shown, explained, not selectable.
                        Column(Modifier.fillMaxWidth().auroraSurface(colors).padding(Space.M), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(stringResource(R.string.when_always), color = colors.textTertiary, style = AuroraType.Title)
                            Text(stringResource(R.string.when_always_blocked), color = colors.textTertiary, style = AuroraType.Secondary)
                        }
                    } else ChoiceCard(stringResource(R.string.when_always), stringResource(R.string.when_always_desc), whenKind == When.Always, { whenKind = When.Always })
                }
                // The chosen timing's settings follow on the same screen.
                if (whenKind == null || whenKind == When.Always) return@StepFlow
                val allowance = if (shield?.mode?.usesQuota == true && whenKind == When.Now) vm.app.committedMinutesLeft() else null
                val max = allowance ?: (24 * 60)
                if (whenKind == When.Later) {
                    item(key = "date") { DateChoice(laterDay) { laterDay = it } }
                    item(key = "time") { TimeEntry(stringResource(R.string.later_time), laterMinute) { laterMinute = it } }
                }
                if (whenKind == When.Recurring) {
                    item(key = "days") {
                        Column(verticalArrangement = Arrangement.spacedBy(Space.XS)) {
                            Text(stringResource(R.string.recurring_days), color = colors.textPrimary, style = AuroraType.Title)
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.XS), verticalArrangement = Arrangement.spacedBy(Space.XS)) {
                                Chip(stringResource(R.string.recurring_every_day), days == (1..7).toSet()) { days = (1..7).toSet() }
                                Chip(stringResource(R.string.recurring_weekdays), days == setOf(1, 2, 3, 4, 5)) { days = setOf(1, 2, 3, 4, 5) }
                                Chip(stringResource(R.string.recurring_weekends), days == setOf(6, 7)) { days = setOf(6, 7) }
                            }
                            ChipFlow((1..7).toList(), days, { day -> days = if (day in days) days - day else days + day }, { dayShort(it) })
                        }
                    }
                    item(key = "time") { TimeEntry(stringResource(R.string.later_time), recurMinute) { recurMinute = it } }
                }
                item(key = "duration") {
                    DurationPicker(
                        minutes, { minutes = it }, ProtectionPresets.filter { it <= max }.let { if (whenKind == When.Now) listOf(15, 30, 60, 360).filter { m -> m <= max } else it },
                        title = stringResource(R.string.cfg_how_long), hint = stringResource(R.string.cfg_how_long_hint),
                        max = max.coerceAtLeast(1), endsFromNow = whenKind == When.Now,
                    )
                }
                if (allowance != null) item(key = "allowance") {
                    Text(stringResource(R.string.start_always_block_allowance, minutesText(allowance)), color = colors.violetText, style = AuroraType.Secondary)
                }
                if (whenKind == When.Later) item(key = "later-end") {
                    val m = minutes
                    if (laterStart <= System.currentTimeMillis()) Text(stringResource(R.string.later_in_past), color = colors.danger, style = AuroraType.Secondary)
                    else if (m != null) Text(stringResource(R.string.later_ends, dateTime(laterStart + m * 60_000L)), color = colors.accent, style = AuroraType.BodyStrong)
                    Text(stringResource(R.string.later_zone_note), Modifier.padding(top = Space.XS), color = colors.textTertiary, style = AuroraType.Caption)
                }
                if (whenKind == When.Recurring) item(key = "until") {
                    Column(verticalArrangement = Arrangement.spacedBy(Space.XS)) {
                        Text(stringResource(R.string.recurring_end), color = colors.textPrimary, style = AuroraType.Title)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.XS), verticalArrangement = Arrangement.spacedBy(Space.XS)) {
                            Chip(stringResource(R.string.recurring_no_end), until == null) { until = null }
                            listOf(4, 8, 12).forEach { w ->
                                val date = LocalDate.now().plusWeeks(w.toLong()).toString()
                                Chip(pluralStringResource(R.plurals.recurring_in_weeks, w, w), until == date) { until = date }
                            }
                        }
                        Text(stringResource(R.string.recurring_note), color = colors.textTertiary, style = AuroraType.Caption)
                    }
                }
            }
            Step.Focus -> {
                stepTitle(R.string.cfg_focus_title, R.string.cfg_focus_body)
                focusItems()
            }
            Step.Review -> {
                stepTitle(R.string.cfg_review_title, null)
                if (gate) item(key = "gate") { app.aurora.ui.protect.ShieldGate(hasSites, onOpenWeb) }
                item(key = "summary") { Summary(d, configName, shield, intent, focus, timing, zone) }
                val overlap = d.rules.copy(shields = d.rules.shields + listOfNotNull(shield), intentPresets = d.rules.intentPresets + listOfNotNull(intent))
                    .overlaps(config, vm.app.runsNow(d.time))
                if (overlap.isNotEmpty()) item(key = "overlap") {
                    Text(stringResource(R.string.cfg_overlap, overlap.joinToString(", ") { d.label(it).ifBlank { it.removePrefix("web:") } }), color = colors.textSecondary, style = AuroraType.Secondary)
                }
                if (strict) item(key = "commit") { CommitBlock(committed, { committed = it }, onStrict) }
                if (shield?.mode?.usesQuota == true) item(key = "allowance") {
                    Column(Modifier.padding(horizontal = Space.Gutter), verticalArrangement = Arrangement.spacedBy(Space.XS)) {
                        CommittedAllowance(d)
                        if (d.plan == app.aurora.plan.Plan.Free && whenKind == When.Recurring) Text(stringResource(R.string.committed_schedule_note), color = colors.textSecondary, style = AuroraType.Caption)
                    }
                }
                // Focus is an addition, so it comes after what the session is.
                if (whenKind != When.Always) {
                    item(key = "focus-label") { Label(stringResource(R.string.cfg_focus_label)) }
                    focusItems()
                }
            }
        }
    }
    deleting?.let { c ->
        val goals = d.rules.trackGoals.filter { it.configId == c.id }
        AuroraDialog(
            stringResource(R.string.config_delete_title, c.name), { deleting = null },
            stringResource(if (goals.isEmpty()) R.string.preset_delete else R.string.config_delete_archive),
            {
                deleting = null
                vm.run { vm.app.deleteConfig(c.id, removeGoals = true) }
            },
            if (goals.isEmpty()) stringResource(R.string.config_delete_body) else stringResource(R.string.config_delete_habits, goals.joinToString(", ") { it.name }) + " " + stringResource(R.string.config_delete_body),
            stringResource(R.string.action_cancel),
        )
    }
}

/** The commitment every strict session asks for, wherever it is started. */
@Composable
fun CommittedAllowance(d: app.aurora.ui.protect.ProtectData) {
    val app = androidx.compose.ui.platform.LocalContext.current.applicationContext as app.aurora.AuroraApplication
    val status = d.runtime.quotaToday(d.plan, d.time, java.time.ZoneId.systemDefault())
    val left = app.serverAdjustedRemainingMs(status.remainingMs) ?: return
    val colors = Aurora.colors
    Column(Modifier.fillMaxWidth().auroraSurface(colors).padding(Space.M), verticalArrangement = Arrangement.spacedBy(Space.XS)) {
        Text(stringResource(R.string.committed_today_title), color = colors.textPrimary, style = AuroraType.BodyStrong)
        Text(
            stringResource(R.string.committed_today_body, minutesText((status.usedMs / 60_000).toInt()), minutesText((left / 60_000).toInt()), minutesText(((status.limitMs ?: 0) / 60_000).toInt()), clockTime(status.resetAt)),
            color = colors.textSecondary, style = AuroraType.Secondary,
        )
        Text(stringResource(R.string.committed_today_note), color = colors.textTertiary, style = AuroraType.Caption)
    }
}

@Composable
fun CommitBlock(committed: Boolean, onChange: (Boolean) -> Unit, onStrict: (() -> Unit)?) {
    val colors = Aurora.colors
    Column(verticalArrangement = Arrangement.spacedBy(Space.S)) {
        Text(stringResource(R.string.start_commit_body), color = colors.textSecondary, style = AuroraType.Body)
        Row(
            Modifier.fillMaxWidth().auroraSurface(colors, warm = committed).toggleable(committed, role = Role.Checkbox, onValueChange = onChange).padding(Space.M),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.M),
        ) {
            SelectionMark(committed)
            Text(stringResource(R.string.always_block_confirm), color = colors.textPrimary, style = AuroraType.BodyStrong)
        }
        Text(stringResource(R.string.start_commit_limits), color = colors.textTertiary, style = AuroraType.Caption)
        UninstallProtection()
        if (onStrict != null) AuroraButton(stringResource(R.string.cfg_strict_link), onStrict, kind = ButtonKind.Quiet)
    }
}

@Composable
private fun Label(text: String, none: Boolean? = null, onNone: () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(top = Space.S), verticalAlignment = Alignment.CenterVertically) {
        Text(text, Modifier.weight(1f), color = Aurora.colors.textSecondary, style = AuroraType.Eyebrow)
        // "None" is a quiet way back beside the heading, offered only once something is chosen.
        if (none == false) Chip(stringResource(R.string.cfg_none), false, onClick = onNone)
    }
}

/** Today, tomorrow and the next twelve days as chips: enough for "tomorrow at 7" and next week. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DateChoice(value: String, onChange: (String) -> Unit) {
    val today = LocalDate.now()
    Column(verticalArrangement = Arrangement.spacedBy(Space.XS)) {
        Text(stringResource(R.string.later_date), color = Aurora.colors.textPrimary, style = AuroraType.Title)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.XS), verticalArrangement = Arrangement.spacedBy(Space.XS)) {
            (0L..13L).map { today.plusDays(it) }.forEach { date ->
                val label = when (date) {
                    today -> stringResource(R.string.later_today)
                    today.plusDays(1) -> stringResource(R.string.later_tomorrow)
                    else -> date.format(DateTimeFormatter.ofPattern("EEE d MMM"))
                }
                Chip(label, value == date.toString()) { onChange(date.toString()) }
            }
        }
    }
}

private fun dateTime(ms: Long): String =
    Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT))

/** The review card: what will be enforced, when, and the Focus task. */
@Composable
private fun Summary(d: ProtectData, name: String, shield: ShieldPreset?, intent: IntentPreset?, focus: FocusPlan?, timing: Timing?, zone: ZoneId) {
    val colors = Aurora.colors
    Column(Modifier.fillMaxWidth().auroraSurface(colors, edge = shield?.let { modeColor(it.mode) }).padding(Space.L), verticalArrangement = Arrangement.spacedBy(Space.S)) {
        Text(name, color = colors.textPrimary, style = AuroraType.Headline)
        if (shield != null) {
            ModeChip(shield.mode)
            if (shield.name != name) Text(shield.name, color = colors.textPrimary, style = AuroraType.BodyStrong)
            IconStack(shield.keys, d, max = 7)
        }
        if (intent != null) {
            Text(intent.name + " · " + intentSummary(intent.config), color = colors.textSecondary, style = AuroraType.Secondary)
            IconStack(intent.keys, d, max = 7)
        }
        timing?.let { Text(timingText(it, zone), color = colors.accent, style = AuroraType.BodyStrong) }
        focus?.let { Text(stringResource(R.string.cfg_review_focus, it.task), color = colors.textSecondary, style = AuroraType.Secondary) }
    }
}

@Composable
fun timingText(t: Timing, zone: ZoneId): String = when (t) {
    is Timing.Now -> stringResource(R.string.cfg_review_when_now, minutesText(t.minutes))
    is Timing.Later -> stringResource(R.string.cfg_review_when_later, dateTime(t.start), minutesText(t.minutes))
    is Timing.Recurring -> stringResource(R.string.cfg_review_when_recurring, daysText(t.days), minuteOfDay(t.startMinute), minutesText(t.minutes))
    Timing.Always -> stringResource(R.string.cfg_review_when_always)
}

@Composable
fun problemText(p: ActivationProblem): String = stringResource(
    when (p) {
        ActivationProblem.Empty -> R.string.cfg_problem_empty
        ActivationProblem.AlwaysWithAlwaysBlock -> R.string.when_always_blocked
        ActivationProblem.InThePast -> R.string.later_in_past
        ActivationProblem.NoDays -> R.string.cfg_problem_days
        ActivationProblem.TooShort -> R.string.duration_problem_empty
        ActivationProblem.OverItems -> R.string.cfg_problem_items
        ActivationProblem.OverIntentApps -> R.string.cfg_problem_intents
        ActivationProblem.OverCommittedAllowance -> R.string.cfg_problem_allowance
        ActivationProblem.NeedsConnection -> R.string.cfg_problem_connection
    },
)

private val Int.dp get() = androidx.compose.ui.unit.Dp(this.toFloat())
