package app.aurora.ui.insights

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.aurora.R
import app.aurora.AuroraApplication
import app.aurora.auroraViewModel
import app.aurora.data.Access
import app.aurora.data.formatDuration
import app.aurora.data.openUsageAccessSettings
import app.aurora.plan.Feature
import app.aurora.plan.Plan
import app.aurora.plan.has
import app.aurora.progress.DayStats
import app.aurora.progress.History
import app.aurora.progress.levelFor
import app.aurora.progress.light
import app.aurora.progress.on
import app.aurora.progress.steadyDays
import app.aurora.progress.title
import app.aurora.progress.totalXp
import app.aurora.progress.xp
import app.aurora.protect.Goal
import app.aurora.protect.GoalKind
import app.aurora.protect.Rules
import app.aurora.protect.key
import app.aurora.ui.components.AuroraButton
import app.aurora.ui.components.AuroraDialog
import app.aurora.ui.components.AuroraIcons
import app.aurora.ui.components.AuroraLight
import app.aurora.ui.components.AuroraScreen
import app.aurora.ui.components.ButtonKind
import app.aurora.ui.components.ChoicePills
import app.aurora.ui.components.EmptyState
import app.aurora.ui.components.MetricGrid
import app.aurora.ui.components.ProgressLine
import app.aurora.ui.components.RadioRow
import app.aurora.ui.components.RowDivider
import app.aurora.ui.components.RowGroup
import app.aurora.ui.components.SectionLabel
import app.aurora.ui.components.SkeletonRows
import app.aurora.ui.theme.Aurora
import app.aurora.ui.theme.AuroraType
import app.aurora.ui.theme.Radius
import app.aurora.ui.theme.Space
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.UUID

data class UsageRow(val packageName: String, val label: String, val icon: ImageBitmap?, val ms: Long, val onList: Boolean)

/** One day as Android measured it. Null totals: no records for that day (or no usage access). */
data class DayUsage(val date: LocalDate, val total: Long?, val protected: Long?)

enum class Period(val days: Int, val label: Int) { Today(1, R.string.tab_today), Week(7, R.string.plus_period_week), Month(30, R.string.plus_period_month) }

private const val OtherApps = "other"

data class InsightsState(
    val access: Boolean,
    val loading: Boolean = true,
    val period: Period = Period.Today,
    val rows: List<UsageRow> = emptyList(),
    val days: List<DayUsage> = emptyList(),
    val history: History = History(),
    val rules: Rules = Rules(),
    val plan: Plan = Plan.Free,
)

class InsightsViewModel(private val app: AuroraApplication) : ViewModel() {
    private val _state = MutableStateFlow(InsightsState(access = app.access.isGranted(Access.Usage)))
    val state: StateFlow<InsightsState> = _state

    fun select(period: Period) {
        _state.value = _state.value.copy(period = period)
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            val period = _state.value.period
            val access = app.access.isGranted(Access.Usage)
            val history = app.history.state.filterNotNull().first().value
            val rules = app.rules.state.filterNotNull().first().value
            val protectedKeys = app.protectedList.filterNotNull().first().active(app.plan).map { it.key }.toSet()
            val today = LocalDate.now()
            val days = (period.days - 1 downTo 0).map { back ->
                val date = today.minusDays(back.toLong())
                val usage = if (access) app.screenTime.day(date) else null
                DayUsage(date, usage?.values?.sum(), usage?.filterKeys { it in protectedKeys }?.values?.sum())
            }
            val rows = if (!access || period != Period.Today) emptyList() else appRows(app.screenTime.today(), protectedKeys)
            _state.value = InsightsState(access, loading = false, period = period, rows = rows, days = days, history = history, rules = rules, plan = app.plan)
        }
    }

    private suspend fun appRows(usage: Map<String, Long>, protectedKeys: Set<String>): List<UsageRow> {
        // Apps aurora can't see (system components, uninstalled apps) are folded into one honest row.
        var other = 0L
        val named = usage.mapNotNull { (pkg, ms) ->
            val installed = app.catalog.app(pkg)
            if (installed == null) other += ms
            installed?.let { UsageRow(pkg, it.label, it.icon, ms, pkg in protectedKeys) }
        }
        return (named + UsageRow(OtherApps, app.getString(R.string.insights_other_apps), null, other, false))
            .filter { it.ms >= 60_000 }
            .sortedByDescending { it.ms }
            .take(8)
    }

    fun addGoal(goal: Goal) {
        viewModelScope.launch { runCatching { app.rules.update { it.copy(goals = it.goals + goal) } }; refresh() }
    }

    fun removeGoal(goal: Goal) {
        viewModelScope.launch { runCatching { app.rules.update { r -> r.copy(goals = r.goals.filterNot { it.id == goal.id }) } }; refresh() }
    }
}

