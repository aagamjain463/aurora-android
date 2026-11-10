package app.aurora.protect

import app.aurora.ui.components.glassCard
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
import app.aurora.MainActivity
import app.aurora.ui.components.AuroraMark
import app.aurora.R
import app.aurora.data.formatClock
import app.aurora.data.ThemeMode
import app.aurora.finishIntent
import app.aurora.intentOverrun
import app.aurora.record
import app.aurora.runsNow
import app.aurora.startIntent
import app.aurora.wentBack
import app.aurora.ui.components.AuroraButton
import app.aurora.ui.components.AuroraTextField
import app.aurora.ui.components.ButtonKind
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
fun ShieldContent(request: ShieldRequest, close: (ShieldClose) -> Unit) {
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
        val home = { close(ShieldClose.Home) }
        val granted = { close(ShieldClose.Granted) }
        val aurora = { close(ShieldClose.Aurora) }
        // Intent belongs to its session: when the session ends, its pause and cooldown leave the screen with it.
        val intentOver by produceState(false, pkg, decision.reason) {
            if (decision.reason != ShieldReason.Intent && decision.reason != ShieldReason.IntentCooldown) return@produceState
            while (true) {
                value = app.policySnapshot(emptySet(), emptyMap())?.let { pkg !in it.intents } == true
                delay(1000)
            }
        }
        val intent = decision.reason == ShieldReason.Intent
        val leave = { app.scope.launch { runCatching { app.wentBack(intent = true) } }; home() }
        androidx.compose.runtime.CompositionLocalProvider(LocalSessionNote provides note, LocalLeave provides if (part != null) R.string.shield_go_back else R.string.shield_return_home) {
        ShieldFrame(label, icon, showName = website || part != null, onClose = if (intent) leave else null) {
            when {
                intentOver -> Ended(label, home, onEnded = granted)
                // Friction and Cooldown are ended in Aurora, for the whole session; the Shield only says where.
                decision.reason == ShieldReason.Intent -> IntentFlow(app, decision, label, now, home, onOpen = granted)
                else -> Blocked(decision, label, now, home, onEnded = granted, onAurora = aurora)
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
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .imePadding(),
        ) {
        // A phone on its side: two panes, so every moment fits on one screen without scrolling.
        if (maxWidth > maxHeight && maxHeight < 520.dp) {
            Landscape(label, icon, showName, onClose, maxHeight, shown.value, content)
            return@BoxWithConstraints
        }
        Column(
            Modifier
                .align(Alignment.TopCenter)
                .widthIn(max = ContentMaxWidth)
                .verticalScroll(rememberScrollState())
                .fillMaxWidth()
                .heightIn(min = maxHeight)
                .padding(horizontal = Space.Gutter)
                .graphicsLayer { alpha = shown.value; translationY = (1f - shown.value) * 12.dp.toPx() },
            horizontalAlignment = if (start) Alignment.Start else Alignment.CenterHorizontally,
        ) {
            if (start) {
                Row(Modifier.fillMaxWidth().height(60.dp), verticalAlignment = Alignment.CenterVertically) {
                    AppTile(label, icon, 52.dp, 15.dp)
                    Spacer(Modifier.weight(1f))
                    Box(
                        Modifier.size(42.dp).glassCard(colors, CircleShape)
                            .clickable(role = androidx.compose.ui.semantics.Role.Button, onClickLabel = stringResource(LocalLeave.current), onClick = onClose!!),
                        contentAlignment = Alignment.Center,
                    ) { androidx.compose.material3.Icon(AuroraIcons.Close, stringResource(LocalLeave.current), tint = colors.textPrimary, modifier = Modifier.size(18.dp)) }
                }
                if (showName) Text(label, Modifier.padding(top = Space.XS), color = colors.textSecondary, style = AuroraType.Secondary)
                Spacer(Modifier.height(22.dp))
                content()
                Spacer(Modifier.height((30.dp - navInset).coerceAtLeast(Space.M)))
            } else {
                app.aurora.ui.components.AuroraMark(26.dp, Modifier.padding(top = 34.dp))
                Spacer(Modifier.weight(0.5f).heightIn(min = Space.L))
                // The app held at the centre of the seal: Aurora's instrument, the brand in every block.
                // Large text needs the room: the seal steps down so the words stay on the first screen.
                val roomy = androidx.compose.ui.platform.LocalDensity.current.fontScale <= 1.3f
                app.aurora.ui.components.Seal(if (roomy) 172.dp else 132.dp) { AppTile(label, icon, if (roomy) 68.dp else 52.dp, if (roomy) 19.dp else 15.dp) }
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

/** Set in landscape: the words step down a size and the actions sit side by side, so nothing has to scroll. */
private val LocalLandscape = androidx.compose.runtime.staticCompositionLocalOf { false }

/** In landscape the reflection pause is drawn round the app on the left ([PauseRing] hands it over): left, total. */
private val LocalPause = androidx.compose.runtime.staticCompositionLocalOf<androidx.compose.runtime.MutableState<Pair<Long, Long>?>?> { null }

/**
 * The Shield on a phone on its side. The left pane holds who this is about (the seal and the app, or on the Intent
 * page the app, its pause ring and the close button); the right pane holds the moment, its actions at the foot.
 * Sized to fit the height, so it never scrolls; only an open keyboard can push it.
 */
@Composable
private fun Landscape(
    label: String, icon: ImageBitmap?, showName: Boolean, onClose: (() -> Unit)?, height: androidx.compose.ui.unit.Dp, shown: Float,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    val colors = Aurora.colors
    val start = onClose != null
    val pause = remember { mutableStateOf<Pair<Long, Long>?>(null) }
    androidx.compose.runtime.CompositionLocalProvider(LocalLandscape provides true, LocalPause provides pause) {
        Row(
            Modifier.fillMaxSize().padding(horizontal = Space.Gutter)
                .graphicsLayer { alpha = shown; translationY = (1f - shown) * 12.dp.toPx() },
            horizontalArrangement = Arrangement.spacedBy(Space.XL),
        ) {
            Box(Modifier.weight(0.4f).fillMaxSize()) {
                if (start) Box(
                    Modifier.padding(top = Space.M).size(42.dp).glassCard(colors, CircleShape)
                        .clickable(role = androidx.compose.ui.semantics.Role.Button, onClickLabel = stringResource(LocalLeave.current), onClick = onClose!!),
                    contentAlignment = Alignment.Center,
                ) { androidx.compose.material3.Icon(AuroraIcons.Close, stringResource(LocalLeave.current), tint = colors.textPrimary, modifier = Modifier.size(18.dp)) }
                else app.aurora.ui.components.AuroraMark(22.dp, Modifier.align(Alignment.TopCenter).padding(top = Space.M))
                Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                    val seal = if (height < 360.dp) 128.dp else 152.dp
                    if (start) Box(Modifier.size(seal), contentAlignment = Alignment.Center) {
                        pause.value?.let { (left, total) -> Ring(if (total <= 0) 1f else (1f - left.toFloat() / total).coerceIn(0f, 1f), Modifier.fillMaxSize()) }
                        AppTile(label, icon, 64.dp, 18.dp)
                    } else app.aurora.ui.components.Seal(seal) { AppTile(label, icon, 56.dp, 16.dp) }
                    val seconds = pause.value?.let { ((it.first + 999) / 1000).toInt() }?.takeIf { it > 0 }
                    when {
                        seconds != null -> Text("${seconds}s", Modifier.padding(top = Space.S).semantics { liveRegion = LiveRegionMode.Polite }, color = colors.textPrimary, style = AuroraType.Title)
                        showName -> Text(label, Modifier.padding(top = Space.S), color = colors.textSecondary, style = AuroraType.Secondary, textAlign = TextAlign.Center)
                    }
                }
            }
            Column(
                Modifier.weight(0.6f).widthIn(max = 520.dp).verticalScroll(rememberScrollState()).heightIn(min = height)
                    .padding(top = Space.L, bottom = Space.M),
                horizontalAlignment = if (start) Alignment.Start else Alignment.CenterHorizontally,
            ) {
                // A block's few words sit in the middle of the pane, its actions at the foot.
                if (!start) Spacer(Modifier.weight(1f))
                content()
            }
        }
    }
}

/** The pause's ring of light, closing as [fraction] goes from 0 to 1. */
@Composable
private fun Ring(fraction: Float, modifier: Modifier) {
    val colors = Aurora.colors
    androidx.compose.foundation.Canvas(modifier) {
        val stroke = 4.dp.toPx()
        val inset = stroke / 2 + 2.dp.toPx()
        val box = androidx.compose.ui.geometry.Size(size.width - 2 * inset, size.height - 2 * inset)
        val tl = androidx.compose.ui.geometry.Offset(inset, inset)
        drawArc(colors.hairline, 0f, 360f, false, tl, box, style = androidx.compose.ui.graphics.drawscope.Stroke(stroke))
        drawArc(colors.accent, -90f, 360f * (1f - fraction), false, tl, box,
            style = androidx.compose.ui.graphics.drawscope.Stroke(stroke, cap = androidx.compose.ui.graphics.StrokeCap.Round))
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
        Box(Modifier.size(size).glassCard(colors, shape), contentAlignment = Alignment.Center) {
            Text(label.firstOrNull()?.uppercase().orEmpty(), color = colors.textPrimary, style = AuroraType.Display.copy(fontSize = (size.value * 0.4f).sp))
        }
    }
}

/** When the moment ends: one quiet line under the headline, no pill and no icon. */
@Composable
private fun EndsPill(text: String) {
    Text(
        text, Modifier.fillMaxWidth().padding(top = if (LocalLandscape.current) 8.dp else 14.dp),
        color = Aurora.colors.textSecondary, style = AuroraType.Body, textAlign = if (LocalStartAligned.current) TextAlign.Start else TextAlign.Center,
    )
}

/** A section label over a part of the Intent page ("Your reason"), in sentence case. */
@Composable
private fun PartLabel(text: String) {
    Text(
        text, Modifier.padding(start = 4.dp, top = if (LocalLandscape.current) 14.dp else 26.dp, bottom = if (LocalLandscape.current) 8.dp else 10.dp).clearAndSetSemantics { heading(); this.text = androidx.compose.ui.text.AnnotatedString(text) },
        color = Aurora.colors.textTertiary, style = AuroraType.Eyebrow,
    )
}


/** The Shield's question: large and bold, centred on a block, left-aligned on the Intent page. */
@Composable
private fun Headline(text: String, highlight: String? = null) {
    val start = LocalStartAligned.current
    Text(
        text, Modifier.fillMaxWidth().semantics { heading() }, color = Aurora.colors.textPrimary,
        style = when {
            LocalLandscape.current && start -> AuroraType.HeroBold.copy(fontSize = 26.sp, lineHeight = 30.sp)
            LocalLandscape.current -> AuroraType.Display.copy(fontSize = 30.sp, lineHeight = 34.sp)
            start -> AuroraType.HeroBold.copy(fontSize = 36.sp, lineHeight = 40.sp)
            else -> AuroraType.Display.copy(fontSize = 42.sp, lineHeight = 46.sp)
        },
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
        Modifier.padding(top = if (LocalLandscape.current) 10.dp else 26.dp).fillMaxWidth().semantics { contentDescription = "$label: $text" },
        color = colors.textPrimary,
        style = if (LocalLandscape.current) AuroraType.DisplayS.copy(fontSize = 17.sp, lineHeight = 22.sp) else AuroraType.DisplayS.copy(fontSize = 22.sp, lineHeight = 28.sp),
        textAlign = if (start) TextAlign.Start else TextAlign.Center,
    )
}

@Composable
private fun Body(text: String) {
    val start = LocalStartAligned.current
    Text(
        text,
        // Centred as a block too, not just its lines: the column around it may align to the start.
        if (start) Modifier.fillMaxWidth().padding(top = 8.dp)
        else Modifier.fillMaxWidth().padding(top = if (LocalLandscape.current) 10.dp else 22.dp).wrapContentWidth().widthIn(max = if (LocalLandscape.current) 400.dp else 270.dp),
        color = Aurora.colors.textSecondary, style = AuroraType.Body, textAlign = if (start) TextAlign.Start else TextAlign.Center,
    )
}

/** The reflection pause: a small ring of light that closes as the seconds pass. */
@Composable
private fun PauseRing(leftMs: Long, totalMs: Long) {
    // In landscape the ring goes round the app on the left, where there's room for it.
    LocalPause.current?.let { pause ->
        androidx.compose.runtime.SideEffect { pause.value = leftMs to totalMs }
        androidx.compose.runtime.DisposableEffect(Unit) { onDispose { pause.value = null } }
        return
    }
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
    Column(Modifier.padding(top = if (LocalLandscape.current) Space.XS else Space.M, bottom = Space.S), horizontalAlignment = Alignment.CenterHorizontally) {
        RollingClock(formatClock(until - now), colors.textPrimary, if (LocalLandscape.current) AuroraType.Headline else AuroraType.Hero, Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        Text(caption, color = colors.textSecondary, style = AuroraType.Secondary, textAlign = TextAlign.Center)
    }
}

/** Both cooldowns restart from every new attempt to open the app; the Shield says so plainly. */
@Composable
private fun RestartNote(text: String = stringResource(R.string.shield_restart_note)) {
    Text(
        text,
        Modifier.fillMaxWidth().padding(top = Space.XS).clip(Radius.Pill).background(Aurora.colors.mutedSoft).padding(horizontal = Space.M, vertical = 10.dp),
        color = Aurora.colors.mutedText,
        style = AuroraType.Label,
        textAlign = TextAlign.Center,
    )
}

/** The moment's actions, at the foot of the page. */
@Composable
private fun androidx.compose.foundation.layout.ColumnScope.Actions(content: @Composable () -> Unit) {
    if (LocalLandscape.current) {
        Spacer(Modifier.weight(1f).heightIn(min = Space.M))
        SideBySide(content)
        return
    }
    Spacer(Modifier.weight(1f).heightIn(min = Space.L))
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) { content() }
}

/** Actions in one row of equal widths, the first (the main one) on the right where the thumb rests. */
@Composable
private fun SideBySide(content: @Composable () -> Unit) = androidx.compose.ui.layout.Layout(content, Modifier.fillMaxWidth()) { items, c ->
    val gap = Space.S.roundToPx()
    val n = items.size.coerceAtLeast(1)
    val w = ((c.maxWidth - gap * (n - 1)) / n).coerceAtLeast(0)
    val placed = items.map { it.measure(androidx.compose.ui.unit.Constraints.fixedWidth(w)) }
    val h = placed.maxOfOrNull { it.height } ?: 0
    layout(c.maxWidth, h) { placed.asReversed().forEachIndexed { i, p -> p.place(i * (w + gap), (h - p.height) / 2) } }
}

// ---- Blocks: no way through -------------------------------------------------------------------

@Composable
private fun androidx.compose.foundation.layout.ColumnScope.Blocked(decision: Decision.Shield, label: String, now: Long, home: () -> Unit, onEnded: () -> Unit, onAurora: () -> Unit) {
    val app = LocalContext.current.applicationContext as AuroraApplication
    val runtime by app.runtime.state.collectAsStateWithLifecycle()
    // An Intent cooldown is read live: a new attempt restarts it, and the countdown follows.
    val until = if (decision.reason == ShieldReason.IntentCooldown) runtime?.value?.intentCooldowns?.get(decision.packageName) ?: decision.until else decision.until
    if (until != null && now >= until && decision.reason != ShieldReason.Limit) {
        Ended(label, home, onEnded)
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
        ShieldReason.Friction, ShieldReason.Cooldown -> {
            Headline(stringResource(R.string.shield_blocked_title, label), label)
            ends?.let { EndsPill(it) }
            Body(stringResource(if (decision.reason == ShieldReason.Friction) R.string.shield_end_friction_body else R.string.shield_end_cooldown_body))
            val context = LocalContext.current
            Actions {
                AuroraButton(stringResource(R.string.shield_open_aurora), {
                    // Start aurora before taking the Shield away, so it never shows the app behind it.
                    context.startActivity(android.content.Intent(context, MainActivity::class.java).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                    onAurora()
                }, Modifier.fillMaxWidth())
                AuroraButton(stringResource(LocalLeave.current), home, Modifier.fillMaxWidth(), kind = ButtonKind.Quiet)
            }
            return
        }
        else -> {
            Headline(stringResource(R.string.shield_intent_cooldown_title))
            until?.let { Countdown(it, now, stringResource(R.string.shield_opens_again_at, clockTime(it))) }
            Body(stringResource(R.string.shield_intent_cooldown_body, label))
            RestartNote()
            // The only ways past it: end the session in Aurora (at once if Flexible, after its challenge or wait
            // if Friction or Cooldown). A Strict session has none.
            val t = app.now()
            val endable = remember(runtime) { app.runsNow(t).any { it.covers(t) && decision.packageName in it.intent?.keys.orEmpty() && (it.cancellable(t) || it.endsAfterTask) } }
            if (endable) {
                Body(stringResource(R.string.shield_intent_cooldown_end))
                val context = LocalContext.current
                Actions {
                    AuroraButton(stringResource(R.string.shield_open_aurora), {
                        context.startActivity(android.content.Intent(context, MainActivity::class.java).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                        onAurora()
                    }, Modifier.fillMaxWidth())
                    AuroraButton(stringResource(LocalLeave.current), home, Modifier.fillMaxWidth(), kind = ButtonKind.Quiet)
                }
                return
            }
        }
    }
    Actions { AuroraButton(stringResource(LocalLeave.current), home, Modifier.fillMaxWidth()) }
}

/** What held the app back is over: open it, or leave. */
@Composable
private fun androidx.compose.foundation.layout.ColumnScope.Ended(label: String, home: () -> Unit, onEnded: () -> Unit) {
    Headline(stringResource(R.string.shield_ended_title))
    Body(stringResource(R.string.shield_ended_body, label))
    Actions {
        AuroraButton(stringResource(R.string.shield_open_app, label), onEnded, Modifier.fillMaxWidth())
        AuroraButton(stringResource(LocalLeave.current), home, Modifier.fillMaxWidth(), kind = ButtonKind.Quiet)
    }
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

// ---- Ending a Friction or Cooldown session: in Aurora only, for every app in it -----------------

/**
 * The one way a Friction or Cooldown session ends early: its challenge, or its wait sat out on this
 * screen. The wait starts again whenever [host] comes back to the front (the person went home or to
 * another app), and closing this screen drops it.
 */
@Composable
fun EndSession(app: AuroraApplication, run: ConfigRun, host: androidx.lifecycle.LifecycleOwner, onEnd: () -> Unit, onCancel: () -> Unit) {
    val shield = run.shield ?: return
    val colors = Aurora.colors
    androidx.compose.foundation.layout.BoxWithConstraints(
        Modifier.fillMaxSize().background(colors.background).windowInsetsPadding(WindowInsets.safeDrawing).imePadding(),
    ) {
        Column(
            Modifier.verticalScroll(rememberScrollState()).fillMaxWidth().heightIn(min = maxHeight).padding(horizontal = Space.Gutter),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            AuroraMark(26.dp, Modifier.padding(top = 34.dp))
            Spacer(Modifier.height(Space.XL))
            if (shield.mode == Mode.Friction) {
                Headline(stringResource(R.string.end_session_friction_title))
                Body(stringResource(R.string.end_session_body, run.name))
                val s = shield.friction
                when {
                    // Always exact words, always shown. A Shield saved before they were asked for gets fixed ones.
                    s.task == FrictionTask.Intention -> PhraseTask(s.phrase.ifBlank { stringResource(R.string.friction_phrase_default) }, onDone = onEnd)
                    else -> MathTask(s.difficulty, onSolved = onEnd, onMiss = {})
                }
                Actions { AuroraButton(stringResource(R.string.action_cancel), onCancel, Modifier.fillMaxWidth(), kind = ButtonKind.Quiet) }
            } else {
                var since by remember { mutableStateOf(TrustedClock.wall()) }
                // Leaving Aurora restarts the wait; the screen going off or locking doesn't: the wait runs on.
                var slept by remember { mutableStateOf(false) }
                val context = LocalContext.current
                androidx.lifecycle.compose.LifecycleStartEffect(Unit, lifecycleOwner = host) {
                    if (!slept) since = TrustedClock.wall()
                    slept = false
                    onStopOrDispose { slept = screenAsleep(context) }
                }
                val now by produceState(TrustedClock.wall()) {
                    while (true) {
                        value = TrustedClock.wall()
                        delay(500)
                    }
                }
                val readyAt = since + shield.cooldownWaitMinutes * 60_000L
                val ready = now >= readyAt
                Headline(stringResource(if (ready) R.string.shield_cooldown_done_title else R.string.end_session_cooldown_title))
                Body(stringResource(R.string.end_session_body, run.name))
                if (!ready) {
                    Countdown(readyAt, now, stringResource(R.string.shield_cooldown_ready_at, clockTime(readyAt)))
                    RestartNote(stringResource(R.string.end_session_cooldown_note))
                }
                Actions {
                    if (ready) AuroraButton(stringResource(R.string.end_session_confirm), onEnd, Modifier.fillMaxWidth())
                    AuroraButton(stringResource(R.string.action_cancel), onCancel, Modifier.fillMaxWidth(), kind = ButtonKind.Quiet)
                }
            }
            Spacer(Modifier.height(Space.M))
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
internal fun MathTask(difficulty: Difficulty, onSolved: () -> Unit, onMiss: () -> Unit) {
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
                .glassCard(colors, Radius.L, edge = false)
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
            Text(stringResource(R.string.friction_math_wrong), Modifier.semantics { liveRegion = LiveRegionMode.Polite }, color = colors.mutedText, style = AuroraType.Secondary)
        }
        AuroraButton(stringResource(R.string.friction_math_check), check, Modifier.fillMaxWidth(), enabled = answer.isNotBlank())
    }
}

/** The words set when the Shield was made, typed out exactly (case and extra spaces aside). */
@Composable
internal fun PhraseTask(phrase: String, onDone: () -> Unit) {
    val colors = Aurora.colors
    var text by remember { mutableStateOf("") }
    val done = phraseMatches(phrase, text)
    Column(Modifier.padding(top = Space.L), verticalArrangement = Arrangement.spacedBy(Space.S)) {
        Text(stringResource(R.string.friction_phrase_prompt), color = colors.textSecondary, style = AuroraType.BodyStrong)
        Text(
            phrase,
            Modifier.fillMaxWidth().glassCard(colors, Radius.L, edge = false).padding(Space.L),
            color = colors.textPrimary,
            style = AuroraType.Title,
        )
        AuroraTextField(
            text, { text = it.take(phrase.length + 40) },
            placeholder = stringResource(R.string.friction_phrase_placeholder),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
            singleLine = false,
        )
        AuroraButton(stringResource(R.string.friction_intention_confirm_phrase), onDone, Modifier.fillMaxWidth(), enabled = done)
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
            // Every opening pauses from the start; the Shield stays up through the screen going off, so that doesn't.
            val pauseStart: Long? = remember(pkg) { if (config.reflectionSeconds <= 0) 0L else TrustedClock.wall() }
            val left = pauseStart?.let { it + config.reflectionSeconds * 1000L - now } ?: (config.reflectionSeconds * 1000L)
            var minutes by remember { mutableStateOf<Int?>(null) }
            // Never longer than the protection that's running: access can't outlast it anyway.
            val max = decision.until?.let { ((it - TrustedClock.wall()) / 60_000).toInt().coerceIn(1, 24 * 60) } ?: (24 * 60)
            Headline(stringResource(R.string.intent_prompt_title, label), label)
            Body(stringResource(R.string.intent_prompt_question))
            if (config.reflectionSeconds > 0 && left > 0) PauseRing(left.coerceAtLeast(0), config.reflectionSeconds * 1000L)
            PartLabel(stringResource(R.string.usage_prompt, label))
            DurationPicker(minutes, { minutes = it }, UsagePresets.filter { it <= max }.ifEmpty { listOf(max) }, Modifier.fillMaxWidth(), max = max)
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
            UsageChoice(label, decision, onBack = { step = IntentStep.Reflect }, onChoose = start)
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
        LitBadge(icon, tint = if (warm) colors.textSecondary else colors.accent, size = 52)
        Column(Modifier.weight(1f)) {
            Text(label, color = colors.textSecondary, style = AuroraType.Caption)
            Text(text, color = colors.textPrimary, style = AuroraType.Title)
        }
    }
}
