package app.aurora.ui.home

import app.aurora.progress.bound
import app.aurora.progress.reaching
import app.aurora.progress.whileBound
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.em
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
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
import app.aurora.ui.components.LitBadge
import app.aurora.ui.components.TwoToneHeadline
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.size
import androidx.compose.ui.res.pluralStringResource
import app.aurora.ui.components.AuroraButton
import app.aurora.ui.components.AuroraIconButton
import app.aurora.ui.components.AuroraIcons
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
import kotlinx.coroutines.Dispatchers
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
 * The protection between [from] and [to] that really held: only while the Shield service was bound, since
 * Accessibility off, safe mode or a dead phone isn't protection. What Time Reclaimed counts.
 */
fun heldSpans(history: History, rules: Rules, runtime: Runtime, list: ProtectedList, plan: Plan, from: Long, to: Long, now: Long, zone: ZoneId): List<ProtectedSpan> {
    val service = history.service.reaching(now, app.aurora.protect.ShieldService.connected.value)
    val bound = service.bound()
    val evidenceFrom = service.firstOrNull()?.start ?: Long.MAX_VALUE
    return protectionToday(history, rules, runtime, list, plan, from, to, zone)
        .filter { it.end > from && it.start < to && it.keys.isNotEmpty() }
        .flatMap { it.whileBound(bound, evidenceFrom) }
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
    fun spans(from: Long, to: Long) = heldSpans(history, rules, runtime, list, plan, from, to, now, zone)
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
        measuring = viewModelScope.launch(Dispatchers.Default) {
            runCatching { app.settleFocus(endNow = false) }
            runCatching { app.syncRuns() }
            runCatching { app.goalUsage(includeToday = false) }
            _away.value = app.measureReclaimed()
        }
    }
}

/**
 * Home. One hero, Start session, then Time Reclaimed: real time away from protected apps, measured
 * from Android's usage records, never the time someone set on a timer. Saved sessions have their own screen.
 */
