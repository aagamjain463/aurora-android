package app.aurora.ui.track

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
import androidx.compose.ui.graphics.Brush
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
import app.aurora.progress.Level
import app.aurora.progress.LiveRun
import app.aurora.progress.RecordDays
import app.aurora.progress.RecordWeeks
import app.aurora.progress.RunRecord
import app.aurora.progress.SessionStats
import app.aurora.progress.goalProgress
import app.aurora.progress.levelFor
import app.aurora.progress.sessionStats
import app.aurora.progress.totalXp
import app.aurora.progress.xp
import app.aurora.runsNow
import app.aurora.serviceSeen
import app.aurora.syncRuns
import app.aurora.protect.ShieldService
import app.aurora.ui.components.ArtTier
import app.aurora.ui.components.AuroraButton
import app.aurora.ui.components.AuroraDialog
import app.aurora.ui.components.AuroraIcons
import app.aurora.ui.components.AuroraScreen
import app.aurora.ui.components.ButtonKind
import app.aurora.ui.components.ChoicePills
import app.aurora.ui.components.EmptyState
import app.aurora.ui.components.ProgressLine
import app.aurora.ui.components.SectionLabel
import app.aurora.ui.components.SkeletonRows
import app.aurora.ui.components.auroraSurface
import app.aurora.ui.components.clockTime
import app.aurora.ui.home.AwayState
import app.aurora.ui.home.OutcomeTag
import app.aurora.ui.home.measureReclaimed
import app.aurora.ui.insights.InsightsViewModel
import app.aurora.ui.insights.usageSection
import app.aurora.ui.library.TabHeader
import app.aurora.ui.theme.Aurora
import app.aurora.ui.theme.AuroraType
import app.aurora.ui.theme.Radius
import app.aurora.ui.theme.Space
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId

enum class TrackTab(val label: Int) { Progress(R.string.track_tab_progress), Goals(R.string.track_tab_goals), Usage(R.string.track_tab_usage) }

private enum class StatPeriod(val label: Int) { Today(R.string.reclaimed_today), Week(R.string.reclaimed_week), Overall(R.string.reclaimed_overall) }

/** A goal as Track shows it: [subject] is its session's or apps' name. */
data class GoalRow(val goal: Goal, val subject: String, val progress: GoalProgress)

private data class TrackState(
    val loading: Boolean = true,
    val level: Level = levelFor(0),
    val xpToday: Int = 0,
    val stats: Map<StatPeriod, SessionStats> = emptyMap(),
    /** Null: Time Reclaimed can't be measured right now. */
    val reclaimed: Reclaimed? = null,
    val recent: List<RunRecord> = emptyList(),
    val goals: List<GoalRow> = emptyList(),
    val usageAccess: Boolean = false,
)

private class TrackModel(val app: AuroraApplication) : ViewModel() {
    private val _state = MutableStateFlow(TrackState())
    val state: StateFlow<TrackState> = _state
    private var measuring: Job? = null

    /** Called on resume and every minute while Track is open; skipped while one is running. */
    fun refresh() {
        if (measuring?.isActive == true) return
        measuring = viewModelScope.launch {
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
            val live = app.runsNow(t).map { LiveRun(it.configId, it.startAt(t), it.endAt(t), it.shield != null) }
            val tomorrow = startOf(today.plusDays(1))
            _state.value = TrackState(
                loading = false,
                level = levelFor(history.totalXp()),
                xpToday = history.day(today).xp(),
                stats = mapOf(
                    StatPeriod.Today to startOf(today),
                    StatPeriod.Week to startOf(today.with(DayOfWeek.MONDAY)),
                    StatPeriod.Overall to 0L,
                ).mapValues { (_, from) -> sessionStats(history.runs, live, from, tomorrow, t.wall) },
                reclaimed = reclaimed,
                recent = history.runs.takeLast(3).reversed(),
                goals = rules.trackGoals.sortedByDescending { it.createdAt }.map { g ->
                    GoalRow(g, subject(g), goalProgress(g, history.runs, live, history.service, usage[g.id].orEmpty(), today, t.wall, zone))
                },
                usageAccess = app.access.isGranted(Access.Usage),
            )
        }
    }

