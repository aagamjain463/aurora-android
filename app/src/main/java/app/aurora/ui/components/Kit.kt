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
                Chip(minutesText(p), selected = !custom && value == p, spoken = minutesSpoken(p)) {
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
                Text(
                    problem?.text(min, max) ?: stringResource(R.string.duration_custom_hint, minutesText(min), minutesText(max)),
                    color = if (problem != null) colors.danger else colors.textTertiary,
                    style = AuroraType.Caption,
                )
            }
        }
        if (endsFromNow && value != null) {
            Text(
                stringResource(R.string.duration_ends_at, minutesText(value), clockTime(System.currentTimeMillis() + value * 60_000L)),
                color = colors.accent,
                style = AuroraType.BodyStrong,
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
            .glass(colors, Radius.M)
            .padding(horizontal = Space.M, vertical = Space.S),
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
    val bg by animateColorAsState(if (selected) colors.accent else colors.elevated, auroraTween(), label = "chip")
    val fg by animateColorAsState(if (selected) colors.onAccent else colors.textPrimary, auroraTween(), label = "chipText")
    Text(
        text,
        modifier
            .heightIn(min = 44.dp)
            .clip(Radius.Pill)
            .then(if (selected) Modifier.background(bg).gloss(Radius.Pill) else Modifier.glass(colors, Radius.Pill))
            .clickable(role = Role.RadioButton, onClick = onClick)
            .semantics { this.selected = selected; contentDescription = spoken }
            .padding(horizontal = 18.dp, vertical = 12.dp),
        color = fg,
        style = AuroraType.Label,
    )
}

/** The large opening of a screen: an eyebrow, a confident headline, and one supporting line. */
@Composable
fun ScreenIntro(title: String, modifier: Modifier = Modifier, eyebrow: String? = null, body: String? = null) {
    val colors = Aurora.colors
    Column(modifier.padding(horizontal = Space.Gutter), verticalArrangement = Arrangement.spacedBy(Space.XS)) {
        if (eyebrow != null) Text(eyebrow, color = colors.accent, style = AuroraType.Eyebrow)
        Text(title, Modifier.semantics { heading() }, color = colors.textPrimary, style = AuroraType.Display)
        if (body != null) Text(body, color = colors.textSecondary, style = AuroraType.Body)
    }
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
            .glass(colors, Radius.L, tint = if (selected) colors.accent else null)
            .semantics(mergeDescendants = true) { this.selected = selected }
            .padding(Space.M),
        horizontalArrangement = Arrangement.spacedBy(Space.M),
        verticalAlignment = Alignment.Top,
    ) {
        if (swatch != null) ModeGlyph(swatch, Modifier.padding(top = 2.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.XS)) {
                Text(title, color = colors.textPrimary, style = AuroraType.Title)
                if (badge != null) Tag(badge, accent = true)
            }
            Text(body, color = colors.textSecondary, style = AuroraType.Secondary)
        }
        SelectionMark(selected, Modifier.padding(top = 2.dp))
    }
}

/** A small luminous disc that marks a mode or a state in its colour. */
@Composable
fun ModeGlyph(color: Color, modifier: Modifier = Modifier, size: Int = 28) {
    Box(
        modifier
            .size(size.dp)
            .clip(RoundedCornerShape(50))
            .background(androidx.compose.ui.graphics.Brush.radialGradient(listOf(color, color.copy(alpha = 0.35f)))),
    )
}

/** A big number with its label: the one metric a section is about. */
@Composable
fun BigMetric(value: String, label: String, modifier: Modifier = Modifier, note: String? = null, spoken: String? = null) {
    val colors = Aurora.colors
    Column(modifier.semantics(mergeDescendants = true) { if (spoken != null) contentDescription = "$label: $spoken" }) {
        Text(label, color = colors.textSecondary, style = AuroraType.BodyStrong)
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
            .padding(horizontal = Space.M)
            .fillMaxWidth()
            .glass(colors, Radius.L)
            .then(if (tint != null) Modifier.background(tint) else Modifier)
            .padding(Space.L),
    ) { content() }
}

/** The transition between two product pillars (Protection, then Progress): space, a rule and a heading. */
@Composable
fun PillarHeading(title: String, body: String, modifier: Modifier = Modifier) {
    val colors = Aurora.colors
    Column(modifier.padding(start = Space.Gutter, end = Space.Gutter, top = Space.XXL, bottom = Space.M)) {
        Box(Modifier.width(40.dp).heightIn(min = 3.dp).clip(Radius.Pill).background(colors.violet))
        Spacer(Modifier.size(Space.M))
        Text(title, Modifier.semantics { heading() }, color = colors.textPrimary, style = AuroraType.Headline)
        Text(body, color = colors.textSecondary, style = AuroraType.Secondary, textAlign = TextAlign.Start)
    }
}
