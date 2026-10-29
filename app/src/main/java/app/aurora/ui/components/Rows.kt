package app.aurora.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.aurora.data.InstalledApp
import app.aurora.ui.theme.Aurora
import app.aurora.ui.theme.AuroraType
import app.aurora.ui.theme.Radius
import app.aurora.ui.theme.Space

/** Sentence-case, tracked section label. Marked as a heading for screen readers. */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text.replaceFirstChar { it.uppercase() },
        modifier
            .padding(start = Space.Gutter, end = Space.Gutter, top = Space.XL, bottom = Space.S)
            .semantics { heading() },
        color = Aurora.colors.textSecondary,
        style = AuroraType.Eyebrow,
    )
}

/** A soft surface holding related rows. Rows inside are separated by inset hairlines via [RowDivider]. */
@Composable
fun RowGroup(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier
            .padding(horizontal = Space.M)
            .fillMaxWidth()
            .clip(Radius.L)
            .background(Aurora.colors.surface)
            .border(1.dp, Aurora.colors.hairline, Radius.L),
        content = content,
    )
}

@Composable
fun RowDivider(inset: Dp = Space.M) {
    Box(
        Modifier
            .padding(start = inset)
            .fillMaxWidth()
            .height(1.dp)
            .background(Aurora.colors.hairline),
    )
}

/**
 * The workhorse row: optional leading icon, title, optional subtitle, optional trailing slot.
 * Clickable when [onClick] is set; otherwise purely informative.
 */
@Composable
fun AuroraRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    icon: ImageVector? = null,
    leading: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    val colors = Aurora.colors
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .padding(horizontal = Space.M, vertical = Space.S),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.M),
    ) {
        when {
            leading != null -> leading()
            icon != null -> Icon(icon, null, tint = colors.textSecondary, modifier = Modifier.size(24.dp))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, color = colors.textPrimary, style = AuroraType.BodyStrong)
            if (subtitle != null) Text(subtitle, color = colors.textSecondary, style = AuroraType.Secondary)
        }
        trailing?.invoke(this)
    }
}

@Composable
fun Chevron() {
    Icon(AuroraIcons.Chevron, null, tint = Aurora.colors.textTertiary, modifier = Modifier.size(20.dp))
}

/** Settings row with a switch; the whole row toggles. */
@Composable
fun ToggleRow(
    title: String,
    subtitle: String?,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    AuroraRow(
        title = title,
        subtitle = subtitle,
        modifier = modifier.toggleable(checked, enabled = enabled, role = Role.Switch, onValueChange = onCheckedChange),
        trailing = { AuroraSwitch(checked, enabled = enabled) },
    )
}

/** An installed app's icon at a consistent size and shape. */
@Composable
fun AppIcon(app: InstalledApp, modifier: Modifier = Modifier, size: Dp = 40.dp) {
    Image(app.icon, contentDescription = null, modifier = modifier.size(size).clip(RoundedCornerShape(size * 0.28f)))
}

/** Not-yet-available feature, shown honestly: dimmed, non-interactive, labelled "soon". */
@Composable
fun ComingSoonRow(title: String, subtitle: String, icon: ImageVector) {
    val colors = Aurora.colors
    AuroraRow(
        title = title,
        subtitle = subtitle,
        icon = icon,
        modifier = Modifier.semantics(mergeDescendants = true) { stateDescription = "Coming soon" },
        trailing = {
            Text(
                "soon",
                Modifier
                    .clip(Radius.Pill)
                    .background(colors.dawn.copy(alpha = 0.14f))
                    .padding(horizontal = 10.dp, vertical = 4.dp),
                color = colors.dawnText,
                style = AuroraType.Caption,
            )
        },
    )
}
