package app.aurora.ui.protect

import androidx.compose.foundation.layout.Arrangement
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
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.aurora.AuroraApplication
import app.aurora.auroraViewModel
import app.aurora.data.Access
import app.aurora.data.InstalledApp
import app.aurora.data.openAccessSettings
import app.aurora.plan.Allowance
import app.aurora.plan.ProtectedItem
import app.aurora.plan.limit
import app.aurora.protect.AddResult
import app.aurora.protect.ProtectedList
import app.aurora.protect.exemptPackages
import app.aurora.ui.components.AuroraDialog
import app.aurora.ui.components.AppIcon
import app.aurora.ui.components.AuroraIcons
import app.aurora.ui.components.AuroraScreen
import app.aurora.ui.components.AuroraTextField
import app.aurora.ui.components.ComingSoonRow
import app.aurora.ui.components.EmptyState
import app.aurora.ui.components.Notice
import app.aurora.ui.components.RowDivider
import app.aurora.ui.components.RowGroup
import app.aurora.ui.components.SectionLabel
import app.aurora.ui.components.SelectionMark
import app.aurora.ui.components.SkeletonRows
import app.aurora.ui.theme.Aurora
import app.aurora.ui.theme.AuroraType
import app.aurora.ui.theme.Space
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import java.io.IOException

class ProtectViewModel(private val app: AuroraApplication) : ViewModel() {
    private val _apps = MutableStateFlow<List<InstalledApp>?>(null)
    private val _exempt = MutableStateFlow<Set<String>>(emptySet())
    private val _atLimit = MutableStateFlow(false)

    /** Real launchable apps on this device; null while loading. */
    val apps: StateFlow<List<InstalledApp>?> = _apps

    /** Apps Shield never covers on this phone (Settings, the dialer, the launcher...). */
    val exempt: StateFlow<Set<String>> = _exempt

    /** The same list the Shield service enforces; null while loading. */
    val list: StateFlow<ProtectedList?> = app.protection.list
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** True after an add was refused at the plan's limit, until the message is dismissed. */
    val atLimit: StateFlow<Boolean> = _atLimit

    val plan = app.plan

    init {
        viewModelScope.launch {
            _exempt.value = withContext(Dispatchers.IO) { exemptPackages(app) }
            _apps.value = app.catalog.launchableApps()
            pruneUninstalled()
        }
    }

    fun toggle(packageName: String) {
        val item = ProtectedItem.App.of(packageName) ?: return
        viewModelScope.launch {
            try {
                if (item in app.protection.list.first().items) app.protection.remove(listOf(item))
                else if (app.protection.add(item, plan) == AddResult.AtLimit) _atLimit.value = true
            } catch (_: IOException) {
                // The list on screen is read back from storage, so it keeps showing the truth.
            }
        }
    }

    fun dismissLimit() {
        _atLimit.value = false
    }

    /**
     * Uninstalled apps can't be shielded, and they'd hold a slot, so they're removed (ROADMAP Phase 3
     * task 8). Only apps Android says don't exist: a disabled or hidden app is kept.
     */
    private suspend fun pruneUninstalled() {
        val list = app.protection.list.first()
        if (!list.readable) return
        val gone = list.items.filterIsInstance<ProtectedItem.App>().filter { app.catalog.app(it.packageName) == null }
        if (gone.isNotEmpty()) runCatching { app.protection.remove(gone) }
    }
}

@Composable
fun ProtectScreen() {
    val vm = auroraViewModel { ProtectViewModel(it) }
    val context = LocalContext.current
    val apps by vm.apps.collectAsStateWithLifecycle()
    val list by vm.list.collectAsStateWithLifecycle()
    val exempt by vm.exempt.collectAsStateWithLifecycle()
    val atLimit by vm.atLimit.collectAsStateWithLifecycle()
    val status = rememberProtectionStatus(list)
    val chosen = remember(list) { list?.apps.orEmpty().toSet() }
    val paused = remember(list) { list?.paused(vm.plan).orEmpty().mapNotNull { (it as? ProtectedItem.App)?.packageName }.toSet() }
    var query by rememberSaveable { mutableStateOf("") }
    var disclosure by rememberSaveable { mutableStateOf(false) }

    // Chosen apps lead the list, but the order is fixed when the list loads:
    // ticking an app never makes it jump away from your finger.
    val ordered = remember(apps, list != null) {
        apps?.sortedByDescending { it.packageName in chosen }
    }
    val visible = remember(ordered, query) {
        val q = query.trim()
        if (q.isEmpty()) ordered else ordered?.filter { it.label.contains(q, ignoreCase = true) }
    }
    val max = limit(vm.plan, Allowance.ProtectedItems)
    val count = list?.items?.size ?: 0

    AuroraScreen(
        title = "Protect",
        subtitle = "Choose the apps that tend to pull you away.",
        tabBar = true,
    ) {
        if (status != null) item(key = "status") {
            Spacer(Modifier.height(Space.M))
            ShieldStatusCard(
                status = status,
                count = count,
                onTurnOn = { disclosure = true },
                onOpenSettings = { openAccessSettings(context, Access.Accessibility) },
            )
        }
        if (paused.isNotEmpty()) item(key = "paused") {
            Notice(
                eyebrow = "Paused",
                body = "aurora Free protects $max items. ${paused.size} of yours ${if (paused.size == 1) "is" else "are"} paused and saved. " +
                    "Remove one to make the next one active.",
                modifier = Modifier.padding(top = Space.S),
            )
        }
        item(key = "search") {
            AuroraTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = "Search apps",
                icon = AuroraIcons.Search,
                imeAction = ImeAction.Search,
                modifier = Modifier.padding(horizontal = Space.M).padding(top = Space.L),
            )
        }
        item(key = "label") {
            SectionLabel(
                when {
                    count == 0 -> "Your apps"
                    max != null -> "Your apps · $count of $max protected"
                    else -> "Your apps · $count protected"
                },
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
                        pkg in paused -> "Paused · over the Free limit"
                        else -> null
                    },
                    // A protected exempt app (chosen before Phase 3) can still be removed.
                    enabled = isChosen || pkg !in exempt,
                ) { vm.toggle(pkg) }
            }
        }
        item(key = "soon") {
            SectionLabel("On the way")
            RowGroup {
                ComingSoonRow("Pause before opening", "A moment to choose, before an app takes over.", AuroraIcons.Pause)
                RowDivider(56.dp)
                ComingSoonRow("Time limits", "Decide how long an app deserves each day.", AuroraIcons.Focus)
                RowDivider(56.dp)
                ComingSoonRow("Schedules & strict mode", "Protection that holds when it matters most.", AuroraIcons.Calendar)
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
    if (atLimit) {
        AuroraDialog(
            title = "That's the most for now",
            body = "You're protecting $max items, the most aurora Free allows. Remove one to add another.",
            confirmLabel = "Got it",
            onConfirm = vm::dismissLimit,
            onDismiss = vm::dismissLimit,
        )
    }
}

@Composable
private fun AppRow(app: InstalledApp, chosen: Boolean, note: String?, enabled: Boolean, onToggle: () -> Unit) {
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
