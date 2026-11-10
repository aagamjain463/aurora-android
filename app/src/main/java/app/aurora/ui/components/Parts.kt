package app.aurora.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.aurora.ui.theme.Aurora
import app.aurora.ui.theme.AuroraType
import app.aurora.ui.theme.Motion
import app.aurora.ui.theme.Radius
import app.aurora.ui.theme.Space
import app.aurora.ui.theme.auroraTween

/** Multi-select chips that wrap, e.g. weekdays or what the person wants more time for. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun <T> ChipFlow(
    options: List<T>,
    selected: Set<T>,
    onToggle: (T) -> Unit,
    label: (T) -> String,
    modifier: Modifier = Modifier,
    spokenLabel: (T) -> String = label,
) {
    val colors = Aurora.colors
    FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(Space.XS), verticalArrangement = Arrangement.spacedBy(Space.XS)) {
        options.forEach { option ->
            val on = option in selected
            // Same pill as [Chip]: outlined when off, the one filled tone when on.
            val fg by animateColorAsState(if (on) colors.onAccent else colors.textSecondary, auroraTween(), label = "chipText")
            val bg by animateColorAsState(if (on) colors.accent else Color.Transparent, auroraTween(), label = "chipFill")
            Box(
                Modifier
                    .heightIn(min = 40.dp)
                    .clip(Radius.Pill)
                    .then(if (on) Modifier.background(bg).glassShine(colors, Radius.Pill) else Modifier.glassCard(colors, Radius.Pill))
                    .toggleable(on, role = Role.Checkbox, onValueChange = { onToggle(option) })
                    .padding(horizontal = 16.dp, vertical = 10.dp)
                    .semantics { contentDescription = spokenLabel(option) },
                contentAlignment = Alignment.Center,
            ) { Text(label(option), color = fg, style = if (on) AuroraType.Choice.copy(fontWeight = FontWeight(600)) else AuroraType.Choice) }
        }
    }
}

/** One option of a single choice with an explanation, e.g. a protection mode. The whole row is the target. */
@Composable
fun RadioRow(title: String, subtitle: String?, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, badge: String? = null) {
    val colors = Aurora.colors
    Row(
        modifier
            .fillMaxWidth()
            .selectable(selected, role = Role.RadioButton, onClick = onClick)
            .heightIn(min = 64.dp)
            .padding(horizontal = Space.M, vertical = Space.S),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.M),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.XS)) {
                Text(title, color = colors.textPrimary, style = AuroraType.BodyStrong)
                if (badge != null) Tag(badge)
            }
            if (subtitle != null) Text(subtitle, color = colors.textSecondary, style = AuroraType.Secondary)
        }
        SelectionMark(selected)
    }
}

/** A small lowercase label, e.g. "plus", "experimental", "paused". Dawn-tinted: information, never alarm. */
@Composable
fun Tag(text: String, modifier: Modifier = Modifier, accent: Boolean = false) {
    val colors = Aurora.colors
    Text(
        text.lowercase(),
        modifier
            .glass(colors, Radius.Pill, tint = if (accent) colors.accent else colors.muted)
            .padding(horizontal = 8.dp, vertical = 2.dp),
        color = if (accent) colors.accent else colors.mutedText,
        style = AuroraType.Caption,
    )
}

/**
 * Numbers that belong together, in one quiet surface divided by hairlines rather than a card each.
 * [cells] are (value, label); a null value reads "—" and is announced as unavailable.
 */
