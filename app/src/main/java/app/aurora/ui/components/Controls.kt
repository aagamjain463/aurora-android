package app.aurora.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import app.aurora.ui.theme.Aurora
import app.aurora.ui.theme.AuroraType
import app.aurora.ui.theme.Motion
import app.aurora.ui.theme.Radius
import app.aurora.ui.theme.Space
import app.aurora.ui.theme.auroraTween

/** Circular selection indicator for multi-select lists. Decorative: the row owns the semantics. */
@Composable
fun SelectionMark(selected: Boolean, modifier: Modifier = Modifier) {
    val colors = Aurora.colors
    val fill by animateColorAsState(if (selected) colors.accent else Color.Transparent, auroraTween(Motion.Quick), label = "fill")
    val checkAlpha by animateFloatAsState(if (selected) 1f else 0f, auroraTween(Motion.Quick), label = "check")
    Box(
        modifier
            .size(24.dp)
            .clip(Radius.Pill)
            .background(fill)
            .border(1.5.dp, if (selected) colors.accent else colors.textTertiary.copy(alpha = 0.6f), Radius.Pill),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            AuroraIcons.Check, null,
            tint = colors.onAccent,
            modifier = Modifier.size(16.dp).graphicsLayer { alpha = checkAlpha },
        )
    }
}

/**
 * Single-choice pills, e.g. focus lengths or theme. With [fill] the pills share the width equally;
 * otherwise they keep their natural width and scroll if they don't fit.
 */
@Composable
fun <T> ChoicePills(
    options: List<T>,
    selected: T,
    onSelect: (T) -> Unit,
    label: (T) -> String,
    modifier: Modifier = Modifier,
    fill: Boolean = false,
    spokenLabel: (T) -> String = label,
) {
    val colors = Aurora.colors
    Row(
        modifier
            .then(if (fill) Modifier.fillMaxWidth() else Modifier.horizontalScroll(rememberScrollState()))
            .selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(Space.XS),
    ) {
        options.forEach { option ->
            val isSelected = option == selected
            val bg by animateColorAsState(if (isSelected) colors.accentSoft else colors.surface, auroraTween(), label = "bg")
            val fg by animateColorAsState(if (isSelected) colors.accent else colors.textSecondary, auroraTween(), label = "fg")
            val stroke by animateColorAsState(if (isSelected) colors.accent.copy(alpha = 0.5f) else colors.hairline, auroraTween(), label = "stroke")
            Box(
                Modifier
                    .then(if (fill) Modifier.weight(1f) else Modifier)
                    .heightIn(min = 48.dp)
                    .clip(Radius.Pill)
                    .background(bg)
                    .border(1.dp, stroke, Radius.Pill)
                    .selectable(isSelected, role = Role.RadioButton, onClick = { onSelect(option) })
                    .padding(horizontal = if (fill) 4.dp else 20.dp)
                    .semantics { contentDescription = spokenLabel(option) },
                contentAlignment = Alignment.Center,
            ) {
                Text(label(option), Modifier.clearAndSetSemantics { }, color = fg, style = AuroraType.Label)
            }
        }
    }
}

@Composable
fun AuroraSwitch(checked: Boolean, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val colors = Aurora.colors
    // onCheckedChange = null: the enclosing row is the toggle, so the whole row is one target.
    Switch(
        checked = checked,
        onCheckedChange = null,
        modifier = modifier,
        enabled = enabled,
        colors = SwitchDefaults.colors(
            checkedThumbColor = colors.onAccent,
            checkedTrackColor = colors.accent,
            checkedBorderColor = colors.accent,
            uncheckedThumbColor = colors.textTertiary,
            uncheckedTrackColor = colors.background,
            uncheckedBorderColor = colors.textTertiary.copy(alpha = 0.5f),
        ),
    )
}

/** Pill text field. [onDone] fires on the keyboard action, after the keyboard is dismissed. */
@Composable
fun AuroraTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    imeAction: ImeAction = ImeAction.Done,
    capitalization: KeyboardCapitalization = KeyboardCapitalization.None,
    onDone: () -> Unit = {},
) {
    val colors = Aurora.colors
    val focus = LocalFocusManager.current
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth(),
        singleLine = true,
        textStyle = AuroraType.Body.copy(color = colors.textPrimary),
        cursorBrush = SolidColor(colors.accent),
        keyboardOptions = KeyboardOptions(imeAction = imeAction, capitalization = capitalization),
        keyboardActions = KeyboardActions(onAny = { focus.clearFocus(); onDone() }),
        decorationBox = { inner ->
            Row(
                Modifier
                    .heightIn(min = 52.dp)
                    .clip(Radius.Pill)
                    .background(colors.surface)
                    .border(1.dp, colors.hairline, Radius.Pill)
                    .padding(horizontal = 18.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Space.S),
            ) {
                if (icon != null) Icon(icon, null, tint = colors.textTertiary, modifier = Modifier.size(20.dp))
                Box(Modifier.weight(1f)) {
                    if (value.isEmpty()) Text(placeholder, color = colors.textTertiary, style = AuroraType.Body)
                    inner()
                }
            }
        },
    )
}
