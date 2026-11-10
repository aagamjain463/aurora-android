package app.aurora.ui.onboarding

import app.aurora.ui.components.glassShine
import app.aurora.ui.components.glassFill
import app.aurora.ui.components.glassCard
import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.clearAndSetSemantics
import app.aurora.protect.Mode
import app.aurora.ui.components.ChoicePills
import androidx.compose.foundation.layout.aspectRatio
import app.aurora.ui.protect.modeName
import app.aurora.ui.components.minutesText
import app.aurora.ui.components.glass
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
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
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
import app.aurora.rememberAccess
import app.aurora.ui.components.AuroraButton
import app.aurora.ui.components.AuroraIcons
import app.aurora.ui.components.AuroraMark
import app.aurora.ui.components.ButtonKind
import app.aurora.ui.components.ContentMaxWidth
import app.aurora.ui.components.HeroHeadline
import app.aurora.ui.components.LitBadge
import app.aurora.ui.components.Wordmark
import app.aurora.ui.components.auroraSurface
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
    val colors = Aurora.colors
    val prefs by app.preferences.preferences.collectAsStateWithLifecycle(initialValue = null)
    var page by rememberSaveable { mutableIntStateOf(-1) }
    LaunchedEffect(prefs != null) { if (page < 0 && prefs != null) page = prefs!!.onboardingStep.coerceIn(0, PageCount - 1) }
    LaunchedEffect(page) { if (page >= 0) runCatching { app.preferences.setOnboardingStep(page) } }
    BackHandler(enabled = page > 0) { page-- }
    val p = page.coerceAtLeast(0)
    val granted = Needed.associateWith { rememberAccess(it) == AccessState.Granted }
    val shieldOn = granted[Access.Accessibility] == true
    val missing = Needed.filter { granted[it] != true }
    val finish = {
        app.scope.launch { runCatching { app.preferences.setOnboardingStep(0) } }
        onFinish()
    }

    Box(Modifier.fillMaxSize().background(if (p == 0) colors.sky else colors.background)) {
        Column(Modifier.align(Alignment.TopCenter).widthIn(max = ContentMaxWidth).fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            // No name at the top: the pages speak for themselves. Only the way past the tour, on the right.
            Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = Space.Gutter), verticalAlignment = Alignment.CenterVertically) {
                Spacer(Modifier.weight(1f))
                if (p in 1 until ShieldPage) AuroraButton(stringResource(R.string.onb_skip_tour), { page = ShieldPage }, kind = ButtonKind.Quiet, compact = true)
            }
            Crossfade(p, Modifier.weight(1f), animationSpec = tween(Motion.Standard), label = "page") { step ->
                when (step) {
                    0 -> Welcome()
                    HowPage -> HowItWorks()
                    ShieldPage -> Permissions(missing)
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
                        BackNext({ page = p - 1 }, stringResource(R.string.action_next), { page = p + 1 })
                    }
                    // Each permission still off has its own Allow above; any of them can be skipped for now.
                    missing.isNotEmpty() -> {
                        PageDots(p, Modifier.padding(bottom = Space.M))
                        BackNext({ page = p - 1 }, stringResource(if (shieldOn) R.string.onb_finish else R.string.onb_skip), finish, quiet = true)
                    }
                    else -> {
                        PageDots(p, Modifier.padding(bottom = Space.M))
                        BackNext({ page = p - 1 }, stringResource(R.string.onb_finish), finish)
                    }
                }
            }
        }
    }
}

