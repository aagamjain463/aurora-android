package app.aurora.ui.protect

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.aurora.R
import app.aurora.auroraViewModel
import app.aurora.data.Access
import app.aurora.data.InstalledApp
import app.aurora.data.openAccessSettings
import app.aurora.plan.Allowance
import app.aurora.plan.Plan
import app.aurora.plan.ProtectedItem
import app.aurora.protect.Mode
import app.aurora.protect.WebPrefix
import app.aurora.protect.covers
import app.aurora.protect.key
import app.aurora.protect.windowAt
import app.aurora.skipWindow
import app.aurora.turnOff
import app.aurora.ui.components.AppIcon
import app.aurora.ui.components.AuroraButton
import app.aurora.ui.components.AuroraDialog
import app.aurora.ui.components.AuroraIcons
import app.aurora.ui.components.AuroraRow
import app.aurora.ui.components.AuroraScreen
import app.aurora.ui.components.AuroraTextField
import app.aurora.ui.components.ButtonKind
import app.aurora.ui.components.Chevron
import app.aurora.ui.components.IconBadge
import app.aurora.ui.components.ModeGlyph
import app.aurora.ui.components.RowDivider
import app.aurora.ui.components.RowGroup
import app.aurora.ui.components.ScreenIntro
import app.aurora.ui.components.SectionLabel
import app.aurora.ui.components.SelectionMark
import app.aurora.ui.components.SkeletonRows
import app.aurora.ui.components.allowanceText
import app.aurora.ui.components.clockTime
import app.aurora.ui.theme.Aurora
import app.aurora.ui.theme.AuroraType
import app.aurora.ui.theme.Radius
import app.aurora.ui.theme.Space
import java.time.ZoneId

object ProtectRoutes {
    const val Apps = "protect/apps"
    const val Item = "protect/item"
    const val Start = "protect/start"
    const val Schedules = "protect/schedules"
    const val ScheduleEdit = "protect/schedule"
    const val Limits = "protect/limits"
    const val Groups = "protect/groups"
    const val Intent = "protect/intent"
    const val IntentApp = "protect/intent/app"
    const val Web = "protect/web"
    const val Plus = "plus"
}

/**
 * Protect: what's protected right now, how to start protection, and the saved apps, Intentional App
 * Opening, schedules and limits behind it. Running sessions come first, with the one action each mode
 * allows; saved apps are preferences and never protect anything until a session or schedule runs.
 */
