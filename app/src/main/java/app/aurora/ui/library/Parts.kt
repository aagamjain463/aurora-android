package app.aurora.ui.library

import androidx.compose.ui.semantics.text
import androidx.compose.ui.semantics.clearAndSetSemantics
import app.aurora.ui.components.AuroraSwitch
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.lazy.itemsIndexed
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
    /** Said above a disabled button, so it is never silently grey. */
    whyDisabled: String? = null,
    footer: (@Composable ColumnScope.() -> Unit)? = null,
    content: LazyListScope.() -> Unit,
) {
    val colors = Aurora.colors
    BackHandler(onBack = onBack)
    Box(Modifier.fillMaxSize().background(colors.background), contentAlignment = Alignment.TopCenter) {
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
        // Same gutter as the content above, so the button and its note line up with the page.
        Column(Modifier.padding(horizontal = Space.Gutter, vertical = Space.M), verticalArrangement = Arrangement.spacedBy(Space.XS)) {
            footer?.invoke(this)
            if (!primaryEnabled && whyDisabled != null) Text(whyDisabled, color = colors.textSecondary, style = AuroraType.Secondary)
            AuroraButton(primary, onPrimary, Modifier.fillMaxWidth(), enabled = primaryEnabled)
        }
    }
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
        val count = if (q.isEmpty()) stringResource(R.string.picker_count, visible.size, selected.count { !it.startsWith(WebPrefix) }) else stringResource(R.string.picker_matches, visible.size)
        Text(
            count.uppercase(),
            Modifier.padding(start = 4.dp, top = Space.S, bottom = 2.dp).clearAndSetSemantics { this.text = androidx.compose.ui.text.AnnotatedString(count) },
            color = Aurora.colors.textTertiary, style = AuroraType.Eyebrow,
        )
        if (cap != null) Text(androidx.compose.ui.res.pluralStringResource(R.plurals.picker_cap, cap, cap), color = Aurora.colors.textTertiary, style = AuroraType.Caption)
        }
    }
    // One card of rows, each with its switch: the first and last rows carry the card's corners.
    itemsIndexed(visible, key = { _, it -> "app-" + it.packageName }, contentType = { _, _ -> "app" }) { i, app ->
        val on = app.packageName in selected
        val colors = Aurora.colors
        val state = stringResource(if (on) R.string.picker_selected else R.string.picker_not_selected)
        val r = 26.dp
        val shape = androidx.compose.foundation.shape.RoundedCornerShape(
            topStart = if (i == 0) r else 0.dp, topEnd = if (i == 0) r else 0.dp,
            bottomStart = if (i == visible.lastIndex) r else 0.dp, bottomEnd = if (i == visible.lastIndex) r else 0.dp,
        )
        Column(Modifier.fillMaxWidth().clip(shape).background(colors.surface)) {
            if (i > 0) androidx.compose.foundation.layout.Box(Modifier.fillMaxWidth().height(1.dp).background(colors.hairline))
            Row(
                Modifier.fillMaxWidth().heightIn(min = 62.dp)
                    .toggleable(on, role = Role.Switch) { onToggle(app.packageName) }
                    .semantics(mergeDescendants = true) { stateDescription = state }
                    .padding(horizontal = 18.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                AppIcon(app)
                Text(app.label, Modifier.weight(1f), color = colors.textPrimary, style = AuroraType.BodyStrong, maxLines = 1, overflow = TextOverflow.Ellipsis)
                AuroraSwitch(on)
            }
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
fun TabHeader(title: String, onOpenSettings: (() -> Unit)?, topic: app.aurora.ui.guide.GuideTopic? = null, onOpenGuide: (() -> Unit)? = null) {
    val colors = Aurora.colors
    var info by rememberSaveable { mutableStateOf(false) }
    if (info && topic != null) app.aurora.ui.guide.GuideDialog(topic, { info = false }, onOpenGuide)
    Column {
        AuroraHeader(onOpenSettings, onInfo = topic?.let { { info = true } })
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
    onView: () -> Unit,
    onEdit: () -> Unit,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
    edge: androidx.compose.ui.graphics.Color? = null,
) {
    val colors = Aurora.colors
    Column(
        modifier.padding(horizontal = Space.Gutter).fillMaxWidth().pressable(Radius.L, onClick = onEdit).auroraSurface(colors, edge = edge).padding(horizontal = 22.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(Space.XS),
    ) {
        // Laid out like every Aurora tile: name and its control on top, then what it is, then its mode and apps.
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.S)) {
            Text(name, Modifier.weight(1f), color = colors.textPrimary, style = AuroraType.Title)
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
                    ItemMenuItems({ open = false }, onView, onEdit, onDuplicate, onDelete)
                }
            }
        }
        Text(detail, color = colors.textSecondary, style = AuroraType.Secondary)
        Row(Modifier.padding(top = Space.XS), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.XS)) {
            kind()
            if (running) Text(
                stringResource(R.string.preset_in_use),
                Modifier.clip(Radius.Pill).background(colors.accentSoft).padding(horizontal = 10.dp, vertical = 4.dp),
                color = colors.accent, style = AuroraType.Caption,
            )
            Box(Modifier.weight(1f))
            if (keys.isNotEmpty()) IconStack(keys, data, max = 4, size = 28.dp)
        }
    }
}