/** The way back and the way on, side by side and equal. [quiet] when the page's filled action sits above them. */
@Composable
private fun BackNext(onBack: () -> Unit, label: String, onNext: () -> Unit, quiet: Boolean = false) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.S)) {
        AuroraButton(stringResource(R.string.action_back), onBack, Modifier.weight(1f), kind = if (quiet) ButtonKind.Quiet else ButtonKind.Secondary)
        AuroraButton(label, onNext, Modifier.weight(1f), kind = if (quiet) ButtonKind.Quiet else ButtonKind.Primary)
    }
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
        // The mark held in the seal: the bezel draws itself round once, like a watch being set.
        app.aurora.ui.components.Seal(196.dp) { AuroraMark(88.dp) }
        Spacer(Modifier.height(36.dp))
        HeroHeadline(
            stringResource(R.string.today_headline),
            style = AuroraType.BrandLine.copy(fontSize = 52.sp, lineHeight = 54.sp, textAlign = TextAlign.Center),
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

/** One tab: a still picture of it, its name, one headline and one plain line, centred. */
@Composable
private fun TabPage(tab: Tab) {
    val colors = Aurora.colors
    Page {
        // Decorative previews keep their proportions; page text follows system text size.
        CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale = 1f)) {
            Box(Modifier.fillMaxWidth().clearAndSetSemantics { }) {
                when (tab) {
                    Tab.Shield -> ShieldArt()
                    Tab.Intent -> IntentArt()
                    Tab.Home -> HomeArt()
                    Tab.Track -> TrackArt()
                }
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
            // Shield's line is one line, whatever the width.
            maxLines = if (tab == Tab.Shield) 1 else Int.MAX_VALUE,
            autoSize = if (tab == Tab.Shield) androidx.compose.foundation.text.TextAutoSize.StepBased(minFontSize = 18.sp, maxFontSize = AuroraType.Display.fontSize) else null,
        )
        Text(
            stringResource(tab.body), Modifier.padding(top = Space.S).widthIn(max = 300.dp),
            color = colors.textSecondary, style = AuroraType.Body, textAlign = TextAlign.Center,
        )
    }
}

private val TileShape = RoundedCornerShape(16.dp)

/** Shield: four apps, each held, over one track of the four ways it can hold them, none favoured. */
@Composable
private fun ShieldArt() {
    val colors = Aurora.colors
    val modeLabels = Mode.entries.associateWith { modeName(it) }
    Column(Modifier.fillMaxWidth().auroraSurface(colors).padding(22.dp), verticalArrangement = Arrangement.spacedBy(22.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            // What a Shield holds: apps, websites, Reels and Shorts.
            listOf(AuroraIcons.Apps, AuroraIcons.Globe, AuroraIcons.Reels, AuroraIcons.Shorts).forEach { icon ->
                Box(Modifier.weight(1f).aspectRatio(1f).glassCard(colors, TileShape, fill = colors.glassFill(field = true))) {
                    Icon(icon, null, Modifier.align(Alignment.Center).size(24.dp), tint = colors.textTertiary)
                    Box(
                        Modifier.align(Alignment.BottomEnd).padding(6.dp).size(20.dp).clip(Radius.Pill).background(colors.accent).glassShine(colors, Radius.Pill),
                        contentAlignment = Alignment.Center,
                    ) { Icon(AuroraIcons.Lock, null, Modifier.size(11.dp), tint = colors.onAccent) }
                }
            }
        }
        // All four on one line, equal widths. Nothing is chosen: no way of holding is the right one.
        ChoicePills<Mode?>(Mode.entries, null, onSelect = { }, label = { modeLabels.getValue(it!!) }, fill = true, segmented = true)
    }
}

/** Intent: the question with its pause beside it, then three times of one size, one chosen. */
@Composable
private fun IntentArt() {
    val colors = Aurora.colors
    Column(Modifier.fillMaxWidth().auroraSurface(colors).padding(22.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Box(Modifier.size(44.dp).glassCard(colors, RoundedCornerShape(13.dp), fill = colors.glassFill(field = true)), contentAlignment = Alignment.Center) {
                Icon(AuroraIcons.Spark, null, Modifier.size(20.dp), tint = colors.textSecondary)
            }
            Box(Modifier.size(44.dp).border(2.5.dp, colors.accent, Radius.Pill), contentAlignment = Alignment.Center) {
                Text("5s", color = colors.textPrimary, style = AuroraType.Caption)
            }
        }
        Text(stringResource(R.string.intent_prompt_question), color = colors.textPrimary, style = AuroraType.DisplayS.copy(fontSize = 24.sp, lineHeight = 28.sp))
        ChoicePills(listOf(5, 15, 30), 15, onSelect = { }, label = { minutesText(it) }, fill = true)
    }
}

