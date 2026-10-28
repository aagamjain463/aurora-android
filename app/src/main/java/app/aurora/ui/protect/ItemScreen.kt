package app.aurora.ui.protect

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.aurora.auroraViewModel
import app.aurora.data.Access
import app.aurora.data.AccessState
import app.aurora.plan.Allowance
import app.aurora.protect.AppRule
import app.aurora.protect.DailyLimit
import app.aurora.protect.IntentNote
import app.aurora.protect.Mode
import app.aurora.protect.WebPrefix
import app.aurora.protect.itemOf
import app.aurora.rememberAccess
import app.aurora.ui.components.AuroraButton
import app.aurora.ui.components.AuroraScreen
import app.aurora.ui.components.AuroraTextField
import app.aurora.ui.components.ButtonKind
import app.aurora.ui.components.ChipFlow
import app.aurora.ui.components.ChoicePills
import app.aurora.ui.components.Notice
import app.aurora.ui.components.RadioRow
import app.aurora.ui.components.RowDivider
import app.aurora.ui.components.RowGroup
import app.aurora.ui.components.SectionLabel
import app.aurora.ui.components.SkeletonRows
import app.aurora.ui.components.ToggleRow
import app.aurora.ui.theme.Aurora
import app.aurora.ui.theme.AuroraType
import app.aurora.ui.theme.Space
import kotlinx.coroutines.delay

/** How a mode reads when choosing it. Friction and Cooldown differ in what they space out. */
fun Mode.title(): String = when (this) {
    Mode.Flexible -> "Flexible"
    Mode.Friction -> "Friction"
    Mode.Cooldown -> "Cooldown"
    Mode.Block -> "Always blocked"
    Mode.RulesOnly -> "Only during rules"
}

fun Mode.explain(): String = when (this) {
    Mode.Flexible -> "A moment of awareness each time it opens. Continue whenever you mean to."
    Mode.Friction -> "A short pause before every opening. Afterwards, you choose."
    Mode.Cooldown -> "Use it for a while, then it rests before the next session."
    Mode.Block -> "Shielded every time, with no way through."
    Mode.RulesOnly -> "No everyday Shield. Schedules, Focus, Lockdown and limits still apply."
}

private val PauseSeconds = listOf(5, 10, 20, 30)
private val RestMinutes = listOf(15, 30, 60, 120)
private val LimitMinutes = listOf(10, 20, 30, 60, 90)

