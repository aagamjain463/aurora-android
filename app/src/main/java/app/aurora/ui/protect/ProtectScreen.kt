package app.aurora.ui.protect

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.aurora.auroraViewModel
import app.aurora.data.Access
import app.aurora.data.AccessState
import app.aurora.data.InstalledApp
import app.aurora.data.openAccessSettings
import app.aurora.lockdownLeft
import app.aurora.plan.Allowance
import app.aurora.plan.ProtectedItem
import app.aurora.protect.key
import app.aurora.rememberAccess
import app.aurora.ui.components.AppIcon
import app.aurora.ui.components.AuroraButton
import app.aurora.ui.components.AuroraDialog
import app.aurora.ui.components.AuroraIcons
import app.aurora.ui.components.AuroraRow
import app.aurora.ui.components.AuroraScreen
import app.aurora.ui.components.AuroraTextField
import app.aurora.ui.components.ButtonKind
import app.aurora.ui.components.Chevron
import app.aurora.ui.components.EmptyState
import app.aurora.ui.components.IconBadge
import app.aurora.ui.components.Notice
import app.aurora.ui.components.RowDivider
import app.aurora.ui.components.RowGroup
import app.aurora.ui.components.SectionLabel
import app.aurora.ui.components.SelectionMark
import app.aurora.ui.components.SkeletonRows
import app.aurora.ui.components.StatusPill
import app.aurora.ui.components.Tag
import app.aurora.ui.components.allowanceText
import app.aurora.ui.home.clockTime
import app.aurora.ui.theme.Aurora
import app.aurora.ui.theme.AuroraType
import app.aurora.ui.theme.Space

/** Where the Protect tab leads. */
object ProtectRoutes {
    const val Apps = "protect/apps"
    const val Item = "protect/item"
    const val Schedules = "protect/schedules"
    const val Limits = "protect/limits"
    const val Groups = "protect/groups"
    const val Lockdown = "protect/lockdown"
    const val Web = "protect/web"
    const val Plus = "plus"
}

/**
 * The control centre: is Shield working, what is protected and how, and the rules on top. Simple on
 * the surface (a list and a few rows); every detail is one tap away.
 */
