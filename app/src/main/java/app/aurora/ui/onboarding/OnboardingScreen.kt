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
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.ui.semantics.Role
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
import androidx.compose.foundation.layout.size
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
import app.aurora.ui.components.glass
import app.aurora.AuroraApplication
import app.aurora.R
import app.aurora.SessionNotification
import app.aurora.auroraViewModel
import app.aurora.data.Access
import app.aurora.data.AccessState
import app.aurora.data.openAccessSettings
import app.aurora.plan.Allowance
import app.aurora.plan.ProtectedItem
import app.aurora.activate
import app.aurora.normalSession
import app.aurora.protect.ActivationProblem
import app.aurora.protect.Mode
import app.aurora.protect.Timing
import app.aurora.protect.strict
import app.aurora.ui.configure.CommitBlock
import app.aurora.ui.configure.problemText
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
import app.aurora.ui.components.minutesText
import app.aurora.ui.protect.ItemIcon
import app.aurora.ui.protect.ProtectModel
import app.aurora.ui.protect.ShieldDisclosure
import app.aurora.ui.protect.appList
import app.aurora.ui.protect.isXiaomi
import app.aurora.ui.protect.modeColor
import app.aurora.ui.protect.modeDescription
import app.aurora.ui.protect.modeName
import app.aurora.ui.protect.openAppInfo
import app.aurora.ui.protect.openAutostart
import app.aurora.ui.theme.Aurora
import app.aurora.ui.theme.AuroraType
import app.aurora.ui.theme.Motion
import app.aurora.ui.theme.Radius
import app.aurora.ui.theme.Space
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.runtime.remember
import app.aurora.ui.components.BrandEyebrow
import app.aurora.ui.components.LitBadge
import app.aurora.ui.components.AuroraIcons
import androidx.compose.foundation.layout.widthIn
import app.aurora.ui.components.ContentMaxWidth

private const val PageCount = 4
private const val StartPage = 2
private const val ShieldPage = 3

/**
 * Four steps: the promise, the apps that pull you away (every launchable app, searchable), the first
 * session, then turning Shield on, explained at the moment it is needed. The person can leave and come
 * back; onboarding resumes where they were. Protection starts only from a button they press.
 */
