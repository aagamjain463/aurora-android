package app.aurora.ui.track

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.aurora.R
import app.aurora.auroraViewModel
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.remember
import app.aurora.newId
import app.aurora.progress.Goal
import app.aurora.progress.GoalKind
import app.aurora.progress.GoalPeriod
import app.aurora.progress.GoalStatus
import app.aurora.protect.activeGoalIds
import app.aurora.setGoalStatus
import app.aurora.ui.components.AuroraDialog
import app.aurora.protect.Rules
import app.aurora.protect.UsageConfig
import app.aurora.protect.intentOf
import app.aurora.protect.shieldOf
import app.aurora.saveGoal
import app.aurora.ui.components.AuroraButton
import app.aurora.ui.components.AuroraTextField
import app.aurora.ui.components.ButtonKind
import app.aurora.ui.components.Chip
import app.aurora.ui.components.ChoiceCard
import app.aurora.ui.components.ChoicePills
import app.aurora.ui.components.DurationPicker
import app.aurora.ui.library.PickRow
import app.aurora.ui.library.StepFlow
import app.aurora.ui.library.appChecklist
import app.aurora.ui.library.stepTitle
import app.aurora.ui.protect.ProtectModel
import app.aurora.ui.theme.Aurora
import app.aurora.ui.theme.AuroraType
import app.aurora.ui.theme.Space

private enum class GoalStep { Name, Link, Session, Track, What, Apps, Limit }
private enum class SessionTrack { Daily, Weekly, PerWeek }

private val DayPresets = listOf(30, 60, 120, 180)
private val WeekPresets = listOf(120, 300, 600, 1200)
private val LimitDayPresets = listOf(15, 30, 45, 60, 120)
private val LimitWeekPresets = listOf(60, 180, 300, 600)
private const val MaxWeekMinutes = 7 * 24 * 60

