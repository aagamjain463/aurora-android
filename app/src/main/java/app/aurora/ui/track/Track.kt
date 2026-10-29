package app.aurora.ui.track

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.aurora.R
import app.aurora.data.formatDuration
import app.aurora.progress.DayMark
import app.aurora.progress.Habit
import app.aurora.progress.HabitProgress
import app.aurora.progress.History
import app.aurora.progress.habitProgress
import app.aurora.progress.performance
import app.aurora.protect.Rules
import app.aurora.ui.components.AuroraButton
import app.aurora.ui.components.AuroraIcons
import app.aurora.ui.components.ButtonKind
import app.aurora.ui.components.Chevron
import app.aurora.ui.components.EmptyState
import app.aurora.ui.components.MetricGrid
import app.aurora.ui.components.SectionLabel
import app.aurora.ui.components.auroraSurface
import app.aurora.ui.components.clockTime
import app.aurora.ui.home.OutcomeTag
import app.aurora.ui.theme.Aurora
import app.aurora.ui.theme.AuroraType
import app.aurora.ui.theme.Radius
import app.aurora.ui.theme.Space
import java.time.LocalDate
import java.time.ZoneId

/** Track's first pillar: every active habit with its ring, streak and the last two weeks. */
fun LazyListScope.habitsSection(rules: Rules, history: History, onCreate: () -> Unit, onOpen: (String) -> Unit) {
    val today = LocalDate.now()
    val zone = ZoneId.systemDefault()
    val active = rules.habits.filterNot { it.archived }
    val archived = rules.habits.filter { it.archived }
    item(key = "habits-label") { SectionLabel(stringResource(R.string.track_habits)) }
    if (active.isEmpty()) item(key = "habits-empty") {
        Column(Modifier.padding(horizontal = Space.M).fillMaxWidth().auroraSurface(Aurora.colors, warm = true)) {
            EmptyState(stringResource(R.string.track_habits_empty_title), stringResource(R.string.track_habits_empty_body), icon = AuroraIcons.Spark, action = {
                AuroraButton(stringResource(R.string.track_create_habit), onCreate, icon = AuroraIcons.Plus)
            })
        }
    }
    items(active, key = { "habit-" + it.id }) { h -> HabitCard(h, habitProgress(h, history.runs, today, zone)) { onOpen(h.id) } }
    if (active.isNotEmpty()) item(key = "habit-create") {
        AuroraButton(stringResource(R.string.track_create_habit), onCreate, Modifier.padding(horizontal = Space.M, vertical = Space.XS).fillMaxWidth(), kind = ButtonKind.Secondary, icon = AuroraIcons.Plus)
    }
    if (archived.isNotEmpty()) item(key = "habit-archived") {
        Column(Modifier.padding(horizontal = Space.M, vertical = Space.XS)) {
            Text(stringResource(R.string.track_archived), Modifier.padding(start = Space.XS, bottom = Space.XS), color = Aurora.colors.textTertiary, style = AuroraType.Eyebrow)
            archived.forEach { h ->
                Row(
                    Modifier.fillMaxWidth().clip(Radius.M).clickable(role = Role.Button) { onOpen(h.id) }.padding(Space.S),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(h.name, Modifier.weight(1f), color = Aurora.colors.textSecondary, style = AuroraType.BodyStrong)
                    Chevron()
                }
            }
        }
    }
}

@Composable
private fun HabitCard(h: Habit, p: HabitProgress, onClick: () -> Unit) {
    val colors = Aurora.colors
    Row(
        Modifier.padding(horizontal = Space.M).padding(bottom = Space.S).fillMaxWidth().auroraSurface(colors, warm = true)
            .clickable(role = Role.Button, onClick = onClick).padding(Space.L),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.L),
    ) {
        HabitRing(p.rate, size = 76.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Space.XS)) {
            Text(h.name, color = colors.textPrimary, style = AuroraType.Title)
            Text(stringResource(R.string.habit_linked, h.configName), color = colors.textSecondary, style = AuroraType.Caption)
            Text(streakText(p), color = colors.sun, style = AuroraType.Label)
            DayStrip(p.calendar.takeLast(14), Modifier.padding(top = Space.XS))
        }
    }
}

@Composable
fun streakText(p: HabitProgress): String =
    stringResource(R.string.habit_streak) + " · " + stringResource(if (p.streakUnitWeeks) R.string.habit_weeks_unit else R.string.habit_days_unit, p.currentStreak)

/**
 * The completion ring: emerald warming to sun as it fills, the point of light at its head. An on-demand
 * habit has no rate, so the ring stays an open horizon with its count of successes instead.
 */