/** One protected app or site: how aurora steps in, the person's own reason, its daily budget, its groups. */
@Composable
fun ItemScreen(key: String, onBack: () -> Unit, onPlus: () -> Unit) {
    val vm = auroraViewModel { ProtectModel(it) }
    val data by vm.data.collectAsStateWithLifecycle()
    val notice by vm.notice.collectAsStateWithLifecycle()
    val usage = rememberAccess(Access.Usage)
    val d = data
    val website = key.startsWith(WebPrefix)
    val colors = Aurora.colors

    AuroraScreen(title = d?.label(key) ?: "", subtitle = if (website) "Website" else null, onBack = onBack) {
        if (d == null) {
            item { SkeletonRows(4) }
            return@AuroraScreen
        }
        val rule = d.rules.rule(key)
        fun save(change: (AppRule) -> AppRule) = vm.edit { r -> r.copy(apps = r.apps + (key to change(r.rule(key)))) }

        item(key = "mode") {
            SectionLabel("Everyday protection")
            RowGroup {
                Mode.entries.forEachIndexed { i, mode ->
                    if (i > 0) RowDivider()
                    RadioRow(mode.title(), mode.explain(), rule.mode == mode, onClick = { save { it.copy(mode = mode) } })
                }
            }
            when (rule.mode) {
                Mode.Friction -> Column(Modifier.padding(horizontal = Space.M).padding(top = Space.M), verticalArrangement = Arrangement.spacedBy(Space.XS)) {
                    Text("Pause before opening", Modifier.padding(start = Space.XS), color = colors.textSecondary, style = AuroraType.Secondary)
                    ChoicePills(PauseSeconds, rule.waitSeconds, { s -> save { it.copy(waitSeconds = s) } }, label = { "$it s" }, fill = true, spokenLabel = { "$it seconds" })
                }
                Mode.Cooldown -> Column(Modifier.padding(horizontal = Space.M).padding(top = Space.M), verticalArrangement = Arrangement.spacedBy(Space.XS)) {
                    Text("Rest after each session", Modifier.padding(start = Space.XS), color = colors.textSecondary, style = AuroraType.Secondary)
                    ChoicePills(RestMinutes, rule.cooldownMinutes, { m -> save { it.copy(cooldownMinutes = m) } }, label = { if (it < 60) "$it min" else "${it / 60} h" }, fill = true, spokenLabel = { "$it minutes" })
                }
                else -> Unit
            }
        }

        item(key = "intent") {
            val intentOn = rule.intent?.enabled == true
            val activeIntents = d.rules.activeIntents(d.plan)
            SectionLabel("Intent")
            RowGroup {
                ToggleRow(
                    title = "Remind me why",
                    subtitle = when {
                        intentOn && key !in activeIntents -> "Paused: aurora Free reminds you on ${d.max(Allowance.IntentApps)} apps."
                        rule.mode == Mode.Block || rule.mode == Mode.RulesOnly -> "Shows your reason whenever the Shield appears."
                        else -> "Your own words, and a moment to reflect, before it opens."
                    },
                    checked = intentOn,
                    onCheckedChange = { on ->
                        if (!on) save { it.copy(intent = it.intent?.copy(enabled = false)) }
                        else vm.enableWithin(Allowance.IntentApps, activeIntents.size) { r ->
                            val current = r.rule(key)
                            r.copy(apps = r.apps + (key to current.copy(intent = (current.intent ?: IntentNote(defaultReason(d.rules.motivations))).copy(enabled = true))))
                        }
                    },
                )
            }
            if (intentOn) IntentEditor(rule.intent!!) { note -> save { it.copy(intent = note) } }
        }

        if (!website) item(key = "limit") {
            val limit = d.rules.limits.find { it.key == key }
            val activeLimits = d.rules.activeLimits(d.plan)
            SectionLabel("Daily limit")
            RowGroup {
                ToggleRow(
                    title = "Limit time each day",
                    subtitle = when {
                        limit?.enabled == true && limit !in activeLimits -> "Paused: aurora Free includes ${d.max(Allowance.DailyLimitRules)} daily limits."
                        limit?.enabled == true -> "Shielded once today's ${limit.minutes} minutes are used. Resets at midnight."
                        else -> "When the time is used, it stays closed until midnight."
                    },
                    checked = limit?.enabled == true,
                    onCheckedChange = { on ->
                        if (!on) vm.edit { r -> r.copy(limits = r.limits.map { if (it.key == key) it.copy(enabled = false) else it }) }
                        else vm.enableWithin(Allowance.DailyLimitRules, activeLimits.size) { r ->
                            r.copy(limits = r.limits.filterNot { it.key == key } + (limit?.copy(enabled = true) ?: DailyLimit(key, 30)))
                        }
                    },
                )
            }
            if (limit?.enabled == true) {
                ChoicePills(
                    LimitMinutes, limit.minutes,
                    { m -> vm.edit { r -> r.copy(limits = r.limits.map { if (it.key == key) it.copy(minutes = m) else it }) } },
                    label = { "$it min" }, fill = true, spokenLabel = { "$it minutes a day" },
                    modifier = Modifier.padding(horizontal = Space.M).padding(top = Space.M),
                )
                if (usage != AccessState.Granted) Notice(
                    "Needs usage access",
                    "aurora measures today's time from Android's usage records. Until you allow usage access, this limit can't be enforced.",
                    Modifier.padding(top = Space.M),
                )
            }
        }

        if (d.rules.groups.isNotEmpty()) item(key = "groups") {
            SectionLabel("Groups")
            ChipFlow(
                options = d.rules.groups,
                selected = d.rules.groups.filter { key in it.keys }.toSet(),
                onToggle = { g ->
                    vm.edit { r -> r.copy(groups = r.groups.map { if (it.id == g.id) it.copy(keys = if (key in it.keys) it.keys - key else it.keys + key) else it }) }
                },
                label = { it.name },
                modifier = Modifier.padding(horizontal = Space.M),
            )
        }

        item(key = "remove") {
            Row(Modifier.fillMaxWidth().padding(top = Space.XL), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                AuroraButton("Stop protecting", {
                    itemOf(key)?.let(vm::remove)
                    onBack()
                }, kind = ButtonKind.Quiet)
            }
        }
    }
    LimitDialog(notice, vm::dismissNotice, onPlus)
}

/** A first reason built from what the person said they want more time for. They can change it. */
fun defaultReason(motivations: List<String>): String =
    motivations.firstOrNull()?.let { "I want more time for ${it.lowercase()}." } ?: "I want to spend my time on purpose."

/** Reason, alternative and reflection length. Typing is saved shortly after the person stops. */
@Composable
private fun IntentEditor(note: IntentNote, onChange: (IntentNote) -> Unit) {
    val colors = Aurora.colors
    var reason by rememberSaveable { mutableStateOf(note.reason) }
    var alternative by rememberSaveable { mutableStateOf(note.alternative) }
    LaunchedEffect(reason, alternative) {
        delay(400)
        if (reason.isNotBlank() && (reason != note.reason || alternative != note.alternative)) {
            onChange(note.copy(reason = reason.trim(), alternative = alternative.trim()))
        }
    }
    Column(Modifier.padding(horizontal = Space.M).padding(top = Space.M), verticalArrangement = Arrangement.spacedBy(Space.S)) {
        Text("Why you'd rather not open it", Modifier.padding(start = Space.XS), color = colors.textSecondary, style = AuroraType.Secondary)
        AuroraTextField(reason, { if (it.length <= 120) reason = it }, "I want to spend more time building my future.", imeAction = ImeAction.Next, capitalization = KeyboardCapitalization.Sentences)
        Text("What you'd rather do instead", Modifier.padding(start = Space.XS), color = colors.textSecondary, style = AuroraType.Secondary)
        AuroraTextField(alternative, { if (it.length <= 80) alternative = it }, "Read, study, go for a walk… (optional)", capitalization = KeyboardCapitalization.Sentences)
        Text("Moment to reflect", Modifier.padding(start = Space.XS, top = Space.XS), color = colors.textSecondary, style = AuroraType.Secondary)
        ChoicePills(PauseSeconds, note.reflectionSeconds, { onChange(note.copy(reflectionSeconds = it)) }, label = { "$it s" }, fill = true, spokenLabel = { "$it seconds" })
    }
}
