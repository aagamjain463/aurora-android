package app.aurora.ui.home

import app.aurora.ui.components.rememberGlassSource
import app.aurora.ui.components.glassSource
import app.aurora.ui.components.frosted
import app.aurora.ui.components.PageGlow
import app.aurora.readyToStart
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
import app.aurora.data.Reclaimed
import app.aurora.data.UsageKeepDays
import app.aurora.data.reclaimed
import app.aurora.data.clockTicks
import app.aurora.data.formatDuration
import app.aurora.data.openAccessSettings
import app.aurora.data.timeAway
import app.aurora.plan.Plan
import app.aurora.protect.planExcess
import app.aurora.progress.History
import app.aurora.progress.levelFor
import app.aurora.progress.light
import app.aurora.progress.totalXp
import app.aurora.protect.Mode
import app.aurora.protect.ProtectedList
import app.aurora.protect.Rules
import app.aurora.protect.Runtime
import app.aurora.protect.covers
import app.aurora.protect.windowsBetween
import app.aurora.protect.nextStart
import app.aurora.protect.key
import app.aurora.runsNow
import app.aurora.goalUsage
import app.aurora.settleFocus
import app.aurora.syncRuns
import app.aurora.ui.components.ArtTier
import app.aurora.ui.components.AuroraHeader
import app.aurora.ui.components.BrandEyebrow
import app.aurora.ui.components.AuroraBackdrop
import app.aurora.ui.components.Sky
import app.aurora.ui.components.LitBadge
import app.aurora.ui.components.TwoToneHeadline
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.size
import androidx.compose.ui.res.pluralStringResource
import app.aurora.ui.components.AuroraButton
import app.aurora.ui.components.AuroraIconButton
import app.aurora.ui.components.AuroraIcons
import app.aurora.ui.components.AuroraLight
import app.aurora.ui.components.ButtonKind
import app.aurora.ui.components.ModeGlyph
import app.aurora.ui.components.PillarHeading
import app.aurora.ui.components.ProgressLine
import app.aurora.ui.components.TabBarHeight
import app.aurora.ui.components.Wordmark
import app.aurora.ui.components.clockTime
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
import app.aurora.ui.theme.Space
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId
import androidx.compose.foundation.layout.widthIn
import app.aurora.ui.components.ContentMaxWidth
import app.aurora.ui.components.rise
import androidx.compose.animation.togetherWith
import app.aurora.ui.components.ProvideIntro
import app.aurora.ui.components.AuroraSweep

/** Time Reclaimed, or why it can't be shown. */
sealed interface AwayState {
    data object Loading : AwayState
    data object NoUsageAccess : AwayState
    data object Unavailable : AwayState
    data class Measured(val reclaimed: Reclaimed) : AwayState
}

/** The protection spans that overlap today: recorded sessions and Focus, and today's schedule windows. */
fun protectionToday(history: History, rules: Rules, runtime: Runtime, list: ProtectedList, plan: Plan, from: Long, to: Long, zone: ZoneId): List<ProtectedSpan> {
    val active = list.active(plan).map { it.key }.toSet()
    val recorded = history.protection.filter { it.end > from && it.start < to }.map { ProtectedSpan(it.start, it.end, it.keys) }
    val windows = rules.activeSchedules().flatMap { s ->
        val keys = rules.covers(s, active)
        s.windowsBetween(from, to, zone).filterNot { (start, _) -> runtime.skipped[s.id] == start && s.mode != Mode.AlwaysBlock }
            .map { (start, end) -> ProtectedSpan(start, end, keys) }
    }
    val migrated = runtime.sessions.filter { s -> history.protection.none { it.id == s.id } }
        .map { ProtectedSpan(it.time.startWall, it.time.startWall + it.time.durationMs, it.keys) }
    return recorded + windows + migrated
}

/**
 * Stores finished days while usage records still hold them, then measures Time Reclaimed: what Home and
 * Track both show. Loading until aurora's data has been read.
 */