    private suspend fun subject(g: Goal): String = when {
        g.kind == GoalKind.Session -> app.rules.value?.configs?.find { it.id == g.configId }?.name.orEmpty()
        g.apps.isEmpty() -> app.getString(R.string.track_screen_time)
        g.apps.size == 1 -> app.catalog.app(g.apps.single())?.label ?: g.apps.single()
        else -> app.resources.getQuantityString(R.plurals.count_apps, g.apps.size, g.apps.size)
    }

    fun delete(id: String) {
        viewModelScope.launch {
            runCatching { app.deleteGoal(id) }
            measuring?.join()
            refresh()
        }
    }
}

/** Track: Progress (XP, what sessions achieved), Goals, and Usage (screen time). */
@Composable
fun TrackScreen(onPlus: () -> Unit = {}, onOpenSettings: (() -> Unit)? = null, onCreateGoal: () -> Unit = {}, start: TrackTab = TrackTab.Progress) {
    val vm = auroraViewModel { TrackModel(it) }
    val usage = auroraViewModel { InsightsViewModel(it) }
    val state by vm.state.collectAsStateWithLifecycle()
    val usageState by usage.state.collectAsStateWithLifecycle()
    val minute by clockTicks(60_000).collectAsStateWithLifecycle(0L)
    val resources = LocalResources.current
    var tab by rememberSaveable { mutableStateOf(start) }
    var period by rememberSaveable { mutableStateOf(StatPeriod.Today) }
    var plusNote by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<Goal?>(null) }
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
            ChoicePills(
                TrackTab.entries, tab, { tab = it }, label = { resources.getString(it.label) }, fill = true, segmented = true,
                modifier = Modifier.padding(horizontal = Space.M).padding(bottom = Space.S),
            )
        }
        when (tab) {
            TrackTab.Progress -> if (state.loading) item(key = "loading") { SkeletonRows(5) } else progressSection(state, period, { period = it })
            TrackTab.Goals -> if (state.loading) item(key = "loading") { SkeletonRows(5) } else goalsSection(state, onCreateGoal) { deleting = it }
            TrackTab.Usage -> usageSection(usageState, usage) { plusNote = true }
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
    ) { AuroraButton(stringResource(R.string.insights_about_aurora_plus), { plusNote = false; onPlus() }, kind = ButtonKind.Quiet) }
}

private fun LazyListScope.progressSection(state: TrackState, period: StatPeriod, onPeriod: (StatPeriod) -> Unit) {
    item(key = "xp") { XpCard(state.level, state.xpToday) }
    item(key = "stats") {
        val resources = LocalResources.current
        val stats = state.stats.getValue(period)
        val reclaimed = state.reclaimed?.let {
            when (period) {
                StatPeriod.Today -> it.todayMs
                StatPeriod.Week -> it.weekMs
                StatPeriod.Overall -> it.overallMs
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(Space.S)) {
            ChoicePills(StatPeriod.entries, period, onPeriod, label = { resources.getString(it.label) }, fill = true, segmented = true, modifier = Modifier.padding(horizontal = Space.M).padding(top = Space.M))
            Column(Modifier.padding(horizontal = Space.M).fillMaxWidth().auroraSurface(Aurora.colors).padding(Space.L), verticalArrangement = Arrangement.spacedBy(Space.S)) {
                StatRow(stringResource(R.string.track_stat_sessions), stats.started.toString())
                StatRow(stringResource(R.string.track_stat_succeeded), stats.succeeded.toString())
                StatRow(stringResource(R.string.track_stat_time_set), formatDuration(stats.setMs))
                StatRow(stringResource(R.string.today_metric_label), reclaimed?.let(::formatDuration) ?: "—")
            }
        }
    }
    item(key = "recent") { RecentSessions(state.recent) }
}

@Composable
private fun XpCard(level: Level, xpToday: Int) {
    val colors = Aurora.colors
    Column(Modifier.padding(horizontal = Space.M).fillMaxWidth().auroraSurface(colors, warm = true).padding(Space.L), verticalArrangement = Arrangement.spacedBy(Space.S)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(level.name, Modifier.weight(1f).semantics { heading() }, color = colors.textPrimary, style = AuroraType.Title)
            Text(stringResource(R.string.track_level, level.number), color = colors.rose, style = AuroraType.Label)
        }
        ProgressLine(level.progress, warm = true)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.track_xp, level.intoLevel, level.levelSize), Modifier.weight(1f), color = colors.textSecondary, style = AuroraType.Caption)
            Text(stringResource(R.string.insights_xp_today, xpToday), color = colors.textSecondary, style = AuroraType.Caption)
        }
    }
}

