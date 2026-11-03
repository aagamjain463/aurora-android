package app.aurora.protect

import androidx.compose.ui.semantics.text
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.clickable
import androidx.compose.animation.core.animateFloat
import app.aurora.ui.components.RollingClock
import app.aurora.ui.components.rise
import app.aurora.ui.components.ArtTier
import app.aurora.ui.components.AuroraIcons
import app.aurora.ui.components.LitBadge
import app.aurora.ui.components.auroraSurface
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.aurora.AuroraApplication
import app.aurora.R
import app.aurora.cooldownWait
import app.aurora.data.formatClock
import app.aurora.data.ThemeMode
import app.aurora.finishIntent
import app.aurora.grantAccess
import app.aurora.intentOverrun
import app.aurora.record
import app.aurora.runsNow
import app.aurora.reflectionStart
import app.aurora.startIntent
import app.aurora.wentBack
import app.aurora.ui.components.AuroraButton
import app.aurora.ui.components.AuroraTextField
import app.aurora.ui.components.ButtonKind
import app.aurora.ui.components.Chip
import app.aurora.ui.components.DurationPicker
import app.aurora.ui.components.clockTime
import app.aurora.ui.components.minutesText
import app.aurora.ui.theme.Aurora
import app.aurora.ui.theme.AuroraTheme
import app.aurora.ui.theme.AuroraType
import app.aurora.ui.theme.Radius
import app.aurora.ui.theme.Space
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.IOException
import kotlin.random.Random
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import app.aurora.ui.components.ContentMaxWidth

/*
 * What the Shield overlay shows, for every reason (docs/SHIELD.md). One composable per moment; the
 * service decides which. Blocks offer only "Return home". Cooldown, Friction and Intent lead to timed
 * access through a real wait, a real task, or a deliberate decision, and the person always chooses how
 * long. Nothing here can lift a stronger restriction: the engine decided before this was shown.
 */

/** Usage presets after a task or a wait: the time the person may then spend in the app. */
val UsagePresets = listOf(5, 10, 15, 30)

@Composable
fun ShieldContent(request: ShieldRequest, close: (home: Boolean) -> Unit) {
    val app = LocalContext.current.applicationContext as AuroraApplication
    val prefs by app.preferences.preferences.collectAsStateWithLifecycle(null)
    // The Shield is always night: the same sky as Today's hero, whatever the theme. It reads as aurora at
    // once, and never flashes white over a dark app.
    AuroraTheme(reduceMotion = prefs?.reduceMotion == true, themeMode = prefs?.themeMode ?: ThemeMode.Dark) {
        val pkg = request.packageName
        val website = pkg.startsWith(WebPrefix)
        // A part of an app shows its own name over its app's icon.
        val part = partOf(pkg)
        val partName = part?.let { stringResource(it.name) }
        val label by produceState(if (website) pkg.removePrefix(WebPrefix) else partName.orEmpty(), pkg) {
            if (!website && part == null) value = app.catalog.label(pkg) ?: pkg
        }
        val icon by produceState<ImageBitmap?>(null, pkg) {
            if (!website) value = app.catalog.app(part?.owner ?: pkg)?.icon
        }
        val now by produceState(TrustedClock.wall()) {
            while (true) {
                value = TrustedClock.wall()
                delay(500)
            }
        }
        LaunchedEffect(request.eventUptime) {
            // Settings locked by a commitment isn't a protected app being opened.
            if (request.fresh && request.reason != ShieldReason.Locked) runCatching {
                app.record { s ->
                    s.copy(
                        shields = s.shields + 1,
                        intentPrompts = s.intentPrompts + if (request.reason == ShieldReason.Intent && request.decision?.timeUp != true) 1 else 0,
                        remindersShown = s.remindersShown + if (request.decision?.timeUp == true) 1 else 0,
                    )
                }
            }
        }
        // The person's own reason for the session behind this Shield, if they wrote one.
        val runtimeState by app.runtime.state.collectAsStateWithLifecycle()
        val rulesState by app.rules.state.collectAsStateWithLifecycle()
        val note = remember(runtimeState, rulesState, pkg) { sessionNote(app.runsNow(), pkg, app.now()) }
        val decision = request.decision ?: Decision.Shield(pkg, request.reason, request.until)
        val home = { close(true) }
        val intent = decision.reason == ShieldReason.Intent
        val leave = { app.scope.launch { runCatching { app.wentBack(intent = true) } }; home() }
        androidx.compose.runtime.CompositionLocalProvider(LocalSessionNote provides note, LocalLeave provides if (part != null) R.string.shield_go_back else R.string.shield_return_home) {
        ShieldFrame(label, icon, showName = website || part != null, onClose = if (intent) leave else null) {
            when (decision.reason) {
                ShieldReason.AlwaysBlock, ShieldReason.Flexible, ShieldReason.Focus, ShieldReason.Limit, ShieldReason.IntentCooldown, ShieldReason.Locked ->
                    Blocked(decision, label, now, home, onEnded = { close(false) })
                // Ended while on screen: nothing is left to wait for or solve.
                ShieldReason.Cooldown -> if (decision.over(now)) Blocked(decision, label, now, home, onEnded = { close(false) }) else CooldownFlow(app, decision, label, now, home, onOpen = { close(false) })
                ShieldReason.Friction -> if (decision.over(now)) Blocked(decision, label, now, home, onEnded = { close(false) }) else FrictionFlow(app, decision, label, home, onOpen = { close(false) })
                ShieldReason.Intent -> IntentFlow(app, decision, label, now, home, onOpen = { close(false) })
            }
        }
        }
    }
}

