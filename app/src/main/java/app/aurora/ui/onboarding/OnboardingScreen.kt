package app.aurora.ui.onboarding

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.aurora.ui.components.PageGlow
import app.aurora.AuroraApplication
import app.aurora.R
import app.aurora.auroraViewModel
import app.aurora.data.Access
import app.aurora.data.AccessState
import app.aurora.data.openAccessSettings
import app.aurora.plan.ProtectedItem
import app.aurora.protect.Mode
import app.aurora.protect.ShieldService
import app.aurora.protect.key
import app.aurora.rememberAccess
import app.aurora.ui.components.AuroraButton
import app.aurora.ui.components.AuroraDialog
import app.aurora.ui.components.ArtTier
import app.aurora.ui.components.Sky
import app.aurora.ui.components.AuroraBackdrop
import app.aurora.ui.components.TwoToneHeadline
import app.aurora.ui.components.AuroraMark
import app.aurora.ui.components.LockupMark
import app.aurora.ui.components.AuroraTextField
import app.aurora.ui.components.ButtonKind
import app.aurora.ui.components.Chip
import app.aurora.ui.components.ChoiceCard
import app.aurora.ui.components.SelectionMark
import app.aurora.ui.components.Wordmark
import app.aurora.ui.protect.ProtectModel
import app.aurora.ui.protect.ShieldDisclosure
import app.aurora.ui.protect.appList
import app.aurora.ui.protect.modeColor
import app.aurora.ui.protect.modeDescription
import app.aurora.ui.protect.modeName
import app.aurora.ui.theme.Aurora
import app.aurora.ui.theme.AuroraType
import app.aurora.ui.theme.Motion
import app.aurora.ui.theme.Radius
import app.aurora.ui.theme.Space
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.heightIn
import app.aurora.ui.components.BrandEyebrow
import app.aurora.ui.components.LitBadge
import app.aurora.ui.components.AuroraIcons
import androidx.compose.foundation.layout.widthIn
import app.aurora.ui.components.ContentMaxWidth

private const val PageCount = 5
private const val StepCount = PageCount - 2

/**
 * Where a saved step from the old eight-page flow lands in the four-step one. Old Intent and
 * Keep-running pages fold into the Shield step; the final page stays the send-off.
 */
internal fun onboardingPageFor(saved: Int): Int = when {
    saved <= 0 -> 0
    saved == 1 -> 0
    saved == 2 -> 1
    saved == 3 -> 2
    saved in 4..6 -> 3
    else -> 4
}.coerceIn(0, PageCount - 1)

/**
 * Four steps to first value: the promise and what matters, the apps that pull you away, how Shield
 * works, and turning Shield on. The final page is the First Light send-off, not another decision.
 * Intent, usage access and battery guidance are offered later, where they are needed. The person can
 * leave and come back; onboarding resumes where they were. Nothing here starts protection by itself.
 */
@Composable
fun OnboardingScreen(onFinish: () -> Unit) {
    val app = LocalContext.current.applicationContext as AuroraApplication
    val vm = auroraViewModel { ProtectModel(it) }
    val colors = Aurora.colors
    val reduced = Aurora.reducedMotion
    val prefs by app.preferences.preferences.collectAsStateWithLifecycle(initialValue = null)
    var page by rememberSaveable { mutableIntStateOf(-1) }
    LaunchedEffect(prefs != null) { if (page < 0 && prefs != null) page = onboardingPageFor(prefs!!.onboardingStep) }
    LaunchedEffect(page) { if (page >= 0) runCatching { app.preferences.setOnboardingStep(page) } }
    val notice by vm.notice.collectAsStateWithLifecycle()
    BackHandler(enabled = page > 0) { page-- }
    val p = page.coerceAtLeast(0)
    val shieldOn = rememberAccess(Access.Accessibility) == AccessState.Granted

    Box(Modifier.fillMaxSize().background(colors.background)) {
        // First Light opens and closes onboarding; the steps between are quiet.
        androidx.compose.animation.AnimatedVisibility(p == 0 || p == PageCount - 1, enter = fadeIn(tween(Motion.Gentle)), exit = fadeOut(tween(Motion.Standard))) {
            // The sky lights on the first page and surges on the last, when Aurora is ready.
            AuroraBackdrop(ArtTier.Immersive, sky = if (p == PageCount - 1) Sky.Flare else Sky.Lit)
        }
        PageGlow()
        Column(Modifier.align(Alignment.TopCenter).widthIn(max = ContentMaxWidth).fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()) {
            Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = Space.Gutter), verticalAlignment = Alignment.CenterVertically) {
                AuroraMark(LockupMark)
                Spacer(Modifier.width(Space.S))
                Wordmark(Modifier.weight(1f))
                // The art pages keep the sky clear; the steps between show where you are.
                if (p in 1 until PageCount - 1) PageDots(p)
            }
            AnimatedContent(
                targetState = p,
                modifier = Modifier.weight(1f),
                transitionSpec = {
                    val enter = if (reduced) fadeIn(tween(Motion.Standard)) else
                        fadeIn(tween(Motion.Gentle)) + slideInHorizontally(tween(Motion.Gentle, easing = Motion.Ease)) { if (targetState > initialState) it / 3 else -it / 3 }
                    enter togetherWith fadeOut(tween(Motion.Quick))
                },
                label = "page",
            ) { step ->
                when (step) {
                    0 -> Welcome(vm)
                    1 -> Apps(vm)
                    2 -> Page { Modes(vm) }
                    3 -> Page { TurnOnShield() }
                    else -> Page { FirstLight(vm) }
                }
            }
            Column(Modifier.padding(horizontal = Space.Gutter, vertical = Space.M).animateContentSize(), verticalArrangement = Arrangement.spacedBy(Space.XS)) {
                val label = when (p) {
                    0 -> stringResource(R.string.onb_begin)
                    PageCount - 1 -> stringResource(R.string.onb_finish)
                    else -> stringResource(R.string.action_continue)
                }
                // One filled action per step: until Shield is on, "Turn on Shield" is it, and skipping stays quiet.
                val waitingForShield = p == 3 && !shieldOn
                if (!waitingForShield) AuroraButton(label, {
                    if (p == PageCount - 1) {
                        app.scope.launch { runCatching { app.preferences.setOnboardingStep(0) } }
                        onFinish()
                    } else page = p + 1
                }, Modifier.fillMaxWidth())
                if (p in 1..3 && !(p == 3 && shieldOn)) AuroraButton(stringResource(R.string.onb_skip), { page = p + 1 }, Modifier.fillMaxWidth(), kind = ButtonKind.Quiet)
            }
        }
    }
    notice?.let { n -> AuroraDialog(n.title, vm::dismissNotice, stringResource(R.string.action_ok), vm::dismissNotice, n.body) }
}

