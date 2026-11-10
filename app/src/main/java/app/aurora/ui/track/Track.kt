package app.aurora.ui.track

import app.aurora.ui.components.AuroraIconButton
import app.aurora.ui.components.glassRim
import androidx.compose.ui.unit.em
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Spacer
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.aurora.AuroraApplication
import app.aurora.R
import app.aurora.auroraViewModel
import app.aurora.data.Access
import app.aurora.data.Reclaimed
import app.aurora.data.clockTicks
import app.aurora.data.formatDuration
import app.aurora.data.openUsageAccessSettings
import app.aurora.deleteGoal
import app.aurora.goalUsage
import app.aurora.progress.DayMark
import app.aurora.progress.Goal
import app.aurora.progress.GoalKind
import app.aurora.progress.GoalPeriod
import app.aurora.progress.GoalProgress
import app.aurora.progress.GoalState
import app.aurora.progress.LiveRun
import app.aurora.progress.RunRecord
import app.aurora.progress.Outcome
import app.aurora.progress.SessionStats
import androidx.compose.foundation.layout.FlowRow
import app.aurora.progress.GoalStatus
import app.aurora.progress.goalProgress
import app.aurora.protect.activeGoalIds
import app.aurora.setGoalStatus
import app.aurora.progress.sessionStats
import app.aurora.runsNow
import app.aurora.serviceSeen
import app.aurora.syncRuns
import app.aurora.protect.ShieldService
import app.aurora.ui.components.ArtTier
import app.aurora.ui.components.AuroraButton
import app.aurora.ui.components.AuroraDialog
import app.aurora.ui.components.AuroraIcons
import app.aurora.ui.components.glass
import androidx.compose.foundation.layout.size
import app.aurora.ui.library.MenuItem
import app.aurora.ui.library.DetailsDialog
import app.aurora.ui.library.keyRows
import app.aurora.ui.home.outcomeName
import app.aurora.ui.protect.modeName
import app.aurora.ui.components.Chevron
import app.aurora.ui.components.AuroraRow
import app.aurora.ui.components.RowGroup
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.heightIn
import app.aurora.ui.components.AuroraScreen
import app.aurora.ui.components.ButtonKind
import app.aurora.ui.components.ChoicePills
import app.aurora.ui.components.EmptyState
import app.aurora.ui.components.CountUpText
import app.aurora.ui.components.ProgressLine
import app.aurora.ui.components.glass
import app.aurora.ui.components.SectionLabel
import app.aurora.ui.components.SkeletonRows
import app.aurora.ui.components.auroraSurface
import app.aurora.ui.components.clockTime
import app.aurora.ui.home.AwayState
import app.aurora.ui.home.OutcomeTag
import app.aurora.ui.home.measureReclaimed
import app.aurora.ui.home.protectionToday
import app.aurora.ui.insights.InsightsViewModel
import app.aurora.ui.insights.usageSection
import app.aurora.ui.library.TabHeader
import app.aurora.ui.theme.Aurora
import app.aurora.ui.theme.AuroraType
import app.aurora.ui.theme.Radius
import app.aurora.ui.theme.Space
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import app.aurora.ui.components.Chip

/** Insights opens on where the time goes; Progress and Goals follow. */
enum class TrackTab(val label: Int) { Usage(R.string.track_tab_usage), Progress(R.string.track_tab_progress), Goals(R.string.track_tab_goals) }

private enum class StatPeriod(val label: Int) { Today(R.string.reclaimed_today), Week(R.string.reclaimed_week), Overall(R.string.reclaimed_overall) }

/** A goal as Track shows it: [subject] is its session's or apps' name. */
data class GoalRow(
    val goal: Goal, val subject: String, val progress: GoalProgress, val measuring: Boolean = true,
    /** Session habits: its session's runs since the habit began, and how many succeeded. */
    val sessions: Int = 0, val won: Int = 0,
)

private data class TrackState(
    val loading: Boolean = true,
    /** Something protecting today began before midnight, so today's time can outrun today's sessions. */
    val fromLastNight: Boolean = false,
    val stats: Map<StatPeriod, SessionStats> = emptyMap(),
    /** Null: Time Reclaimed can't be measured right now. */
    val reclaimed: Reclaimed? = null,
    /** Finished sessions kept: Progress only links to them. */
    val sessions: Int = 0,
    val goals: List<GoalRow> = emptyList(),
    /** Completed habits, newest first: they leave the Habits list for History. */
    val completed: List<GoalRow> = emptyList(),
    val usageAccess: Boolean = false,
)