suspend fun AuroraApplication.measureReclaimed(): AwayState {
    val zone = ZoneId.systemDefault()
    val now = System.currentTimeMillis()
    val date = LocalDate.now(zone)
    val history = history.value ?: return AwayState.Loading
    val rules = rules.value ?: return AwayState.Loading
    val runtime = runtime.value ?: return AwayState.Loading
    val list = protectedList.value ?: return AwayState.Loading
    fun startOf(day: LocalDate) = day.atStartOfDay(zone).toInstant().toEpochMilli()
    // ponytail: past schedule windows come from today's rules; a schedule edited before its day is stored counts as edited.
    fun spans(from: Long, to: Long) = protectionToday(history, rules, runtime, list, plan, from, to, zone)
        .filter { it.end > from && it.start < to && it.keys.isNotEmpty() }
    val from = startOf(date)
    val today = spans(from, now)
    val unsettled = (UsageKeepDays.toLong() downTo 1).map(date::minusDays)
        .filter { it.toString() !in history.reclaimed && spans(startOf(it), startOf(it.plusDays(1))).isNotEmpty() }
    val usage = if (today.isEmpty() && unsettled.isEmpty()) emptyList() else {
        if (!access.isGranted(Access.Usage)) return AwayState.NoUsageAccess
        screenTime.intervals(unsettled.firstOrNull()?.let(::startOf) ?: from, now) ?: return AwayState.Unavailable
    }
    val finished = unsettled.associate { day ->
        val start = startOf(day)
        val end = startOf(day.plusDays(1))
        day.toString() to timeAway(spans(start, end), usage, start, end).awayMs
    }
    if (finished.isNotEmpty()) runCatching { this.history.update { it.copy(reclaimed = it.reclaimed + finished) } }
    return AwayState.Measured(reclaimed(history.reclaimed + finished, timeAway(today, usage, from, now).awayMs, date))
}

class TodayModel(val app: AuroraApplication) : ViewModel() {
    private val _away = MutableStateFlow<AwayState>(AwayState.Loading)
    val away: StateFlow<AwayState> = _away
    private var measuring: Job? = null

    /**
     * Records sessions that ended while aurora was closed, then measures. Called on resume and every minute
     * while Home is open; skipped while one is running.
     */
    fun refresh() {
        if (measuring?.isActive == true) return
        measuring = viewModelScope.launch {
            runCatching { app.settleFocus(endNow = false) }
            runCatching { app.syncRuns() }
            runCatching { app.goalUsage(includeToday = false) }
            _away.value = app.measureReclaimed()
        }
    }
}

/**
 * Home. One hero, the two ways in, then Time Reclaimed: real time away from protected apps, measured
 * from Android's usage records, never the time someone set on a timer. Custom sessions follow.
 */
