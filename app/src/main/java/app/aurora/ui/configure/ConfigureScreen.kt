package app.aurora.ui.configure

import app.aurora.protect.needsContentShield
import app.aurora.ui.components.Eyebrow
import app.aurora.ui.components.RowDivider
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import app.aurora.ui.components.glass
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.height
import androidx.compose.ui.draw.clip
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
import app.aurora.ui.components.ChoiceCard
import app.aurora.ui.components.DurationPicker
import app.aurora.ui.components.SelectionMark
import app.aurora.ui.components.ToggleRow
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
 * Saved (pick one, or New), Name (and whether to keep it), Contents (at most one Shield and one Intent),
 * When (the kind of timing), Timing (its settings; skipped for Always), then Review (Focus timer and a message). A saved session
 * skips Name and Contents. A configuration made for a Goal is saved after Name, Contents and Focus.
 */
private enum class Step { Saved, Name, Contents, When, Timing, Focus, Review }

/**
 * Home's Start session flow. [custom] is whether a new session is kept under Saved Sessions to start again
 * (the person can change it on the Name step); one not kept carries its own copies and leaves nothing behind.
 * [createdShield]/[createdIntent] are presets the person just made from here, selected on return.
 * [forGoal]: always kept, and save without starting is the main action.
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
    /** Home's Start on a saved session: [configId] is started as saved, beginning at When. */
    startSaved: Boolean = false,
) {
    val vm = auroraViewModel { ProtectModel(it) }
    val data by vm.data.collectAsStateWithLifecycle()
    val d = data ?: return
    val colors = Aurora.colors
    val zone = ZoneId.systemDefault()
    val running = remember(d) { vm.app.runsNow(d.time).map { it.configId }.toSet() }
    val saved = d.rules.configs.filter { it.custom && it.id !in running }.sortedByDescending { it.createdAt }
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

    var save by rememberSaveable { mutableStateOf(custom || forGoal) }
    // A saved session was picked: it starts as saved, so Name and Contents are skipped.
    var fromSaved by rememberSaveable { mutableStateOf(startSaved && start != null) }
    var note by rememberSaveable { mutableStateOf(start?.note.orEmpty()) }
    var messageOn by rememberSaveable { mutableStateOf(start?.note?.isNotBlank() == true) }
    var focusOn by rememberSaveable { mutableStateOf(start?.focus != null) }
    // What this Focus is for, in the person's own words: shown above the timer while it runs.
    var goal by rememberSaveable { mutableStateOf(start?.focus?.goal.orEmpty()) }
    var focusWindow by rememberSaveable { mutableStateOf(start?.focus?.minutes == null) }
    var focusMinutes by rememberSaveable { mutableStateOf(start?.focus?.minutes) }
    var committed by rememberSaveable { mutableStateOf(false) }
    var problem by rememberSaveable { mutableStateOf<ActivationProblem?>(null) }
    var step by rememberSaveable {
        mutableStateOf(
            startStep?.let { Step.valueOf(it) } ?: when {
                fromSaved -> Step.When
                start == null && saved.isNotEmpty() && !forGoal -> Step.Saved
                else -> Step.Name
            },
        )
    }

    var deleting by remember { mutableStateOf<UsageConfig?>(null) }
    var committing by remember { mutableStateOf(false) }
    LaunchedEffect(createdShield) { if (createdShield != null) shieldId = createdShield }
    LaunchedEffect(createdIntent) { if (createdIntent != null) intentId = createdIntent }

    // The configuration as it stands. A Normal Session carries its own copies, so it leaves nothing saved behind.
    val shield: ShieldPreset? = shieldId?.let { s -> d.rules.shields.find { it.id == s } }
    val intent: IntentPreset? = intentId?.let { s -> d.rules.intentPresets.find { it.id == s } }
    val keep = save || fromSaved
    val configName = name.trim().ifEmpty { listOfNotNull(shield?.name, intent?.name).joinToString(" + ").ifEmpty { stringResource(R.string.home_normal_title) } }
    // The Focus timer is a yes or no, with an optional goal typed in; it carries the session's name.
    val focusPlan = if (focusOn) FocusPlan(configName, goal = goal.trim(), minutes = if (focusWindow) null else focusMinutes) else null
    val focus = focusPlan.takeIf { whenKind != When.Always }
    val config = UsageConfig(
        id, configName, keep,
        shieldId = if (keep) shield?.id else null, intentId = if (keep) intent?.id else null,
        shield = if (keep) null else shield, intent = if (keep) null else intent,
        focus = focus, createdAt = start?.createdAt ?: System.currentTimeMillis(), note = if (messageOn) note.trim() else "",
    )
    val alwaysBlock = shield?.mode == Mode.AlwaysBlock
    // Strict, Cooldown and Friction: no early exit, no Always timing, and a commitment to confirm.
    val strict = shield?.mode?.strict == true
    val laterStart = LocalDateTime.of(LocalDate.parse(laterDay), java.time.LocalTime.of(laterMinute / 60, laterMinute % 60)).atZone(zone).toInstant().toEpochMilli()
    val timing: Timing? = when (whenKind) {
        When.Now -> minutes?.let { Timing.Now(it) }
        When.Later -> minutes?.let { Timing.Later(laterStart, it) }
        // A schedule has no last date: it runs until it's deleted.
        When.Recurring -> minutes?.let { Timing.Recurring(days, recurMinute, it) }
        When.Always -> Timing.Always
        null -> null
    }

    val shieldOn = app.aurora.rememberAccess(app.aurora.data.Access.Accessibility) == app.aurora.data.AccessState.Granted
    // Free's committed minutes left today: the pre-picked hour shrinks to fit.
    val blockLeft = if (shield?.mode?.usesQuota == true && whenKind == When.Now) vm.app.committedMinutesLeft() else null
    androidx.compose.runtime.LaunchedEffect(blockLeft) { if (blockLeft != null && (minutes ?: 0) > blockLeft) minutes = blockLeft.takeIf { it > 0 } }
    val hasSites = needsContentShield(shield?.keys.orEmpty())
    val gate = app.aurora.ui.protect.shieldGateNeeded(hasSites)
    val steps = buildList {
        if (saved.isNotEmpty() && !forGoal && !startSaved) add(Step.Saved)
        if (!fromSaved) { add(Step.Name); add(Step.Contents) }
        // Starting a saved session goes straight from its timing to Start: Review (Focus, message) is for editing it.
        // A strict one asks "I'm ready to commit" in a dialog; Shield setup shows on the last step only when it's needed.
        if (!forGoal) { add(Step.When); if (whenKind != When.Always) add(Step.Timing); if (!fromSaved) add(Step.Review) } else add(Step.Focus)
    }
    // The step that starts the session: Review, or the last timing step when starting a saved session.
    val finish = !forGoal && step == steps.last()
    val index = steps.indexOf(step).coerceAtLeast(0)
    val back = { problem = null; if (index == 0) onBack() else step = steps[index - 1] }
    val hasContent = (shield?.keys?.isNotEmpty() == true) || (intent?.keys?.isNotEmpty() == true)
    val focusValid = !focusOn || focusWindow || focusMinutes != null
    val ready = hasContent && shieldOn && (!strict || committed || fromSaved)
    val stepOk = when (step) {
        Step.Saved -> true
        Step.Name -> name.isNotBlank()
        Step.Contents -> shield != null || intent != null
        Step.When -> whenKind != null && !(whenKind == When.Always && strict)
        Step.Timing -> when (whenKind) {
            null, When.Always -> false
            else -> minutes != null && (whenKind != When.Later || laterStart > System.currentTimeMillis()) && (whenKind != When.Recurring || days.isNotEmpty())
        }
        Step.Focus -> focusValid
        // Without Shield this would only be a timer, so nothing starts until it's on.
        Step.Review -> true
    }
    val canNext = stepOk && (!finish || ready)
    // Both run in the ViewModel's scope (the main thread), so navigating afterwards is always safe.
    val saveOnly = {
        vm.run {
            if (vm.app.saveConfig(config.copy(focus = focusPlan)) == null) onDone(config.id)
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
            item(key = "goal") { GoalField(goal) { goal = it } }
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
    // The motivational message sits with Focus: added, changed or turned off in the same place.
    val messageItem: androidx.compose.foundation.lazy.LazyListScope.() -> Unit = {
        item(key = "message") {
            Column(verticalArrangement = Arrangement.spacedBy(Space.S)) {
                ToggleRow(stringResource(R.string.cfg_message_on), stringResource(R.string.cfg_message_body), messageOn, { messageOn = it })
                if (messageOn) AuroraTextField(note, { note = it.take(140) }, stringResource(R.string.cfg_message_placeholder), capitalization = KeyboardCapitalization.Sentences, singleLine = false)
            }
        }
    }

    StepFlow(
        steps.size, index, back,
        primary = when {
            step == Step.Focus && forGoal -> stringResource(R.string.action_save)
            !finish -> stringResource(R.string.action_next)
            whenKind == When.Now -> stringResource(R.string.cfg_start)
            whenKind == When.Always -> stringResource(R.string.cfg_turn_on)
            else -> stringResource(R.string.cfg_schedule)
        },
        primaryEnabled = canNext,
        whyDisabled = if (stepOk) stringResource(
            when {
                !hasContent -> R.string.cfg_problem_empty
                !shieldOn -> R.string.need_shield
                else -> R.string.need_commit
            },
        ) else when (step) {
            Step.Saved -> null
            Step.Name -> stringResource(R.string.need_name)
            Step.Contents -> stringResource(R.string.cfg_problem_empty)
            Step.When -> stringResource(if (whenKind == null) R.string.need_when else R.string.when_always_blocked)
            Step.Timing -> when {
                minutes == null -> stringResource(R.string.duration_problem_empty)
                whenKind == When.Recurring -> stringResource(R.string.cfg_problem_days)
                else -> null // A start in the past is already said beside the date.
            }
            Step.Focus -> stringResource(R.string.need_time)
            Step.Review -> null
        },
        onPrimary = {
            when {
                step == Step.Focus && forGoal -> saveOnly()
                finish && fromSaved && strict -> committing = true
                finish -> confirm()
                else -> step = steps[index + 1]
            }
        },
        footer = {
            // Plan limits are explained at the app's root.
            problem?.takeUnless { it.overLimit }?.let { p -> Text(problemText(p), color = colors.danger, style = AuroraType.Secondary) }
            if (save && !fromSaved && !forGoal && step == Step.Review) {
                AuroraButton(stringResource(R.string.cfg_save_for_later), { saveOnly() }, Modifier.fillMaxWidth(), kind = ButtonKind.Quiet)
            }
        },
    ) {
        when (step) {
            Step.Saved -> {
                stepTitle(R.string.cfg_saved_title, R.string.cfg_saved_body)
                // New first, then everything saved under its own heading.
                item(key = "new") {
                    AuroraButton(stringResource(R.string.cfg_new), {
                        id = newId(); name = ""; shieldId = null; intentId = null; note = ""; messageOn = false
                        focusOn = false; goal = ""; focusWindow = true; focusMinutes = null
                        fromSaved = false; step = Step.Name
                    }, Modifier.fillMaxWidth(), kind = ButtonKind.Secondary, icon = AuroraIcons.Plus)
                }
                item(key = "saved-label") { Label(stringResource(R.string.cfg_saved_heading)) }
                items(saved, key = { "cfg-" + it.id }) { c ->
                    val s = d.rules.shieldOf(c)
                    val i = d.rules.intentOf(c)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                    PickRow(c.name, listOfNotNull(s?.name, i?.name, c.focus?.task).joinToString(" · "), id == c.id, modifier = Modifier.weight(1f), onClick = {
                        id = c.id; name = c.name; shieldId = c.shieldId; intentId = c.intentId; note = c.note
                        focusOn = c.focus != null; goal = c.focus?.goal.orEmpty()
                        focusWindow = c.focus?.minutes == null; focusMinutes = c.focus?.minutes
                        messageOn = c.note.isNotBlank(); fromSaved = true; step = Step.When
                    }, leading = { IconStack((s?.keys.orEmpty() + i?.keys.orEmpty()).distinct(), d, max = 3, size = 28.dp) })
                    AuroraIconButton(AuroraIcons.Trash, stringResource(R.string.config_delete_title, c.name), { deleting = c })
                    }
                }
            }
            Step.Name -> {
                stepTitle(R.string.cfg_name_title, null)
                item(key = "name") { AuroraTextField(name, { name = it.take(40) }, "", capitalization = KeyboardCapitalization.Words) }
                if (!forGoal) item(key = "save") { ToggleRow(stringResource(R.string.quick_save), stringResource(R.string.quick_save_body), save, { save = it }) }
            }
            Step.Contents -> {
                stepTitle(R.string.cfg_contents_title, R.string.cfg_contents_body)
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
            }
            Step.Timing -> {
                // The chosen timing's settings, on their own page: the answer first, large, then only the questions.
                stepTitle(
                    when (whenKind) {
                        When.Later -> R.string.when_later
                        When.Recurring -> R.string.when_recurring
                        else -> R.string.when_now
                    },
                    null,
                )
                val allowance = if (shield?.mode?.usesQuota == true && whenKind == When.Now) vm.app.committedMinutesLeft() else null
                val max = allowance ?: (24 * 60)
                item(key = "answer") {
                    val m = minutes
                    val past = whenKind == When.Later && laterStart <= System.currentTimeMillis()
                    TimingAnswer(
                        m,
                        when {
                            past -> stringResource(R.string.later_in_past)
                            m == null -> null
                            whenKind == When.Now -> stringResource(R.string.focus_ends_at, clockTime(System.currentTimeMillis() + m * 60_000L))
                            whenKind == When.Later -> stringResource(R.string.later_ends, dateTime(laterStart + m * 60_000L))
                            else -> listOf(daysText(days), minuteOfDay(recurMinute) + " – " + minuteOfDay(recurMinute + m)).filter { it.isNotEmpty() }.joinToString(" · ")
                        },
                        warn = past,
                    )
                }
                if (whenKind == When.Later) {
                    item(key = "date") { DateChoice(laterDay) { laterDay = it } }
                    item(key = "time") {
                        Column(verticalArrangement = Arrangement.spacedBy(Space.XS)) {
                            Label(stringResource(R.string.later_time))
                            TimeEntry(null, laterMinute, echo = false) { laterMinute = it }
                        }
                    }
                }
                if (whenKind == When.Recurring) {
                    item(key = "days") {
                        Column(verticalArrangement = Arrangement.spacedBy(Space.XS)) {
                            Label(stringResource(R.string.recurring_days))
                            DayRow(days) { days = it }
                        }
                    }
                    item(key = "time") {
                        Column(verticalArrangement = Arrangement.spacedBy(Space.XS)) {
                            Label(stringResource(R.string.later_time))
                            TimeEntry(null, recurMinute, echo = false) { recurMinute = it }
                        }
                    }
                }
                item(key = "duration") {
                    Column(verticalArrangement = Arrangement.spacedBy(Space.XS)) {
                        Label(stringResource(R.string.cfg_how_long))
                        DurationPicker(
                            minutes, { minutes = it }, ProtectionPresets.filter { it <= max }.let { if (whenKind == When.Now) listOf(15, 30, 60, 360).filter { m -> m <= max } else it },
                            max = max.coerceAtLeast(1), customInitially = custom,
                        )
                    }
                }
                if (allowance != null) item(key = "allowance") {
                    Text(stringResource(R.string.start_always_block_allowance, minutesText(allowance)), color = colors.textTertiary, style = AuroraType.Caption)
                }
            }
            Step.Focus -> {
                stepTitle(R.string.cfg_focus_title, R.string.cfg_focus_body)
                focusItems()
                messageItem()
            }
            Step.Review -> {
                stepTitle(R.string.cfg_review_title, null)
                // The session as one card, then the two optional extras together, then only what still needs doing.
                val allowance = if (shield?.mode?.usesQuota == true) vm.app.committedMinutesLeft() else null
                item(key = "summary") {
                    val allowanceNote = when {
                        allowance == null -> null
                        d.plan == app.aurora.plan.Plan.Free && whenKind == When.Recurring -> stringResource(R.string.committed_schedule_note)
                        else -> stringResource(R.string.start_always_block_allowance, minutesText(allowance))
                    }
                    Summary(d, configName, shield, intent, timing, zone, allowanceNote)
                }
                item(key = "extras") {
                    Column(Modifier.fillMaxWidth().auroraSurface(colors)) {
                        if (whenKind != When.Always) {
                            ToggleRow(stringResource(R.string.cfg_focus_timer), stringResource(R.string.cfg_focus_timer_body), focusOn, { focusOn = it })
                            if (focusOn) GoalField(goal, Modifier.padding(start = 18.dp, end = 18.dp, bottom = Space.M)) { goal = it }
                            RowDivider()
                        }
                        ToggleRow(stringResource(R.string.cfg_message_on), stringResource(R.string.cfg_message_body), messageOn, { messageOn = it })
                        if (messageOn) AuroraTextField(
                            note, { note = it.take(140) }, stringResource(R.string.cfg_message_placeholder),
                            Modifier.padding(start = 18.dp, end = 18.dp, bottom = Space.M), capitalization = KeyboardCapitalization.Sentences, singleLine = false,
                        )
                    }
                }
                if (gate) item(key = "gate") { app.aurora.ui.protect.ShieldGate(hasSites, onOpenWeb) }
                val overlap = d.rules.copy(shields = d.rules.shields + listOfNotNull(shield), intentPresets = d.rules.intentPresets + listOfNotNull(intent))
                    .overlaps(config, vm.app.runsNow(d.time))
                if (overlap.isNotEmpty()) item(key = "overlap") {
                    Text(stringResource(R.string.cfg_overlap, overlap.joinToString(", ") { d.label(it).ifBlank { it.removePrefix("web:") } }), color = colors.textTertiary, style = AuroraType.Caption)
                }
                // The commitment last: everything to read first, "I'm ready to commit" at the end.
                if (strict) item(key = "commit") { CommitBlock(committed, { committed = it }, onStrict) }
            }
        }
        if (fromSaved && finish && gate) item(key = "gate") { app.aurora.ui.protect.ShieldGate(hasSites, onOpenWeb) }
    }
    if (committing) AuroraDialog(
        configName, { committing = false },
        stringResource(R.string.always_block_confirm), { committing = false; confirm() },
        stringResource(R.string.start_commit_body) + "\n\n" + stringResource(R.string.start_commit_limits),
        stringResource(R.string.action_cancel),
    )
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

/** What a strict session locks, read as one group, then "I'm ready to commit" below all of it. */
@Composable
fun CommitBlock(committed: Boolean, onChange: (Boolean) -> Unit, onStrict: (() -> Unit)?) {
    val colors = Aurora.colors
    Column(verticalArrangement = Arrangement.spacedBy(Space.S)) {
        Column(Modifier.fillMaxWidth().auroraSurface(colors).padding(horizontal = 22.dp, vertical = 20.dp), verticalArrangement = Arrangement.spacedBy(Space.S)) {
            Text(stringResource(R.string.start_commit_body), color = colors.textPrimary, style = AuroraType.Body)
            Text(stringResource(R.string.start_commit_limits), color = colors.textSecondary, style = AuroraType.Secondary)
            if (onStrict != null) AuroraButton(stringResource(R.string.cfg_strict_link), onStrict, Modifier.fillMaxWidth(), kind = ButtonKind.Quiet, compact = true)
        }
        UninstallProtection()
        Row(
            Modifier.fillMaxWidth().auroraSurface(colors, warm = committed).toggleable(committed, role = Role.Checkbox, onValueChange = onChange).padding(Space.M),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.M),
        ) {
            SelectionMark(committed)
            Text(stringResource(R.string.always_block_confirm), color = colors.textPrimary, style = AuroraType.BodyStrong)
        }
    }
}

/** What a Focus timer is for, typed in. Optional; shown above the timer while it runs. */
@Composable
private fun GoalField(goal: String, modifier: Modifier = Modifier, onChange: (String) -> Unit) {
    AuroraTextField(goal, { onChange(it.take(60)) }, stringResource(R.string.cfg_focus_goal_placeholder), modifier, capitalization = KeyboardCapitalization.Sentences)
}

@Composable
private fun Label(text: String, none: Boolean? = null, onNone: () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(top = Space.S), verticalAlignment = Alignment.CenterVertically) {
        Eyebrow(text, Modifier.weight(1f), color = Aurora.colors.textSecondary)
        // "None" is a quiet way back beside the heading, offered only once something is chosen.
        if (none == false) Chip(stringResource(R.string.cfg_none), false, onClick = onNone)
    }
}

