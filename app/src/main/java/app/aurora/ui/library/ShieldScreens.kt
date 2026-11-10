package app.aurora.ui.library

import app.aurora.ui.protect.modeIcon
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
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
import app.aurora.ui.components.AppIcon
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
import app.aurora.ui.components.pressable
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
fun ShieldLibraryScreen(onCreate: () -> Unit, onEdit: (String) -> Unit, onOpenSettings: () -> Unit) {
    val vm = auroraViewModel { ProtectModel(it) }
    val data by vm.data.collectAsStateWithLifecycle()
    var deleting by remember { mutableStateOf<ShieldPreset?>(null) }
    var viewing by remember { mutableStateOf<ShieldPreset?>(null) }
    val d = data
    AuroraScreen(
        title = stringResource(R.string.tab_shield),
        tabBar = true,
        art = ArtTier.Balanced,
        header = { TabHeader(stringResource(R.string.shield_library_headline), onOpenSettings) },
    ) {
        item(key = "create") {
            AuroraButton(stringResource(R.string.shield_create), onCreate, Modifier.padding(horizontal = Space.Gutter).padding(bottom = Space.XL).fillMaxWidth(), icon = AuroraIcons.Plus)
        }
        if (d == null) return@AuroraScreen
        val running = vm.app.runsNow(d.time).mapNotNull { it.shield?.id }.toSet()
        val shields = d.rules.shields.sortedByDescending { it.createdAt }
        if (shields.isEmpty()) item(key = "empty") {
            EmptyState(stringResource(R.string.shield_empty_title), "")
        }
        items(shields, key = { it.id }) { p ->
            PresetCard(
                p.name, {}, shieldDetail(p), p.keys, d, p.id in running,
                onView = { viewing = p },
                onEdit = { onEdit(p.id) },
                onDuplicate = { vm.run { vm.app.saveShield(p.copy(id = newId(), name = vm.app.getString(R.string.preset_copy_name, p.name), createdAt = System.currentTimeMillis())) } },
                onDelete = { deleting = p },
                modifier = Modifier.padding(bottom = Space.Card),
            )
        }
    }
    deleting?.let { p -> DeletePresetDialog(vm, p.id, p.name) { deleting = null } }
    val shown = viewing
    if (shown != null && d != null) DetailsDialog(shown.name, shieldRows(shown, d), { viewing = null }) { onEdit(shown.id) }
}

/** A Shield in full: every app, site and part it covers. How strict is the session's to say. */
@Composable
fun shieldRows(p: ShieldPreset, d: app.aurora.ui.protect.ProtectData): List<Pair<String, String>> = keyRows(p.keys, d)

