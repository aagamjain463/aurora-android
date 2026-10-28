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
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
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
import app.aurora.data.ProtectionStatus
import app.aurora.data.clockTicks
import app.aurora.data.formatClock
import app.aurora.data.formatDuration
import app.aurora.data.spokenDuration
import app.aurora.dismissFocus
import app.aurora.plan.Allowance
import app.aurora.plan.Plan
import app.aurora.plan.canAdd
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
import app.aurora.ui.components.HorizonArc
import app.aurora.ui.components.HorizonGlow
import app.aurora.ui.components.StatusPill
import app.aurora.ui.home.clockTime
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
        Box(Modifier.fillMaxSize().background(colors.background))
        return
    }

    val session = s.session
    val phase = when {
        session == null -> Phase.Setup
        session.isOver(s.time) -> Phase.Complete
        else -> Phase.Running
    }
    val profiles = s.rules.activeProfiles(s.plan)
    var profileId by rememberSaveable { mutableStateOf(profiles.first().id) }
    val profile = profiles.find { it.id == profileId } ?: profiles.first()
    var minutes by rememberSaveable(profile.id) { mutableIntStateOf(profile.minutes) }
    var intention by rememberSaveable { mutableStateOf("") }
    var confirmEnd by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<FocusProfile?>(null) }
    var showPlus by remember { mutableStateOf(false) }
    val protection = rememberProtectionStatus(s.list)
    val haptics = LocalHapticFeedback.current
    LaunchedEffect(phase) {
        if (phase == Phase.Complete) {
            vm.settle()
            haptics.performHapticFeedback(HapticFeedbackType.Confirm)
        }
    }

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
            Spacer(Modifier.size(48.dp))
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
                            Phase.Setup -> Setup(
                                s, profiles, profile, { profileId = it.id }, minutes, { minutes = it }, intention, { intention = it },
                                protection, onEdit = { editing = it },
                                onNew = {
                                    if (canAdd(s.plan, Allowance.FocusProfiles, profiles.size)) editing = FocusProfile(UUID.randomUUID().toString(), "", 25)
                                    else showPlus = true
                                },
                            )
                            Phase.Running -> session?.let { Running(it, s, protection) }
                            Phase.Complete -> session?.let { Complete(it, s) }
                        }
                    }
                }
                Column(Modifier.padding(horizontal = Space.Gutter, vertical = Space.M)) {
                    when (p) {
                        Phase.Setup -> AuroraButton("Begin focus", {
                            haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                            vm.start(minutes, profile, intention)
                        }, Modifier.fillMaxWidth())
                        Phase.Running -> AuroraButton("End session", { confirmEnd = true }, Modifier.fillMaxWidth(), kind = ButtonKind.Secondary)
                        Phase.Complete -> AuroraButton("Done", { vm.done(onClose) }, Modifier.fillMaxWidth())
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
            body = "You've focused for ${formatDuration(session.elapsed(s.time))}. Stopping early is completely fine; it still counts as time focused.",
            confirmLabel = "End session",
            onConfirm = { confirmEnd = false; vm.endEarly(onClose) },
            dismissLabel = "Keep going",
            onDismiss = { confirmEnd = false },
        )
    }
    editing?.let { p ->
        ProfileEditor(p, s.rules, isNew = s.rules.profiles.none { it.id == p.id }, canDelete = s.rules.profiles.size > 1,
            onSave = { vm.saveProfile(it); profileId = it.id; editing = null },
            onDelete = { vm.deleteProfile(it); profileId = s.rules.profiles.first { x -> x.id != it.id }.id; editing = null },
            onDismiss = { editing = null })
    }
    if (showPlus) AuroraDialog(
        title = "One Focus profile on Free",
        body = "aurora plus adds a profile for each kind of focus, like study, reading or deep work, each with its own apps and length. Sessions themselves are unlimited on Free.",
        confirmLabel = "Got it",
        onConfirm = { showPlus = false },
        onDismiss = { showPlus = false },
    ) { AuroraButton("About aurora plus", { showPlus = false; onPlus() }, kind = ButtonKind.Quiet) }
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