@Composable
fun ProtectScreen(onOpen: (String) -> Unit, onOpenItem: (String) -> Unit) {
    val vm = auroraViewModel { ProtectModel(it) }
    val data by vm.data.collectAsStateWithLifecycle()
    val notice by vm.notice.collectAsStateWithLifecycle()
    val status = rememberProtectionStatus(data?.list)
    val context = LocalContext.current
    var disclosure by rememberSaveable { mutableStateOf(false) }
    var confirmOff by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { vm.pruneUninstalled() }
    val d = data

    AuroraScreen(
        title = stringResource(R.string.tab_protect),
        tabBar = true,
        listState = rememberLazyListState(),
        header = {
            ScreenIntro(
                stringResource(R.string.protect_headline),
                Modifier.padding(top = Space.XS, bottom = Space.L),
                eyebrow = stringResource(R.string.tab_protect),
            )
        },
    ) {
        item(key = "status") {
            if (status != null && d != null) {
                ShieldStatusCard(status, d.list.items.size, onTurnOn = { disclosure = true }, onOpenSettings = { openAccessSettings(context, Access.Accessibility) })
            }
        }
        if (d == null) {
            item { SkeletonRows(4) }
            return@AuroraScreen
        }

        // Running now.
        val windows = runningWindows(d)
        if (d.sessions.isNotEmpty() || windows.isNotEmpty()) {
            item(key = "now") { SectionLabel(stringResource(R.string.protect_active_now)) }
            items(d.sessions, key = { "s-" + it.id }) { s ->
                SessionCard(s, d, onTurnOff = { confirmOff = s.id }, Modifier.padding(bottom = Space.S))
            }
            items(windows, key = { "w-" + it.id }) { w -> WindowCard(w, d) { vm.run { vm.app.skipWindow(w.id) } } }
        }

        item(key = "start") {
            Column(Modifier.padding(horizontal = Space.M, vertical = Space.L)) {
                AuroraButton(
                    stringResource(R.string.protect_start),
                    { onOpen(ProtectRoutes.Start) },
                    Modifier.fillMaxWidth(),
                    icon = AuroraIcons.Protect,
                    enabled = d.active.isNotEmpty(),
                )
                if (d.active.isEmpty()) {
                    Text(stringResource(R.string.protect_start_needs_apps), Modifier.padding(top = Space.XS, start = Space.XS), color = Aurora.colors.textSecondary, style = AuroraType.Secondary)
                }
            }
        }

        item(key = "intent") { IntentFeatureCard(d) { onOpen(ProtectRoutes.Intent) } }

        item(key = "items-label") {
            SectionLabel(stringResource(R.string.protected_apps_name) + " · " + allowanceText(d.active.size, d.max(Allowance.ProtectedItems)))
        }
        item(key = "items") {
            RowGroup {
                d.list.items.forEachIndexed { i, item ->
                    if (i > 0) RowDivider(72.dp)
                    val key = item.key
                    val paused = item in d.paused
                    AuroraRow(
                        title = d.label(key),
                        subtitle = if (paused) stringResource(R.string.protect_paused) else d.rules.summary(key, d.plan, key.startsWith(WebPrefix)) { modeNameText(context, it) },
                        leading = { ItemIcon(key, d) },
                        onClick = { onOpenItem(key) },
                        trailing = { Chevron() },
                    )
                }
                if (d.list.items.isNotEmpty()) RowDivider(72.dp)
                AuroraRow(stringResource(R.string.protect_add_apps), leading = { IconBadge(AuroraIcons.Plus) }, onClick = { onOpen(ProtectRoutes.Apps) })
                RowDivider(72.dp)
                AuroraRow(stringResource(R.string.protect_add_website), leading = { IconBadge(AuroraIcons.Globe) }, onClick = { onOpen(ProtectRoutes.Web) })
            }
        }

        item(key = "more") { SectionLabel(stringResource(R.string.protect_more)) }
        item(key = "rules") {
            RowGroup {
                val schedules = d.rules.schedules.count { it.enabled }
                AuroraRow(
                    stringResource(R.string.schedules_name),
                    subtitle = if (schedules == 0) stringResource(R.string.protect_schedules_sub) else pluralStringResource(R.plurals.protect_schedules_on, schedules, schedules),
                    icon = AuroraIcons.Calendar, onClick = { onOpen(ProtectRoutes.Schedules) }, trailing = { Chevron() },
                )
                RowDivider(56.dp)
                AuroraRow(stringResource(R.string.limits_name), subtitle = stringResource(R.string.protect_limits_sub), icon = AuroraIcons.Hourglass, onClick = { onOpen(ProtectRoutes.Limits) }, trailing = { Chevron() })
                RowDivider(56.dp)
                AuroraRow(stringResource(R.string.groups_name), subtitle = stringResource(R.string.protect_groups_sub), icon = AuroraIcons.Layers, onClick = { onOpen(ProtectRoutes.Groups) }, trailing = { Chevron() })
            }
        }
        if (d.plan == Plan.Free) {
            item(key = "plus") {
                Column(Modifier.padding(top = Space.L)) {
                    RowGroup { AuroraRow(stringResource(R.string.plus_name), subtitle = stringResource(R.string.protect_plus_sub), icon = AuroraIcons.Spark, onClick = { onOpen(ProtectRoutes.Plus) }, trailing = { Chevron() }) }
                }
            }
        }
    }

    if (disclosure) ShieldDisclosure(onContinue = { disclosure = false; openAccessSettings(context, Access.Accessibility) }, onDismiss = { disclosure = false })
    confirmOff?.let { id ->
        AuroraDialog(
            title = stringResource(R.string.session_turn_off_title),
            body = stringResource(R.string.session_turn_off_body),
            confirmLabel = stringResource(R.string.session_turn_off),
            onConfirm = { confirmOff = null; vm.run { vm.app.turnOff(id) } },
            dismissLabel = stringResource(R.string.action_keep_on),
            onDismiss = { confirmOff = null },
        )
    }
    notice?.let { n ->
        AuroraDialog(
            title = n.title, body = n.body,
            confirmLabel = if (n.plus) stringResource(R.string.plus_see) else stringResource(R.string.action_ok),
            onConfirm = { vm.dismissNotice(); if (n.plus) onOpen(ProtectRoutes.Plus) },
            dismissLabel = if (n.plus) stringResource(R.string.action_not_now) else null,
            onDismiss = vm::dismissNotice,
        )
    }
}

