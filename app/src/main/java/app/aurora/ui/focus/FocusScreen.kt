package app.aurora.ui.focus

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.aurora.AuroraApplication
import app.aurora.auroraViewModel
import app.aurora.data.FocusLengths
import app.aurora.data.FocusSession
import app.aurora.data.clockTicks
import app.aurora.data.formatClock
import app.aurora.data.formatDuration
import app.aurora.data.spokenDuration
import app.aurora.ui.components.AuroraButton
import app.aurora.ui.components.AuroraDialog
import app.aurora.ui.components.AuroraIconButton
import app.aurora.ui.components.AuroraIcons
import app.aurora.ui.components.AuroraTextField
import app.aurora.ui.components.ButtonKind
import app.aurora.ui.components.ChoicePills
import app.aurora.ui.components.HorizonArc
import app.aurora.ui.components.HorizonGlow
import app.aurora.ui.home.clockTime
import app.aurora.ui.theme.Aurora
import app.aurora.ui.theme.AuroraType
import app.aurora.ui.theme.Motion
import app.aurora.ui.theme.Space
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class FocusState(val now: Long, val session: FocusSession?, val defaultMinutes: Int)

class FocusViewModel(private val app: AuroraApplication) : ViewModel() {
    val state: StateFlow<FocusState?> =
        combine(app.preferences.preferences, clockTicks()) { prefs, now ->
            FocusState(now, prefs.session, prefs.defaultFocusMinutes)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun start(minutes: Int, intention: String) {
        viewModelScope.launch { app.preferences.startSession(minutes, intention) }
    }

    /** Calls [then] once the session is cleared. Leaving first would destroy this ViewModel and could cancel the write. */
    fun clear(then: () -> Unit) {
        viewModelScope.launch {
            app.preferences.clearSession()
            then()
        }
    }
}

private enum class Phase { Setup, Running, Complete }

/**
 * A local focus timer. It keeps honest time across app restarts; it does not block apps,
 * and the screen says so.
 */
@Composable
fun FocusScreen(onClose: () -> Unit) {
    val vm = auroraViewModel { FocusViewModel(it) }
    val state by vm.state.collectAsStateWithLifecycle()
    val colors = Aurora.colors
    val s = state
    if (s == null) {
        Box(Modifier.fillMaxSize().background(colors.background))
        return
    }

    val session = s.session
    val phase = when {
        session == null -> Phase.Setup
        session.isComplete(s.now) -> Phase.Complete
        else -> Phase.Running
    }
    var minutes by rememberSaveable { mutableIntStateOf(s.defaultMinutes) }
    var intention by rememberSaveable { mutableStateOf("") }
    var confirmEnd by remember { mutableStateOf(false) }

    Column(
        Modifier
            .fillMaxSize()
            .background(colors.background)
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding(),
    ) {
        Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = Space.XS), verticalAlignment = Alignment.CenterVertically) {
            AuroraIconButton(AuroraIcons.Close, "Close", onClose)
            Text(
                "Focus",
                Modifier.weight(1f).semantics { heading() },
                color = colors.textPrimary,
                style = AuroraType.BodyStrong,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.padding(24.dp))
        }

        AnimatedContent(
            targetState = phase,
            modifier = Modifier.weight(1f),
            transitionSpec = { fadeIn(tween(Motion.Gentle)) togetherWith fadeOut(tween(Motion.Standard)) },
            label = "phase",
        ) { p ->
            Column(Modifier.fillMaxSize()) {
                BoxWithConstraints(Modifier.weight(1f)) {
                    Column(
                        Modifier
                            .verticalScroll(rememberScrollState())
                            .heightIn(min = maxHeight)
                            .padding(horizontal = Space.Gutter),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        // `session` may already be cleared while an old phase animates out.
                        when (p) {
                            Phase.Setup -> Setup(minutes, { minutes = it }, intention, { intention = it })
                            Phase.Running -> session?.let { Running(it, s.now) }
                            Phase.Complete -> session?.let { Complete(it) }
                        }
                    }
                }
                Column(Modifier.padding(horizontal = Space.Gutter, vertical = Space.M)) {
                    when (p) {
                        Phase.Setup -> AuroraButton("Begin focus", { vm.start(minutes, intention) }, Modifier.fillMaxWidth())
                        Phase.Running -> AuroraButton(
                            "End session", { confirmEnd = true }, Modifier.fillMaxWidth(), kind = ButtonKind.Secondary,
                        )
                        Phase.Complete -> AuroraButton("Done", { vm.clear(onClose) }, Modifier.fillMaxWidth())
                    }
                }
            }
        }
    }

    // A session can finish while the question is open; then there's nothing left to end.
    LaunchedEffect(phase) { if (phase != Phase.Running) confirmEnd = false }
    if (confirmEnd && phase == Phase.Running && session != null) {
        AuroraDialog(
            title = "End this session?",
            body = "You've focused for ${formatDuration(session.durationMs - session.remaining(s.now))}. " +
                "Stopping early is completely fine.",
            confirmLabel = "End session",
            onConfirm = { confirmEnd = false; vm.clear(onClose) },
            dismissLabel = "Keep going",
            onDismiss = { confirmEnd = false },
        )
    }
}