/** What Focus holds back, in words, and whether Shield can actually do it. */
private fun holds(s: FocusState, groupId: String?, protection: ProtectionStatus?): String {
    val keys = s.rules.cover(groupId, s.activeKeys)
    val group = groupId?.let { id -> s.rules.groups.find { it.id == id }?.name }
    return when {
        keys.isEmpty() && s.activeKeys.isEmpty() -> "Protect some apps, and Focus will hold them back while it runs."
        keys.isEmpty() -> "${group ?: "This group"} has no protected apps yet, so nothing is held back."
        protection != ProtectionStatus.Active -> "Shield isn't on, so Focus can't hold apps back yet. Turn it on in Protect."
        else -> "Shield holds back ${group?.let { "$it · " }.orEmpty()}${keys.size} ${if (keys.size == 1) "item" else "items"} until it ends."
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
    ArcStage(progress = 0f, glow = 0.45f, warmth = 0.5f, breathing = false) {
        Text("$minutes", color = colors.textPrimary, style = AuroraType.Hero)
        Text("minutes", color = colors.textSecondary, style = AuroraType.Caption)
    }
    Spacer(Modifier.height(Space.L))
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.XS)) {
        ChoicePills(profiles, profile, onProfile, label = { it.name }, modifier = Modifier.weight(1f, fill = false))
        AuroraIconButton(AuroraIcons.Edit, "Edit ${profile.name}", { onEdit(profile) })
        AuroraIconButton(AuroraIcons.Plus, "New profile", onNew)
    }
    Spacer(Modifier.height(Space.M))
    ChoicePills(
        options = (FocusLengths + profile.minutes).distinct().sorted(),
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
    Text(holds(s, profile.groupId, protection), color = colors.textTertiary, style = AuroraType.Caption, textAlign = TextAlign.Center)
}

@Composable
private fun Running(session: Session, s: FocusState, protection: ProtectionStatus?) {
    val colors = Aurora.colors
    val remaining = session.remaining(s.time)
    StatusPill(if (protection == ProtectionStatus.Active) "Shield is holding" else "Timer only · Shield is off", live = protection == ProtectionStatus.Active)
    Spacer(Modifier.height(Space.M))
    Text(session.intention.ifBlank { s.rules.profiles.find { it.id == session.profileId }?.name ?: "Focus session" }, color = colors.textPrimary, style = AuroraType.Title, textAlign = TextAlign.Center)
    Spacer(Modifier.height(Space.XS))
    Text("Ends at ${clockTime(session.endWall(s.time))}", color = colors.textSecondary, style = AuroraType.Secondary)
    Spacer(Modifier.height(Space.L))
    ArcStage(progress = session.progress(s.time), glow = 1f, warmth = 0.15f, breathing = true) {
        Text(
            formatClock(remaining),
            Modifier.clearAndSetSemantics { contentDescription = "${spokenDuration(remaining)} remaining" },
            color = colors.textPrimary,
            style = AuroraType.Hero,
        )
        Text("remaining", color = colors.textSecondary, style = AuroraType.Caption)
    }
    Spacer(Modifier.height(Space.XL))
    Text(holds(s, session.groupId, protection), color = colors.textTertiary, style = AuroraType.Caption, textAlign = TextAlign.Center)
    Spacer(Modifier.height(Space.XS))
    Text("You can leave this screen. aurora keeps time.", color = colors.textTertiary, style = AuroraType.Caption, textAlign = TextAlign.Center)
}

@Composable
private fun Complete(session: Session, s: FocusState) {
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
        if (session.intention.isBlank()) "You gave yourself $minutes minutes of focus. It's added to your progress."
        else "You gave $minutes minutes to “${session.intention}”. It's added to your progress.",
        color = colors.textSecondary,
        style = AuroraType.Body,
        textAlign = TextAlign.Center,
    )
    s.rules.motivations.firstOrNull()?.let {
        Spacer(Modifier.height(Space.S))
        Text("More time for ${it.lowercase()}.", color = colors.dawnText, style = AuroraType.Secondary, textAlign = TextAlign.Center)
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
    AuroraDialog(
        title = if (isNew) "New profile" else "Edit profile",
        confirmLabel = "Save",
        onConfirm = { onSave(profile.copy(name = name.trim().ifBlank { "Focus" }, minutes = minutes, groupId = group)) },
        dismissLabel = "Cancel",
        onDismiss = onDismiss,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(Space.S)) {
            AuroraTextField(name, { if (it.length <= 24) name = it }, "Study, Reading, Deep work…", capitalization = KeyboardCapitalization.Words)
            ChoicePills(FocusLengths, minutes, { minutes = it }, label = { "$it" }, fill = true, spokenLabel = { "$it minutes" })
            ChoicePills(listOf<String?>(null) + rules.groups.map { it.id }, group, { group = it }, label = { id -> id?.let { g -> rules.groups.find { it.id == g }?.name } ?: "All protected" })
            if (!isNew && canDelete) AuroraButton("Delete profile", { onDelete(profile) }, kind = ButtonKind.Quiet)
        }
    }
}
