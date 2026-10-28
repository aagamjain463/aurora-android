package app.aurora.ui.insights

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.aurora.AuroraApplication
import app.aurora.auroraViewModel
import app.aurora.data.Access
import app.aurora.data.formatDuration
import app.aurora.data.openUsageAccessSettings
import app.aurora.ui.components.AuroraButton
import app.aurora.ui.components.AuroraIcons
import app.aurora.ui.components.AuroraScreen
import app.aurora.ui.components.EmptyState
import app.aurora.ui.components.SectionLabel
import app.aurora.ui.components.SkeletonRows
import app.aurora.ui.theme.Aurora
import app.aurora.ui.theme.AuroraType
import app.aurora.ui.theme.Radius
import app.aurora.ui.theme.Space
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

data class UsageRow(val packageName: String, val label: String, val icon: ImageBitmap?, val ms: Long, val onList: Boolean)

private const val OtherApps = "other"

data class InsightsState(
    val access: Boolean,
    val loading: Boolean = false,
    val total: Long = 0,
    val rows: List<UsageRow> = emptyList(),
)

class InsightsViewModel(private val app: AuroraApplication) : ViewModel() {
    private val _state = MutableStateFlow(InsightsState(access = app.access.isGranted(Access.Usage), loading = true))
    val state: StateFlow<InsightsState> = _state

    fun refresh() {
        viewModelScope.launch {
            if (!app.access.isGranted(Access.Usage)) {
                _state.value = InsightsState(access = false)
                return@launch
            }
            val usage = app.screenTime.today()
            val onList = app.protection.list.first().apps.toSet()
            // Apps aurora can't see (system components, uninstalled apps) are folded into one honest row.
            var other = 0L
            val named = usage.mapNotNull { (pkg, ms) ->
                val installed = app.catalog.app(pkg)
                if (installed == null) other += ms
                installed?.let { UsageRow(pkg, it.label, it.icon, ms, pkg in onList) }
            }
            val rows = (named + UsageRow(OtherApps, "Other apps", null, other, false))
                .filter { it.ms >= 60_000 }
                .sortedByDescending { it.ms }
                .take(8)
            _state.value = InsightsState(access = true, total = usage.values.sum(), rows = rows)
        }
    }
}

@Composable
fun InsightsScreen() {
    val vm = auroraViewModel { InsightsViewModel(it) }
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val colors = Aurora.colors
    LifecycleResumeEffect(Unit) {
        vm.refresh()
        onPauseOrDispose { }
    }

    AuroraScreen(title = "Insights", subtitle = "Today, on this phone.", tabBar = true) {
        when {
            !state.access -> item(key = "permission") {
                EmptyState(
                    icon = AuroraIcons.Insights,
                    title = "See where your time goes",
                    body = "With usage access, aurora can show how long you spend in each app today. " +
                        "It's calculated on this phone and never leaves it.",
                    action = {
                        AuroraButton("Allow usage access", { openUsageAccessSettings(context) })
                    },
                )
                Text(
                    "Android Settings will open. Find aurora and turn on “Permit usage access”.",
                    Modifier.fillMaxWidth().padding(horizontal = Space.Gutter * 2),
                    color = colors.textTertiary,
                    style = AuroraType.Caption,
                    textAlign = TextAlign.Center,
                )
            }
            state.loading -> item(key = "loading") { SkeletonRows(5) }
            state.total < 60_000 -> item(key = "quiet") {
                EmptyState(
                    icon = AuroraIcons.Today,
                    title = "A quiet day so far",
                    body = "There's no screen time to show since midnight yet.",
                )
            }
            else -> {
                item(key = "total") {
                    Column(Modifier.padding(horizontal = Space.Gutter).padding(top = Space.L)) {
                        Text(formatDuration(state.total), color = colors.textPrimary, style = AuroraType.Hero)
                        Text("screen time since midnight", color = colors.textSecondary, style = AuroraType.Secondary)
                    }
                }
                item(key = "label") { SectionLabel("Most used") }
                val max = state.rows.maxOfOrNull { it.ms } ?: 1L
                items(state.rows, key = { it.packageName }) { row -> UsageBar(row, row.ms.toFloat() / max) }
                item(key = "footnote") {
                    Text(
                        "Calculated on this phone from Android's usage records. " +
                            "Totals can differ slightly from Digital Wellbeing.",
                        Modifier.padding(horizontal = Space.Gutter).padding(top = Space.L),
                        color = colors.textTertiary,
                        style = AuroraType.Caption,
                    )
                }
            }
        }
    }
}

@Composable
private fun UsageBar(row: UsageRow, fraction: Float) {
    val colors = Aurora.colors
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = Space.Gutter, vertical = Space.S)
            .semantics(mergeDescendants = true) {
                contentDescription = "${row.label}, ${formatDuration(row.ms)}" + if (row.onList) ", on your protect list" else ""
            },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.M),
    ) {
        if (row.icon != null) {
            Image(row.icon, null, Modifier.size(36.dp))
        } else {
            Box(Modifier.size(36.dp).clip(Radius.S).background(colors.hairline))
        }
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.S)) {
                Text(
                    row.label, Modifier.weight(1f),
                    color = colors.textPrimary, style = AuroraType.Body, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Text(formatDuration(row.ms), color = colors.textSecondary, style = AuroraType.Secondary)
            }
            Spacer(Modifier.height(6.dp))
            Box(Modifier.fillMaxWidth().height(4.dp).clip(Radius.Pill).background(colors.hairline)) {
                Box(
                    Modifier
                        .fillMaxWidth(fraction.coerceIn(0.02f, 1f))
                        .height(4.dp)
                        .clip(Radius.Pill)
                        .background(if (row.onList) colors.dawn else colors.accent),
                )
            }
            if (row.onList) {
                Spacer(Modifier.height(4.dp))
                Text("on your protect list", color = colors.dawnText, style = AuroraType.Caption)
            }
        }
    }
}