@Composable
fun HabitRing(rate: Float?, size: Dp, label: String? = null) {
    val colors = Aurora.colors
    val spoken = rate?.let { "${(it * 100).toInt()}%" } ?: "—"
    Box(Modifier.size(size).semantics { contentDescription = "${label ?: ""} $spoken" }, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = (this.size.minDimension * 0.08f)
            val inset = stroke
            val box = Size(this.size.width - 2 * inset, this.size.height - 2 * inset)
            drawArc(colors.hairline, 0f, 360f, false, Offset(inset, inset), box, style = Stroke(stroke))
            val p = rate ?: 0f
            if (p > 0f) drawArc(
                Brush.sweepGradient(listOf(colors.accent, colors.cta.last(), colors.sun, colors.accent)),
                -90f, 360f * p, false, Offset(inset, inset), box, style = Stroke(stroke, cap = StrokeCap.Round),
            )
        }
        Text(spoken, color = colors.textPrimary, style = if (size > 100.dp) AuroraType.Headline else AuroraType.Label)
    }
}

/** Days as points of light: lit for success, warm for partial, faint ring for missed, empty for rest. */
@Composable
fun DayStrip(days: List<Pair<LocalDate, DayMark>>, modifier: Modifier = Modifier, dot: Dp = 12.dp) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(5.dp)) { days.forEach { (_, m) -> DayDot(m, dot) } }
}

@Composable
fun DayDot(m: DayMark, size: Dp) {
    val colors = Aurora.colors
    val base = Modifier.size(size).clip(Radius.Pill)
    when (m) {
        DayMark.Succeeded -> Box(base.background(Brush.radialGradient(listOf(colors.accent, colors.accent.copy(alpha = 0.6f)))))
        DayMark.Partial -> Box(base.background(colors.sun.copy(alpha = 0.7f)))
        DayMark.Missed -> Box(base.border(1.5.dp, colors.dawn.copy(alpha = 0.7f), Radius.Pill))
        DayMark.Pending -> Box(base.border(1.5.dp, colors.accent.copy(alpha = 0.7f), Radius.Pill))
        DayMark.Rest -> Box(base.background(colors.hairline))
        DayMark.Before -> Box(base.background(colors.hairline.copy(alpha = 0.35f)))
    }
}

/** Track's second pillar: what configurations actually achieved, from evidence. */
fun LazyListScope.performanceSection(history: History, running: Int) {
    val p = performance(history.runs, running, history.service)
    item(key = "perf") {
        SectionLabel(stringResource(R.string.track_performance))
        MetricGrid(
            listOf(
                p.activations.toString() to stringResource(R.string.track_activations),
                p.succeeded.toString() to stringResource(R.string.track_succeeded),
                p.completed.toString() to stringResource(R.string.track_completed),
                p.turnedOff.toString() to stringResource(R.string.track_turned_off),
                (if (p.verifiedMs == 0L) "0m" else formatDuration(p.verifiedMs)) to stringResource(R.string.track_verified),
                (if (p.focusMs == 0L) "0m" else formatDuration(p.focusMs)) to stringResource(R.string.track_focus_time),
            ),
        )
        Text(stringResource(R.string.track_performance_note), Modifier.padding(horizontal = Space.Gutter).padding(top = Space.S), color = Aurora.colors.textTertiary, style = AuroraType.Caption)
    }
    val recent = history.runs.takeLast(5).reversed()
    item(key = "perf-recent") {
        val colors = Aurora.colors
        Column(Modifier.padding(horizontal = Space.M).padding(top = Space.M).fillMaxWidth().auroraSurface(colors).padding(Space.M), verticalArrangement = Arrangement.spacedBy(Space.S)) {
            Text(stringResource(R.string.track_recent_runs), color = colors.textPrimary, style = AuroraType.BodyStrong)
            if (recent.isEmpty()) Text(stringResource(R.string.track_no_runs), color = colors.textSecondary, style = AuroraType.Secondary)
            recent.forEach { r ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.S)) {
                    Column(Modifier.weight(1f)) {
                        Text(r.name, color = colors.textPrimary, style = AuroraType.Body)
                        Text(clockTime(r.start) + " · " + formatDuration(r.end - r.start) + if (r.verifiedMs > 0) " · " + formatDuration(r.verifiedMs) + " " + stringResource(R.string.track_verified).lowercase() else "", color = colors.textTertiary, style = AuroraType.Caption)
                    }
                    OutcomeTag(r.outcome)
                }
            }
        }
    }
    item(key = "perf-space") { Box(Modifier.height(Space.S)) }
}
