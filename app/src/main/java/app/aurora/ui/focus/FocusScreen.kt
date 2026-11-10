package app.aurora.ui.focus

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.material3.Icon
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.composed
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.text.input.ImeAction
import app.aurora.ui.components.chipText
import app.aurora.ui.components.minutesSpoken
import app.aurora.ui.theme.Radius
import app.aurora.ui.theme.auroraTween
import kotlin.math.roundToInt
import androidx.compose.ui.unit.sp
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.semantics.clearAndSetSemantics
import app.aurora.ui.components.glassControl
import androidx.compose.foundation.border
import app.aurora.ui.components.ArtTier
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
import androidx.compose.animation.core.animateFloat
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.ui.res.stringResource
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
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.aurora.R
import app.aurora.AuroraApplication
import app.aurora.auroraViewModel
import app.aurora.data.FocusLengths
import app.aurora.data.ProtectionStatus
import app.aurora.data.clockTicks
import app.aurora.data.formatClock
import app.aurora.data.formatDuration
import app.aurora.data.spokenDuration
import app.aurora.dismissFocus
import app.aurora.plan.Plan
import app.aurora.protect.DeviceTime
import app.aurora.protect.FocusProfile
import app.aurora.protect.ProtectedList
import app.aurora.protect.Rules
import app.aurora.protect.Session
import app.aurora.protect.cover
import app.aurora.protect.key
import app.aurora.settleFocus
import app.aurora.startFocus
import app.aurora.ui.components.AuroraButton
import app.aurora.ui.components.AuroraDialog
import app.aurora.ui.components.AuroraIconButton
import app.aurora.ui.components.AuroraIcons
import app.aurora.ui.components.AuroraTextField
import app.aurora.ui.components.ButtonKind
import app.aurora.ui.components.ChoicePills
import app.aurora.ui.components.clockTime
import app.aurora.ui.components.FocusTimer
import app.aurora.ui.components.Eyebrow
import app.aurora.ui.components.StatusPill
import app.aurora.ui.protect.rememberProtectionStatus
import app.aurora.ui.theme.Aurora
import app.aurora.ui.theme.AuroraType
import app.aurora.ui.theme.Motion
import app.aurora.ui.theme.Space
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.IOException
import java.util.UUID

data class FocusState(
    val time: DeviceTime,
    val session: Session?,
    val rules: Rules,
    val list: ProtectedList,
    val plan: Plan,
    val defaultMinutes: Int,
) {
    val activeKeys: Set<String> get() = list.active(plan).map { it.key }.toSet()
}

class FocusViewModel(private val app: AuroraApplication) : ViewModel() {
    val state: StateFlow<FocusState?> = combine(
        combine(app.runtime.state, app.rules.state, app.protectedList) { r, rules, list -> Triple(r, rules, list) },
        app.billing.plan,
        app.preferences.preferences,
        clockTicks(),
    ) { (runtime, rules, list), plan, prefs, _ ->
        if (runtime == null || rules == null || list == null) null
        else FocusState(app.now(), runtime.value.focus, rules.value, list, plan, prefs.defaultFocusMinutes)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun start(minutes: Int, profile: FocusProfile, intention: String) = write {
        app.startFocus(minutes, profile.id, profile.groupId, intention)
    }

    /** Counts a session that just ended, once (it stays on screen until the person closes it). */
    fun settle() = write { app.settleFocus(endNow = false) }

    fun endEarly(then: () -> Unit) = write {
        app.settleFocus(endNow = true)
        then()
    }

    /** Calls [then] once the summary is closed. Leaving first would destroy this ViewModel and could cancel the write. */
    fun done(then: () -> Unit) = write {
        app.dismissFocus()
        then()
    }

    fun saveProfile(profile: FocusProfile) = write {
        app.rules.update { r -> r.copy(profiles = if (r.profiles.any { it.id == profile.id }) r.profiles.map { if (it.id == profile.id) profile else it } else r.profiles + profile) }
    }

    fun deleteProfile(profile: FocusProfile) = write {
        app.rules.update { r -> if (r.profiles.size <= 1) r else r.copy(profiles = r.profiles.filterNot { it.id == profile.id }) }
    }

    private fun write(block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                block()
            } catch (_: IOException) {
                // The screen reads the stored session back, so it keeps showing the truth.
            }
        }
    }
}

