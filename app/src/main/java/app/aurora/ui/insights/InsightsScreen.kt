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

enum class Period(val days: Int, val label: String) { Today(1, "Today"), Week(7, "7 days"), Month(30, "30 days") }

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
        return (named + UsageRow(OtherApps, "Other apps", null, other, false))
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
    val colors = Aurora.colors
    var addingGoal by remember { mutableStateOf(false) }
    var plusNote by remember { mutableStateOf(false) }
    LifecycleResumeEffect(Unit) {
        vm.refresh()
        onPauseOrDispose { }
    }

    AuroraScreen(title = "Insights", subtitle = "Measured on this phone. Nothing leaves it.", tabBar = true) {
        item(key = "period") {
            ChoicePills(
                Period.entries, state.period,
                { p -> if (p == Period.Month && !has(state.plan, Feature.AdvancedInsights)) plusNote = true else vm.select(p) },
                label = { it.label }, fill = true,
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
                title = "See where your time goes",
                body = "With usage access, aurora can show how long you spend in each app. It's calculated on this phone and never leaves it. What aurora counts itself is below either way.",
                action = { AuroraButton("Allow usage access", { openUsageAccessSettings(context) }) },
            )
        } else item(key = "total") {
            val known = state.days.mapNotNull { it.total }
            Column(Modifier.padding(horizontal = Space.Gutter).padding(top = Space.L)) {
                if (state.period == Period.Today) {
                    Text(formatDuration(known.sum()), color = colors.textPrimary, style = AuroraType.Hero)
                    Text("screen time since midnight", color = colors.textSecondary, style = AuroraType.Secondary)
                } else {
                    Text(if (known.isEmpty()) "—" else formatDuration(known.sum() / known.size), color = colors.textPrimary, style = AuroraType.Hero)
                    Text("average a day · ${known.size} of ${state.days.size} days measured", color = colors.textSecondary, style = AuroraType.Secondary)
                    Spacer(Modifier.height(Space.L))
                    DayBars(state.days)
                }
            }
        }

        if (state.access && state.period == Period.Today) {
            if (state.rows.isEmpty()) item(key = "quiet") {
                EmptyState(icon = AuroraIcons.Today, title = "A quiet day so far", body = "There's no screen time to show since midnight yet.")
            } else {
                item(key = "label") { SectionLabel("Most used") }
                val max = state.rows.maxOfOrNull { it.ms } ?: 1L
                items(state.rows, key = { it.packageName }) { row -> UsageBar(row, row.ms.toFloat() / max) }
            }
        }

        item(key = "aurora") {
            SectionLabel("Counted by aurora")
            MetricGrid(
                listOf(
                    "${stats.sumOf { it.focusMinutes }.let { if (it == 0) "0m" else formatDuration(it * 60_000L) }}" to "focus",
                    "${stats.sumOf { it.shields }}" to "times aurora stepped in",
                    "${stats.sumOf { it.wentBack }}" to "times you stepped back",
                    "${stats.sumOf { it.grantedMinutes }}m" to "time you chose in protected apps",
                ),
            )
            val continued = stats.sumOf { it.continued }
            val decided = continued + stats.sumOf { it.wentBack }
            if (decided > 0) Text(
                "When aurora asked, you stepped back ${stats.sumOf { it.wentBack }} of $decided times. Chosen time is what you picked at the Shield; aurora doesn't claim it as time saved.",
                Modifier.padding(horizontal = Space.Gutter).padding(top = Space.S),
                color = colors.textTertiary,
                style = AuroraType.Caption,
            )
        }

        item(key = "goals") {
            SectionLabel("Goals")
            RowGroup {
                state.rules.goals.forEachIndexed { i, goal ->
                    if (i > 0) RowDivider()
                    GoalRow(goal, state.days, stats) { vm.removeGoal(goal) }
                }
                if (state.rules.goals.isNotEmpty()) RowDivider()
                Box(Modifier.fillMaxWidth().padding(Space.XS), contentAlignment = Alignment.Center) {
                    AuroraButton("Add a goal", { addingGoal = true }, kind = ButtonKind.Quiet, icon = AuroraIcons.Target)
                }
            }
        }

        item(key = "progress") {
            val today = LocalDate.now()
            val light = state.history.light(today)
            val level = levelFor(state.history.totalXp())
            SectionLabel("Progress")
            RowGroup {
                AuroraLight(light.glow, light.rise, Modifier.fillMaxWidth().height(120.dp))
                Column(Modifier.padding(Space.M), verticalArrangement = Arrangement.spacedBy(Space.XS)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("${level.name} · level ${level.number}", Modifier.weight(1f), color = colors.textPrimary, style = AuroraType.BodyStrong)
                        Text("${state.history.day(today).xp()} XP today", color = colors.textSecondary, style = AuroraType.Caption)
                    }
                    ProgressLine(level.progress)
                    Text(
                        "${level.intoLevel} of ${level.levelSize} XP to the next level · ${state.history.steadyDays(today)} days in a row · light from ${light.activeDays} of the last 7 days",
                        color = colors.textSecondary,
                        style = AuroraType.Caption,
                    )
                }
            }
            Text(
                "XP comes only from completed Focus, stepping back at a Shield or an intentional opening, and committed Always Block time, with a daily cap. " +
                    "aurora can't see whether you were truly focused, so leaving a timer running earns no more than the cap.",
                Modifier.padding(horizontal = Space.Gutter).padding(top = Space.S),
                color = colors.textTertiary,
                style = AuroraType.Caption,
            )
        }

        item(key = "footnote") {
            Text(
                "Screen time is calculated on this phone from Android's usage records, which Android keeps for a limited time. " +
                    "Days without records show “—”. Totals can differ slightly from Digital Wellbeing.",
                Modifier.padding(horizontal = Space.Gutter).padding(top = Space.L),
                color = colors.textTertiary,
                style = AuroraType.Caption,
            )
        }
    }

    if (addingGoal) GoalDialog({ addingGoal = false }) { vm.addGoal(it); addingGoal = false }
    if (plusNote) AuroraDialog(
        title = "30 days is part of plus",
        body = "aurora Free shows today and the last 7 days. aurora plus adds a 30-day view.",
        confirmLabel = "Got it",
        onConfirm = { plusNote = false },
        onDismiss = { plusNote = false },
    ) { AuroraButton("About aurora plus", { plusNote = false; onPlus() }, kind = ButtonKind.Quiet) }
}