/**
 * Where time goes, and what changed. Three kinds of number, always labelled: measured by Android (screen
 * time), counted by aurora (Shields, choices, Focus), and unavailable (shown as "—", never as zero).
 */
@Composable
fun InsightsScreen(onPlus: () -> Unit = {}) {
    val vm = auroraViewModel { InsightsViewModel(it) }
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val resources = LocalResources.current
    val colors = Aurora.colors
    var addingGoal by remember { mutableStateOf(false) }
    var plusNote by remember { mutableStateOf(false) }
    LifecycleResumeEffect(Unit) {
        vm.refresh()
        onPauseOrDispose { }
    }

    AuroraScreen(title = stringResource(R.string.tab_insights), subtitle = stringResource(R.string.insights_measured_on_this_phone_nothing_leaves_it), tabBar = true) {
        item(key = "period") {
            ChoicePills(
                Period.entries, state.period,
                { p -> if (p == Period.Month && !has(state.plan, Feature.AdvancedInsights)) plusNote = true else vm.select(p) },
                label = { resources.getString(it.label) }, fill = true,
                modifier = Modifier.padding(horizontal = Space.M).padding(top = Space.M),
            )
        }
        if (state.loading) {
            item(key = "loading") { SkeletonRows(5) }
            return@AuroraScreen
        }
        val stats = state.days.map { state.history.day(it.date) }
        if (!state.access) item(key = "permission") {
            EmptyState(
                icon = AuroraIcons.Insights,
                title = stringResource(R.string.insights_see_where_your_time_goes),
                body = stringResource(R.string.insights_with_usage_access_aurora_can_show_how_long_you_spend_in_each_app),
                action = { AuroraButton(stringResource(R.string.today_metric_allow), { openUsageAccessSettings(context) }) },
            )
        } else item(key = "total") {
            val known = state.days.mapNotNull { it.total }
            Column(Modifier.padding(horizontal = Space.Gutter).padding(top = Space.L)) {
                if (state.period == Period.Today) {
                    Text(if (known.isEmpty()) "—" else formatDuration(known.sum()), color = colors.textPrimary, style = AuroraType.Hero)
                    Text(stringResource(R.string.insights_screen_time_since_midnight), color = colors.textSecondary, style = AuroraType.Secondary)
                } else {
                    Text(if (known.isEmpty()) "—" else formatDuration(known.sum() / known.size), color = colors.textPrimary, style = AuroraType.Hero)
                    Text(stringResource(R.string.insights_average_days , known.size, state.days.size), color = colors.textSecondary, style = AuroraType.Secondary)
                    Spacer(Modifier.height(Space.L))
                    DayBars(state.days)
                }
            }
        }

        if (state.access && state.period == Period.Today) {
            if (state.days.none { it.total != null }) item(key = "unavailable") {
                EmptyState(icon = AuroraIcons.Insights, title = stringResource(R.string.usage_records_unavailable_title), body = stringResource(R.string.usage_records_unavailable_body))
            } else if (state.rows.isEmpty()) item(key = "quiet") {
                EmptyState(icon = AuroraIcons.Today, title = stringResource(R.string.insights_a_quiet_day_so_far), body = stringResource(R.string.insights_there_s_no_screen_time_to_show_since_midnight_yet))
            } else {
                item(key = "label") { SectionLabel(stringResource(R.string.insights_most_used)) }
                val max = state.rows.maxOfOrNull { it.ms } ?: 1L
                items(state.rows, key = { it.packageName }) { row -> UsageBar(row, row.ms.toFloat() / max) }
            }
        }

        item(key = "aurora") {
            SectionLabel(stringResource(R.string.insights_counted_by_aurora))
            MetricGrid(
                listOf(
                    "${stats.sumOf { it.focusMinutes }.let { if (it == 0) "0m" else formatDuration(it * 60_000L) }}" to stringResource(R.string.insights_metric_focus),
                    "${stats.sumOf { it.shields }}" to stringResource(R.string.insights_metric_shields),
                    "${stats.sumOf { it.wentBack }}" to stringResource(R.string.insights_metric_back),
                    "${stats.sumOf { it.grantedMinutes }}m" to stringResource(R.string.insights_metric_chosen),
                ),
            )
            val continued = stats.sumOf { it.continued }
            val decided = continued + stats.sumOf { it.wentBack }
            if (decided > 0) Text(
                pluralStringResource(R.plurals.insights_choices_body, decided, stats.sumOf { it.wentBack }, decided),
                Modifier.padding(horizontal = Space.Gutter).padding(top = Space.S),
                color = colors.textTertiary,
                style = AuroraType.Caption,
            )
        }

        item(key = "goals") {
            SectionLabel(stringResource(R.string.today_goals))
            RowGroup {
                state.rules.goals.forEachIndexed { i, goal ->
                    if (i > 0) RowDivider()
                    GoalRow(goal, state.days, stats) { vm.removeGoal(goal) }
                }
                if (state.rules.goals.isNotEmpty()) RowDivider()
                Box(Modifier.fillMaxWidth().padding(Space.XS), contentAlignment = Alignment.Center) {
                    AuroraButton(stringResource(R.string.insights_add_a_goal), { addingGoal = true }, kind = ButtonKind.Quiet, icon = AuroraIcons.Target)
                }
            }
        }

        item(key = "progress") {
            val today = LocalDate.now()
            val light = state.history.light(today)
            val level = levelFor(state.history.totalXp())
            SectionLabel(stringResource(R.string.insights_progress))
            RowGroup {
                AuroraLight(light.glow, light.rise, Modifier.fillMaxWidth().height(120.dp))
                Column(Modifier.padding(Space.M), verticalArrangement = Arrangement.spacedBy(Space.XS)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.insights_level , level.name, level.number), Modifier.weight(1f), color = colors.textPrimary, style = AuroraType.BodyStrong)
                        Text(stringResource(R.string.insights_xp_today , state.history.day(today).xp()), color = colors.textSecondary, style = AuroraType.Caption)
                    }
                    ProgressLine(level.progress)
                    Text(
                        stringResource(R.string.insights_progress_detail , level.intoLevel, level.levelSize, state.history.steadyDays(today), light.activeDays),
                        color = colors.textSecondary,
                        style = AuroraType.Caption,
                    )
                }
            }
            Text(
                stringResource(R.string.insights_xp_comes_only_from_completed_focus_stepping_back_at_a_shield_or) +
                    stringResource(R.string.insights_aurora_can_t_see_whether_you_were_truly_focused_so_leaving_a_tim),
                Modifier.padding(horizontal = Space.Gutter).padding(top = Space.S),
                color = colors.textTertiary,
                style = AuroraType.Caption,
            )
        }

        item(key = "footnote") {
            Text(
                stringResource(R.string.insights_screen_time_is_calculated_on_this_phone_from_android_s_usage_rec) +
                    stringResource(R.string.insights_days_without_records_show_totals_can_differ_slightly_from_digita),
                Modifier.padding(horizontal = Space.Gutter).padding(top = Space.L),
                color = colors.textTertiary,
                style = AuroraType.Caption,
            )
        }
    }

    if (addingGoal) GoalDialog({ addingGoal = false }) { vm.addGoal(it); addingGoal = false }
    if (plusNote) AuroraDialog(
        title = stringResource(R.string.insights_30_days_is_part_of_plus),
        body = stringResource(R.string.insights_aurora_free_shows_today_and_the_last_7_days_aurora_plus_adds_a_3),
        confirmLabel = stringResource(R.string.settings_got_it),
        onConfirm = { plusNote = false },
        onDismiss = { plusNote = false },
    ) { AuroraButton(stringResource(R.string.insights_about_aurora_plus), { plusNote = false; onPlus() }, kind = ButtonKind.Quiet) }
}

