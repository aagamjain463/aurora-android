package app.aurora.ui.onboarding

import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.clearAndSetSemantics
import app.aurora.protect.Mode
import app.aurora.ui.components.Chip
import app.aurora.ui.components.minutesText
import app.aurora.ui.components.glass
import app.aurora.ui.components.sunlit
import app.aurora.ui.home.IntentChip
import app.aurora.ui.protect.ModeChip
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.aurora.AuroraApplication
import app.aurora.R
import app.aurora.data.Access
import app.aurora.data.AccessState
import app.aurora.data.openAccessSettings
import app.aurora.protect.ShieldService
import app.aurora.rememberAccess
import app.aurora.ui.components.AuroraButton
import app.aurora.ui.components.AuroraIcons
import app.aurora.ui.components.AuroraMark
import app.aurora.ui.components.ButtonKind
import app.aurora.ui.components.ContentMaxWidth
import app.aurora.ui.components.HeroHeadline
import app.aurora.ui.components.LitBadge
import app.aurora.ui.components.LockupMark
import app.aurora.ui.components.SelectionMark
import app.aurora.ui.components.Wordmark
import app.aurora.ui.components.auroraSurface
import app.aurora.ui.protect.ShieldDisclosure
import app.aurora.ui.protect.isXiaomi
import app.aurora.ui.protect.openAppInfo
import app.aurora.ui.protect.openAutostart
import app.aurora.ui.theme.Aurora
import app.aurora.ui.theme.AuroraType
import app.aurora.ui.theme.Motion
import app.aurora.ui.theme.Radius
import app.aurora.ui.theme.Space
import kotlinx.coroutines.launch

/** The four tabs in the order they are used: make a Shield and an Intent, start them from Home, see it in Track. */
private enum class Tab(val icon: ImageVector, val label: Int, val title: Int, val body: Int) {
    Shield(AuroraIcons.Protect, R.string.tab_shield, R.string.onb_tour_shield_title, R.string.onb_tour_shield_body),
    Intent(AuroraIcons.Intent, R.string.tab_intent, R.string.onb_tour_intent_title, R.string.onb_tour_intent_body),
    Home(AuroraIcons.Home, R.string.tab_home, R.string.onb_tour_home_title, R.string.onb_tour_home_body),
    Track(AuroraIcons.Track, R.string.tab_track, R.string.onb_tour_track_title, R.string.onb_tour_track_body),
}

private const val HowPage = 1
private const val FirstTabPage = 2
private val PageCount = FirstTabPage + Tab.entries.size + 1
internal val ShieldPage = PageCount - 1

/**
 * Still pages: the promise, how Aurora works (its four tabs on one card), a page for each tab (a picture of it, its
 * name, one serif line and one plain line), then turning Shield on. Everything is centred, nothing moves but a plain
 * fade between pages, and each page has one filled action. Nothing is picked or started here. The person can leave and come back; onboarding resumes where they were.
 */
@Composable
fun OnboardingScreen(onFinish: () -> Unit) {
    val app = LocalContext.current.applicationContext as AuroraApplication
    val context = LocalContext.current
    val colors = Aurora.colors
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
            // The welcome is the mark alone; the pages after it carry the lockup.
            Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = Space.Gutter), verticalAlignment = Alignment.CenterVertically) {
                if (p != 0) {
                    AuroraMark(LockupMark)
                    Spacer(Modifier.width(Space.S))
                    Wordmark(Modifier.weight(1f))
                    if (p < ShieldPage) AuroraButton(stringResource(R.string.onb_skip_tour), { page = ShieldPage }, kind = ButtonKind.Quiet, compact = true)
                }
            }
            Crossfade(p, Modifier.weight(1f), animationSpec = tween(Motion.Standard), label = "page") { step ->
                when (step) {
                    0 -> Welcome()
                    HowPage -> HowItWorks()
                    ShieldPage -> TurnOnShield()
                    else -> TabPage(Tab.entries[step - FirstTabPage])
                }
            }
            val navInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
            Column(
                Modifier.padding(horizontal = Space.Gutter).padding(top = Space.M, bottom = Space.L - navInset.coerceAtMost(Space.M)),
                verticalArrangement = Arrangement.spacedBy(Space.XS),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                when {
                    p == 0 -> {
                        AuroraButton(stringResource(R.string.onb_begin), { page = HowPage }, Modifier.fillMaxWidth())
                        Privacy(Modifier.padding(top = Space.S))
                    }
                    p < ShieldPage -> {
                        PageDots(p, Modifier.padding(bottom = Space.M))
                        AuroraButton(stringResource(R.string.action_continue), { page = p + 1 }, Modifier.fillMaxWidth())
                    }
                    // One filled action per page: until Shield is on, turning it on is it, and skipping stays quiet.
                    !shieldOn -> {
                        PageDots(p, Modifier.padding(bottom = Space.M))
                        AuroraButton(stringResource(R.string.status_turn_on), { disclosure = true }, Modifier.fillMaxWidth())
                        AuroraButton(stringResource(R.string.onb_skip), finish, Modifier.fillMaxWidth(), kind = ButtonKind.Quiet)
                    }
                    else -> {
                        PageDots(p, Modifier.padding(bottom = Space.M))
                        AuroraButton(stringResource(R.string.onb_finish), finish, Modifier.fillMaxWidth())
                    }
                }
            }
        }
    }
    if (disclosure) ShieldDisclosure(onContinue = { disclosure = false; openAccessSettings(context, Access.Accessibility) }, onDismiss = { disclosure = false })
}