@Composable
fun OnboardingScreen(onFinish: () -> Unit) {
    val app = LocalContext.current.applicationContext as AuroraApplication
    val vm = auroraViewModel { ProtectModel(it) }
    val colors = Aurora.colors
    val reduced = Aurora.reducedMotion
    val prefs by app.preferences.preferences.collectAsStateWithLifecycle(initialValue = null)
    var page by rememberSaveable { mutableIntStateOf(-1) }
    LaunchedEffect(prefs != null) { if (page < 0 && prefs != null) page = prefs!!.onboardingStep.coerceIn(0, PageCount - 1) }
    LaunchedEffect(page) { if (page >= 0) runCatching { app.preferences.setOnboardingStep(page) } }
    val notice by vm.notice.collectAsStateWithLifecycle()
    BackHandler(enabled = page > 0) { page-- }
    val p = page.coerceAtLeast(0)
    val shieldOn = rememberAccess(Access.Accessibility) == AccessState.Granted
    val data by vm.data.collectAsStateWithLifecycle()
    val chosen = data?.list?.items?.map { it.key }.orEmpty()
    val mode = data?.rules?.defaultRule?.mode ?: Mode.Flexible
    var firstMinutes by rememberSaveable { mutableIntStateOf(60) }
    var committed by rememberSaveable { mutableStateOf(false) }
    var problem by rememberSaveable { mutableStateOf<ActivationProblem?>(null) }
    val context = LocalContext.current
    val notifications = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { }

    Box(Modifier.fillMaxSize().background(colors.background)) {
        // First Light opens and closes onboarding; the steps between are quiet.
        androidx.compose.animation.AnimatedVisibility(p == 0 || p == StartPage, enter = fadeIn(tween(Motion.Gentle)), exit = fadeOut(tween(Motion.Standard))) {
            // The sky lights on the first page and surges on the last, when Aurora is ready.
            AuroraBackdrop(ArtTier.Immersive, sky = if (p == StartPage) Sky.Flare else Sky.Lit)
        }
        PageGlow()
        Column(Modifier.align(Alignment.TopCenter).widthIn(max = ContentMaxWidth).fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()) {
            Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = Space.Gutter), verticalAlignment = Alignment.CenterVertically) {
                AuroraMark(LockupMark)
                Spacer(Modifier.width(Space.S))
                Wordmark(Modifier.weight(1f))
                // The art pages keep the sky clear; the steps between show where you are.
                if (p == 1 || p == ShieldPage) PageDots(p)
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
                    0 -> Welcome()
                    1 -> Apps(vm)
                    StartPage -> Page { FirstLight(vm, firstMinutes, { firstMinutes = it }, committed, { committed = it }) }
                    else -> Page { TurnOnShield() }
                }
            }
            Column(Modifier.padding(horizontal = Space.Gutter, vertical = Space.M).animateContentSize(), verticalArrangement = Arrangement.spacedBy(Space.XS)) {
                val finish = {
                    app.scope.launch { runCatching { app.preferences.setOnboardingStep(0) } }
                    onFinish()
                }
                problem?.takeUnless { it.overLimit }?.let { Text(problemText(it), color = colors.danger, style = AuroraType.Secondary) }
                val name = stringResource(R.string.home_normal_title)
                val startNow = {
                    if (android.os.Build.VERSION.SDK_INT >= 33 && !SessionNotification.allowed(context)) {
                        runCatching { notifications.launch(android.Manifest.permission.POST_NOTIFICATIONS) }
                    }
                    vm.run {
                        problem = app.activate(normalSession(name, chosen, mode), Timing.Now(firstMinutes))?.problem
                        if (problem == null) finish()
                    }
                    Unit
                }
                when {
                    // Starting is the point of the step; without Shield it leads to turning it on first.
                    p >= StartPage && chosen.isNotEmpty() && (p == StartPage || shieldOn) -> {
                        val ready = !mode.strict || committed
                        if (!ready) Text(stringResource(R.string.need_commit), color = colors.textSecondary, style = AuroraType.Secondary)
                        AuroraButton(stringResource(R.string.onb_start_now), { if (shieldOn) startNow() else page = ShieldPage }, Modifier.fillMaxWidth(), enabled = ready)
                        AuroraButton(stringResource(R.string.onb_start_later), { finish() }, Modifier.fillMaxWidth(), kind = ButtonKind.Quiet)
                    }
                    // One filled action per step: until Shield is on, "Turn on Shield" is it, and skipping stays quiet.
                    p == ShieldPage && !shieldOn -> AuroraButton(stringResource(R.string.onb_skip), { finish() }, Modifier.fillMaxWidth(), kind = ButtonKind.Quiet)
                    p >= StartPage -> AuroraButton(stringResource(R.string.onb_finish), { finish() }, Modifier.fillMaxWidth())
                    else -> AuroraButton(stringResource(if (p == 0) R.string.onb_begin else R.string.action_continue), { page = p + 1 }, Modifier.fillMaxWidth())
                }
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
    val spoken = stringResource(R.string.onb_step, page + 1, PageCount)
    Row(Modifier.semantics { contentDescription = spoken }, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        repeat(PageCount) { i ->
            Box(Modifier.height(4.dp).width(if (i == page) 18.dp else 6.dp).clip(Radius.Pill).background(if (i <= page) colors.accent else colors.hairline))
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
@Composable
internal fun Welcome() {
    val colors = Aurora.colors
    val reduced = Aurora.reducedMotion
    val pillars = listOf(
        Triple(AuroraIcons.Protect, R.string.onb_pillar_shield, R.string.onb_pillar_shield_body),
        Triple(AuroraIcons.Intent, R.string.onb_pillar_intent, R.string.onb_pillar_intent_body),
        Triple(AuroraIcons.Track, R.string.onb_pillar_track, R.string.onb_pillar_track_body),
    )
    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).heightIn(min = maxHeight).padding(horizontal = Space.Gutter),
            verticalArrangement = Arrangement.spacedBy(Space.S),
        ) {
            Spacer(Modifier.weight(1f).heightIn(min = 160.dp))
            BrandEyebrow(stringResource(R.string.onb_welcome_eyebrow))
            TwoToneHeadline(stringResource(R.string.today_headline))
            Text(stringResource(R.string.onb_welcome_body), color = colors.textSecondary, style = AuroraType.Body)
            Spacer(Modifier.height(Space.S))
            pillars.forEachIndexed { i, (icon, title, body) ->
                val shown = remember { androidx.compose.animation.core.Animatable(if (reduced) 1f else 0f) }
                LaunchedEffect(Unit) {
                    if (!reduced) shown.animateTo(1f, tween(Motion.Gentle, delayMillis = 260 + 140 * i, easing = Motion.Ease))
                }
                Row(
                    Modifier.fillMaxWidth().graphicsLayer { alpha = shown.value; translationY = (1f - shown.value) * 24f },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Space.M),
                ) {
                    LitBadge(icon, size = 44, tint = if (i == 2) colors.rose else colors.accent)
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(title), color = colors.textPrimary, style = AuroraType.BodyStrong)
                        Text(stringResource(body), color = colors.textSecondary, style = AuroraType.Secondary)
                    }
                }
            }
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
    Text(stringResource(R.string.onb_shield_privacy), color = colors.textTertiary, style = AuroraType.Caption)
    // Xiaomi stops background services, so keeping Shield running belongs with turning it on.
    if (isXiaomi) {
        Spacer(Modifier.height(Space.S))
        Text(stringResource(R.string.onb_keep_xiaomi), color = colors.textSecondary, style = AuroraType.Secondary)
        AuroraButton(stringResource(R.string.keep_running_autostart), { openAutostart(context) }, Modifier.fillMaxWidth(), kind = ButtonKind.Secondary)
        AuroraButton(stringResource(R.string.keep_running_battery), { openAppInfo(context) }, Modifier.fillMaxWidth(), kind = ButtonKind.Quiet)
    }
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

