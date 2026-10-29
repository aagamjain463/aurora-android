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
import app.aurora.ui.components.clockTime
import app.aurora.ui.components.HorizonArc
import app.aurora.ui.components.HorizonGlow
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
            AuroraIconButton(AuroraIcons.Close, stringResource(R.string.action_close), onClose)
            Text(
                stringResource(R.string.focus_name),
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
                        Phase.Setup -> AuroraButton(stringResource(R.string.focus_begin_focus), {
                            haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                            vm.start(minutes, profile, intention)
                        }, Modifier.fillMaxWidth())
                        Phase.Running -> AuroraButton(stringResource(R.string.focus_end_session), { confirmEnd = true }, Modifier.fillMaxWidth(), kind = ButtonKind.Secondary)
                        Phase.Complete -> AuroraButton(stringResource(R.string.settings_done), { vm.done(onClose) }, Modifier.fillMaxWidth())
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
    if (showPlus) AuroraDialog(
        title = stringResource(R.string.focus_one_focus_profile_on_free),
        body = stringResource(R.string.focus_aurora_plus_adds_a_profile_for_each_kind_of_focus_like_study_rea),
        confirmLabel = stringResource(R.string.settings_got_it),
        onConfirm = { showPlus = false },
        onDismiss = { showPlus = false },
    ) { AuroraButton(stringResource(R.string.insights_about_aurora_plus), { showPlus = false; onPlus() }, kind = ButtonKind.Quiet) }
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
        if (Aurora.colors.isDark) app.aurora.ui.components.AuroraLandscape(Modifier.matchParentSize())
        HorizonGlow(glow, Modifier.matchParentSize(), warmth = warmth, breathing = breathing, horizon = true)
        HorizonArc(progress, Modifier.fillMaxWidth().padding(top = Space.XL), complete = complete)
        Column(Modifier.padding(bottom = Space.L), horizontalAlignment = Alignment.CenterHorizontally) { readout() }
    }
}

/** What Focus holds back, in words, and whether Shield can actually do it. */
@Composable
private fun holds(s: FocusState, groupId: String?, protection: ProtectionStatus?): String {
    val keys = s.rules.cover(groupId, s.activeKeys)
    val group = groupId?.let { id -> s.rules.groups.find { it.id == id }?.name }
    return when {
        keys.isEmpty() && s.activeKeys.isEmpty() -> stringResource(R.string.focus_holds_empty)
        keys.isEmpty() -> stringResource(R.string.focus_group_empty , group ?: stringResource(R.string.focus_this_group))
        protection != ProtectionStatus.Active -> stringResource(R.string.focus_holds_off)
        else -> stringResource(R.string.focus_holds_on , group?.let { "$it · " }.orEmpty(), keys.size)
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
        Text(app.aurora.ui.components.minutesText(minutes), color = colors.textPrimary, style = AuroraType.Hero)
        Text(androidx.compose.ui.res.stringResource(app.aurora.R.string.focus_length_caption), color = colors.textSecondary, style = AuroraType.Caption)
    }
    Spacer(Modifier.height(Space.L))
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.XS)) {
        ChoicePills(profiles, profile, onProfile, label = { it.name }, modifier = Modifier.weight(1f, fill = false))
        AuroraIconButton(AuroraIcons.Edit, stringResource(R.string.focus_edit_profile_label , profile.name), { onEdit(profile) })
        AuroraIconButton(AuroraIcons.Plus, stringResource(R.string.focus_new_profile), onNew)
    }
    Spacer(Modifier.height(Space.M))
    app.aurora.ui.components.DurationPicker(
        minutes, { it?.let(onMinutes) }, FocusLengths,
        Modifier.fillMaxWidth(),
        title = androidx.compose.ui.res.stringResource(app.aurora.R.string.focus_length_prompt),
        max = 8 * 60,
    )
    Spacer(Modifier.height(Space.L))
    AuroraTextField(
        value = intention,
        onValueChange = { if (it.length <= 60) onIntention(it) },
        placeholder = stringResource(R.string.focus_what_s_this_time_for_optional),
        capitalization = KeyboardCapitalization.Sentences,
    )
    Spacer(Modifier.height(Space.M))
    Text(holds(s, profile.groupId, protection), color = colors.textTertiary, style = AuroraType.Caption, textAlign = TextAlign.Center)
}

@Composable
private fun Running(session: Session, s: FocusState, protection: ProtectionStatus?) {
    val colors = Aurora.colors
    val remaining = session.remaining(s.time)
    val spokenRemaining = stringResource(R.string.focus_spoken_remaining , spokenDuration(remaining))
    StatusPill(if (protection == ProtectionStatus.Active) stringResource(R.string.focus_shield_is_holding) else stringResource(R.string.focus_timer_only_shield_is_off), live = protection == ProtectionStatus.Active)
    Spacer(Modifier.height(Space.M))
    Text(session.intention.ifBlank { s.rules.profiles.find { it.id == session.profileId }?.name ?: stringResource(R.string.focus_focus_session) }, color = colors.textPrimary, style = AuroraType.Title, textAlign = TextAlign.Center)
    Spacer(Modifier.height(Space.XS))
    Text(stringResource(R.string.focus_ends_at , clockTime(session.endWall(s.time))), color = colors.textSecondary, style = AuroraType.Secondary)
    Spacer(Modifier.height(Space.L))
    ArcStage(progress = session.progress(s.time), glow = 1f, warmth = 0.15f, breathing = true) {
        Text(
            formatClock(remaining),
            Modifier.clearAndSetSemantics { contentDescription = spokenRemaining },
            color = colors.textPrimary,
            style = AuroraType.Hero,
        )
        Text(stringResource(R.string.focus_remaining), color = colors.textSecondary, style = AuroraType.Caption)
    }
    Spacer(Modifier.height(Space.XL))
    Text(holds(s, session.groupId, protection), color = colors.textTertiary, style = AuroraType.Caption, textAlign = TextAlign.Center)
    Spacer(Modifier.height(Space.XS))
    Text(stringResource(R.string.focus_you_can_leave_this_screen_aurora_keeps_time), color = colors.textTertiary, style = AuroraType.Caption, textAlign = TextAlign.Center)
}

@Composable
private fun Complete(session: Session, s: FocusState) {
    val colors = Aurora.colors
    val minutes = session.durationMs / 60_000
    ArcStage(progress = 1f, glow = 1f, warmth = 1f, breathing = false, complete = true) {
        Text("$minutes", color = colors.textPrimary, style = AuroraType.Hero)
        Text(stringResource(R.string.focus_minutes_focused), color = colors.textSecondary, style = AuroraType.Caption)
    }
    Spacer(Modifier.height(Space.XL))
    Text(stringResource(R.string.focus_time_well_spent), Modifier.semantics { heading() }, color = colors.textPrimary, style = AuroraType.Headline)
    Spacer(Modifier.height(Space.XS))
    Text(
        if (session.intention.isBlank()) stringResource(R.string.focus_complete_body , app.aurora.ui.components.minutesText(minutes.toInt()))
        else stringResource(R.string.focus_complete_intention , app.aurora.ui.components.minutesText(minutes.toInt()), session.intention),
        color = colors.textSecondary,
        style = AuroraType.Body,
        textAlign = TextAlign.Center,
    )
    s.rules.motivations.firstOrNull()?.let {
        Spacer(Modifier.height(Space.S))
        Text(stringResource(R.string.focus_more_time_for , it.lowercase()), color = colors.dawnText, style = AuroraType.Secondary, textAlign = TextAlign.Center)
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