/** A schedule window running now, outside any session. */
data class RunningWindow(val id: String, val name: String, val mode: Mode, val keys: Set<String>, val end: Long, val committed: Boolean, val canSkip: Boolean)

fun runningWindows(d: ProtectData): List<RunningWindow> {
    val zone = ZoneId.systemDefault()
    val now = d.time.wall
    return d.rules.activeSchedules(d.plan).mapNotNull { s ->
        val (start, end) = s.windowAt(now, zone) ?: return@mapNotNull null
        if (d.runtime.skipped[s.id] == start && s.mode != Mode.AlwaysBlock) return@mapNotNull null
        // A committed Always Block window shows as its session instead.
        if (d.sessions.any { it.scheduleId == s.id }) return@mapNotNull null
        val committed = s.mode == Mode.AlwaysBlock && d.plan == Plan.Plus
        RunningWindow(s.id, s.name, s.mode, d.rules.covers(s, d.activeKeys), end, committed, canSkip = !committed && d.runtime.skipped[s.id] != start)
    }
}

@Composable
private fun WindowCard(w: RunningWindow, d: ProtectData, onSkip: () -> Unit) {
    val colors = Aurora.colors
    val tint = modeColor(w.mode)
    Column(
        Modifier.padding(horizontal = Space.M).padding(bottom = Space.S).fillMaxWidth().clip(Radius.L).background(colors.surface)
            .border(1.dp, tint.copy(alpha = 0.45f), Radius.L).padding(Space.L),
        verticalArrangement = Arrangement.spacedBy(Space.XS),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.S)) {
            ModeGlyph(tint, size = 14)
            Text("${w.name} · ${modeName(w.mode)}", color = tint, style = AuroraType.Eyebrow)
            Box(Modifier.weight(1f))
            IconStack(w.keys.toList(), d)
        }
        Text(stringResource(R.string.schedule_running_until, clockTime(w.end)), color = colors.textPrimary, style = AuroraType.Title)
        if (w.canSkip) AuroraButton(stringResource(R.string.schedule_skip_today), onSkip, Modifier.fillMaxWidth().padding(top = Space.XS), kind = ButtonKind.Secondary)
        else if (w.committed) Text(stringResource(R.string.session_ends_automatically), color = colors.dawnText, style = AuroraType.Label)
    }
}

/** The flagship entry to Intentional App Opening. */
@Composable
private fun IntentFeatureCard(d: ProtectData, onOpen: () -> Unit) {
    val colors = Aurora.colors
    val on = d.rules.activeIntents(d.plan).size
    Column(
        Modifier
            .padding(horizontal = Space.M)
            .fillMaxWidth()
            .clip(Radius.L)
            .background(androidx.compose.ui.graphics.Brush.linearGradient(listOf(colors.dawnSoft, colors.accentSoft)))
            .border(1.dp, colors.hairline, Radius.L)
            .clickable(role = Role.Button, onClick = onOpen)
            .padding(Space.L),
        verticalArrangement = Arrangement.spacedBy(Space.XS),
    ) {
        Text(stringResource(R.string.intent_card_eyebrow), color = colors.dawnText, style = AuroraType.Eyebrow)
        Text(stringResource(R.string.intent_name), color = colors.textPrimary, style = AuroraType.Headline)
        Text(stringResource(R.string.intent_card_body), color = colors.textSecondary, style = AuroraType.Body)
        Spacer(Modifier.height(Space.XS))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (on == 0) stringResource(R.string.intent_card_set_up) else pluralStringResource(R.plurals.intent_card_on, on, on),
                Modifier.weight(1f),
                color = colors.accent,
                style = AuroraType.Label,
            )
            Chevron()
        }
    }
}

