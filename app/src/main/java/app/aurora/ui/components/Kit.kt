package app.aurora.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.aurora.R
import app.aurora.ui.theme.Aurora
import app.aurora.ui.theme.AuroraType
import app.aurora.ui.theme.Radius
import app.aurora.ui.theme.Space
import app.aurora.ui.theme.auroraTween
import java.util.Date

/** "45 min", "1 h", "1 h 15 min". The one way aurora writes a length of time it asked for. */
fun minutesText(minutes: Int): String {
    val h = minutes / 60
    val m = minutes % 60
    return when {
        h == 0 -> "$m min"
        m == 0 -> "$h h"
        else -> "$h h $m min"
    }
}

/** A length on a chip, as short as it reads: "5m", "1h", "1h 30m". Screen readers hear [minutesSpoken]. */
fun chipText(minutes: Int): String {
    val h = minutes / 60
    val m = minutes % 60
    return when {
        h == 0 -> "${m}m"
        m == 0 -> "${h}h"
        else -> "${h}h ${m}m"
    }
}

/** Screen-reader form of [minutesText]. */
fun minutesSpoken(minutes: Int): String {
    val h = minutes / 60
    val m = minutes % 60
    val parts = buildList {
        if (h > 0) add(if (h == 1) "1 hour" else "$h hours")
        if (m > 0 || h == 0) add(if (m == 1) "1 minute" else "$m minutes")
    }
    return parts.joinToString(" ")
}

/** Seconds as the pause pickers show them: "No pause", "15 s", "1 min", "1 min 30 s". */
fun secondsText(seconds: Int): String = when {
    seconds == 0 -> "No pause"
    seconds < 60 -> "$seconds s"
    seconds % 60 == 0 -> "${seconds / 60} min"
    else -> "${seconds / 60} min ${seconds % 60} s"
}

/** The local clock time [epochMs], e.g. "4:35 PM", in the person's own format. */
@Composable
fun clockTime(epochMs: Long): String {
    val context = LocalContext.current
    return remember(epochMs) { android.text.format.DateFormat.getTimeFormat(context).format(Date(epochMs)) }
}

enum class DurationProblem { Empty, NotWhole, MinutesOver59, TooShort, TooLong }

/**
 * Why a typed duration can't be used, or null when it can. Pure, so the rules are unit tested
 * (DurationTest): whole minutes only, at least [min], at most [max].
 */
fun durationProblem(hours: String, minutes: String, min: Int, max: Int): DurationProblem? {
    if (hours.isBlank() && minutes.isBlank()) return DurationProblem.Empty
    val h = hours.ifBlank { "0" }.toIntOrNull()
    val m = minutes.ifBlank { "0" }.toIntOrNull()
    if (h == null || m == null || h < 0 || m < 0) return DurationProblem.NotWhole
    if (m > 59) return DurationProblem.MinutesOver59
    val total = h * 60 + m
    if (total < min) return DurationProblem.TooShort
    if (total > max) return DurationProblem.TooLong
    return null
}

@Composable
private fun DurationProblem.text(min: Int, max: Int): String = when (this) {
    DurationProblem.Empty -> stringResource(R.string.duration_problem_empty)
    DurationProblem.NotWhole -> stringResource(R.string.duration_problem_whole)
    DurationProblem.MinutesOver59 -> stringResource(R.string.duration_problem_minutes)
    DurationProblem.TooShort -> stringResource(R.string.duration_problem_short, minutesText(min))
    DurationProblem.TooLong -> stringResource(R.string.duration_problem_long, minutesText(max))
}

/** Parses a duration typed as hours and minutes, or null if [durationProblem] finds one. */
fun parseDuration(hours: String, minutes: String, min: Int, max: Int): Int? =
    if (durationProblem(hours, minutes, min, max) != null) null
    else hours.ifBlank { "0" }.toInt() * 60 + minutes.ifBlank { "0" }.toInt()

