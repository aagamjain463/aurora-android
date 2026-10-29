package app.aurora.ui.protect

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.aurora.R
import app.aurora.StartResult
import app.aurora.alwaysBlockLeft
import app.aurora.auroraViewModel
import app.aurora.protect.Difficulty
import app.aurora.protect.FrictionSettings
import app.aurora.protect.FrictionTask
import app.aurora.protect.Mode
import app.aurora.protect.key
import app.aurora.startProtection
import app.aurora.ui.components.AuroraButton
import app.aurora.ui.components.AuroraIconButton
import app.aurora.ui.components.AuroraIcons
import app.aurora.ui.components.ButtonKind
import app.aurora.ui.components.ChoiceCard
import app.aurora.ui.components.Chip
import app.aurora.ui.components.DurationPicker
import app.aurora.ui.components.SelectionMark
import app.aurora.ui.components.minutesText
import app.aurora.ui.components.secondsText
import app.aurora.ui.theme.Aurora
import app.aurora.ui.theme.AuroraType
import app.aurora.ui.theme.Radius
import app.aurora.ui.theme.Space
import kotlinx.coroutines.launch

private enum class StartStep { Apps, Mode, Settings, Duration, Review }

/** Protection presets: the common answers to "How long should protection last?" Custom is always there. */
val ProtectionPresets = listOf(30, 60, 120, 240)
val CooldownWaitPresets = listOf(5, 15, 30, 60)
val ReflectionPauses = listOf(10, 20, 30, 60)

