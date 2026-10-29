package app.aurora.ui.strict

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.aurora.R
import app.aurora.auroraViewModel
import app.aurora.data.formatClock
import app.aurora.protect.FocusOutcome
import app.aurora.reportFocusNow
import app.aurora.runsNow
import app.aurora.ui.components.AuroraButton
import app.aurora.ui.components.AuroraScreen
import app.aurora.ui.components.ButtonKind
import app.aurora.ui.components.LightRing
import app.aurora.ui.components.SectionLabel
import app.aurora.ui.components.Tag
import app.aurora.ui.components.auroraSurface
import app.aurora.ui.components.clockTime
import app.aurora.ui.components.minutesText
import app.aurora.ui.protect.ProtectModel
import app.aurora.ui.theme.Aurora
import app.aurora.ui.theme.AuroraType
import app.aurora.ui.theme.Space
import kotlinx.coroutines.launch

/** Where a capability stands on an ordinary phone (docs/CONFIGURATIONS.md "Always Block"). */
enum class Capability { Works, Managed, NotConsumer, Policy }

/**
 * Always Block, honestly: what the commitment really does, and what Android keeps in the person's hands.
 * No switches: nothing here can be turned on that doesn't work.
 */
val StrictCapabilities = listOf(
    Triple(R.string.strict_cancel, R.string.strict_cancel_body, Capability.Works),
    Triple(R.string.strict_weaker, R.string.strict_weaker_body, Capability.Works),
    Triple(R.string.strict_restart, R.string.strict_restart_body, Capability.Works),
    Triple(R.string.strict_clock, R.string.strict_clock_body, Capability.Works),
    Triple(R.string.strict_permission, R.string.strict_permission_body, Capability.Works),
    Triple(R.string.strict_uninstall, R.string.strict_uninstall_body, Capability.Managed),
    Triple(R.string.strict_time, R.string.strict_time_body, Capability.Managed),
    Triple(R.string.strict_settings, R.string.strict_settings_body, Capability.Policy),
    Triple(R.string.strict_permissions, R.string.strict_permissions_body, Capability.NotConsumer),
)

@Composable
fun StrictModeScreen(onBack: () -> Unit) {
    val colors = Aurora.colors
    AuroraScreen(title = stringResource(R.string.strict_title), subtitle = stringResource(R.string.strict_body), onBack = onBack) {
        items(StrictCapabilities, key = { it.first }) { (title, body, cap) ->
            Column(
                Modifier.padding(horizontal = Space.M).padding(bottom = Space.S).fillMaxWidth().auroraSurface(colors, warm = cap == Capability.Works).padding(Space.L),
                verticalArrangement = Arrangement.spacedBy(Space.XS),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.S)) {
                    Text(stringResource(title), Modifier.weight(1f), color = colors.textPrimary, style = AuroraType.Title)
                    Tag(
                        stringResource(
                            when (cap) {
                                Capability.Works -> R.string.strict_works
                                Capability.Managed -> R.string.strict_managed
                                Capability.NotConsumer -> R.string.strict_not_consumer
                                Capability.Policy -> R.string.strict_policy
                            },
                        ),
                        accent = cap == Capability.Works,
                    )
                }
                Text(stringResource(body), color = colors.textSecondary, style = AuroraType.Secondary)
            }
        }
        item(key = "limits") { Text(stringResource(R.string.start_commit_limits), Modifier.padding(Space.Gutter), color = colors.textTertiary, style = AuroraType.Caption) }
    }
}

/**
 * Focus inside a running configuration: the task, the light ring over its target, and the person's own
 * word on how it went. The configuration's protection keeps running either way.
 */
@Composable
fun FocusRunScreen(runId: String, onBack: () -> Unit) {
    val vm = auroraViewModel { ProtectModel(it) }
    val data by vm.data.collectAsStateWithLifecycle()
    val colors = Aurora.colors
    val scope = rememberCoroutineScope()
    val d = data
    val run = d?.let { vm.app.runsNow(it.time).find { r -> r.id == runId } }
    val focus = run?.focus
    AuroraScreen(title = focus?.task.orEmpty(), subtitle = run?.name, onBack = onBack) {
        if (d == null || run == null || focus == null) return@AuroraScreen
        val end = run.endAt(d.time)
        val start = run.startAt(d.time)
        val target = focus.minutes?.let { it * 60_000L } ?: ((end ?: d.time.wall) - start)
        val elapsed = (d.time.wall - start).coerceIn(0, target.coerceAtLeast(1))
        item(key = "ring") {
            Column(Modifier.fillMaxWidth().padding(vertical = Space.L), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Space.S)) {
                LightRing(elapsed.toFloat() / target.coerceAtLeast(1)) {
                    Text(formatClock(target - elapsed), color = colors.textPrimary, style = AuroraType.Hero)
                    Text(stringResource(R.string.focusrun_target, minutesText((target / 60_000).toInt())), color = colors.textSecondary, style = AuroraType.Secondary)
                }
                if (focus.goal.isNotBlank()) Text(focus.goal, color = colors.sun, style = AuroraType.BodyStrong)
                if (focus.description.isNotBlank()) Text(focus.description, Modifier.padding(horizontal = Space.Gutter), color = colors.textSecondary, style = AuroraType.Body)
                end?.let { Text(stringResource(R.string.focusrun_protection, clockTime(it)), color = colors.textTertiary, style = AuroraType.Caption) }
            }
        }
        item(key = "report") {
            SectionLabel(stringResource(R.string.focusrun_report_title))
            val reported = run.focusOutcome
            Column(Modifier.padding(horizontal = Space.M), verticalArrangement = Arrangement.spacedBy(Space.XS)) {
                if (reported != null) Text(stringResource(R.string.focusrun_reported, outcomeLabel(reported)), color = colors.accent, style = AuroraType.BodyStrong)
                listOf(FocusOutcome.Completed, FocusOutcome.Partial, FocusOutcome.NotCompleted).forEach { o ->
                    AuroraButton(
                        outcomeLabel(o), { scope.launch { runCatching { vm.app.reportFocusNow(run.id, o) } } }, Modifier.fillMaxWidth(),
                        kind = if (reported == o) ButtonKind.Primary else ButtonKind.Secondary,
                    )
                }
            }
        }
    }
}

@Composable
private fun outcomeLabel(o: FocusOutcome): String = stringResource(
    when (o) {
        FocusOutcome.Completed -> R.string.focus_completed
        FocusOutcome.Partial -> R.string.focus_partial
        FocusOutcome.NotCompleted -> R.string.focus_not_completed
    },
)

