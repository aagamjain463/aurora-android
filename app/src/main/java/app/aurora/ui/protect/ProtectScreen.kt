package app.aurora.ui.protect

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.aurora.AuroraApplication
import app.aurora.auroraViewModel
import app.aurora.data.InstalledApp
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
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class ProtectViewModel(private val app: AuroraApplication) : ViewModel() {
    private val _apps = MutableStateFlow<List<InstalledApp>?>(null)

    /** Real launchable apps on this device; null while loading. */
    val apps: StateFlow<List<InstalledApp>?> = _apps

    val chosen: StateFlow<Set<String>?> = app.preferences.preferences
        .map { it.protectedApps }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    init {
        viewModelScope.launch { _apps.value = app.catalog.launchableApps() }
    }

    fun toggle(packageName: String) {
        viewModelScope.launch { app.preferences.toggleProtected(packageName) }
    }
}

@Composable
fun ProtectScreen() {
    val vm = auroraViewModel { ProtectViewModel(it) }
    val apps by vm.apps.collectAsStateWithLifecycle()
    val chosenOrNull by vm.chosen.collectAsStateWithLifecycle()
    val chosen = chosenOrNull.orEmpty()
    var query by rememberSaveable { mutableStateOf("") }

    // Chosen apps lead the list, but the order is fixed when the list loads:
    // ticking an app never makes it jump away from your finger.
    val ordered = remember(apps, chosenOrNull != null) {
        apps?.sortedByDescending { it.packageName in chosen }
    }
    val visible = remember(ordered, query) {
        val q = query.trim()
        if (q.isEmpty()) ordered else ordered?.filter { it.label.contains(q, ignoreCase = true) }
    }

    AuroraScreen(
        title = "Protect",
        subtitle = "Choose the apps that tend to pull you away.",
        tabBar = true,
    ) {
        item(key = "notice") {
            Spacer(Modifier.height(Space.M))
            Notice(
                eyebrow = "Preview",
                body = "Your choices are saved on this phone, but nothing is blocked yet. " +
                    "Blocking arrives in an upcoming update, and aurora will tell you clearly when it's on.",
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
            SectionLabel(if (chosen.isEmpty()) "Your apps" else "Your apps · ${chosen.size} chosen")
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
                AppRow(app, app.packageName in chosen) { vm.toggle(app.packageName) }
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
}

@Composable
private fun AppRow(app: InstalledApp, chosen: Boolean, onToggle: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .toggleable(chosen, role = Role.Checkbox, onValueChange = { onToggle() })
            .heightIn(min = 64.dp)
            .padding(horizontal = Space.Gutter, vertical = Space.XS),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.M),
    ) {
        AppIcon(app, size = 40.dp)
        Text(app.label, Modifier.weight(1f), color = Aurora.colors.textPrimary, style = AuroraType.Body, maxLines = 1, overflow = TextOverflow.Ellipsis)
        SelectionMark(chosen)
    }
}
