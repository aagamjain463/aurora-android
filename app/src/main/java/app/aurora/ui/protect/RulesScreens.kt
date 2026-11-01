package app.aurora.ui.protect

import app.aurora.ui.components.auroraSurface
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.aurora.R
import app.aurora.auroraViewModel
import app.aurora.protect.retainedShield
import app.aurora.plan.Plan
import app.aurora.protect.Difficulty
import app.aurora.protect.FrictionSettings
import app.aurora.protect.FrictionTask
import app.aurora.protect.Group
import app.aurora.protect.Mode
import app.aurora.protect.Rules
import app.aurora.protect.Schedule
import app.aurora.protect.cover
import app.aurora.protect.covers
import app.aurora.protect.key
import app.aurora.protect.nextStart
import app.aurora.protect.windowAt
import app.aurora.ui.components.AuroraButton
import app.aurora.ui.components.AuroraDialog
import app.aurora.ui.components.AuroraIconButton
import app.aurora.ui.components.AuroraIcons
import app.aurora.ui.components.AuroraRow
import app.aurora.ui.components.AuroraScreen
import app.aurora.ui.components.AuroraSwitch
import app.aurora.ui.components.AuroraTextField
import app.aurora.ui.components.ButtonKind
import app.aurora.ui.components.Chevron
import app.aurora.ui.components.ChipFlow
import app.aurora.ui.components.ChoiceCard
import app.aurora.ui.components.Chip
import app.aurora.ui.components.DurationPicker
import app.aurora.ui.components.EmptyState
import app.aurora.ui.components.ModeGlyph
import app.aurora.ui.components.RowDivider
import app.aurora.ui.components.RowGroup
import app.aurora.ui.components.SectionLabel
import app.aurora.ui.components.SelectionMark
import app.aurora.ui.components.SkeletonRows
import app.aurora.ui.components.allowanceText
import app.aurora.ui.components.clockTime
import app.aurora.ui.components.minutesText
import app.aurora.ui.components.secondsText
import app.aurora.ui.theme.Aurora
import app.aurora.ui.theme.AuroraType
import app.aurora.ui.theme.Radius
import app.aurora.ui.theme.Space
import java.time.DayOfWeek
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle
import java.util.Locale
import java.util.UUID

private val Week = (1..7).toList()
private val Weekdays = setOf(1, 2, 3, 4, 5)

fun dayShort(day: Int): String = DayOfWeek.of(day).getDisplayName(TextStyle.SHORT, Locale.getDefault())

/** "9:00 AM", in the person's own clock format. */
fun minuteOfDay(minute: Int): String = LocalTime.of(minute / 60 % 24, minute % 60).format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT))

/** "Every weekday", "Every day", "Mon, Wed, Fri". */
@Composable
fun daysText(days: Set<Int>): String = when (days) {
    Week.toSet() -> stringResource(R.string.schedule_every_day)
    Weekdays -> stringResource(R.string.schedule_weekdays)
    setOf(6, 7) -> stringResource(R.string.schedule_weekends)
    else -> days.sorted().joinToString(", ") { dayShort(it) }
}

private enum class ScheduleStep { Name, Days, Times, Apps, Mode, Settings, Summary }

/** A time of day typed as hour and minute, with an AM/PM switch in 12-hour locales. */
@Composable
fun TimeEntry(label: String, minute: Int, onChange: (Int) -> Unit) {
    val colors = Aurora.colors
    val context = androidx.compose.ui.platform.LocalContext.current
    val h24 = android.text.format.DateFormat.is24HourFormat(context)
    val hourShown = if (h24) minute / 60 else (minute / 60 + 11) % 12 + 1
    var hourText by remember { mutableStateOf(hourShown.toString()) }
    var minText by remember { mutableStateOf("%02d".format(minute % 60)) }
    // Only a change from outside rewrites the fields: reformatting what's being typed would turn "5" into "05" mid-entry.
    LaunchedEffect(minute, h24) {
        if (hourText.toIntOrNull() != hourShown) hourText = hourShown.toString()
        if (minText.toIntOrNull() != minute % 60) minText = "%02d".format(minute % 60)
    }
    val pm = minute / 60 >= 12
    fun commit(h: String, m: String, isPm: Boolean = pm) {
        val hour = h.toIntOrNull() ?: return
        val min = m.toIntOrNull() ?: return
        if (min !in 0..59) return
        val h24value = if (h24) hour.takeIf { it in 0..23 } else hour.takeIf { it in 1..12 }?.let { it % 12 + if (isPm) 12 else 0 }
        h24value?.let { onChange(it * 60 + min) }
    }
    Column(verticalArrangement = Arrangement.spacedBy(Space.XS)) {
        Text(label, color = colors.textPrimary, style = AuroraType.Title)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.S)) {
            AuroraTextField(hourText, { hourText = it.filter(Char::isDigit).take(2); commit(hourText, minText) }, "9", Modifier.weight(1f),
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number))
            Text(":", color = colors.textPrimary, style = AuroraType.Headline)
            AuroraTextField(minText, { minText = it.filter(Char::isDigit).take(2); commit(hourText, minText) }, "00", Modifier.weight(1f),
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number))
            if (!h24) {
                Chip("AM", !pm) { commit(hourText, minText, isPm = false) }
                Chip("PM", pm) { commit(hourText, minText, isPm = true) }
            }
        }
        Text(minuteOfDay(minute), color = colors.textSecondary, style = AuroraType.Caption)
    }
}

/** Every daily budget in one place. Limits are measured from real app use, not from protection time. */
@Composable
fun LimitsScreen(onBack: () -> Unit, onOpenItem: (String) -> Unit, onOpenApps: () -> Unit) {
    val vm = auroraViewModel { ProtectModel(it) }
    val data by vm.data.collectAsStateWithLifecycle()
    val d = data
    AuroraScreen(title = stringResource(R.string.limits_name), subtitle = stringResource(R.string.limits_subtitle), onBack = onBack) {
        if (d == null) {
            item { SkeletonRows(3) }
            return@AuroraScreen
        }
        val retained = d.rules.retainedShield(d.plan, d.time.wall, java.time.ZoneId.systemDefault())
        val active = d.rules.activeLimits()
        val apps = d.list.apps.filter { it in d.activeKeys }
        if (apps.isEmpty()) {
            item(key = "none") {
                EmptyState(
                    icon = AuroraIcons.Hourglass,
                    title = stringResource(R.string.limits_empty_title),
                    body = stringResource(R.string.limits_empty_body),
                    action = { AuroraButton(stringResource(R.string.protect_add_apps), onOpenApps) },
                )
            }
            return@AuroraScreen
        }
        item(key = "list") {
            SectionLabel(stringResource(R.string.limits_count, allowanceText(active.size, null)))
            RowGroup {
                apps.forEachIndexed { i, pkg ->
                    if (i > 0) RowDivider(72.dp)
                    val limit = d.rules.limits.find { it.key == pkg && it.enabled }
                    AuroraRow(
                        title = d.label(pkg),
                        subtitle = when {
                            limit == null -> stringResource(R.string.limits_none)
                            retained != null && pkg !in retained -> stringResource(R.string.limits_paused, minutesText(limit.minutes))
                            else -> stringResource(R.string.limits_per_day, minutesText(limit.minutes))
                        },
                        leading = { ItemIcon(pkg, d) },
                        onClick = { onOpenItem(pkg) },
                        trailing = { Chevron() },
                    )
                }
            }
            Text(stringResource(R.string.limits_note), Modifier.padding(Space.Gutter), color = Aurora.colors.textTertiary, style = AuroraType.Caption)
        }
    }
}
