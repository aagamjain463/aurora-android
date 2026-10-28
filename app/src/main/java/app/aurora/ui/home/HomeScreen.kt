package app.aurora.ui.home

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
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
import app.aurora.data.FocusSession
import app.aurora.data.InstalledApp
import app.aurora.data.clockTicks
import app.aurora.data.formatClock
import app.aurora.data.formatDuration
import app.aurora.data.spokenDuration
import app.aurora.ui.components.AppIcon
import app.aurora.ui.components.AuroraButton
import app.aurora.ui.components.AuroraIconButton
import app.aurora.ui.components.AuroraIcons
import app.aurora.ui.components.AuroraRow
import app.aurora.ui.components.Chevron
import app.aurora.ui.components.HorizonGlow
import app.aurora.ui.components.RowGroup
import app.aurora.ui.components.SectionLabel
import app.aurora.ui.components.StatusPill
import app.aurora.ui.components.TabBarHeight
import app.aurora.ui.components.Wordmark
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
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

data class HomeState(
    val now: Long,
    val session: FocusSession?,
    val defaultMinutes: Int,
    val protectedApps: List<InstalledApp>,
    val protectedCount: Int,
    /** Null when Usage access isn't granted. */
    val screenTimeToday: Long?,
)

class HomeViewModel(private val app: AuroraApplication) : ViewModel() {
    private val installed = MutableStateFlow<List<InstalledApp>?>(null)
    private val screenTime = MutableStateFlow<Long?>(null)

    init {
        viewModelScope.launch { installed.value = app.catalog.launchableApps() }
    }

    fun refresh() {
        viewModelScope.launch {
            screenTime.value = if (app.screenTime.hasAccess()) app.screenTime.today().values.sum() else null
        }
    }

    val state: StateFlow<HomeState?> =
        combine(app.preferences.preferences, installed, screenTime, clockTicks()) { prefs, apps, time, now ->
            val chosen = apps?.filter { it.packageName in prefs.protectedApps }
            HomeState(
                now = now,
                session = prefs.session,
                defaultMinutes = prefs.defaultFocusMinutes,
                protectedApps = chosen.orEmpty(),
                protectedCount = chosen?.size ?: prefs.protectedApps.size,
                screenTimeToday = time,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
}

private enum class Phase { Idle, Running, Complete }

@Composable
fun HomeScreen(
    onStartFocus: () -> Unit,
    onOpenProtect: () -> Unit,
    onOpenInsights: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val vm = auroraViewModel { HomeViewModel(it) }
    val state by vm.state.collectAsStateWithLifecycle()
    LifecycleResumeEffect(Unit) {
        vm.refresh()
        onPauseOrDispose { }
    }
    val s = state
    if (s == null) {
        Box(Modifier.fillMaxSize().background(Aurora.colors.background))
        return
    }

    val colors = Aurora.colors
    val session = s.session
    val phase = when {
        session == null -> Phase.Idle
        session.isComplete(s.now) -> Phase.Complete
        else -> Phase.Running
    }
    val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + TabBarHeight + Space.XXL

    Column(
        Modifier
            .fillMaxSize()
            .background(colors.background)
            .verticalScroll(rememberScrollState())
            .padding(bottom = bottom),
    ) {
        Box(Modifier.fillMaxWidth()) {
            HorizonGlow(
                intensity = if (phase == Phase.Idle) 0.5f else 0.8f,
                warmth = when (phase) {
                    Phase.Idle -> 0.5f
                    Phase.Running -> 0.15f
                    Phase.Complete -> 1f
                },
                breathing = phase == Phase.Running,
                modifier = Modifier.matchParentSize(),
            )
            Column(Modifier.statusBarsPadding()) {
                Row(
                    Modifier.fillMaxWidth().height(56.dp).padding(start = Space.Gutter, end = Space.XS),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Wordmark(Modifier.weight(1f))
                    AuroraIconButton(AuroraIcons.Settings, "Settings", onOpenSettings)
                }
                Column(Modifier.padding(horizontal = Space.Gutter).padding(top = Space.XL)) {
                    StatusPill(
                        text = when (phase) {
                            Phase.Idle -> "No session running"
                            Phase.Running -> "In focus · ends ${clockTime(session!!.endsAt)}"
                            Phase.Complete -> "Session complete"
                        },
                        live = phase == Phase.Running,
                        warm = phase == Phase.Complete,
                    )
                    Spacer(Modifier.height(Space.L))
                    AnimatedContent(
                        targetState = phase,
                        transitionSpec = { fadeIn(tween(Motion.Gentle)) togetherWith fadeOut(tween(Motion.Quick)) },
                        label = "hero",
                    ) { p -> Hero(p, s) }
                    Spacer(Modifier.height(Space.XL))
                    AuroraButton(
                        text = when (phase) {
                            Phase.Idle -> "Start focus · ${s.defaultMinutes} min"
                            Phase.Running -> "Return to session"
                            Phase.Complete -> "Finish session"
                        },
                        onClick = onStartFocus,
                        modifier = Modifier.fillMaxWidth(),
                        icon = AuroraIcons.Focus,
                    )
                }
                Spacer(Modifier.height(Space.XXL + Space.L))
            }
        }

        SectionLabel("Protected apps")
        RowGroup {
            if (s.protectedCount == 0) {
                AuroraRow(
                    title = "Choose apps to protect",
                    subtitle = "Pick the apps that tend to pull you away.",
                    icon = AuroraIcons.Protect,
                    onClick = onOpenProtect,
                    trailing = { Chevron() },
                )
            } else {
                AuroraRow(
                    title = if (s.protectedCount == 1) "1 app chosen" else "${s.protectedCount} apps chosen",
                    subtitle = "Saved · blocking arrives in an update",
                    leading = { IconStack(s.protectedApps) },
                    onClick = onOpenProtect,
                    trailing = { Chevron() },
                )
            }
        }

        SectionLabel("Your day")
        RowGroup {
            val time = s.screenTimeToday
            if (time == null) {
                AuroraRow(
                    title = "See your screen time",
                    subtitle = "Allow usage access to see today's total here.",
                    icon = AuroraIcons.Insights,
                    onClick = onOpenInsights,
                    trailing = { Chevron() },
                )
            } else {
                AuroraRow(
                    title = "Screen time",
                    subtitle = "Since midnight, on this phone",
                    icon = AuroraIcons.Insights,
                    onClick = onOpenInsights,
                    trailing = {
                        Text(formatDuration(time), color = colors.textPrimary, style = AuroraType.Title)
                        Chevron()
                    },
                )
            }
        }
    }
}

@Composable
private fun Hero(phase: Phase, s: HomeState) {
    val colors = Aurora.colors
    // `session` may already be cleared while an old phase animates out.
    val session = s.session
    if (phase != Phase.Idle && session == null) return
    Column {
        when (phase) {
            Phase.Idle -> {
                Text(greeting(), Modifier.semantics { heading() }, color = colors.textPrimary, style = AuroraType.Display)
                Spacer(Modifier.height(Space.XS))
                Text(
                    "Ready when you are. A focus session sets aside time for what matters to you.",
                    color = colors.textSecondary,
                    style = AuroraType.Body,
                )
            }
            Phase.Running -> {
                val remaining = session!!.remaining(s.now)
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