@Composable
fun shieldDetail(p: ShieldPreset): String {
    val sites = p.keys.count { it.startsWith(WebPrefix) }
    val parts = p.keys.count { it.startsWith(PartPrefix) }
    val apps = p.keys.size - sites - parts
    return listOfNotNull(
        pluralStringResource(R.plurals.count_apps, apps, apps),
        sites.takeIf { it > 0 }?.let { pluralStringResource(R.plurals.count_sites, it, it) },
        parts.takeIf { it > 0 }?.let { pluralStringResource(R.plurals.count_parts, it, it) },
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

private enum class ShieldStep { Name, Apps, Sites, Parts, Review }

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
    ShieldEditor(vm, existing ?: ShieldPreset(fresh, "", createdAt = System.currentTimeMillis()), onBack, onSaved, startStep, onOpenWeb)
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
    var query by rememberSaveable { mutableStateOf("") }
    var step by rememberSaveable { mutableStateOf(ShieldStep.entries[startStep]) }

    // Apps, websites and parts of apps each have their own page; any of them can be left empty.
    val steps = ShieldStep.entries
    val all = keys + sites.map { WebPrefix + it } + parts.map { if (it == PartYouTubeChannels) channelsKey(channels) else it }
    // Parts of apps are only held back by Web & content: every part is shown, but choosing one asks for it first.
    val partsOpen = app.aurora.rememberAccess(app.aurora.data.Access.Content) == app.aurora.data.AccessState.Granted
    val askWeb = app.aurora.ui.protect.askFor(app.aurora.data.Access.Content)
    val channelsMissing = PartYouTubeChannels in parts && channels.isEmpty()
    val cap = vm.data.collectAsStateWithLifecycle().value?.max(Allowance.ProtectedItems)
    val index = steps.indexOf(step).coerceAtLeast(0)
    val back = { if (index == 0) onBack() else step = steps[index - 1] }
    val canNext = when (step) {
        ShieldStep.Name -> name.isNotBlank()
        ShieldStep.Apps, ShieldStep.Sites -> true
        ShieldStep.Parts -> !channelsMissing
        ShieldStep.Review -> all.isNotEmpty()
    }
    // A Shield is what is blocked; how strict is the session's (its old mode fields stay only for older sessions).
    val save = {
        val preset = initial.copy(name = name.trim(), keys = all)
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
                step == ShieldStep.Name -> R.string.need_name
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
                stepTitle(R.string.shield_parts_title, null)
                // One card per app, each part a plain choice; the channel list sits right under YouTube.
                AppParts.groupBy { it.owner }.forEach { (owner, group) ->
                    item(key = "parts-$owner") {
                        PartGroup(owner, apps?.find { it.packageName == owner }, group, parts) { part, want ->
                            when {
                                !want -> parts = parts - part.key
                                // Nothing is chosen until Web & content is on: its disclosure, then its switch.
                                !partsOpen -> askWeb()
                                cap != null && all.size >= cap -> vm.atLimit(Allowance.ProtectedItems)
                                else -> parts = parts + part.key
                            }
                        }
                    }
                    if (owner == YouTube && PartYouTubeChannels in parts) item(key = "channels") { ChannelEditor(channels) { channels = it } }
                }
            }
            ShieldStep.Review -> {
                stepTitle(R.string.cfg_review_title, R.string.shield_review_note)
                item(key = "review") {
                    val preview = ShieldPreset(initial.id, name.trim(), all)
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
private fun SettingsTitle(title: String) {
    Text(title, Modifier.padding(top = Space.L), color = Aurora.colors.textPrimary, style = AuroraType.Title)
}

/** The finished Shield as its library card will show it, without actions. */
@Composable
private fun PresetCardPreview(p: ShieldPreset, vm: ProtectModel) {
    val data by vm.data.collectAsStateWithLifecycle()
    val colors = Aurora.colors
    Column(
        Modifier.fillMaxWidth().auroraSurface(colors).padding(horizontal = Space.Inner, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(Space.S),
    ) {
        Text(p.name, color = colors.textPrimary, style = AuroraType.Headline)
        Text(shieldDetail(p), color = colors.textSecondary, style = AuroraType.Secondary)
        if (p.keys.isNotEmpty()) app.aurora.ui.protect.IconStack(p.keys, data, max = 8)
    }
}

/**
 * One app's parts: the app's icon and name on the page, then a card of short names, each with its check.
 * What a part does waits behind the info button beside its name. Choosing several is normal, so the mark is a check, not a switch.
 */
@Composable
private fun PartGroup(owner: String, installed: InstalledApp?, group: List<AppPart>, chosen: List<String>, onToggle: (AppPart, Boolean) -> Unit) {
    val colors = Aurora.colors
    var about by remember { mutableStateOf<AppPart?>(null) }
    Column(Modifier.fillMaxWidth().padding(top = Space.M), verticalArrangement = Arrangement.spacedBy(Space.M)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.S)) {
            if (installed != null) AppIcon(installed, size = 28.dp)
            Text(installed?.label ?: stringResource(if (owner == Instagram) R.string.brand_instagram else R.string.brand_youtube), Modifier.semantics { heading() }, color = colors.textPrimary, style = AuroraType.Title)
        }
        Column(Modifier.fillMaxWidth().auroraSurface(colors)) {
            group.forEachIndexed { i, part ->
                if (i > 0) RowDivider(inset = 20.dp)
                val on = part.key in chosen
                val label = stringResource(R.string.part_info, stringResource(part.short))
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 72.dp).toggleable(on, role = Role.Checkbox) { onToggle(part, it) }.padding(horizontal = 20.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // The info marks and checks each hold one column, however long the name.
                    Text(stringResource(part.short), Modifier.weight(1f), color = colors.textPrimary, style = AuroraType.BodyStrong)
                    Box(Modifier.padding(horizontal = Space.XS).size(44.dp).pressable(app.aurora.ui.theme.Radius.Pill, onClickLabel = label) { about = part }, contentAlignment = Alignment.Center) {
                        androidx.compose.material3.Icon(AuroraIcons.Info, label, tint = colors.textTertiary, modifier = Modifier.size(18.dp))
                    }
                    SelectionMark(on)
                }
            }
        }
    }
    about?.let { part ->
        AuroraDialog(stringResource(part.short), { about = null }, stringResource(R.string.settings_got_it), { about = null }, stringResource(part.body))
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


/**
 * How strict a session is, chosen on its own step: the four modes, the strict note, and the chosen mode's settings
 * (Friction's challenge, Cooldown's wait) on the same page. The session holds these; a Shield only says what is blocked.
 */
@OptIn(ExperimentalLayoutApi::class)
fun androidx.compose.foundation.lazy.LazyListScope.strictnessItems(
    mode: Mode, onPick: (Mode) -> Unit,
    task: FrictionTask, onTask: (FrictionTask) -> Unit,
    difficulty: Difficulty, onDifficulty: (Difficulty) -> Unit,
    phrase: String, onPhrase: (String) -> Unit,
    wait: Int?, onWait: (Int?) -> Unit,
) {
    stepTitle(R.string.cfg_strictness_title, R.string.cfg_strictness_body)
    // Just the names; what a mode does is said in the screen's confirm dialog ([StrictnessConfirm]) when one is picked.
    items(Mode.entries, key = { "mode-" + it.name }) { m -> ChoiceCard(modeName(m), "", mode == m, { if (mode != m) onPick(m) }, icon = modeIcon(m)) }
    if (mode.strict) item(key = "ab-note") {
        Text(stringResource(R.string.shield_always_block_note), Modifier.padding(top = Space.XS), color = Aurora.colors.mutedText, style = AuroraType.Secondary)
    }
    if (mode == Mode.Friction) {
        item(key = "friction-title") { SettingsTitle(stringResource(R.string.start_friction_title)) }
        items(FrictionTasks, key = { "task-" + it.name }) { t -> ChoiceCard(taskName(t), taskDescription(t), task == t, { onTask(t) }) }
        if (task == FrictionTask.Math) item(key = "difficulty") {
            Column(verticalArrangement = Arrangement.spacedBy(Space.XS)) {
                Text(stringResource(R.string.friction_difficulty), Modifier.padding(top = Space.S), color = Aurora.colors.textPrimary, style = AuroraType.Title)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.XS)) { Difficulty.entries.forEach { dif -> Chip(difficultyName(dif), difficulty == dif) { onDifficulty(dif) } } }
            }
        }
        if (task == FrictionTask.Intention) item(key = "phrase") {
            Column(verticalArrangement = Arrangement.spacedBy(Space.XS)) {
                Text(stringResource(R.string.friction_phrase_title), Modifier.padding(top = Space.S), color = Aurora.colors.textPrimary, style = AuroraType.Title)
                Text(stringResource(R.string.friction_phrase_hint), color = Aurora.colors.textTertiary, style = AuroraType.Secondary)
                AuroraTextField(phrase, { onPhrase(it.take(120)) }, stringResource(R.string.friction_phrase_placeholder), Modifier.padding(top = Space.XS))
            }
        }
        item(key = "access") { Text(stringResource(R.string.usage_hint), color = Aurora.colors.textTertiary, style = AuroraType.Caption) }
    } else if (mode == Mode.Cooldown) {
        item(key = "wait-title") { SettingsTitle(stringResource(R.string.duration_cooldown_prompt)) }
        item(key = "wait") { DurationPicker(wait, onWait, ShieldWaitPresets, max = 12 * 60) }
        item(key = "restart") { Text(stringResource(R.string.shield_restart_note), color = Aurora.colors.mutedText, style = AuroraType.BodyStrong) }
    }
}

/** Asks to confirm a session's strictness, saying what [mode] does. */
@Composable
fun StrictnessConfirm(mode: Mode, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    app.aurora.ui.components.AuroraDialog(
        title = stringResource(R.string.cfg_strictness_confirm_title, modeName(mode)),
        onDismiss = onDismiss,
        confirmLabel = stringResource(R.string.cfg_strictness_confirm_action, modeName(mode)),
        onConfirm = onConfirm,
        dismissLabel = stringResource(R.string.action_cancel),
        body = modeDescription(mode),
        icon = modeIcon(mode),
    )
}