/**
 * The one duration control in aurora: presets for the common answers, and always a custom entry of
 * hours and minutes, typed directly. A typed value is never rounded to a preset.
 *
 * [value] null means nothing chosen yet. [title] says exactly what this time is ("How long should
 * protection last?"), [hint] what it means. With [endsFromNow] the end time is shown, for durations that
 * start now.
 * [customInitially] opens the hours and minutes fields even when [value] matches a preset.
 * Empty [presets] shows only the hours and minutes fields.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DurationPicker(
    value: Int?,
    onChange: (Int?) -> Unit,
    presets: List<Int>,
    modifier: Modifier = Modifier,
    title: String? = null,
    hint: String? = null,
    min: Int = 1,
    max: Int = 24 * 60,
    endsFromNow: Boolean = false,
    customInitially: Boolean = false,
) {
    val colors = Aurora.colors
    var custom by rememberSaveable { mutableStateOf(presets.isEmpty() || customInitially || (value != null && value !in presets)) }
    var hours by rememberSaveable { mutableStateOf(if (custom && value != null) (value / 60).takeIf { it > 0 }?.toString().orEmpty() else "") }
    var minutes by rememberSaveable { mutableStateOf(if (custom && value != null) (value % 60).toString() else "") }
    val problem = if (custom && (hours.isNotEmpty() || minutes.isNotEmpty())) durationProblem(hours, minutes, min, max) else null

    Column(modifier, verticalArrangement = Arrangement.spacedBy(Space.S)) {
        if (title != null) Text(title, Modifier.semantics { heading() }, color = colors.textPrimary, style = AuroraType.Title)
        if (hint != null) Text(hint, color = colors.textSecondary, style = AuroraType.Secondary)
        if (presets.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.XS), verticalArrangement = Arrangement.spacedBy(Space.XS)) {
            presets.forEach { p ->
                Chip(chipText(p), selected = !custom && value == p, spoken = minutesSpoken(p)) {
                    custom = false
                    onChange(p)
                }
            }
            Chip(stringResource(R.string.duration_custom), selected = custom, spoken = stringResource(R.string.duration_custom)) {
                custom = true
                onChange(parseDuration(hours, minutes, min, max))
            }
        }
        AnimatedVisibility(custom) {
            Column(verticalArrangement = Arrangement.spacedBy(Space.XS)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.S)) {
                    NumberBox(hours, { hours = it.filter(Char::isDigit).take(3); onChange(parseDuration(hours, minutes, min, max)) }, stringResource(R.string.duration_hours), Modifier.weight(1f))
                    NumberBox(minutes, { minutes = it.filter(Char::isDigit).take(3); onChange(parseDuration(hours, minutes, min, max)) }, stringResource(R.string.duration_minutes), Modifier.weight(1f))
                }
                problem?.let { Text(it.text(min, max), color = colors.danger, style = AuroraType.Caption) }
            }
        }
        if (endsFromNow && value != null) {
            Text(
                stringResource(R.string.duration_ends_at, minutesText(value), clockTime(System.currentTimeMillis() + value * 60_000L)),
                color = colors.textSecondary,
                style = AuroraType.Body,
            )
        }
    }
}

/** A typed number with its unit under it, large enough to read and tap. */
@Composable
private fun NumberBox(text: String, onChange: (String) -> Unit, unit: String, modifier: Modifier = Modifier) {
    val colors = Aurora.colors
    Column(
        modifier
            .glassCard(colors, Radius.M, fill = colors.glassFill(field = true))
            .padding(horizontal = Space.Inner, vertical = Space.S),
    ) {
        BasicTextField(
            text,
            onChange,
            Modifier.fillMaxWidth().semantics { contentDescription = unit },
            textStyle = AuroraType.Headline.copy(color = colors.textPrimary),
            singleLine = true,
            cursorBrush = SolidColor(colors.accent),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
            decorationBox = { inner ->
                Box {
                    if (text.isEmpty()) Text("0", color = colors.textTertiary, style = AuroraType.Headline)
                    inner()
                }
            },
        )
        Text(unit, color = colors.textSecondary, style = AuroraType.Caption)
    }
}

/** A selectable chip: duration presets, task types, days. */
@Composable
fun Chip(text: String, selected: Boolean, modifier: Modifier = Modifier, spoken: String = text, onClick: () -> Unit) {
    val colors = Aurora.colors
    val fg by animateColorAsState(if (selected) colors.onAccent else colors.textSecondary, auroraTween(), label = "chipText")
    val bg by animateColorAsState(if (selected) colors.accent else Color.Transparent, auroraTween(), label = "chipFill")
    Box(
        modifier
            .heightIn(min = 40.dp)
            .clip(Radius.Pill)
            .then(if (selected) Modifier.background(bg).glassShine(colors, Radius.Pill) else Modifier.glassCard(colors, Radius.Pill))
            .clickable(role = Role.RadioButton, onClick = onClick)
            .semantics { this.selected = selected; contentDescription = spoken }
            .padding(horizontal = 16.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = fg, style = if (selected) AuroraType.Choice.copy(fontWeight = androidx.compose.ui.text.font.FontWeight(600)) else AuroraType.Choice)
    }
}

/** The large opening of a screen: an eyebrow, a confident headline, and one supporting line. */
@Composable
fun ScreenIntro(title: String, modifier: Modifier = Modifier, eyebrow: String? = null, body: String? = null) {
    val colors = Aurora.colors
    Column(modifier.padding(horizontal = Space.Gutter), verticalArrangement = Arrangement.spacedBy(Space.XS)) {
        if (eyebrow != null) Text(eyebrow, color = colors.textTertiary, style = AuroraType.Eyebrow)
        Text(title, Modifier.semantics { heading() }, color = colors.textPrimary, style = AuroraType.Display)
        if (body != null) Text(body, color = colors.textSecondary, style = AuroraType.Body)
    }
}