@Composable
fun MetricGrid(cells: List<Pair<String?, String>>, modifier: Modifier = Modifier) {
    val colors = Aurora.colors
    RowGroup(modifier) {
        cells.chunked(2).forEachIndexed { i, pair ->
            if (i > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(colors.hairline))
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                pair.forEachIndexed { j, (value, label) ->
                    if (j > 0) Box(Modifier.width(1.dp).fillMaxHeight().background(colors.hairline))
                    Column(
                        Modifier
                            .weight(1f)
                            .padding(Space.M)
                            .semantics(mergeDescendants = true) { contentDescription = "$label, ${value ?: "not available"}" },
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Text(value ?: "—", color = if (value == null) colors.textTertiary else colors.textPrimary, style = AuroraType.Headline, maxLines = 1)
                        Text(label, color = colors.textSecondary, style = AuroraType.Caption, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
                if (pair.size == 1) Box(Modifier.weight(1f))
            }
        }
    }
}

/** A thin progress line in Aurora gold, filling toward its bright end. Warm (muted) for goals missed; never red. */
@Composable
fun ProgressLine(fraction: Float, modifier: Modifier = Modifier, warm: Boolean = false) {
    val colors = Aurora.colors
    val f by rememberFill(fraction.coerceIn(0f, 1f))
    Box(modifier.fillMaxWidth().height(4.dp).clip(Radius.Pill).background(colors.hairline)) {
        Box(Modifier.fillMaxWidth(f).height(4.dp).clip(Radius.Pill).then(if (warm) Modifier.background(colors.muted) else Modifier.background(androidx.compose.ui.graphics.Brush.horizontalGradient(listOf(colors.plus.copy(alpha = 0.55f), colors.plus)))))
    }
}

/** A round tinted icon, for list leads and explanations. */
@Composable
fun IconBadge(icon: ImageVector, modifier: Modifier = Modifier, tint: Color = Aurora.colors.accent, size: Int = 40) {
    Box(Modifier.then(modifier).size(size.dp).clip(Radius.Pill).background(tint.copy(alpha = 0.12f)), contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = tint, modifier = Modifier.size((size / 2).dp))
    }
}

/** Free/Plus allowance in words: "2 of 3" on Free, "2" on Plus. */
fun allowanceText(used: Int, max: Int?): String = if (max == null) "$used" else "$used of $max"

/** Lets a full-width element (a row group) reach past the [by] padding of the column it sits in. */
fun Modifier.bleed(by: Dp) = layout { measurable, constraints ->
    val px = by.roundToPx()
    val placeable = measurable.measure(constraints.copy(minWidth = constraints.minWidth + 2 * px, maxWidth = constraints.maxWidth + 2 * px))
    layout(placeable.width - 2 * px, placeable.height) { placeable.place(-px, 0) }
}

/**
 * A round badge holding an icon, lit with a faint ring of aurora light when [lit] (protection running,
 * a mode chosen). The Shield and the mode tiles use it, so "on" always looks the same.
 */
@Composable
fun LitBadge(icon: ImageVector, modifier: Modifier = Modifier, lit: Boolean = true, tint: Color = Aurora.colors.accent, size: Int = 56) {
    // The glyph in a round glass well.
    Box(modifier.size(size.dp).glassCard(Aurora.colors, Radius.Pill), contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = if (lit) tint else Aurora.colors.textSecondary, modifier = Modifier.size((size * 0.46f).dp))
    }
}

/** One figure and what it counts, for a row of stats in a card. */
@Composable
fun StatCell(value: String, label: String, modifier: Modifier = Modifier, tint: Color = Aurora.colors.textPrimary) {
    Column(modifier.padding(horizontal = Space.S).semantics(mergeDescendants = true) { }) {
        Text(value, color = tint, style = AuroraType.Title, maxLines = 1)
        Text(label, color = Aurora.colors.textSecondary, style = AuroraType.Caption)
    }
}

@Composable
fun StatDivider() {
    Box(Modifier.width(1.dp).height(44.dp).background(Aurora.colors.hairline))
}

/**
 * This week as seven points of light, Monday first. A recorded day with progress is lit; today wears a
 * ring; days still ahead are faint.
 */
@Composable
fun WeekDots(week: List<java.time.LocalDate>, today: java.time.LocalDate, active: (Int) -> Boolean, modifier: Modifier = Modifier) {
    val colors = Aurora.colors
    val locale = androidx.compose.ui.platform.LocalConfiguration.current.locales[0]
    val lit = week.indices.count { active(it) && !week[it].isAfter(today) }
    val spoken = androidx.compose.ui.res.pluralStringResource(app.aurora.R.plurals.week_dots_spoken, lit, lit)
    Row(modifier.semantics(mergeDescendants = true) { contentDescription = spoken }, horizontalArrangement = Arrangement.SpaceBetween) {
        week.forEachIndexed { i, day ->
            val on = active(i) && !day.isAfter(today)
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(
                    Modifier.size(14.dp)
                        .then(if (day == today) Modifier.border(1.5.dp, colors.accent.copy(alpha = 0.7f), Radius.Pill).padding(3.dp) else Modifier.padding(2.dp))
                        .clip(Radius.Pill)
                        .background(
                            when {
                                on -> colors.accent
                                day.isAfter(today) -> colors.hairline
                                else -> colors.textTertiary.copy(alpha = 0.35f)
                            },
                        ),
                )
                Text(
                    day.dayOfWeek.getDisplayName(java.time.format.TextStyle.NARROW, locale),
                    color = if (day == today) colors.accent else colors.textTertiary,
                    style = AuroraType.Caption,
                )
            }
        }
    }
}
