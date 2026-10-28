package app.aurora.ui.home

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.aurora.AuroraApplication
import app.aurora.auroraViewModel
import app.aurora.data.Access
import app.aurora.data.InstalledApp
import app.aurora.data.ProtectionStatus
import app.aurora.data.clockTicks
import app.aurora.data.formatClock
import app.aurora.data.formatDuration
import app.aurora.data.spokenDuration
import app.aurora.plan.Plan
import app.aurora.progress.DayStats
import app.aurora.progress.History
import app.aurora.progress.levelFor
import app.aurora.progress.light
import app.aurora.progress.on
import app.aurora.progress.steadyDays
import app.aurora.progress.title
import app.aurora.progress.totalXp
import app.aurora.protect.DeviceTime
import app.aurora.protect.ProtectedList
import app.aurora.protect.Rules
import app.aurora.protect.Runtime
import app.aurora.protect.key
import app.aurora.settleFocus
import app.aurora.settleLockdown
import app.aurora.ui.components.AppIcon
import app.aurora.ui.components.AuroraButton
import app.aurora.ui.components.AuroraIconButton
import app.aurora.ui.components.AuroraIcons
import app.aurora.ui.components.AuroraLight
import app.aurora.ui.components.AuroraRow
import app.aurora.ui.components.ButtonKind
import app.aurora.ui.components.Chevron
import app.aurora.ui.components.MetricGrid
import app.aurora.ui.components.ProgressLine
import app.aurora.ui.components.RowDivider
import app.aurora.ui.components.RowGroup
import app.aurora.ui.components.SectionLabel
import app.aurora.ui.components.StatusPill
import app.aurora.ui.components.TabBarHeight
import app.aurora.ui.components.Wordmark
import app.aurora.ui.protect.rememberProtectionStatus
import app.aurora.ui.protect.summary
import app.aurora.ui.theme.Aurora
import app.aurora.ui.theme.AuroraType
import app.aurora.ui.theme.Motion
import app.aurora.ui.theme.Space
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** Today's usage, measured on the phone. Null fields mean usage access isn't granted. */
data class UsageToday(val total: Long?, val protected: Long?)

data class HomeState(
    val time: DeviceTime,
    val runtime: Runtime,
    val rules: Rules,
    val list: ProtectedList,
    val plan: Plan,
    val history: History,
    val usage: UsageToday,
    val apps: List<InstalledApp>,
    val defaultMinutes: Int,
) {
    val today: LocalDate get() = Instant.ofEpochMilli(time.wall).atZone(ZoneId.systemDefault()).toLocalDate()
    val stats: DayStats get() = history.day(today)
}

class HomeViewModel(private val app: AuroraApplication) : ViewModel() {
    private val installed = MutableStateFlow<List<InstalledApp>>(emptyList())
    private val usage = MutableStateFlow(UsageToday(null, null))

    init {
        viewModelScope.launch { installed.value = app.catalog.launchableApps() }
    }

    fun refresh() {
        viewModelScope.launch {
            usage.value = if (!app.access.isGranted(Access.Usage)) UsageToday(null, null) else {
                val today = app.screenTime.today()
                val protectedKeys = app.protectedList.value?.active(app.plan)?.map { it.key }?.toSet().orEmpty()
                UsageToday(today.values.sum(), today.filterKeys { it in protectedKeys }.values.sum())
            }
        }
    }

    /** Records sessions that ended while aurora was closed. Idempotent. */
    fun settle() {
        viewModelScope.launch {
            runCatching {
                app.settleFocus(endNow = false)
                app.settleLockdown()
            }
        }
    }

