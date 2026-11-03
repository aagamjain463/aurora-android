package app.aurora.ui.insights

import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.unit.sp
import app.aurora.ui.components.auroraSurface
import androidx.compose.ui.graphics.Brush
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
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.ui.res.stringResource
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import androidx.lifecycle.viewModelScope
import app.aurora.R
import app.aurora.AuroraApplication
import app.aurora.data.Access
import app.aurora.data.formatDuration
import app.aurora.data.openUsageAccessSettings
import app.aurora.plan.EntitlementPolicy
import app.aurora.plan.Plan
import app.aurora.protect.key
import app.aurora.ui.components.AuroraButton
import app.aurora.ui.components.AuroraIcons
import app.aurora.ui.components.ChoicePills
import app.aurora.ui.components.EmptyState
import app.aurora.ui.components.ProgressLine
import app.aurora.ui.components.SectionLabel
import app.aurora.ui.components.SkeletonRows
import app.aurora.ui.theme.Aurora
import app.aurora.ui.theme.Palette
import app.aurora.ui.theme.AuroraType
import app.aurora.ui.theme.Radius
import app.aurora.ui.theme.Space
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.TextStyle

data class UsageRow(val packageName: String, val label: String, val icon: ImageBitmap?, val ms: Long, val onList: Boolean)

/** One day as Android measured it. Null totals: no records for that day (or no usage access). */
data class DayUsage(val date: LocalDate, val total: Long?, val protected: Long?)

enum class Period(val days: Int, val label: Int) { Today(1, R.string.tab_today), Week(7, R.string.insights_period_week), Month(30, R.string.insights_period_month) }

private const val OtherApps = "other"

data class InsightsState(
    val access: Boolean,
    val loading: Boolean = true,
    val period: Period = Period.Today,
    val rows: List<UsageRow> = emptyList(),
    val days: List<DayUsage> = emptyList(),
    val plan: Plan = Plan.Free,
)

class InsightsViewModel(private val app: AuroraApplication) : ViewModel() {
    private val _state = MutableStateFlow(InsightsState(access = app.access.isGranted(Access.Usage)))
    val state: StateFlow<InsightsState> = _state
    private var refreshJob: Job? = null

    fun select(period: Period) {
        _state.value = _state.value.copy(period = period, loading = true)
        refresh()
    }

