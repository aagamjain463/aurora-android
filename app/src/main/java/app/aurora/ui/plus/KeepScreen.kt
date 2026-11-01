package app.aurora.ui.plus

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.aurora.LimitPrompt
import app.aurora.R
import app.aurora.auroraViewModel
import app.aurora.chooseKept
import app.aurora.plan.Allowance
import app.aurora.plan.FreeLimits
import app.aurora.plan.LimitReason
import app.aurora.plan.Remedy
import app.aurora.progress.GoalStatus
import app.aurora.protect.requestedIntent
import app.aurora.protect.requestedShield
import app.aurora.protect.retainedIntent
import app.aurora.protect.retainedShield
import app.aurora.ui.components.AuroraButton
import app.aurora.ui.components.AuroraDialog
import app.aurora.ui.components.AuroraScreen
import app.aurora.ui.components.ButtonKind
import app.aurora.ui.components.SectionLabel
import app.aurora.ui.components.clockTime
import app.aurora.ui.components.minutesText
import app.aurora.ui.library.PickRow
import app.aurora.ui.protect.ProtectModel
import app.aurora.ui.theme.Aurora
import app.aurora.ui.theme.AuroraType
import app.aurora.ui.theme.Space
import java.time.ZoneId

/**
 * After Plus ends: the person chooses what stays active on Free. Nothing here deletes or ends anything: what
 * they don't choose is paused, a committed session already running continues, and renewing restores it all.
 * Until they choose, the oldest stay active.
 */
@Composable
fun KeepScreen(onBack: () -> Unit, onPlus: () -> Unit) {
    val vm = auroraViewModel { ProtectModel(it) }
    val data by vm.data.collectAsStateWithLifecycle()
    val d = data ?: return
    val zone = ZoneId.systemDefault()
    var shield by remember { mutableStateOf<Set<String>?>(null) }
    var intent by remember { mutableStateOf<Set<String>?>(null) }
    var goal by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        shield = d.rules.retainedShield(d.plan, d.time.wall, zone).orEmpty()
        intent = d.rules.retainedIntent(d.plan, d.time.wall, zone).orEmpty()
        goal = d.rules.keep.goalId ?: d.rules.trackGoals.firstOrNull { it.status == GoalStatus.Active }?.id
    }
    val maxShield = FreeLimits.getValue(Allowance.ProtectedItems)
    val maxIntent = FreeLimits.getValue(Allowance.IntentApps)
    val shieldKeys = d.rules.requestedShield(d.time.wall, zone)
    val intentKeys = d.rules.requestedIntent(d.time.wall, zone)
    val goals = d.rules.trackGoals.filter { it.status == GoalStatus.Active }

    AuroraScreen(title = stringResource(R.string.keep_title), subtitle = stringResource(R.string.keep_subtitle), onBack = onBack) {
        if (shieldKeys.size > maxShield) {
            item(key = "shield-label") { SectionLabel(stringResource(R.string.keep_shield, shield.orEmpty().size, maxShield)) }
            shieldKeys.forEach { key ->
                item(key = "s-$key") {
                    val on = key in shield.orEmpty()
                    PickRow(d.label(key).ifEmpty { key }, "", on, { shield = if (on) shield.orEmpty() - key else if (shield.orEmpty().size < maxShield) shield.orEmpty() + key else shield })
                }
            }
        }
        if (intentKeys.size > maxIntent) {
            item(key = "intent-label") { SectionLabel(stringResource(R.string.keep_intent, intent.orEmpty().size, maxIntent)) }
            intentKeys.forEach { key ->
                item(key = "i-$key") {
                    val on = key in intent.orEmpty()
                    PickRow(d.label(key).ifEmpty { key }, "", on, { intent = if (on) intent.orEmpty() - key else if (intent.orEmpty().size < maxIntent) intent.orEmpty() + key else intent })
                }
            }
        }
        if (goals.size > FreeLimits.getValue(Allowance.ActiveGoals)) {
            item(key = "goal-label") { SectionLabel(stringResource(R.string.keep_goal)) }
            goals.forEach { g -> item(key = "g-${g.id}") { PickRow(g.name, "", goal == g.id, { goal = g.id }) } }
        }
        item(key = "save") {
            Column(Modifier.padding(horizontal = Space.Gutter).padding(top = Space.M), verticalArrangement = Arrangement.spacedBy(Space.XS)) {
                AuroraButton(stringResource(R.string.keep_save), {
                    vm.run {
                        vm.app.chooseKept(shield.orEmpty(), intent.orEmpty(), goal)
                        onBack()
                    }
                }, Modifier.fillMaxWidth())
                AuroraButton(stringResource(R.string.plus_see), onPlus, Modifier.fillMaxWidth(), kind = ButtonKind.Quiet)
                Text(stringResource(R.string.keep_note), color = Aurora.colors.textTertiary, style = AuroraType.Caption)
            }
        }
    }
}

/** Why a plan limit stopped something, and what can be done. Dismissing it changes nothing: the action already didn't happen. */
@Composable
fun LimitPromptDialog(prompt: LimitPrompt, onDismiss: () -> Unit, onPlus: () -> Unit) {
    val d = prompt.decision
    val max = (d.limit ?: 0).toInt()
    val (title, body) = when (d.reason) {
        LimitReason.ShieldTargets -> stringResource(R.string.limit_items_title) to androidx.compose.ui.res.pluralStringResource(R.plurals.limit_items_body, max, max)
        LimitReason.IntentApps -> androidx.compose.ui.res.pluralStringResource(R.plurals.limit_intent_title, max, max) to androidx.compose.ui.res.pluralStringResource(R.plurals.limit_intent_body, max, max)
        LimitReason.CommittedProtection -> stringResource(R.string.limit_always_title) to
            if (d.remedy == Remedy.ChooseShorter) stringResource(R.string.limit_committed_shorter, minutesText(max / 60_000), minutesText(((d.remaining ?: 0) / 60_000).toInt()), clockTime(d.resetAt ?: 0))
            else stringResource(R.string.limit_committed_used, minutesText(max / 60_000), clockTime(d.resetAt ?: 0))
        else -> stringResource(R.string.plus_name) to ""
    }
    AuroraDialog(title, onDismiss, stringResource(R.string.plus_see), { onDismiss(); onPlus() }, body, stringResource(R.string.action_cancel)) {
        val shorter = prompt.shorter
        val minutes = ((d.remaining ?: 0) / 60_000).toInt()
        if (shorter != null && d.remedy == Remedy.ChooseShorter) {
            AuroraButton(stringResource(R.string.limit_start_shorter, minutesText(minutes)), { onDismiss(); shorter(minutes) }, Modifier.fillMaxWidth(), kind = ButtonKind.Secondary)
        }
    }
}
