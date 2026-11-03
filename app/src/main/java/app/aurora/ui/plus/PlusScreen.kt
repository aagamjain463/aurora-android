package app.aurora.ui.plus

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
import app.aurora.account.ApiResult
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextDecoration
import app.aurora.ui.components.HeroHeadline
import app.aurora.ui.components.LitBadge
import app.aurora.ui.components.dawnColors

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
    val scope = rememberCoroutineScope()
    val colors = Aurora.colors
    var transferMessage by remember { mutableStateOf<String?>(null) }
    val reauthText = stringResource(R.string.account_reauth_body)
    val limitText = stringResource(R.string.plus_conflict_limit)
    val failedText = stringResource(R.string.plus_conflict_failed)
    val offlineText = stringResource(R.string.account_offline)
    val compare = listOf(
    Triple(stringResource(R.string.plus_compare_items), "${FreeLimits.getValue(Allowance.ProtectedItems)}", stringResource(R.string.plus_unlimited)),
    Triple(stringResource(R.string.plus_compare_intent), "${FreeLimits.getValue(Allowance.IntentApps)}", stringResource(R.string.plus_unlimited)),
    Triple(stringResource(R.string.plus_compare_goals), "${FreeLimits.getValue(Allowance.ActiveGoals)}", stringResource(R.string.plus_unlimited)),
    Triple(stringResource(R.string.plus_compare_always), minutesText(FreeLimits.getValue(Allowance.CommittedMinutesPerDay)), stringResource(R.string.plus_unlimited)),
    Triple(stringResource(R.string.plus_compare_modes), stringResource(R.string.plus_unlimited), stringResource(R.string.plus_unlimited)),
    Triple(stringResource(R.string.plus_compare_profiles), stringResource(R.string.plus_unlimited), stringResource(R.string.plus_unlimited)),
    Triple(stringResource(R.string.plus_compare_rules), stringResource(R.string.plus_unlimited), stringResource(R.string.plus_unlimited)),
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
            Column(Modifier.padding(horizontal = Space.Gutter).padding(top = Space.XL, bottom = Space.S), verticalArrangement = Arrangement.spacedBy(Space.S)) {
                BrandEyebrow(stringResource(R.string.plus_name))
                if (plan == Plan.Plus) Text(stringResource(R.string.plus_you_have_aurora_plus), Modifier.semantics { heading() }, color = colors.textPrimary, style = AuroraType.Display)
                else TwoToneHeadline(stringResource(R.string.plus_more_room_for_every_part_of_your_day), tailWords = 5, style = AuroraType.DisplayS.copy(fontSize = 42.sp, lineHeight = 45.sp))
                Text(stringResource(R.string.plus_free_protects_just_as_strongly_as_plus_plus_removes_the_limits_s), color = colors.textSecondary, style = AuroraType.Body)
            }
        },
    ) {
        item(key = "plus") {
            SectionLabel(stringResource(if (plan == Plan.Plus) R.string.plus_free_and_plus else R.string.plus_choose_a_plan))
            PlusCard(compare.filter { it.second != it.third }, current = plan == Plan.Plus) {
                when {
                    plan == Plan.Plus || subscription?.state == "on_hold" -> AuroraButton(stringResource(R.string.plus_manage_in_google_play), { openSubscriptionSettings(context, subscription?.productId) }, Modifier.fillMaxWidth(), kind = ButtonKind.Secondary)
                    billing.connection == BillingState.Connection.Connecting -> Text(stringResource(R.string.plus_checking_google_play), color = colors.textSecondary, style = AuroraType.Secondary)
                    billing.offers.isEmpty() -> Text(stringResource(R.string.plus_unavailable), color = colors.textSecondary, style = AuroraType.Secondary)
                    else -> billing.offers.sortedByDescending { it.yearly }.forEach { offer ->
                        AuroraButton(
                            stringResource(if (offer.yearly) R.string.plus_year_price else R.string.plus_month_price, offer.price),
                            { context.activity()?.let { app.billing.buy(it, offer) } },
                            Modifier.fillMaxWidth(),
                            kind = if (offer.yearly) ButtonKind.Primary else ButtonKind.Secondary,
                        )
                    }
                }
            }
        }
        item(key = "free") {
            FreeCard(compare, current = plan != Plan.Plus, Modifier.padding(top = Space.M))
            Text(
                stringResource(R.string.plus_downgrading_never_deletes_anything_items_past_free_s_limits_are),
                Modifier.padding(horizontal = Space.Gutter).padding(top = Space.S),
                color = colors.textTertiary,
                style = AuroraType.Caption,
            )
        }
        item(key = "buy") {
            if (plan == Plan.Plus) SectionLabel(stringResource(R.string.plus_your_subscription))
            Column(Modifier.padding(horizontal = Space.Gutter).padding(top = Space.S), verticalArrangement = Arrangement.spacedBy(Space.XS)) {
                subscription?.let { s ->
                    when {
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
                    ) {
                        transferMessage?.let { Text(it, color = colors.danger, style = AuroraType.Secondary) }
                        AuroraButton(
                            stringResource(R.string.plus_conflict_move),
                            {
                                scope.launch {
                                    when (val r = app.account.transferPurchase(conflict.token)) {
                                        is ApiResult.Ok -> {
                                            app.billing.store(r.value)
                                            app.billing.clearConflict()
                                            transferMessage = null
                                        }
                                        is ApiResult.Refused -> if (r.code == "reauth_required") {
                                            context.activity()?.let { app.account.reauthenticate(it) }
                                            app.account.clearEmailStep()
                                            transferMessage = reauthText
                                        } else transferMessage = if (r.code == "transfer_limit") limitText else failedText
                                        ApiResult.Unreachable -> transferMessage = offlineText
                                    }
                                }
                            },
                            Modifier.fillMaxWidth(), kind = ButtonKind.Secondary,
                        )
                    }
                }
                if (billing.verifying) Notice(stringResource(R.string.plus_verifying_title), stringResource(R.string.plus_verifying_body))
                if (billing.pending) Notice(stringResource(R.string.plus_payment_pending), stringResource(R.string.plus_google_play_is_waiting_for_your_payment_plus_starts_once_it_comp))
                billing.message?.let { Notice(stringResource(R.string.plus_google_play), it) }
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    AuroraButton(stringResource(R.string.plus_restore_purchases), { scope.launch { runCatching { app.billing.refresh() } } }, kind = ButtonKind.Quiet)
                }
                Text(stringResource(R.string.plus_play_note), color = colors.textTertiary, style = AuroraType.Caption)
                Text(
                    stringResource(R.string.plus_subscriptions_renew_until_you_cancel_cancel_any_time_in_google_p),
                    color = colors.textTertiary,
                    style = AuroraType.Caption,
                )
            }
        }
    }
}

