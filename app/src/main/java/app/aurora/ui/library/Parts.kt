package app.aurora.ui.library

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

/*
 * Pieces shared by the Shield, Intent, Home and Habit builders: a stepped flow, the complete app list,
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
    Column(Modifier.fillMaxSize().background(colors.background).statusBarsPadding().navigationBarsPadding().imePadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = Space.XS, vertical = Space.XS), verticalAlignment = Alignment.CenterVertically) {
            AuroraIconButton(AuroraIcons.Back, stringResource(R.string.action_back), onBack)
            Row(Modifier.weight(1f).padding(horizontal = Space.S), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                repeat(steps) { i -> Box(Modifier.weight(1f).height(4.dp).clip(Radius.Pill).background(if (i <= index) colors.accent else colors.hairline)) }
            }
            Box(Modifier.padding(end = Space.M)) { Text("${index + 1}/$steps", color = colors.textTertiary, style = AuroraType.Caption) }
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(horizontal = Space.Gutter, vertical = Space.M), verticalArrangement = Arrangement.spacedBy(Space.S), content = content)
        Column(Modifier.padding(Space.M), verticalArrangement = Arrangement.spacedBy(Space.XS)) {
            footer?.invoke(this)
            AuroraButton(primary, onPrimary, Modifier.fillMaxWidth(), enabled = primaryEnabled)
        }
    }
}

/** A step's heading and one supporting line. */
fun LazyListScope.stepTitle(@androidx.annotation.StringRes title: Int, @androidx.annotation.StringRes body: Int?, vararg args: Any) {
    item(key = "step-title") {
        Column(Modifier.padding(bottom = Space.S), verticalArrangement = Arrangement.spacedBy(Space.XS)) {
            Text(stringResource(title, *args), Modifier.semantics { heading() }, color = Aurora.colors.textPrimary, style = AuroraType.Headline)
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
        Text(
            if (q.isEmpty()) stringResource(R.string.picker_count, visible.size, selected.count { !it.startsWith(WebPrefix) }) else stringResource(R.string.picker_matches, visible.size),
            color = Aurora.colors.textSecondary, style = AuroraType.Caption,
        )
    }
    items(visible, key = { "app-" + it.packageName }, contentType = { "app" }) { app ->
        val on = app.packageName in selected
        val colors = Aurora.colors
        val state = stringResource(if (on) R.string.picker_selected else R.string.picker_not_selected)
        Row(
            Modifier.fillMaxWidth().clip(Radius.M).background(if (on) colors.elevated else colors.background)
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

/** A tab's opening over First Light: the mark, an eyebrow, a confident line, one supporting sentence. */
@Composable
fun TabHeader(eyebrow: String, title: String, body: String, onOpenSettings: (() -> Unit)?) {
    val colors = Aurora.colors
    Column {
        AuroraHeader(onOpenSettings)
        Spacer(Modifier.height(88.dp))
        Column(Modifier.padding(horizontal = Space.Gutter).padding(bottom = Space.M), verticalArrangement = Arrangement.spacedBy(Space.XS)) {
            Text(eyebrow, color = colors.sun, style = AuroraType.Eyebrow)
            Text(title, Modifier.semantics { heading() }, color = colors.textPrimary, style = AuroraType.Display)
            Text(body, color = colors.textSecondary, style = AuroraType.Body)
        }
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
        modifier.padding(horizontal = Space.M).fillMaxWidth().auroraSurface(colors, edge = edge).clickable(role = Role.Button, onClick = onEdit).padding(Space.L),
        verticalArrangement = Arrangement.spacedBy(Space.S),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.S)) {
            kind()
            Box(Modifier.weight(1f))
            Text(
                stringResource(if (running) R.string.preset_in_use else R.string.preset_saved),
                Modifier.clip(Radius.Pill).background(if (running) colors.accentSoft else colors.elevated).padding(horizontal = 10.dp, vertical = 4.dp),
                color = if (running) colors.accent else colors.textSecondary, style = AuroraType.Caption,
            )
        }
        Text(name, color = colors.textPrimary, style = AuroraType.Headline)
        Text(detail, color = colors.textSecondary, style = AuroraType.Secondary)
        if (keys.isNotEmpty()) Box(Modifier.padding(start = 8.dp, top = 4.dp)) { IconStack(keys, data, max = 6) }
        Row(Modifier.fillMaxWidth().padding(top = Space.XS), horizontalArrangement = Arrangement.spacedBy(Space.XS)) {
            AuroraButton(stringResource(R.string.preset_edit), onEdit, Modifier.weight(1f), kind = ButtonKind.Secondary, compact = true)
            AuroraButton(stringResource(R.string.preset_duplicate), onDuplicate, Modifier.weight(1f), kind = ButtonKind.Secondary, compact = true)
            AuroraButton(stringResource(R.string.preset_delete), onDelete, Modifier.weight(1f), kind = ButtonKind.Quiet, compact = true)
        }
    }
}

/** A selectable row with the item's icon, for choosing presets and configurations. */
@Composable
fun PickRow(title: String, subtitle: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, leading: (@Composable () -> Unit)? = null) {
    val colors = Aurora.colors
    Row(
        modifier.fillMaxWidth().clip(Radius.L).background(if (selected) colors.elevated else colors.surface)
            .border(if (selected) 2.dp else 1.dp, if (selected) colors.accent else colors.hairline, Radius.L)
            .clickable(role = Role.RadioButton, onClick = onClick)
            .semantics(mergeDescendants = true) { this.selected = selected }
            .padding(Space.M),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.M),
    ) {
        leading?.invoke()
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, color = colors.textPrimary, style = AuroraType.Title)
            Text(subtitle, color = colors.textSecondary, style = AuroraType.Secondary)
        }
        SelectionMark(selected)
    }
}
