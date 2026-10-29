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
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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

private val TopBarHeight = 56.dp

/** Height of [AuroraNavBar] above the system navigation bar. Tab screens pad their content by it. */
val TabBarHeight = 73.dp

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
    content: LazyListScope.() -> Unit,
) {
    val colors = Aurora.colors
    val threshold = with(LocalDensity.current) { 48.dp.toPx() }
    val collapsed by remember {
        derivedStateOf { listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > threshold }
    }
    // A tab screen over First Light opens with its own header (the mark), so no bar height is reserved.
    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + if (art != null && onBack == null) 0.dp else TopBarHeight
    val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() +
        (if (tabBar) TabBarHeight else 0.dp) + Space.XXL

    Box(modifier.fillMaxSize().background(colors.background)) {
        if (art != null) {
            val tall = with(LocalDensity.current) { art.height.roundToPx() * 2 }
            FirstLightBackdrop(art, scroll = { if (listState.firstVisibleItemIndex == 0) listState.firstVisibleItemScrollOffset else tall })
        }
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = top, bottom = bottom),
        ) {
            if (header != null) item(key = "title", contentType = "header") { header() }
            else item(key = "title", contentType = "title") {
                Column(Modifier.padding(horizontal = Space.Gutter).padding(top = Space.XS, bottom = Space.S)) {
                    Text(title, Modifier.semantics { heading() }, color = colors.textPrimary, style = AuroraType.Display)
                    if (subtitle != null) {
                        Spacer(Modifier.height(Space.XS))
                        Text(subtitle, color = colors.textSecondary, style = AuroraType.Body)
                    }
                }
            }
            content()
        }
        TopBar(title, collapsed, onBack, actions, overArt = art != null)
    }
}

@Composable
private fun TopBar(title: String, collapsed: Boolean, onBack: (() -> Unit)?, actions: @Composable RowScope.() -> Unit, overArt: Boolean = false) {
    val colors = Aurora.colors
    val titleAlpha by animateFloatAsState(if (collapsed) 1f else 0f, auroraTween(), label = "title")
    // Over First Light the bar is clear until the title collapses into it.
    Column(Modifier.fillMaxWidth().background(colors.background.copy(alpha = if (overArt) titleAlpha else 1f)).statusBarsPadding()) {
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
 * Bottom tab bar: a lifted panel with soft top corners. The selected tab sits in a pill of aurora light,
 * its label lit, with the rising point (a small dot) beneath it.
 */
@Composable
fun AuroraNavBar(items: List<NavItem>, current: String?, onSelect: (String) -> Unit) {
    val colors = Aurora.colors
    val shape = androidx.compose.foundation.shape.RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
    Column(
        Modifier.fillMaxWidth().clip(shape)
            .background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(colors.elevated, colors.background)))
            .border(1.dp, androidx.compose.ui.graphics.Brush.verticalGradient(listOf(colors.hairline, androidx.compose.ui.graphics.Color.Transparent)), shape),
    ) {
        Row(
            Modifier
                .navigationBarsPadding()
                .fillMaxWidth()
                .height(72.dp)
                .selectableGroup(),
        ) {
            items.forEach { item ->
                val selected = item.route == current
                val tint by animateColorAsState(if (selected) colors.accent else colors.textTertiary, auroraTween(), label = "tint")
                val dot by animateFloatAsState(if (selected) 1f else 0f, auroraTween(), label = "dot")
                Column(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .selectable(selected, role = Role.Tab, onClick = { onSelect(item.route) }),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Box(
                        Modifier
                            .size(width = 64.dp, height = 32.dp)
                            .clip(Radius.Pill)
                            .background(colors.accent.copy(alpha = 0.14f * dot)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(item.icon, null, tint = tint, modifier = Modifier.size(24.dp))
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(androidx.compose.ui.res.stringResource(item.label), color = if (selected) colors.textPrimary else colors.textTertiary, style = AuroraType.Caption)
                    Spacer(Modifier.height(3.dp))
                    Box(Modifier.size(4.dp).graphicsLayer { alpha = dot }.clip(Radius.Pill).background(colors.accent))
                }
            }
        }
    }
}