/** What leaving says: "Return home", or "Go back" for a part of an app, which keeps the rest of the app. */
private val LocalLeave = androidx.compose.runtime.staticCompositionLocalOf { R.string.shield_return_home }

/** The running session's note for this app, shown under every headline ([SessionNote]). */
private val LocalSessionNote = androidx.compose.runtime.staticCompositionLocalOf<String?> { null }

/** Set by [ShieldFrame]: Intentional App Opening reads left-aligned; every other moment is centred. */
private val LocalStartAligned = androidx.compose.runtime.staticCompositionLocalOf { false }

/**
 * The Shield's stage. A block is centred on the sky: the mark, the app, then the moment's words, with
 * the actions at the foot. Intentional App Opening ([onClose] set) is a page read from the top: the app and a
 * close button, then the question.
 */
@Composable
private fun ShieldFrame(label: String, icon: ImageBitmap?, showName: Boolean, onClose: (() -> Unit)? = null, content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    val colors = Aurora.colors
    val reduced = Aurora.reducedMotion
    val start = onClose != null
    val shown = androidx.compose.runtime.remember { androidx.compose.animation.core.Animatable(if (reduced) 1f else 0f) }
    androidx.compose.runtime.LaunchedEffect(Unit) { if (!reduced) shown.animateTo(1f, androidx.compose.animation.core.tween(app.aurora.ui.theme.Motion.Gentle, easing = app.aurora.ui.theme.Motion.Ease)) }
    val navInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    app.aurora.ui.components.ProvideIntro {
    androidx.compose.runtime.CompositionLocalProvider(LocalStartAligned provides start) {
    Box(Modifier.fillMaxSize().background(if (start) colors.background else colors.sky)) {
        androidx.compose.foundation.layout.BoxWithConstraints(
            Modifier
                .align(Alignment.TopCenter)
                .widthIn(max = ContentMaxWidth)
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .imePadding(),
        ) {
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .fillMaxWidth()
                .heightIn(min = maxHeight)
                .padding(horizontal = Space.Gutter)
                .graphicsLayer { alpha = shown.value; translationY = (1f - shown.value) * 12.dp.toPx() },
            horizontalAlignment = if (start) Alignment.Start else Alignment.CenterHorizontally,
        ) {
            if (start) {
                Row(Modifier.fillMaxWidth().height(60.dp), verticalAlignment = Alignment.CenterVertically) {
                    AppTile(label, icon, 44.dp, 13.dp)
                    Spacer(Modifier.weight(1f))
                    Box(
                        Modifier.size(42.dp).clip(CircleShape).background(colors.elevated)
                            .clickable(role = androidx.compose.ui.semantics.Role.Button, onClickLabel = stringResource(LocalLeave.current), onClick = onClose!!),
                        contentAlignment = Alignment.Center,
                    ) { androidx.compose.material3.Icon(AuroraIcons.Close, stringResource(LocalLeave.current), tint = colors.textPrimary, modifier = Modifier.size(18.dp)) }
                }
                if (showName) Text(label, Modifier.padding(top = Space.XS), color = colors.textSecondary, style = AuroraType.Secondary)
                Spacer(Modifier.height(22.dp))
                content()
                Spacer(Modifier.height((30.dp - navInset).coerceAtLeast(Space.M)))
            } else {
                app.aurora.ui.components.AuroraMark(26.dp, Modifier.padding(top = 34.dp), lit = true)
                Spacer(Modifier.weight(0.62f).heightIn(min = Space.L))
                AppTile(label, icon, 64.dp, 18.dp)
                // The icon names an app; a website has only its name.
                if (showName) Text(label, Modifier.padding(top = Space.S), color = colors.textSecondary, style = AuroraType.Secondary, textAlign = TextAlign.Center)
                Spacer(Modifier.height(22.dp))
                content()
                Spacer(Modifier.height((44.dp - navInset).coerceAtLeast(Space.M)))
            }
        }
        }
    }
    }
    }
}

