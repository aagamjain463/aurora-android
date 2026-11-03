package app.aurora.ui.plus

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import app.aurora.LimitPrompt
import app.aurora.R
import app.aurora.plan.LimitReason
import app.aurora.plan.Remedy
import app.aurora.ui.components.AuroraButton
import app.aurora.ui.components.AuroraDialog
import app.aurora.ui.components.ButtonKind
import app.aurora.ui.components.clockTime
import app.aurora.ui.components.minutesText

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
