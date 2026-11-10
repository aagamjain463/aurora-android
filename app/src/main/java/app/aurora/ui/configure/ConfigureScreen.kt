package app.aurora.ui.configure

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.size
import androidx.compose.ui.unit.sp
import app.aurora.ui.components.glassControl
import app.aurora.ui.theme.Radius
import app.aurora.ui.components.glassShine
import app.aurora.ui.components.glassCard
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
import app.aurora.protect.canonicalKey
import app.aurora.protect.intentConfigsOf
import app.aurora.protect.partNames
import app.aurora.ui.session.unlockItems
import androidx.compose.foundation.layout.width
import app.aurora.runsNow
import app.aurora.saveConfig
import app.aurora.ui.components.AuroraButton
import app.aurora.ui.components.AuroraDialog
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
import app.aurora.ui.library.strictnessItems
import app.aurora.protect.modeOf
import app.aurora.protect.frictionOf
import app.aurora.protect.cooldownWaitOf
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

/** When to run. [Save]: keep it in Saved without starting (only where saving is the point). */
enum class When { Now, Later, Recurring, Always, Save }

/**
 * Name, Contents (as many Shields and Intents as wanted, no app in two of them), Strictness, Unlocks (the breaks it
 * allows), When (the kind of timing), Timing (its settings; skipped for Always), then Review (Focus timer and a message).
 * Starting a saved session: its Unlocks (to change if wanted; the rest only to view), When, Timing, Review. Editing one, or
 * making one for a habit: Name, Contents, Strictness, Unlocks, then Focus and the message, and save.
 */
