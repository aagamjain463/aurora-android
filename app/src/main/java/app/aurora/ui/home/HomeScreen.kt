package app.aurora.ui.home

import app.aurora.ui.components.auroraSurface
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Brush
import app.aurora.ui.theme.DarkColors
import app.aurora.ui.theme.LocalAuroraColors
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.aurora.AuroraApplication
import app.aurora.R
import app.aurora.auroraViewModel
import app.aurora.data.Access
import app.aurora.data.ProtectedSpan
import app.aurora.data.TimeAway
import app.aurora.data.clockTicks
import app.aurora.data.formatDuration
import app.aurora.data.openAccessSettings
import app.aurora.data.timeAway
import app.aurora.plan.Plan
import app.aurora.progress.History
import app.aurora.progress.levelFor
import app.aurora.progress.light
import app.aurora.progress.on
import app.aurora.progress.steadyDays
import app.aurora.progress.title
import app.aurora.progress.totalXp
import app.aurora.progress.xp
import app.aurora.protect.Mode
import app.aurora.protect.ProtectedList
import app.aurora.protect.Rules
import app.aurora.protect.Runtime
import app.aurora.protect.covers
import app.aurora.protect.windowsBetween
import app.aurora.protect.nextStart
import app.aurora.protect.key
import app.aurora.settleFocus
import app.aurora.syncRuns
import app.aurora.ui.components.ArtTier
import app.aurora.ui.components.AuroraHeader
import app.aurora.ui.components.Chevron
import app.aurora.ui.components.FirstLightBackdrop
import app.aurora.ui.components.LitBadge
import app.aurora.ui.components.StatCell
import app.aurora.ui.components.StatDivider
import app.aurora.ui.components.TwoToneHeadline
import app.aurora.ui.components.WeekDots
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.size
import androidx.compose.ui.res.pluralStringResource
import app.aurora.ui.components.AuroraButton
import app.aurora.ui.components.AuroraIconButton
import app.aurora.ui.components.AuroraIcons
import app.aurora.ui.components.AuroraLight
import app.aurora.ui.components.AuroraLandscape
import app.aurora.ui.components.ButtonKind
import app.aurora.ui.components.ModeGlyph
import app.aurora.ui.components.PillarHeading
import app.aurora.ui.components.ProgressLine
import app.aurora.ui.components.TabBarHeight
import app.aurora.ui.components.Wordmark
import app.aurora.ui.components.clockTime
import app.aurora.ui.components.minutesText
import app.aurora.ui.protect.ProtectData
import app.aurora.ui.protect.ProtectModel
import app.aurora.ui.protect.SessionCard
import app.aurora.ui.protect.modeColor
import app.aurora.ui.protect.modeName
import app.aurora.ui.protect.rememberProtectionStatus
import app.aurora.ui.protect.runningWindows
import app.aurora.ui.protect.summary
import app.aurora.ui.theme.Aurora
import app.aurora.ui.theme.AuroraType
import app.aurora.ui.theme.Radius
import app.aurora.ui.theme.Space
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId

/** Time away today, or why it can't be shown. */
sealed interface AwayState {
    data object Loading : AwayState
    data object NoUsageAccess : AwayState
    data object Unavailable : AwayState
    data object NoProtection : AwayState
    data class Measured(val away: TimeAway) : AwayState
}

/** The protection spans that overlap today: recorded sessions and Focus, and today's schedule windows. */
fun protectionToday(history: History, rules: Rules, runtime: Runtime, list: ProtectedList, plan: Plan, from: Long, to: Long, zone: ZoneId): List<ProtectedSpan> {
    val active = list.active(plan).map { it.key }.toSet()
    val recorded = history.protection.filter { it.end > from && it.start < to }.map { ProtectedSpan(it.start, it.end, it.keys) }
    val windows = rules.activeSchedules(plan).flatMap { s ->
        val keys = rules.covers(s, active)
        s.windowsBetween(from, to, zone).filterNot { (start, _) -> runtime.skipped[s.id] == start && s.mode != Mode.AlwaysBlock }
            .map { (start, end) -> ProtectedSpan(start, end, keys) }
    }
    val migrated = runtime.sessions.filter { s -> history.protection.none { it.id == s.id } }
        .map { ProtectedSpan(it.time.startWall, it.time.startWall + it.time.durationMs, it.keys) }
    return recorded + windows + migrated
}

