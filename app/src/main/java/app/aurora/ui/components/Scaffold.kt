package app.aurora.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.aurora.ui.theme.Aurora
import app.aurora.ui.theme.AuroraType
import app.aurora.ui.theme.Radius
import app.aurora.ui.theme.Space
import app.aurora.ui.theme.auroraTween
import androidx.compose.foundation.layout.widthIn

private val TopBarHeight = 56.dp

/** Content never grows wider than a comfortable reading column; on tablets the art spans, the cards don't. */
val ContentMaxWidth = 640.dp

/** Dock plus its margins. Text scaling adds label height to both the dock and content clearance. */
val TabBarHeight
    @Composable @ReadOnlyComposable get() = 96.dp + with(LocalDensity.current) {
        (AuroraType.Caption.lineHeight.toDp() - 18.dp).coerceAtLeast(0.dp)
    }

/**
 * Standard aurora screen: a large title that scrolls away into a compact top bar.
 * Set [tabBar] on tab destinations so content clears the floating [AuroraNavBar].
 */
@Composable
fun AuroraScreen(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    tabBar: Boolean = false,
    listState: LazyListState = rememberLazyListState(),
    actions: @Composable RowScope.() -> Unit = {},
    /** Replaces the standard large title, for screens that open with their own composition. */
    header: (@Composable () -> Unit)? = null,
    /** First Light behind the top of the screen. Null for configuration screens. */
    art: ArtTier? = null,
    /** The aurora over [art]: lit while protection runs ([Sky]). */
    sky: Sky = Sky.Quiet,
    /** Time passing along the art's ring, 0 to 1 ([DawnRing]). */
    arc: Float? = null,
    content: LazyListScope.() -> Unit,
) {
    val colors = Aurora.colors
    val threshold = with(LocalDensity.current) { 48.dp.toPx() }
    val collapsed by remember {
        derivedStateOf { listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > threshold }
    }
    // A tab screen over the sky opens with its own header (the mark), so no bar height is reserved.
    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + if (art != null && onBack == null) 0.dp else TopBarHeight
    val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() +
        (if (tabBar) TabBarHeight else 0.dp) + Space.XXL

    // Hero screens over the sky open like Home: each block settles in from the top of the page down.
    // Plain screens do the same, quicker: they have no art to wait for.
    val glass = rememberGlassSource()
    ProvideIntro(if (art != null) 900 else QuickIntroMs) {
    Box(modifier.fillMaxSize().background(colors.background)) {
        Box(Modifier.fillMaxSize().glassSource(glass)) {
        if (art != null) {
            val tall = with(LocalDensity.current) { art.height.roundToPx() * 2 }
            AuroraBackdrop(art, sky = sky, arc = arc, scroll = { if (listState.firstVisibleItemIndex == 0) listState.firstVisibleItemScrollOffset else tall })
        }
        PageGlow()
        LazyColumn(
            state = listState,
            modifier = Modifier.align(Alignment.TopCenter).widthIn(max = ContentMaxWidth).fillMaxSize(),
            contentPadding = PaddingValues(top = top, bottom = bottom),
        ) {
            val list = RisingScope(this)
            if (header != null) list.item(key = "title", contentType = "header") { header() }
            else list.item(key = "title", contentType = "title") {
                Column(Modifier.padding(horizontal = Space.Gutter).padding(top = Space.XS, bottom = Space.S)) {
                    Text(title, Modifier.semantics { heading() }, color = colors.textPrimary, style = AuroraType.Display)
                    if (subtitle != null) {
                        Spacer(Modifier.height(Space.XS))
                        Text(subtitle, color = colors.textSecondary, style = AuroraType.Body)
                    }
                }
            }
            list.content()
        }
        }
        TopBar(title, collapsed, onBack, actions, overArt = art != null, glass = glass)
        if (art == null) TopAurora(Modifier.align(Alignment.TopCenter))
    }
    }
}

