package app.aurora.ui.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.aurora.R
import app.aurora.auroraViewModel
import app.aurora.newId
import app.aurora.protect.IntentConfig
import app.aurora.protect.IntentExpiry
import app.aurora.protect.IntentPreset
import app.aurora.runsNow
import app.aurora.saveIntentPreset
import app.aurora.ui.components.ArtTier
import app.aurora.ui.components.AuroraButton
import app.aurora.ui.components.AuroraIcons
import app.aurora.ui.components.AuroraScreen
import app.aurora.ui.components.AuroraSwitch
import app.aurora.ui.components.AuroraTextField
import app.aurora.ui.components.ButtonKind
import app.aurora.ui.components.Chip
import app.aurora.ui.components.ChoiceCard
import app.aurora.ui.components.DurationPicker
import app.aurora.ui.components.EmptyState
import app.aurora.ui.components.SectionLabel
import app.aurora.ui.components.Tag
import app.aurora.ui.components.auroraSurface
import app.aurora.ui.protect.IntentCooldownPresets
import app.aurora.ui.protect.ProtectModel
import app.aurora.ui.protect.SecondsPicker
import app.aurora.ui.protect.intentSummary
import app.aurora.ui.theme.Aurora
import app.aurora.ui.theme.AuroraType
import app.aurora.ui.theme.Space

/** The Intent tab: saved Intent presets. Like Shield, it creates; Home starts. */
@Composable
fun IntentLibraryScreen(onCreate: () -> Unit, onEdit: (String) -> Unit, onOpenSettings: () -> Unit) {
    val vm = auroraViewModel { ProtectModel(it) }
    val data by vm.data.collectAsStateWithLifecycle()
    var deleting by remember { mutableStateOf<IntentPreset?>(null) }
    val d = data
    AuroraScreen(
        title = stringResource(R.string.tab_intent),
        tabBar = true,
        art = ArtTier.Balanced,
        header = { TabHeader(stringResource(R.string.intent_tab_title), onOpenSettings) },
    ) {
        item(key = "create") {
            AuroraButton(stringResource(R.string.intent_create), onCreate, Modifier.padding(horizontal = Space.M).padding(bottom = Space.M).fillMaxWidth(), icon = AuroraIcons.Plus)
        }
        if (d == null) return@AuroraScreen
        val running = vm.app.runsNow(d.time).mapNotNull { it.intent?.id }.toSet()
        val presets = d.rules.intentPresets.sortedByDescending { it.createdAt }
        if (presets.isEmpty()) item(key = "empty") {
            EmptyState(stringResource(R.string.intent_lib_empty_title), stringResource(R.string.intent_lib_empty_body), icon = AuroraIcons.Intent)
        }
        items(presets, key = { it.id }) { p ->
            PresetCard(
                p.name, { Tag(pluralStringResource(R.plurals.count_apps, p.keys.size, p.keys.size), accent = true) }, intentSummary(p.config), p.keys, d, p.id in running,
                onEdit = { onEdit(p.id) },
                onDuplicate = { vm.run { vm.app.saveIntentPreset(p.copy(id = newId(), name = vm.app.getString(R.string.preset_copy_name, p.name), createdAt = System.currentTimeMillis())) } },
                onDelete = { deleting = p },
                modifier = Modifier.padding(bottom = Space.S),
                edge = Aurora.colors.rose,
            )
        }
        // Explained until the first Intent exists; after that, the person knows.
        if (presets.isEmpty()) item(key = "how") { IntentHow() }
    }
    deleting?.let { p -> DeletePresetDialog(vm, p.id, p.name) { deleting = null } }
}

/** Pause, decide, choose a time, use, follow through: one quiet line each. */
@Composable
private fun IntentHow() {
    val colors = Aurora.colors
    val steps = listOf(
        R.string.intent_step_pause to R.string.intent_step_pause_body,
        R.string.intent_step_decide to R.string.intent_step_decide_body,
        R.string.intent_step_time to R.string.intent_step_time_body,
        R.string.intent_step_use to R.string.intent_step_use_body,
        R.string.intent_step_follow to R.string.intent_step_follow_body,
    )
    Column(Modifier.padding(top = Space.L)) {
        SectionLabel(stringResource(R.string.intent_name))
        Column(Modifier.padding(horizontal = Space.M).fillMaxWidth().auroraSurface(colors).padding(Space.L), verticalArrangement = Arrangement.spacedBy(Space.S)) {
            steps.forEachIndexed { i, (title, body) ->
                Row(horizontalArrangement = Arrangement.spacedBy(Space.M)) {
                    Text("${i + 1}", color = if (i == 4) colors.violetText else colors.accent, style = AuroraType.Label)
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(title), color = colors.textPrimary, style = AuroraType.BodyStrong)
                        Text(stringResource(body), color = colors.textSecondary, style = AuroraType.Secondary)
                    }
                }
            }
        }
    }
}

