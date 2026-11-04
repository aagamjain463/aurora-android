package app.aurora.ui.onboarding

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.aurora.AuroraApplication
import app.aurora.R
import app.aurora.data.Access
import app.aurora.data.AccessState
import app.aurora.data.openAccessSettings
import app.aurora.protect.Mode
import app.aurora.protect.ShieldService
import app.aurora.rememberAccess
import app.aurora.ui.components.AuroraButton
import app.aurora.ui.components.AuroraIcons
import app.aurora.ui.components.AuroraMark
import app.aurora.ui.components.ButtonKind
import app.aurora.ui.components.Chip
import app.aurora.ui.components.ContentMaxWidth
import app.aurora.ui.components.Eyebrow
import app.aurora.ui.components.HeroHeadline
import app.aurora.ui.components.LitBadge
import app.aurora.ui.components.LockupMark
import app.aurora.ui.components.SelectionMark
import app.aurora.ui.components.Wordmark
import app.aurora.ui.components.auroraSurface
import app.aurora.ui.components.glass
import app.aurora.ui.components.minutesText
import app.aurora.ui.components.sunlit
import app.aurora.ui.home.IntentChip
import app.aurora.ui.protect.ModeChip
import app.aurora.ui.protect.ShieldDisclosure
import app.aurora.ui.protect.isXiaomi
import app.aurora.ui.protect.openAppInfo
import app.aurora.ui.protect.openAutostart
import app.aurora.ui.theme.Aurora
import app.aurora.ui.theme.AuroraType
import app.aurora.ui.theme.Motion
import app.aurora.ui.theme.Radius
import app.aurora.ui.theme.Space
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val PageCount = 6
internal const val ShieldPage = PageCount - 1

/** The four tabs, toured in the order they are used: make a Shield and an Intent, start them from Home, see it in Track. */
private enum class Tour(val title: Int, val body: Int) {
    Shield(R.string.onb_tour_shield_title, R.string.onb_tour_shield_body),
    Intent(R.string.onb_tour_intent_title, R.string.onb_tour_intent_body),
    Home(R.string.onb_tour_home_title, R.string.onb_tour_home_body),
    Track(R.string.onb_tour_track_title, R.string.onb_tour_track_body),
}

/**
 * A short tour, no setup: the promise, then Shield, Intent, Home and Track, each a moving picture, one serif line
 * and one plain line, then turning Shield on. Everything is centred and quiet; one filled action per page. Nothing is
 * picked or started here. The person can leave and come back; onboarding resumes where they were.
 */