/**
 * Starting protection is a short, explicit flow: which apps, which mode, the mode's settings, and how
 * long. Every session has a duration; nothing starts open-ended. Always Block asks for a commitment
 * before it starts, because nothing in aurora ends it early.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun StartScreen(preselect: String?, onBack: () -> Unit, onDone: () -> Unit, onPlus: () -> Unit) {
    val vm = auroraViewModel { ProtectModel(it) }
    val data by vm.data.collectAsStateWithLifecycle()
    val d = data ?: return
    val colors = Aurora.colors
    val scope = rememberCoroutineScope()

    val first = preselect?.let { d.rules.rule(it) } ?: d.rules.defaultRule
    var step by rememberSaveable { mutableStateOf(StartStep.Apps) }
    var keys by rememberSaveable { mutableStateOf(if (preselect != null) listOf(preselect) else d.activeKeys.toList()) }
    var mode by rememberSaveable { mutableStateOf(first.mode) }
    var task by rememberSaveable { mutableStateOf(first.friction.task) }
    var difficulty by rememberSaveable { mutableStateOf(first.friction.difficulty) }
    var pause by rememberSaveable { mutableStateOf(first.friction.reflectionSeconds) }
    var wait by rememberSaveable { mutableStateOf<Int?>(first.cooldownWaitMinutes) }
    var minutes by rememberSaveable { mutableStateOf<Int?>(null) }
    var committed by rememberSaveable { mutableStateOf(false) }
    var overAllowance by rememberSaveable { mutableStateOf(false) }

    val steps = listOfNotNull(StartStep.Apps, StartStep.Mode, StartStep.Settings.takeIf { mode == Mode.Friction || mode == Mode.Cooldown }, StartStep.Duration, StartStep.Review)
    val index = steps.indexOf(step).coerceAtLeast(0)
    val back = { if (index == 0) onBack() else step = steps[index - 1] }
    BackHandler(onBack = back)
    val allowance = if (mode == Mode.AlwaysBlock) vm.app.alwaysBlockLeft() else null
    val maxMinutes = allowance ?: (24 * 60)

    Column(Modifier.fillMaxSize().background(colors.background).statusBarsPadding().navigationBarsPadding().imePadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = Space.XS, vertical = Space.XS), verticalAlignment = Alignment.CenterVertically) {
            AuroraIconButton(AuroraIcons.Back, stringResource(R.string.action_back), back)
            // Progress: one segment per step.
            Row(Modifier.weight(1f).padding(horizontal = Space.S), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                steps.forEachIndexed { i, _ ->
                    Box(Modifier.weight(1f).height(4.dp).clip(Radius.Pill).background(if (i <= index) colors.accent else colors.hairline))
                }
            }
            Box(Modifier.padding(end = Space.M)) { Text("${index + 1}/${steps.size}", color = colors.textTertiary, style = AuroraType.Caption) }
        }
        AnimatedContent(step, Modifier.weight(1f), transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "start") { s ->
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = Space.Gutter, vertical = Space.M), verticalArrangement = Arrangement.spacedBy(Space.S)) {
                when (s) {
                    StartStep.Apps -> {
                        StepTitle(stringResource(R.string.start_apps_title), stringResource(R.string.start_apps_body))
                        d.active.map { it.key }.forEach { key ->
                            val on = key in keys
                            val state = stringResource(if (on) R.string.picker_selected else R.string.picker_not_selected)
                            Row(
                                Modifier.fillMaxWidth().clip(Radius.M).background(if (on) colors.elevated else colors.surface)
                                    .border(1.dp, if (on) colors.accent else colors.hairline, Radius.M)
                                    .clickable(role = Role.Checkbox) { keys = if (on) keys - key else keys + key }
                                    .semantics(mergeDescendants = true) { stateDescription = state }
                                    .padding(Space.S),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(Space.M),
                            ) {
                                ItemIcon(key, d, 40.dp)
                                Text(d.label(key), Modifier.weight(1f), color = colors.textPrimary, style = AuroraType.BodyStrong)
                                SelectionMark(on)
                            }
                        }
                    }
                    StartStep.Mode -> {
                        StepTitle(stringResource(R.string.start_mode_title), stringResource(R.string.start_mode_body))
                        Mode.entries.forEach { m ->
                            ChoiceCard(modeName(m), modeDescription(m), mode == m, { mode = m }, swatch = modeColor(m))
                        }
                    }
                    StartStep.Settings -> if (mode == Mode.Friction) {
                        StepTitle(stringResource(R.string.start_friction_title), stringResource(R.string.start_friction_body))
                        FrictionTask.entries.forEach { t -> ChoiceCard(taskName(t), taskDescription(t), task == t, { task = t }) }
                        if (task == FrictionTask.Math) {
                            Text(stringResource(R.string.friction_difficulty), Modifier.padding(top = Space.S), color = colors.textPrimary, style = AuroraType.Title)
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.XS)) {
                                Difficulty.entries.forEach { dif -> Chip(difficultyName(dif), difficulty == dif) { difficulty = dif } }
                            }
                        }
                        if (task == FrictionTask.Reflection) {
                            Text(stringResource(R.string.friction_reflection_pause), Modifier.padding(top = Space.S), color = colors.textPrimary, style = AuroraType.Title)
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.XS)) {
                                ReflectionPauses.forEach { p -> Chip(secondsText(p), pause == p) { pause = p } }
                            }
                        }
                    } else {
                        StepTitle(stringResource(R.string.duration_cooldown_prompt), stringResource(R.string.duration_cooldown_hint))
                        DurationPicker(wait, { wait = it }, CooldownWaitPresets, max = 12 * 60)
                    }
                    StartStep.Duration -> {
                        StepTitle(stringResource(R.string.duration_protection_prompt), stringResource(R.string.duration_protection_hint))
                        DurationPicker(minutes, { minutes = it }, ProtectionPresets.filter { it <= maxMinutes }, max = maxMinutes.coerceAtLeast(1), endsFromNow = true)
                        if (allowance != null) {
                            Text(stringResource(R.string.start_always_block_allowance, minutesText(allowance)), color = colors.dawnText, style = AuroraType.Secondary)
                        }
                    }
                    StartStep.Review -> {
                        val names = keys.map { d.label(it) }
                        StepTitle(stringResource(R.string.start_review_title), null)
                        Column(
                            Modifier.fillMaxWidth().clip(Radius.L).background(colors.surface).border(1.dp, modeColor(mode).copy(alpha = 0.5f), Radius.L).padding(Space.L),
                            verticalArrangement = Arrangement.spacedBy(Space.XS),
                        ) {
                            Text(modeName(mode), color = modeColor(mode), style = AuroraType.Eyebrow)
                            Text(
                                stringResource(R.string.start_review_sentence, names.joinToString(", "), minutesText(minutes ?: 0)),
                                color = colors.textPrimary, style = AuroraType.Title,
                            )
                            Text(modeDescription(mode), color = colors.textSecondary, style = AuroraType.Secondary)
                            when (mode) {
                                Mode.Friction -> Text(stringResource(R.string.start_review_friction, taskName(task)), color = colors.textSecondary, style = AuroraType.Secondary)
                                Mode.Cooldown -> Text(stringResource(R.string.start_review_cooldown, minutesText(wait ?: 0)), color = colors.textSecondary, style = AuroraType.Secondary)
                                else -> Unit
                            }
                        }
                        if (mode == Mode.AlwaysBlock) {
                            Text(stringResource(R.string.start_commit_body), Modifier.padding(top = Space.S), color = colors.textSecondary, style = AuroraType.Body)
                            Row(
                                Modifier.fillMaxWidth().clip(Radius.M).background(if (committed) colors.dawnSoft else colors.surface)
                                    .border(1.dp, if (committed) colors.dawn else colors.hairline, Radius.M)
                                    .clickable(role = Role.Checkbox) { committed = !committed }
                                    .padding(Space.M),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(Space.M),
                            ) {
                                SelectionMark(committed)
                                Text(stringResource(R.string.always_block_confirm), color = colors.textPrimary, style = AuroraType.BodyStrong)
                            }
                            Text(stringResource(R.string.start_commit_limits), color = colors.textTertiary, style = AuroraType.Caption)
                        }
                        if (overAllowance) Text(stringResource(R.string.start_over_allowance), color = colors.danger, style = AuroraType.Secondary)
                    }
                }
            }
        }
        val canNext = when (step) {
            StartStep.Apps -> keys.isNotEmpty()
            StartStep.Mode -> true
            StartStep.Settings -> mode != Mode.Cooldown || wait != null
            StartStep.Duration -> minutes != null
            StartStep.Review -> mode != Mode.AlwaysBlock || committed
        }
        Column(Modifier.padding(Space.M)) {
            if (step == StartStep.Review) {
                AuroraButton(stringResource(R.string.protect_start), {
                    scope.launch {
                        val result = try {
                            vm.app.startProtection(keys.toSet(), mode, minutes ?: return@launch, FrictionSettings(task, difficulty, pause), wait ?: 15)
                        } catch (_: java.io.IOException) {
                            return@launch
                        }
                        when (result) {
                            StartResult.Started -> onDone()
                            StartResult.OverAllowance -> overAllowance = true
                            StartResult.NothingSelected -> step = StartStep.Apps
                        }
                    }
                }, Modifier.fillMaxWidth(), enabled = canNext)
                if (overAllowance) AuroraButton(stringResource(R.string.plus_see), onPlus, Modifier.fillMaxWidth(), kind = ButtonKind.Quiet)
            } else {
                AuroraButton(stringResource(R.string.action_next), { step = steps[index + 1] }, Modifier.fillMaxWidth(), enabled = canNext)
            }
        }
    }
}

@Composable
private fun StepTitle(title: String, body: String?) {
    Text(title, Modifier.semantics { heading() }, color = Aurora.colors.textPrimary, style = AuroraType.Headline)
    if (body != null) Text(body, Modifier.padding(bottom = Space.S), color = Aurora.colors.textSecondary, style = AuroraType.Body)
}

@Composable
fun difficultyName(d: Difficulty): String = stringResource(
    when (d) {
        Difficulty.Easy -> R.string.friction_easy
        Difficulty.Medium -> R.string.friction_medium
        Difficulty.Hard -> R.string.friction_hard
    },
)