@Composable
private fun StatRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().semantics(mergeDescendants = true) { }, verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), color = Aurora.colors.textSecondary, style = AuroraType.Body)
        Text(value, color = Aurora.colors.textPrimary, style = AuroraType.Headline)
    }
}

@Composable
private fun RecentSessions(recent: List<RunRecord>) {
    val colors = Aurora.colors
    SectionLabel(stringResource(R.string.track_recent_runs))
    Column(Modifier.padding(horizontal = Space.M).fillMaxWidth().auroraSurface(colors).padding(Space.L), verticalArrangement = Arrangement.spacedBy(Space.M)) {
        if (recent.isEmpty()) Text(stringResource(R.string.track_no_runs), color = colors.textSecondary, style = AuroraType.Secondary)
        recent.forEach { r ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.S)) {
                Column(Modifier.weight(1f)) {
                    Text(r.name, color = colors.textPrimary, style = AuroraType.Body)
                    Text(clockTime(r.start) + " · " + formatDuration(r.end - r.start), color = colors.textTertiary, style = AuroraType.Caption)
                }
                OutcomeTag(r.outcome)
            }
        }
    }
}

private fun LazyListScope.goalsSection(state: TrackState, onCreate: () -> Unit, onDelete: (Goal) -> Unit) {
    item(key = "goal-create") {
        AuroraButton(stringResource(R.string.goal_create), onCreate, Modifier.padding(horizontal = Space.M).padding(bottom = Space.XS).fillMaxWidth(), icon = AuroraIcons.Plus)
    }
    if (state.goals.isEmpty()) item(key = "goal-empty") {
        EmptyState(stringResource(R.string.goal_empty_title), stringResource(R.string.goal_empty_body), icon = AuroraIcons.Target)
    }
    items(state.goals, key = { "goal-" + it.goal.id }) { row -> GoalCard(row, state.usageAccess) { onDelete(row.goal) } }
}

