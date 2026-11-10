package app.aurora.ui.plus

import app.aurora.ui.components.glassCard
import app.aurora.ui.components.glassFill
import androidx.compose.foundation.layout.fillMaxHeight
import app.aurora.ui.components.Eyebrow
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.aurora.R
import app.aurora.AuroraApplication
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
import app.aurora.ui.components.SectionLabel
import app.aurora.ui.components.minutesText
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import app.aurora.account.RemoteTrial
import app.aurora.ui.theme.Aurora
import app.aurora.ui.theme.AuroraType
import app.aurora.ui.theme.Space
import kotlinx.coroutines.launch
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.ui.draw.clip
import app.aurora.ui.components.AuroraIcons
import app.aurora.ui.components.AuroraMark
import app.aurora.ui.components.glass
import app.aurora.ui.theme.Radius
import androidx.compose.runtime.CompositionLocalProvider
import app.aurora.ui.theme.DarkColors
import app.aurora.ui.theme.LocalAuroraColors
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextDecoration
import app.aurora.ui.components.HeroHeadline
import app.aurora.ui.components.LitBadge
import app.aurora.ui.components.PlusBadge
import app.aurora.ui.components.PlusGoldBrush
import app.aurora.ui.components.PlusSurface
import app.aurora.ui.theme.clipShape

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
    val subscription by app.billing.subscription.collectAsStateWithLifecycle()
    val remote by app.account.remote.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val colors = Aurora.colors
    val trialClaim = rememberTrialClaim()
    var lifetimeMessage by remember { mutableStateOf<String?>(null) }
    // A trial is Plus, but not a purchase: the ways to buy stay open during it.
    val source = subscription?.source.takeIf { plan == Plan.Plus }
    val paid = source == "subscription" || source == "lifetime"
    val trial = remote?.trial
    val lifetimeOffer = remote?.lifetime?.offer
    val offlineText = stringResource(R.string.account_offline)
    val soldOutText = stringResource(R.string.lifetime_sold_out)
    // Only what Plus changes. Everything else is the same on both plans, so neither card repeats it.
    val compare = listOf(
        Triple(stringResource(R.string.plus_compare_items), "${FreeLimits.getValue(Allowance.ProtectedItems)}", stringResource(R.string.plus_unlimited)),
        Triple(stringResource(R.string.plus_compare_intent), "${FreeLimits.getValue(Allowance.IntentApps)}", stringResource(R.string.plus_unlimited)),
        Triple(stringResource(R.string.plus_compare_goals), "${FreeLimits.getValue(Allowance.ActiveGoals)}", stringResource(R.string.plus_unlimited)),
        Triple(stringResource(R.string.plus_compare_always), minutesText(FreeLimits.getValue(Allowance.CommittedMinutesPerDay)), stringResource(R.string.plus_unlimited)),
        Triple(stringResource(R.string.tab_insights), stringResource(R.string.plus_period_week), stringResource(R.string.plus_period_month)),
    )


    LaunchedEffect(Unit) { app.billing.connect() }

    AuroraScreen(
        title = stringResource(R.string.plus_name),
        onBack = onBack,
        art = ArtTier.Balanced,
        header = {
            // One line, then the two plans: nothing to read past them.
            Column(Modifier.fillMaxWidth().padding(horizontal = Space.Gutter).padding(top = Space.S, bottom = Space.XL)) {
                val style = AuroraType.HeroBold.copy(fontSize = 34.sp, lineHeight = 38.sp)
                if (plan == Plan.Plus) Text(stringResource(R.string.plus_you_have_aurora_plus), Modifier.semantics { heading() }, color = colors.textPrimary, style = style)
                else TwoToneHeadline(stringResource(R.string.plus_more_room_for_every_part_of_your_day), tailWords = 5, style = style)
            }
        },
    ) {
        item(key = "plus") {
            PlusCard(compare, current = plan == Plan.Plus) {
                when {
                    source == "lifetime" -> Text(stringResource(R.string.lifetime_owned), color = colors.textSecondary, style = AuroraType.Secondary)
                    paid || subscription?.state == "on_hold" -> {
                        if (subscription?.store == "app_store") Text(stringResource(R.string.plus_managed_in_app_store), color = colors.textSecondary, style = AuroraType.Secondary)
                        else AuroraButton(stringResource(R.string.plus_manage_in_google_play), { openSubscriptionSettings(context, subscription?.productId) }, Modifier.fillMaxWidth(), kind = ButtonKind.Secondary)
                    }
                    billing.connection == BillingState.Connection.Connecting -> Text(stringResource(R.string.plus_checking_google_play), color = colors.textSecondary, style = AuroraType.Secondary)
                    billing.offers.isEmpty() -> Text(stringResource(R.string.plus_unavailable), color = colors.textSecondary, style = AuroraType.Secondary)
                    else -> billing.offers.sortedByDescending { it.yearly }.forEach { offer ->
                        AuroraButton(
                            stringResource(if (offer.yearly) R.string.plus_year_price else R.string.plus_month_price, offer.price),
                            { context.activity()?.let { app.billing.buy(it, offer) } },
                            Modifier.fillMaxWidth(),
                            kind = if (offer.yearly) ButtonKind.Plus else ButtonKind.Secondary,
                        )
                    }.also {
                        // Founding Lifetime: only where Play sells it (the US) and while the server has a slot to hold.
                        val lifetime = billing.lifetime
                        if (lifetime != null && (lifetimeOffer == "available" || lifetimeOffer == "held")) {
                            AuroraButton(
                                stringResource(R.string.lifetime_price, lifetime.price),
                                {
                                    scope.launch {
                                        val hold = app.account.holdLifetime()
                                        lifetimeMessage = when {
                                            hold == null -> offlineText
                                            !hold.held -> soldOutText
                                            else -> null.also { context.activity()?.let { app.billing.buyLifetime(it, lifetime) } }
                                        }
                                        app.account.sync()
                                    }
                                },
                                Modifier.fillMaxWidth(), kind = ButtonKind.PlusQuiet,
                            )
                            // Shown with the price, before the store sheet: what "lifetime" means (founder, 2026-11-10).
                            Text(stringResource(R.string.lifetime_terms), color = colors.textTertiary, style = AuroraType.Caption)
                            Text(stringResource(R.string.lifetime_scope), color = colors.textTertiary, style = AuroraType.Caption)
                        } else if (lifetime != null && lifetimeOffer == "sold_out") {
                            Text(soldOutText, color = colors.textTertiary, style = AuroraType.Caption)
                        }
                        lifetimeMessage?.let { Text(it, color = colors.textSecondary, style = AuroraType.Secondary) }
                    }
                }
            }
        }
        item(key = "trial") {
            TrialSection(trial, onTrial = source == "trial", free = plan != Plan.Plus, message = trialClaim.message, onStart = trialClaim.claim)
        }
        item(key = "free") {
            FreeCard(compare, current = plan != Plan.Plus, Modifier.padding(top = Space.L))
        }
        item(key = "buy") {
            if (plan == Plan.Plus) SectionLabel(stringResource(R.string.plus_your_subscription))
            Column(Modifier.padding(horizontal = Space.Gutter).padding(top = Space.M), verticalArrangement = Arrangement.spacedBy(Space.XS)) {
                subscription?.let { s ->
                    when {
                        source == "trial" || source == "lifetime" -> Unit
                        plan == Plan.Plus && s.state == "in_grace_period" -> Notice(stringResource(R.string.plus_grace_title), stringResource(R.string.plus_grace_body))
                        plan == Plan.Plus -> Text(stringResource(if (s.autoRenewing) R.string.plus_renews_on else R.string.plus_ends_on, dateOf(s.paidThrough)), color = colors.textSecondary, style = AuroraType.Secondary)
                        s.state == "on_hold" -> Notice(stringResource(R.string.plus_hold_title), stringResource(R.string.plus_hold_body))
                        s.state == "too_many_devices" -> Notice(stringResource(R.string.plus_devices_title), stringResource(R.string.plus_devices_body))
                    }
                }
                billing.conflict?.let { conflict ->
                    Notice(
                        stringResource(R.string.plus_conflict_title),
                        conflict.hint?.let { stringResource(R.string.plus_conflict_body_hint, it) } ?: stringResource(R.string.plus_conflict_body),
                    )
                }
                if (billing.verifying) Notice(stringResource(R.string.plus_verifying_title), stringResource(R.string.plus_verifying_body))
                if (billing.pending) Notice(stringResource(R.string.plus_payment_pending), stringResource(R.string.plus_google_play_is_waiting_for_your_payment_plus_starts_once_it_comp))
                billing.message?.let { Notice(stringResource(R.string.plus_google_play), it) }
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    AuroraButton(stringResource(R.string.plus_restore_purchases), { scope.launch { runCatching { app.billing.refresh() } } }, kind = ButtonKind.Quiet)
                }
                Text(
                    stringResource(R.string.plus_subscriptions_renew_until_you_cancel_cancel_any_time_in_google_p),
                    Modifier.fillMaxWidth(),
                    color = colors.textTertiary,
                    style = AuroraType.Caption,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

/**
 * The 14-day trial, in plain words: offered to a new account with no payment details and nothing that renews, then
 * its end date while it runs, then when it ended. Nothing at all once Plus is paid for.
 */
@Composable
internal fun TrialSection(trial: RemoteTrial?, onTrial: Boolean, free: Boolean, message: String?, onStart: () -> Unit) {
    val colors = Aurora.colors
    Column(Modifier.padding(horizontal = Space.Gutter).padding(top = Space.L), verticalArrangement = Arrangement.spacedBy(Space.S)) {
        when {
            trial?.status == "active" && onTrial -> Notice(stringResource(R.string.trial_active_title, dateOf(trial.endsAt ?: 0)), stringResource(R.string.trial_active_body))
            trial?.status == "available" && free -> Notice(stringResource(R.string.trial_offer_title), stringResource(R.string.trial_offer_body)) {
                message?.let { Text(it, color = colors.textSecondary, style = AuroraType.Secondary) }
                AuroraButton(stringResource(R.string.trial_start), onStart, Modifier.fillMaxWidth(), kind = ButtonKind.Plus)
            }
            trial?.status == "ended" && free -> Text(stringResource(R.string.trial_ended, dateOf(trial.endsAt ?: 0)), color = colors.textTertiary, style = AuroraType.Secondary)
        }
    }
}

/**
 * Free, as a quiet twin of the Plus card: the mark and the plan, the name set large, then its limits as four wells
 * of plain tabular figures, and Insights' reach on one line under them. Monochrome glass only; gold stays with Plus.
 */
@Composable
private fun FreeCard(rows: List<Triple<String, String, String>>, current: Boolean, modifier: Modifier = Modifier) {
    val colors = Aurora.colors
    val limits = rows.dropLast(1)
    val insights = rows.last()
    Column(modifier.padding(horizontal = Space.Gutter).fillMaxWidth().glassCard(colors, Radius.L).padding(horizontal = 24.dp).padding(top = 26.dp, bottom = 24.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            AuroraMark(40.dp)
            Spacer(Modifier.weight(1f))
            if (current) PlanPill()
        }
        Text(
            stringResource(R.string.plus_free_name), Modifier.padding(top = 22.dp).semantics { heading() },
            color = colors.textPrimary, style = AuroraType.DisplayXL.copy(fontSize = 30.sp, lineHeight = 36.sp),
        )
        Text(stringResource(R.string.plus_free_summary), Modifier.padding(top = 4.dp), color = colors.textTertiary, style = AuroraType.Secondary)
        // Two by two: each limit a well, its figure large and its meaning small under it.
        Column(Modifier.padding(top = 22.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            limits.chunked(2).forEach { pair ->
                Row(Modifier.fillMaxWidth().height(androidx.compose.foundation.layout.IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    pair.forEach { (label, free, _) -> LimitWell(free, label, Modifier.weight(1f).fillMaxHeight()) }
                }
            }
        }
        Row(
            Modifier.padding(top = 18.dp).fillMaxWidth().padding(horizontal = 4.dp).semantics(mergeDescendants = true) {},
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.S),
        ) {
            Icon(AuroraIcons.Insights, null, tint = colors.textTertiary, modifier = Modifier.size(16.dp))
            Text(insights.first, Modifier.weight(1f), color = colors.textSecondary, style = AuroraType.Secondary)
            Text(insights.second, color = colors.textPrimary, style = AuroraType.Secondary.copy(fontWeight = FontWeight(500)), textAlign = TextAlign.End)
        }
    }
}

/** One plan limit: the figure in tabular Inter, the words under it. [gold]: Plus's, the figure in gold on a gold-lit well. */
@Composable
private fun LimitWell(figure: String, label: String, modifier: Modifier = Modifier, gold: Boolean = false) {
    val colors = Aurora.colors
    val figureStyle = AuroraType.Metric.copy(fontSize = 26.sp, lineHeight = 30.sp)
    Column(
        modifier
            .then(
                if (gold) Modifier.clip(Radius.M.clipShape).background(colors.plus.copy(alpha = 0.07f), Radius.M).border(1.dp, colors.plus.copy(alpha = 0.24f), Radius.M)
                else Modifier.glassCard(colors, Radius.M, fill = colors.glassFill(field = true)),
            )
            .padding(horizontal = 16.dp, vertical = 14.dp)
            .semantics(mergeDescendants = true) {},
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            figure, style = if (gold) figureStyle.copy(brush = PlusGoldBrush) else figureStyle.copy(color = colors.textPrimary), maxLines = 1,
            autoSize = androidx.compose.foundation.text.TextAutoSize.StepBased(minFontSize = 16.sp, maxFontSize = 26.sp),
        )
        Text(label, color = colors.textSecondary, style = AuroraType.Caption)
    }
}

/**
 * Plus, laid out as the Free card's twin so the two compare at a glance: the mark and the plan, the name set large in
 * gold, then what Plus lifts as four gold-lit wells, Insights' reach on one line, and the ways to buy it.
 */
@Composable
private fun PlusCard(rows: List<Triple<String, String, String>>, current: Boolean, modifier: Modifier = Modifier, actions: @Composable () -> Unit) {
    PlusSurface(modifier.padding(horizontal = Space.Gutter).fillMaxWidth()) {
        val colors = Aurora.colors
        val limits = rows.dropLast(1)
        val insights = rows.last()
        Column(Modifier.padding(horizontal = 24.dp).padding(top = 26.dp, bottom = 24.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                AuroraMark(40.dp)
                Spacer(Modifier.weight(1f))
                PlusBadge(text = stringResource(if (current) R.string.plus_your_plan else R.string.plus_badge))
            }
            Text(
                stringResource(R.string.plus_name), Modifier.padding(top = 22.dp).semantics { heading() },
                style = AuroraType.DisplayXL.copy(fontSize = 30.sp, lineHeight = 36.sp, brush = PlusGoldBrush),
            )
            Text(stringResource(R.string.plus_everything_in_free), Modifier.padding(top = 4.dp), color = colors.textTertiary, style = AuroraType.Secondary)
            Column(Modifier.padding(top = 22.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                limits.chunked(2).forEach { pair ->
                    Row(Modifier.fillMaxWidth().height(androidx.compose.foundation.layout.IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        pair.forEach { (label, _, plus) -> LimitWell(plus, label, Modifier.weight(1f).fillMaxHeight(), gold = true) }
                    }
                }
            }
            Row(
                Modifier.padding(top = 18.dp).fillMaxWidth().padding(horizontal = 4.dp).semantics(mergeDescendants = true) {},
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.S),
            ) {
                Icon(AuroraIcons.Insights, null, tint = colors.plus, modifier = Modifier.size(16.dp))
                Text(insights.first, Modifier.weight(1f), color = colors.textSecondary, style = AuroraType.Secondary)
                Text(insights.third, color = colors.plus, style = AuroraType.Secondary.copy(fontWeight = FontWeight(500)), textAlign = TextAlign.End)
            }
            Column(Modifier.padding(top = 24.dp), verticalArrangement = Arrangement.spacedBy(Space.S)) { actions() }
        }
    }
}

@Composable
private fun PlanPill(modifier: Modifier = Modifier) {
    val colors = Aurora.colors
    Eyebrow(
        stringResource(R.string.plus_your_plan),
        modifier.clip(Radius.Pill).glass(colors, Radius.Pill).padding(horizontal = Space.S, vertical = 6.dp),
        color = colors.textSecondary,
    )
}

internal fun dateOf(at: Long): String = java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM).format(java.util.Date(at))

private tailrec fun Context.activity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.activity()
    else -> null
}