private enum class Phase { Setup, Running, Complete }

/**
 * Focus: time set aside, with Shield holding the profile's apps back until it ends. A session is a stored
 * timestamp on the monotonic clock, so it survives leaving, process death and (on the wall clock) restarts.
 */
@Composable
fun FocusScreen(onClose: () -> Unit, onPlus: () -> Unit = {}) {
    val vm = auroraViewModel { FocusViewModel(it) }
    val state by vm.state.collectAsStateWithLifecycle()
    val colors = Aurora.colors
    val s = state
    if (s == null) {
        app.aurora.ui.components.LoadingSurface()
        return
    }

    val session = s.session
    val phase = when {
        session == null -> Phase.Setup
        session.isOver(s.time) -> Phase.Complete
        else -> Phase.Running
    }
    val profiles = s.rules.profiles
    var profileId by rememberSaveable { mutableStateOf(profiles.first().id) }
    val profile = profiles.find { it.id == profileId } ?: profiles.first()
    var minutes by rememberSaveable(profile.id) { mutableIntStateOf(profile.minutes) }
    var intention by rememberSaveable { mutableStateOf("") }
    var confirmEnd by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<FocusProfile?>(null) }
    val protection = rememberProtectionStatus(s.list)
    val haptics = LocalHapticFeedback.current
    LaunchedEffect(phase) {
        if (phase == Phase.Complete) {
            vm.settle()
            haptics.performHapticFeedback(HapticFeedbackType.Confirm)
        }
    }

    val levelTarget = if (phase == Phase.Running && session != null) {
        (session.remaining(s.time).toFloat() / session.durationMs.coerceAtLeast(1L)).coerceIn(0f, 1f)
    } else 0f

    Box(Modifier.fillMaxSize().background(colors.background)) {
    FocusLevel(levelTarget)
    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding(),
    ) {
        Box(Modifier.fillMaxWidth().height(60.dp).padding(start = Space.XS + 2.dp), contentAlignment = Alignment.CenterStart) {
            AuroraIconButton(if (phase == Phase.Setup) AuroraIcons.Close else AuroraIcons.Back, stringResource(R.string.action_close), onClose)
            // A running session shows only its goal and its timer: no title.
            if (phase == Phase.Complete) Text(
                stringResource(R.string.focus_name), Modifier.align(Alignment.Center).semantics { heading() },
                color = colors.textPrimary, style = AuroraType.Bar,
            )
        }

        AnimatedContent(
            targetState = phase,
            modifier = Modifier.weight(1f),
            transitionSpec = { fadeIn(tween(Motion.Gentle)) togetherWith fadeOut(tween(Motion.Standard)) },
            label = "phase",
        ) { p ->
            Column(Modifier.fillMaxSize()) {
                // A running session fills the room: its name at the top, the time just above the middle.
                if (p == Phase.Running) Column(Modifier.weight(1f).fillMaxWidth().padding(horizontal = Space.Gutter)) {
                    session?.let { Running(it, s) }
                } else BoxWithConstraints(Modifier.weight(1f)) {
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
                            Phase.Setup -> Setup(
                                s, profiles, profile, { profileId = it.id }, minutes, { minutes = it }, intention, { intention = it },
                                protection, onEdit = { editing = it },
                                onNew = { editing = FocusProfile(UUID.randomUUID().toString(), "", 25) },
                            )
                            Phase.Running -> Unit
                            Phase.Complete -> session?.let { Complete(it, s) }
                        }
                    }
                }
                Column(Modifier.padding(horizontal = Space.Gutter, vertical = Space.M)) {
                    when (p) {
                        Phase.Setup -> AuroraButton(stringResource(R.string.focus_begin_focus), {
                            haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                            vm.start(minutes, profile, intention)
                        }, Modifier.fillMaxWidth(), icon = AuroraIcons.Play)
                        Phase.Running -> AuroraButton(stringResource(R.string.focus_end_session), { confirmEnd = true }, Modifier.fillMaxWidth(), kind = ButtonKind.Secondary)
                        Phase.Complete -> AuroraButton(stringResource(R.string.settings_done), { vm.done(onClose) }, Modifier.fillMaxWidth())
                    }
                }
            }
        }
    }
    }

    // A session can finish while the question is open; then there's nothing left to end.
    LaunchedEffect(phase) { if (phase != Phase.Running) confirmEnd = false }
    if (confirmEnd && phase == Phase.Running && session != null) {
        AuroraDialog(
            title = stringResource(R.string.focus_end_this_session),
            body = stringResource(R.string.focus_end_body , formatDuration(session.elapsed(s.time))),
            confirmLabel = stringResource(R.string.focus_end_session),
            onConfirm = { confirmEnd = false; vm.endEarly(onClose) },
            dismissLabel = stringResource(R.string.focus_keep_going),
            onDismiss = { confirmEnd = false },
        )
    }
    editing?.let { p ->
        ProfileEditor(p, s.rules, isNew = s.rules.profiles.none { it.id == p.id }, canDelete = s.rules.profiles.size > 1,
            onSave = { vm.saveProfile(it); profileId = it.id; editing = null },
            onDelete = { vm.deleteProfile(it); profileId = s.rules.profiles.first { x -> x.id != it.id }.id; editing = null },
            onDismiss = { editing = null })
    }
}

