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
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.sp
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
import androidx.compose.ui.semantics.text
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.aurora.ui.theme.Aurora
import app.aurora.ui.theme.AuroraType
import app.aurora.ui.theme.Radius
import app.aurora.ui.theme.Space
import app.aurora.ui.theme.auroraTween
import androidx.compose.foundation.layout.widthIn

private val TopBarHeight = 60.dp
/** The bar's breathing room under the status bar; content below the bar starts past it, never under it. */
private val TopBarInset = Space.XS

/** Content never grows wider than a comfortable reading column; on tablets the art spans, the cards don't. */
val ContentMaxWidth = 640.dp

/** Dock plus its margins. The dock holds icons only, so text scaling leaves it as it is. */
val TabBarHeight
    @Composable @ReadOnlyComposable get() = 92.dp

/**
 * Standard aurora screen. A screen you go back from carries its title centred in the top bar beside the back arrow,
 * its [subtitle] centred beneath; a root screen opens with a large title that scrolls away into the bar.
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
    /** How the screen opens (its header tier). Null for configuration screens. */
    art: ArtTier? = null,
    content: LazyListScope.() -> Unit,
) {
    val colors = Aurora.colors
    val threshold = with(LocalDensity.current) { 48.dp.toPx() }
    val pinned = onBack != null && header == null
    val collapsed by remember {
        derivedStateOf { listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > threshold }
    }
    // A tab screen over the sky opens with its own header (the mark), so no bar height is reserved.
    // A root screen's large title sits in the bar's own row, beside the bar's actions.
    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + if (onBack == null && header == null) 0.dp else if (art != null && onBack == null) 0.dp else TopBarHeight + TopBarInset + Space.S
    val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() +
        (if (tabBar) TabBarHeight else 0.dp) + Space.XXL

    // Hero screens over the sky open like Home: each block settles in from the top of the page down.
    // Plain screens do the same, quicker: they have no art to wait for.
    // The page the top bar's glass blurs once content scrolls under it.
    val backdrop = rememberBackdrop()
    if (tabBar) {
        val reduced = Aurora.reducedMotion
        // Kept tabs all listen; only the one in view (resumed) answers.
        val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
        androidx.compose.runtime.LaunchedEffect(listState, reduced, lifecycle) {
            TabReselect.events.collect {
                if (!lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) return@collect
                if (reduced) listState.scrollToItem(0) else listState.animateScrollToItem(0)
            }
        }
    }
    ProvideIntro(if (art != null) 560 else QuickIntroMs) {
    Box(modifier.fillMaxSize().background(colors.background)) {
        Box(Modifier.fillMaxSize().backdropSource(backdrop)) {
        LazyColumn(
            state = listState,
            modifier = Modifier.align(Alignment.TopCenter).widthIn(max = ContentMaxWidth).fillMaxSize(),
            contentPadding = PaddingValues(top = top, bottom = bottom),
        ) {
            val list = RisingScope(this)
            if (header != null) list.item(key = "title", contentType = "header") { header() }
            else if (pinned) {
                if (subtitle != null) list.item(key = "title", contentType = "title") {
                    Text(
                        subtitle, Modifier.fillMaxWidth().padding(horizontal = Space.XL).padding(top = Space.XS, bottom = Space.S),
                        color = colors.textSecondary, style = AuroraType.Secondary, textAlign = TextAlign.Center,
                    )
                }
            }
            else list.item(key = "title", contentType = "title") {
                Column(Modifier.padding(horizontal = Space.Gutter).padding(top = TopBarInset, bottom = Space.S)) {
                    Box(Modifier.height(TopBarHeight), contentAlignment = Alignment.CenterStart) {
                        Text(title, Modifier.semantics { heading() }.padding(end = 56.dp), color = colors.textPrimary, style = AuroraType.DisplayXL)
                    }
                    if (subtitle != null) {
                        Spacer(Modifier.height(Space.XS))
                        Text(subtitle, color = colors.textSecondary, style = AuroraType.Body)
                    }
                }
            }
            list.content()
        }
        }
        TopBar(title, collapsed, onBack, actions, overArt = art != null || (onBack == null && header == null), pinned = pinned, backdrop = backdrop)
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
private fun TopBar(title: String, collapsed: Boolean, onBack: (() -> Unit)?, actions: @Composable RowScope.() -> Unit, overArt: Boolean = false, pinned: Boolean = false, backdrop: Backdrop? = null) {
    val colors = Aurora.colors
    val titleAlpha by animateFloatAsState(if (collapsed || pinned) 1f else 0f, auroraTween(), label = "title")
    val glassAlpha by animateFloatAsState(if (collapsed) 1f else 0f, auroraTween(), label = "glass")
    // Glass / Subtle once content scrolls beneath; until then a root screen's bar is clear and a pushed screen's is
    // the page itself, so nothing floats over nothing.
    val pageAlpha = if (overArt) 0f else 1f - glassAlpha
    Box(Modifier.fillMaxWidth()) {
    Box(Modifier.matchParentSize().background(colors.background.copy(alpha = pageAlpha)))
    Box(Modifier.matchParentSize().graphicsLayer { alpha = glassAlpha }.material(Material.Subtle, androidx.compose.ui.graphics.RectangleShape, backdrop))
    Column(Modifier.fillMaxWidth().statusBarsPadding().padding(top = TopBarInset)) {
        Row(
            Modifier.fillMaxWidth().height(TopBarHeight).padding(start = Space.M, end = Space.Gutter - 3.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (onBack != null) AuroraIconButton(AuroraIcons.Back, androidx.compose.ui.res.stringResource(app.aurora.R.string.action_back), onBack) else Spacer(Modifier.size(48.dp))
            Text(
                title,
                Modifier.weight(1f).graphicsLayer { alpha = titleAlpha }.then(if (pinned) Modifier.semantics { heading() } else Modifier),
                color = colors.textPrimary,
                style = AuroraType.Bar,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
            Row(Modifier.size(width = 48.dp, height = 48.dp), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically, content = actions)
        }
        Spacer(Modifier.height(1.dp))
    }
    }
}

/** A tab chosen again while it is showing. Its screen scrolls back to the top. */
object TabReselect {
    val events = kotlinx.coroutines.flow.MutableSharedFlow<String>(extraBufferCapacity = 1)
}

data class NavItem(val route: String, @androidx.annotation.StringRes val label: Int, val icon: ImageVector)

/**
 * The dock: Aurora's one floating navigation surface, in Glass / Standard. Pages scroll beneath it and show through
 * blurred and colourless; each tab's icon sits over its name. The chosen tab rests in a quiet capsule in full ink,
 * the others recede. Choosing a tab gives a light tick; pressing one settles it slightly.
 */
@Composable
fun AuroraNavBar(items: List<NavItem>, current: String?, backdrop: Backdrop? = null, onSelect: (String) -> Unit) {
    val colors = Aurora.colors
    val haptics = androidx.compose.ui.platform.LocalHapticFeedback.current
    val navInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val fontScale = androidx.compose.ui.platform.LocalDensity.current.fontScale
    val dockScale = minOf(fontScale, 1.3f) / fontScale
    Box(
        Modifier.fillMaxWidth()
            .padding(start = Space.Gutter, end = Space.Gutter, top = Space.M, bottom = maxOf(26.dp, navInset + 10.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            Modifier
                .widthIn(max = ContentMaxWidth)
                .fillMaxWidth()
                .height(64.dp)
                .material(Material.Standard, Radius.Pill, backdrop, float = true)
                .padding(horizontal = 6.dp)
                .selectableGroup(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            items.forEach { item ->
                val selected = item.route == current
                // Unchosen tabs use secondary ink, not tertiary: over glass the backdrop can be bright, and names must stay legible.
                val tint by animateColorAsState(if (selected) colors.textPrimary else colors.textSecondary, auroraTween(), label = "tint")
                // The chosen tab's capsule is ink at a whisper over the glass: a step of depth, never a glow.
                val well = animateColorAsState(colors.textPrimary.copy(alpha = if (selected) (if (colors.isDark) 0.11f else 0.07f) else 0f), auroraTween(), label = "well")
                val label = androidx.compose.ui.res.stringResource(item.label)
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .padding(vertical = 6.dp)
                        .pressable(Radius.Pill, role = Role.Tab) {
                            haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.SegmentTick)
                            // Choosing the tab you are on takes its page back to the top.
                            if (selected) TabReselect.events.tryEmit(item.route) else onSelect(item.route)
                        }
                        .drawBehind { drawRect(well.value) }
                        .semantics { this.selected = selected; text = androidx.compose.ui.text.AnnotatedString(label) },
                    contentAlignment = Alignment.Center,
                ) {
                    // Icon over its name; the chosen tab is in full ink and medium weight, the rest recede.
                    androidx.compose.foundation.layout.Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(item.icon, null, tint = tint, modifier = Modifier.size(22.dp))
                        Text(
                            label, Modifier.padding(top = 3.dp).clearAndSetSemantics { }, color = tint, maxLines = 1,
                            // Tab names grow with text size up to 1.3x, like the platform's navigation bar, so the bar keeps its shape.
                            style = AuroraType.Caption.copy(fontSize = (12f * dockScale).sp, lineHeight = (15f * dockScale).sp, fontWeight = androidx.compose.ui.text.font.FontWeight(if (selected) 600 else 500)),
                        )
                    }
                }
            }
        }
    }
}
