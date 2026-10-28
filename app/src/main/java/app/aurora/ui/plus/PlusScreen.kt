package app.aurora.ui.plus

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.aurora.AuroraApplication
import app.aurora.BuildConfig
import app.aurora.plan.Allowance
import app.aurora.plan.BillingState
import app.aurora.plan.FreeLimits
import app.aurora.plan.Plan
import app.aurora.plan.openSubscriptionSettings
import app.aurora.ui.components.AuroraButton
import app.aurora.ui.components.AuroraLight
import app.aurora.ui.components.AuroraScreen
import app.aurora.ui.components.ButtonKind
import app.aurora.ui.components.Notice
import app.aurora.ui.components.RowDivider
import app.aurora.ui.components.RowGroup
import app.aurora.ui.components.SectionLabel
import app.aurora.ui.theme.Aurora
import app.aurora.ui.theme.AuroraType
import app.aurora.ui.theme.Space
import kotlinx.coroutines.launch

/** (feature, Free, Plus) */
private val Compare = listOf(
    Triple("Protected apps and sites", "${FreeLimits.getValue(Allowance.ProtectedItems)}", "Unlimited"),
    Triple("Lockdown time each day", "${FreeLimits.getValue(Allowance.LockdownMinutesPerDay)} min", "Unlimited"),
    Triple("Apps with Intent", "${FreeLimits.getValue(Allowance.IntentApps)}", "Unlimited"),
    Triple("Daily limits", "${FreeLimits.getValue(Allowance.DailyLimitRules)}", "Unlimited"),
    Triple("Schedules", "${FreeLimits.getValue(Allowance.Schedules)}", "Unlimited"),
    Triple("Focus profiles", "${FreeLimits.getValue(Allowance.FocusProfiles)}", "Unlimited"),
    Triple("Focus sessions, goals, progress", "Included", "Included"),
    Triple("Insights", "Today and this week", "Plus 30 days"),
)

/**
 * aurora plus. Calm and honest: what Free already includes, what Plus adds, prices exactly as Google Play
 * shows them, and no countdowns or "only today" offers. Never shown during active protection.
 */
@Composable
fun PlusScreen(onBack: () -> Unit) {
    val app = LocalContext.current.applicationContext as AuroraApplication
    val context = LocalContext.current
    val billing by app.billing.state.collectAsStateWithLifecycle()
    val plan by app.billing.plan.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val colors = Aurora.colors
    LaunchedEffect(Unit) { app.billing.connect() }

    AuroraScreen(title = "aurora plus", onBack = onBack) {
        item(key = "hero") {
            AuroraLight(glow = 0.85f, rise = 0.7f, warmth = 0.6f, modifier = Modifier.fillMaxWidth().height(160.dp))
            Column(Modifier.padding(horizontal = Space.Gutter)) {
                Text(
                    if (plan == Plan.Plus) "You have aurora plus." else "More room, for every part of your day.",
                    color = colors.textPrimary,
                    style = AuroraType.Headline,
                )
                Spacer(Modifier.height(Space.XS))
                Text(
                    "Free protects just as strongly as Plus. Plus removes the limits, so aurora can cover everything you care about.",
                    color = colors.textSecondary,
                    style = AuroraType.Body,
                )
            }
        }
        item(key = "compare") {
            SectionLabel("Free and plus")
            RowGroup {
                Row(Modifier.fillMaxWidth().padding(horizontal = Space.M, vertical = Space.S)) {
                    Spacer(Modifier.weight(1f))
                    Text("free", Modifier.width(88.dp), color = colors.textTertiary, style = AuroraType.Eyebrow, textAlign = TextAlign.End)
                    Text("plus", Modifier.width(96.dp), color = colors.accent, style = AuroraType.Eyebrow, textAlign = TextAlign.End)
                }
                Compare.forEach { (feature, free, plus) ->
                    RowDivider()
                    Row(Modifier.fillMaxWidth().padding(Space.M).semantics(mergeDescendants = true) { }, verticalAlignment = Alignment.CenterVertically) {
                        Text(feature, Modifier.weight(1f), color = colors.textPrimary, style = AuroraType.Secondary)
                        Text(free, Modifier.width(88.dp), color = colors.textSecondary, style = AuroraType.Secondary, textAlign = TextAlign.End)
                        Text(plus, Modifier.width(96.dp), color = colors.textPrimary, style = AuroraType.BodyStrong, textAlign = TextAlign.End)
                    }
                }
            }
            Text(
                "Downgrading never deletes anything. Items past Free's limits are saved and paused, and you choose which ones stay on.",
                Modifier.padding(horizontal = Space.Gutter).padding(top = Space.S),
                color = colors.textTertiary,
                style = AuroraType.Caption,
            )
        }
        item(key = "buy") {
            SectionLabel(if (plan == Plan.Plus) "Your subscription" else "Choose a plan")
            Column(Modifier.padding(horizontal = Space.Gutter), verticalArrangement = Arrangement.spacedBy(Space.XS)) {
                when {
                    plan == Plan.Plus -> AuroraButton("Manage in Google Play", { openSubscriptionSettings(context, BuildConfig.PLUS_PRODUCT_ID) }, Modifier.fillMaxWidth(), kind = ButtonKind.Secondary)
                    billing.connection == BillingState.Connection.Connecting -> Text("Checking Google Play…", color = colors.textSecondary, style = AuroraType.Secondary)
                    billing.offers.isEmpty() -> Unit
                    else -> billing.offers.forEach { offer ->
                        AuroraButton(
                            "${offer.price} ${if (offer.yearly) "a year" else "a month"}",
                            { context.activity()?.let { app.billing.buy(it, offer) } },
                            Modifier.fillMaxWidth(),
                            kind = if (offer.yearly) ButtonKind.Primary else ButtonKind.Secondary,
                        )
                    }
                }
                if (billing.pending) Notice("Payment pending", "Google Play is waiting for your payment. Plus starts once it completes; nothing is charged twice.")
                billing.message?.let { Notice("Google Play", it) }
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    AuroraButton("Restore purchases", { scope.launch { runCatching { app.billing.refresh() } } }, kind = ButtonKind.Quiet)
                }
                Text(
                    "Subscriptions renew until you cancel. Cancel any time in Google Play › Payments & subscriptions; Plus stays until the end of the period you paid for.",
                    color = colors.textTertiary,
                    style = AuroraType.Caption,
                )
            }
        }
    }
}

private tailrec fun Context.activity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.activity()
    else -> null
}