/** Why Focus can't hold anything back, if it can't; nothing when all is well. */
@Composable
private fun holds(s: FocusState, groupId: String?, protection: ProtectionStatus?): String? {
    val keys = s.rules.cover(groupId, s.activeKeys)
    val group = groupId?.let { id -> s.rules.groups.find { it.id == id }?.name }
    return when {
        keys.isEmpty() && s.activeKeys.isEmpty() -> null
        keys.isEmpty() -> stringResource(R.string.focus_group_empty , group ?: stringResource(R.string.focus_this_group))
        protection != ProtectionStatus.Active -> stringResource(R.string.focus_holds_off)
        else -> null
    }
}

@Composable
private fun Setup(
    s: FocusState,
    profiles: List<FocusProfile>,
    profile: FocusProfile,
    onProfile: (FocusProfile) -> Unit,
    minutes: Int,
    onMinutes: (Int) -> Unit,
    intention: String,
    onIntention: (String) -> Unit,
    protection: ProtectionStatus?,
    onEdit: (FocusProfile) -> Unit,
    onNew: () -> Unit,
) {
    val colors = Aurora.colors
    var custom by remember { mutableStateOf(false) }
    Text(stringResource(R.string.focus_name), Modifier.semantics { heading() }, color = colors.textPrimary, style = AuroraType.DisplayS)
    Row(Modifier.padding(top = Space.XXS), verticalAlignment = Alignment.CenterVertically) {
        LightTabs(profiles, profile, onProfile, label = { it.name }, modifier = Modifier.weight(1f, fill = false))
        // Lifted to the words' line, above the tabs' point of light.
        QuietIcon(AuroraIcons.Edit, stringResource(R.string.focus_edit_profile_label, profile.name), Modifier.padding(bottom = 10.dp)) { onEdit(profile) }
        QuietIcon(AuroraIcons.Plus, stringResource(R.string.focus_new_profile), Modifier.padding(bottom = 10.dp), onNew)
    }
    // At rest the timer shows the length chosen, on the ring the session will run around. Turn around it to set it.
    FocusTimer(
        minutes.coerceAtMost(DialMinutes) / DialMinutes.toFloat(), "$minutes",
        Modifier.padding(top = Space.XL).lengthInput(minutes, onMinutes),
        unit = stringResource(R.string.duration_minutes),
        caption = stringResource(R.string.focus_ends_at, clockTime(System.currentTimeMillis() + minutes * 60_000L)),
        spoken = app.aurora.ui.components.minutesText(minutes),
        follow = tween(Motion.Standard, easing = Motion.Ease),
    )
    val customLabel = stringResource(R.string.duration_custom)
    LightTabs(
        FocusLengths + Custom, if (minutes in FocusLengths) minutes else Custom,
        { if (it == Custom) custom = true else onMinutes(it) },
        label = { if (it == Custom) customLabel else chipText(it) },
        spoken = { if (it == Custom) customLabel else minutesSpoken(it) },
        modifier = Modifier.padding(top = Space.L),
    )
    GoalField(intention, onIntention, Modifier.padding(top = Space.L))
    holds(s, profile.groupId, protection)?.let {
        Text(it, Modifier.padding(top = Space.M), color = colors.textTertiary, style = AuroraType.Caption, textAlign = TextAlign.Center)
    }
    if (custom) {
        var typed by remember { mutableStateOf<Int?>(minutes) }
        AuroraDialog(
            title = stringResource(R.string.focus_length_prompt),
            confirmLabel = stringResource(R.string.action_save),
            onConfirm = { typed?.let(onMinutes); custom = false },
            confirmEnabled = typed != null,
            dismissLabel = stringResource(R.string.action_cancel),
            onDismiss = { custom = false },
        ) {
            app.aurora.ui.components.DurationPicker(typed, { typed = it }, emptyList(), max = 8 * 60)
        }
    }
}