@Composable
fun HomeScreen(
    onStart: () -> Unit,
    onOpenFocus: (runId: String) -> Unit,
    onOpenLegacyFocus: () -> Unit,
    onOpenSettings: () -> Unit,
    onEditSession: (configId: String) -> Unit = {},
    onStartSession: (configId: String) -> Unit = {},
    onOpenSaved: () -> Unit = {},
    onOpenRun: (String) -> Unit = {},
    onOpenPlus: () -> Unit = {},
) {
    var choosing by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    val today = auroraViewModel { TodayModel(it) }
    val protect = auroraViewModel { ProtectModel(it) }
    val data by protect.data.collectAsStateWithLifecycle()
    val away by today.away.collectAsStateWithLifecycle()
    val history by today.app.history.state.collectAsStateWithLifecycle()
    val minute by androidx.compose.runtime.remember { clockTicks(60_000) }.collectAsStateWithLifecycle(0L)
    val prefs by today.app.preferences.preferences.collectAsStateWithLifecycle(null)
    val entitlement by today.app.billing.subscription.collectAsStateWithLifecycle()
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

    ProvideIntro { Box(Modifier.fillMaxSize().background(colors.background)) {
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    val heroVisible by androidx.compose.runtime.remember { androidx.compose.runtime.derivedStateOf { listState.firstVisibleItemIndex == 0 } }
    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize()) {
    // A running session takes the whole first screen, above the dock; the rest is a scroll away.
    val screen = maxHeight
    val dock = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + TabBarHeight
    val landscape = maxWidth > maxHeight
    val lead = data?.let { leadRun(it, today.app) }
    LazyColumn(Modifier.align(Alignment.TopCenter).widthIn(max = ContentMaxWidth).fillMaxSize(), state = listState, contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = bottom)) {
        // The brand promise leads into setup; measurement follows the actions.
        item(key = "hero") {
            // While a session runs it is the hero, the whole first screen, centred; otherwise the brand promise leads.
            // A screen tall enough gives each part its own room; a short one (large text, small phone) simply scrolls.
            val roomy = !landscape && screen >= 600.dp
            if (lead != null) Column(
                (if (roomy) Modifier.height(screen) else Modifier.heightIn(min = screen)).statusBarsPadding(),
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                AuroraHeader(onOpenSettings)
                app.aurora.ui.session.SessionHero(
                    lead, data!!, today.app, { onOpenRun(lead.id) }, onOpenFocus, landscape = landscape, fill = roomy,
                    modifier = if (roomy) Modifier.weight(1f) else Modifier,
                )
                // The dock's height, so the hero sits at the centre of what shows, and nothing peeks below it.
                Spacer(Modifier.height(dock))
            } else Column(Modifier.statusBarsPadding()) {
                AuroraHeader(onOpenSettings)
                Spacer(Modifier.height(28.dp))
                Column(Modifier.rise().padding(horizontal = Space.Gutter)) {
                    // The main line set large on two lines, "control." (the brand's central idea) in full ink;
                    // then the promise. The copy stays exact; only setting and tone are design.
                    // Narrower screens and large text step down from full size, keeping both lines.
                    TwoToneHeadline(
                        stringResource(R.string.today_headline), Modifier.fillMaxWidth(), tailWords = 1,
                        style = AuroraType.BrandLine,
                        autoSize = androidx.compose.foundation.text.TextAutoSize.StepBased(minFontSize = 32.sp, maxFontSize = AuroraType.BrandLine.fontSize),
                    )
                    Text(stringResource(R.string.today_description), Modifier.padding(top = Space.M), color = colors.textSecondary, style = AuroraType.Body.copy(fontSize = 17.sp, lineHeight = 25.sp))
                }
            }
        }
        // Time Reclaimed: real time away, today, this week and overall.
        val metric: androidx.compose.foundation.lazy.LazyListScope.() -> Unit = { if (away !is AwayState.Loading) item(key = "metric") {
            Column(Modifier.rise().padding(horizontal = Space.Gutter).padding(top = Space.Card).fillMaxWidth().auroraSurface(colors).padding(horizontal = 24.dp, vertical = 24.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.today_metric_label), Modifier.weight(1f).semantics { heading() }, color = colors.textPrimary, style = AuroraType.Title.copy(fontSize = 15.sp))
                    if (away is AwayState.Measured) Text(stringResource(R.string.reclaimed_today), color = colors.textTertiary, style = AuroraType.Caption)
                }
                when (val a = away) {
                    is AwayState.Measured -> Column {
                        app.aurora.ui.components.CountUpText(
                            formatDuration(a.reclaimed.todayMs), colors.textPrimary,
                            AuroraType.Hero.copy(fontSize = 56.sp, lineHeight = 60.sp, letterSpacing = (-0.045).em),
                            Modifier.padding(top = 14.dp).fillMaxWidth(),
                            autoSize = androidx.compose.foundation.text.TextAutoSize.StepBased(minFontSize = 24.sp, maxFontSize = 56.sp),
                        )
                        Box(Modifier.padding(top = 18.dp).fillMaxWidth().height(1.dp).background(colors.hairline))
                        Row(Modifier.padding(top = 14.dp).fillMaxWidth()) {
                            listOf(R.string.reclaimed_week to a.reclaimed.weekMs, R.string.reclaimed_overall to a.reclaimed.overallMs).forEach { (label, ms) ->
                                Column(Modifier.weight(1f).semantics(mergeDescendants = true) { }) {
                                    Text(stringResource(label), color = colors.textTertiary, style = AuroraType.Caption)
                                    app.aurora.ui.components.CountUpText(formatDuration(ms), colors.textPrimary, AuroraType.Metric.copy(fontSize = 24.sp, lineHeight = 30.sp, fontWeight = androidx.compose.ui.text.font.FontWeight(380), letterSpacing = (-0.02).em), Modifier.padding(top = 3.dp))
                                }
                            }
                        }
                    }
                    AwayState.NoUsageAccess -> {
                        Text(stringResource(R.string.today_metric_not_measured), Modifier.padding(vertical = Space.XS), color = colors.textPrimary, style = AuroraType.Headline)
                        Text(stringResource(R.string.today_metric_no_access), color = colors.textSecondary, style = AuroraType.Secondary)
                        AuroraButton(stringResource(R.string.today_metric_allow), app.aurora.ui.protect.askFor(Access.Usage), Modifier.padding(top = Space.S), kind = ButtonKind.Secondary)
                    }
                    AwayState.Unavailable -> {
                        Text("—", color = colors.textPrimary, style = AuroraType.Metric)
                        Text(stringResource(R.string.usage_records_unavailable_body), color = colors.textSecondary, style = AuroraType.Secondary)
                    }
                    else -> Unit
                }
            }
        } }
        val d = data
        // Below a running session, the first thing a scroll finds is Time Reclaimed (founder, 2026-11-09).
        if (lead != null) metric()
        // What is running sits right under the hero's timer, before anything else (founder, 2026-11-08).
        if (d != null) homeNow(d, today.app, onOpenFocus, onOpenLegacyFocus, onOpenRun)
        // The trial's countdown while it runs, from the server's signed end (founder, 2026-11-10). [minute] redraws it.
        val trialEnd = entitlement?.takeIf { it.source == "trial" && today.app.plan == app.aurora.plan.Plan.Plus }?.paidThrough
        if (trialEnd != null) item(key = "trial") {
            val now = androidx.compose.runtime.remember(minute) { System.currentTimeMillis() }
            app.aurora.ui.plus.TrialCountdown(trialEnd, now, onOpenPlus, Modifier.rise().padding(top = Space.Card))
        }
        // Start a session: it asks New or Saved.
        // One session runs at a time: while one does, the hero is the way in, and Start steps away.
        if (d == null || leadRun(d, today.app) == null) item(key = "choose") { Column(Modifier.animateItem()) { ChooseCard { choosing = true } } }
        // Time Reclaimed follows at once.
        if (lead == null) metric()
        // What is scheduled, always on and recent.
        if (d != null) homeLater(d, today.app, onOpenFocus)
    }
    }
    if (choosing) app.aurora.ui.session.StartChooser(onNew = onStart, onSaved = onOpenSaved, onDismiss = { choosing = false })
    // Content frosts under the status bar, never under the clock.
    if (!heroVisible) Box(Modifier.fillMaxWidth().background(colors.background).statusBarsPadding())
    } }
}