@Composable
private fun Page(content: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = Space.Gutter), verticalArrangement = Arrangement.spacedBy(Space.S)) {
        content()
    }
}

@Composable
private fun PageDots(page: Int) {
    val colors = Aurora.colors
    val step = (page - 1).coerceIn(0, StepCount - 1)
    val spoken = stringResource(R.string.onb_step, step + 1, StepCount)
    Row(Modifier.semantics { contentDescription = spoken }, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        repeat(StepCount) { i ->
            Box(Modifier.height(4.dp).width(if (i == step) 18.dp else 6.dp).clip(Radius.Pill).background(if (i <= step) colors.accent else colors.hairline))
        }
    }
}

@Composable
private fun Title(eyebrow: String, title: String, body: String?) {
    val colors = Aurora.colors
    Spacer(Modifier.height(Space.M))
    Text(eyebrow, color = colors.accent, style = AuroraType.Eyebrow)
    Text(title, Modifier.semantics { heading() }, color = colors.textPrimary, style = AuroraType.Display)
    if (body != null) Text(body, color = colors.textSecondary, style = AuroraType.Body)
    Spacer(Modifier.height(Space.S))
}

/**
 * The promise sits low over the lake, then the three things Aurora does arrive one by one. The column
 * fills the screen so the type rests near the action, and scrolls when text is large.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Welcome(vm: ProtectModel) {
    val data by vm.data.collectAsStateWithLifecycle()
    val chosen = data?.rules?.motivations.orEmpty()
    var custom by rememberSaveable { mutableStateOf("") }
    val colors = Aurora.colors
    val pillars = listOf(
        AuroraIcons.Protect to R.string.onb_pillar_shield,
        AuroraIcons.Intent to R.string.onb_pillar_intent,
        AuroraIcons.Track to R.string.onb_pillar_track,
    )
    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).heightIn(min = maxHeight).padding(horizontal = Space.Gutter),
            verticalArrangement = Arrangement.spacedBy(Space.S),
        ) {
            Spacer(Modifier.weight(1f).heightIn(min = 96.dp))
            BrandEyebrow(stringResource(R.string.onb_welcome_eyebrow))
            TwoToneHeadline(stringResource(R.string.today_headline))
            Text(stringResource(R.string.onb_welcome_body), color = colors.textSecondary, style = AuroraType.Body)
            Row(Modifier.fillMaxWidth().padding(top = Space.XS), horizontalArrangement = Arrangement.spacedBy(Space.M)) {
                pillars.forEachIndexed { i, (icon, label) ->
                    Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.XS)) {
                        LitBadge(icon, size = 30, tint = if (i == 2) colors.rose else colors.accent)
                        Text(stringResource(label), color = colors.textSecondary, style = AuroraType.Caption)
                    }
                }
            }
            Spacer(Modifier.height(Space.S))
            Text(stringResource(R.string.onb_motivation_title), color = colors.textPrimary, style = AuroraType.Title)
            Text(stringResource(R.string.onb_motivation_body), color = colors.textSecondary, style = AuroraType.Secondary)
            val options = listOf(R.string.onb_m_deep_work, R.string.onb_m_study, R.string.onb_m_people, R.string.onb_m_sleep, R.string.onb_m_reading, R.string.onb_m_health, R.string.onb_m_creating).map { stringResource(it) }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.XS), verticalArrangement = Arrangement.spacedBy(Space.XS)) {
                (options + chosen.filterNot { it in options }).forEach { m ->
                    Chip(m, m in chosen) { vm.edit { r -> r.copy(motivations = if (m in r.motivations) r.motivations - m else r.motivations + m) } }
                }
            }
            AuroraTextField(custom, { if (it.length <= 40) custom = it }, stringResource(R.string.onb_motivation_custom), capitalization = KeyboardCapitalization.Sentences, onDone = {
                val m = custom.trim()
                if (m.isNotEmpty()) vm.edit { r -> if (m in r.motivations) r else r.copy(motivations = r.motivations + m) }
                custom = ""
            })
            Spacer(Modifier.height(Space.S))
        }
    }
}

/** Every launchable app, searchable, nothing cut off. */
@Composable
private fun Apps(vm: ProtectModel) {
    val apps by vm.apps.collectAsStateWithLifecycle()
    val data by vm.data.collectAsStateWithLifecycle()
    val exempt by vm.exempt.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Column(Modifier.padding(horizontal = Space.Gutter), verticalArrangement = Arrangement.spacedBy(Space.S)) {
                Title(stringResource(R.string.onb_apps_eyebrow), stringResource(R.string.onb_apps_title), stringResource(R.string.onb_apps_body))
            }
        }
        item {
            AuroraTextField(query, { query = it }, stringResource(R.string.picker_search), Modifier.padding(horizontal = Space.Gutter, vertical = Space.XS), icon = app.aurora.ui.components.AuroraIcons.Search)
        }
        appList(apps, data, exempt, query) { pkg -> ProtectedItem.App.of(pkg)?.let { vm.toggle(it) } }
    }
}