/** The ring's scale while choosing: once round is two hours. */
private const val DialMinutes = 120
private const val Custom = -1

/**
 * Turning a finger around the ring sets the length to where it points, clockwise from the top, in 5-minute steps,
 * with a tick under the finger at each one. It never jumps across the top: past two hours it stays at two hours.
 * TalkBack adjusts it as a slider.
 */
private fun Modifier.lengthInput(minutes: Int, onMinutes: (Int) -> Unit): Modifier = composed {
    val haptics = LocalHapticFeedback.current
    val now by rememberUpdatedState(minutes)
    val set by rememberUpdatedState(onMinutes)
    semantics {
        progressBarRangeInfo = ProgressBarRangeInfo(minutes.toFloat(), 5f..DialMinutes.toFloat(), steps = DialMinutes / 5 - 2)
        setProgress { set((it / 5).roundToInt().times(5).coerceIn(5, DialMinutes)); true }
    }.pointerInput(Unit) {
        var last = now
        fun turn(p: androidx.compose.ui.geometry.Offset) {
            val deg = Math.toDegrees(kotlin.math.atan2((p.x - size.width / 2f).toDouble(), (size.height / 2f - p.y).toDouble()))
            val m = (((deg + 360) % 360 / 360 * DialMinutes / 5).roundToInt() * 5).coerceIn(5, DialMinutes)
            // A jump of more than half the ring is the finger crossing the top: hold the end it came from.
            if (m == last || kotlin.math.abs(m - last) > DialMinutes / 2) return
            last = m
            set(m)
            haptics.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick)
        }
        detectDragGestures(onDragStart = { last = now; turn(it) }) { change, _ ->
            change.consume()
            turn(change.position)
        }
    }
}