/**
 * Create a goal: a name, then either a custom session and how to track it, or a limit on apps or the whole
 * screen time. Creating starts and blocks nothing. [createdConfig] is a session just built from here.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun GoalEditorScreen(createdConfig: String?, onBack: () -> Unit, onSaved: () -> Unit, onCreateConfig: () -> Unit, onPlus: () -> Unit = {}, startStep: String? = null, startKind: GoalKind? = null) {
    val vm = auroraViewModel { ProtectModel(it) }
    val data by vm.data.collectAsStateWithLifecycle()
    val apps by vm.apps.collectAsStateWithLifecycle()
    val d = data ?: return
    val colors = Aurora.colors
    val resources = LocalResources.current
    val configs = d.rules.configs.filter { it.custom }.sortedByDescending { it.createdAt }

    var name by rememberSaveable { mutableStateOf("") }
    var kind by rememberSaveable { mutableStateOf(startKind) }
    var configId by rememberSaveable { mutableStateOf<String?>(createdConfig) }
    var track by rememberSaveable { mutableStateOf<SessionTrack?>(null) }
    var perWeek by rememberSaveable { mutableIntStateOf(3) }
    var whole by rememberSaveable { mutableStateOf<Boolean?>(null) }
    var keys by rememberSaveable { mutableStateOf(emptyList<String>()) }
    var query by rememberSaveable { mutableStateOf("") }
    var period by rememberSaveable { mutableStateOf(GoalPeriod.Daily) }
    var minutes by rememberSaveable { mutableStateOf<Int?>(null) }
    var step by rememberSaveable { mutableStateOf(startStep?.let { GoalStep.valueOf(it) } ?: GoalStep.Name) }
    LaunchedEffect(createdConfig) { if (createdConfig != null) configId = createdConfig }
    var blocked by remember { mutableStateOf<Goal?>(null) }
    blocked?.let { goal ->
        GoalLimitDialog(d.rules.activeGoalIds(d.plan).firstOrNull()?.let { id -> d.rules.trackGoals.find { it.id == id } }, onPlus, { blocked = null }) { current, status ->
            vm.run {
                vm.app.setGoalStatus(current.id, status)
                blocked = null
                if (vm.app.saveGoal(goal).allowed) onSaved()
            }
        }
    }

    val steps = buildList {
        add(GoalStep.Name)
        add(GoalStep.Link)
        if (kind == GoalKind.Limit) {
            add(GoalStep.What)
            if (whole != true) add(GoalStep.Apps)
            add(GoalStep.Limit)
        } else {
            add(GoalStep.Session)
            add(GoalStep.Track)
        }
    }
    val index = steps.indexOf(step).coerceAtLeast(0)
    val last = step == steps.last()
    val back = { if (index == 0) onBack() else step = steps[index - 1] }
    val canNext = when (step) {
        GoalStep.Name -> name.isNotBlank()
        GoalStep.Link -> kind != null
        GoalStep.Session -> configs.any { it.id == configId }
        GoalStep.Track -> track == SessionTrack.PerWeek || (track != null && minutes != null)
        GoalStep.What -> whole != null
        GoalStep.Apps -> keys.isNotEmpty()
        GoalStep.Limit -> minutes != null
    }
    StepFlow(
        steps.size, index, back,
        primary = stringResource(if (last) R.string.goal_save else R.string.action_next),
        primaryEnabled = canNext,
        onPrimary = {
            if (!last) step = steps[index + 1]
            else {
                val goal = if (kind == GoalKind.Limit) {
                    Goal(newId(), name.trim(), GoalKind.Limit, period, minutes ?: 0, apps = if (whole == true) emptySet() else keys.toSet(), createdAt = System.currentTimeMillis())
                } else {
                    Goal(
                        newId(), name.trim(), GoalKind.Session,
                        if (track == SessionTrack.Daily) GoalPeriod.Daily else GoalPeriod.Weekly,
                        if (track == SessionTrack.PerWeek) 0 else minutes ?: 0,
                        configId = configId, sessions = perWeek.takeIf { track == SessionTrack.PerWeek }, createdAt = System.currentTimeMillis(),
                    )
                }
                vm.run {
                    if (vm.app.saveGoal(goal).allowed) onSaved() else blocked = goal
                }
            }
        },
        footer = if (step == GoalStep.Limit) ({ Text(stringResource(R.string.goal_limit_note), color = colors.textTertiary, style = AuroraType.Caption) }) else null,
    ) {
        when (step) {
            GoalStep.Name -> {
                stepTitle(R.string.goal_name_title, null)
                item(key = "name") { AuroraTextField(name, { name = it.take(40) }, stringResource(R.string.goal_name_placeholder), capitalization = KeyboardCapitalization.Sentences) }
                item(key = "ideas") {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.XS), verticalArrangement = Arrangement.spacedBy(Space.XS)) {
                        listOf("Daily Study", "Deep Work", "Less Instagram", "Less Screen Time").forEach { Chip(it, name == it) { name = it } }
                    }
                }
            }
            GoalStep.Link -> {
                stepTitle(R.string.goal_link_title, null)
                item(key = "session") { ChoiceCard(stringResource(R.string.goal_link_session), stringResource(R.string.goal_link_session_desc), kind == GoalKind.Session, { kind = GoalKind.Session; minutes = null }) }
                item(key = "limit") { ChoiceCard(stringResource(R.string.goal_link_limit), stringResource(R.string.goal_link_limit_desc), kind == GoalKind.Limit, { kind = GoalKind.Limit; minutes = null }) }
            }
            GoalStep.Session -> {
                stepTitle(R.string.goal_session_title, null)
                if (configs.isEmpty()) item(key = "none") { Text(stringResource(R.string.goal_session_none), color = colors.textSecondary, style = AuroraType.Body) }
                items(configs, key = { "c-" + it.id }) { c -> PickRow(c.name, sessionSummary(d.rules, c), configId == c.id, { configId = c.id }) }
                item(key = "create") { AuroraButton(stringResource(R.string.goal_session_create), onCreateConfig, Modifier.fillMaxWidth().padding(top = Space.S), kind = ButtonKind.Secondary) }
            }
            GoalStep.Track -> {
                stepTitle(R.string.goal_track_title, null)
                item(key = "daily") { ChoiceCard(stringResource(R.string.goal_track_daily), stringResource(R.string.goal_track_daily_desc), track == SessionTrack.Daily, { track = SessionTrack.Daily; minutes = null }) }
                if (track == SessionTrack.Daily) item(key = "daily-pick") { key(track) { DurationPicker(minutes, { minutes = it }, DayPresets, title = stringResource(R.string.goal_time_day)) } }
                item(key = "weekly") { ChoiceCard(stringResource(R.string.goal_track_weekly), stringResource(R.string.goal_track_weekly_desc), track == SessionTrack.Weekly, { track = SessionTrack.Weekly; minutes = null }) }
                if (track == SessionTrack.Weekly) item(key = "weekly-pick") { key(track) { DurationPicker(minutes, { minutes = it }, WeekPresets, title = stringResource(R.string.goal_time_week), max = MaxWeekMinutes) } }
                item(key = "per-week") { ChoiceCard(stringResource(R.string.goal_track_sessions), stringResource(R.string.goal_track_sessions_desc), track == SessionTrack.PerWeek, { track = SessionTrack.PerWeek }) }
                if (track == SessionTrack.PerWeek) item(key = "per-week-pick") {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.XS), verticalArrangement = Arrangement.spacedBy(Space.XS)) {
                        (1..14).forEach { n -> Chip("$n", perWeek == n) { perWeek = n } }
                    }
                }
            }
            GoalStep.What -> {
                stepTitle(R.string.goal_what_title, null)
                item(key = "whole") { ChoiceCard(stringResource(R.string.goal_what_whole), stringResource(R.string.goal_what_whole_desc), whole == true, { whole = true }) }
                item(key = "apps") { ChoiceCard(stringResource(R.string.goal_what_apps), stringResource(R.string.goal_what_apps_desc), whole == false, { whole = false }) }
            }
            GoalStep.Apps -> {
                stepTitle(R.string.shield_apps_title, null)
                appChecklist(apps, emptySet(), keys.toSet(), query, { query = it }) { pkg -> keys = if (pkg in keys) keys - pkg else keys + pkg }
            }
            GoalStep.Limit -> {
                stepTitle(R.string.goal_limit_title, null)
                item(key = "period") {
                    ChoicePills(GoalPeriod.entries, period, { period = it; minutes = null }, label = { resources.getString(if (it == GoalPeriod.Daily) R.string.goal_per_day_pill else R.string.goal_per_week_pill) }, fill = true, segmented = true)
                }
                item(key = "limit-pick") {
                    key(period) {
                        if (period == GoalPeriod.Daily) DurationPicker(minutes, { minutes = it }, LimitDayPresets)
                        else DurationPicker(minutes, { minutes = it }, LimitWeekPresets, max = MaxWeekMinutes)
                    }
                }
            }
        }
    }
}

/**
 * A second active Goal on Free: names the one already active and offers each honest way forward. Cancelling
 * changes nothing. [onFree] pauses, completes or archives the current Goal; its history stays.
 */