/** Mode name outside composition (summary lines are built in plain code). */
fun modeNameText(context: android.content.Context, mode: Mode): String = context.getString(
    when (mode) {
        Mode.Flexible -> R.string.mode_flexible
        Mode.Friction -> R.string.mode_friction
        Mode.Cooldown -> R.string.mode_cooldown
        Mode.AlwaysBlock -> R.string.mode_always_block
    },
)

/**
 * Every app the person can open from their launcher: all of them, alphabetically, with search. Phase 3's
 * onboarding list stopped after 30 apps; nothing here is cut off. Ticking adds the app to the protected
 * apps (preferences only); it starts no protection.
 */
@Composable
fun AppPickerScreen(onBack: () -> Unit, onPlus: () -> Unit) {
    val vm = auroraViewModel { ProtectModel(it) }
    val apps by vm.apps.collectAsStateWithLifecycle()
    val data by vm.data.collectAsStateWithLifecycle()
    val exempt by vm.exempt.collectAsStateWithLifecycle()
    val notice by vm.notice.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    AuroraScreen(
        title = stringResource(R.string.picker_title),
        subtitle = data?.let { stringResource(R.string.picker_subtitle, allowanceText(it.active.size, it.max(Allowance.ProtectedItems))) },
        onBack = onBack,
    ) {
        item(key = "search") {
            AuroraTextField(query, { query = it }, stringResource(R.string.picker_search), Modifier.padding(horizontal = Space.M, vertical = Space.S), icon = AuroraIcons.Search)
        }
        appList(apps, data, exempt, query) { pkg -> ProtectedItem.App.of(pkg)?.let { vm.toggle(it) } }
    }
    notice?.let { n ->
        AuroraDialog(n.title, vm::dismissNotice, if (n.plus) stringResource(R.string.plus_see) else stringResource(R.string.action_ok), { vm.dismissNotice(); if (n.plus) onPlus() }, n.body, if (n.plus) stringResource(R.string.action_not_now) else null)
    }
}

/** The searchable, complete list of launchable apps, shared by the picker and onboarding. */
fun androidx.compose.foundation.lazy.LazyListScope.appList(
    apps: List<InstalledApp>?,
    data: ProtectData?,
    exempt: Set<String>,
    query: String,
    onToggle: (String) -> Unit,
) {
    if (apps == null || data == null) {
        item { SkeletonRows(8) }
        return
    }
    val chosen = data.list.apps.toSet()
    val q = query.trim()
    val visible = apps.filter { it.packageName !in exempt || it.packageName in chosen }
        .filter { q.isEmpty() || it.label.contains(q, ignoreCase = true) }
    item(key = "count") {
        Text(
            if (q.isEmpty()) stringResource(R.string.picker_count, visible.size, chosen.size) else stringResource(R.string.picker_matches, visible.size),
            Modifier.padding(horizontal = Space.Gutter, vertical = Space.XS),
            color = Aurora.colors.textSecondary,
            style = AuroraType.Caption,
        )
    }
    items(visible, key = { it.packageName }, contentType = { "app" }) { app ->
        AppPickRow(app, app.packageName in chosen) { onToggle(app.packageName) }
    }
    item(key = "note") {
        Text(stringResource(R.string.picker_exempt_note), Modifier.padding(Space.Gutter), color = Aurora.colors.textTertiary, style = AuroraType.Caption)
    }
}

@Composable
private fun AppPickRow(app: InstalledApp, selected: Boolean, onClick: () -> Unit) {
    val colors = Aurora.colors
    val state = stringResource(if (selected) R.string.picker_selected else R.string.picker_not_selected)
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(role = Role.Checkbox, onClick = onClick)
            .semantics(mergeDescendants = true) { stateDescription = state }
            .padding(horizontal = Space.Gutter, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.M),
    ) {
        AppIcon(app, size = 44.dp)
        Text(app.label, Modifier.weight(1f), color = colors.textPrimary, style = AuroraType.BodyStrong, maxLines = 1, overflow = TextOverflow.Ellipsis)
        SelectionMark(selected)
    }
}