/** Restrained daily bars. Today is full strength; days without records are a faint dash, never a zero bar. */
@Composable
private fun DayBars(days: List<DayUsage>) {
    val colors = Aurora.colors
    val locale = LocalLocale.current.platformLocale
    val max = days.mapNotNull { it.total }.maxOrNull()?.coerceAtLeast(60_000L) ?: 1L
    val spoken = days.joinToString(", ") { d -> "${d.date.dayOfWeek.getDisplayName(TextStyle.SHORT, locale)} ${d.total?.let(::formatDuration) ?: "no data"}" }
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
    Column(Modifier.padding(Space.M), verticalArrangement = Arrangement.spacedBy(Space.XS)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(goal.kind.title(goal.target), Modifier.weight(1f), color = colors.textPrimary, style = AuroraType.BodyStrong)
            AuroraButton("Remove", onRemove, kind = ButtonKind.Quiet)
        }
        if (days.size > 1) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.semantics(mergeDescendants = true) { contentDescription = "Met on $met of $measured measured days" }) {
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
            Text("Met on $met of $measured measured days", color = colors.textSecondary, style = AuroraType.Caption)
        } else {
            val r = results.single()
            if (r.value == null) Text("Needs usage access to measure", color = colors.textTertiary, style = AuroraType.Caption)
            else {
                ProgressLine(r.value.toFloat() / goal.target.coerceAtLeast(1), warm = r.met == false)
                Text(
                    "${r.value} of ${goal.target}${if (goal.kind == GoalKind.OpensUnder) "" else " min"} today",
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
        title = "A goal for each day",
        confirmLabel = "Add goal",
        onConfirm = { onAdd(Goal(UUID.randomUUID().toString(), kind, target)) },
        dismissLabel = "Cancel",
        onDismiss = onDismiss,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(Space.S)) {
            Column {
                for (k in GoalKind.entries) {
                    RadioRow(
                        when (k) {
                            GoalKind.ScreenTimeUnder -> "Less screen time"
                            GoalKind.ProtectedTimeUnder -> "Less time in protected apps"
                            GoalKind.FocusAtLeast -> "More focus"
                            GoalKind.OpensUnder -> "Fewer unplanned openings"
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
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = Space.Gutter, vertical = Space.S)
            .semantics(mergeDescendants = true) {
                contentDescription = "${row.label}, ${formatDuration(row.ms)}" + if (row.onList) ", protected" else ""
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
                Text("protected", color = colors.dawnText, style = AuroraType.Caption)
            }
        }
    }
}