/** Today, Tomorrow, or Custom: any later date from the calendar. A custom date shows on its chip. */
@OptIn(ExperimentalLayoutApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun DateChoice(value: String, onChange: (String) -> Unit) {
    val today = LocalDate.now()
    val chosen = LocalDate.parse(value)
    val custom = chosen != today && chosen != today.plusDays(1)
    var picking by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(Space.XS)) {
        Label(stringResource(R.string.later_date))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.XS), verticalArrangement = Arrangement.spacedBy(Space.XS)) {
            Chip(stringResource(R.string.later_today), chosen == today) { onChange(today.toString()) }
            Chip(stringResource(R.string.later_tomorrow), chosen == today.plusDays(1)) { onChange(today.plusDays(1).toString()) }
            Chip(if (custom) chosen.format(DateTimeFormatter.ofPattern("EEE d MMM")) else stringResource(R.string.later_custom_date), custom) { picking = true }
        }
    }
    if (picking) {
        // The picker works in UTC midnights; only today and later can be picked.
        val utc = java.time.ZoneOffset.UTC
        val state = androidx.compose.material3.rememberDatePickerState(
            initialSelectedDateMillis = chosen.atStartOfDay(utc).toInstant().toEpochMilli(),
            selectableDates = object : androidx.compose.material3.SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long) = !Instant.ofEpochMilli(utcTimeMillis).atZone(utc).toLocalDate().isBefore(today)
                override fun isSelectableYear(year: Int) = year >= today.year
            },
        )
        androidx.compose.material3.DatePickerDialog(
            onDismissRequest = { picking = false },
            confirmButton = {
                AuroraButton(stringResource(R.string.action_ok), {
                    state.selectedDateMillis?.let { onChange(Instant.ofEpochMilli(it).atZone(utc).toLocalDate().toString()) }
                    picking = false
                }, kind = ButtonKind.Quiet, compact = true)
            },
            dismissButton = { AuroraButton(stringResource(R.string.action_cancel), { picking = false }, kind = ButtonKind.Quiet, compact = true) },
            colors = androidx.compose.material3.DatePickerDefaults.colors(containerColor = Aurora.colors.elevated),
        ) {
            androidx.compose.material3.DatePicker(state, showModeToggle = false, colors = androidx.compose.material3.DatePickerDefaults.colors(containerColor = Aurora.colors.elevated))
        }
    }
}