/** Choices as words, the chosen one marked by a small dot under it. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun <T> LightTabs(
    options: List<T>,
    selected: T,
    onSelect: (T) -> Unit,
    label: (T) -> String,
    modifier: Modifier = Modifier,
    spoken: (T) -> String = label,
) {
    val colors = Aurora.colors
    val haptics = LocalHapticFeedback.current
    // Large text wraps the choices onto a second line, centred, rather than sliding them off the edge.
    androidx.compose.foundation.layout.FlowRow(modifier, horizontalArrangement = Arrangement.Center) {
        options.forEach { o ->
            val on = o == selected
            val tint by animateColorAsState(if (on) colors.textPrimary else colors.textTertiary, auroraTween(), label = "tab")
            val dot by animateFloatAsState(if (on) 1f else 0f, auroraTween(), label = "dot")
            Column(
                Modifier
                    .heightIn(min = 48.dp)
                    .clip(Radius.Pill)
                    .selectable(on, role = Role.RadioButton, onClick = {
                        if (!on) haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                        onSelect(o)
                    })
                    .semantics { contentDescription = spoken(o) }
                    .padding(horizontal = 11.dp, vertical = Space.XS),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(label(o), color = tint, style = AuroraType.Choice, maxLines = 1)
                Box(
                    Modifier.padding(top = 6.dp).size(4.dp)
                        .graphicsLayer { alpha = dot; scaleX = dot; scaleY = dot }
                        .clip(CircleShape).background(colors.textPrimary),
                )
            }
        }
    }
}

/** A small icon action that sits beside words without outweighing them. */
@Composable
private fun QuietIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(modifier.size(44.dp).clip(CircleShape).clickable(role = Role.Button, onClickLabel = label, onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(icon, label, tint = Aurora.colors.textTertiary, modifier = Modifier.size(17.dp))
    }
}

/**
 * The goal, typed in the voice it will be shown in while the session runs: the serif's italic, centred over a
 * hairline that lights up while you write.
 */
@Composable
private fun GoalField(value: String, onValueChange: (String) -> Unit, modifier: Modifier = Modifier) {
    val colors = Aurora.colors
    val focus = LocalFocusManager.current
    var focused by remember { mutableStateOf(false) }
    val line by animateColorAsState(if (focused) colors.textSecondary else colors.hairlineStrong, auroraTween(), label = "line")
    val style = AuroraType.Headline.copy(textAlign = TextAlign.Center, color = colors.textPrimary)
    BasicTextField(
        value, { if (it.length <= 60) onValueChange(it) },
        modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused },
        textStyle = style,
        singleLine = true,
        cursorBrush = SolidColor(colors.accent),
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { focus.clearFocus() }),
        decorationBox = { inner ->
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.fillMaxWidth().heightIn(min = 44.dp), contentAlignment = Alignment.Center) {
                    if (value.isEmpty()) Text(stringResource(R.string.cfg_focus_goal_placeholder), color = colors.textTertiary, style = style.copy(color = colors.textTertiary))
                    inner()
                }
                Box(Modifier.padding(top = Space.XS).fillMaxWidth(0.7f).height(1.dp).background(line))
            }
        },
    )
}

@Composable
private fun androidx.compose.foundation.layout.ColumnScope.Running(session: Session, s: FocusState) = FocusLevelRunning(
    s.rules.profiles.find { it.id == session.profileId }?.name, session.intention, session.remaining(s.time), session.endWall(s.time),
)

@Composable
private fun Complete(session: Session, s: FocusState) =
    FocusLevelDone((session.durationMs / 60_000).toInt(), session.intention, s.rules.motivations.firstOrNull())

/**
 * The level: the time left, as water rising from the bottom of the page, below the top bar. It rises to full when a
 * session begins, sinks as it runs and is gone when it ends. Two swells drift across its surface at different speeds,
 * the near one with a lit crest (founder, 2026-11-08: water that moves). Solid fills, no gradient; with reduced
 * motion the surface lies flat and still. Put it first inside the page's Box, behind everything. Shared by both Focus screens.
 */
