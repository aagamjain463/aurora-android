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
    var viewing by remember { mutableStateOf<IntentPreset?>(null) }
    val d = data
    AuroraScreen(
        title = stringResource(R.string.tab_intent),
        tabBar = true,
        art = ArtTier.Balanced,
        header = { TabHeader(stringResource(R.string.intent_tab_title), onOpenSettings) },
    ) {
        item(key = "create") {
            AuroraButton(stringResource(R.string.intent_create), onCreate, Modifier.padding(horizontal = Space.Gutter).padding(bottom = Space.XL).fillMaxWidth(), icon = AuroraIcons.Plus)
        }
        if (d == null) return@AuroraScreen
        val running = vm.app.runsNow(d.time).mapNotNull { it.intent?.id }.toSet()
        val presets = d.rules.intentPresets.sortedByDescending { it.createdAt }
        if (presets.isEmpty()) item(key = "empty") {
            EmptyState(stringResource(R.string.intent_lib_empty_title), "")
        }
        items(presets, key = { it.id }) { p ->
            PresetCard(
                p.name, { Tag(intentCount(p.keys), accent = true) }, intentSummary(p.config), p.keys, d, p.id in running,
                onView = { viewing = p },
                onEdit = { onEdit(p.id) },
                onDuplicate = { vm.run { vm.app.saveIntentPreset(p.copy(id = newId(), name = vm.app.getString(R.string.preset_copy_name, p.name), createdAt = System.currentTimeMillis())) } },
                onDelete = { deleting = p },
                modifier = Modifier.padding(bottom = Space.Card),
                edge = Aurora.colors.textSecondary,
            )
        }
    }
    deleting?.let { p -> DeletePresetDialog(vm, p.id, p.name) { deleting = null } }
    val shown = viewing
    if (shown != null && d != null) DetailsDialog(shown.name, intentRows(shown, d), { viewing = null }) { onEdit(shown.id) }
}

/** An Intent in full: its pause and what follows, and every app it asks before. */
@Composable
fun intentRows(p: IntentPreset, d: app.aurora.ui.protect.ProtectData): List<Pair<String, String>> =
    listOf(stringResource(R.string.detail_intent) to intentSummary(p.config)) + keyRows(p.keys, d)

/** "3 apps", "2 websites", or both: what an Intent asks before. */
@Composable
fun intentCount(keys: List<String>): String {
    val sites = keys.count { it.startsWith(app.aurora.protect.WebPrefix) }
    val apps = keys.size - sites
    return listOfNotNull(
        pluralStringResource(R.plurals.count_apps, apps, apps).takeIf { apps > 0 || sites == 0 },
        pluralStringResource(R.plurals.count_sites, sites, sites).takeIf { sites > 0 },
    ).joinToString(" · ")
}

private enum class IntentStep { Name, Apps, Sites, Reflect, Time, Review }