private enum class Step { Name, Contents, Strictness, Unlocks, When, Timing, Focus, Review }

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
    val start = d.rules.configs.find { it.id == configId }

    var id by rememberSaveable { mutableStateOf(start?.id ?: newId()) }
    var name by rememberSaveable { mutableStateOf(start?.name.orEmpty()) }
    var shieldIds by rememberSaveable { mutableStateOf(start?.allShieldIds.orEmpty()) }
    var intentIds by rememberSaveable { mutableStateOf(start?.allIntentIds.orEmpty()) }
    var unlocksJson by rememberSaveable { mutableStateOf(app.aurora.data.AuroraJson.encodeToString(start?.unlocks.orEmpty())) }
    val unlocks: List<app.aurora.protect.SessionUnlock> = remember(unlocksJson) { app.aurora.data.AuroraJson.decodeFromString(unlocksJson) }
    var unlockJson by rememberSaveable { mutableStateOf<String?>(null) }
    val unlock: app.aurora.protect.SessionUnlock? = unlockJson?.let { app.aurora.data.AuroraJson.decodeFromString<app.aurora.protect.SessionUnlock>(it) }
    // A pass being added says what it is for: an Other pass has no apps yet, so it can't say so itself.
    var newPassKind by rememberSaveable { mutableStateOf<String?>(null) }
    fun editUnlock(u: app.aurora.protect.SessionUnlock?) { unlockJson = u?.let { app.aurora.data.AuroraJson.encodeToString(it) }; if (u == null) newPassKind = null }
    var clash by remember { mutableStateOf<Pair<String, Set<String>>?>(null) }
    var details by remember { mutableStateOf(false) }

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
                fromSaved -> Step.Unlocks
                else -> Step.Name
            },
        )
    }

    // How strict the session is: its own, or (older data) its Shield's.
    var mode by rememberSaveable { mutableStateOf(start?.let { d.rules.modeOf(it) } ?: Mode.Flexible) }
    // Every strictness is confirmed once, in a dialog that says what it does; the default too, on Next.
    var modeConfirmed by rememberSaveable { mutableStateOf(false) }
    var askingMode by rememberSaveable { mutableStateOf<Mode?>(null) }
    var askingAdvance by rememberSaveable { mutableStateOf(false) }
    val startFriction = start?.let { d.rules.frictionOf(it) } ?: app.aurora.protect.FrictionSettings()
    var task by rememberSaveable { mutableStateOf(if (startFriction.task == app.aurora.protect.FrictionTask.Reflection) app.aurora.protect.FrictionTask.Math else startFriction.task) }
    var difficulty by rememberSaveable { mutableStateOf(startFriction.difficulty) }
    var phrase by rememberSaveable { mutableStateOf(startFriction.phrase) }
    var wait by rememberSaveable { mutableStateOf<Int?>(start?.let { d.rules.cooldownWaitOf(it) } ?: 1) }
    val friction = startFriction.copy(task = task, difficulty = difficulty, phrase = phrase.trim())
    val wordsMissing = mode == Mode.Friction && !app.aurora.protect.FrictionSettings(task = task, phrase = phrase).ready

    var committing by remember { mutableStateOf(false) }
    /** The Shield or Intent already chosen that shares an app with [keys], with those apps; null when none does. */
    fun clashWith(pid: String, keys: Collection<String>): Pair<String, Set<String>>? {
        val mine = keys.map(::canonicalKey).toSet()
        val others = shieldIds.filter { it != pid }.mapNotNull { s -> d.rules.shields.find { it.id == s }?.let { it.name to it.keys } } +
            intentIds.filter { it != pid }.mapNotNull { s -> d.rules.intentPresets.find { it.id == s }?.let { it.name to it.keys } }
        return others.firstNotNullOfOrNull { (n, k) -> (mine intersect k.map(::canonicalKey).toSet()).takeIf { it.isNotEmpty() }?.let { n to it } }
    }
    // An app goes in one Shield or Intent of a session only: one that would share an app isn't added, and says why.
    fun addShield(pid: String) {
        val p = d.rules.shields.find { it.id == pid } ?: return
        clashWith(pid, p.keys)?.let { clash = it } ?: run { shieldIds = shieldIds + pid }
    }
    fun addIntent(pid: String) {
        val p = d.rules.intentPresets.find { it.id == pid } ?: return
        clashWith(pid, p.keys)?.let { clash = it } ?: run { intentIds = intentIds + pid }
    }
    LaunchedEffect(createdShield) { if (createdShield != null && createdShield !in shieldIds) addShield(createdShield) }
    // Contents shows Shields or Intents, one menu at a time; a just-made Intent comes back to its own menu.
    var intentsTab by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(createdIntent) { if (createdIntent != null && createdIntent !in intentIds) { intentsTab = true; addIntent(createdIntent) } }

    // The configuration as it stands. A Normal Session carries its own copies, so it leaves nothing saved behind.
    val chosen = UsageConfig("chosen", "", shieldIds = shieldIds.filter { s -> d.rules.shields.any { it.id == s } }, intentIds = intentIds.filter { s -> d.rules.intentPresets.any { it.id == s } })
    val shield: ShieldPreset? = d.rules.shieldOf(chosen)
    val intent: IntentPreset? = d.rules.intentOf(chosen)
    // Just save keeps it, from any way in; a new session can also be kept while it starts (Review).
    val keep = save || fromSaved || whenKind == When.Save
    val configName = name.trim().ifEmpty { listOfNotNull(shield?.name, intent?.name).joinToString(" + ").ifEmpty { stringResource(R.string.home_normal_title) } }
    // The Focus timer is a yes or no, with an optional goal typed in; it carries the session's name.
    val focusPlan = if (focusOn) FocusPlan(configName, goal = goal.trim(), minutes = if (focusWindow) null else focusMinutes) else null
    val focus = focusPlan.takeIf { whenKind != When.Always }
    val config = UsageConfig(
        id, configName, keep,
        shieldIds = if (keep) chosen.shieldIds else emptyList(), intentIds = if (keep) chosen.intentIds else emptyList(),
        shield = if (keep) null else shield, intent = if (keep) null else intent,
        intentConfigs = if (keep) emptyMap() else d.rules.intentConfigsOf(chosen),
        focus = focus, createdAt = start?.createdAt ?: System.currentTimeMillis(), note = if (messageOn) note.trim() else "",
        mode = mode, friction = friction.takeIf { mode == Mode.Friction }, cooldownWaitMinutes = wait.takeIf { mode == Mode.Cooldown },
        unlocks = unlocks,
    )
    val parts = d.rules.partNames(chosen).toList()
    // Strict, Cooldown and Friction: no early exit, no Always timing, and a commitment to confirm.
    val strict = mode.strict
    val laterStart = LocalDateTime.of(LocalDate.parse(laterDay), java.time.LocalTime.of(laterMinute / 60, laterMinute % 60)).atZone(zone).toInstant().toEpochMilli()
    val timing: Timing? = when (whenKind) {
        When.Now -> minutes?.let { Timing.Now(it) }
        When.Later -> minutes?.let { Timing.Later(laterStart, it) }
        // A schedule has no last date: it runs until it's deleted.
        When.Recurring -> minutes?.let { Timing.Recurring(days, recurMinute, it) }
        When.Always -> Timing.Always
        When.Save, null -> null
    }
    // Only a session made under Saved can be kept without starting it.
    val offerSave = !fromSaved && !forGoal
    var conflict by remember { mutableStateOf<app.aurora.protect.Conflict?>(null) }

    val shieldOn = app.aurora.rememberAccess(app.aurora.data.Access.Accessibility) == app.aurora.data.AccessState.Granted
    // Free's committed minutes left today: the pre-picked hour shrinks to fit.
    val blockLeft = if (mode.usesQuota && whenKind == When.Now) vm.app.committedMinutesLeft() else null
    androidx.compose.runtime.LaunchedEffect(blockLeft) { if (blockLeft != null && (minutes ?: 0) > blockLeft) minutes = blockLeft.takeIf { it > 0 } }
    val hasSites = needsContentShield(shield?.keys.orEmpty() + intent?.keys.orEmpty())
    val gate = app.aurora.ui.protect.shieldGateNeeded(hasSites)
    val steps = buildList {
        if (!fromSaved) { add(Step.Name); add(Step.Contents); add(Step.Strictness) }
        add(Step.Unlocks)
        // A strict saved session asks "I'm ready to commit" in a dialog; Shield setup shows on the last step only when needed.
        if (!forGoal) { add(Step.When); if (whenKind != When.Save) { if (whenKind != When.Always) add(Step.Timing); add(Step.Review) } } else add(Step.Focus)
    }
    // The step that starts the session: Review, or the last timing step when starting a saved session.
    val finish = !forGoal && step == steps.last()
    val index = steps.indexOf(step).coerceAtLeast(0)
    val back = { problem = null; if (index == 0) onBack() else step = steps[index - 1] }
    val hasContent = (shield?.keys?.isNotEmpty() == true) || (intent?.keys?.isNotEmpty() == true)
    // The Focus timer always has a goal: it is what the timer shows.
    val focusValid = !focusOn || (goal.isNotBlank() && (focusWindow || focusMinutes != null))
    val ready = hasContent && shieldOn && (!strict || committed || fromSaved)
    val stepOk = when (step) {
        Step.Name -> name.isNotBlank()
        Step.Contents -> shield != null || intent != null
        Step.Unlocks -> true
        Step.Strictness -> (mode != Mode.Cooldown || wait != null) && !wordsMissing
        Step.When -> whenKind != null && !(whenKind == When.Always && strict)
        Step.Timing -> when (whenKind) {
            null, When.Always, When.Save -> false
            else -> minutes != null && (whenKind != When.Later || laterStart > System.currentTimeMillis()) && (whenKind != When.Recurring || days.isNotEmpty())
        }
        Step.Focus -> focusValid
        // Without Shield this would only be a timer, so nothing starts until it's on.
        Step.Review -> focusValid
    }
    val canNext = stepOk && (!finish || ready || (whenKind == When.Save && hasContent))
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
            val refusal = vm.app.activate(config, t)
            problem = refusal?.problem
            conflict = refusal?.conflict
            if (problem == null) {
                if (android.os.Build.VERSION.SDK_INT >= 33 && !app.aurora.SessionNotification.allowed(context)) {
                    runCatching { notifications.launch(android.Manifest.permission.POST_NOTIFICATIONS) }
                }
                onDone(config.id)
            }
        }
    }
    // Friction, Cooldown and Always Block never start without uninstall protection: its short disclosure, its switch, then start.
    val guardLauncher = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) {
        if (app.aurora.protect.UninstallGuard.active(context)) confirm()
    }
    val askGuard = app.aurora.ui.protect.rememberUninstallRequest { runCatching { guardLauncher.launch(app.aurora.protect.UninstallGuard.enableIntent(context)) } }
    val begin = { if (strict && !app.aurora.protect.UninstallGuard.active(context)) askGuard() else confirm() }

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
            whenKind == When.Save -> stringResource(R.string.action_save)
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
            Step.Name -> stringResource(R.string.need_name)
            Step.Contents -> stringResource(R.string.cfg_problem_empty)
            Step.Unlocks -> null
            Step.Strictness -> stringResource(if (wordsMissing) R.string.friction_phrase_need else R.string.need_time)
            Step.When -> stringResource(if (whenKind == null) R.string.need_when else R.string.when_always_blocked)
            Step.Timing -> when {
                minutes == null -> stringResource(R.string.duration_problem_empty)
                whenKind == When.Recurring -> stringResource(R.string.cfg_problem_days)
                else -> null // A start in the past is already said beside the date.
            }
            Step.Focus -> stringResource(if (goal.isBlank()) R.string.need_focus_goal else R.string.need_time)
            Step.Review -> if (!focusValid) stringResource(R.string.need_focus_goal) else null
        },
        onPrimary = {
            when {
                step == Step.Focus && forGoal -> saveOnly()
                finish && whenKind == When.Save -> saveOnly()
                finish && fromSaved && strict -> committing = true
                finish -> begin()
                step == Step.Strictness && !modeConfirmed -> { askingAdvance = true; askingMode = mode }
                else -> step = steps[index + 1]
            }
        },
        footer = {
            // Plan limits are explained at the app's root.
            problem?.takeUnless { it.overLimit || it == ActivationProblem.Conflict }?.let { p -> Text(problemText(p), color = colors.danger, style = AuroraType.Secondary) }
        },
    ) {
        when (step) {
            Step.Name -> {
                stepTitle(R.string.cfg_name_title, null)
                item(key = "name") { AuroraTextField(name, { name = it.take(40) }, "", capitalization = KeyboardCapitalization.Words) }
            }
            Step.Contents -> {
                stepTitle(R.string.cfg_contents_title, R.string.cfg_contents_body)
                item(key = "chosen") { app.aurora.ui.session.ChosenLine(chosen.shieldIds.size, chosen.intentIds.size) }
                // Shields and Intents side by side as two menus, so neither is scrolled past to reach the other.
                item(key = "contents-tabs") {
                    val shieldTab = stringResource(R.string.cfg_shield)
                    val intentTab = stringResource(R.string.cfg_intent)
                    app.aurora.ui.components.ChoicePills(
                        listOf(false, true), intentsTab, { intentsTab = it },
                        label = { if (it) intentTab else shieldTab }, fill = true, segmented = true,
                    )
                }
                if (!intentsTab) {
                    item(key = "shield-create") { CreateRow(stringResource(R.string.cfg_create_shield), onCreateShield) }
                    if (d.rules.shields.isEmpty()) item(key = "shield-none") { EmptyNote(stringResource(R.string.cfg_no_shields)) }
                    items(d.rules.shields.sortedByDescending { it.createdAt }, key = { "s-" + it.id }) { p ->
                        PickRow(
                            p.name, shieldDetail(p), p.id in shieldIds, { if (p.id in shieldIds) shieldIds = shieldIds - p.id else addShield(p.id) }, Modifier.padding(bottom = Space.S),
                            leading = { androidx.compose.foundation.layout.Box(Modifier.width(60.dp)) { app.aurora.ui.protect.IconStack(p.keys, d, max = 3, size = 28.dp) } },
                        )
                    }
                } else {
                    item(key = "intent-create") { CreateRow(stringResource(R.string.cfg_create_intent), onCreateIntent) }
                    if (d.rules.intentPresets.isEmpty()) item(key = "intent-none") { EmptyNote(stringResource(R.string.cfg_no_intents)) }
                    items(d.rules.intentPresets.sortedByDescending { it.createdAt }, key = { "i-" + it.id }) { p ->
                        PickRow(
                            p.name, intentSummary(p.config), p.id in intentIds, { if (p.id in intentIds) intentIds = intentIds - p.id else addIntent(p.id) }, Modifier.padding(bottom = Space.S),
                            leading = { androidx.compose.foundation.layout.Box(Modifier.width(60.dp)) { app.aurora.ui.protect.IconStack(p.keys, d, max = 3, size = 28.dp) } },
                        )
                    }
                }
            }
            Step.Unlocks -> {
                stepTitle(R.string.task_unlocks_title, R.string.task_unlocks_body)
                // Starting a saved session: the rest of it, to view, not change.
                if (fromSaved && start != null) item(key = "glance") { app.aurora.ui.session.SessionAtAGlance(start, d) { details = true } }
                fun setUnlocks(next: List<app.aurora.protect.SessionUnlock>) { unlocksJson = app.aurora.data.AuroraJson.encodeToString(next) }
                unlockItems(
                    unlocks, parts.toMap(), d,
                    onToggle = { u -> setUnlocks(unlocks.map { if (it.id == u.id) it.copy(on = !it.on) else it }) },
                    onEdit = { editUnlock(it) },
                ) { k ->
                    val name = when (k) {
                        app.aurora.protect.PassKind.Break -> context.getString(R.string.pass_kind_break)
                        app.aurora.protect.PassKind.Work -> context.getString(R.string.pass_kind_work)
                        app.aurora.protect.PassKind.Other -> ""
                    }
                    editUnlock(app.aurora.protect.SessionUnlock(app.aurora.newId(), name, minutes = if (k == app.aurora.protect.PassKind.Break) 10 else 30, times = if (k == app.aurora.protect.PassKind.Break) 3 else 1, focusRuns = k == app.aurora.protect.PassKind.Work))
                    newPassKind = k.name
                }
            }
            Step.Strictness -> strictnessItems(mode, { askingAdvance = false; askingMode = it }, task, { task = it }, difficulty, { difficulty = it }, phrase, { phrase = it }, wait, { wait = it })
            Step.When -> {
                stepTitle(R.string.cfg_when_title, null)
                item(key = "now") { ChoiceCard(stringResource(R.string.when_now), "", whenKind == When.Now, { whenKind = When.Now }) }
                item(key = "later") { ChoiceCard(stringResource(R.string.when_later), "", whenKind == When.Later, { whenKind = When.Later }) }
                item(key = "recurring") { ChoiceCard(stringResource(R.string.when_recurring), "", whenKind == When.Recurring, { whenKind = When.Recurring }) }
                item(key = "always") {
                    if (strict) {
                        // A strict Shield can't be scheduled as Always: shown, explained, not selectable.
                        Column(Modifier.fillMaxWidth().auroraSurface(colors).padding(Space.M), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(stringResource(R.string.when_always), color = colors.textTertiary, style = AuroraType.Title)
                            Text(stringResource(R.string.when_always_blocked), color = colors.textTertiary, style = AuroraType.Secondary)
                        }
                    } else ChoiceCard(stringResource(R.string.when_always), "", whenKind == When.Always, { whenKind = When.Always })
                }
                if (offerSave) item(key = "save") { ChoiceCard(stringResource(R.string.when_save), stringResource(R.string.when_save_body), whenKind == When.Save, { whenKind = When.Save }) }
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
                val allowance = if (mode.usesQuota && whenKind == When.Now) vm.app.committedMinutesLeft() else null
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
                // Editing a saved session ends here: its Focus tracking and its motivational message.
                if (start != null) stepTitle(R.string.cfg_edit_extras_title, null) else stepTitle(R.string.cfg_focus_title, R.string.cfg_focus_body)
                focusItems()
                messageItem()
            }
            Step.Review -> {
                stepTitle(R.string.cfg_review_title, null)
                // The session as one card, then the two optional extras together, then only what still needs doing.
                val allowance = if (mode.usesQuota) vm.app.committedMinutesLeft() else null
                item(key = "summary") {
                    val allowanceNote = when {
                        allowance == null -> null
                        d.plan == app.aurora.plan.Plan.Free && whenKind == When.Recurring -> stringResource(R.string.committed_schedule_note)
                        else -> stringResource(R.string.start_always_block_allowance, minutesText(allowance))
                    }
                    Summary(d, configName, shield, intent, timing, zone, allowanceNote, mode)
                }
                item(key = "extras") {
                    Column(Modifier.fillMaxWidth().auroraSurface(colors)) {
                        // Made from New session: it can be kept in Saved Sessions as it starts.
                        if (!custom && !fromSaved) {
                            ToggleRow(stringResource(R.string.cfg_keep_saved), stringResource(R.string.cfg_keep_saved_body), save, { save = it })
                            RowDivider()
                        }
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
                if (gate) item(key = "gate") { app.aurora.ui.protect.ShieldGate(hasSites) }
                val overlap = d.rules.copy(shields = d.rules.shields + listOfNotNull(shield), intentPresets = d.rules.intentPresets + listOfNotNull(intent))
                    .overlaps(config, vm.app.runsNow(d.time))
                if (overlap.isNotEmpty()) item(key = "overlap") {
                    Text(stringResource(R.string.cfg_overlap, overlap.joinToString(", ") { d.label(it).ifBlank { it.removePrefix("web:") } }), color = colors.textTertiary, style = AuroraType.Caption)
                }
                // The commitment last: everything to read first, "I'm ready to commit" at the end.
                if (strict && !fromSaved) item(key = "commit") { CommitBlock(committed, { committed = it }, onStrict) }
            }
        }
        if (fromSaved && finish && gate) item(key = "gate") { app.aurora.ui.protect.ShieldGate(hasSites) }
    }
    conflict?.let { c -> app.aurora.ui.session.ConflictDialog(c, d) { conflict = null; problem = null } }
    unlock?.let { u ->
        app.aurora.ui.session.UnlockEditor(
            u, isNew = unlocks.none { it.id == u.id }, parts = parts, d = d,
            initialKind = newPassKind?.let { app.aurora.protect.PassKind.valueOf(it) },
            onChange = ::editUnlock,
            onDone = {
                unlocksJson = app.aurora.data.AuroraJson.encodeToString(if (unlocks.any { it.id == u.id }) unlocks.map { if (it.id == u.id) u else it } else unlocks + u)
                editUnlock(null)
            },
            onDelete = { unlocksJson = app.aurora.data.AuroraJson.encodeToString(unlocks.filterNot { it.id == u.id }); editUnlock(null) },
            onDismiss = { editUnlock(null) },
        )
    }
    clash?.let { (other, keys) ->
        AuroraDialog(
            stringResource(R.string.task_clash_title), { clash = null }, stringResource(R.string.action_ok), { clash = null },
            stringResource(R.string.task_clash_body, androidx.compose.ui.res.pluralStringResource(R.plurals.conflict_items, keys.size, keys.size), other),
        ) { androidx.compose.foundation.layout.Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { app.aurora.ui.protect.IconStack(keys.toList(), d, max = 6, size = 32.dp) } }
    }
    if (details && start != null) app.aurora.ui.home.SessionDetails(start, d) { details = false }
    askingMode?.let { m ->
        app.aurora.ui.library.StrictnessConfirm(m, onConfirm = {
            mode = m; modeConfirmed = true; askingMode = null
            if (askingAdvance) { askingAdvance = false; step = steps[steps.indexOf(Step.Strictness) + 1] }
        }, onDismiss = { askingMode = null; askingAdvance = false })
    }
    if (committing) AuroraDialog(
        configName, { committing = false },
        stringResource(R.string.always_block_confirm), { committing = false; begin() },
        stringResource(R.string.start_commit_body) + "\n\n" + stringResource(R.string.start_commit_limits),
        stringResource(R.string.action_cancel),
    )
}

/**
 * A strict session's one warning: a lock, one line on what stays locked, and "I'm ready to commit" in the same card.
 * Tapping the warning opens what strict protection locks in full.
 */
@Composable
fun CommitBlock(committed: Boolean, onChange: (Boolean) -> Unit, onStrict: (() -> Unit)?) {
    val colors = Aurora.colors
    Column(verticalArrangement = Arrangement.spacedBy(Space.S)) {
        UninstallProtection()
        Column(Modifier.fillMaxWidth().auroraSurface(colors, warm = committed)) {
            Row(
                Modifier.fillMaxWidth()
                    .then(if (onStrict != null) Modifier.clickable(onClickLabel = stringResource(R.string.cfg_strict_link), onClick = onStrict) else Modifier)
                    .padding(horizontal = Space.Inner, vertical = 18.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                androidx.compose.foundation.layout.Box(Modifier.size(38.dp).glassControl(Radius.Pill), contentAlignment = Alignment.Center) {
                    androidx.compose.material3.Icon(AuroraIcons.Lock, null, tint = colors.textPrimary, modifier = Modifier.size(17.dp))
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(stringResource(R.string.commit_title), color = colors.textPrimary, style = AuroraType.BodyStrong)
                    Text(stringResource(R.string.commit_line), color = colors.textTertiary, style = AuroraType.Subtext)
                }
                if (onStrict != null) app.aurora.ui.components.Chevron()
            }
            RowDivider()
            Row(
                Modifier.fillMaxWidth().toggleable(committed, role = Role.Checkbox, onValueChange = onChange).padding(horizontal = Space.Inner, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                SelectionMark(committed)
                Text(stringResource(R.string.always_block_confirm), color = colors.textPrimary, style = AuroraType.BodyStrong)
            }
        }
    }
}

/** What a Focus timer is for, typed in. Optional; shown above the timer while it runs. */
@Composable
internal fun GoalField(goal: String, modifier: Modifier = Modifier, onChange: (String) -> Unit) {
    AuroraTextField(goal, { onChange(it.take(60)) }, stringResource(R.string.cfg_focus_goal_placeholder), modifier, capitalization = KeyboardCapitalization.Sentences)
}

/** Making a new one, first in its list: a quiet glass row with a plus, never a second filled action. */
@Composable
internal fun CreateRow(text: String, onClick: () -> Unit) {
    AuroraButton(text, onClick, Modifier.fillMaxWidth(), kind = ButtonKind.Secondary, icon = AuroraIcons.Plus)
}

@Composable
internal fun EmptyNote(text: String) {
    Text(text, Modifier.padding(horizontal = 4.dp), color = Aurora.colors.textTertiary, style = AuroraType.Secondary)
}

@Composable
internal fun Label(text: String) {
    Eyebrow(text, Modifier.fillMaxWidth().padding(top = Space.S), color = Aurora.colors.textSecondary)
}

/** Today, Tomorrow, or Custom: any later date from the calendar. A custom date shows on its chip. */
@OptIn(ExperimentalLayoutApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
internal fun DateChoice(value: String, onChange: (String) -> Unit) {
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
 * The timing page's answer, said once and large: the length in tabular Inter (numbers big, units small) on a lit
 * card, and one line under it saying when it ends or runs. [warn] sets that line in the danger colour.
 */
@Composable
internal fun TimingAnswer(minutes: Int?, line: String?, warn: Boolean = false) {
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
        Modifier.fillMaxWidth().glass(colors).padding(horizontal = Space.Inner, vertical = 22.dp)
            .semantics(mergeDescendants = true) { },
        verticalArrangement = Arrangement.spacedBy(Space.XS),
    ) {
        Text(text, color = colors.textPrimary, style = AuroraType.Metric)
        line?.let { Text(it, color = if (warn) colors.danger else colors.textSecondary, style = AuroraType.Body) }
    }
}

/** The week as one row of round days, Monday first: tap to add or take a day away. */
@Composable
internal fun DayRow(days: Set<Int>, onChange: (Set<Int>) -> Unit) {
    val colors = Aurora.colors
    val locale = androidx.compose.ui.platform.LocalConfiguration.current.locales[0]
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        (1..7).forEach { day ->
            val on = day in days
            val dow = java.time.DayOfWeek.of(day)
            val full = dow.getDisplayName(java.time.format.TextStyle.FULL, locale)
            androidx.compose.foundation.layout.Box(
                Modifier.weight(1f).heightIn(min = 44.dp).aspectRatio(1f, matchHeightConstraintsFirst = false)
                    .then(if (on) Modifier.clip(CircleShape).background(colors.accent).then(Modifier.glassShine(colors, CircleShape)) else Modifier.glassCard(colors, CircleShape))
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

internal fun dateTime(ms: Long): String =
    Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT))

/** The review card: what will be enforced, when, and the Focus task. */
/** The session at a glance: its name and kind, the faces of what it holds back, and when it runs. */
@Composable
private fun Summary(d: ProtectData, name: String, shield: ShieldPreset?, intent: IntentPreset?, timing: Timing?, zone: ZoneId, note: String?, mode: Mode) {
    val colors = Aurora.colors
    val keys = (shield?.keys.orEmpty() + intent?.keys.orEmpty()).distinct()
    Column(Modifier.fillMaxWidth().auroraSurface(colors).padding(22.dp), verticalArrangement = Arrangement.spacedBy(Space.M)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.S)) {
            Text(name, Modifier.weight(1f), color = colors.textPrimary, style = AuroraType.Headline, maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            ModeChip(mode)
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
        ActivationProblem.Conflict -> R.string.conflict_title
    },
)

private val Int.dp get() = androidx.compose.ui.unit.Dp(this.toFloat())