/** Free: the quiet card. Every Shield at full strength, its few limits stated plainly. */
@Composable
private fun FreeCard(rows: List<Triple<String, String, String>>, current: Boolean, modifier: Modifier = Modifier) {
    val colors = Aurora.colors
    Column(modifier.padding(horizontal = Space.Gutter).fillMaxWidth().glass(colors).padding(horizontal = Space.L, vertical = 20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AuroraMark(18.dp, Modifier.padding(end = 10.dp))
            Text(stringResource(R.string.plus_free_name), Modifier.weight(1f).semantics { heading() }, color = colors.textPrimary, style = AuroraType.DisplayS.copy(fontSize = 26.sp, lineHeight = 30.sp))
            if (current) PlanPill()
        }
        Text(stringResource(R.string.plus_free_tagline), Modifier.padding(top = Space.XS, bottom = Space.S), color = colors.textSecondary, style = AuroraType.Secondary)
        rows.forEach { (feature, free, _) ->
            RowDivider()
            Row(Modifier.fillMaxWidth().padding(vertical = 11.dp).semantics(mergeDescendants = true) { }, verticalAlignment = Alignment.CenterVertically) {
                Text(feature, Modifier.weight(1f).padding(end = Space.S), color = colors.textSecondary, style = AuroraType.Secondary)
                Text(free, color = colors.textPrimary, style = AuroraType.Caption, textAlign = TextAlign.End)
            }
        }
    }
}