private enum class IntentStep { Name, Apps, Reflect, Time, Review }

/** Create or edit an Intent: name, apps, reflection, then usage time and what happens after on one step, save. */
@Composable
fun IntentEditorScreen(id: String?, onBack: () -> Unit, onSaved: (String) -> Unit, startStep: Int = 0, onPlus: (() -> Unit)? = null) {
    val vm = auroraViewModel { ProtectModel(it) }
    val data by vm.data.collectAsStateWithLifecycle()
    val d = data ?: return
    // A new preset keeps one id for the whole flow, however often the screen recomposes.
    val fresh = rememberSaveable { newId() }
    val existing = id?.let { i -> d.rules.intentPresets.find { it.id == i } }
    IntentEditor(vm, existing ?: IntentPreset(fresh, "", createdAt = System.currentTimeMillis()), onBack, onSaved, startStep)
    app.aurora.ui.protect.LimitNoticeDialog(vm, onPlus)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun IntentEditor(vm: ProtectModel, initial: IntentPreset, onBack: () -> Unit, onSaved: (String) -> Unit, startStep: Int) {
    val apps by vm.apps.collectAsStateWithLifecycle()
    val exempt by vm.exempt.collectAsStateWithLifecycle()
    val colors = Aurora.colors
    val c0 = initial.config
    var name by rememberSaveable(initial.id) { mutableStateOf(initial.name) }
    var keys by rememberSaveable(initial.id) { mutableStateOf(initial.keys) }
    var pause by rememberSaveable(initial.id) { mutableIntStateOf(c0.reflectionSeconds) }
    var reason by rememberSaveable(initial.id) { mutableStateOf(c0.reason) }
    var alternative by rememberSaveable(initial.id) { mutableStateOf(c0.alternative) }
    var confirm by rememberSaveable(initial.id) { mutableStateOf(c0.confirmContinue) }
    var usage by rememberSaveable(initial.id) { mutableStateOf(c0.defaultUsageMinutes) }
    var expiry by rememberSaveable(initial.id) { mutableStateOf(c0.expiry) }
    var cooldown by rememberSaveable(initial.id) { mutableStateOf<Int?>(c0.cooldownMinutes) }
    var fresh by rememberSaveable(initial.id) { mutableStateOf(c0.freshEachOpen) }
    var query by rememberSaveable { mutableStateOf("") }
    var step by rememberSaveable { mutableStateOf(IntentStep.entries[startStep]) }

    val steps = IntentStep.entries
    val index = steps.indexOf(step)
    val back = { if (index == 0) onBack() else step = steps[index - 1] }
    val config = IntentConfig(reason.trim(), alternative.trim(), pause, true, usage, expiry, cooldown ?: c0.cooldownMinutes, fresh, confirm)
    val canNext = when (step) {
        IntentStep.Name -> name.isNotBlank()
        IntentStep.Apps -> keys.isNotEmpty()
        IntentStep.Time -> expiry == IntentExpiry.Reminder || cooldown != null
        else -> true
    }
    StepFlow(
        steps.size, index, back,
        primary = if (step == IntentStep.Review) stringResource(R.string.intent_save) else stringResource(R.string.action_next),
        primaryEnabled = canNext,
        whyDisabled = stringResource(
            when (step) {
                IntentStep.Name -> R.string.need_name
                IntentStep.Apps -> R.string.need_apps
                else -> R.string.need_time
            },
        ),
        onPrimary = {
            if (step != IntentStep.Review) step = steps[index + 1]
            else {
                val preset = initial.copy(name = name.trim(), keys = keys, config = config)
                vm.run {
                    vm.app.saveIntentPreset(preset)
                    onSaved(preset.id)
                }
            }
        },
    ) {
        when (step) {
            IntentStep.Name -> {
                stepTitle(R.string.intent_name_title, null)
                item(key = "name") { AuroraTextField(name, { name = it.take(40) }, stringResource(R.string.intent_name_placeholder), capitalization = KeyboardCapitalization.Words) }
                item(key = "ideas") {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.XS), verticalArrangement = Arrangement.spacedBy(Space.XS)) {
                        listOf("Mindful Social Media", "Intentional YouTube", "Controlled Gaming").forEach { Chip(it, name == it) { name = it } }
                    }
                }
            }
            IntentStep.Apps -> {
                stepTitle(R.string.shield_apps_title, R.string.intent_overview_body)
                val cap = vm.data.value?.max(app.aurora.plan.Allowance.IntentApps)
                appChecklist(apps, exempt, keys.toSet(), query, { query = it }, cap) { pkg ->
                    when {
                        pkg in keys -> keys = keys - pkg
                        cap != null && keys.size >= cap -> vm.atLimit(app.aurora.plan.Allowance.IntentApps)
                        else -> keys = keys + pkg
                    }
                }
            }
            IntentStep.Reflect -> {
                stepTitle(R.string.intent_reflection_title, R.string.intent_reflection_hint)
                item(key = "pause") { SecondsPicker(pause) { pause = it } }
                item(key = "reason") {
                    Column(Modifier.padding(top = Space.S), verticalArrangement = Arrangement.spacedBy(Space.XS)) {
                        Text(stringResource(R.string.intent_reason_title), color = colors.textPrimary, style = AuroraType.Title)
                        AuroraTextField(reason, { reason = it.take(140) }, stringResource(R.string.intent_reason_placeholder), capitalization = KeyboardCapitalization.Sentences, singleLine = false)
                        Text(stringResource(R.string.intent_alternative_title), Modifier.padding(top = Space.S), color = colors.textPrimary, style = AuroraType.Title)
                        AuroraTextField(alternative, { alternative = it.take(140) }, stringResource(R.string.intent_alternative_placeholder), capitalization = KeyboardCapitalization.Sentences, singleLine = false)
                    }
                }
                item(key = "confirm") {
                    Row(
                        Modifier.padding(top = Space.S).fillMaxWidth().auroraSurface(colors).toggleableRow(confirm) { confirm = it }.padding(Space.M),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Space.M),
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.intent_confirm_title), color = colors.textPrimary, style = AuroraType.BodyStrong)
                            Text(stringResource(R.string.intent_confirm_hint), color = colors.textSecondary, style = AuroraType.Secondary)
                        }
                        AuroraSwitch(confirm)
                    }
                }
            }
            IntentStep.Time -> {
                stepTitle(R.string.intent_usage_title, R.string.intent_usage_body)
                item(key = "usage") {
                    DurationPicker(usage, { usage = it }, listOf(5, 10, 15, 30), title = stringResource(R.string.intent_default_title), hint = stringResource(R.string.intent_default_hint), max = 8 * 60)
                }
                if (usage != null) item(key = "clear") { AuroraButton(stringResource(R.string.intent_default_clear), { usage = null }, kind = ButtonKind.Quiet) }
                // What happens when that time runs out, on the same step.
                item(key = "after-title") { Text(stringResource(R.string.intent_expiry_title), Modifier.padding(top = Space.L), color = colors.textPrimary, style = AuroraType.Title) }
                item(key = "reminder") { ChoiceCard(stringResource(R.string.intent_expiry_reminder), stringResource(R.string.intent_expiry_reminder_desc), expiry == IntentExpiry.Reminder, { expiry = IntentExpiry.Reminder }) }
                item(key = "cooldown") { ChoiceCard(stringResource(R.string.intent_expiry_cooldown), stringResource(R.string.intent_expiry_cooldown_desc), expiry == IntentExpiry.Cooldown, { expiry = IntentExpiry.Cooldown }) }
                if (expiry == IntentExpiry.Cooldown) {
                    item(key = "length") {
                        DurationPicker(cooldown, { cooldown = it }, IntentCooldownPresets, Modifier.padding(top = Space.S), title = stringResource(R.string.intent_cooldown_title), hint = stringResource(R.string.intent_cooldown_hint), max = 12 * 60)
                    }
                    item(key = "restart") { Text(stringResource(R.string.shield_restart_note), color = colors.violetText, style = AuroraType.BodyStrong) }
                }
                item(key = "fresh") {
                    Row(
                        Modifier.padding(top = Space.S).fillMaxWidth().auroraSurface(colors).toggleableRow(fresh) { fresh = it }.padding(Space.M),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Space.M),
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.intent_fresh_title), color = colors.textPrimary, style = AuroraType.BodyStrong)
                            Text(stringResource(R.string.intent_fresh_hint), color = colors.textSecondary, style = AuroraType.Secondary)
                        }
                        AuroraSwitch(fresh)
                    }
                }
            }
            IntentStep.Review -> {
                stepTitle(R.string.cfg_review_title, R.string.shield_review_note)
                item(key = "review") {
                    val data by vm.data.collectAsStateWithLifecycle()
                    Column(Modifier.fillMaxWidth().auroraSurface(colors, warm = true).padding(Space.L), verticalArrangement = Arrangement.spacedBy(Space.S)) {
                        Tag(pluralStringResource(R.plurals.count_apps, keys.size, keys.size), accent = true)
                        Text(name.trim(), color = colors.textPrimary, style = AuroraType.Headline)
                        Text(intentSummary(config), color = colors.textSecondary, style = AuroraType.Secondary)
                        app.aurora.ui.protect.IconStack(keys, data, max = 8)
                    }
                }
            }
        }
    }
}

private fun Modifier.toggleableRow(value: Boolean, onChange: (Boolean) -> Unit) =
    toggleable(value, role = androidx.compose.ui.semantics.Role.Switch, onValueChange = onChange)
