package app.aurora.ui.library

import app.aurora.ui.components.PageGlow
import app.aurora.ui.components.glass
import app.aurora.ui.components.pressable
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.aurora.R
import app.aurora.data.InstalledApp
import app.aurora.plan.ProtectedItem
import app.aurora.protect.WebPrefix
import app.aurora.ui.components.AppIcon
import app.aurora.ui.components.AuroraButton
import app.aurora.ui.components.AuroraHeader
import app.aurora.ui.components.AuroraIconButton
import app.aurora.ui.components.AuroraIcons
import app.aurora.ui.components.AuroraTextField
import app.aurora.ui.components.ButtonKind
import app.aurora.ui.components.SelectionMark
import app.aurora.ui.components.SkeletonRows
import app.aurora.ui.components.auroraSurface
import app.aurora.ui.protect.IconStack
import app.aurora.ui.protect.ProtectData
import app.aurora.ui.theme.Aurora
import app.aurora.ui.theme.AuroraType
import app.aurora.ui.theme.Radius
import app.aurora.ui.theme.Space
import androidx.compose.ui.graphics.graphicsLayer
import app.aurora.ui.components.ContentMaxWidth

/*
 * Pieces shared by the Shield, Intent, Home and Goal builders: a stepped flow, the complete app list,
 * the website list, and the preset card. Built only from the First Light components.
 */

/**
 * A short stepped flow: back, a segmented progress line, the step's content (a lazy list, so long app
 * lists stay smooth) and one primary action. Back on the first step leaves.
 */
@Composable
fun StepFlow(
    steps: Int,
    index: Int,
    onBack: () -> Unit,
    primary: String,
    primaryEnabled: Boolean,
    onPrimary: () -> Unit,
    footer: (@Composable ColumnScope.() -> Unit)? = null,
    content: LazyListScope.() -> Unit,
) {
    val colors = Aurora.colors
    BackHandler(onBack = onBack)
    Box(Modifier.fillMaxSize().background(colors.background), contentAlignment = Alignment.TopCenter) {
    PageGlow()
    Column(Modifier.widthIn(max = ContentMaxWidth).fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = Space.XS, vertical = Space.XS), verticalAlignment = Alignment.CenterVertically) {
            AuroraIconButton(AuroraIcons.Back, stringResource(R.string.action_back), onBack)
            // The horizon fills step by step; the light marks where the person is.
            Row(Modifier.weight(1f).padding(horizontal = Space.S).semantics(mergeDescendants = true) { contentDescription = "${index + 1}/$steps" }, horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                // One step has no progress to show.
                if (steps > 1) repeat(steps) { i ->
                    val filled by androidx.compose.animation.core.animateFloatAsState(if (i <= index) 1f else 0f, app.aurora.ui.theme.auroraTween(app.aurora.ui.theme.Motion.Gentle), label = "step")
                    Box(Modifier.weight(1f).height(8.dp), contentAlignment = Alignment.CenterStart) {
                        Box(Modifier.fillMaxWidth().height(4.dp).clip(Radius.Pill).background(colors.hairline))
                        Box(Modifier.fillMaxWidth(filled).height(4.dp).clip(Radius.Pill).background(colors.accent))
                        if (i == index) Box(Modifier.align(Alignment.CenterEnd).size(8.dp).graphicsLayer { alpha = filled; scaleX = filled; scaleY = filled }.clip(Radius.Pill).background(colors.light))
                    }
                }
            }
            Box(Modifier.size(Space.M))
        }
        // Each step starts at its top and settles in from the side it came from.
        val reduced = Aurora.reducedMotion
        var last by androidx.compose.runtime.remember { androidx.compose.runtime.mutableIntStateOf(index) }
        val from = if (index >= last) 1f else -1f
        androidx.compose.runtime.SideEffect { last = index }
        androidx.compose.runtime.key(index) {
            val side = androidx.compose.runtime.remember { from }
            val shown = androidx.compose.runtime.remember { androidx.compose.animation.core.Animatable(if (reduced) 1f else 0f) }
            androidx.compose.runtime.LaunchedEffect(Unit) { if (!reduced) shown.animateTo(1f, androidx.compose.animation.core.tween(app.aurora.ui.theme.Motion.Gentle, easing = app.aurora.ui.theme.Motion.Ease)) }
            LazyColumn(
                Modifier.weight(1f).fillMaxWidth().graphicsLayer { alpha = shown.value; translationX = (1f - shown.value) * 72.dp.toPx() * side },
                contentPadding = PaddingValues(horizontal = Space.Gutter, vertical = Space.M),
                verticalArrangement = Arrangement.spacedBy(Space.S),
                content = content,
            )
        }
        Column(Modifier.padding(Space.M), verticalArrangement = Arrangement.spacedBy(Space.XS)) {
            footer?.invoke(this)
            AuroraButton(primary, onPrimary, Modifier.fillMaxWidth(), enabled = primaryEnabled)
        }
    }
    app.aurora.ui.components.TopAurora()
    }
}