    fun refresh() {
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            val period = _state.value.period
            val access = app.access.isGranted(Access.Usage)
            val protectedKeys = app.protectedList.filterNotNull().first().active(app.plan).map { it.key }.toSet()
            val today = LocalDate.now()
            val perDay = (period.days - 1 downTo 0).map { back ->
                val date = today.minusDays(back.toLong())
                date to if (access) app.screenTime.day(date) else null
            }
            val days = perDay.map { (date, usage) -> DayUsage(date, usage?.values?.sum(), usage?.filterKeys { it in protectedKeys }?.values?.sum()) }
            val rows = when {
                !access -> emptyList()
                period == Period.Today -> appRows(app.screenTime.today(), protectedKeys)
                // Each app's average over the days Android has records for, so a missing day doesn't pull it down.
                else -> perDay.mapNotNull { it.second }.let { known ->
                    val totals = mutableMapOf<String, Long>()
                    known.forEach { day -> day.forEach { (pkg, ms) -> totals[pkg] = (totals[pkg] ?: 0L) + ms } }
                    if (known.isEmpty()) emptyList() else appRows(totals.mapValues { it.value / known.size }, protectedKeys)
                }
            }
            _state.value = InsightsState(access, loading = false, period = period, rows = rows, days = days, plan = app.plan)
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
}

/**
 * Track's Usage: where time goes, measured by Android. Days without records show as "—", never as zero.
 */
fun LazyListScope.usageSection(state: InsightsState, vm: InsightsViewModel, onMonthLocked: () -> Unit) {
    item(key = "period") {
        val resources = LocalResources.current
        ChoicePills(
            Period.entries, state.period,
            { p -> if (p == Period.Month && !EntitlementPolicy.canAccessAdvancedAnalytics(state.plan).allowed) onMonthLocked() else vm.select(p) },
            label = { resources.getString(it.label) }, fill = true, segmented = true,
            modifier = Modifier.padding(horizontal = Space.Gutter),
        )
    }
    if (state.loading) {
        item(key = "usage-loading") { SkeletonRows(5) }
        return
    }
    if (!state.access) item(key = "permission") {
        val context = LocalContext.current
        EmptyState(
            icon = AuroraIcons.Insights,
            title = stringResource(R.string.insights_see_where_your_time_goes),
            body = stringResource(R.string.insights_with_usage_access_aurora_can_show_how_long_you_spend_in_each_app),
            action = { AuroraButton(stringResource(R.string.today_metric_allow), { openUsageAccessSettings(context) }) },
        )
    } else item(key = "total") {
        val colors = Aurora.colors
        val known = state.days.mapNotNull { it.total }
        Column(Modifier.padding(horizontal = Space.Gutter).padding(top = Space.M).fillMaxWidth().auroraSurface(colors).padding(horizontal = 22.dp, vertical = 20.dp)) {
            val metric = AuroraType.Metric.copy(fontSize = 40.sp, lineHeight = 46.sp)
            if (state.period == Period.Today) {
                Text(stringResource(R.string.insights_screen_time_since_midnight), color = colors.textTertiary, style = AuroraType.Caption)
                Text(if (known.isEmpty()) "—" else formatDuration(known.sum()), color = colors.textPrimary, style = metric)
            } else {
                Text(stringResource(R.string.insights_average_days , known.size, state.days.size), color = colors.textTertiary, style = AuroraType.Caption)
                Text(if (known.isEmpty()) "—" else formatDuration(known.sum() / known.size), color = colors.textPrimary, style = metric)
                Spacer(Modifier.height(16.dp))
                DayBars(state.days)
            }
        }
    }

    if (state.access) {
        if (state.days.none { it.total != null }) item(key = "unavailable") {
            EmptyState(icon = AuroraIcons.Insights, title = stringResource(R.string.usage_records_unavailable_title), body = stringResource(R.string.usage_records_unavailable_body))
        } else if (state.rows.isEmpty() && state.period == Period.Today) item(key = "quiet") {
            EmptyState(icon = AuroraIcons.Today, title = stringResource(R.string.insights_a_quiet_day_so_far), body = stringResource(R.string.insights_there_s_no_screen_time_to_show_since_midnight_yet))
        } else if (state.rows.isNotEmpty()) {
            item(key = "label") { SectionLabel(stringResource(if (state.period == Period.Today) R.string.insights_most_used else R.string.usage_most_used_per_day)) }
            item(key = "most-used") {
                val max = state.rows.maxOfOrNull { it.ms } ?: 1L
                Column(Modifier.padding(horizontal = Space.Gutter).fillMaxWidth().auroraSurface(Aurora.colors)) {
                    state.rows.forEachIndexed { i, row ->
                        if (i > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(Aurora.colors.hairline))
                        UsageBar(row, row.ms.toFloat() / max)
                    }
                }
            }
        }
    }
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
        Canvas(Modifier.fillMaxWidth().height(90.dp)) {
            val gap = if (days.size <= 7) 12.dp.toPx() else 3.dp.toPx()
            val bar = (size.width - gap * (days.size - 1)) / days.size
            days.forEachIndexed { i, d ->
                val x = (bar + gap) * i
                val total = d.total
                if (total == null) {
                    drawRoundRect(colors.hairline, Offset(x, size.height - 3.dp.toPx()), Size(bar, 3.dp.toPx()), CornerRadius(2.dp.toPx()))
                } else {
                    val h = (size.height * total / max).coerceAtLeast(6.dp.toPx())
                    // Today in full ink; earlier days are the same bar, quieter. Flat, never a gradient.
                    drawRoundRect(if (i == days.lastIndex) colors.textPrimary else colors.elevated, Offset(x, size.height - h), Size(bar, h), CornerRadius(minOf(7.dp.toPx(), bar / 2)))
                }
            }
        }
        if (days.size <= 7) Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            days.forEachIndexed { i, d ->
                val today = i == days.lastIndex
                Text(
                    d.date.dayOfWeek.getDisplayName(TextStyle.NARROW, locale),
                    Modifier.weight(1f),
                    color = if (today) colors.textPrimary else colors.textTertiary,
                    style = AuroraType.Caption.copy(fontSize = 11.5.sp, fontWeight = androidx.compose.ui.text.font.FontWeight(if (today) 600 else 400)),
                    textAlign = TextAlign.Center,
                )
            }
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
            .heightIn(min = 58.dp)
            .padding(horizontal = 18.dp, vertical = 10.dp)
            .semantics(mergeDescendants = true) {
                contentDescription = "${row.label}, ${formatDuration(row.ms)}" + if (row.onList) protectedSuffix else ""
            },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (row.icon != null) {
            Image(row.icon, null, Modifier.size(32.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(9.dp)))
        } else {
            Box(Modifier.size(32.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(9.dp)).background(colors.elevated))
        }
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    row.label, Modifier.weight(1f),
                    color = colors.textPrimary, style = AuroraType.BodyStrong.copy(fontSize = 14.5.sp), maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                if (row.onList) Text(stringResource(R.string.insights_protected), color = colors.textTertiary, style = AuroraType.Caption)
                Text(formatDuration(row.ms), color = colors.textPrimary, style = AuroraType.Title.copy(fontSize = 14.sp, fontFeatureSettings = "tnum"))
            }
            Box(Modifier.padding(top = 7.dp).fillMaxWidth().height(4.dp).clip(Radius.Pill).background(colors.elevated)) {
                Box(Modifier.fillMaxWidth(fraction.coerceIn(0.02f, 1f)).height(4.dp).clip(Radius.Pill).background(colors.textSecondary))
            }
        }
    }
}
