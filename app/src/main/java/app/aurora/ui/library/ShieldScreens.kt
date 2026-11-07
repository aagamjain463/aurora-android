package app.aurora.ui.library

import app.aurora.ui.protect.modeIcon
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import app.aurora.plan.Allowance
import app.aurora.protect.Difficulty
import app.aurora.protect.FrictionSettings
import app.aurora.protect.FrictionTask
import app.aurora.protect.FrictionTasks
import app.aurora.protect.Mode
import app.aurora.protect.strict
import app.aurora.protect.ShieldPreset
import app.aurora.protect.AppPart
import app.aurora.protect.AppParts
import app.aurora.protect.Instagram
import app.aurora.protect.YouTube
import app.aurora.data.InstalledApp
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.sp
import app.aurora.ui.components.AppIcon
import app.aurora.ui.components.Eyebrow
import app.aurora.ui.components.RowDivider
import app.aurora.ui.components.SelectionMark
import app.aurora.protect.PartPrefix
import app.aurora.protect.PartYouTubeChannels
import app.aurora.protect.channelsKey
import app.aurora.protect.channelsOf
import app.aurora.protect.cleanChannel
import app.aurora.protect.partOf
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
import app.aurora.ui.components.ButtonKind
import app.aurora.ui.components.Chevron
import app.aurora.ui.components.Chip
import app.aurora.ui.components.ChoiceCard
import app.aurora.ui.components.DurationPicker
import app.aurora.ui.components.EmptyState
import app.aurora.ui.components.IconBadge
import app.aurora.ui.components.RowGroup
import app.aurora.ui.components.minutesText
import app.aurora.ui.components.auroraSurface
import app.aurora.ui.protect.LimitNoticeDialog
import app.aurora.ui.protect.ModeChip
import app.aurora.ui.protect.ProtectModel
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
fun ShieldLibraryScreen(onCreate: () -> Unit, onEdit: (String) -> Unit, onOpenSettings: () -> Unit, onOpenGuide: () -> Unit = {}) {
    val vm = auroraViewModel { ProtectModel(it) }
    val data by vm.data.collectAsStateWithLifecycle()
    var deleting by remember { mutableStateOf<ShieldPreset?>(null) }
    var viewing by remember { mutableStateOf<ShieldPreset?>(null) }
    val d = data
    AuroraScreen(
        title = stringResource(R.string.tab_shield),
        tabBar = true,
        art = ArtTier.Balanced,
        header = { TabHeader(stringResource(R.string.shield_library_headline), onOpenSettings, app.aurora.ui.guide.GuideTopic.Shield, onOpenGuide) },
    ) {
        item(key = "create") {
            AuroraButton(stringResource(R.string.shield_create), onCreate, Modifier.padding(horizontal = Space.Gutter).padding(bottom = Space.M).fillMaxWidth(), icon = AuroraIcons.Plus)
        }
        if (d == null) return@AuroraScreen
        val running = vm.app.runsNow(d.time).mapNotNull { it.shield?.id }.toSet()
        val shields = d.rules.shields.sortedByDescending { it.createdAt }
        if (shields.isEmpty()) item(key = "empty") {
            EmptyState(stringResource(R.string.shield_empty_title), "", icon = AuroraIcons.Protect)
        }
        items(shields, key = { it.id }) { p ->
            PresetCard(
                p.name, { ModeChip(p.mode) }, shieldDetail(p), p.keys, d, p.id in running,
                onView = { viewing = p },
                onEdit = { onEdit(p.id) },
                onDuplicate = { vm.run { vm.app.saveShield(p.copy(id = newId(), name = vm.app.getString(R.string.preset_copy_name, p.name), createdAt = System.currentTimeMillis())) } },
                onDelete = { deleting = p },
                modifier = Modifier.padding(bottom = Space.S),
                edge = modeColor(p.mode),
            )
        }
    }
    deleting?.let { p -> DeletePresetDialog(vm, p.id, p.name) { deleting = null } }
    val shown = viewing
    if (shown != null && d != null) DetailsDialog(shown.name, shieldRows(shown, d), { viewing = null }) { onEdit(shown.id) }
}