@Composable
fun OnboardingScreen(onFinish: () -> Unit) {
    val app = LocalContext.current.applicationContext as AuroraApplication
    val context = LocalContext.current
    val colors = Aurora.colors
    val reduced = Aurora.reducedMotion
    val prefs by app.preferences.preferences.collectAsStateWithLifecycle(initialValue = null)
    var page by rememberSaveable { mutableIntStateOf(-1) }
    LaunchedEffect(prefs != null) { if (page < 0 && prefs != null) page = prefs!!.onboardingStep.coerceIn(0, PageCount - 1) }
    LaunchedEffect(page) { if (page >= 0) runCatching { app.preferences.setOnboardingStep(page) } }
    BackHandler(enabled = page > 0) { page-- }
    val p = page.coerceAtLeast(0)
    val shieldOn = rememberAccess(Access.Accessibility) == AccessState.Granted
    var disclosure by rememberSaveable { mutableStateOf(false) }
    val finish = {
        app.scope.launch { runCatching { app.preferences.setOnboardingStep(0) } }
        onFinish()
    }

    Box(Modifier.fillMaxSize().background(if (p == 0) colors.sky else colors.background)) {
        Column(Modifier.align(Alignment.TopCenter).widthIn(max = ContentMaxWidth).fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            // The welcome is the mark alone; the pages after it carry the lockup, and the tour can be skipped.
            Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = Space.Gutter), verticalAlignment = Alignment.CenterVertically) {
                if (p != 0) {
                    AuroraMark(LockupMark)
                    Spacer(Modifier.width(Space.S))
                    Wordmark(Modifier.weight(1f))
                    if (p < ShieldPage) AuroraButton(stringResource(R.string.onb_skip_tour), { page = ShieldPage }, kind = ButtonKind.Quiet, compact = true)
                }
            }
            AnimatedContent(
                targetState = p,
                modifier = Modifier.weight(1f),
                transitionSpec = {
                    val forward = targetState > initialState
                    if (reduced) fadeIn(tween(Motion.Standard)) togetherWith fadeOut(tween(Motion.Quick))
                    else (fadeIn(tween(Motion.Gentle)) + slideInHorizontally(tween(Motion.Gentle, easing = Motion.Ease)) { if (forward) it / 5 else -it / 5 }) togetherWith
                        (fadeOut(tween(Motion.Standard)) + slideOutHorizontally(tween(Motion.Gentle, easing = Motion.Ease)) { if (forward) -it / 8 else it / 8 })
                },
                label = "page",
            ) { step ->
                when (step) {
                    0 -> Welcome()
                    ShieldPage -> TurnOnShield()
                    else -> TourPage(Tour.entries[step - 1])
                }
            }
            val navInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
            Column(
                Modifier.padding(horizontal = Space.Gutter).padding(top = Space.M, bottom = (if (p == 0) 48.dp else Space.L) - navInset.coerceAtMost(Space.M)).animateContentSize(),
                verticalArrangement = Arrangement.spacedBy(Space.XS),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (p != 0) PageDots(p, Modifier.padding(bottom = Space.M))
                when {
                    p == 0 -> AuroraButton(stringResource(R.string.onb_begin), { page = 1 }, Modifier.fillMaxWidth())
                    p < ShieldPage -> AuroraButton(stringResource(R.string.action_continue), { page = p + 1 }, Modifier.fillMaxWidth())
                    // One filled action per page: until Shield is on, turning it on is it, and skipping stays quiet.
                    !shieldOn -> {
                        AuroraButton(stringResource(R.string.status_turn_on), { disclosure = true }, Modifier.fillMaxWidth())
                        AuroraButton(stringResource(R.string.onb_skip), finish, Modifier.fillMaxWidth(), kind = ButtonKind.Quiet)
                    }
                    else -> AuroraButton(stringResource(R.string.onb_finish), finish, Modifier.fillMaxWidth())
                }
            }
        }
    }
    if (disclosure) ShieldDisclosure(onContinue = { disclosure = false; openAccessSettings(context, Access.Accessibility) }, onDismiss = { disclosure = false })
}

/** Where you are: a lit dash for this page, points for the rest. */
@Composable
private fun PageDots(page: Int, modifier: Modifier = Modifier) {
    val colors = Aurora.colors
    val spoken = stringResource(R.string.onb_step, page, PageCount - 1)
    Row(modifier.semantics { contentDescription = spoken }, horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        repeat(PageCount - 1) { i ->
            val width by animateDpAsState(if (i + 1 == page) 22.dp else 6.dp, tween(Motion.Gentle, easing = Motion.Ease), label = "dot")
            Box(Modifier.height(4.dp).width(width).clip(Radius.Pill).background(if (i + 1 == page) colors.accent else colors.hairline))
        }
    }
}

/** Fades up into place [order] beats after the page arrives. Still when motion is reduced. */
private fun Modifier.enter(order: Int): Modifier = composed {
    val reduced = Aurora.reducedMotion
    val shown = remember { Animatable(if (reduced) 1f else 0f) }
    LaunchedEffect(Unit) {
        if (!reduced) {
            delay(80L + order * 90L)
            shown.animateTo(1f, tween(Motion.Reveal, easing = Motion.Ease))
        }
    }
    graphicsLayer {
        alpha = shown.value
        translationY = (1f - shown.value) * 22.dp.toPx()
    }
}

/** A loop from 0 to 1 every [ms], for the tour's pictures. Reduced motion holds it at [still]. */
@Composable
private fun loop(ms: Int, still: Float): Float =
    if (Aurora.reducedMotion) still
    else rememberInfiniteTransition(label = "tour").animateFloat(0f, 1f, infiniteRepeatable(tween(ms, easing = LinearEasing), RepeatMode.Restart), label = "tour").value