    val state: StateFlow<HomeState?> = combine(
        combine(app.runtime.state, app.rules.state, app.protectedList, app.history.state) { r, rules, list, h -> listOf(r, rules, list, h) },
        app.billing.plan,
        usage,
        combine(installed, app.preferences.preferences) { a, p -> a to p.defaultFocusMinutes },
        clockTicks(),
    ) { parts, plan, usage, (apps, minutes), _ ->
        @Suppress("UNCHECKED_CAST")
        val runtime = parts[0] as app.aurora.data.Loaded<Runtime>?
        @Suppress("UNCHECKED_CAST")
        val rules = parts[1] as app.aurora.data.Loaded<Rules>?
        val list = parts[2] as ProtectedList?
        @Suppress("UNCHECKED_CAST")
        val history = parts[3] as app.aurora.data.Loaded<History>?
        if (runtime == null || rules == null || list == null || history == null) null
        else HomeState(app.now(), runtime.value, rules.value, list, plan, history.value, usage, apps, minutes)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
}

private enum class Phase { Idle, Running, Complete }

/**
 * Today, the emotional centre: Aurora Light drawn from real progress, whether you're protected, how the
 * day is going, and one thing to do next. A first day is beautiful and empty, never filled in.
 */
@Composable
fun HomeScreen(
    onStartFocus: () -> Unit,
    onOpenProtect: () -> Unit,
    onOpenInsights: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenLockdown: () -> Unit = {},
) {
    val vm = auroraViewModel { HomeViewModel(it) }
    val state by vm.state.collectAsStateWithLifecycle()
    LifecycleResumeEffect(Unit) {
        vm.refresh()
        vm.settle()
        onPauseOrDispose { }
    }
    val s = state
    if (s == null) {
        Box(Modifier.fillMaxSize().background(Aurora.colors.background))
        return
    }

    val colors = Aurora.colors
    val session = s.runtime.focus
    val phase = when {
        session == null -> Phase.Idle
        session.isOver(s.time) -> Phase.Complete
        else -> Phase.Running
    }
    LaunchedEffect(phase) { if (phase == Phase.Complete) vm.settle() }
    val lockdown = s.runtime.lockdown?.takeUnless { it.isOver(s.time) }
    val protection = rememberProtectionStatus(s.list)
    val light = s.history.light(s.today)
    val level = levelFor(s.history.totalXp())
    val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + TabBarHeight + Space.XXL

    Column(
        Modifier
            .fillMaxSize()
            .background(colors.background)
            .verticalScroll(rememberScrollState())
            .padding(bottom = bottom),
    ) {
        Column(Modifier.statusBarsPadding()) {
            Row(
                Modifier.fillMaxWidth().height(56.dp).padding(start = Space.Gutter, end = Space.XS),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Wordmark(Modifier.weight(1f))
                AuroraIconButton(AuroraIcons.Settings, "Settings", onOpenSettings)
            }
            AuroraLight(
                glow = if (phase == Phase.Running || lockdown != null) maxOf(light.glow, 0.7f) else light.glow,
                rise = light.rise,
                warmth = if (phase == Phase.Complete) 1f else 0.3f,
                modifier = Modifier.fillMaxWidth().height(220.dp),
                description = "Aurora Light. ${light.activeDays} of the last 7 days had progress.",
            )
            Column(Modifier.padding(horizontal = Space.Gutter)) {
                Text(
                    "${level.name.lowercase()} · level ${level.number}",
                    color = colors.textTertiary,
                    style = AuroraType.Eyebrow,
                )
                Spacer(Modifier.height(Space.S))
                AnimatedContent(
                    targetState = phase,
                    transitionSpec = { fadeIn(tween(Motion.Gentle)) togetherWith fadeOut(tween(Motion.Quick)) },
                    label = "hero",
                ) { p -> Hero(p, s, lockdown?.endWall(s.time)) }
                Spacer(Modifier.height(Space.M))
                StatusPill(
                    text = when {
                        phase == Phase.Running -> "In focus · ends ${clockTime(session!!.endWall(s.time))}"
                        lockdown != null -> "Lockdown · until ${clockTime(lockdown.endWall(s.time))}"
                        else -> protection?.summary() ?: "Checking Shield…"
                    },
                    live = phase == Phase.Running || lockdown != null || protection == ProtectionStatus.Active,
                    warm = protection != ProtectionStatus.Active && protection != ProtectionStatus.Off && phase == Phase.Idle && lockdown == null,
                )
                Spacer(Modifier.height(Space.L))
                AuroraButton(
                    text = when (phase) {
                        Phase.Idle -> "Start focus · ${s.rules.activeProfiles(s.plan).first().minutes} min"
                        Phase.Running -> "Return to session"
                        Phase.Complete -> "Finish session"
                    },
                    onClick = onStartFocus,
                    modifier = Modifier.fillMaxWidth(),
                    icon = AuroraIcons.Focus,
                )
                if (phase == Phase.Idle && lockdown == null) {
                    AuroraButton("Lockdown", onOpenLockdown, Modifier.fillMaxWidth(), kind = ButtonKind.Quiet, icon = AuroraIcons.Lock)
                }
            }
        }

        SectionLabel("Today")
        MetricGrid(
            listOf(
                s.usage.total?.let(::formatDuration) to "screen time",
                (if (s.stats.focusMinutes > 0) formatDuration(s.stats.focusMinutes * 60_000L) else "0m") to "focus",
                "${s.stats.wentBack}" to if (s.stats.wentBack == 1) "time you stepped back" else "times you stepped back",
                (if (s.stats.grantedMinutes > 0) "${s.stats.grantedMinutes}m" else "0m") to "chosen time in protected apps",
            ),
        )
        if (s.usage.total == null) {
            Text(
                "Screen time needs usage access. Insights explains why.",
                Modifier.padding(horizontal = Space.Gutter).padding(top = Space.S),
                color = colors.textTertiary,
                style = AuroraType.Caption,
            )
        }

        if (s.rules.goals.isNotEmpty()) {
            SectionLabel("Goals")
            RowGroup {
                s.rules.goals.forEachIndexed { i, goal ->
                    if (i > 0) RowDivider()
                    val day = goal.on(
                        s.stats,
                        s.usage.total?.let { (it / 60_000).toInt() },
                        s.usage.protected?.let { (it / 60_000).toInt() },
                        dayOver = false,
                    )
                    Column(Modifier.padding(Space.M).semantics(mergeDescendants = true) { }, verticalArrangement = Arrangement.spacedBy(Space.XS)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(goal.kind.title(goal.target), Modifier.weight(1f), color = colors.textPrimary, style = AuroraType.BodyStrong)
                            Text(
                                when {
                                    day.value == null -> "needs usage access"
                                    day.met == true -> "done"
                                    day.met == false -> "over today"
                                    else -> "on track"
                                },
                                color = if (day.met == true) colors.accent else colors.textSecondary,
                                style = AuroraType.Caption,
                            )
                        }
                        val v = day.value
                        if (v != null) ProgressLine(v.toFloat() / goal.target.coerceAtLeast(1), warm = day.met == false)
                    }
                }
            }
        }

        SectionLabel("Protection")
        RowGroup {
            val count = s.list.active(s.plan).size
            if (count == 0) {
                AuroraRow(
                    title = "Choose apps to protect",
                    subtitle = "Pick the apps that tend to pull you away.",
                    icon = AuroraIcons.Protect,
                    onClick = onOpenProtect,
                    trailing = { Chevron() },
                )
            } else {
                // "Protected" only when Shield is really running. Otherwise the apps are only chosen.
                val word = if (protection == ProtectionStatus.Active) "protected" else "chosen"
                val paused = s.list.paused(s.plan).size
                AuroraRow(
                    title = if (count == 1) "1 item $word" else "$count items $word",
                    subtitle = protection?.summary().orEmpty() + if (paused > 0) " · $paused paused" else "",
                    leading = { IconStack(s.apps.filter { app -> s.list.apps.contains(app.packageName) }) },
                    onClick = onOpenProtect,
                    trailing = { Chevron() },
                )
            }
            RowDivider()
            val steady = s.history.steadyDays(s.today)
            AuroraRow(
                title = "Your progress",
                subtitle = if (steady > 1) "$steady days in a row with intentional time" else "${level.intoLevel} of ${level.levelSize} XP to the next level",
                icon = AuroraIcons.Spark,
                onClick = onOpenInsights,
                trailing = { Chevron() },
            )
        }
    }
}

