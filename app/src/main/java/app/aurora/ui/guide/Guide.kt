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
import app.aurora.ui.protect.modeIcon
import app.aurora.ui.components.LitBadge
import app.aurora.ui.components.RowDivider
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
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

private class Point(val title: String, val body: String, val icon: ImageVector? = null)

/** A topic told as: what it is, the parts in order, and how it starts. */
private class Section(val intro: Point?, val items: List<Point>, val numbered: Boolean = false, val note: Point? = null) {
    val all get() = listOfNotNull(intro) + items + listOfNotNull(note)
}

@Composable
private fun point(title: Int, body: Int, icon: ImageVector? = null) = Point(stringResource(title), stringResource(body), icon)

/** What [topic] offers. */
@Composable
private fun section(topic: GuideTopic): Section = when (topic) {
    GuideTopic.Home -> Section(
        intro = null,
        items = listOf(
            point(R.string.guide_home_start, R.string.guide_home_start_body, AuroraIcons.Play),
            point(R.string.guide_home_saved, R.string.guide_home_saved_body, AuroraIcons.Layers),
        ),
    )
    GuideTopic.Shield -> Section(
        intro = point(R.string.guide_shield_what, R.string.guide_shield_what_body),
        items = Mode.entries.map { Point(modeName(it), modeDescription(it), modeIcon(it)) },
        note = point(R.string.guide_shield_start, R.string.guide_shield_start_body),
    )
    GuideTopic.Intent -> Section(
        intro = point(R.string.guide_intent_what, R.string.guide_intent_what_body),
        items = listOf(
            point(R.string.intent_step_pause, R.string.intent_step_pause_body),
            point(R.string.intent_step_decide, R.string.intent_step_decide_body),
            point(R.string.intent_step_time, R.string.intent_step_time_body),
            point(R.string.intent_step_use, R.string.intent_step_use_body),
            point(R.string.intent_step_follow, R.string.intent_step_follow_body),
        ),
        numbered = true,
        note = point(R.string.guide_shield_start, R.string.guide_intent_start_body),
    )
    GuideTopic.Track -> Section(
        intro = null,
        items = listOf(
            point(R.string.guide_track_usage, R.string.guide_track_usage_body, AuroraIcons.Insights),
            point(R.string.guide_track_progress, R.string.guide_track_progress_body, AuroraIcons.Track),
            point(R.string.guide_track_goals, R.string.guide_track_goals_body, AuroraIcons.Target),
        ),
    )
}