/** Every item of a hero screen's list, marked to [rise] on the screen's first show. */
private class RisingScope(private val base: LazyListScope) : LazyListScope {
    override fun item(key: Any?, contentType: Any?, content: @Composable LazyItemScope.() -> Unit) =
        base.item(key, contentType) { val scope = this; Column(Modifier.rise()) { scope.content() } }

    override fun items(count: Int, key: ((index: Int) -> Any)?, contentType: (index: Int) -> Any?, itemContent: @Composable LazyItemScope.(index: Int) -> Unit) =
        base.items(count, key, contentType) { i -> val scope = this; Column(Modifier.rise()) { scope.itemContent(i) } }
}

@Composable
private fun TopBar(title: String, collapsed: Boolean, onBack: (() -> Unit)?, actions: @Composable RowScope.() -> Unit, overArt: Boolean = false, glass: GlassSource? = null) {
    val colors = Aurora.colors
    val titleAlpha by animateFloatAsState(if (collapsed) 1f else 0f, auroraTween(), label = "title")
    // Over the sky the bar is clear until the title collapses into it; then the page frosts behind it.
    val shown = if (overArt) titleAlpha else 1f
    Column(Modifier.fillMaxWidth().frosted(glass, androidx.compose.ui.graphics.RectangleShape) { shown }.background(colors.background.copy(alpha = shown * if (glass != null) 0.60f else 1f)).statusBarsPadding()) {
        Row(
            Modifier.fillMaxWidth().height(TopBarHeight).padding(horizontal = Space.XS),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (onBack != null) AuroraIconButton(AuroraIcons.Back, androidx.compose.ui.res.stringResource(app.aurora.R.string.action_back), onBack) else Spacer(Modifier.size(48.dp))
            Text(
                title,
                Modifier.weight(1f).graphicsLayer { alpha = titleAlpha },
                color = colors.textPrimary,
                style = AuroraType.BodyStrong,
                textAlign = TextAlign.Center,
            )
            Row(Modifier.size(width = 48.dp, height = 48.dp), horizontalArrangement = Arrangement.End, content = actions)
        }
        Box(Modifier.fillMaxWidth().height(1.dp).graphicsLayer { alpha = titleAlpha }.background(colors.hairline))
    }
}

data class NavItem(val route: String, @androidx.annotation.StringRes val label: Int, val icon: ImageVector)

/**
 * A floating dock, inset from the page. A ribbon of aurora light marks the chosen tab: its leading edge
 * races ahead and its tail follows on a softer spring, so it stretches across the dock like a curtain of
 * light and gathers as it lands. The chosen icon pops in, with a light tick.
 */