private class TrackModel(val app: AuroraApplication) : ViewModel() {
    private val _state = MutableStateFlow(TrackState())
    val state: StateFlow<TrackState> = _state
    private var measuring: Job? = null

    /** Called on resume and every minute while Track is open; skipped while one is running. */
    fun refresh() {
        if (measuring?.isActive == true) return
        measuring = viewModelScope.launch(Dispatchers.Default) {
            runCatching { app.syncRuns() }
            // The service last reported at some earlier event: a run going now should count up to now.
            if (ShieldService.connected.value) runCatching { app.serviceSeen(connected = true) }
            val reclaimed = (app.measureReclaimed() as? AwayState.Measured)?.reclaimed
            val usage = runCatching { app.goalUsage(includeToday = true) }.getOrDefault(emptyMap())
            val history = app.history.state.filterNotNull().first().value
            val rules = app.rules.state.filterNotNull().first().value
            val t = app.now()
            val zone = ZoneId.systemDefault()
            val today = LocalDate.now(zone)
            fun startOf(date: LocalDate) = date.atStartOfDay(zone).toInstant().toEpochMilli()
            val unlocks = app.runtime.value?.unlocks.orEmpty()
            val live = app.runsNow(t).map { r ->
                // Unlocks that stopped its timer aren't the session's time.
                val stopped = unlocks[r.id].orEmpty().filterNot { it.focusRuns }.map { it.start to minOf(it.end, t.wall) }
                LiveRun(r.configId, r.startAt(t), r.endAt(t), r.shield != null, stopped)
            }
            val tomorrow = startOf(today.plusDays(1))
            _state.value = TrackState(
                loading = false,
                fromLastNight = fromLastNight(history, rules, startOf(today), t.wall, zone),
                stats = mapOf(
                    StatPeriod.Today to startOf(today),
                    StatPeriod.Week to startOf(today.with(DayOfWeek.MONDAY)),
                    StatPeriod.Overall to 0L,
                ).mapValues { (_, from) -> sessionStats(history.runs, live, from, tomorrow, t.wall) },
                reclaimed = reclaimed,
                sessions = history.runs.size,
                goals = rules.activeGoalIds(app.plan).let { active ->
                    rules.trackGoals.filter { it.status != GoalStatus.Completed }
                        .sortedWith(compareByDescending<Goal> { it.id in active }.thenByDescending { it.createdAt }).map { g ->
                            GoalRow(g, subject(g), goalProgress(g, history.runs, live, history.service, usage[g.id].orEmpty(), today, t.wall, zone), g.id in active)
                        }
                },
                completed = rules.trackGoals.filter { it.status == GoalStatus.Completed }.sortedByDescending { completedAt(it) }.map { g ->
                    val runs = if (g.kind == GoalKind.Session) history.runs.filter { it.configId == g.configId && it.start >= g.createdAt && it.start < completedAt(g) } else emptyList()
                    GoalRow(
                        g, subject(g), goalProgress(g, history.runs, live, history.service, usage[g.id].orEmpty(), today, t.wall, zone), false,
                        sessions = runs.size, won = runs.count { it.outcome == Outcome.Succeeded },
                    )
                },
                usageAccess = app.access.isGranted(Access.Usage),
            )
        }
    }

    private fun fromLastNight(history: app.aurora.progress.History, rules: app.aurora.protect.Rules, from: Long, to: Long, zone: ZoneId): Boolean {
        val runtime = app.runtime.value ?: return false
        val list = app.protectedList.value ?: return false
        return protectionToday(history, rules, runtime, list, app.plan, from, to, zone).any { it.start < from && it.keys.isNotEmpty() }
    }

    private suspend fun subject(g: Goal): String = when {
        g.kind == GoalKind.Session -> app.rules.value?.configs?.find { it.id == g.configId }?.name.orEmpty()
        g.apps.isEmpty() -> app.getString(R.string.track_screen_time)
        g.apps.size == 1 -> app.catalog.app(g.apps.single())?.label ?: g.apps.single()
        else -> app.resources.getQuantityString(R.plurals.count_apps, g.apps.size, g.apps.size)
    }

    /** Null when done; otherwise the Goal that holds Free's one active slot. */
    fun setStatus(id: String, status: GoalStatus, replacing: String? = null, replacingStatus: GoalStatus = GoalStatus.Paused, onBlocked: () -> Unit = {}) {
        viewModelScope.launch {
            val done = runCatching { app.setGoalStatus(id, status, replacing, replacingStatus).allowed }.getOrDefault(true)
            if (!done) onBlocked()
            measuring?.join()
            refresh()
        }
    }