/** Restrained daily bars. Today is full strength; days without records are a faint dash, never a zero bar. */
@Composable
private fun DayBars(days: List<DayUsage>) {
    val colors = Aurora.colors
    val locale = LocalLocale.current.platformLocale
    val max = days.mapNotNull { it.total }.maxOrNull()?.coerceAtLeast(60_000L) ?: 1L
    val noData = stringResource(R.string.insights_no_data)
    val spoken = days.joinToString(", ") { d -> "${d.date.dayOfWeek.getDisplayName(TextStyle.SHORT, locale)} ${d.total?.let(::formatDuration) ?: noData}" }
    Column(Modifier.semantics(mergeDescendants = true) { contentDescription = "Screen time by day: $spoken" }) {
        Canvas(Modifier.fillMaxWidth().height(120.dp)) {
            val slot = size.width / days.size
            val bar = (slot * 0.56f).coerceAtMost(28.dp.toPx())
            days.forEachIndexed { i, d ->
                val x = slot * i + (slot - bar) / 2
                val total = d.total
                if (total == null) {
                    drawRoundRect(colors.hairline, Offset(x, size.height - 3.dp.toPx()), Size(bar, 3.dp.toPx()), CornerRadius(2.dp.toPx()))
                } else {
                    val h = (size.height * total / max).coerceAtLeast(3.dp.toPx())
                    val last = i == days.lastIndex
                    drawRoundRect(
                        if (last) colors.accent else colors.accent.copy(alpha = 0.35f),
                        Offset(x, size.height - h), Size(bar, h), CornerRadius(bar / 2.5f),
                    )
                }
            }
        }
        if (days.size <= 7) Row(Modifier.fillMaxWidth().padding(top = Space.XS)) {
            days.forEach { d ->
                Text(
                    d.date.dayOfWeek.getDisplayName(TextStyle.NARROW, locale),
                    Modifier.weight(1f),
                    color = colors.textTertiary,
                    style = AuroraType.Caption,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

@Composable
private fun GoalRow(goal: Goal, days: List<DayUsage>, stats: List<DayStats>, onRemove: () -> Unit) {
    val colors = Aurora.colors
    val today = LocalDate.now()
    val results = days.mapIndexed { i, d ->
        goal.on(stats[i], d.total?.let { (it / 60_000).toInt() }, d.protected?.let { (it / 60_000).toInt() }, dayOver = d.date != today)
    }
    val met = results.count { it.met == true }
    val measured = results.count { it.met != null }
    val metText = stringResource(R.string.insights_goal_met, met, measured)
    Column(Modifier.padding(Space.M), verticalArrangement = Arrangement.spacedBy(Space.XS)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(goal.kind.title(goal.target), Modifier.weight(1f), color = colors.textPrimary, style = AuroraType.BodyStrong)
            AuroraButton(stringResource(R.string.insights_remove), onRemove, kind = ButtonKind.Quiet)
        }
        if (days.size > 1) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.semantics(mergeDescendants = true) { contentDescription = metText }) {
                results.forEach { r ->
                    Box(
                        Modifier.size(10.dp).clip(Radius.Pill).background(
                            when (r.met) {
                                true -> colors.accent
                                false -> colors.dawn.copy(alpha = 0.6f)
                                null -> colors.hairline
                            },
                        ),
                    )
                }
            }
            Text(metText, color = colors.textSecondary, style = AuroraType.Caption)
        } else {
            val r = results.single()
            if (r.value == null) Text(stringResource(R.string.insights_needs_usage_access_to_measure), color = colors.textTertiary, style = AuroraType.Caption)
            else {
                ProgressLine(r.value.toFloat() / goal.target.coerceAtLeast(1), warm = r.met == false)
                Text(
                    stringResource(R.string.insights_goal_today , r.value, goal.target, if (goal.kind == GoalKind.OpensUnder) "" else " min"),
                    color = colors.textSecondary,
                    style = AuroraType.Caption,
                )
            }
        }
    }
}

@Composable
private fun GoalDialog(onDismiss: () -> Unit, onAdd: (Goal) -> Unit) {
    var kind by remember { mutableStateOf(GoalKind.FocusAtLeast) }
    val options = when (kind) {
        GoalKind.ScreenTimeUnder -> listOf(120, 180, 240, 300)
        GoalKind.ProtectedTimeUnder -> listOf(15, 30, 60, 90)
        GoalKind.FocusAtLeast -> listOf(25, 50, 90, 120)
        GoalKind.OpensUnder -> listOf(1, 3, 5, 10)
    }
    var target by remember(kind) { mutableStateOf(options[1]) }
    AuroraDialog(
        title = stringResource(R.string.insights_a_goal_for_each_day),
        confirmLabel = stringResource(R.string.insights_add_goal),
        onConfirm = { onAdd(Goal(UUID.randomUUID().toString(), kind, target)) },
        dismissLabel = stringResource(R.string.action_cancel),
        onDismiss = onDismiss,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(Space.S)) {
            Column {
                for (k in GoalKind.entries) {
                    RadioRow(
                        when (k) {
                            GoalKind.ScreenTimeUnder -> stringResource(R.string.insights_less_screen_time)
                            GoalKind.ProtectedTimeUnder -> stringResource(R.string.insights_less_time_in_protected_apps)
                            GoalKind.FocusAtLeast -> stringResource(R.string.insights_more_focus)
                            GoalKind.OpensUnder -> stringResource(R.string.insights_fewer_unplanned_openings)
                        },
                        subtitle = null,
                        selected = kind == k,
                        onClick = { kind = k },
                    )
                }
            }
            ChoicePills(options, target, { target = it }, label = { if (kind == GoalKind.OpensUnder) "$it" else if (it < 60) "$it m" else "${it / 60}h${if (it % 60 > 0) " ${it % 60}" else ""}" }, fill = true)
            Text(kind.title(target), color = Aurora.colors.textSecondary, style = AuroraType.Secondary)
        }
    }
}

@Composable
private fun UsageBar(row: UsageRow, fraction: Float) {
    val colors = Aurora.colors
    val protectedSuffix = stringResource(R.string.insights_protected_suffix)
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = Space.Gutter, vertical = Space.S)
            .semantics(mergeDescendants = true) {
                contentDescription = "${row.label}, ${formatDuration(row.ms)}" + if (row.onList) protectedSuffix else ""
            },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.M),
    ) {
        if (row.icon != null) {
            Image(row.icon, null, Modifier.size(36.dp))
        } else {
            Box(Modifier.size(36.dp).clip(Radius.S).background(colors.hairline))
        }
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.S)) {
                Text(
                    row.label, Modifier.weight(1f),
                    color = colors.textPrimary, style = AuroraType.Body, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Text(formatDuration(row.ms), color = colors.textSecondary, style = AuroraType.Secondary)
            }
            Spacer(Modifier.height(6.dp))
            ProgressLine(fraction.coerceIn(0.02f, 1f), warm = row.onList)
            if (row.onList) {
                Spacer(Modifier.height(4.dp))
                Text(stringResource(R.string.insights_protected), color = colors.dawnText, style = AuroraType.Caption)
            }
        }
    }
}