@Composable
private fun Points(topic: GuideTopic) {
    val colors = Aurora.colors
    Column(verticalArrangement = Arrangement.spacedBy(Space.M)) {
        section(topic).all.forEach { p ->
            Column(Modifier.semantics(mergeDescendants = true) { }) {
                Text(p.title, color = colors.textPrimary, style = AuroraType.BodyStrong)
                Text(p.body, Modifier.padding(top = 2.dp), color = colors.textSecondary, style = AuroraType.Secondary)
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
    AuroraScreen(
        title = stringResource(R.string.guide_title),
        onBack = onBack,
        header = {
            Column(Modifier.padding(horizontal = Space.Gutter).padding(top = Space.XS, bottom = Space.L)) {
                Text(stringResource(R.string.guide_title), Modifier.semantics { heading() }, color = colors.textPrimary, style = AuroraType.DisplayXL)
                Text(stringResource(R.string.guide_subtitle), Modifier.padding(top = Space.XS), color = colors.textSecondary, style = AuroraType.Body)
            }
        },
    ) {
        item(key = "flow") { Flow() }
        GuideTopic.entries.forEach { topic ->
            item(key = "topic-" + topic.name) { Topic(topic) }
        }
    }
}

/** The whole app in one line: make Shields and Intents, start them from Home, see how it went in Track. */
@Composable
private fun Flow() {
    val colors = Aurora.colors
    Column(Modifier.padding(horizontal = Space.Gutter).fillMaxWidth().auroraSurface(colors).padding(horizontal = 20.dp, vertical = 22.dp)) {
        Text(stringResource(R.string.guide_flow_title), Modifier.semantics { heading() }, color = colors.textPrimary, style = AuroraType.Title)
        Row(Modifier.fillMaxWidth().padding(top = Space.L).clearAndSetSemantics { }, verticalAlignment = Alignment.Top) {
            listOf(GuideTopic.Shield, GuideTopic.Intent, GuideTopic.Home, GuideTopic.Track).forEachIndexed { i, t ->
                if (i > 0) Box(Modifier.weight(0.5f).padding(top = 22.dp).height(1.dp).background(colors.hairline))
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    LitBadge(t.icon, lit = t == GuideTopic.Home, size = 44)
                    Text(stringResource(t.title), Modifier.padding(top = Space.XS), color = colors.textSecondary, style = AuroraType.Caption)
                }
            }
        }
        Text(stringResource(R.string.guide_flow_body), Modifier.padding(top = Space.L), color = colors.textSecondary, style = AuroraType.Secondary)
    }
}

@Composable
private fun Topic(topic: GuideTopic) {
    val colors = Aurora.colors
    val s = section(topic)
    Column(Modifier.padding(top = Space.XL)) {
        Row(
            Modifier.padding(horizontal = Space.Gutter + 4.dp).padding(bottom = Space.S),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(topic.icon, null, tint = colors.accent, modifier = Modifier.size(20.dp))
            Text(stringResource(topic.title), Modifier.semantics { heading() }, color = colors.textPrimary, style = AuroraType.DisplayS)
        }
        if (s.intro != null) Text(
            s.intro.body,
            Modifier.padding(horizontal = Space.Gutter + 4.dp).padding(bottom = Space.M),
            color = colors.textSecondary, style = AuroraType.Body,
        )
        Column(Modifier.padding(horizontal = Space.Gutter).fillMaxWidth().auroraSurface(colors)) {
            s.items.forEachIndexed { i, p ->
                if (i > 0 && !s.numbered) RowDivider(68.dp)
                Item(p, number = if (s.numbered) i + 1 else null, last = i == s.items.lastIndex)
            }
        }
        if (s.note != null) Row(
            Modifier.padding(horizontal = Space.Gutter + 4.dp).padding(top = Space.S).semantics(mergeDescendants = true) { },
            horizontalArrangement = Arrangement.spacedBy(Space.XS),
        ) {
            Icon(AuroraIcons.Info, null, tint = colors.textTertiary, modifier = Modifier.padding(top = 1.dp).size(16.dp))
            Text(s.note.body, color = colors.textTertiary, style = AuroraType.Secondary)
        }
    }
}

/** One part of a topic: its glyph, or its step number on a thread to the next step. */
@Composable
private fun Item(p: Point, number: Int?, last: Boolean) {
    val colors = Aurora.colors
    Row(
        Modifier.fillMaxWidth().height(IntrinsicSize.Min).padding(horizontal = 18.dp).semantics(mergeDescendants = true) { },
        horizontalArrangement = Arrangement.spacedBy(Space.M),
    ) {
        Column(Modifier.fillMaxHeight().padding(top = if (number == null || number == 1) Space.M else 0.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            if (number != null && number > 1) Box(Modifier.width(1.dp).height(Space.M).background(colors.hairline))
            Box(
                Modifier.size(34.dp).clip(RoundedCornerShape(if (number != null) 50 else 30)).background(colors.textPrimary.copy(alpha = if (colors.isDark) 0.07f else 0.05f)),
                contentAlignment = Alignment.Center,
            ) {
                if (number != null) Text(number.toString(), color = colors.textPrimary, style = AuroraType.Caption.copy(fontWeight = FontWeight.Medium))
                else if (p.icon != null) Icon(p.icon, null, tint = colors.textPrimary, modifier = Modifier.size(17.dp))
            }
            if (number != null && !last) Box(Modifier.width(1.dp).weight(1f).background(colors.hairline))
        }
        Column(Modifier.weight(1f).padding(vertical = Space.M)) {
            Text(p.title, color = colors.textPrimary, style = AuroraType.BodyStrong)
            Text(p.body, Modifier.padding(top = 3.dp), color = colors.textSecondary, style = AuroraType.Secondary)
        }
    }
}