/** A Shield in full: its type, its setting, and every app, site and part it covers. */
@Composable
fun shieldRows(p: ShieldPreset, d: app.aurora.ui.protect.ProtectData): List<Pair<String, String>> {
    val setting = when (p.mode) {
        Mode.Friction -> taskName(p.friction.task)
        Mode.Cooldown -> stringResource(R.string.start_review_cooldown, minutesText(p.cooldownWaitMinutes))
        else -> null
    }
    return listOf(stringResource(R.string.detail_type) to listOfNotNull(modeName(p.mode), setting).joinToString(" · ")) + keyRows(p.keys, d)
}

@Composable
fun shieldDetail(p: ShieldPreset): String {
    val sites = p.keys.count { it.startsWith(WebPrefix) }
    val parts = p.keys.count { it.startsWith(PartPrefix) }
    val apps = p.keys.size - sites - parts
    val extra = when (p.mode) {
        Mode.Friction -> taskName(p.friction.task)
        Mode.Cooldown -> stringResource(R.string.start_review_cooldown, minutesText(p.cooldownWaitMinutes))
        // The mode's chip already names these; their meaning is spelled out where the mode is chosen.
        else -> null
    }
    return listOfNotNull(
        pluralStringResource(R.plurals.count_apps, apps, apps),
        sites.takeIf { it > 0 }?.let { pluralStringResource(R.plurals.count_sites, it, it) },
        parts.takeIf { it > 0 }?.let { pluralStringResource(R.plurals.count_parts, it, it) },
        extra,
    ).joinToString(" · ")
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

private enum class ShieldStep { Name, Apps, Sites, Parts, Type, Review }

/**
 * Create or edit a Shield: name, apps, websites, parts of apps, the type with its settings on the same step, save. Saving never starts
 * it. [onSaved] gets the preset's id, so a Home flow that sent the person here can select it.
 */
@Composable
fun ShieldEditorScreen(id: String?, onBack: () -> Unit, onSaved: (String) -> Unit, startStep: Int = 0, onPlus: (() -> Unit)? = null, onOpenWeb: () -> Unit = {}) {
    val vm = auroraViewModel { ProtectModel(it) }
    val data by vm.data.collectAsStateWithLifecycle()
    val d = data ?: return
    // A new preset keeps one id for the whole flow, however often the screen recomposes.
    val fresh = rememberSaveable { newId() }
    val existing = id?.let { i -> d.rules.shields.find { it.id == i } }
    ShieldEditor(vm, existing ?: ShieldPreset(fresh, "", mode = d.rules.defaultRule.mode, createdAt = System.currentTimeMillis()), onBack, onSaved, startStep, onOpenWeb)
    LimitNoticeDialog(vm, onPlus)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ShieldEditor(vm: ProtectModel, initial: ShieldPreset, onBack: () -> Unit, onSaved: (String) -> Unit, startStep: Int, onOpenWeb: () -> Unit) {
    val apps by vm.apps.collectAsStateWithLifecycle()
    val exempt by vm.exempt.collectAsStateWithLifecycle()
    val colors = Aurora.colors
    var name by rememberSaveable(initial.id) { mutableStateOf(initial.name) }
    var keys by rememberSaveable(initial.id) { mutableStateOf(initial.keys.filterNot { it.startsWith(WebPrefix) || it.startsWith(PartPrefix) }) }
    // The chosen channels edit apart from their key; saving folds them back in (channelsKey).
    var parts by rememberSaveable(initial.id) { mutableStateOf(initial.keys.filter { it.startsWith(PartPrefix) }.map { partOf(it)?.key ?: it }) }
    var channels by rememberSaveable(initial.id) { mutableStateOf(initial.keys.firstOrNull { partOf(it)?.key == PartYouTubeChannels }?.let(::channelsOf).orEmpty()) }
    var sites by rememberSaveable(initial.id) { mutableStateOf(initial.keys.filter { it.startsWith(WebPrefix) }.map { it.removePrefix(WebPrefix) }) }
    var mode by rememberSaveable(initial.id) { mutableStateOf(initial.mode) }
    // Reflection is retired: a Shield saved with it ends with a math problem.
    var task by rememberSaveable(initial.id) { mutableStateOf(if (initial.friction.task == FrictionTask.Reflection) FrictionTask.Math else initial.friction.task) }
    var difficulty by rememberSaveable(initial.id) { mutableStateOf(initial.friction.difficulty) }
    var pause by rememberSaveable(initial.id) { mutableIntStateOf(initial.friction.reflectionSeconds) }
    var phrase by rememberSaveable(initial.id) { mutableStateOf(initial.friction.phrase) }
    var wait by rememberSaveable(initial.id) { mutableStateOf<Int?>(initial.cooldownWaitMinutes) }
    var query by rememberSaveable { mutableStateOf("") }
    var step by rememberSaveable { mutableStateOf(ShieldStep.entries[startStep]) }

    // Apps, websites and parts of apps each have their own page; any of them can be left empty.
    val steps = ShieldStep.entries
    val all = keys + sites.map { WebPrefix + it } + parts.map { if (it == PartYouTubeChannels) channelsKey(channels) else it }
    val channelsMissing = PartYouTubeChannels in parts && channels.isEmpty()
    val cap = vm.data.collectAsStateWithLifecycle().value?.max(Allowance.ProtectedItems)
    val index = steps.indexOf(step).coerceAtLeast(0)
    val back = { if (index == 0) onBack() else step = steps[index - 1] }
    val canNext = when (step) {
        ShieldStep.Name -> name.isNotBlank()
        ShieldStep.Apps, ShieldStep.Sites -> true
        ShieldStep.Parts -> !channelsMissing
        ShieldStep.Type -> (mode != Mode.Cooldown || wait != null) &&
            (mode != Mode.Friction || task != FrictionTask.Intention || phrase.isNotBlank())
        ShieldStep.Review -> all.isNotEmpty()
    }
    val save = {
        val preset = initial.copy(
            name = name.trim(), keys = all, mode = mode,
            friction = FrictionSettings(task = task, difficulty = difficulty, reflectionSeconds = pause, phrase = phrase.trim()),
            cooldownWaitMinutes = wait ?: initial.cooldownWaitMinutes,
        )
        vm.run {
            if (vm.app.saveShield(preset) == null) onSaved(preset.id)
        }
    }
    StepFlow(
        steps.size, index, back,
        primary = if (step == ShieldStep.Review) stringResource(R.string.shield_save) else stringResource(R.string.action_next),
        primaryEnabled = canNext,
        whyDisabled = stringResource(
            when {
                step == ShieldStep.Type && mode == Mode.Friction && task == FrictionTask.Intention && phrase.isBlank() -> R.string.friction_phrase_need
                step == ShieldStep.Name -> R.string.need_name
                step == ShieldStep.Type -> R.string.need_time
                step == ShieldStep.Parts -> R.string.yt_channels_need
                else -> R.string.need_apps
            },
        ),
        onPrimary = { if (step == ShieldStep.Review) save() else step = steps[index + 1] },
    ) {
        when (step) {
            ShieldStep.Name -> {
                stepTitle(R.string.shield_name_title, null)
                item(key = "name") { AuroraTextField(name, { name = it.take(40) }, "", capitalization = KeyboardCapitalization.Words) }
            }
            ShieldStep.Apps -> {
                stepTitle(R.string.shield_apps_title, R.string.shield_apps_body)
                appChecklist(apps, exempt, keys.toSet(), query, { query = it }, cap) { pkg ->
                    when {
                        pkg in keys -> keys = keys - pkg
                        cap != null && all.size >= cap -> vm.atLimit(Allowance.ProtectedItems)
                        else -> keys = keys + pkg
                    }
                }
            }
            ShieldStep.Sites -> {
                stepTitle(R.string.shield_sites_title, R.string.shield_sites_body)
                siteEditor(sites) { next ->
                    if (cap != null && next.size > sites.size && all.size - sites.size + next.size > cap) vm.atLimit(Allowance.ProtectedItems) else sites = next
                }
            }
            ShieldStep.Parts -> {
                stepTitle(R.string.shield_parts_title, R.string.shield_parts_body)
                // One card per app, each part a plain choice; the channel list sits right under YouTube.
                AppParts.groupBy { it.owner }.forEach { (owner, group) ->
                    item(key = "parts-$owner") {
                        PartGroup(owner, apps?.find { it.packageName == owner }, group, parts) { part, want ->
                            when {
                                !want -> parts = parts - part.key
                                cap != null && all.size >= cap -> vm.atLimit(Allowance.ProtectedItems)
                                else -> parts = parts + part.key
                            }
                        }
                    }
                    if (owner == YouTube && PartYouTubeChannels in parts) item(key = "channels") { ChannelEditor(channels) { channels = it } }
                }
                item(key = "parts-permission") {
                    // Whether Web & content is on, said plainly: one quiet line when on, one row to turn it on when off.
                    val on = app.aurora.rememberAccess(app.aurora.data.Access.Content) == app.aurora.data.AccessState.Granted
                    if (on) Row(Modifier.padding(start = 4.dp, top = Space.XS), horizontalArrangement = Arrangement.spacedBy(Space.XS), verticalAlignment = Alignment.CenterVertically) {
                        androidx.compose.material3.Icon(AuroraIcons.Check, null, tint = colors.accent, modifier = Modifier.size(16.dp))
                        Text(stringResource(R.string.parts_permission_on), color = colors.textTertiary, style = AuroraType.Caption)
                    } else Column(Modifier.fillMaxWidth().auroraSurface(colors, warm = true)) {
                        AuroraRow(
                            title = stringResource(R.string.parts_permission_enable),
                            subtitle = stringResource(R.string.parts_permission_off),
                            icon = AuroraIcons.Protect, onClick = onOpenWeb, trailing = { Chevron() },
                        )
                    }
                }
            }
            ShieldStep.Type -> {
                stepTitle(R.string.shield_type_title, null)
                items(Mode.entries, key = { "mode-" + it.name }) { m -> ChoiceCard(modeName(m), modeDescription(m), mode == m, { mode = m }, icon = modeIcon(m)) }
                if (mode.strict) item(key = "ab-note") {
                    Text(stringResource(R.string.shield_always_block_note), Modifier.padding(top = Space.XS), color = colors.mutedText, style = AuroraType.Secondary)
                }
                // The chosen type's settings follow on the same step.
                if (mode == Mode.Friction) {
                item(key = "friction-title") { SettingsTitle(stringResource(R.string.start_friction_title), stringResource(R.string.start_friction_body)) }
                items(FrictionTasks, key = { "task-" + it.name }) { t -> ChoiceCard(taskName(t), taskDescription(t), task == t, { task = t }) }
                if (task == FrictionTask.Math) item(key = "difficulty") {
                    Column(verticalArrangement = Arrangement.spacedBy(Space.XS)) {
                        Text(stringResource(R.string.friction_difficulty), Modifier.padding(top = Space.S), color = colors.textPrimary, style = AuroraType.Title)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.XS)) { Difficulty.entries.forEach { dif -> Chip(difficultyName(dif), difficulty == dif) { difficulty = dif } } }
                    }
                }
                if (task == FrictionTask.Intention) item(key = "phrase") {
                    Column(verticalArrangement = Arrangement.spacedBy(Space.XS)) {
                        Text(stringResource(R.string.friction_phrase_title), Modifier.padding(top = Space.S), color = colors.textPrimary, style = AuroraType.Title)
                        Text(stringResource(R.string.friction_phrase_hint), color = colors.textTertiary, style = AuroraType.Secondary)
                        AuroraTextField(phrase, { phrase = it.take(120) }, stringResource(R.string.friction_phrase_placeholder), Modifier.padding(top = Space.XS))
                    }
                }
                item(key = "access") { Text(stringResource(R.string.usage_hint), color = colors.textTertiary, style = AuroraType.Caption) }
            } else if (mode == Mode.Cooldown) {
                item(key = "wait-title") { SettingsTitle(stringResource(R.string.duration_cooldown_prompt), stringResource(R.string.duration_cooldown_hint)) }
                item(key = "wait") { DurationPicker(wait, { wait = it }, ShieldWaitPresets, max = 12 * 60) }
                item(key = "restart") { Text(stringResource(R.string.shield_restart_note), color = colors.mutedText, style = AuroraType.BodyStrong) }
            }
            }
            ShieldStep.Review -> {
                stepTitle(R.string.cfg_review_title, R.string.shield_review_note)
                item(key = "review") {
                    val preview = ShieldPreset(initial.id, name.trim(), all, mode, FrictionSettings(task = task, difficulty = difficulty, reflectionSeconds = pause, phrase = phrase.trim()), wait ?: 1)
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
        Modifier.fillMaxWidth().auroraSurface(colors, edge = modeColor(p.mode)).padding(horizontal = 22.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(Space.S),
    ) {
        ModeChip(p.mode)
        Text(p.name, color = colors.textPrimary, style = AuroraType.Headline)
        Text(shieldDetail(p), color = colors.textSecondary, style = AuroraType.Secondary)
        if (p.keys.isNotEmpty()) app.aurora.ui.protect.IconStack(p.keys, data, max = 8)
    }
}

/**
 * One app's parts: the app's own icon and name, then each part as a short name, one quiet line and a check.
 * Choosing several is normal, so the mark is a check, not a switch.
 */
@Composable
private fun PartGroup(owner: String, installed: InstalledApp?, group: List<AppPart>, chosen: List<String>, onToggle: (AppPart, Boolean) -> Unit) {
    val colors = Aurora.colors
    Column(Modifier.fillMaxWidth().auroraSurface(colors).padding(vertical = 6.dp)) {
        Row(
            Modifier.padding(horizontal = 18.dp).padding(top = 12.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (installed != null) AppIcon(installed, size = 26.dp)
            Eyebrow(installed?.label ?: stringResource(if (owner == Instagram) R.string.brand_instagram else R.string.brand_youtube), Modifier.semantics { heading() }, color = colors.textSecondary)
        }
        group.forEachIndexed { i, part ->
            if (i > 0) RowDivider(inset = 18.dp)
            val on = part.key in chosen
            Row(
                Modifier.fillMaxWidth().toggleable(on, role = Role.Checkbox) { onToggle(part, it) }.padding(horizontal = 18.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.M),
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(stringResource(part.short), color = colors.textPrimary, style = AuroraType.BodyStrong)
                    Text(stringResource(part.body), color = colors.textTertiary, style = AuroraType.Secondary.copy(fontSize = 12.5.sp, lineHeight = 17.sp))
                }
                SelectionMark(on)
            }
        }
    }
}

/** The channels YouTube may play from: type a name or @handle, add it; each one can be removed. */
@Composable
private fun ChannelEditor(channels: List<String>, onChange: (List<String>) -> Unit) {
    val colors = Aurora.colors
    var text by rememberSaveable { mutableStateOf("") }
    val add = {
        val name = cleanChannel(text)
        if (name.isNotEmpty() && channels.none { it.equals(name, ignoreCase = true) }) onChange(channels + name)
        text = ""
    }
    Column(verticalArrangement = Arrangement.spacedBy(Space.XS)) {
        Text(stringResource(R.string.part_yt_channels), Modifier.padding(top = Space.S), color = colors.textPrimary, style = AuroraType.Title)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.XS)) {
            AuroraTextField(text, { text = it.take(100) }, stringResource(R.string.yt_channels_placeholder), Modifier.weight(1f), onDone = add)
            AuroraButton(stringResource(R.string.shield_sites_add), add, kind = ButtonKind.Secondary, compact = true, enabled = text.isNotBlank())
        }
        Text(stringResource(R.string.yt_channels_hint), color = colors.textTertiary, style = AuroraType.Caption)
        channels.forEach { name ->
            Row(
                Modifier.fillMaxWidth().auroraSurface(colors, app.aurora.ui.theme.Radius.M).padding(horizontal = Space.M, vertical = Space.XS),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Space.M),
            ) {
                Text(name, Modifier.weight(1f), color = colors.textPrimary, style = AuroraType.BodyStrong)
                app.aurora.ui.components.AuroraIconButton(AuroraIcons.Close, stringResource(R.string.action_remove_channel, name), { onChange(channels - name) })
            }
        }
    }
}