/** 0 before [from], 1 once [from] + [span] has passed, eased between; falls back to 0 over the loop's last moment. */
private fun ramp(t: Float, from: Float, span: Float = 0.1f): Float {
    val up = Motion.Ease.transform(((t - from) / span).coerceIn(0f, 1f))
    val down = ((1f - t) / 0.06f).coerceIn(0f, 1f)
    return up * down
}

/**
 * The welcome: the lit mark alone in the middle of the night, and the promise under it with its last word lit.
 * Each settles in a beat after the last. The column fills the screen, and scrolls when text is large.
 */
@Composable
internal fun Welcome() {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).heightIn(min = maxHeight).padding(horizontal = Space.Gutter),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.weight(1f))
            AuroraMark(76.dp, Modifier.enter(0), lit = true)
            Spacer(Modifier.height(44.dp))
            HeroHeadline(
                stringResource(R.string.today_headline), Modifier.enter(2),
                style = AuroraType.HeroBold.copy(fontSize = 48.sp, lineHeight = 50.sp, textAlign = TextAlign.Center),
            )
            Spacer(Modifier.weight(1.2f))
        }
    }
}

/** One tab: its moving picture, one serif line and one plain line, centred. */
@Composable
private fun TourPage(tour: Tour) {
    val colors = Aurora.colors
    BoxWithConstraints(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).heightIn(min = maxHeight).padding(horizontal = Space.Gutter),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.weight(1f).heightIn(min = Space.M))
            // Decorative: the words below say what the picture shows.
            Box(Modifier.fillMaxWidth().enter(0).clearAndSetSemantics { }) {
                when (tour) {
                    Tour.Shield -> ShieldArt()
                    Tour.Intent -> IntentArt()
                    Tour.Home -> HomeArt()
                    Tour.Track -> TrackArt()
                }
            }
            Spacer(Modifier.height(48.dp))
            Text(
                stringResource(tour.title), Modifier.enter(1).semantics { heading() },
                color = colors.textPrimary, style = AuroraType.Display, textAlign = TextAlign.Center,
            )
            Text(
                stringResource(tour.body), Modifier.padding(top = Space.S).widthIn(max = 300.dp).enter(2),
                color = colors.textSecondary, style = AuroraType.Body, textAlign = TextAlign.Center,
            )
            Spacer(Modifier.weight(1.3f).heightIn(min = Space.M))
        }
    }
}

private val TileShape = RoundedCornerShape(16.dp)