/** Create or edit an Intent: name, apps, websites, reflection, then usage time and what happens after on one step, save. */
@Composable
fun IntentEditorScreen(id: String?, onBack: () -> Unit, onSaved: (String) -> Unit, startStep: Int = 0, onPlus: (() -> Unit)? = null) {
    val vm = auroraViewModel { ProtectModel(it) }
    val data by vm.data.collectAsStateWithLifecycle()
    val d = data ?: return
    // A new preset keeps one id for the whole flow, however often the screen recomposes.
    val fresh = rememberSaveable { newId() }
    val existing = id?.let { i -> d.rules.intentPresets.find { it.id == i } }
    IntentEditor(vm, existing ?: IntentPreset(fresh, "", config = IntentConfig(reflectionSeconds = 5), createdAt = System.currentTimeMillis()), onBack, onSaved, startStep)
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
    var keys by rememberSaveable(initial.id) { mutableStateOf(initial.keys.filterNot { it.startsWith(app.aurora.protect.WebPrefix) }) }
    // Websites ask first too, in supported browsers, with the same pause, time, reminder and cooldown as apps.
    var sites by rememberSaveable(initial.id) { mutableStateOf(initial.keys.filter { it.startsWith(app.aurora.protect.WebPrefix) }.map { it.removePrefix(app.aurora.protect.WebPrefix) }) }
    val all = keys + sites.map { app.aurora.protect.WebPrefix + it }
    val webOn = app.aurora.rememberAccess(app.aurora.data.Access.Content) == app.aurora.data.AccessState.Granted
    var pause by rememberSaveable(initial.id) { mutableIntStateOf(c0.reflectionSeconds) }
    var expiry by rememberSaveable(initial.id) { mutableStateOf(c0.expiry) }
    var cooldown by rememberSaveable(initial.id) { mutableStateOf<Int?>(c0.cooldownMinutes) }
    var query by rememberSaveable { mutableStateOf("") }
    var step by rememberSaveable { mutableStateOf(IntentStep.entries[startStep]) }

    val steps = IntentStep.entries
    val index = steps.indexOf(step)
    val back = { if (index == 0) onBack() else step = steps[index - 1] }
    // No reason, alternative or default time: the pause is the one thing set here, and the time is chosen at every opening.
    val config = IntentConfig(reflectionSeconds = pause, expiry = expiry, cooldownMinutes = cooldown ?: c0.cooldownMinutes, confirmContinue = c0.confirmContinue)
    val canNext = when (step) {
        IntentStep.Name -> name.isNotBlank()
        IntentStep.Apps -> true
        IntentStep.Sites -> all.isNotEmpty()
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
                IntentStep.Apps, IntentStep.Sites -> R.string.need_apps_or_sites
                else -> R.string.need_time
            },
        ),
        onPrimary = {
            if (step != IntentStep.Review) step = steps[index + 1]
            else {
                val preset = initial.copy(name = name.trim(), keys = all, config = config)
                vm.run {
                    if (vm.app.saveIntentPreset(preset) == null) onSaved(preset.id)
                }
            }
        },
    ) {
        when (step) {
            IntentStep.Name -> {
                stepTitle(R.string.intent_name_title, null)
                item(key = "name") { AuroraTextField(name, { name = it.take(40) }, "", capitalization = KeyboardCapitalization.Words) }
            }
            IntentStep.Apps -> {
                stepTitle(R.string.shield_apps_title, R.string.intent_overview_body)
                val cap = vm.data.value?.max(app.aurora.plan.Allowance.IntentApps)
                appChecklist(apps, exempt, keys.toSet(), query, { query = it }, cap) { pkg ->
                    when {
                        pkg in keys -> keys = keys - pkg
                        cap != null && all.size >= cap -> vm.atLimit(app.aurora.plan.Allowance.IntentApps)
                        else -> keys = keys + pkg
                    }
                }
            }
            IntentStep.Sites -> {
                stepTitle(R.string.shield_sites_title, R.string.shield_sites_body)
                val cap = vm.data.value?.max(app.aurora.plan.Allowance.IntentApps)
                siteEditor(sites) { next ->
                    if (cap != null && next.size > sites.size && all.size - sites.size + next.size > cap) vm.atLimit(app.aurora.plan.Allowance.IntentApps) else sites = next
                }
                // Websites are only asked about once Web & content is on: said here, never assumed.
                if (!webOn) item(key = "sites-permission") {
                    app.aurora.ui.protect.PermissionCard(listOf(app.aurora.data.Access.Content), Modifier.padding(top = Space.S))
                }
            }
            IntentStep.Reflect -> {
                stepTitle(R.string.intent_reflection_title, R.string.intent_reflection_hint)
                item(key = "pause") { SecondsPicker(pause) { pause = it } }
            }
            IntentStep.Time -> {
                stepTitle(R.string.intent_usage_title, R.string.intent_usage_body)
                // What happens when the time chosen at the opening runs out.
                item(key = "after-title") { Text(stringResource(R.string.intent_expiry_title), Modifier.padding(top = Space.S), color = colors.textPrimary, style = AuroraType.Title) }
                item(key = "reminder") { ChoiceCard(stringResource(R.string.intent_expiry_reminder), stringResource(R.string.intent_expiry_reminder_desc), expiry == IntentExpiry.Reminder, { expiry = IntentExpiry.Reminder }) }
                item(key = "cooldown") { ChoiceCard(stringResource(R.string.intent_expiry_cooldown), stringResource(R.string.intent_expiry_cooldown_desc), expiry == IntentExpiry.Cooldown, { expiry = IntentExpiry.Cooldown }) }
                if (expiry == IntentExpiry.Cooldown) {
                    item(key = "length") {
                        DurationPicker(cooldown, { cooldown = it }, IntentCooldownPresets, Modifier.padding(top = Space.S), title = stringResource(R.string.intent_cooldown_title), hint = stringResource(R.string.intent_cooldown_hint), max = 12 * 60)
                    }
                    item(key = "restart") { Text(stringResource(R.string.shield_restart_note), color = colors.mutedText, style = AuroraType.BodyStrong) }
                }
            }
            IntentStep.Review -> {
                stepTitle(R.string.cfg_review_title, R.string.shield_review_note)
                item(key = "review") {
                    val data by vm.data.collectAsStateWithLifecycle()
                    Column(Modifier.fillMaxWidth().auroraSurface(colors, warm = true).padding(horizontal = Space.Inner, vertical = 20.dp), verticalArrangement = Arrangement.spacedBy(Space.S)) {
                        Tag(intentCount(all), accent = true)
                        Text(name.trim(), color = colors.textPrimary, style = AuroraType.Headline)
                        Text(intentSummary(config), color = colors.textSecondary, style = AuroraType.Secondary)
                        app.aurora.ui.protect.IconStack(all, data, max = 8)
                    }
                }
            }
        }
    }
}
