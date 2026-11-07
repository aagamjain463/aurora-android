package app.aurora.ui.components

import androidx.compose.ui.semantics.text
import androidx.compose.ui.semantics.clearAndSetSemantics
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
import androidx.compose.ui.unit.sp
import androidx.compose.ui.semantics.contentDescription
import app.aurora.data.InstalledApp
import app.aurora.ui.theme.Aurora
import app.aurora.ui.theme.AuroraType
import app.aurora.ui.theme.Radius
import app.aurora.ui.theme.Space

/** Small-capital, tracked section label ("MOST USED"). Screen readers hear the words as written, as a heading. */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier
            .padding(start = Space.Gutter + 4.dp, end = Space.Gutter, top = Space.L, bottom = 10.dp)
            .clearAndSetSemantics { heading(); this.text = androidx.compose.ui.text.AnnotatedString(text) },
        color = Aurora.colors.textTertiary,
        style = AuroraType.Eyebrow,
    )
}

/** Small-capital, tracked label inside a card or beside a control. Read aloud as written, not letter by letter. */
@Composable
fun Eyebrow(
    text: String,
    modifier: Modifier = Modifier,
    color: androidx.compose.ui.graphics.Color = Aurora.colors.textTertiary,
    textAlign: androidx.compose.ui.text.style.TextAlign? = null,
) {
    Text(
        text,
        modifier.semantics { contentDescription = text },
        color = color,
        style = AuroraType.Eyebrow,
        textAlign = textAlign,
    )
}

/** A soft surface holding related rows. Rows inside are separated by inset hairlines via [RowDivider]. */
@Composable
fun RowGroup(modifier: Modifier = Modifier, edge: androidx.compose.ui.graphics.Color? = null, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier
            .padding(horizontal = Space.Gutter)
            .fillMaxWidth()
            .auroraSurface(Aurora.colors, edge = edge),
        content = content,
    )
}

@Composable
fun RowDivider(inset: Dp = 0.dp) {
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
            .heightIn(min = 62.dp)
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .padding(horizontal = 18.dp, vertical = Space.S),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        when {
            leading != null -> leading()
            icon != null -> Icon(icon, null, tint = colors.textSecondary, modifier = Modifier.size(20.dp))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, color = colors.textPrimary, style = AuroraType.BodyStrong)
            if (subtitle != null) Text(subtitle, color = colors.textTertiary, style = AuroraType.Secondary.copy(fontSize = 12.5.sp, lineHeight = 17.sp))
        }
        trailing?.invoke(this)
    }
}

/**
 * How Aurora explains one thing: a tinted round icon, a short title and at most one quiet line, with an
 * optional value on the right. No padding of its own, so it sits in a [RowGroup] or a dialog alike.
 */
@Composable
fun FactRow(
    icon: ImageVector,
    title: String,
    modifier: Modifier = Modifier,
    body: String? = null,
    tint: androidx.compose.ui.graphics.Color = Aurora.colors.accent,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    val colors = Aurora.colors
    Row(
        modifier.fillMaxWidth().semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        IconBadge(icon, tint = tint, size = 36)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, color = colors.textPrimary, style = AuroraType.BodyStrong)
            if (body != null) Text(body, color = colors.textTertiary, style = AuroraType.Secondary.copy(fontSize = 12.5.sp, lineHeight = 17.sp))
        }
        trailing?.invoke(this)
    }
}

@Composable
fun Chevron() {
    Icon(AuroraIcons.Chevron, null, tint = Aurora.colors.textTertiary, modifier = Modifier.size(16.dp))
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
fun AppIcon(app: InstalledApp, modifier: Modifier = Modifier, size: Dp = 38.dp) {
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
                    .glass(colors, Radius.Pill, tint = colors.muted)
                    .padding(horizontal = 10.dp, vertical = 4.dp),
                color = colors.mutedText,
                style = AuroraType.Caption,
            )
        },
    )
}
