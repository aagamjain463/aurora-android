package app.aurora.ui.protect

import app.aurora.ui.components.auroraSurface
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.aurora.R
import app.aurora.auroraViewModel
import app.aurora.data.formatDuration
import app.aurora.plan.Allowance
import app.aurora.plan.ProtectedItem
import app.aurora.protect.IntentConfig
import app.aurora.protect.IntentExpiry
import app.aurora.protect.WebPrefix
import app.aurora.ui.components.AuroraButton
import app.aurora.ui.components.AuroraDialog
import app.aurora.ui.components.AuroraIcons
import app.aurora.ui.components.AuroraRow
import app.aurora.ui.components.AuroraScreen
import app.aurora.ui.components.AuroraTextField
import app.aurora.ui.components.ButtonKind
import app.aurora.ui.components.Chevron
import app.aurora.ui.components.ChoiceCard
import app.aurora.ui.components.Chip
import app.aurora.ui.components.DurationPicker
import app.aurora.ui.components.EmptyState
import app.aurora.ui.components.ProgressLine
import app.aurora.ui.components.RowDivider
import app.aurora.ui.components.RowGroup
import app.aurora.ui.components.SectionLabel
import app.aurora.ui.components.ToggleRow
import app.aurora.ui.components.allowanceText
import app.aurora.ui.components.clockTime
import app.aurora.ui.components.minutesText
import app.aurora.ui.components.secondsText
import app.aurora.ui.theme.Aurora
import app.aurora.ui.theme.AuroraType
import app.aurora.ui.theme.Radius
import app.aurora.ui.theme.Space

val ReflectionPresets = listOf(0, 5, 30)
val IntentCooldownPresets = listOf(5, 15, 30, 60)

@Composable
fun intentSummary(c: IntentConfig): String = listOf(
    if (c.reflectionSeconds == 0) stringResource(R.string.intent_no_pause) else stringResource(R.string.intent_pause_of, secondsText(c.reflectionSeconds)),
    when (c.expiry) {
        IntentExpiry.Reminder -> stringResource(R.string.intent_expiry_reminder)
        IntentExpiry.Cooldown -> stringResource(R.string.intent_expiry_cooldown_short, minutesText(c.cooldownMinutes))
    },
).joinToString(" · ")

/** Reflection pause: No pause, 5 s, 30 s, or a custom number of seconds typed below the chips. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SecondsPicker(value: Int, onChange: (Int) -> Unit) {
    var custom by rememberSaveable { mutableStateOf(value !in ReflectionPresets) }
    var text by rememberSaveable { mutableStateOf(if (value !in ReflectionPresets) value.toString() else "") }
    // One column with real gaps: the typed seconds never sit flush against the Custom chip.
    Column(verticalArrangement = Arrangement.spacedBy(Space.S)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.XS), verticalArrangement = Arrangement.spacedBy(Space.XS)) {
            ReflectionPresets.forEach { s -> Chip(secondsText(s), !custom && value == s) { custom = false; onChange(s) } }
            Chip(stringResource(R.string.duration_custom), custom) { custom = true }
        }
        if (custom) {
            val n = text.toIntOrNull()
            val valid = n != null && n in 1..600
            Column(Modifier.padding(top = Space.XS), verticalArrangement = Arrangement.spacedBy(Space.XS)) {
                AuroraTextField(
                    text, { text = it.filter(Char::isDigit).take(3); text.toIntOrNull()?.takeIf { s -> s in 1..600 }?.let(onChange) },
                    stringResource(R.string.intent_seconds_placeholder), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                Text(
                    if (valid || text.isEmpty()) stringResource(R.string.intent_seconds_hint) else stringResource(R.string.intent_seconds_problem),
                    Modifier.padding(start = 4.dp),
                    color = if (valid || text.isEmpty()) Aurora.colors.textTertiary else Aurora.colors.danger,
                    style = AuroraType.Caption,
                )
            }
        }
    }
}