    fun delete(id: String) {
        viewModelScope.launch {
            runCatching { app.deleteGoal(id) }
            measuring?.join()
            refresh()
        }
    }
}

/** Track: Progress (time reclaimed, what sessions achieved), Goals, and Usage (screen time). */
@Composable
fun TrackScreen(onPlus: () -> Unit = {}, onOpenSettings: (() -> Unit)? = null, onCreateGoal: () -> Unit = {}, onOpenHistory: () -> Unit = {}, onOpenHabitHistory: () -> Unit = {}, onOpenAppUsage: (app.aurora.ui.insights.Period) -> Unit = {}, start: TrackTab = TrackTab.Usage) {
    val vm = auroraViewModel { TrackModel(it) }
    val usage = auroraViewModel { InsightsViewModel(it) }
    val state by vm.state.collectAsStateWithLifecycle()
    val usageState by usage.state.collectAsStateWithLifecycle()
    val minute by androidx.compose.runtime.remember { clockTicks(60_000) }.collectAsStateWithLifecycle(0L)
    val resources = LocalResources.current
    var tab by rememberSaveable { mutableStateOf(start) }
    var period by rememberSaveable { mutableStateOf(StatPeriod.Today) }
    var plusNote by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<Goal?>(null) }
    var resuming by remember { mutableStateOf<Goal?>(null) }
    resuming?.let { g ->
        val current = state.goals.firstOrNull { it.measuring }?.goal
        GoalLimitDialog(current, onPlus, { resuming = null }) { old, status ->
            resuming = null
            vm.setStatus(g.id, GoalStatus.Active, replacing = old.id, replacingStatus = status)
        }
    }
    LifecycleResumeEffect(minute) {
        vm.refresh()
        onPauseOrDispose { }
    }
    LifecycleResumeEffect(tab) {
        if (tab == TrackTab.Usage) usage.refresh()
        onPauseOrDispose { }
    }
    AuroraScreen(
        title = stringResource(R.string.tab_track),
        tabBar = true,
        art = ArtTier.Balanced,
        header = { TabHeader(stringResource(R.string.track_headline), onOpenSettings) },
    ) {
        item(key = "tabs") {
            // Editorial tabs: words only, a fine rule under the chosen one, a hairline beneath them all.
            Column(Modifier.padding(horizontal = Space.Gutter).padding(bottom = Space.L)) {
                ChoicePills(TrackTab.entries, tab, { tab = it }, label = { resources.getString(it.label) }, quiet = true, quietSize = 17.sp)
                Box(Modifier.fillMaxWidth().height(1.dp).background(Aurora.colors.hairline))
            }
        }
        when (tab) {
            TrackTab.Progress -> if (state.loading) item(key = "loading") { SkeletonRows(5) } else progressSection(state, period, { period = it }, onOpenHistory)
            TrackTab.Goals -> if (state.loading) item(key = "loading") { SkeletonRows(5) } else goalsSection(state, onCreateGoal, onOpenHabitHistory, { deleting = it }) { g, status ->
                vm.setStatus(g.id, status, onBlocked = { resuming = g })
            }
            TrackTab.Usage -> usageSection(usageState, usage, onOpenAppUsage) { plusNote = true }
        }
    }
    deleting?.let { g ->
        AuroraDialog(
            stringResource(R.string.preset_delete_title, g.name), { deleting = null }, stringResource(R.string.preset_delete),
            { deleting = null; vm.delete(g.id) },
            stringResource(R.string.goal_delete_body), stringResource(R.string.action_cancel),
        )
    }
    if (plusNote) AuroraDialog(
        title = stringResource(R.string.insights_30_days_is_part_of_plus),
        body = stringResource(R.string.insights_aurora_free_shows_today_and_the_last_7_days_aurora_plus_adds_a_3),
        confirmLabel = stringResource(R.string.settings_got_it),
        onConfirm = { plusNote = false },
        onDismiss = { plusNote = false },
    ) { AuroraButton(stringResource(R.string.insights_about_aurora_plus), { plusNote = false; onPlus() }, kind = ButtonKind.PlusQuiet) }
}

