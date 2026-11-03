package app.aurora.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.aurora.ui.theme.Aurora
import app.aurora.ui.theme.AuroraType
import app.aurora.ui.theme.Motion
import app.aurora.ui.theme.Radius
import app.aurora.ui.theme.Space

/** A small state badge. [live] = something is actively happening (the dot breathes). */
@Composable
fun StatusPill(text: String, live: Boolean, modifier: Modifier = Modifier, warm: Boolean = false) {
    val colors = Aurora.colors
    val dotColor = when {
        warm -> colors.violet
        live -> colors.accent
        else -> colors.textTertiary
    }
    val pulse = if (live && !Aurora.reducedMotion) {
        rememberInfiniteTransition(label = "pulse").animateFloat(
            0.35f, 1f, infiniteRepeatable(tween(1600, easing = Motion.Ease), RepeatMode.Reverse), label = "pulse",
        ).value
    } else 1f
    Row(
        modifier
            .glass(colors, Radius.Pill)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.XS),
    ) {
        Box(Modifier.size(6.dp).graphicsLayer { alpha = pulse }.clip(Radius.Pill).background(dotColor))
        Text(text, color = colors.textSecondary, style = AuroraType.Caption)
    }
}

/** Centered message for "nothing here yet" and "needs your permission" states. */
@Composable
fun EmptyState(
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    action: (@Composable () -> Unit)? = null,
) {
    val colors = Aurora.colors
    Column(
        modifier
            .fillMaxWidth()
            .padding(horizontal = Space.Gutter, vertical = Space.XL),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (icon != null) {
            Box(
                Modifier.size(64.dp).clip(Radius.Pill).background(colors.accentSoft),
                contentAlignment = Alignment.Center,
            ) { Icon(icon, null, tint = colors.accent, modifier = Modifier.size(28.dp)) }
            Spacer(Modifier.height(Space.L))
        }
        Text(title, color = colors.textPrimary, style = AuroraType.Title, textAlign = TextAlign.Center)
        if (body.isNotEmpty()) {
            Spacer(Modifier.height(Space.XS))
            Text(body, color = colors.textSecondary, style = AuroraType.Secondary, textAlign = TextAlign.Center)
        }
        if (action != null) {
            Spacer(Modifier.height(Space.L))
            action()
        }
    }
}

/**
 * Honest status note, e.g. "saved, not enforced yet". Warm-tinted so it reads as information,
 * never as an error.
 */
@Composable
fun Notice(eyebrow: String, body: String, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    val colors = Aurora.colors
    Column(
        modifier
            .fillMaxWidth()
            .glass(colors, Radius.L)
            .padding(horizontal = 22.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(Space.M),
    ) {
        Column(Modifier.semantics(mergeDescendants = true) { }, verticalArrangement = Arrangement.spacedBy(Space.XS)) {
            Text(eyebrow, Modifier.semantics { heading() }, color = colors.textPrimary, style = AuroraType.Title)
            Text(body, color = colors.textSecondary, style = AuroraType.Secondary)
        }
        action?.invoke()
    }
}

/** Quiet loading placeholder for list rows. Pulses gently unless motion is reduced. */
@Composable
fun SkeletonRows(count: Int = 6) {
    val colors = Aurora.colors
    val alpha = if (Aurora.reducedMotion) 0.6f else rememberInfiniteTransition(label = "skeleton").animateFloat(
        0.35f, 0.8f, infiniteRepeatable(tween(900, easing = Motion.Ease), RepeatMode.Reverse), label = "skeleton",
    ).value
    Column(
        Modifier
            .padding(horizontal = Space.Gutter)
            .graphicsLayer { this.alpha = alpha }
            .semantics { paneTitle = "Loading" },
    ) {
        repeat(count) {
            Row(Modifier.padding(Space.M), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(40.dp).clip(Radius.S).background(colors.hairline))
                Spacer(Modifier.width(Space.M))
                Box(Modifier.height(14.dp).width((120 + (it * 37) % 90).dp).clip(Radius.Pill).background(colors.hairline))
            }
        }
    }
}

/**
 * aurora's dialog: one clear question, a confirm and an optional dismiss.
 * [content] slots custom controls between body and buttons.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AuroraDialog(
    title: String,
    onDismiss: () -> Unit,
    confirmLabel: String,
    onConfirm: () -> Unit,
    body: String? = null,
    dismissLabel: String? = null,
    confirmEnabled: Boolean = true,
    scrollContent: Boolean = true,
    content: (@Composable () -> Unit)? = null,
) {
    val colors = Aurora.colors
    val maxHeight = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.height.toDp() } - Space.XXL
    // The window spans the screen and the card sets its own margins: with the platform's wrap-content width a
    // weighted row of text fields (the duration boxes) made the dialog window re-layout every frame.
    BasicAlertDialog(onDismissRequest = onDismiss, properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
        // Native window blur, where the phone allows it: the page frosts behind the dialog.
        val view = androidx.compose.ui.platform.LocalView.current
        val blur = with(androidx.compose.ui.platform.LocalDensity.current) { 40.dp.roundToPx() }
        androidx.compose.runtime.SideEffect {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                (view.parent as? androidx.compose.ui.window.DialogWindowProvider)?.window?.let { w ->
                    w.addFlags(android.view.WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
                    w.attributes = w.attributes.apply { blurBehindRadius = blur }
                }
            }
        }
        Column(
            Modifier
                .padding(horizontal = Space.L)
                .fillMaxWidth()
                .heightIn(max = maxHeight.coerceAtLeast(0.dp))
                // Keep the message readable even when window blur is unavailable.
                .clip(Radius.Dialog)
                .background(colors.surface)
                .glass(colors, Radius.Dialog),
        ) {
            Column(
                Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())
                    .padding(horizontal = Space.L).padding(top = Space.L, bottom = Space.S),
            ) {
                Text(title, Modifier.semantics { heading() }, color = colors.textPrimary, style = AuroraType.Headline)
                if (body != null) {
                    Spacer(Modifier.height(Space.S))
                    Text(body, color = colors.textSecondary, style = AuroraType.Secondary)
                }
                if (content != null && scrollContent) {
                    Spacer(Modifier.height(Space.L))
                    content()
                }
            }
            // Controls that hold a weighted row (the duration boxes) sit outside the scroll: inside a bounded scroll
            // that row never settles its measure in a dialog window.
            if (content != null && !scrollContent) Column(Modifier.padding(horizontal = Space.L).padding(top = Space.S)) { content() }
            if (confirmLabel.isNotEmpty() || dismissLabel != null) {
                Column(
                    Modifier.padding(horizontal = Space.L).padding(top = Space.S, bottom = Space.L),
                    verticalArrangement = Arrangement.spacedBy(Space.XS),
                ) {
                    if (confirmLabel.isNotEmpty()) AuroraButton(confirmLabel, onConfirm, Modifier.fillMaxWidth(), enabled = confirmEnabled)
                    if (dismissLabel != null) AuroraButton(dismissLabel, onDismiss, Modifier.fillMaxWidth(), kind = ButtonKind.Quiet)
                }
            }
        }
    }
}