/** The arc with the horizon glow rising behind it and a readout under its curve. */
@Composable
private fun ArcStage(
    progress: Float,
    glow: Float,
    warmth: Float,
    breathing: Boolean,
    complete: Boolean = false,
    readout: @Composable () -> Unit,
) {
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.BottomCenter) {
        HorizonGlow(glow, Modifier.matchParentSize(), warmth = warmth, breathing = breathing, horizon = true)
        HorizonArc(progress, Modifier.fillMaxWidth().padding(top = Space.XL), complete = complete)
        Column(Modifier.padding(bottom = Space.L), horizontalAlignment = Alignment.CenterHorizontally) { readout() }
    }
}

@Composable
private fun Setup(minutes: Int, onMinutes: (Int) -> Unit, intention: String, onIntention: (String) -> Unit) {
    val colors = Aurora.colors
    ArcStage(progress = 0f, glow = 0.45f, warmth = 0.5f, breathing = false) {
        Text("$minutes", color = colors.textPrimary, style = AuroraType.Hero)
        Text("minutes", color = colors.textSecondary, style = AuroraType.Caption)
    }
    Spacer(Modifier.height(Space.XL))
    ChoicePills(
        options = FocusLengths,
        selected = minutes,
        onSelect = onMinutes,
        fill = true,
        label = { "$it" },
        spokenLabel = { "$it minutes" },
    )
    Spacer(Modifier.height(Space.L))
    AuroraTextField(
        value = intention,
        onValueChange = { if (it.length <= 60) onIntention(it) },
        placeholder = "What's this time for? (optional)",
        capitalization = KeyboardCapitalization.Sentences,
    )
    Spacer(Modifier.height(Space.M))
    Text(
        "Your apps stay available during a session. Blocking distractions arrives in an upcoming update.",
        color = colors.textTertiary,
        style = AuroraType.Caption,
        textAlign = TextAlign.Center,
    )
}

@Composable
private fun Running(session: FocusSession, now: Long) {
    val colors = Aurora.colors
    val remaining = session.remaining(now)
    Text(
        session.intention.ifBlank { "Focus session" },
        color = colors.textPrimary,
        style = AuroraType.Title,
        textAlign = TextAlign.Center,
    )
    Spacer(Modifier.height(Space.XS))
    Text("Ends at ${clockTime(session.endsAt)}", color = colors.textSecondary, style = AuroraType.Secondary)
    Spacer(Modifier.height(Space.L))
    ArcStage(progress = session.progress(now), glow = 1f, warmth = 0.15f, breathing = true) {
        Text(
            formatClock(remaining),
            Modifier.clearAndSetSemantics { contentDescription = "${spokenDuration(remaining)} remaining" },
            color = colors.textPrimary,
            style = AuroraType.Hero,
        )
        Text("remaining", color = colors.textSecondary, style = AuroraType.Caption)
    }
    Spacer(Modifier.height(Space.XL))
    Text(
        "You can leave this screen. aurora keeps time.",
        color = colors.textTertiary,
        style = AuroraType.Caption,
        textAlign = TextAlign.Center,
    )
}

@Composable
private fun Complete(session: FocusSession) {
    val colors = Aurora.colors
    val minutes = session.durationMs / 60_000
    ArcStage(progress = 1f, glow = 1f, warmth = 1f, breathing = false, complete = true) {
        Text("$minutes", color = colors.textPrimary, style = AuroraType.Hero)
        Text("minutes focused", color = colors.textSecondary, style = AuroraType.Caption)
    }
    Spacer(Modifier.height(Space.XL))
    Text("Time well spent.", Modifier.semantics { heading() }, color = colors.textPrimary, style = AuroraType.Headline)
    Spacer(Modifier.height(Space.XS))
    Text(
        if (session.intention.isBlank()) "You gave yourself $minutes minutes of focus."
        else "You gave $minutes minutes to “${session.intention}”.",
        color = colors.textSecondary,
        style = AuroraType.Body,
        textAlign = TextAlign.Center,
    )
}