/** A mode's or an option's glyph in a round tile, as the mode cards show it. */
@Composable
fun GlyphTile(icon: androidx.compose.ui.graphics.vector.ImageVector, modifier: Modifier = Modifier, size: androidx.compose.ui.unit.Dp = 38.dp) {
    val colors = Aurora.colors
    Box(
        modifier.size(size).glassCard(colors, RoundedCornerShape(50)),
        contentAlignment = Alignment.Center,
    ) { androidx.compose.material3.Icon(icon, null, tint = colors.textPrimary, modifier = Modifier.size(18.dp)) }
}

/** The single-choice mark of the mode cards: a ring, filled with a dot when chosen. Decorative: the card owns the semantics. */
@Composable
fun RadioMark(selected: Boolean, modifier: Modifier = Modifier) {
    val colors = Aurora.colors
    Box(
        modifier.size(22.dp).border(1.5.dp, if (selected) colors.textPrimary else colors.hairlineStrong, RoundedCornerShape(50)),
        contentAlignment = Alignment.Center,
    ) { if (selected) Box(Modifier.size(11.dp).clip(RoundedCornerShape(50)).background(colors.textPrimary)) }
}

/**
 * A choice card with a title, a description and an optional accent swatch: Shield modes, task types,
 * expiry behaviours. The selected card is raised and outlined in the accent.
 */
@Composable
fun ChoiceCard(
    title: String,
    body: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    swatch: Color? = null,
    badge: String? = null,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
) {
    val colors = Aurora.colors
    val haptics = androidx.compose.ui.platform.LocalHapticFeedback.current
    Row(
        modifier
            .fillMaxWidth()
            .pressable(Radius.L, Role.RadioButton) {
                if (!selected) haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.SegmentTick)
                onClick()
            }
            .glassCard(colors, Radius.L, edge = false)
            .then(if (selected) Modifier.border(1.5.dp, colors.textPrimary, Radius.L) else Modifier)
            .semantics(mergeDescendants = true) { this.selected = selected }
            .padding(horizontal = 20.dp, vertical = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(Space.M),
        // A title alone sits centred against its mark; with a line under it, both hang from the top.
        verticalAlignment = if (body.isEmpty()) Alignment.CenterVertically else Alignment.Top,
    ) {
        if (icon != null) GlyphTile(icon) else if (swatch != null) ModeGlyph(swatch, Modifier.padding(top = 2.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.XS)) {
                Text(title, color = colors.textPrimary, style = AuroraType.Title)
                if (badge != null) Tag(badge, accent = true)
            }
            if (body.isNotEmpty()) Text(body, color = colors.textSecondary, style = AuroraType.Secondary)
        }
        RadioMark(selected, if (body.isEmpty()) Modifier else Modifier.padding(top = 8.dp))
    }
}

/** A small ring that marks a mode or a state in its tone. */
@Composable
fun ModeGlyph(color: Color, modifier: Modifier = Modifier, size: Int = 28) {
    Box(
        modifier
            .size(size.dp)
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.14f))
            .border(1.5.dp, color, RoundedCornerShape(50)),
    )
}

/** A big number with its label: the one metric a section is about. */
@Composable
fun BigMetric(value: String, label: String, modifier: Modifier = Modifier, note: String? = null, spoken: String? = null) {
    val colors = Aurora.colors
    Column(modifier.semantics(mergeDescendants = true) { if (spoken != null) contentDescription = "$label: $spoken" }) {
        Text(label, color = colors.textTertiary, style = AuroraType.Secondary)
        CountUpText(value, colors.textPrimary, AuroraType.Metric)
        if (note != null) Text(note, color = colors.textTertiary, style = AuroraType.Caption)
    }
}

/** A quiet card container with the brand's hairline, for grouped content that isn't a list of rows. */
@Composable
fun Card(modifier: Modifier = Modifier, tint: Color? = null, content: @Composable () -> Unit) {
    val colors = Aurora.colors
    Box(
        modifier
            .padding(horizontal = Space.Gutter)
            .fillMaxWidth()
            .glass(colors, Radius.L)
            .then(if (tint != null) Modifier.background(tint) else Modifier)
            .padding(horizontal = Space.Inner, vertical = 20.dp),
    ) { content() }
}

/** The transition between two product pillars (Protection, then Progress): space, a rule and a heading. */
@Composable
fun PillarHeading(title: String, body: String, modifier: Modifier = Modifier) {
    val colors = Aurora.colors
    Column(modifier.padding(start = Space.Gutter, end = Space.Gutter, top = Space.XXL, bottom = Space.M)) {
        Box(Modifier.width(40.dp).heightIn(min = 3.dp).clip(Radius.Pill).background(colors.muted))
        Spacer(Modifier.size(Space.M))
        Text(title, Modifier.semantics { heading() }, color = colors.textPrimary, style = AuroraType.Headline)
        Text(body, color = colors.textSecondary, style = AuroraType.Secondary, textAlign = TextAlign.Start)
    }
}
