package app.aurora.ui.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.aurora.R
import app.aurora.auroraViewModel
import app.aurora.configsUsing
import app.aurora.deletePreset
import app.aurora.newId
import app.aurora.protect.Difficulty
import app.aurora.protect.FrictionSettings
import app.aurora.protect.FrictionTask
import app.aurora.protect.Mode
import app.aurora.protect.strict
import app.aurora.protect.ShieldPreset
import app.aurora.protect.WebPrefix
import app.aurora.runsNow
import app.aurora.saveShield
import app.aurora.ui.components.ArtTier
import app.aurora.ui.components.AuroraButton
import app.aurora.ui.components.AuroraDialog
import app.aurora.ui.components.AuroraIcons
import app.aurora.ui.components.AuroraRow
import app.aurora.ui.components.AuroraScreen
import app.aurora.ui.components.AuroraTextField
import app.aurora.ui.components.Chevron
import app.aurora.ui.components.Chip
import app.aurora.ui.components.ChoiceCard
import app.aurora.ui.components.DurationPicker
import app.aurora.ui.components.EmptyState
import app.aurora.ui.components.IconBadge
import app.aurora.ui.components.RowGroup
import app.aurora.ui.components.minutesText
import app.aurora.ui.components.secondsText
import app.aurora.ui.components.auroraSurface
import app.aurora.ui.protect.ModeChip
import app.aurora.ui.protect.ProtectModel
import app.aurora.ui.protect.ReflectionPauses
import app.aurora.ui.protect.difficultyName
import app.aurora.ui.protect.modeColor
import app.aurora.ui.protect.modeDescription
import app.aurora.ui.protect.modeName
import app.aurora.ui.protect.taskDescription
import app.aurora.ui.protect.taskName
import app.aurora.ui.theme.Aurora
import app.aurora.ui.theme.AuroraType
import app.aurora.ui.theme.Space

/** Shield Cooldown waits offered first; a custom wait of any length is always there. */
val ShieldWaitPresets = listOf(1, 5, 15, 30, 60)

/**
 * The Shield tab: the library of saved Shields. Creating, editing, duplicating and deleting happen here;
 * starting never does (Home is where sessions start).
 */
@Composable
fun ShieldLibraryScreen(onCreate: () -> Unit, onEdit: (String) -> Unit, onOpenLimits: () -> Unit, onOpenWeb: () -> Unit, onOpenSettings: () -> Unit) {
    val vm = auroraViewModel { ProtectModel(it) }
    val data by vm.data.collectAsStateWithLifecycle()
    var deleting by remember { mutableStateOf<ShieldPreset?>(null) }
    val d = data
    AuroraScreen(
        title = stringResource(R.string.tab_shield),
        tabBar = true,
        art = ArtTier.Balanced,
        header = { TabHeader(stringResource(R.string.shield_library_headline), onOpenSettings) },
    ) {
        item(key = "create") {
            AuroraButton(stringResource(R.string.shield_create), onCreate, Modifier.padding(horizontal = Space.M).padding(bottom = Space.M).fillMaxWidth(), icon = AuroraIcons.Plus)
        }
        // Web & content is a main feature: it stands out right under Create, with Daily limits below it.
        item(key = "web") {
            RowGroup(Modifier.padding(bottom = Space.XS), edge = Aurora.colors.accent) {
                AuroraRow(stringResource(R.string.shield_web_row), subtitle = stringResource(R.string.shield_web_row_body), leading = { IconBadge(AuroraIcons.Globe) }, onClick = onOpenWeb, trailing = { Chevron() })
            }
        }
        item(key = "limits") {
            RowGroup(Modifier.padding(bottom = Space.M)) {
                AuroraRow(stringResource(R.string.shield_limits_row), subtitle = stringResource(R.string.shield_limits_row_body), leading = { IconBadge(AuroraIcons.Hourglass) }, onClick = onOpenLimits, trailing = { Chevron() })
            }
        }
        if (d == null) return@AuroraScreen
        val running = vm.app.runsNow(d.time).mapNotNull { it.shield?.id }.toSet()
        val shields = d.rules.shields.sortedByDescending { it.createdAt }
        if (shields.isEmpty()) item(key = "empty") {
            EmptyState(stringResource(R.string.shield_empty_title), stringResource(R.string.shield_empty_body), icon = AuroraIcons.Protect)
        }
        items(shields, key = { it.id }) { p ->
            val apps = p.keys.count { !it.startsWith(WebPrefix) }
            PresetCard(
                p.name, { ModeChip(p.mode) }, shieldDetail(p, apps), p.keys, d, p.id in running,
                onEdit = { onEdit(p.id) },
                onDuplicate = { vm.run { vm.app.saveShield(p.copy(id = newId(), name = vm.app.getString(R.string.preset_copy_name, p.name), createdAt = System.currentTimeMillis())) } },
                onDelete = { deleting = p },
                modifier = Modifier.padding(bottom = Space.S),
                edge = modeColor(p.mode),
            )
        }
    }
    deleting?.let { p -> DeletePresetDialog(vm, p.id, p.name) { deleting = null } }
}