@Composable
internal fun FocusLevel(target: Float) {
    val colors = Aurora.colors
    val reduced = Aurora.reducedMotion
    val level = remember { androidx.compose.animation.core.Animatable(target) }
    LaunchedEffect(target) {
        // Beginning and ending move it the whole way, on the modal curve; a running clock glides with each second.
        val whole = kotlin.math.abs(target - level.targetValue) > 0.05f
        level.animateTo(
            target,
            when {
                reduced -> androidx.compose.animation.core.snap()
                whole -> tween(Motion.Gentle, easing = Motion.Ease)
                else -> tween(1000, easing = androidx.compose.animation.core.LinearEasing)
            },
        )
    }
    // White by night, ink by day, both faint: clearly water, and the time over it keeps its contrast.
    val water = if (colors.isDark) androidx.compose.ui.graphics.Color.White.copy(alpha = 0.11f) else androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.07f)
    val crest = if (colors.isDark) androidx.compose.ui.graphics.Color.White.copy(alpha = 0.38f) else androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.22f)
    // Read only while drawing, so the swells redraw each frame without recomposing the page.
    val waves = androidx.compose.animation.core.rememberInfiniteTransition(label = "water")
    val near = waves.animateFloat(0f, TwoPi, androidx.compose.animation.core.infiniteRepeatable(tween(4600, easing = androidx.compose.animation.core.LinearEasing)), label = "near")
    val far = waves.animateFloat(TwoPi, 0f, androidx.compose.animation.core.infiniteRepeatable(tween(7400, easing = androidx.compose.animation.core.LinearEasing)), label = "far")
    Column(Modifier.fillMaxSize()) {
        Spacer(Modifier.statusBarsPadding().height(60.dp))
        Box(
            Modifier.weight(1f).fillMaxWidth().drawBehind {
                val l = level.value
                if (l <= 0f) return@drawBehind
                val surface = size.height * (1f - l)
                // The swells settle as the water drains away, so the last of it leaves calmly.
                val calm = if (reduced) 0f else (l * 8f).coerceAtMost(1f)
                val step = 4.dp.toPx()
                fun swell(phase: Float, amp: Float, length: Float, closed: Boolean) = androidx.compose.ui.graphics.Path().apply {
                    var x = 0f
                    moveTo(0f, surface + amp * kotlin.math.sin(phase))
                    while (x < size.width) {
                        x = (x + step).coerceAtMost(size.width)
                        lineTo(x, surface + amp * kotlin.math.sin(TwoPi * x / length + phase))
                    }
                    if (closed) { lineTo(size.width, size.height); lineTo(0f, size.height); close() }
                }
                val farPhase = if (reduced) 0f else far.value
                val nearPhase = if (reduced) 0f else near.value
                drawPath(swell(farPhase, 6.dp.toPx() * calm, size.width * 1.35f, true), water)
                drawPath(swell(nearPhase, 8.dp.toPx() * calm, size.width * 0.85f, true), water)
                drawPath(
                    swell(nearPhase, 8.dp.toPx() * calm, size.width * 0.85f, false), crest,
                    style = androidx.compose.ui.graphics.drawscope.Stroke(1.5.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round),
                )
            },
        )
    }
}

private const val TwoPi = (2 * Math.PI).toFloat()

/**
 * A running Focus over the level: its name and goal at the top; the time, large and bare, just above the middle.
 * No ring: the level behind the page is the progress.
 */
@Composable
internal fun androidx.compose.foundation.layout.ColumnScope.FocusLevelRunning(name: String?, goal: String, remainingMs: Long, endWall: Long) {
    val colors = Aurora.colors
    val clock = formatClock(remainingMs)
    // The seconds sit small and quiet beside the rest, so the eye reads minutes.
    val split = clock.lastIndexOf(':')
    val lead = if (split > 0) clock.substring(0, split) else clock
    val tail = if (split > 0) clock.substring(split + 1) else null
    Column(Modifier.fillMaxWidth().padding(top = Space.L), horizontalAlignment = Alignment.CenterHorizontally) {
        name?.let { app.aurora.ui.components.Eyebrow(it, Modifier.padding(bottom = Space.XS), textAlign = TextAlign.Center) }
        if (goal.isNotBlank()) app.aurora.ui.components.FocusGoal(goal)
    }
    // Room above and below the time is 1 : 1.25, so it rests a little above the middle.
    Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = androidx.compose.ui.BiasAlignment(0f, -0.11f)) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            val spoken = stringResource(R.string.focus_spoken_remaining, spokenDuration(remainingMs))
            Row(Modifier.clearAndSetSemantics { contentDescription = spoken }, verticalAlignment = Alignment.Bottom) {
                app.aurora.ui.components.RollingClock(lead, colors.textPrimary, AuroraType.Timer, Modifier.weight(1f, fill = false).alignByBaseline())
                tail?.let {
                    app.aurora.ui.components.RollingClock(it, colors.textTertiary, AuroraType.TimerTail, Modifier.padding(start = 6.dp).alignByBaseline())
                }
            }
            Text(
                stringResource(R.string.focus_ends_at, clockTime(endWall)), Modifier.padding(top = Space.XXS),
                color = colors.textSecondary, style = AuroraType.Caption, textAlign = TextAlign.Center,
            )
        }
    }
}