/** The app in question: its icon, or a website's first letter, on a tile. */
@Composable
private fun AppTile(label: String, icon: ImageBitmap?, size: androidx.compose.ui.unit.Dp, corner: androidx.compose.ui.unit.Dp) {
    val colors = Aurora.colors
    val shape = RoundedCornerShape(corner)
    if (icon != null) {
        Image(icon, label, Modifier.size(size).clip(shape))
    } else {
        Box(Modifier.size(size).clip(shape).background(colors.elevated).border(1.dp, colors.hairline, shape), contentAlignment = Alignment.Center) {
            Text(label.firstOrNull()?.uppercase().orEmpty(), color = colors.textPrimary, style = AuroraType.Display.copy(fontSize = (size.value * 0.4f).sp))
        }
    }
}

/** When the moment ends: one quiet line under the headline, no pill and no icon. */
@Composable
private fun EndsPill(text: String) {
    Text(
        text, Modifier.fillMaxWidth().padding(top = 14.dp),
        color = Aurora.colors.textSecondary, style = AuroraType.Body, textAlign = TextAlign.Center,
    )
}

/** A small-capital label over a part of the Intent page ("YOUR REASON"). */
@Composable
private fun PartLabel(text: String) {
    Text(
        text.uppercase(), Modifier.padding(start = 4.dp, top = 26.dp, bottom = 10.dp).clearAndSetSemantics { heading(); this.text = androidx.compose.ui.text.AnnotatedString(text) },
        color = Aurora.colors.textTertiary, style = AuroraType.Eyebrow,
    )
}


/** The Shield's question: large and bold, centred on a block, left-aligned on the Intent page. */
@Composable
private fun Headline(text: String, highlight: String? = null) {
    val start = LocalStartAligned.current
    Text(
        text, Modifier.fillMaxWidth().semantics { heading() }, color = Aurora.colors.textPrimary,
        style = if (start) AuroraType.Display else AuroraType.Display.copy(fontSize = 42.sp, lineHeight = 46.sp),
        textAlign = if (start) TextAlign.Start else TextAlign.Center,
    )
    LocalSessionNote.current?.let { SessionNote(it) }
}

/** The reason the person wrote when starting the session, in their own words: the serif's italic, no card. */
@Composable
private fun SessionNote(text: String) {
    val colors = Aurora.colors
    val start = LocalStartAligned.current
    val label = stringResource(R.string.session_note_label)
    Text(
        "\u201C" + text + "\u201D",
        Modifier.padding(top = 26.dp).fillMaxWidth().semantics { contentDescription = "$label: $text" },
        color = colors.textPrimary,
        style = AuroraType.DisplayS.copy(fontSize = 22.sp, lineHeight = 28.sp, fontStyle = androidx.compose.ui.text.font.FontStyle.Italic),
        textAlign = if (start) TextAlign.Start else TextAlign.Center,
    )
}

@Composable
private fun Body(text: String) {
    val start = LocalStartAligned.current
    Text(
        text,
        // Centred as a block too, not just its lines: the column around it may align to the start.
        if (start) Modifier.fillMaxWidth().padding(top = 8.dp) else Modifier.fillMaxWidth().padding(top = 22.dp).wrapContentWidth().widthIn(max = 270.dp),
        color = Aurora.colors.textSecondary, style = AuroraType.Body, textAlign = if (start) TextAlign.Start else TextAlign.Center,
    )
}

/** The reflection pause: a small ring of light that closes as the seconds pass. */
@Composable
private fun PauseRing(leftMs: Long, totalMs: Long) {
    val colors = Aurora.colors
    val fraction = if (totalMs <= 0) 1f else (1f - leftMs.toFloat() / totalMs).coerceIn(0f, 1f)
    val seconds = ((leftMs + 999) / 1000).toInt().coerceAtLeast(0)
    Box(Modifier.padding(top = Space.L).size(88.dp), contentAlignment = Alignment.Center) {
        androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
            val stroke = 4.dp.toPx()
            val inset = stroke / 2 + 2.dp.toPx()
            val box = androidx.compose.ui.geometry.Size(size.width - 2 * inset, size.height - 2 * inset)
            val tl = androidx.compose.ui.geometry.Offset(inset, inset)
            drawArc(colors.hairline, 0f, 360f, false, tl, box, style = androidx.compose.ui.graphics.drawscope.Stroke(stroke))
            drawArc(colors.accent, -90f, 360f * (1f - fraction), false, tl, box,
                style = androidx.compose.ui.graphics.drawscope.Stroke(stroke, cap = androidx.compose.ui.graphics.StrokeCap.Round))
        }
        Text(if (seconds > 0) "${seconds}s" else "✓", Modifier.semantics { liveRegion = LiveRegionMode.Polite }, color = colors.textPrimary, style = AuroraType.Title)
    }
}