@Composable
fun shieldDetail(p: ShieldPreset, apps: Int = p.keys.count { !it.startsWith(WebPrefix) }): String {
    val extra = when (p.mode) {
        Mode.Friction -> taskName(p.friction.task)
        Mode.Cooldown -> stringResource(R.string.start_review_cooldown, minutesText(p.cooldownWaitMinutes))
        // The mode's chip already names these; their meaning is spelled out where the mode is chosen.
        else -> null
    }
    return listOfNotNull(pluralStringResource(R.plurals.count_apps, apps, apps), (p.keys.size - apps).takeIf { it > 0 }?.let { pluralStringResource(R.plurals.count_sites, it, it) }, extra).joinToString(" · ")
}

/** Deleting asks first, and refuses (explaining why) while a configuration uses the preset. */
@Composable
fun DeletePresetDialog(vm: ProtectModel, id: String, name: String, onDone: () -> Unit) {
    val using = remember(id) { vm.app.configsUsing(id) }
    if (using.isNotEmpty()) {
        AuroraDialog(stringResource(R.string.preset_delete_title, name), onDone, stringResource(R.string.action_ok), onDone, stringResource(R.string.preset_delete_blocked, using.joinToString(", ")))
    } else {
        AuroraDialog(
            stringResource(R.string.preset_delete_title, name), onDone, stringResource(R.string.preset_delete), { vm.run { vm.app.deletePreset(id) }; onDone() },
            stringResource(R.string.preset_delete_body), stringResource(R.string.action_cancel),
        )
    }
}

private enum class ShieldStep { Name, Apps, Sites, Type, Review }

/**
 * Create or edit a Shield: name, apps, websites, the type with its settings on the same step, save. Saving never starts
 * it. [onSaved] gets the preset's id, so a Home flow that sent the person here can select it.
 */
