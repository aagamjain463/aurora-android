package app.aurora.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
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
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInParent
import kotlinx.coroutines.launch
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
    val pop by animateFloatAsState(if (selected) 1f else 0.6f, if (Aurora.reducedMotion) snap() else spring(dampingRatio = 0.55f, stiffness = 500f), label = "pop")
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
            modifier = Modifier.size(16.dp).graphicsLayer { alpha = checkAlpha; scaleX = pop; scaleY = pop },
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
    /** One track holding all options, the chosen one lit inside it (Insights' period switch). */
    segmented: Boolean = false,
) {
    val colors = Aurora.colors
    val reduced = Aurora.reducedMotion
    val haptics = androidx.compose.ui.platform.LocalHapticFeedback.current
    // One lit pill slides between the options, stretching a little on the way, instead of each one blinking.
    val spots = remember { mutableStateMapOf<Int, Rect>() }
    val chosen = options.indexOf(selected)
    val spot = spots[chosen]
    val left = remember { Animatable(0f) }
    val right = remember { Animatable(0f) }
    var placed by remember { mutableStateOf(false) }
    LaunchedEffect(chosen, spot, reduced) {
        if (spot == null) return@LaunchedEffect
        if (!placed || reduced) {
            left.snapTo(spot.left)
            right.snapTo(spot.right)
            placed = true
        } else {
            val forward = spot.left > left.value
            launch { left.animateTo(spot.left, spring(dampingRatio = 0.86f, stiffness = if (forward) 260f else 520f)) }
            launch { right.animateTo(spot.right, spring(dampingRatio = 0.86f, stiffness = if (forward) 520f else 260f)) }
        }
    }
    val lit = colors.accentSoft
    val edge = colors.accent.copy(alpha = 0.5f)
    Row(
        modifier
            .then(if (fill) Modifier.fillMaxWidth() else Modifier.horizontalScroll(rememberScrollState()))
            .then(if (segmented) Modifier.glass(colors, Radius.Pill).padding(4.dp) else Modifier)
            .selectableGroup()
            .drawBehind {
                val stroke = androidx.compose.ui.graphics.drawscope.Stroke(1.dp.toPx())
                fun pill(l: Float, r: Float, h: Float, color: Color, line: Color) {
                    val corner = androidx.compose.ui.geometry.CornerRadius(h / 2f)
                    drawRoundRect(color, Offset(l, 0f), Size(r - l, h), corner)
                    drawRoundRect(line, Offset(l + 0.5f, 0.5f), Size(r - l - 1f, h - 1f), corner, style = stroke)
                }
                if (!segmented) spots.values.forEach { pill(it.left, it.right, it.height, colors.surface, colors.hairline) }
                if (placed && spot != null) pill(left.value, right.value, spot.height, lit, edge)
            },
        horizontalArrangement = Arrangement.spacedBy(if (segmented) 0.dp else Space.XS),
    ) {
        options.forEachIndexed { i, option ->
            val isSelected = option == selected
            val fg by animateColorAsState(if (isSelected) (if (segmented) colors.textPrimary else colors.accent) else colors.textSecondary, auroraTween(), label = "fg")
            Box(
                Modifier
                    .then(if (fill) Modifier.weight(1f) else Modifier)
                    .heightIn(min = 48.dp)
                    .onPlaced { c ->
                        val at = Rect(c.positionInParent(), Size(c.size.width.toFloat(), c.size.height.toFloat()))
                        if (spots[i] != at) spots[i] = at
                    }
                    .clip(Radius.Pill)
                    .selectable(isSelected, role = Role.RadioButton, onClick = {
                        if (!isSelected) haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.SegmentTick)
                        onSelect(option)
                    })
                    .padding(horizontal = if (fill) 4.dp else 20.dp)
                    .semantics { contentDescription = spokenLabel(option) },
                contentAlignment = Alignment.Center,
            ) {
                // Large text shrinks to fit a segment instead of breaking a word across two lines.
                BasicText(
                    label(option), Modifier.clearAndSetSemantics { }, style = AuroraType.Label.copy(color = fg), maxLines = 1,
                    autoSize = if (fill) TextAutoSize.StepBased(minFontSize = 9.sp, maxFontSize = AuroraType.Label.fontSize) else null,
                )
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
    keyboardOptions: KeyboardOptions = KeyboardOptions(imeAction = imeAction, capitalization = capitalization),
    singleLine: Boolean = true,
) {
    val colors = Aurora.colors
    val focus = LocalFocusManager.current
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth(),
        singleLine = singleLine,
        textStyle = AuroraType.Body.copy(color = colors.textPrimary),
        cursorBrush = SolidColor(colors.accent),
        keyboardOptions = keyboardOptions,
        keyboardActions = KeyboardActions(onAny = { focus.clearFocus(); onDone() }),
        decorationBox = { inner ->
            Row(
                Modifier
                    .heightIn(min = 52.dp)
                    .glass(colors, Radius.Pill)
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