/**
 * The timing page's answer, said once and large: the length in light tabular Geist (numbers big, units small) on a lit
 * card, and one line under it saying when it ends or runs. [warn] sets that line in the danger colour.
 */
@Composable
private fun TimingAnswer(minutes: Int?, line: String?, warn: Boolean = false) {
    val colors = Aurora.colors
    val big = AuroraType.Metric.toSpanStyle()
    val unit = AuroraType.Title.toSpanStyle().copy(color = colors.textSecondary, fontSize = androidx.compose.ui.unit.TextUnit(24f, androidx.compose.ui.unit.TextUnitType.Sp))
    val text = androidx.compose.ui.text.buildAnnotatedString {
        if (minutes == null) { pushStyle(big); append("–"); pop(); return@buildAnnotatedString }
        val h = minutes / 60
        val m = minutes % 60
        if (h > 0) { pushStyle(big); append("$h"); pop(); pushStyle(unit); append(" h"); pop() }
        if (h > 0 && m > 0) append("  ")
        if (m > 0 || h == 0) { pushStyle(big); append("$m"); pop(); pushStyle(unit); append(" min"); pop() }
    }
    Column(
        Modifier.fillMaxWidth().glass(colors).padding(horizontal = 22.dp, vertical = 22.dp)
            .semantics(mergeDescendants = true) { },
        verticalArrangement = Arrangement.spacedBy(Space.XS),
    ) {
        Text(text, color = colors.textPrimary, style = AuroraType.Metric)
        line?.let { Text(it, color = if (warn) colors.danger else colors.textSecondary, style = AuroraType.Body) }
    }
}

