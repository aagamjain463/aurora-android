package app.aurora.ui.guide

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.aurora.R
import app.aurora.protect.Mode
import app.aurora.ui.components.AuroraButton
import app.aurora.ui.components.AuroraDialog
import app.aurora.ui.components.AuroraIcons
import app.aurora.ui.components.AuroraScreen
import app.aurora.ui.components.ButtonKind
import app.aurora.ui.components.auroraSurface
import app.aurora.ui.protect.modeDescription
import app.aurora.ui.protect.modeName
import app.aurora.ui.theme.Aurora
import app.aurora.ui.theme.AuroraType
import app.aurora.ui.theme.Space

/** The four tabs, each with a plain explanation behind its info button. */
enum class GuideTopic(val title: Int, val icon: ImageVector) {
    Home(R.string.guide_home_title, AuroraIcons.Home),
    Shield(R.string.guide_shield_title, AuroraIcons.Protect),
    Intent(R.string.guide_intent_title, AuroraIcons.Intent),
    Track(R.string.guide_track_title, AuroraIcons.Track),
}

/** What [topic] offers, as heading and one plain line each. */
@Composable
private fun points(topic: GuideTopic): List<Pair<String, String>> = when (topic) {
    GuideTopic.Home -> listOf(
        R.string.guide_home_start to R.string.guide_home_start_body,
        R.string.guide_home_saved to R.string.guide_home_saved_body,
    ).map { (a, b) -> stringResource(a) to stringResource(b) }
    GuideTopic.Shield -> listOf(stringResource(R.string.guide_shield_what) to stringResource(R.string.guide_shield_what_body)) +
        Mode.entries.map { modeName(it) to modeDescription(it) } +
        listOf(
            stringResource(R.string.guide_shield_start) to stringResource(R.string.guide_shield_start_body),
        )
    GuideTopic.Intent -> listOf(stringResource(R.string.guide_intent_what) to stringResource(R.string.guide_intent_what_body)) +
        listOf(
            R.string.intent_step_pause to R.string.intent_step_pause_body,
            R.string.intent_step_decide to R.string.intent_step_decide_body,
            R.string.intent_step_time to R.string.intent_step_time_body,
            R.string.intent_step_use to R.string.intent_step_use_body,
            R.string.intent_step_follow to R.string.intent_step_follow_body,
        ).map { (a, b) -> stringResource(a) to stringResource(b) } +
        listOf(
            stringResource(R.string.guide_shield_start) to stringResource(R.string.guide_intent_start_body),
        )
    GuideTopic.Track -> listOf(
        R.string.guide_track_usage to R.string.guide_track_usage_body,
        R.string.guide_track_progress to R.string.guide_track_progress_body,
        R.string.guide_track_goals to R.string.guide_track_goals_body,
    ).map { (a, b) -> stringResource(a) to stringResource(b) }
}

@Composable
private fun Points(topic: GuideTopic) {
    val colors = Aurora.colors
    Column(verticalArrangement = Arrangement.spacedBy(Space.M)) {
        points(topic).forEach { (title, body) ->
            Column(Modifier.semantics(mergeDescendants = true) { }) {
                Text(title, color = colors.textPrimary, style = AuroraType.BodyStrong)
                Text(body, Modifier.padding(top = 2.dp), color = colors.textSecondary, style = AuroraType.Secondary)
            }
        }
    }
}

/** A tab's info button opens this: what the tab offers, and the way to the whole guide. */
@Composable
fun GuideDialog(topic: GuideTopic, onDismiss: () -> Unit, onOpenGuide: (() -> Unit)?) {
    AuroraDialog(stringResource(topic.title), onDismiss, stringResource(R.string.settings_got_it), onDismiss) {
        Column(verticalArrangement = Arrangement.spacedBy(Space.L)) {
            Points(topic)
            if (onOpenGuide != null) AuroraButton(
                stringResource(R.string.guide_see_all), { onDismiss(); onOpenGuide() }, Modifier.fillMaxWidth(),
                kind = ButtonKind.Secondary, icon = AuroraIcons.Info,
            )
        }
    }
}

/** How Aurora works: every tab, explained in one place. */
@Composable
fun GuideScreen(onBack: () -> Unit) {
    val colors = Aurora.colors
    AuroraScreen(title = stringResource(R.string.guide_title), subtitle = stringResource(R.string.guide_subtitle), onBack = onBack) {
        item(key = "flow") {
            Column(Modifier.padding(horizontal = Space.Gutter).padding(bottom = Space.S).fillMaxWidth().auroraSurface(colors).padding(horizontal = 22.dp, vertical = 20.dp)) {
                Text(stringResource(R.string.guide_flow_title), Modifier.semantics { heading() }, color = colors.textPrimary, style = AuroraType.Title)
                Text(stringResource(R.string.guide_flow_body), Modifier.padding(top = Space.XS), color = colors.textSecondary, style = AuroraType.Secondary)
            }
        }
        GuideTopic.entries.forEach { topic ->
            item(key = "topic-" + topic.name) {
                Column(
                    Modifier.padding(horizontal = Space.Gutter).padding(bottom = Space.S).fillMaxWidth().auroraSurface(colors).padding(horizontal = 22.dp, vertical = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(Space.M),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.S)) {
                        Icon(topic.icon, null, tint = colors.accent, modifier = Modifier.size(20.dp))
                        Text(stringResource(topic.title), Modifier.semantics { heading() }, color = colors.textPrimary, style = AuroraType.Title)
                    }
                    Points(topic)
                }
            }
        }
    }
}