/** Where you are, after the welcome: a lit dash for this step, points for the rest. Still. */
@Composable
private fun PageDots(page: Int, modifier: Modifier = Modifier) {
    val colors = Aurora.colors
    val spoken = stringResource(R.string.onb_step, page, PageCount - 1)
    Row(modifier.semantics { contentDescription = spoken }, horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        repeat(PageCount - 1) { i ->
            val here = i + 1 == page
            Box(Modifier.height(4.dp).width(if (here) 22.dp else 6.dp).clip(Radius.Pill).background(if (here) colors.accent else colors.hairline))
        }
    }
}

/** The promise under the first action: a lock and one quiet line. */
@Composable
private fun Privacy(modifier: Modifier = Modifier) {
    val colors = Aurora.colors
    Row(modifier.semantics(mergeDescendants = true) { }, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Icon(AuroraIcons.Lock, null, Modifier.size(13.dp), tint = colors.textTertiary)
        Text(stringResource(R.string.onb_welcome_privacy), color = colors.textTertiary, style = AuroraType.Caption)
    }
}

/** A centred page that fills the screen and scrolls when text is large. */
@Composable
private fun Page(content: @Composable ColumnScope.() -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).heightIn(min = maxHeight).padding(horizontal = Space.Gutter),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.weight(1f).heightIn(min = Space.M))
            content()
            Spacer(Modifier.weight(1.2f).heightIn(min = Space.M))
        }
    }
}

/** The welcome: the lit mark, the promise with its last word lit, and one plain line saying what Aurora does. */
@Composable
internal fun Welcome() {
    val colors = Aurora.colors
    Page {
        AuroraMark(84.dp, lit = true)
        Spacer(Modifier.height(40.dp))
        HeroHeadline(
            stringResource(R.string.today_headline),
            style = AuroraType.HeroBold.copy(fontSize = 52.sp, lineHeight = 54.sp, textAlign = TextAlign.Center),
        )
        Text(
            stringResource(R.string.today_description), Modifier.padding(top = Space.M).widthIn(max = 300.dp),
            color = colors.textSecondary, style = AuroraType.Body, textAlign = TextAlign.Center,
        )
    }
}

/** How Aurora works: its four tabs on one card, each a lit badge, its name and one line. */
@Composable
private fun HowItWorks() {
    val colors = Aurora.colors
    Page {
        Text(
            stringResource(R.string.onb_how_title), Modifier.semantics { heading() },
            color = colors.textPrimary, style = AuroraType.Display, textAlign = TextAlign.Center,
        )
        Text(
            stringResource(R.string.onb_how_body), Modifier.padding(top = Space.S).widthIn(max = 300.dp),
            color = colors.textSecondary, style = AuroraType.Body, textAlign = TextAlign.Center,
        )
        Column(Modifier.padding(top = 36.dp).fillMaxWidth().auroraSurface(colors).padding(vertical = Space.XS)) {
            Tab.entries.forEachIndexed { i, tab ->
                if (i > 0) Box(Modifier.padding(start = 82.dp).fillMaxWidth().height(1.dp).background(colors.hairline))
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp).semantics(mergeDescendants = true) { },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    LitBadge(tab.icon, size = 44)
                    Spacer(Modifier.width(Space.M))
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(tab.label), color = colors.textPrimary, style = AuroraType.Title)
                        Text(stringResource(tab.title), color = colors.textSecondary, style = AuroraType.Body)
                    }
                }
            }
        }
    }
}