/** A live countdown with the clock time it ends. */
@Composable
private fun Countdown(until: Long, now: Long, caption: String) {
    val colors = Aurora.colors
    Column(Modifier.padding(top = Space.M, bottom = Space.S), horizontalAlignment = Alignment.CenterHorizontally) {
        RollingClock(formatClock(until - now), colors.textPrimary, AuroraType.Hero, Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        Text(caption, color = colors.textSecondary, style = AuroraType.Secondary, textAlign = TextAlign.Center)
    }
}

/** Both cooldowns restart from every new attempt to open the app; the Shield says so plainly. */
@Composable
private fun RestartNote() {
    Text(
        stringResource(R.string.shield_restart_note),
        Modifier.fillMaxWidth().padding(top = Space.XS).clip(Radius.Pill).background(Aurora.colors.violetSoft).padding(horizontal = Space.M, vertical = 10.dp),
        color = Aurora.colors.violetText,
        style = AuroraType.Label,
        textAlign = TextAlign.Center,
    )
}

/** The moment's actions, at the foot of the page. */
@Composable
private fun androidx.compose.foundation.layout.ColumnScope.Actions(content: @Composable () -> Unit) {
    Spacer(Modifier.weight(1f).heightIn(min = Space.L))
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) { content() }
}

// ---- Blocks: no way through -------------------------------------------------------------------

@Composable
private fun androidx.compose.foundation.layout.ColumnScope.Blocked(decision: Decision.Shield, label: String, now: Long, home: () -> Unit, onEnded: () -> Unit) {
    val app = LocalContext.current.applicationContext as AuroraApplication
    val runtime by app.runtime.state.collectAsStateWithLifecycle()
    // An Intent cooldown is read live: a new attempt restarts it, and the countdown follows.
    val until = if (decision.reason == ShieldReason.IntentCooldown) runtime?.value?.intentCooldowns?.get(decision.packageName) ?: decision.until else decision.until
    if (until != null && now >= until && decision.reason != ShieldReason.Limit) {
        Headline(stringResource(R.string.shield_ended_title))
        Body(stringResource(R.string.shield_ended_body, label))
        Actions {
            AuroraButton(stringResource(R.string.shield_open_app, label), onEnded, Modifier.fillMaxWidth())
            AuroraButton(stringResource(LocalLeave.current), home, Modifier.fillMaxWidth(), kind = ButtonKind.Quiet)
        }
        return
    }
    // Each block says what holds the app back, when that ends, and what the person can do about it.
    val ends = until?.let {
        when (decision.reason) {
            ShieldReason.AlwaysBlock -> stringResource(R.string.shield_always_block_ends, clockTime(it))
            ShieldReason.Limit -> stringResource(R.string.shield_limit_resets)
            ShieldReason.IntentCooldown -> stringResource(R.string.shield_opens_again_at, clockTime(it))
            else -> stringResource(R.string.shield_ends_at, clockTime(it))
        }
    }
    when (decision.reason) {
        ShieldReason.AlwaysBlock -> {
            Headline(stringResource(R.string.shield_blocked_title, label), label)
            ends?.let { EndsPill(it) }
        }
        ShieldReason.Flexible -> {
            Headline(stringResource(R.string.shield_blocked_title, label), label)
            ends?.let { EndsPill(it) }
        }
        ShieldReason.Focus -> {
            Headline(stringResource(R.string.shield_focus_title))
            ends?.let { EndsPill(it) }
            Body(stringResource(R.string.shield_focus_body, label))
        }
        ShieldReason.Locked -> {
            Headline(stringResource(R.string.shield_locked_title, label), label)
            ends?.let { EndsPill(it) }
            Body(stringResource(R.string.shield_locked_body))
        }
        ShieldReason.Limit -> {
            Headline(stringResource(R.string.shield_limit_title, label), label)
            ends?.let { EndsPill(it) }
        }
        else -> {
            Headline(stringResource(R.string.shield_intent_cooldown_title))
            ends?.let { EndsPill(it) }
            Body(stringResource(R.string.shield_intent_cooldown_body, label))
            RestartNote()
        }
    }
    Actions { AuroraButton(stringResource(LocalLeave.current), home, Modifier.fillMaxWidth()) }
}

// ---- Cooldown: wait first, then timed access ----------------------------------------------------