class TodayModel(val app: AuroraApplication) : ViewModel() {
    private val _away = MutableStateFlow<AwayState>(AwayState.Loading)
    val away: StateFlow<AwayState> = _away
    private var measuring: Job? = null

    /** Measures time away now. Called on resume and every minute while Today is open; skipped while one is running. */
    fun refresh() {
        if (measuring?.isActive == true) return
        measuring = viewModelScope.launch {
            val zone = ZoneId.systemDefault()
            val now = System.currentTimeMillis()
            val from = LocalDate.now().atStartOfDay(zone).toInstant().toEpochMilli()
            val history = app.history.value ?: return@launch
            val rules = app.rules.value ?: return@launch
            val runtime = app.runtime.value ?: return@launch
            val list = app.protectedList.value ?: return@launch
            val spans = protectionToday(history, rules, runtime, list, app.plan, from, now, zone)
            _away.value = when {
                spans.none { it.end > from && it.start < now } -> AwayState.NoProtection
                !app.access.isGranted(Access.Usage) -> AwayState.NoUsageAccess
                else -> app.screenTime.intervals(from, now)?.let { AwayState.Measured(timeAway(spans, it, from, now)) } ?: AwayState.Unavailable
            }
        }
    }

    /** Records sessions that ended while aurora was closed. Idempotent. */
    fun settle() {
        viewModelScope.launch {
            runCatching { app.settleFocus(endNow = false) }
            runCatching { app.syncRuns() }
        }
    }
}

/**
 * Today. One hero, one number, one action, then two separate pillars: Protection (what is protected
 * right now) and Progress (how things are changing over time). The main number is real time away from
 * protected apps, measured from Android's usage records, never the time someone set on a timer.
 */