private fun LazyListScope.progressSection(state: TrackState, period: StatPeriod, onPeriod: (StatPeriod) -> Unit, onOpenHistory: () -> Unit) {
    item(key = "stats") {
        val resources = LocalResources.current
        val colors = Aurora.colors
        val context = LocalContext.current
        val stats = state.stats.getValue(period)
        val reclaimed = state.reclaimed?.let {
            when (period) {
                StatPeriod.Today -> it.todayMs
                StatPeriod.Week -> it.weekMs
                StatPeriod.Overall -> it.overallMs
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(Space.L)) {
            ChoicePills(StatPeriod.entries, period, onPeriod, label = { resources.getString(it.label) }, quiet = true, quietSize = 14.sp, modifier = Modifier.padding(horizontal = Space.Gutter))
            // Built like Home's Time Reclaimed: the one number large, the session counts beneath it.
            Column(Modifier.padding(horizontal = Space.Gutter).fillMaxWidth().auroraSurface(colors).padding(24.dp)) {
                Text(stringResource(R.string.today_metric_label), Modifier.semantics { heading() }, color = colors.textPrimary, style = AuroraType.Title.copy(fontSize = 15.sp))
                when {
                    reclaimed != null -> CountUpText(formatDuration(reclaimed), colors.textPrimary, AuroraType.Metric.copy(fontSize = 40.sp, lineHeight = 46.sp), Modifier.padding(top = 8.dp))
                    // The first time Track needs usage access is when it asks for it.
                    !state.usageAccess -> {
                        Text(stringResource(R.string.today_metric_not_measured), Modifier.padding(top = Space.XS), color = colors.textPrimary, style = AuroraType.Headline)
                        Text(stringResource(R.string.today_metric_no_access), color = colors.textSecondary, style = AuroraType.Secondary)
                        AuroraButton(stringResource(R.string.today_metric_allow), app.aurora.ui.protect.askFor(app.aurora.data.Access.Usage), Modifier.padding(top = Space.S), kind = ButtonKind.Secondary)
                    }
                    else -> {
                        Text("—", color = colors.textPrimary, style = AuroraType.Metric.copy(fontSize = 40.sp, lineHeight = 46.sp))
                        Text(stringResource(R.string.usage_records_unavailable_body), color = colors.textSecondary, style = AuroraType.Secondary)
                    }
                }
                Box(Modifier.padding(top = 14.dp).fillMaxWidth().height(1.dp).background(colors.hairline))
                Row(Modifier.padding(top = 14.dp).fillMaxWidth()) {
                    listOf(R.string.track_stat_sessions to stats.started, R.string.track_stat_succeeded to stats.succeeded).forEach { (label, n) ->
                        Column(Modifier.weight(1f).semantics(mergeDescendants = true) { }) {
                            Text(stringResource(label), color = colors.textTertiary, style = AuroraType.Caption)
                            Text(n.toString(), Modifier.padding(top = 3.dp), color = colors.textPrimary, style = AuroraType.Metric.copy(fontSize = 24.sp, lineHeight = 30.sp, letterSpacing = (-0.02).em))
                        }
                    }
                }
            }
        }
    }
    item(key = "recent") {
        // One way in, no sessions on the page itself: the list and its filters live on their own screen.
        RowGroup(Modifier.padding(top = Space.L)) {
            AuroraRow(
                stringResource(R.string.track_recent_runs),
                subtitle = if (state.sessions == 0) stringResource(R.string.track_no_runs) else pluralStringResource(R.plurals.track_sessions_kept, state.sessions, state.sessions),
                onClick = onOpenHistory,
                trailing = { Chevron() },
            )
        }
    }
}

/** Finished sessions, one quiet line each: the name, and its result on the right. A tap opens the rest. */
@Composable
private fun SessionRows(runs: List<RunRecord>, onOpen: (RunRecord) -> Unit) {
    val colors = Aurora.colors
    runs.forEachIndexed { i, r ->
        if (i > 0) Box(Modifier.padding(horizontal = Space.Inner).fillMaxWidth().height(1.dp).background(colors.hairline))
        Row(
            Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(onClickLabel = stringResource(R.string.preset_view)) { onOpen(r) }.padding(start = 22.dp, end = 16.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Space.S),
        ) {
            Text(r.name, Modifier.weight(1f), color = colors.textPrimary, style = AuroraType.Body, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            OutcomeTag(r.outcome)
            Chevron()
        }
    }
}

/** One finished session in full: when, how long, what protected it, and how it went. */
@Composable
private fun SessionDetailsDialog(r: RunRecord, onDismiss: () -> Unit) {
    val vm = auroraViewModel { app.aurora.ui.protect.ProtectModel(it) }
    val data by vm.data.collectAsStateWithLifecycle()
    val zone = ZoneId.systemDefault()
    val day = java.time.Instant.ofEpochMilli(r.start).atZone(zone).format(java.time.format.DateTimeFormatter.ofPattern("EEEE d MMMM"))
    val result = listOfNotNull(
        outcomeName(r.outcome),
        if (r.turnedOff) stringResource(R.string.detail_ended_early) else null,
        if (r.bypassed) stringResource(R.string.detail_bypassed) else null,
    ).joinToString("\n")
    val rows = listOf(
        stringResource(R.string.detail_when) to "$day\n${clockTime(r.start)} – ${clockTime(r.end)}",
        stringResource(R.string.detail_result) to result,
        stringResource(R.string.reclaimed_label) to formatDuration(r.reclaimedMs),
        stringResource(R.string.detail_held) to formatDuration(r.end - r.start),
    ) + listOfNotNull(r.plannedMs?.let { stringResource(R.string.detail_planned) to formatDuration(it) }) + listOf(
        stringResource(R.string.detail_shield) to listOfNotNull(r.shieldName, r.shieldMode?.let { modeName(it) }).joinToString(" · "),
        stringResource(R.string.detail_intent) to r.intentName.orEmpty(),
        stringResource(R.string.detail_focus) to r.focusTask.orEmpty(),
    ) + (data?.let { keyRows(r.keys, it) } ?: emptyList())
    DetailsDialog(r.name, rows, onDismiss)
}

/** How the session list narrows: by how it went. The day it ran is chosen apart, from a calendar. */
internal enum class SessionFilter(val label: Int, val keeps: (RunRecord) -> Boolean) {
    All(R.string.filter_all, { true }),
    Succeeded(R.string.outcome_succeeded, { it.outcome == Outcome.Succeeded }),
    Missed(R.string.filter_missed, { it.outcome == Outcome.NotAchieved || it.outcome == Outcome.Partial }),
}

/** Every finished session this account has kept, newest first, each with its recorded result. */
@Composable
fun SessionHistoryScreen(onBack: () -> Unit, initialDay: String? = null) {
    val app = LocalContext.current.applicationContext as AuroraApplication
    val resources = LocalResources.current
    val history by app.history.state.collectAsStateWithLifecycle()
    val all = history?.value?.runs.orEmpty().sortedByDescending { it.end }
    var filter by rememberSaveable { mutableStateOf(SessionFilter.All) }
    var day by rememberSaveable { mutableStateOf(initialDay) }
    var picking by remember { mutableStateOf(false) }
    val zone = ZoneId.systemDefault()
    val runs = all.filter(filter.keeps).filter { r -> day == null || java.time.Instant.ofEpochMilli(r.start).atZone(zone).toLocalDate().toString() == day }
    var session by remember { mutableStateOf<RunRecord?>(null) }
    session?.let { SessionDetailsDialog(it) { session = null } }
    if (picking) SessionDayPicker(day, { day = it; picking = false }) { picking = false }
    AuroraScreen(title = stringResource(R.string.history_title), onBack = onBack) {
        if (all.isEmpty()) {
            item(key = "empty") { EmptyState(stringResource(R.string.track_no_runs), "") }
            return@AuroraScreen
        }
        item(key = "filter") {
            // Clear of the top bar, so the pills' rims are never cut off. How it went on the left; the calendar on the right.
            Row(
                Modifier.fillMaxWidth().padding(top = Space.S).padding(horizontal = Space.Gutter),
                horizontalArrangement = Arrangement.spacedBy(Space.XS), verticalAlignment = Alignment.CenterVertically,
            ) {
                ChoicePills(SessionFilter.entries, filter, { filter = it }, label = { resources.getString(it.label) }, modifier = Modifier.weight(1f))
                AuroraIconButton(AuroraIcons.Calendar, stringResource(R.string.filter_date), { picking = true })
            }
        }
        // The chosen day, said once in full on its own line, with the way back to every day beside it.
        day?.let { chosen ->
            item(key = "day") {
                val label = java.time.LocalDate.parse(chosen).format(java.time.format.DateTimeFormatter.ofPattern("EEEE, d MMMM"))
                Row(
                    Modifier.padding(horizontal = Space.Gutter).padding(top = Space.M).fillMaxWidth()
                        .glass(Aurora.colors, Radius.Pill).padding(start = 18.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.S),
                ) {
                    androidx.compose.material3.Icon(AuroraIcons.Calendar, null, tint = Aurora.colors.textSecondary, modifier = Modifier.size(18.dp))
                    Text(label, Modifier.weight(1f), color = Aurora.colors.textPrimary, style = AuroraType.BodyStrong, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                    AuroraIconButton(AuroraIcons.Close, stringResource(R.string.filter_date_clear), { day = null }, Modifier.size(40.dp))
                }
            }
        }
        item(key = "gap") { Spacer(Modifier.height(Space.L)) }
        item(key = "runs") {
            if (runs.isEmpty()) Text(
                stringResource(R.string.filter_none), Modifier.padding(horizontal = Space.Gutter).fillMaxWidth().auroraSurface(Aurora.colors).padding(horizontal = Space.Inner, vertical = 20.dp),
                color = Aurora.colors.textSecondary, style = AuroraType.Secondary,
            )
            else Column(Modifier.padding(horizontal = Space.Gutter).fillMaxWidth().auroraSurface(Aurora.colors)) { SessionRows(runs) { session = it } }
        }
    }
}

private fun LazyListScope.goalsSection(state: TrackState, onCreate: () -> Unit, onHistory: () -> Unit, onDelete: (Goal) -> Unit, onStatus: (Goal, GoalStatus) -> Unit) {
    item(key = "goal-create") {
        Column(Modifier.padding(horizontal = Space.Gutter).padding(bottom = Space.XL), verticalArrangement = Arrangement.spacedBy(Space.S)) {
            AuroraButton(stringResource(R.string.goal_create), onCreate, Modifier.fillMaxWidth(), icon = AuroraIcons.Plus)
            // Completed habits live in History, with their results, ready to resume.
            AuroraButton(stringResource(R.string.habit_history), onHistory, Modifier.fillMaxWidth(), kind = ButtonKind.Secondary, icon = AuroraIcons.Clock)
        }
    }
    if (state.goals.isEmpty()) item(key = "goal-empty") {
        EmptyState(stringResource(R.string.goal_empty_title), stringResource(R.string.goal_empty_body))
    }
    items(state.goals, key = { "goal-" + it.goal.id }) { row -> GoalCard(row, state.usageAccess, { onStatus(row.goal, it) }) { onDelete(row.goal) } }
}

/**
 * One goal, built like Time Reclaimed: name and status on top, the number that matters large, its line, then the
 * record. Pause, Complete, Archive and Delete wait behind one quiet control so the card stays calm.
 */
@Composable
private fun GoalCard(row: GoalRow, usageAccess: Boolean, onStatus: (GoalStatus) -> Unit, onDelete: () -> Unit) {
    val colors = Aurora.colors
    val context = LocalContext.current
    val g = row.goal
    val p = row.progress
    val failed = p.state == GoalState.Failed
    val amount = g.sessions?.let {
        when {
            g.period == GoalPeriod.Weekly -> pluralStringResource(R.plurals.goal_sessions_per_week, it, it)
            g.days.isNotEmpty() -> stringResource(R.string.goal_session_on_days, app.aurora.ui.protect.daysText(g.days))
            else -> stringResource(R.string.goal_session_daily)
        }
    }
        ?: if (g.days.isNotEmpty()) stringResource(R.string.goal_on_days, formatDuration(g.target), app.aurora.ui.protect.daysText(g.days)) else stringResource(if (g.period == GoalPeriod.Daily) R.string.limits_per_day else R.string.goal_per_week, formatDuration(g.target))
    Column(Modifier.padding(horizontal = Space.Gutter).padding(bottom = Space.Card).fillMaxWidth().auroraSurface(colors).padding(start = 22.dp, end = 12.dp, top = 12.dp, bottom = 20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(top = 8.dp)) {
                Text(g.name, Modifier.semantics { heading() }, color = colors.textPrimary, style = AuroraType.Title.copy(fontSize = 15.sp))
                Text(listOf(row.subject, amount).filter { it.isNotEmpty() }.joinToString(" · "), Modifier.padding(top = 2.dp), color = colors.textTertiary, style = AuroraType.Caption)
            }
            val (status, tone) = when {
                !row.measuring -> stringResource(
                    when (g.status) {
                        GoalStatus.Active -> R.string.goal_status_plan
                        GoalStatus.Paused -> R.string.goal_status_paused
                        GoalStatus.Completed -> R.string.goal_status_completed
                        GoalStatus.Archived -> R.string.goal_status_archived
                    },
                ) to colors.textTertiary
                p.state == GoalState.OnTrack -> stringResource(R.string.goal_on_track) to colors.accent
                p.state == GoalState.Achieved -> stringResource(R.string.goal_achieved) to colors.accent
                failed -> stringResource(R.string.goal_failed) to colors.mutedText
                else -> "" to colors.textTertiary
            }
            if (status.isNotEmpty()) Text(status, Modifier.padding(start = Space.S, top = 8.dp), color = tone, style = AuroraType.Caption)
            Box {
                var open by remember { mutableStateOf(false) }
                AuroraIconButton(AuroraIcons.More, stringResource(R.string.preset_more, g.name), { open = true }, Modifier.size(40.dp))
                androidx.compose.material3.DropdownMenu(
                    open, { open = false },
                    shape = Radius.M,
                    containerColor = colors.elevated,
                    border = androidx.compose.foundation.BorderStroke(1.dp, colors.glassRim()),
                ) {
                    if (g.status == GoalStatus.Active) {
                        MenuItem(stringResource(R.string.goal_pause), AuroraIcons.Pause) { open = false; onStatus(GoalStatus.Paused) }
                        MenuItem(stringResource(R.string.goal_complete), AuroraIcons.Check) { open = false; onStatus(GoalStatus.Completed) }
                        MenuItem(stringResource(R.string.goal_archive), AuroraIcons.Layers) { open = false; onStatus(GoalStatus.Archived) }
                    }
                    MenuItem(stringResource(R.string.goal_delete), AuroraIcons.Trash, danger = true) { open = false; onDelete() }
                }
            }
        }
        Column(Modifier.padding(end = 10.dp), verticalArrangement = Arrangement.spacedBy(Space.S)) {
            val value = p.value
            if (value == null) {
                Text(stringResource(if (usageAccess) R.string.usage_records_unavailable_body else R.string.insights_needs_usage_access_to_measure), Modifier.padding(top = Space.XS), color = colors.textSecondary, style = AuroraType.Secondary)
                if (!usageAccess) AuroraButton(stringResource(R.string.today_metric_allow), app.aurora.ui.protect.askFor(app.aurora.data.Access.Usage), kind = ButtonKind.Secondary, compact = true)
            } else {
                val rest = g.target - value
                val left = when {
                    g.sessions != null -> pluralStringResource(R.plurals.goal_sessions_done, g.sessions, value, g.sessions)
                    g.kind == GoalKind.Limit -> stringResource(R.string.goal_used, formatDuration(value))
                    else -> stringResource(R.string.goal_done, formatDuration(value))
                }
                val right = when {
                    g.sessions != null -> if (rest > 0) stringResource(R.string.goal_left, rest.toString()) else null
                    rest > 0 || g.kind == GoalKind.Limit && rest == 0L -> stringResource(R.string.goal_left, formatDuration(rest))
                    g.kind == GoalKind.Limit -> stringResource(R.string.goal_over, formatDuration(-rest))
                    else -> null
                }
                Row(Modifier.padding(top = Space.S), verticalAlignment = Alignment.Bottom) {
                    Text(left, Modifier.weight(1f), color = colors.textPrimary, style = AuroraType.Headline.copy(fontSize = 22.sp, lineHeight = 28.sp))
                    if (right != null) Text(right, Modifier.padding(bottom = 3.dp), color = if (failed) colors.mutedText else colors.textTertiary, style = AuroraType.Caption)
                }
                ProgressLine(value.toFloat() / g.target.coerceAtLeast(1), warm = failed)
            }
            if (g.status != GoalStatus.Active) AuroraButton(stringResource(R.string.goal_resume), { onStatus(GoalStatus.Active) }, Modifier.padding(top = Space.XS).fillMaxWidth(), kind = ButtonKind.Secondary, icon = AuroraIcons.Play)
        }
    }
}

/** When a completed habit was completed: the start of the pause that completing it opened. */
private fun completedAt(g: Goal): Long = g.pauses.lastOrNull { it.to == null }?.from ?: Long.MAX_VALUE

/** Completed habits with their results: what they asked, how often they were met, and Resume. */
@Composable
fun HabitHistoryScreen(onBack: () -> Unit, onPlus: () -> Unit = {}) {
    val vm = auroraViewModel { TrackModel(it) }
    val state by vm.state.collectAsStateWithLifecycle()
    var deleting by remember { mutableStateOf<Goal?>(null) }
    var resuming by remember { mutableStateOf<Goal?>(null) }
    LifecycleResumeEffect(Unit) {
        vm.refresh()
        onPauseOrDispose { }
    }
    resuming?.let { g ->
        GoalLimitDialog(state.goals.firstOrNull { it.measuring }?.goal, onPlus, { resuming = null }) { old, status ->
            resuming = null
            vm.setStatus(g.id, GoalStatus.Active, replacing = old.id, replacingStatus = status)
        }
    }
    AuroraScreen(title = stringResource(R.string.habit_history_title), onBack = onBack) {
        when {
            state.loading -> item(key = "loading") { SkeletonRows(4) }
            state.completed.isEmpty() -> item(key = "empty") {
                EmptyState(stringResource(R.string.habit_history_empty), stringResource(R.string.habit_history_empty_body))
            }
            else -> items(state.completed, key = { "done-" + it.goal.id }) { row ->
                HabitResultCard(row, onResume = { vm.setStatus(row.goal.id, GoalStatus.Active, onBlocked = { resuming = row.goal }) }) { deleting = row.goal }
            }
        }
    }
    deleting?.let { g ->
        AuroraDialog(
            stringResource(R.string.preset_delete_title, g.name), { deleting = null }, stringResource(R.string.preset_delete),
            { deleting = null; vm.delete(g.id) },
            stringResource(R.string.goal_delete_body), stringResource(R.string.action_cancel),
        )
    }
}

/** One completed habit: its name and when it ended, its results as figures, its record, and Resume. */
@Composable
private fun HabitResultCard(row: GoalRow, onResume: () -> Unit, onDelete: () -> Unit) {
    val colors = Aurora.colors
    val g = row.goal
    val p = row.progress
    val zone = ZoneId.systemDefault()
    val ended = completedAt(g).takeIf { it != Long.MAX_VALUE }?.let {
        java.time.Instant.ofEpochMilli(it).atZone(zone).format(java.time.format.DateTimeFormatter.ofPattern("d MMM yyyy"))
    }
    val stats = buildList {
        add(stringResource(R.string.habit_stat_met) to p.met.toString())
        add(stringResource(R.string.habit_stat_missed) to p.failed.toString())
        add(stringResource(R.string.habit_stat_best) to p.best.toString())
    }
    Column(
        Modifier.padding(horizontal = Space.Gutter).padding(bottom = Space.Card).fillMaxWidth().auroraSurface(colors).padding(start = 22.dp, end = 12.dp, top = 12.dp, bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(Space.S),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(top = 8.dp)) {
                Text(g.name, Modifier.semantics { heading() }, color = colors.textPrimary, style = AuroraType.Title.copy(fontSize = 15.sp))
                Text(
                    listOfNotNull(row.subject.ifEmpty { null }, ended?.let { stringResource(R.string.habit_completed_on, it) }).joinToString(" · "),
                    Modifier.padding(top = 2.dp), color = colors.textTertiary, style = AuroraType.Caption,
                )
            }
            AuroraIconButton(AuroraIcons.Trash, stringResource(R.string.goal_delete), onDelete, Modifier.size(40.dp))
        }
        // The results, in light tabular figures: never in the serif.
        FlowRow(Modifier.padding(end = 10.dp, top = Space.XS), horizontalArrangement = Arrangement.spacedBy(Space.L), verticalArrangement = Arrangement.spacedBy(Space.S)) {
            stats.forEach { (label, value) ->
                Column(Modifier.semantics(mergeDescendants = true) { }) {
                    Text(value, color = colors.textPrimary, style = AuroraType.Metric.copy(fontSize = 24.sp, lineHeight = 30.sp, letterSpacing = (-0.02).em))
                    Text(label, color = colors.textTertiary, style = AuroraType.Caption)
                }
            }
        }
        AuroraButton(stringResource(R.string.goal_resume), onResume, Modifier.padding(top = Space.XS, end = 10.dp).fillMaxWidth(), kind = ButtonKind.Secondary, icon = AuroraIcons.Play)
    }
}

/** The calendar for the session list: any day up to today. Clear date shows every day again. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun SessionDayPicker(day: String?, onPick: (String?) -> Unit, onDismiss: () -> Unit) {
    val utc = java.time.ZoneOffset.UTC
    val today = java.time.LocalDate.now()
    val state = androidx.compose.material3.rememberDatePickerState(
        initialSelectedDateMillis = (day?.let(java.time.LocalDate::parse) ?: today).atStartOfDay(utc).toInstant().toEpochMilli(),
        selectableDates = object : androidx.compose.material3.SelectableDates {
            override fun isSelectableDate(utcTimeMillis: Long) = !java.time.Instant.ofEpochMilli(utcTimeMillis).atZone(utc).toLocalDate().isAfter(today)
            override fun isSelectableYear(year: Int) = year <= today.year
        },
    )
    val colors = androidx.compose.material3.DatePickerDefaults.colors(containerColor = Aurora.colors.elevated)
    androidx.compose.material3.DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            app.aurora.ui.components.AuroraButton(stringResource(R.string.action_ok), {
                onPick(state.selectedDateMillis?.let { java.time.Instant.ofEpochMilli(it).atZone(utc).toLocalDate().toString() })
            }, kind = app.aurora.ui.components.ButtonKind.Quiet, compact = true)
        },
        dismissButton = {
            app.aurora.ui.components.AuroraButton(stringResource(R.string.filter_date_clear), { onPick(null) }, kind = app.aurora.ui.components.ButtonKind.Quiet, compact = true)
        },
        colors = colors,
    ) { androidx.compose.material3.DatePicker(state, showModeToggle = false, colors = colors) }
}