@Composable
private fun Hero(phase: Phase, s: HomeState, lockdownEnd: Long?) {
    val colors = Aurora.colors
    // `session` may already be cleared while an old phase animates out.
    val session = s.runtime.focus
    if (phase != Phase.Idle && session == null) return
    Column {
        when (phase) {
            Phase.Idle -> {
                Text(headline(s, lockdownEnd), Modifier.semantics { heading() }, color = colors.textPrimary, style = AuroraType.Display)
                Spacer(Modifier.height(Space.XS))
                Text(subline(s), color = colors.textSecondary, style = AuroraType.Body)
            }
            Phase.Running -> {
                val remaining = session!!.remaining(s.time)
                Text(
                    formatClock(remaining),
                    Modifier.semantics { contentDescription = "${spokenDuration(remaining)} left" },
                    color = colors.textPrimary,
                    style = AuroraType.Hero,
                )
                Text(
                    "left · ${session.intention.ifBlank { "Focus session" }}",
                    color = colors.textSecondary,
                    style = AuroraType.Body,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Phase.Complete -> {
                Text("Time well spent.", Modifier.semantics { heading() }, color = colors.textPrimary, style = AuroraType.Display)
                Spacer(Modifier.height(Space.XS))
                Text(
                    "You finished ${formatDuration(session!!.durationMs)} of focus. Take a breath before what's next.",
                    color = colors.textSecondary,
                    style = AuroraType.Body,
                )
            }
        }
    }
}

/** A calm line grounded in what actually happened today. */
private fun headline(s: HomeState, lockdownEnd: Long?): String = when {
    lockdownEnd != null -> "Locked in until ${clockTime(lockdownEnd)}."
    s.list.items.isEmpty() && s.history.days.isEmpty() -> "Make room for what matters."
    s.stats.wentBack >= 1 -> if (s.stats.wentBack == 1) "You stepped back once today." else "You stepped back ${s.stats.wentBack} times today."
    s.stats.focusMinutes > 0 -> "${formatDuration(s.stats.focusMinutes * 60_000L)} of focus today."
    else -> greeting()
}

private fun subline(s: HomeState): String {
    val motivation = s.rules.motivations.firstOrNull()?.lowercase()
    return when {
        motivation != null -> "Your attention is yours. More of it for $motivation."
        s.list.items.isEmpty() -> "Choose what pulls you away, and aurora gives you a moment to decide."
        else -> "Your attention is yours. A focus session sets aside time for what matters."
    }
}

@Composable
private fun IconStack(apps: List<InstalledApp>) {
    Box {
        apps.take(3).forEachIndexed { i, app ->
            AppIcon(app, size = 28.dp, modifier = Modifier.padding(start = (i * 16).dp))
        }
    }
}

private fun greeting(): String = when (LocalTime.now().hour) {
    in 5..11 -> "Good morning."
    in 12..17 -> "Good afternoon."
    else -> "Good evening."
}

fun clockTime(epochMs: Long): String =
    DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).format(Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()))