/** The week as one row of round days, Monday first: tap to add or take a day away. */
@Composable
private fun DayRow(days: Set<Int>, onChange: (Set<Int>) -> Unit) {
    val colors = Aurora.colors
    val locale = androidx.compose.ui.platform.LocalConfiguration.current.locales[0]
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        (1..7).forEach { day ->
            val on = day in days
            val dow = java.time.DayOfWeek.of(day)
            val full = dow.getDisplayName(java.time.format.TextStyle.FULL, locale)
            androidx.compose.foundation.layout.Box(
                Modifier.weight(1f).heightIn(min = 44.dp).aspectRatio(1f, matchHeightConstraintsFirst = false)
                    .clip(CircleShape)
                    .background(if (on) colors.accent else androidx.compose.ui.graphics.Color.Transparent)
                    .then(if (on) Modifier else Modifier.border(1.dp, colors.hairlineStrong, CircleShape))
                    .toggleable(on, role = Role.Checkbox) { onChange(if (it) days + day else days - day) }
                    .semantics { contentDescription = full },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    dow.getDisplayName(java.time.format.TextStyle.NARROW, locale),
                    color = if (on) colors.onAccent else colors.textSecondary,
                    style = if (on) AuroraType.Choice.copy(fontWeight = androidx.compose.ui.text.font.FontWeight(600)) else AuroraType.Choice,
                )
            }
        }
    }
}