@Composable
private fun androidx.compose.foundation.layout.ColumnScope.CooldownFlow(app: AuroraApplication, decision: Decision.Shield, label: String, now: Long, home: () -> Unit, onOpen: () -> Unit) {
    val restriction = decision.restriction ?: return Blocked(decision, label, now, home, onOpen)
    // The wait is stored, and read live: a genuine new attempt restarts it (Cooldowns.kt), and the
    // countdown shows the restart at once. Showing or recreating the Shield never restarts it.
    val runtime by app.runtime.state.collectAsStateWithLifecycle()
    val stored = runtime?.value?.cooldownWaits?.get(decision.packageName)?.takeIf { it.restrictionId == restriction.id }?.readyAt
    LaunchedEffect(decision.packageName, restriction.id, stored == null) {
        if (stored == null) runCatching { app.cooldownWait(decision.packageName, restriction) }
    }
    val readyAt = stored ?: decision.readyAt
    val ready = readyAt?.let { now >= it } == true
    // The app's scope: going home closes this overlay, and what was just chosen must still be stored.
    val scope = app.scope
    val haptics = LocalHapticFeedback.current
    var failed by remember { mutableStateOf(false) }
    if (!ready) {
        Headline(stringResource(R.string.shield_cooldown_title, label), label)
        Body(stringResource(R.string.shield_cooldown_body))
        readyAt?.let { Countdown(it, now, stringResource(R.string.shield_cooldown_ready_at, clockTime(it))) }
        RestartNote()
        Actions {
            AuroraButton(stringResource(LocalLeave.current), {
                scope.launch { runCatching { app.wentBack(intent = false) } }
                home()
            }, Modifier.fillMaxWidth())
        }
    } else {
        Headline(stringResource(R.string.shield_cooldown_done_title))
        Body(stringResource(R.string.shield_cooldown_done_body))
        UsageChoice(label, decision, onBack = null) { minutes ->
            haptics.performHapticFeedback(HapticFeedbackType.Confirm)
            scope.launch {
                try {
                    app.grantAccess(decision.packageName, minutes, Mode.Cooldown)
                    onOpen()
                } catch (_: IOException) {
                    // Not stored, so not granted: the Shield stays, and says why.
                    failed = true
                }
            }
        }
        if (failed) SaveFailed()
        Actions { AuroraButton(stringResource(LocalLeave.current), home, Modifier.fillMaxWidth(), kind = ButtonKind.Quiet) }
    }
}

@Composable
private fun SaveFailed() {
    Text(stringResource(R.string.error_save_body), Modifier.fillMaxWidth().padding(top = Space.S), color = Aurora.colors.danger, style = AuroraType.Secondary, textAlign = TextAlign.Center)
}

/** "How long do you want to use {app}?": presets and a custom time, then open. */
@Composable
private fun androidx.compose.foundation.layout.ColumnScope.UsageChoice(label: String, decision: Decision.Shield, initial: Int? = null, onBack: (() -> Unit)?, onChoose: (Int) -> Unit) {
    var minutes by remember { mutableStateOf(initial) }
    // Never longer than the protection that's running: access can't outlast the restriction anyway.
    val max = decision.until?.let { ((it - TrustedClock.wall()) / 60_000).toInt().coerceIn(1, 24 * 60) } ?: (24 * 60)
    if (onBack != null) BackHandler(onBack = onBack)
    DurationPicker(
        minutes, { minutes = it }, UsagePresets.filter { it <= max }.ifEmpty { listOf(max) },
        Modifier.padding(top = Space.L),
        title = stringResource(R.string.usage_prompt, label),
        hint = stringResource(R.string.usage_hint),
        max = max,
    )
    Actions {
        AuroraButton(
            minutes?.let { stringResource(R.string.usage_open_for, label, minutesText(it)) } ?: stringResource(R.string.usage_choose_first),
            { minutes?.let(onChoose) },
            Modifier.fillMaxWidth(),
            enabled = minutes != null,
        )
    }
}

// ---- Friction: a real task, then timed access -----------------------------------------------------

private enum class FrictionStep { Task, Usage }

@Composable
private fun androidx.compose.foundation.layout.ColumnScope.FrictionFlow(app: AuroraApplication, decision: Decision.Shield, label: String, home: () -> Unit, onOpen: () -> Unit) {
    val settings = decision.restriction?.friction ?: FrictionSettings()
    var step by remember { mutableStateOf(FrictionStep.Task) }
    var failed by remember { mutableStateOf(false) }
    // The app's scope: going home closes this overlay, and what was just chosen must still be stored.
    val scope = app.scope
    val goHome = {
        scope.launch { runCatching { app.wentBack(intent = false) } }
        home()
    }
    AnimatedContent(step, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "friction") { s ->
        Column {
            when (s) {
                FrictionStep.Task -> {
                    Headline(stringResource(R.string.shield_friction_title))
                    Body(stringResource(R.string.mode_friction_desc))
                    val done = {
                        scope.launch { runCatching { app.record { it.copy(frictionDone = it.frictionDone + 1) } } }
                        step = FrictionStep.Usage
                    }
                    when (settings.task) {
                        FrictionTask.Math -> MathTask(settings.difficulty, onSolved = done, onMiss = { scope.launch { runCatching { app.record { it.copy(frictionMissed = it.frictionMissed + 1) } } } })
                        FrictionTask.Intention -> IntentionTask(label, onDone = done)
                        FrictionTask.Reflection -> ReflectionTask(app, decision.packageName, settings.reflectionSeconds, onDone = done)
                    }
                    Actions { AuroraButton(stringResource(LocalLeave.current), goHome, Modifier.fillMaxWidth(), kind = ButtonKind.Quiet) }
                }
                FrictionStep.Usage -> {
                    Headline(stringResource(R.string.shield_friction_done_title))
                    UsageChoice(label, decision, onBack = { step = FrictionStep.Task }) { minutes ->
                        scope.launch {
                            try {
                                app.grantAccess(decision.packageName, minutes, Mode.Friction)
                                onOpen()
                            } catch (_: IOException) {
                                // Not stored, so not granted.
                                failed = true
                            }
                        }
                    }
                    if (failed) SaveFailed()
                    Actions { AuroraButton(stringResource(LocalLeave.current), goHome, Modifier.fillMaxWidth(), kind = ButtonKind.Quiet) }
                }
            }
        }
    }
}