/** Shield: four apps, locked one after another, and the four ways it can hold them, lit in turn. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ShieldArt() {
    val colors = Aurora.colors
    val t = loop(5200, still = 0.8f)
    Column(Modifier.fillMaxWidth().auroraSurface(colors).padding(22.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            listOf(AuroraIcons.Globe, AuroraIcons.Play, AuroraIcons.Quote, AuroraIcons.Spark).forEachIndexed { i, icon ->
                val on = ramp(t, 0.08f + i * 0.12f)
                Box(Modifier.size(58.dp)) {
                    Box(
                        Modifier.fillMaxSize().clip(TileShape).background(colors.field).border(1.dp, colors.hairline, TileShape),
                        contentAlignment = Alignment.Center,
                    ) { Icon(icon, null, Modifier.size(24.dp).graphicsLayer { alpha = 1f - 0.55f * on }, tint = colors.textSecondary) }
                    Box(
                        Modifier.align(Alignment.BottomEnd).offset(6.dp, 6.dp).size(26.dp)
                            .graphicsLayer { scaleX = on; scaleY = on; alpha = on }
                            .clip(Radius.Pill).sunlit(colors),
                        contentAlignment = Alignment.Center,
                    ) { Icon(AuroraIcons.Lock, null, Modifier.size(13.dp), tint = colors.onAccent) }
                }
            }
        }
        val current = (t * Mode.entries.size).toInt().coerceIn(0, Mode.entries.lastIndex)
        val still = Aurora.reducedMotion
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.XS), verticalArrangement = Arrangement.spacedBy(Space.XS)) {
            Mode.entries.forEachIndexed { i, m -> ModeChip(m, Modifier.graphicsLayer { alpha = if (still || i == current) 1f else 0.4f }) }
        }
    }
}

/** Intent: the question, a pause that closes, then a time chosen. */
@Composable
private fun IntentArt() {
    val colors = Aurora.colors
    val t = loop(5600, still = 0.9f)
    val pause = (t / 0.5f).coerceIn(0f, 1f)
    val chosen = ramp(t, 0.58f) > 0.5f
    Column(Modifier.fillMaxWidth().auroraSurface(colors).padding(22.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(44.dp).clip(RoundedCornerShape(13.dp)).background(colors.field).border(1.dp, colors.hairline, RoundedCornerShape(13.dp)), contentAlignment = Alignment.Center) {
                Icon(AuroraIcons.Spark, null, Modifier.size(20.dp), tint = colors.textSecondary)
            }
            Spacer(Modifier.weight(1f))
            Box(Modifier.size(34.dp).clip(Radius.Pill).background(colors.elevated), contentAlignment = Alignment.Center) {
                Icon(AuroraIcons.Close, null, Modifier.size(14.dp), tint = colors.textPrimary)
            }
        }
        Text(stringResource(R.string.onb_tour_mock_question), color = colors.textPrimary, style = AuroraType.DisplayS.copy(fontSize = 24.sp, lineHeight = 28.sp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.M)) {
            Box(Modifier.size(52.dp), contentAlignment = Alignment.Center) {
                Canvas(Modifier.fillMaxSize()) {
                    val stroke = 3.dp.toPx()
                    val inset = stroke / 2 + 1.dp.toPx()
                    val box = Size(size.width - 2 * inset, size.height - 2 * inset)
                    drawArc(colors.hairline, 0f, 360f, false, Offset(inset, inset), box, style = Stroke(stroke))
                    drawArc(colors.accent, -90f, 360f * (1f - pause), false, Offset(inset, inset), box, style = Stroke(stroke, cap = StrokeCap.Round))
                }
                val left = (5 * (1f - pause) + 0.999f).toInt().coerceAtMost(5)
                Text(if (left > 0 && pause < 1f) "${left}s" else "✓", color = colors.textPrimary, style = AuroraType.Caption)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(Space.XS)) {
                listOf(5, 15, 30).forEach { m -> Chip(minutesText(m), chosen && m == 15) { } }
            }
        }
    }
}