@Composable
fun ProtectScreen(onOpen: (String) -> Unit = {}, onOpenItem: (String) -> Unit = {}) {
    val vm = auroraViewModel { ProtectModel(it) }
    val context = LocalContext.current
    val data by vm.data.collectAsStateWithLifecycle()
    val notice by vm.notice.collectAsStateWithLifecycle()
    val status = rememberProtectionStatus(data?.list)
    var disclosure by rememberSaveable { mutableStateOf(false) }
    var choosing by remember { mutableStateOf<ProtectedItem?>(null) }
    LaunchedEffect(Unit) { vm.pruneUninstalled() }
    val colors = Aurora.colors

    AuroraScreen(title = "Protect", subtitle = "What pulls you away, and how aurora steps in.", tabBar = true) {
        val d = data
        if (status != null) item(key = "status") {
            Spacer(Modifier.height(Space.M))
            ShieldStatusCard(
                status = status,
                count = d?.active?.size ?: 0,
                allowed = rememberAccess(Access.Accessibility) == AccessState.Granted,
                onTurnOn = { disclosure = true },
                onOpenSettings = { openAccessSettings(context, Access.Accessibility) },
            )
        }
        if (d == null) {
            item(key = "loading") { SkeletonRows(4) }
            return@AuroraScreen
        }
        if (!d.rulesReadable) item(key = "unreadable") {
            Notice("Can't read your rules", "aurora can't read your saved rules, so Shield isn't applying them and nothing can be changed. Close aurora and open it again.", Modifier.padding(top = Space.S))
        }
        val lockdown = d.runtime.lockdown?.takeUnless { it.isOver(d.time) }
        if (lockdown != null) item(key = "lockdown-live") {
            LiveBanner("Lockdown", "Until ${clockTime(lockdown.endWall(d.time))} · it can't be ended early", Modifier.padding(top = Space.S)) { onOpen(ProtectRoutes.Lockdown) }
        }
        if (d.paused.isNotEmpty()) item(key = "paused") {
            Notice(
                eyebrow = "Paused",
                body = "aurora Free protects ${d.max(Allowance.ProtectedItems)} items at a time. ${d.paused.size} of yours " +
                    "${if (d.paused.size == 1) "is" else "are"} saved and paused. Tap one to choose what happens to it.",
                modifier = Modifier.padding(top = Space.S),
            )
        }

        item(key = "items-label") {
            SectionLabel("Protected · ${allowanceText(d.active.size, d.max(Allowance.ProtectedItems))}")
        }
        item(key = "items") {
            RowGroup {
                d.list.items.forEachIndexed { i, item ->
                    if (i > 0) RowDivider(72.dp)
                    val key = item.key
                    val isPaused = item in d.paused
                    val website = item is ProtectedItem.Website
                    AuroraRow(
                        title = d.label(key),
                        subtitle = if (isPaused) "Paused · tap to choose" else d.rules.summary(key, d.plan, website),
                        leading = {
                            Box(Modifier.alpha(if (isPaused) 0.5f else 1f)) {
                                val icon = d.apps[key]
                                if (icon != null) AppIcon(icon, size = 40.dp) else IconBadge(AuroraIcons.Globe)
                            }
                        },
                        onClick = { if (isPaused) choosing = item else onOpenItem(key) },
                        trailing = { Chevron() },
                    )
                }
                if (d.list.items.isNotEmpty()) RowDivider(72.dp)
                AuroraRow(
                    title = if (d.list.items.isEmpty()) "Choose apps to protect" else "Add apps",
                    subtitle = if (d.list.items.isEmpty()) "Start with the ones that tend to pull you in." else null,
                    leading = { IconBadge(AuroraIcons.Plus) },
                    onClick = { onOpen(ProtectRoutes.Apps) },
                )
                RowDivider(72.dp)
                AuroraRow(title = "Add a website", leading = { IconBadge(AuroraIcons.Globe) }, onClick = { onOpen(ProtectRoutes.Web) })
            }
        }

        item(key = "rules") {
            SectionLabel("Rules")
            RowGroup {
                val schedules = d.rules.schedules
                AuroraRow(
                    title = "Schedules",
                    subtitle = if (schedules.isEmpty()) "Protection that starts on its own, like study hours."
                    else "${allowanceText(d.rules.activeSchedules(d.plan).size, d.max(Allowance.Schedules))} on · ${schedules.first().name}",
                    icon = AuroraIcons.Calendar,
                    onClick = { onOpen(ProtectRoutes.Schedules) },
                    trailing = { Chevron() },
                )
                RowDivider(56.dp)
                AuroraRow(
                    title = "Daily limits",
                    subtitle = if (d.rules.limits.isEmpty()) "Decide how long an app deserves each day."
                    else "${allowanceText(d.rules.activeLimits(d.plan).size, d.max(Allowance.DailyLimitRules))} on",
                    icon = AuroraIcons.Hourglass,
                    onClick = { onOpen(ProtectRoutes.Limits) },
                    trailing = { Chevron() },
                )
                RowDivider(56.dp)
                AuroraRow(
                    title = "Groups",
                    subtitle = if (d.rules.groups.isEmpty()) "Social, entertainment, games: protect them together."
                    else d.rules.groups.joinToString(", ") { it.name },
                    icon = AuroraIcons.Layers,
                    onClick = { onOpen(ProtectRoutes.Groups) },
                    trailing = { Chevron() },
                )
                RowDivider(56.dp)
                val left = vm.app.lockdownLeft()
                AuroraRow(
                    title = "Lockdown",
                    subtitle = when {
                        lockdown != null -> "On until ${clockTime(lockdown.endWall(d.time))}"
                        left != null -> "A firm commitment for a set time · $left min left today"
                        else -> "A firm commitment for a set time"
                    },
                    icon = AuroraIcons.Lock,
                    onClick = { onOpen(ProtectRoutes.Lockdown) },
                    trailing = { Chevron() },
                )
            }
        }
        item(key = "web") {
            SectionLabel("Websites & content")
            RowGroup {
                AuroraRow(
                    title = "Web & content Shield",
                    subtitle = "Websites in supported browsers, and Shorts and Reels.",
                    icon = AuroraIcons.Globe,
                    onClick = { onOpen(ProtectRoutes.Web) },
                    trailing = { Tag("optional"); Chevron() },
                )
            }
        }
    }

    if (disclosure) {
        ShieldDisclosure(
            onContinue = {
                disclosure = false
                openAccessSettings(context, Access.Accessibility)
            },
            onDismiss = { disclosure = false },
        )
    }
    choosing?.let { item ->
        val d = data
        val label = d?.label(item.key) ?: item.key
        val max = d?.max(Allowance.ProtectedItems)
        val free = (d?.active?.size ?: 0) < (max ?: Int.MAX_VALUE)
        AuroraDialog(
            title = "$label is paused",
            body = if (free) "You have a free slot. Resume $label to protect it again, or remove it from your list."
            else "aurora Free protects $max items at a time. Remove another item first to resume $label, or remove it from your list.",
            confirmLabel = if (free) "Resume" else "Remove $label",
            onConfirm = {
                choosing = null
                if (free) vm.resume(item) else vm.remove(item)
            },
            dismissLabel = "Cancel",
            onDismiss = { choosing = null },
        ) {
            if (free) AuroraButton("Remove from list", { choosing = null; vm.remove(item) }, kind = ButtonKind.Quiet)
        }
    }
    LimitDialog(notice, vm::dismissNotice) { onOpen(ProtectRoutes.Plus) }
}