/** An arithmetic problem and its answer. Pure, so difficulty and correctness are unit tested (FrictionTest). */
data class MathProblem(val text: String, val answer: Int)

fun mathProblem(difficulty: Difficulty, random: Random = Random.Default): MathProblem = when (difficulty) {
    Difficulty.Easy -> {
        val a = random.nextInt(12, 60)
        val b = random.nextInt(12, 40)
        MathProblem("$a + $b", a + b)
    }
    Difficulty.Medium -> {
        val a = random.nextInt(12, 30)
        val b = random.nextInt(3, 9)
        val c = random.nextInt(11, 50)
        MathProblem("$a × $b + $c", a * b + c)
    }
    Difficulty.Hard -> {
        val a = random.nextInt(13, 40)
        val b = random.nextInt(12, 25)
        val c = random.nextInt(21, 99)
        MathProblem("$a × $b − $c", a * b - c)
    }
}

/** True only for the exact answer, typed as a whole number (spaces ignored). */
fun checkAnswer(problem: MathProblem, typed: String): Boolean = typed.trim().replace(" ", "").toIntOrNull() == problem.answer

@Composable
private fun MathTask(difficulty: Difficulty, onSolved: () -> Unit, onMiss: () -> Unit) {
    val colors = Aurora.colors
    var problem by remember { mutableStateOf(mathProblem(difficulty)) }
    var answer by remember { mutableStateOf("") }
    var misses by remember { mutableIntStateOf(0) }
    val haptics = LocalHapticFeedback.current
    val check = {
        if (answer.isNotBlank()) {
            if (checkAnswer(problem, answer)) {
                haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                onSolved()
            } else {
                haptics.performHapticFeedback(HapticFeedbackType.Reject)
                misses++
                onMiss()
                problem = mathProblem(difficulty)
                answer = ""
            }
        }
    }
    Column(Modifier.padding(top = Space.L), verticalArrangement = Arrangement.spacedBy(Space.S)) {
        Text(stringResource(R.string.friction_math_prompt), color = colors.textSecondary, style = AuroraType.BodyStrong)
        Text(
            "${problem.text} = ?",
            Modifier
                .fillMaxWidth()
                .clip(Radius.L)
                .background(colors.elevated)
                .border(1.dp, colors.hairline, Radius.L)
                .padding(Space.L),
            color = colors.textPrimary,
            style = AuroraType.Headline,
        )
        AuroraTextField(
            answer, { answer = it.filter { c -> c.isDigit() || c == '-' }.take(6) },
            placeholder = stringResource(R.string.friction_math_answer),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
            onDone = check,
        )
        if (misses > 0) {
            Text(stringResource(R.string.friction_math_wrong), Modifier.semantics { liveRegion = LiveRegionMode.Polite }, color = colors.violetText, style = AuroraType.Secondary)
        }
        AuroraButton(stringResource(R.string.friction_math_check), check, Modifier.fillMaxWidth(), enabled = answer.isNotBlank())
    }
}

/** A written intention: what the person means to do, read back to them before they go on. */
@Composable
private fun IntentionTask(label: String, onDone: () -> Unit) {
    val colors = Aurora.colors
    var text by remember { mutableStateOf("") }
    var confirming by remember { mutableStateOf(false) }
    // Two words or more: a reason, not a keystroke. No character counts to pad out.
    val meaningful = text.trim().split(Regex("\\s+")).count { it.length > 1 } >= 2
    Column(Modifier.padding(top = Space.L), verticalArrangement = Arrangement.spacedBy(Space.S)) {
        if (!confirming) {
            Text(stringResource(R.string.friction_intention_prompt, label), color = colors.textPrimary, style = AuroraType.Title)
            AuroraTextField(
                text, { text = it.take(200) },
                placeholder = stringResource(R.string.friction_intention_placeholder),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                singleLine = false,
            )
            AuroraButton(stringResource(R.string.friction_intention_next), { confirming = true }, Modifier.fillMaxWidth(), enabled = meaningful)
        } else {
            BackHandler { confirming = false }
            Text(stringResource(R.string.friction_intention_readback), color = colors.textSecondary, style = AuroraType.BodyStrong)
            Text(
                "“${text.trim()}”",
                Modifier.fillMaxWidth().clip(Radius.L).background(colors.elevated).padding(Space.L),
                color = colors.textPrimary,
                style = AuroraType.Title,
            )
            AuroraButton(stringResource(R.string.friction_intention_confirm), onDone, Modifier.fillMaxWidth())
            AuroraButton(stringResource(R.string.friction_intention_edit), { confirming = false }, Modifier.fillMaxWidth(), kind = ButtonKind.Quiet)
        }
    }
}