/** The same four actions behind every saved item's ⋯: view, edit, duplicate, delete. */
@Composable
fun ItemMenuItems(close: () -> Unit, onView: () -> Unit, onEdit: () -> Unit, onDuplicate: () -> Unit, onDelete: () -> Unit) {
    MenuItem(stringResource(R.string.preset_view), AuroraIcons.Info) { close(); onView() }
    MenuItem(stringResource(R.string.preset_edit), AuroraIcons.Edit) { close(); onEdit() }
    MenuItem(stringResource(R.string.preset_duplicate), AuroraIcons.Copy) { close(); onDuplicate() }
    MenuItem(stringResource(R.string.preset_delete), AuroraIcons.Trash, danger = true) { close(); onDelete() }
}

/** A saved item's or a finished session's details: label over value, one per line. [onEdit] adds Edit. */
@Composable
fun DetailsDialog(title: String, rows: List<Pair<String, String>>, onDismiss: () -> Unit, onEdit: (() -> Unit)? = null) {
    val colors = Aurora.colors
    app.aurora.ui.components.AuroraDialog(
        title, onDismiss,
        stringResource(if (onEdit != null) R.string.preset_edit else R.string.action_close),
        { onDismiss(); onEdit?.invoke() },
        dismissLabel = if (onEdit != null) stringResource(R.string.action_close) else null,
    ) {
        // One quiet panel of label-over-value lines, split by hairlines.
        Column(Modifier.fillMaxWidth().clip(Radius.M).background(colors.field)) {
            rows.filter { it.second.isNotBlank() }.forEachIndexed { i, (label, value) ->
                if (i > 0) app.aurora.ui.components.RowDivider()
                Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 14.dp).semantics(mergeDescendants = true) { }) {
                    Text(label.uppercase(), color = colors.textTertiary, style = AuroraType.Eyebrow)
                    Text(value, Modifier.padding(top = 4.dp), color = colors.textPrimary, style = AuroraType.Body)
                }
            }
        }
    }
}

/** Apps, websites and parts of apps in [keys], each named, for [DetailsDialog]. */
@Composable
fun keyRows(keys: Collection<String>, data: ProtectData): List<Pair<String, String>> {
    val sites = keys.filter { it.startsWith(WebPrefix) }
    val parts = keys.filter { it.startsWith(app.aurora.protect.PartPrefix) }
    val apps = keys - sites.toSet() - parts.toSet()
    fun names(list: Collection<String>) = list.map { data.label(it) }.filter { it.isNotBlank() }.joinToString(", ")
    return listOf(
        stringResource(R.string.detail_apps) to names(apps).ifEmpty { stringResource(R.string.detail_none) },
        stringResource(R.string.detail_sites) to names(sites),
        stringResource(R.string.detail_parts) to names(parts),
    )
}

@Composable
fun MenuItem(text: String, icon: androidx.compose.ui.graphics.vector.ImageVector, danger: Boolean = false, onClick: () -> Unit) {
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