/**
 * Plus: the dawn card, the one object on the page that catches the sunrise. It sits on the deepest tone, its edge lit
 * in gold from above and fading down the sides; a single thread of dawn runs under the name. The lit mark and the lit
 * word "Plus" carry the brand. Each row says what Plus opens up, with Free's limit struck through beneath it, then the
 * way to get it ([actions]).
 */
@Composable
private fun PlusCard(rows: List<Triple<String, String, String>>, current: Boolean, modifier: Modifier = Modifier, actions: @Composable () -> Unit) {
    val colors = Aurora.colors
    val edge = colors.glowTop
    Column(
        modifier.padding(horizontal = Space.Gutter).fillMaxWidth()
            .then(if (colors.isDark) Modifier else Modifier.shadow(16.dp, Radius.L, ambientColor = DayShade, spotColor = DayShade))
            .clip(Radius.L)
            .background(if (colors.isDark) colors.sky else colors.surface)
            .border(
                if (colors.isDark) 1.dp else 1.5.dp,
                Brush.verticalGradient(0f to edge.copy(alpha = if (colors.isDark) 0.85f else 1f), 0.55f to edge.copy(alpha = if (colors.isDark) 0.18f else 0.45f), 1f to colors.hairline),
                Radius.L,
            )
            .padding(horizontal = Space.L, vertical = 26.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            AuroraMark(30.dp, lit = true)
            Spacer(Modifier.weight(1f))
            if (current) PlanPill()
        }
        HeroHeadline(stringResource(R.string.plus_name), Modifier.padding(top = 22.dp), style = AuroraType.DisplayXL.copy(fontSize = 50.sp, lineHeight = 52.sp))
        Text(stringResource(R.string.plus_plus_tagline), Modifier.padding(top = Space.S), color = colors.textSecondary, style = AuroraType.Body)
        // The horizon: one thread of dawn, brightest in the middle, gone at the ends.
        val dawn = dawnColors()
        Box(
            Modifier.padding(vertical = 22.dp).fillMaxWidth().height(1.dp)
                .background(Brush.horizontalGradient(listOf(Color.Transparent) + dawn.reversed().drop(1) + dawn.drop(1) + Color.Transparent)),
        )
        Eyebrow(stringResource(R.string.plus_everything_in_free), Modifier.padding(bottom = Space.XS), color = colors.textTertiary)
        rows.forEach { (feature, free, plus) ->
            Row(Modifier.fillMaxWidth().padding(vertical = 10.dp).semantics(mergeDescendants = true) { }, verticalAlignment = Alignment.CenterVertically) {
                LitBadge(AuroraIcons.Check, Modifier.padding(end = Space.S), size = 26)
                Text(feature, Modifier.weight(1f).padding(end = Space.S), color = colors.textPrimary, style = AuroraType.Secondary)
                Column(horizontalAlignment = Alignment.End) {
                    Text(plus, color = colors.accent, style = AuroraType.BodyStrong, textAlign = TextAlign.End)
                    // Free's limit, struck through: shown, not spoken (the Free card lists it for screen readers).
                    if (!current) Text(
                        free, Modifier.clearAndSetSemantics { }, color = colors.textTertiary, textAlign = TextAlign.End,
                        style = AuroraType.Caption.copy(textDecoration = TextDecoration.LineThrough),
                    )
                }
            }
        }
        Column(Modifier.padding(top = 22.dp), verticalArrangement = Arrangement.spacedBy(Space.S)) { actions() }
    }
}

/** The day theme's warm shadow, as on [glass]: paper on paper. */
private val DayShade = Color(0xFF6B4A2E).copy(alpha = 0.18f)

@Composable
private fun PlanPill(modifier: Modifier = Modifier) {
    val colors = Aurora.colors
    Eyebrow(
        stringResource(R.string.plus_your_plan),
        modifier.clip(Radius.Pill).background(colors.accentSoft).border(1.dp, colors.accent.copy(alpha = 0.35f), Radius.Pill).padding(horizontal = Space.S, vertical = 6.dp),
        color = colors.accent,
    )
}

private fun dateOf(at: Long): String = java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM).format(java.util.Date(at))

private tailrec fun Context.activity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.activity()
    else -> null
}