@Composable
private fun GoalCard(row: GoalRow, usageAccess: Boolean, onDelete: () -> Unit) {
    val colors = Aurora.colors
    val context = LocalContext.current
    val g = row.goal
    val p = row.progress
    val failed = p.state == GoalState.Failed
    val amount = g.sessions?.let { pluralStringResource(R.plurals.goal_sessions_per_week, it, it) }
        ?: stringResource(if (g.period == GoalPeriod.Daily) R.string.limits_per_day else R.string.goal_per_week, formatDuration(g.target))
    Column(
        Modifier.padding(horizontal = Space.M).padding(bottom = Space.S).fillMaxWidth().auroraSurface(colors, warm = failed).padding(Space.L),
        verticalArrangement = Arrangement.spacedBy(Space.S),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.S)) {
            Text(g.name, Modifier.weight(1f), color = colors.textPrimary, style = AuroraType.Title)
            when (p.state) {
                GoalState.OnTrack -> Text(stringResource(R.string.goal_on_track), color = colors.accent, style = AuroraType.Label)
                GoalState.Achieved -> Text(stringResource(R.string.goal_achieved), color = colors.accent, style = AuroraType.Label)
                GoalState.Failed -> Text(stringResource(R.string.goal_failed), color = colors.violetText, style = AuroraType.Label)
                GoalState.Unknown -> Unit
            }
        }
        Text(listOf(row.subject, amount).filter { it.isNotEmpty() }.joinToString(" · "), color = colors.textSecondary, style = AuroraType.Secondary)
        val value = p.value
        if (value == null) {
            Text(stringResource(if (usageAccess) R.string.usage_records_unavailable_body else R.string.insights_needs_usage_access_to_measure), color = colors.textTertiary, style = AuroraType.Caption)
            if (!usageAccess) AuroraButton(stringResource(R.string.today_metric_allow), { openUsageAccessSettings(context) }, kind = ButtonKind.Secondary, compact = true)
        } else {
            ProgressLine(value.toFloat() / g.target.coerceAtLeast(1), warm = failed)
            Row(verticalAlignment = Alignment.CenterVertically) {
                val rest = g.target - value
                val left = when {
                    g.sessions != null -> stringResource(R.string.goal_sessions_done, value, g.sessions)
                    g.kind == GoalKind.Limit -> stringResource(R.string.goal_used, formatDuration(value))
                    else -> stringResource(R.string.goal_done, formatDuration(value))
                }
                val right = when {
                    g.sessions != null -> if (rest > 0) stringResource(R.string.goal_left, rest.toString()) else null
                    rest > 0 || g.kind == GoalKind.Limit && rest == 0L -> stringResource(R.string.goal_left, formatDuration(rest))
                    g.kind == GoalKind.Limit -> stringResource(R.string.goal_over, formatDuration(-rest))
                    else -> null
                }
                Text(left, Modifier.weight(1f), color = colors.textPrimary, style = AuroraType.BodyStrong)
                if (right != null) Text(right, color = if (failed) colors.violetText else colors.textSecondary, style = AuroraType.Secondary)
            }
        }
        val summary = stringResource(R.string.goal_record_line, p.met, p.failed, p.streak)
        Column(Modifier.padding(top = Space.XS).semantics(mergeDescendants = true) { contentDescription = summary }, verticalArrangement = Arrangement.spacedBy(Space.XS)) {
            Text(
                if (g.period == GoalPeriod.Daily) stringResource(R.string.goal_record_days, RecordDays) else stringResource(R.string.goal_record_weeks, RecordWeeks),
                color = colors.textTertiary, style = AuroraType.Caption,
            )
            DayStrip(p.record.map { it.second })
            Text(summary, color = colors.textSecondary, style = AuroraType.Caption)
        }
        AuroraButton(stringResource(R.string.goal_delete), onDelete, Modifier.fillMaxWidth(), kind = ButtonKind.Quiet, compact = true)
    }
}

/** The track record, as points of light: lit for success, a violet ring for failure, faint where nothing was judged. */
@Composable
private fun DayStrip(marks: List<DayMark>, modifier: Modifier = Modifier, dot: Dp = 14.dp) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp)) { marks.forEach { DayDot(it, dot) } }
}

@Composable
private fun DayDot(m: DayMark, size: Dp) {
    val colors = Aurora.colors
    val base = Modifier.size(size).clip(Radius.Pill)
    when (m) {
        DayMark.Succeeded -> Box(base.background(Brush.radialGradient(listOf(colors.accent, colors.accent.copy(alpha = 0.6f)))))
        DayMark.Missed -> Box(base.border(1.5.dp, colors.violet.copy(alpha = 0.7f), Radius.Pill))
        DayMark.Pending -> Box(base.border(1.5.dp, colors.accent.copy(alpha = 0.7f), Radius.Pill))
        DayMark.Rest -> Box(base.background(colors.hairline))
        DayMark.Before -> Box(base.background(colors.hairline.copy(alpha = 0.35f)))
    }
}