/** A live rule, tinted with the accent: a Lockdown or a Focus session running now. */
@Composable
fun LiveBanner(title: String, body: String, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null) {
    val colors = Aurora.colors
    RowGroup(modifier) {
        AuroraRow(
            title = title,
            subtitle = body,
            modifier = Modifier.background(colors.accentSoft),
            leading = { StatusPill("Live", live = true) },
            onClick = onClick,
            trailing = if (onClick != null) ({ Chevron() }) else null,
        )
    }
}

/** The calm "that's the most for now" dialog, with a quiet way to see aurora plus. */
@Composable
fun LimitDialog(notice: LimitNotice?, onDismiss: () -> Unit, onPlus: () -> Unit) {
    if (notice == null) return
    AuroraDialog(
        title = notice.title,
        body = notice.body,
        confirmLabel = "Got it",
        onConfirm = onDismiss,
        onDismiss = onDismiss,
    ) {
        if (notice.plus) AuroraButton("About aurora plus", { onDismiss(); onPlus() }, kind = ButtonKind.Quiet)
    }
}

/** Every launchable app, searchable; ticking protects it at once. */
@Composable
fun AppPickerScreen(onBack: () -> Unit, onPlus: () -> Unit) {
    val vm = auroraViewModel { ProtectModel(it) }
    val apps by vm.apps.collectAsStateWithLifecycle()
    val data by vm.data.collectAsStateWithLifecycle()
    val exempt by vm.exempt.collectAsStateWithLifecycle()
    val notice by vm.notice.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    val chosen = remember(data?.list) { data?.list?.apps.orEmpty().toSet() }
    val paused = remember(data) { data?.paused.orEmpty().map { it.key }.toSet() }
    // Chosen apps lead, but the order is fixed when the list loads: ticking never moves a row away.
    val ordered = remember(apps, data != null) { apps?.sortedByDescending { it.packageName in chosen } }
    val visible = remember(ordered, query) {
        val q = query.trim()
        if (q.isEmpty()) ordered else ordered?.filter { it.label.contains(q, ignoreCase = true) }
    }
    val max = data?.max(Allowance.ProtectedItems)

    AuroraScreen(
        title = "Choose apps",
        subtitle = "${allowanceText(data?.active?.size ?: 0, max)} protected. Tick to protect, untick to stop.",
        onBack = onBack,
    ) {
        item(key = "search") {
            AuroraTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = "Search apps",
                icon = AuroraIcons.Search,
                imeAction = ImeAction.Search,
                modifier = Modifier.padding(horizontal = Space.M).padding(top = Space.M, bottom = Space.S),
            )
        }
        when {
            visible == null -> item(key = "loading") { SkeletonRows() }
            visible.isEmpty() -> item(key = "empty") {
                EmptyState(
                    title = if (query.isBlank()) "No apps found" else "Nothing matches “${query.trim()}”",
                    body = if (query.isBlank()) "aurora couldn't find any apps to list on this phone." else "Try a different name.",
                )
            }
            else -> items(visible, key = { it.packageName }, contentType = { "app" }) { app ->
                val pkg = app.packageName
                val isChosen = pkg in chosen
                AppRow(
                    app = app,
                    chosen = isChosen,
                    note = when {
                        pkg in exempt -> "aurora never shields this app"
                        pkg in paused -> "Paused · open Protect to choose"
                        else -> null
                    },
                    // A protected exempt app (chosen before Phase 3) can still be removed.
                    enabled = isChosen || pkg !in exempt,
                ) { ProtectedItem.App.of(pkg)?.let { vm.toggle(it) } }
            }
        }
    }
    LimitDialog(notice, vm::dismissNotice, onPlus)
}

@Composable
fun AppRow(app: InstalledApp, chosen: Boolean, note: String?, enabled: Boolean, onToggle: () -> Unit) {
    val colors = Aurora.colors
    Row(
        Modifier
            .fillMaxWidth()
            .toggleable(chosen, enabled = enabled, role = Role.Checkbox, onValueChange = { onToggle() })
            .heightIn(min = 64.dp)
            .padding(horizontal = Space.Gutter, vertical = Space.XS)
            .alpha(if (enabled) 1f else 0.55f),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.M),
    ) {
        AppIcon(app, size = 40.dp)
        Column(Modifier.weight(1f)) {
            Text(app.label, color = colors.textPrimary, style = AuroraType.Body, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (note != null) Text(note, color = colors.textSecondary, style = AuroraType.Caption)
        }
        if (enabled) SelectionMark(chosen)
    }
}

/** A key's icon: the app's own, or a globe for a site. */
@Composable
fun KeyIcon(data: ProtectData, key: String, size: Int = 40) {
    val app = data.apps[key]
    if (app != null) AppIcon(app, size = size.dp) else IconBadge(AuroraIcons.Globe, size = size)
}

@Composable
internal fun Hint(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier.padding(horizontal = Space.Gutter).padding(top = Space.S),
        color = Aurora.colors.textTertiary,
        style = AuroraType.Caption,
    )
}

@Composable
internal fun Pad(height: androidx.compose.ui.unit.Dp = Space.M) = Spacer(Modifier.height(height))
