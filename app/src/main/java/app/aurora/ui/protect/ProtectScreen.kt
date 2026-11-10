package app.aurora.ui.protect

import app.aurora.ui.components.ArtTier
import app.aurora.ui.components.AuroraHeader
import app.aurora.ui.components.LitBadge
import app.aurora.ui.components.TwoToneHeadline
import app.aurora.ui.components.auroraSurface
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.size
import androidx.compose.ui.semantics.heading
import androidx.compose.material3.Icon
import app.aurora.ui.components.auroraSurface
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
    const val Keep = "plus/keep"
    const val Groups = "protect/groups"
    const val Intent = "protect/intent"
    const val IntentApp = "protect/intent/app"
    const val Web = "protect/web"
    const val Plus = "plus"
}

/** A schedule window running now, outside any session. */
data class RunningWindow(val id: String, val name: String, val mode: Mode, val keys: Set<String>, val end: Long, val committed: Boolean, val canSkip: Boolean)

fun runningWindows(d: ProtectData): List<RunningWindow> {
    val zone = ZoneId.systemDefault()
    val now = d.time.wall
    return d.rules.activeSchedules().mapNotNull { s ->
        val (start, end) = s.windowAt(now, zone) ?: return@mapNotNull null
        if (d.runtime.skipped[s.id] == start && s.mode != Mode.AlwaysBlock) return@mapNotNull null
        // A committed Strict window shows as its session instead.
        if (d.sessions.any { it.scheduleId == s.id }) return@mapNotNull null
        val committed = s.mode == Mode.AlwaysBlock && d.plan == Plan.Plus
        RunningWindow(s.id, s.name, s.mode, d.rules.covers(s, d.activeKeys), end, committed, canSkip = !committed && d.runtime.skipped[s.id] != start)
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
        AuroraDialog(n.title, vm::dismissNotice, if (n.plus) stringResource(R.string.plus_see) else stringResource(R.string.action_ok), { vm.dismissNotice(); if (n.plus) onPlus() }, n.body, if (n.plus) stringResource(R.string.action_not_now) else null, plus = n.plus)
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