/** Home: Start session, lit and pressed now and then, over a saved session holding a Shield and an Intent. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HomeArt() {
    val colors = Aurora.colors
    val t = loop(3600, still = 0f)
    val press = 1f - 0.035f * (ramp(t, 0.3f, 0.06f) * (1f - ramp(t, 0.42f, 0.08f)).coerceIn(0f, 1f))
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(
            Modifier.fillMaxWidth().graphicsLayer { scaleX = press; scaleY = press }.heightIn(min = 58.dp).clip(Radius.Pill).sunlit(colors).padding(horizontal = Space.L),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center,
        ) {
            Icon(AuroraIcons.Play, null, Modifier.size(18.dp), tint = colors.onAccent)
            Spacer(Modifier.size(10.dp))
            Text(stringResource(R.string.home_start_title), color = colors.onAccent, style = AuroraType.Label)
        }
        Column(Modifier.fillMaxWidth().auroraSurface(colors).padding(horizontal = 22.dp, vertical = 20.dp), verticalArrangement = Arrangement.spacedBy(Space.S)) {
            Text(stringResource(R.string.onb_tour_mock_session), color = colors.textPrimary, style = AuroraType.Title)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.XS), verticalArrangement = Arrangement.spacedBy(Space.XS)) {
                ModeChip(Mode.Flexible, label = stringResource(R.string.tab_shield))
                IntentChip(stringResource(R.string.tab_intent))
            }
            Row(
                Modifier.fillMaxWidth().heightIn(min = 48.dp).glass(colors, Radius.Pill),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center,
            ) {
                Icon(AuroraIcons.Play, null, Modifier.size(16.dp), tint = colors.textPrimary)
                Spacer(Modifier.size(8.dp))
                Text(stringResource(R.string.cfg_start), color = colors.textPrimary, style = AuroraType.Title)
            }
        }
    }
}

/** Track: days lighting up one by one as a habit is kept, and a line filling towards it. No figures: nothing is invented. */
@Composable
private fun TrackArt() {
    val colors = Aurora.colors
    val t = loop(5000, still = 0.85f)
    val days = 10
    Column(Modifier.fillMaxWidth().auroraSurface(colors).padding(22.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.S)) {
            LitBadge(AuroraIcons.Target, size = 36)
            Text(stringResource(R.string.track_tab_goals), color = colors.textPrimary, style = AuroraType.Title)
        }
        Box(Modifier.fillMaxWidth().height(6.dp).clip(Radius.Pill).background(colors.hairline)) {
            Box(Modifier.fillMaxWidth((ramp(t, 0.05f, 0.75f)).coerceIn(0f, 1f)).height(6.dp).clip(Radius.Pill).background(colors.accent))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            repeat(days) { i ->
                val on = ramp(t, 0.05f + i * 0.07f, 0.06f)
                Box(Modifier.size(14.dp).clip(Radius.Pill).background(colors.hairline), contentAlignment = Alignment.Center) {
                    Box(Modifier.size(14.dp).graphicsLayer { scaleX = on; scaleY = on; alpha = on }.clip(Radius.Pill).background(colors.accent))
                }
            }
        }
    }
}

/** The last page: Shield needs one permission, asked for by the action below and explained when it is. */
@Composable
private fun TurnOnShield() {
    val context = LocalContext.current
    val colors = Aurora.colors
    val access = rememberAccess(Access.Accessibility)
    val connected by ShieldService.connected.collectAsStateWithLifecycle()
    BoxWithConstraints(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).heightIn(min = maxHeight).padding(horizontal = Space.Gutter),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.weight(1f).heightIn(min = Space.M))
            AuroraMark(64.dp, Modifier.enter(0), lit = true)
            Spacer(Modifier.height(40.dp))
            Text(
                stringResource(R.string.onb_shield_title), Modifier.enter(1).semantics { heading() },
                color = colors.textPrimary, style = AuroraType.Display, textAlign = TextAlign.Center,
            )
            Text(
                stringResource(R.string.onb_shield_body), Modifier.padding(top = Space.S).widthIn(max = 300.dp).enter(2),
                color = colors.textSecondary, style = AuroraType.Body, textAlign = TextAlign.Center,
            )
            if (access == AccessState.Granted) Box(Modifier.padding(top = Space.L)) { StatusLine(stringResource(R.string.onb_shield_running), connected) }
            // Xiaomi stops background services: once Shield is on, keeping it running is the one thing left.
            if (isXiaomi && access == AccessState.Granted) {
                Text(
                    stringResource(R.string.onb_keep_xiaomi), Modifier.padding(top = Space.L).widthIn(max = 300.dp),
                    color = colors.textTertiary, style = AuroraType.Caption, textAlign = TextAlign.Center,
                )
                Column(Modifier.padding(top = Space.XS), horizontalAlignment = Alignment.CenterHorizontally) {
                    AuroraButton(stringResource(R.string.keep_running_autostart), { openAutostart(context) }, kind = ButtonKind.Quiet, compact = true)
                    AuroraButton(stringResource(R.string.keep_running_battery), { openAppInfo(context) }, kind = ButtonKind.Quiet, compact = true)
                }
            }
            Spacer(Modifier.weight(1.3f).heightIn(min = Space.M))
        }
    }
}

@Composable
private fun StatusLine(text: String, ok: Boolean) {
    val colors = Aurora.colors
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.S)) {
        SelectionMark(ok)
        Text(text, color = if (ok) colors.textPrimary else colors.textSecondary, style = AuroraType.BodyStrong)
    }
}