/** Home: Start session (a picture, so not filled: the page has one filled action) over a saved session, as Home lists it. */
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
        Text(stringResource(R.string.home_ready_title), Modifier.padding(start = 4.dp, top = Space.XS), color = colors.textSecondary, style = AuroraType.Label)
        Column(Modifier.fillMaxWidth().auroraSurface(colors).padding(horizontal = Space.Inner, vertical = 20.dp), verticalArrangement = Arrangement.spacedBy(Space.S)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.S)) {
                Text(stringResource(R.string.onb_tour_mock_session), Modifier.weight(1f), color = colors.textPrimary, style = AuroraType.Title)
                Box(Modifier.size(28.dp).glassCard(colors, RoundedCornerShape(8.dp), fill = colors.glassFill(field = true)), contentAlignment = Alignment.Center) {
                    Icon(AuroraIcons.Apps, null, Modifier.size(15.dp), tint = colors.textTertiary)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.S)) {
                FlowRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(Space.XS), verticalArrangement = Arrangement.spacedBy(Space.XS)) {
                    ModeChip(Mode.Flexible, label = stringResource(R.string.tab_shield))
                    IntentChip(stringResource(R.string.tab_intent))
                }
                AuroraButton(stringResource(R.string.cfg_start), { }, kind = ButtonKind.Tonal, icon = AuroraIcons.Play, compact = true)
            }
        }
    }
}

/** Track: screen time, progress and habits side by side, each as a still picture. No figures: nothing is invented. */
@Composable
private fun TrackArt() {
    val colors = Aurora.colors
    Row(Modifier.fillMaxWidth().auroraSurface(colors).padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        TrackTile(stringResource(R.string.onb_tour_mock_time)) {
            // A week of screen time, easing down.
            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.Bottom) {
                listOf(0.85f, 0.7f, 0.78f, 0.55f, 0.5f, 0.38f, 0.3f).forEachIndexed { i, h ->
                    Box(Modifier.weight(1f).fillMaxHeight(h).clip(Radius.Pill).background(if (i == 6) colors.accent else colors.textTertiary.copy(alpha = 0.45f)))
                }
            }
        }
        TrackTile(stringResource(R.string.onb_tour_mock_progress)) {
            // Most of the way round.
            val track = colors.textTertiary.copy(alpha = 0.3f)
            val lit = colors.accent
            androidx.compose.foundation.Canvas(Modifier.fillMaxSize().padding(4.dp)) {
                val stroke = androidx.compose.ui.graphics.drawscope.Stroke(5.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round)
                val side = minOf(size.width, size.height) - stroke.width
                val at = androidx.compose.ui.geometry.Offset((size.width - side) / 2f, (size.height - side) / 2f)
                val box = androidx.compose.ui.geometry.Size(side, side)
                drawArc(track, 0f, 360f, false, at, box, style = stroke)
                drawArc(lit, -90f, 260f, false, at, box, style = stroke)
            }
        }
        TrackTile(stringResource(R.string.track_tab_goals)) {
            // The days kept, lit.
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceEvenly) {
                repeat(3) { row ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        repeat(3) { col ->
                            val kept = row * 3 + col < 7
                            Box(Modifier.size(10.dp).clip(Radius.Pill).background(if (kept) colors.accent else colors.textTertiary.copy(alpha = 0.3f)))
                        }
                    }
                }
            }
        }
    }
}

/** One of Track's three pictures: a square well with its name under it. */
@Composable
private fun androidx.compose.foundation.layout.RowScope.TrackTile(label: String, art: @Composable () -> Unit) {
    val colors = Aurora.colors
    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.fillMaxWidth().aspectRatio(1f).glassCard(colors, TileShape, fill = colors.glassFill(field = true)).padding(14.dp)) { art() }
        Text(label, color = colors.textSecondary, style = AuroraType.Caption, maxLines = 1)
    }
}

/** What the last page asks for, in order: Shield first, since nothing works without it. */
private val Needed = listOf(Access.Accessibility, Access.Content, Access.Usage, Access.Notifications)

/** The last page: only the permissions still off, in the one permission card, each with its own Allow. */
@Composable
private fun Permissions(missing: List<Access>) {
    val colors = Aurora.colors
    Page {
        Text(
            stringResource(R.string.onb_access_title), Modifier.semantics { heading() },
            color = colors.textPrimary, style = AuroraType.Display, textAlign = TextAlign.Center,
        )
        Text(
            stringResource(if (missing.isEmpty()) R.string.onb_access_done else R.string.onb_access_body), Modifier.padding(top = Space.S).widthIn(max = 300.dp),
            color = colors.textSecondary, style = AuroraType.Body, textAlign = TextAlign.Center,
        )
        if (missing.isNotEmpty()) app.aurora.ui.protect.PermissionCard(missing, Modifier.padding(top = 36.dp))
    }
}
