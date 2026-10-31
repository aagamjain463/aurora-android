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
import androidx.compose.ui.res.stringResource
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.aurora.R
import app.aurora.AuroraApplication
import app.aurora.BuildConfig
import app.aurora.plan.Allowance
import app.aurora.plan.BillingState
import app.aurora.plan.FreeLimits
import app.aurora.plan.Plan
import app.aurora.plan.openSubscriptionSettings
import app.aurora.ui.components.AuroraButton
import app.aurora.ui.components.ArtTier
import app.aurora.ui.components.BrandEyebrow
import app.aurora.ui.components.TwoToneHeadline
import app.aurora.ui.components.AuroraScreen
import app.aurora.ui.components.ButtonKind
import app.aurora.ui.components.Notice
import app.aurora.ui.components.RowDivider
import app.aurora.ui.components.RowGroup
import app.aurora.ui.components.SectionLabel
import app.aurora.ui.components.minutesText
import app.aurora.ui.theme.Aurora
import app.aurora.ui.theme.AuroraType
import app.aurora.ui.theme.Space
import kotlinx.coroutines.launch

/**
 * Aurora Plus. Calm and honest: what Free already includes, what Plus adds, prices exactly as Google Play
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
    val compare = listOf(
    Triple(stringResource(R.string.plus_compare_items), "${FreeLimits.getValue(Allowance.ProtectedItems)}", stringResource(R.string.plus_unlimited)),
    Triple(stringResource(R.string.plus_compare_always), minutesText(FreeLimits.getValue(Allowance.AlwaysBlockMinutesPerDay)), stringResource(R.string.plus_unlimited)),
    Triple(stringResource(R.string.plus_compare_intent), "${FreeLimits.getValue(Allowance.IntentApps)}", stringResource(R.string.plus_unlimited)),
    Triple(stringResource(R.string.limits_name), "${FreeLimits.getValue(Allowance.DailyLimitRules)}", stringResource(R.string.plus_unlimited)),
    Triple(stringResource(R.string.schedules_name), "${FreeLimits.getValue(Allowance.Schedules)}", stringResource(R.string.plus_unlimited)),
    Triple(stringResource(R.string.plus_compare_profiles), "${FreeLimits.getValue(Allowance.FocusProfiles)}", stringResource(R.string.plus_unlimited)),
    Triple(stringResource(R.string.plus_compare_included), stringResource(R.string.plus_included), stringResource(R.string.plus_included)),
    Triple(stringResource(R.string.tab_insights), stringResource(R.string.plus_period_week), stringResource(R.string.plus_period_month)),
)


    LaunchedEffect(Unit) { app.billing.connect() }

    AuroraScreen(
        title = stringResource(R.string.plus_name),
        onBack = onBack,
        art = ArtTier.Balanced,
        header = {
            // First Light carries the page; no progress visual here, because there is no data to draw.
            Column(Modifier.padding(horizontal = Space.Gutter).padding(top = 120.dp, bottom = Space.S), verticalArrangement = Arrangement.spacedBy(Space.S)) {
                BrandEyebrow(stringResource(R.string.plus_name))
                if (plan == Plan.Plus) Text(stringResource(R.string.plus_you_have_aurora_plus), Modifier.semantics { heading() }, color = colors.textPrimary, style = AuroraType.Display)
                else TwoToneHeadline(stringResource(R.string.plus_more_room_for_every_part_of_your_day), tailWords = 5, style = AuroraType.DisplayS.copy(fontSize = 42.sp, lineHeight = 45.sp))
                Text(stringResource(R.string.plus_free_protects_just_as_strongly_as_plus_plus_removes_the_limits_s), color = colors.textSecondary, style = AuroraType.Body)
            }
        },
    ) {
        item(key = "compare") {
            SectionLabel(stringResource(R.string.plus_free_and_plus))
            RowGroup {
                Row(Modifier.fillMaxWidth().padding(horizontal = Space.M, vertical = Space.S)) {
                    Spacer(Modifier.weight(1f))
                    Text(stringResource(R.string.plus_free), Modifier.width(88.dp), color = colors.textTertiary, style = AuroraType.Eyebrow, textAlign = TextAlign.End)
                    Text(stringResource(R.string.settings_plus), Modifier.width(96.dp), color = colors.accent, style = AuroraType.Eyebrow, textAlign = TextAlign.End)
                }
                compare.forEach { (feature, free, plus) ->
                    RowDivider()
                    Row(Modifier.fillMaxWidth().padding(Space.M).semantics(mergeDescendants = true) { }, verticalAlignment = Alignment.CenterVertically) {
                        Text(feature, Modifier.weight(1f), color = colors.textPrimary, style = AuroraType.Secondary)
                        Text(free, Modifier.width(88.dp), color = colors.textSecondary, style = AuroraType.Secondary, textAlign = TextAlign.End)
                        Text(plus, Modifier.width(96.dp), color = if (plus == free) colors.textPrimary else colors.accent, style = AuroraType.BodyStrong, textAlign = TextAlign.End)
                    }
                }
            }
            Text(
                stringResource(R.string.plus_downgrading_never_deletes_anything_items_past_free_s_limits_are),
                Modifier.padding(horizontal = Space.Gutter).padding(top = Space.S),
                color = colors.textTertiary,
                style = AuroraType.Caption,
            )
        }
        item(key = "buy") {
            SectionLabel(if (plan == Plan.Plus) stringResource(R.string.plus_your_subscription) else stringResource(R.string.plus_choose_a_plan))
            Column(Modifier.padding(horizontal = Space.Gutter), verticalArrangement = Arrangement.spacedBy(Space.XS)) {
                when {
                    plan == Plan.Plus -> AuroraButton(stringResource(R.string.plus_manage_in_google_play), { openSubscriptionSettings(context, BuildConfig.PLUS_PRODUCT_ID) }, Modifier.fillMaxWidth(), kind = ButtonKind.Secondary)
                    billing.connection == BillingState.Connection.Connecting -> Text(stringResource(R.string.plus_checking_google_play), color = colors.textSecondary, style = AuroraType.Secondary)
                    billing.offers.isEmpty() -> Text(stringResource(R.string.plus_unavailable), color = colors.textSecondary, style = AuroraType.Secondary)
                    else -> billing.offers.forEach { offer ->
                        AuroraButton(
                            stringResource(if (offer.yearly) R.string.plus_year_price else R.string.plus_month_price, offer.price),
                            { context.activity()?.let { app.billing.buy(it, offer) } },
                            Modifier.fillMaxWidth(),
                            kind = if (offer.yearly) ButtonKind.Primary else ButtonKind.Secondary,
                        )
                    }
                }
                if (billing.pending) Notice(stringResource(R.string.plus_payment_pending), stringResource(R.string.plus_google_play_is_waiting_for_your_payment_plus_starts_once_it_comp))
                billing.message?.let { Notice(stringResource(R.string.plus_google_play), it) }
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    AuroraButton(stringResource(R.string.plus_restore_purchases), { scope.launch { runCatching { app.billing.refresh() } } }, kind = ButtonKind.Quiet)
                }
                Text(
                    stringResource(R.string.plus_subscriptions_renew_until_you_cancel_cancel_any_time_in_google_p),
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
