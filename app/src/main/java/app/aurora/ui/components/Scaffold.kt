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
import androidx.compose.ui.semantics.text
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

private val TopBarHeight = 60.dp

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
    /** First Light behind the top of the screen. Null for configuration screens. */
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
    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + if (onBack == null && header == null) 0.dp else if (art != null && onBack == null) 0.dp else TopBarHeight
    val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() +
        (if (tabBar) TabBarHeight else 0.dp) + Space.XXL

    // Hero screens over the sky open like Home: each block settles in from the top of the page down.
    // Plain screens do the same, quicker: they have no art to wait for.
    ProvideIntro(if (art != null) 900 else QuickIntroMs) {
    Box(modifier.fillMaxSize().background(colors.background)) {
        Box(Modifier.fillMaxSize()) {
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
                Column(Modifier.padding(horizontal = Space.Gutter).padding(bottom = Space.S)) {
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
        TopBar(title, collapsed, onBack, actions, overArt = art != null || (onBack == null && header == null), pinned = pinned)
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
private fun TopBar(title: String, collapsed: Boolean, onBack: (() -> Unit)?, actions: @Composable RowScope.() -> Unit, overArt: Boolean = false, pinned: Boolean = false) {
    val colors = Aurora.colors
    val titleAlpha by animateFloatAsState(if (collapsed || pinned) 1f else 0f, auroraTween(), label = "title")
    val lineAlpha by animateFloatAsState(if (collapsed) 1f else 0f, auroraTween(), label = "line")
    // On a tab screen the bar is clear until the title collapses into it.
    val shown = if (overArt) titleAlpha else 1f
    Column(Modifier.fillMaxWidth().background(colors.background.copy(alpha = shown)).statusBarsPadding()) {
        Row(
            Modifier.fillMaxWidth().height(TopBarHeight).padding(start = Space.XS + 2.dp, end = Space.Gutter - 3.dp),
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
        Box(Modifier.fillMaxWidth().height(1.dp).graphicsLayer { alpha = lineAlpha }.background(colors.hairline))
    }
}

data class NavItem(val route: String, @androidx.annotation.StringRes val label: Int, val icon: ImageVector)

/**
 * The dock: a calm bar of icons along the bottom. The chosen tab sits in a soft round well; each icon carries its
 * tab's name as its text, for screen readers and tests. Choosing a tab gives a light tick.
 */
@Composable
fun AuroraNavBar(items: List<NavItem>, current: String?, onSelect: (String) -> Unit) {
    val colors = Aurora.colors
    val haptics = androidx.compose.ui.platform.LocalHapticFeedback.current
    val navInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    Box(
        Modifier.fillMaxWidth()
            // Content scrolling under the dock fades into the page instead of showing below it.
            .background(androidx.compose.ui.graphics.Brush.verticalGradient(0f to androidx.compose.ui.graphics.Color.Transparent, 0.35f to colors.background.copy(alpha = 0.92f), 1f to colors.background))
            .padding(start = Space.Gutter, end = Space.Gutter, top = Space.M, bottom = maxOf(26.dp, navInset + 10.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            Modifier
                .widthIn(max = ContentMaxWidth)
                .fillMaxWidth()
                .height(64.dp)
                .glass(colors, Radius.Pill)
                .padding(horizontal = Space.XS)
                .selectableGroup(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            items.forEach { item ->
                val selected = item.route == current
                val tint by animateColorAsState(if (selected) colors.textPrimary else colors.textTertiary, auroraTween(), label = "tint")
                val dot by androidx.compose.animation.core.animateFloatAsState(if (selected) 1f else 0f, auroraTween(), label = "dot")
                val label = androidx.compose.ui.res.stringResource(item.label)
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .selectable(selected, role = Role.Tab, onClick = {
                            if (!selected) haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.SegmentTick)
                            onSelect(item.route)
                        })
                        .semantics { text = androidx.compose.ui.text.AnnotatedString(label) },
                    contentAlignment = Alignment.Center,
                ) {
                    // The chosen tab is marked by a point of first light under its icon.
                    Icon(item.icon, null, tint = tint, modifier = Modifier.size(22.dp))
                    Box(
                        Modifier.align(Alignment.BottomCenter).padding(bottom = 9.dp).size(4.dp)
                            .graphicsLayer { alpha = dot; scaleX = dot; scaleY = dot }
                            .clip(androidx.compose.foundation.shape.CircleShape).background(if (colors.isDark) colors.glowTop else colors.light),
                    )
                }
            }
        }
    }
}