private fun dateTime(ms: Long): String =
    Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT))

/** The review card: what will be enforced, when, and the Focus task. */
/** The session at a glance: its name and kind, the faces of what it holds back, and when it runs. */
@Composable
private fun Summary(d: ProtectData, name: String, shield: ShieldPreset?, intent: IntentPreset?, timing: Timing?, zone: ZoneId, note: String?) {
    val colors = Aurora.colors
    val keys = (shield?.keys.orEmpty() + intent?.keys.orEmpty()).distinct()
    Column(Modifier.fillMaxWidth().auroraSurface(colors).padding(22.dp), verticalArrangement = Arrangement.spacedBy(Space.M)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.S)) {
            Text(name, Modifier.weight(1f), color = colors.textPrimary, style = AuroraType.Headline, maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            shield?.let { ModeChip(it.mode) }
        }
        if (keys.isNotEmpty()) IconStack(keys, d, max = 7)
        intent?.let { Text(intentSummary(it.config), color = colors.textSecondary, style = AuroraType.Secondary) }
        timing?.let {
            RowDivider()
            Text(timingText(it, zone), color = colors.textPrimary, style = AuroraType.BodyStrong)
        }
        note?.let { Text(it, color = colors.textTertiary, style = AuroraType.Caption) }
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
        ActivationProblem.SessionRunning -> R.string.cfg_problem_session_running
    },
)

private val Int.dp get() = androidx.compose.ui.unit.Dp(this.toFloat())