@Composable
fun HomeScreen(
    onNormalUse: () -> Unit,
    onCustom: () -> Unit,
    onOpenFocus: (runId: String) -> Unit,
    onOpenLegacyFocus: () -> Unit,
    onOpenTrack: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val today = auroraViewModel { TodayModel(it) }
    val protect = auroraViewModel { ProtectModel(it) }
    val data by protect.data.collectAsStateWithLifecycle()
    val away by today.away.collectAsStateWithLifecycle()
    val history by today.app.history.state.collectAsStateWithLifecycle()
    val minute by clockTicks(60_000).collectAsStateWithLifecycle(0L)
    val prefs by today.app.preferences.preferences.collectAsStateWithLifecycle(null)
    val context = androidx.compose.ui.platform.LocalContext.current
    LifecycleResumeEffect(minute) {
        today.refresh()
        today.settle()
        onPauseOrDispose { }
    }
    val colors = Aurora.colors
    val h = history?.value ?: History()
    val date = LocalDate.now()
    val light = h.light(date)
    val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + TabBarHeight + Space.XXL

    Box(Modifier.fillMaxSize().background(colors.background)) {
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    val heroVisible by androidx.compose.runtime.remember { androidx.compose.runtime.derivedStateOf { listState.firstVisibleItemIndex == 0 } }
    FirstLightBackdrop(ArtTier.Immersive, scroll = { if (listState.firstVisibleItemIndex == 0) listState.firstVisibleItemScrollOffset else Int.MAX_VALUE / 4 })
    LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = bottom)) {
        // 1. The hero: the mark, then the founder's headline set over the lake.
        item(key = "hero") {
            Column(Modifier.statusBarsPadding()) {
                AuroraHeader(onOpenSettings)
                Spacer(Modifier.height(176.dp))
                TwoToneHeadline(stringResource(R.string.today_headline), Modifier.padding(horizontal = Space.Gutter))
                Text(
                    stringResource(R.string.today_subtitle),
                    Modifier.padding(horizontal = Space.Gutter).padding(top = Space.S, bottom = Space.L).fillMaxWidth(0.82f),
                    color = colors.textSecondary,
                    style = AuroraType.Body,
                )
            }
        }
        // 2. Time away from protected apps.
        item(key = "metric") {
            Column(Modifier.padding(horizontal = Space.M, vertical = Space.XS).fillMaxWidth().auroraSurface(colors).padding(Space.L)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.XS)) {
                    androidx.compose.material3.Icon(AuroraIcons.Insights, null, tint = colors.textSecondary, modifier = Modifier.size(20.dp))
                    Text(stringResource(R.string.today_metric_label), color = colors.textSecondary, style = AuroraType.BodyStrong)
                }
                Spacer(Modifier.height(Space.XS))
                when (val a = away) {
                    is AwayState.Measured -> {
                        Text(formatDuration(a.away.awayMs), color = colors.textPrimary, style = AuroraType.Metric)
                        Text(
                            stringResource(R.string.today_metric_detail, formatDuration(a.away.protectedMs), formatDuration(a.away.usedMs)),
                            color = colors.accent, style = AuroraType.Secondary,
                        )
                        Text(stringResource(R.string.today_metric_definition), Modifier.padding(top = 4.dp), color = colors.textTertiary, style = AuroraType.Caption)
                    }
                    AwayState.NoUsageAccess -> {
                        Text(stringResource(R.string.today_metric_not_measured), Modifier.padding(vertical = Space.XS), color = colors.textPrimary, style = AuroraType.Headline)
                        Text(stringResource(R.string.today_metric_no_access), color = colors.textSecondary, style = AuroraType.Secondary)
                        AuroraButton(stringResource(R.string.today_metric_allow), { openAccessSettings(context, Access.Usage) }, Modifier.padding(top = Space.S), kind = ButtonKind.Secondary)
                    }
                    AwayState.Unavailable -> {
                        Text("—", color = colors.textPrimary, style = AuroraType.Metric)
                        Text(stringResource(R.string.usage_records_unavailable_body), color = colors.textSecondary, style = AuroraType.Secondary)
                    }
                    AwayState.NoProtection -> {
                        Text("0m", color = colors.textTertiary, style = AuroraType.Metric)
                        Text(stringResource(R.string.today_metric_none), color = colors.textSecondary, style = AuroraType.Secondary)
                    }
                    AwayState.Loading -> Text(" ", style = AuroraType.Metric)
                }
            }
        }
        // 3. How to use Aurora now: the two ways in, then what is running, scheduled and always on.
        val d = data
        item(key = "choose") { ChooseCard(onNormalUse, onCustom) }
        if (d != null) homeSections(d, today.app, onOpenFocus, onOpenLegacyFocus)

        // 4. Progress: this week, from recorded days only.
        item(key = "progress") {
            val week = (0..6).map { date.with(java.time.DayOfWeek.MONDAY).plusDays(it.toLong()) }
            val days = week.map { h.day(it) }
            val steady = h.steadyDays(date)
            Column(
                Modifier.padding(horizontal = Space.M, vertical = Space.XS).fillMaxWidth().auroraSurface(colors)
                    .clickable(role = androidx.compose.ui.semantics.Role.Button, onClick = onOpenTrack).padding(Space.L),
                verticalArrangement = Arrangement.spacedBy(Space.M),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.M)) {
                    androidx.compose.material3.Icon(AuroraIcons.Spark, null, tint = colors.sun, modifier = Modifier.size(26.dp))
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.today_progress_title), Modifier.semantics { heading() }, color = colors.textPrimary, style = AuroraType.Title)
                        Text(stringResource(R.string.today_progress_body), color = colors.textSecondary, style = AuroraType.Secondary)
                    }
                    if (steady > 1) Text(stringResource(R.string.today_steady, steady), color = colors.sun, style = AuroraType.Label)
                    Chevron()
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    WeekDots(week, date, active = { i -> days[i].xp() > 0 }, Modifier.weight(1.25f))
                    StatDivider()
                    StatCell(days.sumOf { it.focusSessions }.toString(), stringResource(R.string.today_week_sessions), Modifier.weight(0.8f))
                    StatDivider()
                    StatCell(minutesText(days.sumOf { it.completedFocusMinutes }), stringResource(R.string.today_week_focus), Modifier.weight(0.9f))
                }
            }
        }
        // 5. Aurora Light: the level, drawn from real progress.
        item(key = "level") {
            val xp = h.totalXp()
            val level = levelFor(xp)
            Column(
                Modifier.padding(horizontal = Space.M, vertical = Space.XS).fillMaxWidth().auroraSurface(colors, warm = true).padding(Space.L),
                verticalArrangement = Arrangement.spacedBy(Space.XS),
            ) {
                Text(stringResource(R.string.today_light_level, level.number, level.name), color = colors.sun, style = AuroraType.Eyebrow)
                AuroraLight(light.glow, light.rise, Modifier.fillMaxWidth().height(112.dp), description = stringResource(R.string.today_light_description))
                Text(stringResource(R.string.today_xp_today, h.day(date).xp()), color = colors.textPrimary, style = AuroraType.Headline)
                ProgressLine(level.progress, Modifier.padding(vertical = Space.XS), warm = true)
                Text(stringResource(R.string.today_xp_next, level.levelSize - level.intoLevel, light.activeDays), color = colors.textSecondary, style = AuroraType.Secondary)
                Text(stringResource(R.string.today_xp_how), color = colors.textTertiary, style = AuroraType.Caption)
            }
        }
        if (d != null && d.rules.goals.isNotEmpty()) {
            item(key = "goals") {
                Column(
                    Modifier.padding(horizontal = Space.M, vertical = Space.XS).fillMaxWidth().auroraSurface(colors).padding(Space.L),
                    verticalArrangement = Arrangement.spacedBy(Space.S),
                ) {
                    Text(stringResource(R.string.today_goals), color = colors.textPrimary, style = AuroraType.Title)
                    d.rules.goals.forEach { g ->
                        val day = g.on(h.day(date), null, null, dayOver = false)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(g.kind.title(g.target), Modifier.weight(1f), color = colors.textPrimary, style = AuroraType.Body)
                            Text(
                                when (day.met) {
                                    true -> stringResource(R.string.goal_met)
                                    false -> stringResource(R.string.goal_missed)
                                    null -> day.value?.let { stringResource(R.string.goal_so_far, it) } ?: stringResource(R.string.goal_unavailable)
                                },
                                color = if (day.met == true) colors.accent else colors.textSecondary,
                                style = AuroraType.Label,
                            )
                        }
                    }
                }
            }
        }
        // 6. Insights.
        item(key = "insights") {
            val stats = h.day(date)
            Column(Modifier.padding(horizontal = Space.M, vertical = Space.L)) {
                Text(
                    stringResource(R.string.today_insight_line, stats.shields, stats.wentBack + stats.intentLetGo),
                    Modifier.padding(horizontal = Space.XS, vertical = Space.S),
                    color = colors.textSecondary,
                    style = AuroraType.Body,
                )
                AuroraButton(stringResource(R.string.today_see_insights), onOpenTrack, Modifier.fillMaxWidth(), kind = ButtonKind.Quiet)
            }
        }
    }
    // Content scrolls under a calm status bar, never under the clock.
    if (!heroVisible) Box(Modifier.fillMaxWidth().background(colors.background.copy(alpha = 0.96f)).statusBarsPadding())
    }
}
