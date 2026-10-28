package app.aurora.protect

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.aurora.ui.components.AuroraButton
import app.aurora.ui.components.AuroraIcons
import app.aurora.ui.components.AuroraMark
import app.aurora.ui.components.ButtonKind
import app.aurora.ui.components.ChoicePills
import app.aurora.ui.components.HorizonArc
import app.aurora.ui.components.HorizonGlow
import app.aurora.ui.components.StatusPill
import app.aurora.ui.components.Wordmark
import app.aurora.ui.home.clockTime
import app.aurora.ui.theme.Aurora
import app.aurora.ui.theme.AuroraType
import app.aurora.ui.theme.Motion
import app.aurora.ui.theme.Radius
import app.aurora.ui.theme.Space

/** Everything the Shield shows beyond the app itself. Built by ShieldActivity from the person's rules. */
data class ShieldInfo(
    val reason: ShieldReason,
    val until: Long? = null,
    val mode: Mode = Mode.Block,
    /** The person's own words, when Intent is on for this app. */
    val intent: IntentNote? = null,
    /** How long the pause before continuing lasts, and when it began (null: not started yet). */
    val pauseMs: Long = 0L,
    val pauseStart: Long? = null,
    val cooldownMinutes: Int = 0,
    val limitMinutes: Int? = null,
    val website: Boolean = false,
)

/** Minutes the person can choose at a Shield that offers a way through. */
val AccessChoices = listOf(5, 15, 30)

/**
 * The Shield: first light over a still horizon, why aurora stepped in, and the person's way forward.
 * No motion on the way in, so nothing delays the first frame. [label] is null while it loads; the layout
 * is already in place, so nothing moves when it arrives.
 *
 * Only [ShieldReason.Choice] offers a way through, and only after the pause the item asks for.
 */
@Composable
fun ShieldScreen(
    label: String?,
    icon: ImageBitmap?,
    info: ShieldInfo,
    now: Long,
    onClose: () -> Unit,
    onContinue: (minutes: Int) -> Unit = {},
) {
    val colors = Aurora.colors
    val name = label.orEmpty()
    val choice = info.reason.canContinue
    val pauseLeft = if (!choice || info.pauseMs <= 0) 0L
    else info.pauseStart?.let { (it + info.pauseMs - now).coerceIn(0L, info.pauseMs) } ?: info.pauseMs
    val ready = choice && pauseLeft == 0L && (info.pauseMs <= 0 || info.pauseStart != null)
    Box(
        Modifier
            .fillMaxSize()
            .background(colors.background)
            .semantics { paneTitle = "Shield" },
    ) {
        HorizonGlow(
            intensity = if (choice) 0.65f else 0.55f,
            warmth = if (info.intent != null) 0.85f else 0.7f,
            modifier = Modifier.fillMaxWidth().fillMaxHeight(0.42f).align(Alignment.BottomCenter),
        )
        BoxWithConstraints(Modifier.fillMaxSize().systemBarsPadding()) {
            // At least the screen's height, so the action sits low; scrolls when large text needs more.
            Column(
                Modifier
                    .verticalScroll(rememberScrollState())
                    .heightIn(min = maxHeight)
                    .fillMaxWidth()
                    .padding(horizontal = Space.Gutter, vertical = Space.L),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.XS)) {
                    AuroraMark(24.dp)
                    Wordmark()
                }
                Spacer(Modifier.weight(1f).heightIn(min = Space.XL))
                Column(
                    Modifier.widthIn(max = 480.dp).alpha(if (label == null) 0f else 1f),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(Modifier.size(72.dp).clip(RoundedCornerShape(20.dp)), contentAlignment = Alignment.Center) {
                        when {
                            icon != null -> Image(icon, contentDescription = null, modifier = Modifier.size(72.dp))
                            info.website -> Box(Modifier.size(72.dp).background(colors.accentSoft), contentAlignment = Alignment.Center) {
                                Icon(AuroraIcons.Globe, null, tint = colors.accent, modifier = Modifier.size(32.dp))
                            }
                        }
                    }
                    Spacer(Modifier.height(Space.L))
                    StatusPill(pill(info), live = false, warm = info.reason == ShieldReason.Limit || info.reason == ShieldReason.Cooldown)
                    Spacer(Modifier.height(Space.M))
                    Text(
                        headline(info, name),
                        Modifier.semantics { heading() },
                        color = colors.textPrimary,
                        style = AuroraType.Headline,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(Space.S))
                    Text(body(info, name), color = colors.textSecondary, style = AuroraType.Body, textAlign = TextAlign.Center)
                    info.intent?.let { IntentQuote(it) }
                    if (choice && info.pauseMs > 0) Pause(pauseLeft, info.pauseMs)
                }
                Spacer(Modifier.weight(1f).heightIn(min = Space.XL))
                Column(Modifier.widthIn(max = 480.dp).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    if (choice) {
                        AuroraButton(if (info.website) "Not now" else "Go back", onClose, Modifier.fillMaxWidth())
                        Spacer(Modifier.height(Space.M))
                        AnimatedContent(ready, transitionSpec = { fadeIn(tween(Motion.Gentle)) togetherWith fadeOut(tween(Motion.Quick)) }, label = "ready") { open ->
                            if (open) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(
                                        if (info.mode == Mode.Cooldown) "Or continue, then $name rests for ${info.cooldownMinutes} min" else "Or continue on purpose, for",
                                        color = colors.textTertiary,
                                        style = AuroraType.Caption,
                                        textAlign = TextAlign.Center,
                                    )
                                    Spacer(Modifier.height(Space.XS))
                                    ChoicePills(
                                        options = AccessChoices,
                                        selected = -1,
                                        onSelect = onContinue,
                                        fill = true,
                                        label = { "$it min" },
                                        spokenLabel = { "Continue to $name for $it minutes" },
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                }
                            } else {
                                Text(
                                    "You can choose to continue in a moment.",
                                    Modifier.heightIn(min = 72.dp),
                                    color = colors.textTertiary,
                                    style = AuroraType.Caption,
                                    textAlign = TextAlign.Center,
                                )
                            }
                        }
                    } else {
                        AuroraButton("Close ${if (info.website) "site" else "app"}", onClose, Modifier.fillMaxWidth())
                        Spacer(Modifier.height(Space.M))
                        Text(footnote(info), color = colors.textTertiary, style = AuroraType.Caption, textAlign = TextAlign.Center)
                    }
                }
            }
        }
    }
}

