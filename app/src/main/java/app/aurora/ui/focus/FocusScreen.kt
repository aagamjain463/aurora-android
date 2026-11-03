package app.aurora.ui.focus

import androidx.compose.ui.unit.sp
import androidx.compose.ui.draw.clip
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
import app.aurora.ui.components.FocusDial
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

    Box(Modifier.fillMaxSize().background(colors.background)) {
    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding(),
    ) {
        Box(Modifier.fillMaxWidth().height(60.dp).padding(start = Space.XS + 2.dp), contentAlignment = Alignment.CenterStart) {
            AuroraIconButton(if (phase == Phase.Setup) AuroraIcons.Close else AuroraIcons.Back, stringResource(R.string.action_close), onClose)
            if (phase != Phase.Setup) Text(
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
                BoxWithConstraints(Modifier.weight(1f)) {
                    Column(
                        Modifier
                            .verticalScroll(rememberScrollState())
                            .heightIn(min = maxHeight)
                            .padding(horizontal = Space.Gutter),
                        verticalArrangement = Arrangement.Top,
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        // `session` may already be cleared while an old phase animates out.
                        when (p) {
                            Phase.Setup -> Setup(
                                s, profiles, profile, { profileId = it.id }, minutes, { minutes = it }, intention, { intention = it },
                                protection, onEdit = { editing = it },
                                onNew = { editing = FocusProfile(UUID.randomUUID().toString(), "", 25) },
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
    // The dial at rest previews the time chosen: the same instrument the session will run on.
    Text(stringResource(R.string.focus_name), Modifier.padding(top = Space.S).semantics { heading() }, color = colors.headlineLead, style = AuroraType.DisplayS)
    FocusDial(
        0f, formatClock(minutes * 60_000L), Modifier.padding(top = Space.M).fillMaxWidth(0.8f),
        spoken = app.aurora.ui.components.minutesText(minutes),
    )
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
    val keys = s.rules.cover(session.groupId, s.activeKeys).toList()
    val holding = protection == ProtectionStatus.Active && keys.isNotEmpty()
    val progress = (1f - remaining.toFloat() / session.durationMs.coerceAtLeast(1L)).coerceIn(0f, 1f)
    // Whether Shield holds the apps back, honestly: "Timer only" when it can't. A quiet line, not a badge.
    Row(
        Modifier.padding(top = Space.S),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            Modifier.size(6.dp).clip(androidx.compose.foundation.shape.CircleShape)
                .then(if (holding) Modifier.background(colors.glowTop) else Modifier.border(1.dp, colors.textTertiary, androidx.compose.foundation.shape.CircleShape)),
        )
        Text(
            stringResource(if (holding) R.string.focus_shield_is_holding else R.string.focus_timer_only_shield_is_off),
            color = colors.textTertiary, style = AuroraType.Caption,
        )
    }
    // The ring reads the time gone; the clock inside it reads the time left.
    FocusDial(
        progress, formatClock(remaining), Modifier.padding(top = Space.L).fillMaxWidth(0.9f),
        label = stringResource(R.string.focus_remaining),
        caption = stringResource(R.string.focus_ends_at, clockTime(session.endWall(s.time))),
        spoken = spokenRemaining,
    )
    // What this time is for, in the person's own words; the profile's name when they didn't give any.
    Eyebrow(
        if (session.intention.isNotBlank()) stringResource(R.string.focus_time_for) else stringResource(R.string.focus_name),
        Modifier.padding(top = Space.XL), textAlign = TextAlign.Center,
    )
    Text(
        session.intention.ifBlank { s.rules.profiles.find { it.id == session.profileId }?.name ?: stringResource(R.string.focus_focus_session) },
        Modifier.padding(top = 4.dp).semantics { heading() },
        // The person's own words, set in the serif's italic: the one thing on this screen that is theirs.
        color = colors.textPrimary, style = AuroraType.DisplayS.copy(fontSize = 30.sp, lineHeight = 34.sp, fontStyle = androidx.compose.ui.text.font.FontStyle.Italic),
        textAlign = TextAlign.Center, maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
    )
    // What is held back: the faces of the apps and one line, no card.
    if (keys.isNotEmpty()) {
        val group = session.groupId?.let { id -> s.rules.groups.find { it.id == id }?.name }
        Row(
            Modifier.padding(top = Space.L),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy((-6).dp)) {
                keys.take(4).forEach { key ->
                    Box(Modifier.size(24.dp).clip(androidx.compose.foundation.shape.CircleShape).border(1.5.dp, colors.background, androidx.compose.foundation.shape.CircleShape)) {
                        app.aurora.ui.protect.ItemIcon(key, null, size = 24.dp)
                    }
                }
            }
            Text(
                androidx.compose.ui.res.pluralStringResource(R.plurals.focus_apps_protected, keys.size, keys.size) + " · " + (group ?: stringResource(R.string.focus_all_protected)),
                color = colors.textSecondary, style = AuroraType.Caption,
            )
        }
    }
}

@Composable
private fun Complete(session: Session, s: FocusState) {
    val colors = Aurora.colors
    val minutes = session.durationMs / 60_000
    // A full sunrise: every tick lit.
    FocusDial(1f, "$minutes", Modifier.padding(top = Space.L).fillMaxWidth(0.9f), caption = stringResource(R.string.focus_minutes_focused))
    Text(stringResource(R.string.focus_time_well_spent), Modifier.padding(top = Space.L).semantics { heading() }, color = colors.headlineLead, style = AuroraType.DisplayS, textAlign = TextAlign.Center)
    Spacer(Modifier.height(Space.S))
    Text(
        if (session.intention.isBlank()) stringResource(R.string.focus_complete_body , app.aurora.ui.components.minutesText(minutes.toInt()))
        else stringResource(R.string.focus_complete_intention , app.aurora.ui.components.minutesText(minutes.toInt()), session.intention),
        color = colors.textSecondary,
        style = AuroraType.Body,
        textAlign = TextAlign.Center,
    )
    s.rules.motivations.firstOrNull()?.let {
        Spacer(Modifier.height(Space.S))
        Text(stringResource(R.string.focus_more_time_for , it.lowercase()), color = colors.violetText, style = AuroraType.Secondary, textAlign = TextAlign.Center)
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