@Composable
private fun Modes(vm: ProtectModel) {
    val data by vm.data.collectAsStateWithLifecycle()
    val current = data?.rules?.defaultRule?.mode ?: Mode.Flexible
    Title(stringResource(R.string.onb_modes_eyebrow), stringResource(R.string.onb_modes_title), stringResource(R.string.onb_modes_body))
    Mode.entries.forEach { m ->
        ChoiceCard(modeName(m), modeDescription(m), current == m, {
            vm.edit { r ->
                val chosen = data?.list?.items.orEmpty().map { it.key }
                r.copy(defaultRule = r.defaultRule.copy(mode = m), apps = r.apps + chosen.associateWith { r.rule(it).copy(mode = m) })
            }
        }, swatch = modeColor(m))
    }
    Text(stringResource(R.string.onb_modes_note), color = Aurora.colors.textTertiary, style = AuroraType.Caption)
}

@Composable
private fun TurnOnShield() {
    val context = LocalContext.current
    val colors = Aurora.colors
    val access = rememberAccess(Access.Accessibility)
    val connected by ShieldService.connected.collectAsStateWithLifecycle()
    var disclosure by rememberSaveable { mutableStateOf(false) }
    Title(stringResource(R.string.onb_shield_eyebrow), stringResource(R.string.onb_shield_title), stringResource(R.string.onb_shield_body))
    StatusLine(stringResource(R.string.onb_shield_permission), access == AccessState.Granted)
    StatusLine(stringResource(R.string.onb_shield_running), connected)
    if (access != AccessState.Granted) AuroraButton(stringResource(R.string.status_turn_on), { disclosure = true }, Modifier.fillMaxWidth())
    Text(stringResource(R.string.onb_shield_next), color = colors.textSecondary, style = AuroraType.Body)
    Text(stringResource(R.string.onb_shield_privacy), color = colors.textTertiary, style = AuroraType.Caption)
    if (disclosure) ShieldDisclosure(onContinue = { disclosure = false; openAccessSettings(context, Access.Accessibility) }, onDismiss = { disclosure = false })
}

@Composable
private fun StatusLine(text: String, ok: Boolean) {
    val colors = Aurora.colors
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.S)) {
        SelectionMark(ok)
        Text(text, color = if (ok) colors.textPrimary else colors.textSecondary, style = AuroraType.BodyStrong)
    }
}

@Composable
private fun FirstLight(vm: ProtectModel) {
    val data by vm.data.collectAsStateWithLifecycle()
    val colors = Aurora.colors
    Spacer(Modifier.height(220.dp))
    Text(stringResource(R.string.onb_first_light_eyebrow), color = colors.violetText, style = AuroraType.Eyebrow)
    Text(stringResource(R.string.onb_first_light_title), Modifier.semantics { heading() }, color = colors.textPrimary, style = AuroraType.Display)
    val count = data?.list?.items?.size ?: 0
    Text(
        if (count == 0) stringResource(R.string.onb_first_light_none) else androidx.compose.ui.res.pluralStringResource(R.plurals.onb_first_light_body, count, count),
        color = colors.textSecondary, style = AuroraType.Body,
    )
    if (count > 0) {
        val mode = data?.rules?.defaultRule?.mode ?: Mode.Flexible
        Text(stringResource(R.string.onb_ready_summary, count, modeName(mode)), color = colors.textPrimary, style = AuroraType.BodyStrong)
        Text(stringResource(R.string.onb_ready_later), color = colors.textTertiary, style = AuroraType.Caption)
    }
}