/** Two short decisions, then a pause the person waits out. The pause is stored, so leaving doesn't skip it. */
@Composable
private fun ReflectionTask(app: AuroraApplication, key: String, seconds: Int, onDone: () -> Unit) {
    val colors = Aurora.colors
    var step by remember { mutableIntStateOf(0) }
    val questions = listOf(
        stringResource(R.string.friction_reflect_q1) to listOf(
            stringResource(R.string.friction_reflect_q1_a1), stringResource(R.string.friction_reflect_q1_a2),
            stringResource(R.string.friction_reflect_q1_a3), stringResource(R.string.friction_reflect_q1_a4),
        ),
        stringResource(R.string.friction_reflect_q2) to listOf(stringResource(R.string.friction_reflect_q2_a1), stringResource(R.string.friction_reflect_q2_a2)),
    )
    Column(Modifier.padding(top = Space.L), verticalArrangement = Arrangement.spacedBy(Space.S)) {
        Text(stringResource(R.string.friction_reflect_step, (step + 1).coerceAtMost(3), 3), color = colors.textTertiary, style = AuroraType.Caption)
        if (step < questions.size) {
            if (step > 0) BackHandler { step-- }
            val (q, answers) = questions[step]
            Text(q, color = colors.textPrimary, style = AuroraType.Title)
            answers.forEach { a -> Chip(a, selected = false, modifier = Modifier.fillMaxWidth()) { step++ } }
        } else {
            val start by produceState<Long?>(null, key) { value = try { app.reflectionStart("friction:$key") } catch (_: IOException) { TrustedClock.wall() } }
            val now by produceState(TrustedClock.wall()) {
                while (true) {
                    value = TrustedClock.wall()
                    delay(250)
                }
            }
            val left = start?.let { it + seconds * 1000L - now } ?: (seconds * 1000L)
            Text(stringResource(R.string.friction_reflect_wait), color = colors.textPrimary, style = AuroraType.Title)
            Text(if (left > 0) formatClock(left) else stringResource(R.string.friction_reflect_ready), color = colors.accent, style = AuroraType.Hero)
            AuroraButton(stringResource(R.string.friction_reflect_continue), onDone, Modifier.fillMaxWidth(), enabled = left <= 0 && start != null)
        }
    }
}

// ---- Intentional App Opening --------------------------------------------------------------------

private enum class IntentStep { Reflect, Usage }