@Composable
private fun IntentQuote(note: IntentNote) {
    val colors = Aurora.colors
    Column(
        Modifier
            .padding(top = Space.L)
            .fillMaxWidth()
            .clip(Radius.L)
            .background(colors.dawn.copy(alpha = if (colors.isDark) 0.10f else 0.16f))
            .padding(Space.M)
            .semantics(mergeDescendants = true) { },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("your reason", color = colors.dawnText, style = AuroraType.Eyebrow)
        Spacer(Modifier.height(Space.XS))
        Text("“${note.reason}”", color = colors.textPrimary, style = AuroraType.Title, textAlign = TextAlign.Center)
        if (note.alternative.isNotBlank()) {
            Spacer(Modifier.height(Space.S))
            Text("Instead: ${note.alternative}", color = colors.textSecondary, style = AuroraType.Secondary, textAlign = TextAlign.Center)
        }
    }
}

/** The pause: light crosses the arc while the person takes a breath. Announced once, not every second. */
@Composable
private fun Pause(leftMs: Long, totalMs: Long) {
    val colors = Aurora.colors
    val seconds = ((leftMs + 999) / 1000).toInt()
    Box(Modifier.padding(top = Space.L).widthIn(max = 260.dp).fillMaxWidth(), contentAlignment = Alignment.BottomCenter) {
        HorizonArc(1f - leftMs.toFloat() / totalMs, Modifier.fillMaxWidth(), complete = leftMs == 0L)
        Column(
            Modifier
                .padding(bottom = Space.S)
                .semantics {
                    liveRegion = LiveRegionMode.Polite
                    contentDescription = if (seconds == 0) "You can choose now" else "Take a breath"
                },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (seconds == 0) Icon(AuroraIcons.Check, null, tint = colors.accent, modifier = Modifier.size(36.dp))
            else Text("$seconds", color = colors.textPrimary, style = AuroraType.Display)
            Text(if (seconds == 0) "your choice" else "take a breath", color = colors.textSecondary, style = AuroraType.Caption)
        }
    }
}

private fun pill(info: ShieldInfo): String = when (info.reason) {
    ShieldReason.Lockdown -> "Lockdown"
    ShieldReason.Schedule -> "Scheduled"
    ShieldReason.Focus -> "In Focus"
    ShieldReason.Limit -> "Daily limit"
    ShieldReason.Cooldown -> "Cooldown"
    ShieldReason.Blocked -> "Protected by Shield"
    ShieldReason.Choice -> when {
        info.intent != null -> "Intent"
        info.mode == Mode.Friction -> "A short pause"
        else -> "A moment to choose"
    }
}

private fun headline(info: ShieldInfo, name: String): String = when (info.reason) {
    ShieldReason.Lockdown -> "$name is locked"
    ShieldReason.Schedule -> "$name is resting"
    ShieldReason.Focus -> "You're in Focus"
    ShieldReason.Limit -> "That's today's time"
    ShieldReason.Cooldown -> "$name is cooling down"
    ShieldReason.Blocked -> "$name is protected"
    ShieldReason.Choice -> "Do you want to open $name?"
}

private fun body(info: ShieldInfo, name: String): String {
    val at = info.until?.let(::clockTime)
    return when (info.reason) {
        ShieldReason.Lockdown -> "You started a Lockdown${at?.let { " that ends at $it" }.orEmpty()}. It holds until then."
        ShieldReason.Schedule -> "Your schedule keeps $name closed${at?.let { " until $it" }.orEmpty()}."
        ShieldReason.Focus -> "$name waits until your session ends${at?.let { " at $it" }.orEmpty()}."
        ShieldReason.Limit -> "You've used your ${info.limitMinutes?.let { "$it minutes" } ?: "time"} of $name today. It opens again tomorrow."
        ShieldReason.Cooldown -> "You chose a gap between sessions.${at?.let { " $name opens again at $it." }.orEmpty()}"
        ShieldReason.Blocked -> "You chose to keep this out of reach. Take a breath, then go back to what you meant to do."
        ShieldReason.Choice -> when {
            info.intent != null -> "You asked aurora to remind you why, before $name opens."
            info.mode == Mode.Friction -> "A short pause before $name. If you still want it afterwards, it's your call."
            else -> "A moment to notice. If you meant to open it, that's your call."
        }
    }
}

private fun footnote(info: ShieldInfo): String = when (info.reason) {
    ShieldReason.Lockdown -> "A Lockdown can't be ended early in aurora. Android's own settings always stay open."
    ShieldReason.Focus -> "You can end Focus early in aurora. That's completely fine."
    ShieldReason.Limit -> "Daily limits reset at midnight."
    else -> "You can change what's protected in aurora."
}