@Composable
fun ShieldEditorScreen(id: String?, onBack: () -> Unit, onSaved: (String) -> Unit, startStep: Int = 0) {
    val vm = auroraViewModel { ProtectModel(it) }
    val data by vm.data.collectAsStateWithLifecycle()
    val d = data ?: return
    // A new preset keeps one id for the whole flow, however often the screen recomposes.
    val fresh = rememberSaveable { newId() }
    val existing = id?.let { i -> d.rules.shields.find { it.id == i } }
    ShieldEditor(vm, existing ?: ShieldPreset(fresh, "", createdAt = System.currentTimeMillis()), onBack, onSaved, startStep)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ShieldEditor(vm: ProtectModel, initial: ShieldPreset, onBack: () -> Unit, onSaved: (String) -> Unit, startStep: Int) {
    val apps by vm.apps.collectAsStateWithLifecycle()
    val exempt by vm.exempt.collectAsStateWithLifecycle()
    val colors = Aurora.colors
    var name by rememberSaveable(initial.id) { mutableStateOf(initial.name) }
    var keys by rememberSaveable(initial.id) { mutableStateOf(initial.keys.filterNot { it.startsWith(WebPrefix) }) }
    var sites by rememberSaveable(initial.id) { mutableStateOf(initial.keys.filter { it.startsWith(WebPrefix) }.map { it.removePrefix(WebPrefix) }) }
    var mode by rememberSaveable(initial.id) { mutableStateOf(initial.mode) }
    var task by rememberSaveable(initial.id) { mutableStateOf(initial.friction.task) }
    var difficulty by rememberSaveable(initial.id) { mutableStateOf(initial.friction.difficulty) }
    var pause by rememberSaveable(initial.id) { mutableIntStateOf(initial.friction.reflectionSeconds) }
    var wait by rememberSaveable(initial.id) { mutableStateOf<Int?>(initial.cooldownWaitMinutes) }
    var query by rememberSaveable { mutableStateOf("") }
    var step by rememberSaveable { mutableStateOf(ShieldStep.entries[startStep]) }

    val steps = ShieldStep.entries
    val index = steps.indexOf(step).coerceAtLeast(0)
    val back = { if (index == 0) onBack() else step = steps[index - 1] }
    val canNext = when (step) {
        ShieldStep.Name -> name.isNotBlank()
        ShieldStep.Apps, ShieldStep.Sites -> true
        ShieldStep.Type -> mode != Mode.Cooldown || wait != null
        ShieldStep.Review -> keys.isNotEmpty() || sites.isNotEmpty()
    }
    val save = {
        val preset = initial.copy(
            name = name.trim(), keys = keys + sites.map { WebPrefix + it }, mode = mode,
            friction = FrictionSettings(task, difficulty, pause), cooldownWaitMinutes = wait ?: initial.cooldownWaitMinutes,
        )
        vm.run {
            vm.app.saveShield(preset)
            onSaved(preset.id)
        }
    }
    StepFlow(
        steps.size, index, back,
        primary = if (step == ShieldStep.Review) stringResource(R.string.shield_save) else stringResource(R.string.action_next),
        primaryEnabled = canNext,
        onPrimary = { if (step == ShieldStep.Review) save() else step = steps[index + 1] },
    ) {
        when (step) {
            ShieldStep.Name -> {
                stepTitle(R.string.shield_name_title, null)
                item(key = "name") { AuroraTextField(name, { name = it.take(40) }, stringResource(R.string.shield_name_placeholder), capitalization = KeyboardCapitalization.Words) }
                item(key = "ideas") {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.XS), verticalArrangement = Arrangement.spacedBy(Space.XS)) {
                        listOf("Social Media Shield", "Entertainment Shield", "Gaming Shield", "Night Shield").forEach { Chip(it, name == it) { name = it } }
                    }
                }
            }
            ShieldStep.Apps -> {
                stepTitle(R.string.shield_apps_title, R.string.shield_apps_body)
                appChecklist(apps, exempt, keys.toSet(), query, { query = it }) { pkg -> keys = if (pkg in keys) keys - pkg else keys + pkg }
            }
            ShieldStep.Sites -> {
                stepTitle(R.string.shield_sites_title, R.string.shield_sites_body)
                siteEditor(sites) { sites = it }
            }
            ShieldStep.Type -> {
                stepTitle(R.string.shield_type_title, null)
                items(Mode.entries, key = { "mode-" + it.name }) { m -> ChoiceCard(modeName(m), modeDescription(m), mode == m, { mode = m }, swatch = modeColor(m)) }
                if (mode.strict) item(key = "ab-note") {
                    Text(stringResource(R.string.shield_always_block_note), Modifier.padding(top = Space.XS), color = colors.violetText, style = AuroraType.Secondary)
                }
                // The chosen type's settings follow on the same step.
                if (mode == Mode.Friction) {
                item(key = "friction-title") { SettingsTitle(stringResource(R.string.start_friction_title), stringResource(R.string.start_friction_body)) }
                items(FrictionTask.entries, key = { "task-" + it.name }) { t -> ChoiceCard(taskName(t), taskDescription(t), task == t, { task = t }) }
                if (task == FrictionTask.Math) item(key = "difficulty") {
                    Column(verticalArrangement = Arrangement.spacedBy(Space.XS)) {
                        Text(stringResource(R.string.friction_difficulty), Modifier.padding(top = Space.S), color = colors.textPrimary, style = AuroraType.Title)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.XS)) { Difficulty.entries.forEach { dif -> Chip(difficultyName(dif), difficulty == dif) { difficulty = dif } } }
                    }
                }
                if (task == FrictionTask.Reflection) item(key = "pause") {
                    Column(verticalArrangement = Arrangement.spacedBy(Space.XS)) {
                        Text(stringResource(R.string.friction_reflection_pause), Modifier.padding(top = Space.S), color = colors.textPrimary, style = AuroraType.Title)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.XS)) { ReflectionPauses.forEach { p -> Chip(secondsText(p), pause == p) { pause = p } } }
                    }
                }
                item(key = "access") { Text(stringResource(R.string.usage_hint), color = colors.textTertiary, style = AuroraType.Caption) }
            } else if (mode == Mode.Cooldown) {
                item(key = "wait-title") { SettingsTitle(stringResource(R.string.duration_cooldown_prompt), stringResource(R.string.duration_cooldown_hint)) }
                item(key = "wait") { DurationPicker(wait, { wait = it }, ShieldWaitPresets, max = 12 * 60) }
                item(key = "restart") { Text(stringResource(R.string.shield_restart_note), color = colors.violetText, style = AuroraType.BodyStrong) }
            }
            }
            ShieldStep.Review -> {
                stepTitle(R.string.cfg_review_title, R.string.shield_review_note)
                item(key = "review") {
                    val preview = ShieldPreset(initial.id, name.trim(), keys + sites.map { WebPrefix + it }, mode, FrictionSettings(task, difficulty, pause), wait ?: 1)
                    Column(
                        Modifier.fillMaxWidth().then(Modifier.padding(top = Space.XS)),
                        verticalArrangement = Arrangement.spacedBy(Space.S),
                    ) {
                        PresetCardPreview(preview, vm)
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsTitle(title: String, body: String) {
    Column(Modifier.padding(top = Space.L), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, color = Aurora.colors.textPrimary, style = AuroraType.Title)
        Text(body, color = Aurora.colors.textSecondary, style = AuroraType.Secondary)
    }
}

/** The finished Shield as its library card will show it, without actions. */
@Composable
private fun PresetCardPreview(p: ShieldPreset, vm: ProtectModel) {
    val data by vm.data.collectAsStateWithLifecycle()
    val colors = Aurora.colors
    Column(
        Modifier.fillMaxWidth().auroraSurface(colors, edge = modeColor(p.mode)).padding(Space.L),
        verticalArrangement = Arrangement.spacedBy(Space.S),
    ) {
        ModeChip(p.mode)
        Text(p.name, color = colors.textPrimary, style = AuroraType.Headline)
        Text(shieldDetail(p), color = colors.textSecondary, style = AuroraType.Secondary)
        if (p.keys.isNotEmpty()) app.aurora.ui.protect.IconStack(p.keys, data, max = 8)
    }
}
