package app.aurora.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
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
val TabBarHeight = 65.dp

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
    content: LazyListScope.() -> Unit,
) {
    val colors = Aurora.colors
    val threshold = with(LocalDensity.current) { 48.dp.toPx() }
    val collapsed by remember {
        derivedStateOf { listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > threshold }
    }
    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + TopBarHeight
    val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() +
        (if (tabBar) TabBarHeight else 0.dp) + Space.XXL

    Box(modifier.fillMaxSize().background(colors.background)) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = top, bottom = bottom),
        ) {
            item(key = "title", contentType = "title") {
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
        TopBar(title, collapsed, onBack, actions)
    }
}

@Composable
private fun TopBar(title: String, collapsed: Boolean, onBack: (() -> Unit)?, actions: @Composable RowScope.() -> Unit) {
    val colors = Aurora.colors
    val titleAlpha by animateFloatAsState(if (collapsed) 1f else 0f, auroraTween(), label = "title")
    Column(Modifier.fillMaxWidth().background(colors.background).statusBarsPadding()) {
        Row(
            Modifier.fillMaxWidth().height(TopBarHeight).padding(horizontal = Space.XS),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (onBack != null) AuroraIconButton(AuroraIcons.Back, "Back", onBack) else Spacer(Modifier.size(48.dp))
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

data class NavItem(val route: String, val label: String, val icon: ImageVector)

/** Bottom tab bar. The selected tab is marked by the rising point: a small dot of aurora light. */
@Composable
fun AuroraNavBar(items: List<NavItem>, current: String?, onSelect: (String) -> Unit) {
    val colors = Aurora.colors
    Column(Modifier.fillMaxWidth().background(colors.background)) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(colors.hairline))
        Row(
            Modifier
                .navigationBarsPadding()
                .fillMaxWidth()
                .height(64.dp)
                .selectableGroup(),
        ) {
            items.forEach { item ->
                val selected = item.route == current
                val tint by animateColorAsState(if (selected) colors.textPrimary else colors.textTertiary, auroraTween(), label = "tint")
                val dot by animateFloatAsState(if (selected) 1f else 0f, auroraTween(), label = "dot")
                Column(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .selectable(selected, role = Role.Tab, onClick = { onSelect(item.route) }),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Icon(item.icon, null, tint = tint, modifier = Modifier.size(24.dp))
                    Spacer(Modifier.height(2.dp))
                    Text(item.label, color = tint, style = AuroraType.Caption)
                    Spacer(Modifier.height(4.dp))
                    Box(
                        Modifier
                            .size(4.dp)
                            .graphicsLayer { alpha = dot; scaleX = 0.5f + dot / 2; scaleY = 0.5f + dot / 2 }
                            .clip(Radius.Pill)
                            .background(colors.accent),
                    )
                }
            }
        }
    }
}