/** A finished Focus: the level has drained, leaving a clear page, a quiet mark and the time given. */
@Composable
internal fun FocusLevelDone(minutes: Int, goal: String, motivation: String?) {
    val colors = Aurora.colors
    Box(Modifier.size(56.dp).glassControl(CircleShape), contentAlignment = Alignment.Center) {
        Icon(AuroraIcons.Check, null, tint = colors.textPrimary, modifier = Modifier.size(24.dp))
    }
    Text(
        "$minutes", Modifier.padding(top = Space.L + 4.dp),
        color = colors.textPrimary, style = AuroraType.Timer, textAlign = TextAlign.Center,
    )
    Text(stringResource(R.string.focus_minutes_focused), color = colors.textSecondary, style = AuroraType.Caption, textAlign = TextAlign.Center)
    Text(
        stringResource(R.string.focus_time_well_spent), Modifier.padding(top = 40.dp).semantics { heading() },
        color = colors.textPrimary, style = AuroraType.Display, textAlign = TextAlign.Center,
    )
    Spacer(Modifier.height(Space.S))
    Text(
        if (goal.isBlank()) stringResource(R.string.focus_complete_body, app.aurora.ui.components.minutesText(minutes))
        else stringResource(R.string.focus_complete_intention, app.aurora.ui.components.minutesText(minutes), goal),
        color = colors.textSecondary,
        style = AuroraType.Body,
        textAlign = TextAlign.Center,
    )
    motivation?.let {
        Spacer(Modifier.height(Space.S))
        Text(stringResource(R.string.focus_more_time_for, it.lowercase()), color = colors.textTertiary, style = AuroraType.Secondary, textAlign = TextAlign.Center)
    }
}

@Composable
private fun ProfileEditor(
    profile: FocusProfile,
    rules: Rules,
    isNew: Boolean,
    canDelete: Boolean,
    onSave: (FocusProfile) -> Unit,
    onDelete: (FocusProfile) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by rememberSaveable(profile.id) { mutableStateOf(profile.name) }
    var minutes by rememberSaveable(profile.id) { mutableIntStateOf(profile.minutes) }
    var group by remember(profile.id) { mutableStateOf(profile.groupId) }
    val defaultName = stringResource(R.string.focus_name)
    val allProtected = stringResource(R.string.focus_all_protected)
    AuroraDialog(
        title = if (isNew) stringResource(R.string.focus_new_profile) else stringResource(R.string.focus_edit_profile),
        confirmLabel = stringResource(R.string.action_save),
        onConfirm = { onSave(profile.copy(name = name.trim().ifBlank { defaultName }, minutes = minutes, groupId = group)) },
        dismissLabel = stringResource(R.string.action_cancel),
        onDismiss = onDismiss,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(Space.S)) {
            AuroraTextField(name, { if (it.length <= 24) name = it }, stringResource(R.string.focus_study_reading_deep_work), capitalization = KeyboardCapitalization.Words)
            ChoicePills(FocusLengths, minutes, { minutes = it }, label = { "$it" }, fill = true, spokenLabel = { "$it minutes" })
            ChoicePills(listOf<String?>(null) + rules.groups.map { it.id }, group, { group = it }, label = { id -> id?.let { g -> rules.groups.find { it.id == g }?.name } ?: allProtected })
            if (!isNew && canDelete) AuroraButton(stringResource(R.string.focus_delete_profile), { onDelete(profile) }, kind = ButtonKind.Quiet)
        }
    }
}