/** A step's heading and one supporting line. */
fun LazyListScope.stepTitle(@androidx.annotation.StringRes title: Int, @androidx.annotation.StringRes body: Int?, vararg args: Any) {
    item(key = "step-title") {
        Column(Modifier.padding(bottom = Space.S), verticalArrangement = Arrangement.spacedBy(Space.XS)) {
            Text(stringResource(title, *args), Modifier.semantics { heading() }, color = Aurora.colors.textPrimary, style = AuroraType.DisplayS)
            if (body != null) Text(stringResource(body), color = Aurora.colors.textSecondary, style = AuroraType.Body)
        }
    }
}

/**
 * Every app the person can open, alphabetically, with search; [selected] ticked. Nothing is cut off.
 * Recovery apps ([exempt]) aren't offered: they are never blocked.
 */
fun LazyListScope.appChecklist(
    apps: List<InstalledApp>?,
    exempt: Set<String>,
    selected: Set<String>,
    query: String,
    onQuery: (String) -> Unit,
    /** The plan's most, when it has one: said up front, not at the end of the flow. */
    cap: Int? = null,
    onToggle: (String) -> Unit,
) {
    item(key = "search") { AuroraTextField(query, onQuery, stringResource(R.string.picker_search), icon = AuroraIcons.Search) }
    if (apps == null) {
        item(key = "loading") { SkeletonRows(6) }
        return
    }
    val q = query.trim()
    val visible = apps.filter { it.packageName !in exempt || it.packageName in selected }.filter { q.isEmpty() || it.label.contains(q, ignoreCase = true) }
    item(key = "count") {
        Column {
        Text(
            if (q.isEmpty()) stringResource(R.string.picker_count, visible.size, selected.count { !it.startsWith(WebPrefix) }) else stringResource(R.string.picker_matches, visible.size),
            color = Aurora.colors.textSecondary, style = AuroraType.Caption,
        )
        if (cap != null) Text(androidx.compose.ui.res.pluralStringResource(R.plurals.picker_cap, cap, cap), color = Aurora.colors.textTertiary, style = AuroraType.Caption)
        }
    }
    items(visible, key = { "app-" + it.packageName }, contentType = { "app" }) { app ->
        val on = app.packageName in selected
        val colors = Aurora.colors
        val state = stringResource(if (on) R.string.picker_selected else R.string.picker_not_selected)
        Row(
            Modifier.fillMaxWidth().clip(Radius.M).then(if (on) Modifier.glass(colors, Radius.M, tint = colors.accent) else Modifier)
                .clickable(role = Role.Checkbox) { onToggle(app.packageName) }
                .semantics(mergeDescendants = true) { stateDescription = state }
                .padding(horizontal = Space.XS, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Space.M),
        ) {
            AppIcon(app, size = 42.dp)
            Text(app.label, Modifier.weight(1f), color = colors.textPrimary, style = AuroraType.BodyStrong, maxLines = 1, overflow = TextOverflow.Ellipsis)
            SelectionMark(on)
        }
    }
}

/** Websites: typed, normalised ("www.Reddit.com/" is reddit.com), never duplicated. */
fun LazyListScope.siteEditor(sites: List<String>, onChange: (List<String>) -> Unit) {
    item(key = "site-input") {
        var text by rememberSaveable { mutableStateOf("") }
        var invalid by rememberSaveable { mutableStateOf(false) }
        val add = {
            val site = ProtectedItem.Website.of(app.aurora.protect.hostOf(text) ?: text)
            if (site == null) invalid = true
            else {
                invalid = false
                text = ""
                if (site.domain !in sites) onChange(sites + site.domain)
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(Space.XS)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.XS)) {
                AuroraTextField(text, { text = it.take(253); invalid = false }, stringResource(R.string.shield_sites_placeholder), Modifier.weight(1f), icon = AuroraIcons.Globe, onDone = add)
                AuroraButton(stringResource(R.string.shield_sites_add), add, kind = ButtonKind.Secondary, compact = true, enabled = text.isNotBlank())
            }
            if (invalid) Text(stringResource(R.string.shield_sites_invalid), color = Aurora.colors.danger, style = AuroraType.Caption)
        }
    }
    items(sites, key = { "site-$it" }) { domain ->
        val colors = Aurora.colors
        Row(
            Modifier.fillMaxWidth().auroraSurface(colors, Radius.M).padding(horizontal = Space.M, vertical = Space.XS),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Space.M),
        ) {
            androidx.compose.material3.Icon(AuroraIcons.Globe, null, tint = colors.accent)
            Text(domain, Modifier.weight(1f), color = colors.textPrimary, style = AuroraType.BodyStrong)
            AuroraIconButton(AuroraIcons.Close, stringResource(R.string.action_remove_site, domain), { onChange(sites - domain) })
        }
    }
}