@Composable
fun HomeScreen(
    onNormalUse: () -> Unit,
    onCustom: () -> Unit,
    onOpenFocus: (runId: String) -> Unit,
    onOpenLegacyFocus: () -> Unit,
    onOpenSettings: () -> Unit,
    onKeep: () -> Unit = {},
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
        onPauseOrDispose { }
    }
    val colors = Aurora.colors
    val h = history?.value ?: History()
    val date = LocalDate.now()
    val light = h.light(date)
    val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + TabBarHeight + Space.XXL

    val glass = rememberGlassSource()
    ProvideIntro { Box(Modifier.fillMaxSize().background(colors.background)) {
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    val heroVisible by androidx.compose.runtime.remember { androidx.compose.runtime.derivedStateOf { listState.firstVisibleItemIndex == 0 } }
    val sky = data?.let { skyNow(it, today.app) } ?: Sky.Quiet
    val arc = data?.let { d -> leadRun(d, today.app)?.let { r -> runProgress(r, d) } }
    Box(Modifier.fillMaxSize().glassSource(glass)) {
    AuroraBackdrop(ArtTier.Immersive, sky = sky, glow = light.glow, arc = arc, scroll = { if (listState.firstVisibleItemIndex == 0) listState.firstVisibleItemScrollOffset else Int.MAX_VALUE / 4 })
    PageGlow()
    LazyColumn(Modifier.align(Alignment.TopCenter).widthIn(max = ContentMaxWidth).fillMaxSize(), state = listState, contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = bottom)) {
        // The brand promise leads into setup; measurement follows the actions.
        item(key = "hero") {
            Column(Modifier.statusBarsPadding()) {
                AuroraHeader(onOpenSettings)
                Spacer(Modifier.height(164.dp))
                // While protection runs, the hero is that session; otherwise it is the brand promise.
                val lead = data?.let { leadRun(it, today.app) }
                val still = Aurora.reducedMotion
                // Starting or finishing hands the hero over in one soft cross-fade, the new one rising in.
                androidx.compose.animation.AnimatedContent(
                    lead != null,
                    transitionSpec = {
                        val gentle = androidx.compose.animation.core.tween<Float>(app.aurora.ui.theme.Motion.Gentle, easing = app.aurora.ui.theme.Motion.Ease)
                        (androidx.compose.animation.fadeIn(gentle) +
                            androidx.compose.animation.slideInVertically(androidx.compose.animation.core.tween(app.aurora.ui.theme.Motion.Gentle, easing = app.aurora.ui.theme.Motion.Ease)) { if (still) 0 else it / 8 })
                            .togetherWith(androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(app.aurora.ui.theme.Motion.Quick)))
                    },
                    label = "hero",
                ) { running ->
                val now = if (running) data?.let { leadRun(it, today.app) } else null
                if (now != null) NowHero(now, data!!) else Column(Modifier.rise()) {
                    TwoToneHeadline(stringResource(R.string.today_headline), Modifier.padding(horizontal = Space.Gutter).padding(bottom = Space.XS))
                    Text(stringResource(R.string.today_description), Modifier.padding(horizontal = Space.Gutter).padding(bottom = Space.S), color = colors.textSecondary, style = AuroraType.Body)
                }
                }
            }
        }
        val d = data
        val busy = d != null && busyNow(d, today.app)
        // One filled action per screen: while something runs, starting another is secondary.
        // Saved sessions start in one tap; while one exists, it holds Home's filled action.
        val running = d?.let { today.app.runsNow(it.time).map { r -> r.configId } }.orEmpty()
        val ready = d?.rules?.readyToStart(Int.MAX_VALUE)?.filterNot { it.id in running }.orEmpty()
        // The two ways in lead, right under the promise.
        item(key = "choose") { Column(Modifier.animateItem()) { ChooseCard(onNormalUse, onCustom, primary = !busy && ready.isEmpty()) } }
        // Time Reclaimed follows at once: real time away, today, this week and overall.
        if (away !is AwayState.Loading) item(key = "metric") {
            Column(Modifier.rise().padding(horizontal = Space.M, vertical = Space.XS).fillMaxWidth().auroraSurface(colors).padding(Space.L)) {
                Text(stringResource(R.string.today_metric_label), Modifier.semantics { heading() }, color = colors.textPrimary, style = AuroraType.Title)
                Spacer(Modifier.height(Space.S))
                when (val a = away) {
                    is AwayState.Measured -> Column(verticalArrangement = Arrangement.spacedBy(Space.XS)) {
                        listOf(
                            R.string.reclaimed_today to a.reclaimed.todayMs,
                            R.string.reclaimed_week to a.reclaimed.weekMs,
                            R.string.reclaimed_overall to a.reclaimed.overallMs,
                        ).forEach { (label, ms) ->
                            Row(Modifier.fillMaxWidth().semantics(mergeDescendants = true) { }, verticalAlignment = Alignment.CenterVertically) {
                                Text(stringResource(label), Modifier.weight(1f), color = colors.textSecondary, style = AuroraType.Body)
                                app.aurora.ui.components.CountUpText(formatDuration(ms), colors.textPrimary, AuroraType.Headline)
                            }
                        }
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
                    else -> Unit
                }
            }
        }
        // Then what is running, finishing or needs attention: Home shows what matters now.
        if (d != null && d.rules.planExcess(d.plan, d.time.wall, java.time.ZoneId.systemDefault()).any) item(key = "over-free") {
            Column(Modifier.animateItem().padding(horizontal = Space.Gutter).padding(top = Space.S), verticalArrangement = Arrangement.spacedBy(Space.XS)) {
                app.aurora.ui.components.Notice(stringResource(R.string.keep_banner_title), stringResource(R.string.keep_banner_body))
                app.aurora.ui.components.AuroraButton(stringResource(R.string.keep_banner_action), onKeep, Modifier.fillMaxWidth(), kind = app.aurora.ui.components.ButtonKind.Secondary)
            }
        }
        if (d != null) homeNow(d, today.app, onOpenFocus, onOpenLegacyFocus)
        if (d != null && ready.isNotEmpty()) item(key = "ready") { Column(Modifier.animateItem()) { QuickStart(ready, d, today.app, primary = !busy) } }
        // What is scheduled, always on and recent.
        if (d != null) homeLater(d, today.app, onOpenFocus)
    }
    }
    // Content frosts under the status bar, never under the clock.
    if (!heroVisible) Box(Modifier.fillMaxWidth().frosted(glass, androidx.compose.ui.graphics.RectangleShape).background(colors.background.copy(alpha = 0.60f)).statusBarsPadding())
    // Protection starting: aurora light sweeps up the page once.
    AuroraSweep(sky == Sky.Flare, Modifier.fillMaxSize())
    } }
}