/** The first session: how long and how. Nothing starts until the person presses Start protection. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FirstLight(vm: ProtectModel, minutes: Int, onMinutes: (Int) -> Unit, committed: Boolean, onCommitted: (Boolean) -> Unit) {
    val data by vm.data.collectAsStateWithLifecycle()
    val colors = Aurora.colors
    val count = data?.list?.items?.size ?: 0
    val mode = data?.rules?.defaultRule?.mode ?: Mode.Flexible
    Spacer(Modifier.height(if (count > 0) 180.dp else 260.dp))
    Text(stringResource(R.string.onb_first_light_eyebrow), color = colors.violetText, style = AuroraType.Eyebrow)
    Text(stringResource(R.string.onb_first_light_title), Modifier.semantics { heading() }, color = colors.textPrimary, style = AuroraType.Display)
    Text(
        if (count == 0) stringResource(R.string.onb_first_light_none) else androidx.compose.ui.res.pluralStringResource(R.plurals.onb_first_light_body, count, count),
        color = colors.textSecondary, style = AuroraType.Body,
    )
    if (count == 0) return
    Text(stringResource(R.string.cfg_how_long), Modifier.padding(top = Space.S), color = colors.textPrimary, style = AuroraType.Title)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.XS), verticalArrangement = Arrangement.spacedBy(Space.XS)) {
        listOf(15, 30, 60, 120).forEach { m -> Chip(minutesText(m), minutes == m) { onMinutes(m) } }
    }
    Text(stringResource(R.string.quick_mode), Modifier.padding(top = Space.S), color = colors.textPrimary, style = AuroraType.Title)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.XS), verticalArrangement = Arrangement.spacedBy(Space.XS)) {
        Mode.entries.forEach { m ->
            Chip(modeName(m), mode == m) {
                vm.edit { r ->
                    val chosen = data?.list?.items.orEmpty().map { it.key }
                    r.copy(defaultRule = r.defaultRule.copy(mode = m), apps = r.apps + chosen.associateWith { r.rule(it).copy(mode = m) })
                }
            }
        }
    }
    Text(modeDescription(mode), color = colors.textSecondary, style = AuroraType.Secondary)
    if (mode.strict) CommitBlock(committed, onCommitted, null)
}