@Composable
fun AuroraNavBar(items: List<NavItem>, current: String?, glass: GlassSource? = null, onSelect: (String) -> Unit) {
    val colors = Aurora.colors
    val reduced = Aurora.reducedMotion
    val haptics = androidx.compose.ui.platform.LocalHapticFeedback.current
    val dockHeight = TabBarHeight - Space.L
    val index = items.indexOfFirst { it.route == current }
    val target = index.coerceAtLeast(0).toFloat()
    val lead by animateFloatAsState(target, if (reduced) androidx.compose.animation.core.snap() else androidx.compose.animation.core.spring(dampingRatio = 0.68f, stiffness = 900f), label = "lead")
    val tail by animateFloatAsState(target, if (reduced) androidx.compose.animation.core.snap() else androidx.compose.animation.core.spring(dampingRatio = 0.82f, stiffness = 220f), label = "tail")
    val shown by animateFloatAsState(if (index >= 0) 1f else 0f, auroraTween(), label = "shown")
    val label = AuroraType.Caption.lineHeight
    Box(
        Modifier.fillMaxWidth()
            // The page dims beneath the dock, so the gesture area stays calm, but still shows through the glass.
            .background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(androidx.compose.ui.graphics.Color.Transparent, colors.background.copy(alpha = 0.55f), colors.background.copy(alpha = 0.80f))))
            .navigationBarsPadding().padding(start = Space.M, end = Space.M, bottom = Space.S, top = Space.L),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            Modifier
                .widthIn(max = ContentMaxWidth)
                .fillMaxWidth()
                .shadow(12.dp, Radius.Dialog, ambientColor = colors.sky, spotColor = colors.sky)
                .then(if (glass != null) Modifier.frosted(glass, Radius.Dialog) else Modifier.background(colors.surface, Radius.Dialog))
                .auroraSurface(colors, Radius.Dialog)
                .height(dockHeight)
                .drawBehind {
                    // Same geometry as each tab's column: icon pill, 4dp, label, 3dp, point.
                    val content = 30.dp.toPx() + 4.dp.toPx() + label.toPx() + 6.dp.toPx()
                    val top = (size.height - content) / 2f
                    val slot = size.width / items.size
                    val pill = androidx.compose.ui.geometry.Size(52.dp.toPx(), 30.dp.toPx())
                    val left = slot * (minOf(lead, tail) + 0.5f) - pill.width / 2f
                    val right = slot * (maxOf(lead, tail) + 0.5f) + pill.width / 2f
                    // The stretch thins the ribbon a little, as light does when it travels.
                    val stretch = ((right - left - pill.width) / slot).coerceIn(0f, 1f)
                    val h = pill.height * (1f - 0.18f * stretch)
                    val y = top + (pill.height - h) / 2f
                    val ribbon = androidx.compose.ui.graphics.Brush.horizontalGradient(
                        listOf(colors.accent.copy(alpha = 0.30f * shown), colors.glowCool.copy(alpha = 0.18f * shown), colors.glowTop.copy(alpha = 0.16f * shown)),
                        startX = left, endX = right,
                    )
                    // A soft bloom behind the ribbon, then the ribbon and its lit upper rim.
                    val bloom = 6.dp.toPx()
                    drawRoundRect(colors.accent.copy(alpha = 0.07f * shown), androidx.compose.ui.geometry.Offset(left - bloom, y - bloom), androidx.compose.ui.geometry.Size(right - left + 2 * bloom, h + 2 * bloom), androidx.compose.ui.geometry.CornerRadius(h / 2f + bloom))
                    drawRoundRect(ribbon, androidx.compose.ui.geometry.Offset(left, y), androidx.compose.ui.geometry.Size(right - left, h), androidx.compose.ui.geometry.CornerRadius(h / 2f))
                    drawRoundRect(colors.accent.copy(alpha = 0.35f * shown), androidx.compose.ui.geometry.Offset(left, y), androidx.compose.ui.geometry.Size(right - left, h), androidx.compose.ui.geometry.CornerRadius(h / 2f), style = androidx.compose.ui.graphics.drawscope.Stroke(1.dp.toPx()))
                }
                .selectableGroup(),
        ) {
            items.forEach { item ->
                val selected = item.route == current
                val tint by animateColorAsState(if (selected) colors.accent else colors.textTertiary, auroraTween(), label = "tint")
                val lift = androidx.compose.runtime.remember { androidx.compose.animation.core.Animatable(1f) }
                androidx.compose.runtime.LaunchedEffect(selected) {
                    if (selected && !reduced) {
                        lift.snapTo(0.86f)
                        lift.animateTo(1f, androidx.compose.animation.core.spring(dampingRatio = 0.7f, stiffness = 500f))
                    }
                }
                Column(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .selectable(selected, role = Role.Tab, onClick = {
                            if (!selected) haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.SegmentTick)
                            onSelect(item.route)
                        }),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Box(Modifier.size(width = 52.dp, height = 30.dp), contentAlignment = Alignment.Center) {
                        Icon(item.icon, null, tint = tint, modifier = Modifier.size(22.dp).graphicsLayer { scaleX = lift.value; scaleY = lift.value })
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(androidx.compose.ui.res.stringResource(item.label), color = if (selected) colors.textPrimary else colors.textTertiary, style = AuroraType.Caption)
                    Spacer(Modifier.height(6.dp))
                }
            }
        }
    }
}