/** One tab: a still picture of it, its name, one serif line and one plain line, centred. */
@Composable
private fun TabPage(tab: Tab) {
    val colors = Aurora.colors
    Page {
        // Decorative: the words below say what the picture shows.
        Box(Modifier.fillMaxWidth().clearAndSetSemantics { }) {
            when (tab) {
                Tab.Shield -> ShieldArt()
                Tab.Intent -> IntentArt()
                Tab.Home -> HomeArt()
                Tab.Track -> TrackArt()
            }
        }
        Spacer(Modifier.height(44.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(tab.icon, null, Modifier.size(15.dp), tint = colors.accent)
            Text(stringResource(tab.label), color = colors.accent, style = AuroraType.Label)
        }
        Text(
            stringResource(tab.title), Modifier.padding(top = Space.S).semantics { heading() },
            color = colors.textPrimary, style = AuroraType.Display, textAlign = TextAlign.Center,
        )
        Text(
            stringResource(tab.body), Modifier.padding(top = Space.S).widthIn(max = 300.dp),
            color = colors.textSecondary, style = AuroraType.Body, textAlign = TextAlign.Center,
        )
    }
}

private val TileShape = RoundedCornerShape(16.dp)

/** Shield: four apps, each locked, over the four ways it can hold them. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ShieldArt() {
    val colors = Aurora.colors
    Column(Modifier.fillMaxWidth().auroraSurface(colors).padding(22.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            listOf(AuroraIcons.Globe, AuroraIcons.Play, AuroraIcons.Quote, AuroraIcons.Spark).forEach { icon ->
                Box(Modifier.size(58.dp)) {
                    Box(
                        Modifier.fillMaxSize().clip(TileShape).background(colors.field).border(1.dp, colors.hairline, TileShape),
                        contentAlignment = Alignment.Center,
                    ) { Icon(icon, null, Modifier.size(24.dp), tint = colors.textTertiary) }
                    Box(
                        Modifier.align(Alignment.BottomEnd).offset(6.dp, 6.dp).size(26.dp).clip(Radius.Pill).sunlit(colors),
                        contentAlignment = Alignment.Center,
                    ) { Icon(AuroraIcons.Lock, null, Modifier.size(13.dp), tint = colors.onAccent) }
                }
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.XS), verticalArrangement = Arrangement.spacedBy(Space.XS)) {
            Mode.entries.forEach { ModeChip(it) }
        }
    }
}

/** Intent: the question, a pause about to run, and a time chosen. */
@Composable
private fun IntentArt() {
    val colors = Aurora.colors
    Column(Modifier.fillMaxWidth().auroraSurface(colors).padding(22.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Box(Modifier.size(44.dp).clip(RoundedCornerShape(13.dp)).background(colors.field).border(1.dp, colors.hairline, RoundedCornerShape(13.dp)), contentAlignment = Alignment.Center) {
            Icon(AuroraIcons.Spark, null, Modifier.size(20.dp), tint = colors.textSecondary)
        }
        Text(stringResource(R.string.intent_prompt_question), color = colors.textPrimary, style = AuroraType.DisplayS.copy(fontSize = 24.sp, lineHeight = 28.sp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.M)) {
            Box(Modifier.size(52.dp).border(3.dp, colors.accent, Radius.Pill), contentAlignment = Alignment.Center) {
                Text("5s", color = colors.textPrimary, style = AuroraType.Caption)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(Space.XS)) {
                listOf(5, 15, 30).forEach { m -> Chip(minutesText(m), m == 15) { } }
            }
        }
    }
}

/** Home: Start session (a picture, so not sunlit: the page has one filled action) over a saved session holding a Shield and an Intent. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HomeArt() {
    val colors = Aurora.colors
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 58.dp).glass(colors, Radius.Pill).padding(horizontal = Space.L),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center,
        ) {
            Icon(AuroraIcons.Play, null, Modifier.size(18.dp), tint = colors.accent)
            Spacer(Modifier.size(10.dp))
            Text(stringResource(R.string.home_start_title), color = colors.textPrimary, style = AuroraType.Label)
        }
        Column(Modifier.fillMaxWidth().auroraSurface(colors).padding(horizontal = 22.dp, vertical = 20.dp), verticalArrangement = Arrangement.spacedBy(Space.S)) {
            Text(stringResource(R.string.onb_tour_mock_session), color = colors.textPrimary, style = AuroraType.Title)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.XS), verticalArrangement = Arrangement.spacedBy(Space.XS)) {
                ModeChip(Mode.Flexible, label = stringResource(R.string.tab_shield))
                IntentChip(stringResource(R.string.tab_intent))
            }
        }
    }
}

/** Track: a habit, a line most of the way towards it, and the days kept lit. No figures: nothing is invented. */
@Composable
private fun TrackArt() {
    val colors = Aurora.colors
    val days = 10
    val kept = 7
    Column(Modifier.fillMaxWidth().auroraSurface(colors).padding(22.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.S)) {
            LitBadge(AuroraIcons.Target, size = 36)
            Text(stringResource(R.string.track_tab_goals), color = colors.textPrimary, style = AuroraType.Title)
        }
        Box(Modifier.fillMaxWidth().height(6.dp).clip(Radius.Pill).background(colors.hairline)) {
            Box(Modifier.fillMaxWidth(kept / days.toFloat()).height(6.dp).clip(Radius.Pill).background(colors.accent))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            repeat(days) { i -> Box(Modifier.size(14.dp).clip(Radius.Pill).background(if (i < kept) colors.accent else colors.hairline)) }
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
    Page {
        AuroraMark(64.dp, lit = true)
        Spacer(Modifier.height(36.dp))
        Text(
            stringResource(R.string.onb_shield_title), Modifier.semantics { heading() },
            color = colors.textPrimary, style = AuroraType.Display, textAlign = TextAlign.Center,
        )
        Text(
            stringResource(R.string.onb_shield_body), Modifier.padding(top = Space.S).widthIn(max = 300.dp),
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