@Composable
private fun androidx.compose.foundation.layout.ColumnScope.IntentFlow(app: AuroraApplication, decision: Decision.Shield, label: String, now: Long, home: () -> Unit, onOpen: () -> Unit) {
    val colors = Aurora.colors
    val pkg = decision.packageName
    val rules by app.rules.state.collectAsStateWithLifecycle()
    val config = decision.intent ?: rules?.value?.apps?.get(pkg)?.intent ?: IntentConfig()
    val use = app.runtime.value?.intentUses?.get(pkg)
    // The app's scope: going home closes this overlay, and what was just chosen must still be stored.
    val scope = app.scope
    val haptics = LocalHapticFeedback.current
    var step by remember { mutableStateOf(IntentStep.Reflect) }
    val start = { minutes: Int ->
        haptics.performHapticFeedback(HapticFeedbackType.Confirm)
        scope.launch {
            try {
                // Refused during a post-usage cooldown: back home, and the next opening shows the cooldown.
                if (app.startIntent(pkg, minutes, config)) onOpen() else home()
            } catch (_: IOException) {
                // Not stored, so not started.
            }
        }
        Unit
    }

    if (decision.timeUp && step == IntentStep.Reflect) {
        val chosen = ((use?.allowanceMs ?: 0L) / 60_000).toInt()
        Headline(stringResource(R.string.intent_reminder_title, minutesText(chosen.coerceAtLeast(1))))
        Body(stringResource(R.string.intent_reminder_body, label))
        Actions {
            AuroraButton(stringResource(R.string.intent_reminder_finish), {
                scope.launch { runCatching { app.finishIntent(pkg) } }
                home()
            }, Modifier.fillMaxWidth())
            AuroraButton(stringResource(R.string.intent_reminder_continue), {
                scope.launch { runCatching { app.intentOverrun(pkg) } }
                step = IntentStep.Usage
            }, Modifier.fillMaxWidth(), kind = ButtonKind.Secondary)
        }
        return
    }

    when (step) {
        IntentStep.Reflect -> {
            val pauseStart by produceState<Long?>(null, pkg) {
                value = if (config.reflectionSeconds <= 0) 0L
                else try { app.reflectionStart(pkg) } catch (_: IOException) { TrustedClock.wall() }
            }
            val left = pauseStart?.let { it + config.reflectionSeconds * 1000L - now } ?: (config.reflectionSeconds * 1000L)
            var minutes by remember { mutableStateOf<Int?>(config.defaultUsageMinutes) }
            // Never longer than the protection that's running: access can't outlast it anyway.
            val max = decision.until?.let { ((it - TrustedClock.wall()) / 60_000).toInt().coerceIn(1, 24 * 60) } ?: (24 * 60)
            Headline(stringResource(R.string.intent_prompt_title, label), label)
            Body(stringResource(R.string.intent_prompt_question))
            if (config.reflectionSeconds > 0 && left > 0) PauseRing(left.coerceAtLeast(0), config.reflectionSeconds * 1000L)
            // The preset's reason, unless the session's note already says the same.
            if (config.reason.isNotBlank() && config.reason.trim() != LocalSessionNote.current?.trim()) {
                PartLabel(stringResource(R.string.intent_your_reason))
                Text(
                    config.reason,
                    Modifier.fillMaxWidth().heightIn(min = 54.dp).clip(RoundedCornerShape(18.dp)).background(colors.field)
                        .border(1.dp, colors.hairline, RoundedCornerShape(18.dp)).padding(horizontal = 18.dp, vertical = 16.dp),
                    color = colors.textPrimary, style = AuroraType.Body,
                )
            }
            PartLabel(stringResource(R.string.usage_prompt, label))
            DurationPicker(minutes, { minutes = it }, UsagePresets.filter { it <= max }.ifEmpty { listOf(max) }, Modifier.fillMaxWidth(), max = max)
            if (config.alternative.isNotBlank()) {
                PartLabel(stringResource(R.string.intent_instead))
                Row(
                    Modifier.fillMaxWidth().auroraSurface(colors, RoundedCornerShape(18.dp)).padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    androidx.compose.material3.Icon(AuroraIcons.Target, null, tint = colors.textPrimary, modifier = Modifier.size(18.dp))
                    Text(config.alternative, color = colors.textPrimary, style = AuroraType.BodyStrong.copy(fontSize = 14.5.sp))
                }
            }
            Text(
                when (config.expiry) {
                    IntentExpiry.Reminder -> stringResource(R.string.intent_expiry_note_reminder)
                    IntentExpiry.Cooldown -> stringResource(R.string.intent_expiry_note_cooldown, minutesText(config.cooldownMinutes))
                },
                Modifier.padding(top = Space.M, start = 4.dp),
                color = colors.textTertiary,
                style = AuroraType.Caption,
            )
            Actions {
                AuroraButton(
                    stringResource(R.string.intent_continue),
                    { minutes?.let(start) },
                    Modifier.fillMaxWidth(),
                    enabled = left <= 0 && pauseStart != null && minutes != null,
                )
                AuroraButton(stringResource(LocalLeave.current), {
                    scope.launch { runCatching { app.wentBack(intent = true) } }
                    home()
                }, Modifier.fillMaxWidth(), kind = ButtonKind.Quiet)
            }
        }
        IntentStep.Usage -> {
            UsageChoice(label, decision, initial = config.defaultUsageMinutes, onBack = { step = IntentStep.Reflect }, onChoose = start)
            Text(
                when (config.expiry) {
                    IntentExpiry.Reminder -> stringResource(R.string.intent_expiry_note_reminder)
                    IntentExpiry.Cooldown -> stringResource(R.string.intent_expiry_note_cooldown, minutesText(config.cooldownMinutes))
                },
                Modifier.padding(top = Space.S),
                color = colors.textTertiary,
                style = AuroraType.Caption,
            )
        }
    }
}

/** The person's own words, shown back to them: their reason (aurora light) or a better choice (sun). */
@Composable
private fun ReasonCard(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, text: String, warm: Boolean) {
    val colors = Aurora.colors
    Row(
        Modifier.padding(top = Space.M).fillMaxWidth().auroraSurface(colors, warm = warm).padding(Space.M).semantics(mergeDescendants = true) { },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.M),
    ) {
        LitBadge(icon, tint = if (warm) colors.rose else colors.accent, size = 52)
        Column(Modifier.weight(1f)) {
            Text(label, color = colors.textSecondary, style = AuroraType.Caption)
            Text(text, color = colors.textPrimary, style = AuroraType.Title)
        }
    }
}