@Composable
fun GoalLimitDialog(current: Goal?, onPlus: () -> Unit, onDismiss: () -> Unit, onFree: (Goal, GoalStatus) -> Unit) {
    AuroraDialog(
        stringResource(R.string.goal_plan_title), onDismiss, stringResource(R.string.plus_see), { onDismiss(); onPlus() },
        if (current != null) stringResource(R.string.goal_plan_current, current.name) else stringResource(R.string.goal_plan_body),
        stringResource(R.string.action_cancel),
    ) {
        if (current != null) Column(verticalArrangement = Arrangement.spacedBy(Space.XS)) {
            AuroraButton(stringResource(R.string.goal_pause_current), { onFree(current, GoalStatus.Paused) }, Modifier.fillMaxWidth(), kind = ButtonKind.Secondary, compact = true)
            AuroraButton(stringResource(R.string.goal_complete_current), { onFree(current, GoalStatus.Completed) }, Modifier.fillMaxWidth(), kind = ButtonKind.Secondary, compact = true)
            AuroraButton(stringResource(R.string.goal_archive_current), { onFree(current, GoalStatus.Archived) }, Modifier.fillMaxWidth(), kind = ButtonKind.Quiet, compact = true)
        }
    }
}

@Composable
private fun sessionSummary(rules: Rules, c: UsageConfig): String =
    listOfNotNull(rules.shieldOf(c)?.name, rules.intentOf(c)?.name, c.focus?.task).joinToString(" · ")
