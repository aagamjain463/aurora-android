package app.aurora.ui.plus

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.aurora.AuroraApplication
import app.aurora.R
import app.aurora.account.RemoteTrial
import app.aurora.account.TrialOutcome
import app.aurora.ui.components.PlusBadge
import app.aurora.ui.components.PlusSurface
import app.aurora.ui.theme.Aurora
import app.aurora.ui.theme.AuroraType
import app.aurora.ui.theme.Space
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.temporal.ChronoUnit

/**
 * The trial's claim, shared by the Plus screen and onboarding: busy while it runs, then the server's reason in words
 * when it is refused. [claim] returns true when the trial was granted.
 */
class TrialClaim(val busy: Boolean, val message: String?, val claim: () -> Unit)

@Composable
fun rememberTrialClaim(onGranted: () -> Unit = {}): TrialClaim {
    val app = LocalContext.current.applicationContext as AuroraApplication
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val refusals = mapOf(
        "account_used" to stringResource(R.string.trial_refused_used),
        "device_used" to stringResource(R.string.trial_refused_used),
        "purchased" to stringResource(R.string.trial_refused_purchased),
        "device_unverifiable" to stringResource(R.string.trial_refused_device),
        "device_ineligible" to stringResource(R.string.trial_refused_device),
    )
    val other = stringResource(R.string.trial_refused_other)
    val offline = stringResource(R.string.account_offline)
    return TrialClaim(busy, message) {
        if (!busy) {
            busy = true
            scope.launch {
                val outcome = app.account.claimTrial()
                message = when (outcome) {
                    is TrialOutcome.Granted -> null
                    is TrialOutcome.Refused -> refusals[outcome.reason] ?: other
                    TrialOutcome.Unreachable -> offline
                }
                app.account.sync()
                busy = false
                if (outcome is TrialOutcome.Granted) onGranted()
            }
        }
    }
}

/** Whole calendar days from [now] to [end] in [zone]: 0 on the last day, never negative. */
fun trialDaysLeft(end: Long, now: Long, zone: ZoneId): Int =
    ChronoUnit.DAYS.between(Instant.ofEpochMilli(now).atZone(zone).toLocalDate(), Instant.ofEpochMilli(end).atZone(zone).toLocalDate()).toInt().coerceAtLeast(0)

/**
 * Home's trial line while the trial runs: how many days are left, or the hour it ends on its last day, and the date.
 * A Plus surface (gold is Plus's), tappable to the Plus screen. The end is the server's, from the signed snapshot.
 */
@Composable
fun TrialCountdown(end: Long, now: Long, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val zone = ZoneId.systemDefault()
    val days = trialDaysLeft(end, now, zone)
    val endAt = Instant.ofEpochMilli(end).atZone(zone)
    val headline = if (days == 0) stringResource(R.string.trial_ends_today, endAt.format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)))
    else pluralStringResource(R.plurals.trial_days_left, days, days)
    val detail = stringResource(R.string.trial_countdown_detail, endAt.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)))
    PlusSurface(modifier.padding(horizontal = Space.Gutter).fillMaxWidth().semantics(mergeDescendants = true) { }.clickable(role = Role.Button, onClick = onOpen)) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.S)) {
                PlusBadge(text = stringResource(R.string.trial_badge))
            }
            Text(headline, color = Aurora.colors.textPrimary, style = AuroraType.Headline)
            Text(detail, color = Aurora.colors.textSecondary, style = AuroraType.Secondary)
        }
    }
}

/** Onboarding's last page when the server offers this account a trial: what it is, in the approved words. */
@Composable
fun TrialOfferPage(message: String?) {
    val colors = Aurora.colors
    Column(Modifier.fillMaxWidth().padding(horizontal = Space.Gutter), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Space.M)) {
        PlusBadge()
        Text(stringResource(R.string.trial_offer_title), color = colors.textPrimary, style = AuroraType.Display, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        Text(stringResource(R.string.trial_offer_body), color = colors.textSecondary, style = AuroraType.Body, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        message?.let { Text(it, color = colors.textSecondary, style = AuroraType.Secondary, textAlign = androidx.compose.ui.text.style.TextAlign.Center) }
    }
}

/** Whether onboarding should offer the trial: the server says this account may have one, and it is on Free. */
fun RemoteTrial?.offered(onPlus: Boolean): Boolean = this?.status == "available" && !onPlus