/** A tab's opening over First Light: the mark and one confident line. */
@Composable
fun TabHeader(title: String, onOpenSettings: (() -> Unit)?) {
    val colors = Aurora.colors
    Column {
        AuroraHeader(onOpenSettings)
        Spacer(Modifier.height(64.dp))
        Text(title, Modifier.padding(horizontal = Space.Gutter).padding(bottom = Space.L).semantics { heading() }, color = colors.textPrimary, style = AuroraType.Display)
    }
}

/**
 * One saved preset: its tint, name, what it is, its apps, and the library actions. Never a switch: a
 * preset is started from Home.
 */
@Composable
fun PresetCard(
    name: String,
    kind: @Composable () -> Unit,
    detail: String,
    keys: List<String>,
    data: ProtectData?,
    running: Boolean,
    onEdit: () -> Unit,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
    edge: androidx.compose.ui.graphics.Color? = null,
) {
    val colors = Aurora.colors
    Column(
        modifier.padding(horizontal = Space.M).fillMaxWidth().pressable(Radius.L, onClick = onEdit).auroraSurface(colors, edge = edge).padding(Space.L),
        verticalArrangement = Arrangement.spacedBy(Space.XS),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.S)) {
            kind()
            Box(Modifier.weight(1f))
            if (running) Text(
                stringResource(R.string.preset_in_use),
                Modifier.clip(Radius.Pill).background(colors.accentSoft).padding(horizontal = 10.dp, vertical = 4.dp),
                color = colors.accent, style = AuroraType.Caption,
            )
            // The card itself edits; the rarer actions wait behind one quiet control.
            Box {
                var open by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
                AuroraIconButton(AuroraIcons.More, stringResource(R.string.preset_more, name), { open = true }, Modifier.size(40.dp))
                androidx.compose.material3.DropdownMenu(
                    open, { open = false },
                    shape = Radius.M,
                    containerColor = colors.elevated,
                    border = androidx.compose.foundation.BorderStroke(1.dp, colors.hairline),
                ) {
                    MenuItem(stringResource(R.string.preset_edit), AuroraIcons.Edit) { open = false; onEdit() }
                    MenuItem(stringResource(R.string.preset_duplicate), AuroraIcons.Copy) { open = false; onDuplicate() }
                    MenuItem(stringResource(R.string.preset_delete), AuroraIcons.Trash, danger = true) { open = false; onDelete() }
                }
            }
        }
        Text(name, color = colors.textPrimary, style = AuroraType.Title)
        Text(detail, color = colors.textSecondary, style = AuroraType.Secondary)
        if (keys.isNotEmpty()) Box(Modifier.padding(start = 8.dp, top = 4.dp)) { IconStack(keys, data, max = 6) }
    }
}

@Composable
private fun MenuItem(text: String, icon: androidx.compose.ui.graphics.vector.ImageVector, danger: Boolean = false, onClick: () -> Unit) {
    val colors = Aurora.colors
    val tint = if (danger) colors.danger else colors.textPrimary
    androidx.compose.material3.DropdownMenuItem(
        text = { Text(text, color = tint, style = AuroraType.Body) },
        onClick = onClick,
        leadingIcon = { androidx.compose.material3.Icon(icon, null, tint = tint, modifier = Modifier.size(20.dp)) },
        modifier = Modifier.widthIn(min = 180.dp),
    )
}

/** A selectable row with the item's icon, for choosing presets and configurations. */
@Composable
fun PickRow(title: String, subtitle: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, leading: (@Composable () -> Unit)? = null, tag: (@Composable () -> Unit)? = null) {
    val colors = Aurora.colors
    Row(
        modifier.fillMaxWidth().glass(colors, Radius.L, tint = if (selected) colors.accent else null)
            .clickable(role = Role.RadioButton, onClick = onClick)
            .semantics(mergeDescendants = true) { this.selected = selected }
            .padding(Space.M),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.M),
    ) {
        leading?.invoke()
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            if (tag != null) Box(Modifier.padding(bottom = Space.XS)) { tag() }
            Text(title, color = colors.textPrimary, style = AuroraType.Title)
            if (subtitle.isNotEmpty()) Text(subtitle, color = colors.textSecondary, style = AuroraType.Secondary)
        }
        SelectionMark(selected)
    }
}
