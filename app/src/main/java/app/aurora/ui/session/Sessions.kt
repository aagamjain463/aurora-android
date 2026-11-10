package app.aurora.ui.session

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import app.aurora.turnOffRun
import app.aurora.protect.EndSession
import androidx.compose.animation.core.animateFloat
import androidx.compose.ui.graphics.Color
import app.aurora.ui.protect.modeName
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Spacer
import app.aurora.data.formatDuration
import androidx.compose.foundation.border
import androidx.compose.foundation.selection.selectable
import app.aurora.ui.components.glassCard
import app.aurora.ui.components.glassShine
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.em
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.aurora.AuroraApplication
import app.aurora.R
import app.aurora.auroraViewModel
import app.aurora.data.formatClock
import app.aurora.data.spokenDuration
import app.aurora.endUnlock
import app.aurora.protect.ConfigRun
import app.aurora.protect.Conflict
import app.aurora.protect.ConflictKind
import app.aurora.protect.SessionUnlock
import app.aurora.protect.PassKind
import app.aurora.protect.kind
import app.aurora.protect.opensAll
import app.aurora.protect.unlockKeys
import app.aurora.protect.UnlockUse
import app.aurora.protect.UsageConfig
import app.aurora.protect.partNames
import app.aurora.protect.shownMode
import app.aurora.protect.ShieldService
import app.aurora.protect.shieldOf
import app.aurora.protect.intentOf
import app.aurora.protect.modeOf
import app.aurora.runsNow
import app.aurora.ui.components.AuroraButton
import app.aurora.ui.components.AuroraDialog
import app.aurora.ui.components.AuroraIcons
import app.aurora.ui.components.AuroraScreen
import app.aurora.ui.components.AuroraSwitch
import app.aurora.ui.components.AuroraTextField
import app.aurora.ui.components.ButtonKind
import app.aurora.ui.components.ChoiceCard
import app.aurora.ui.components.Chevron
import app.aurora.ui.components.Chip
import app.aurora.ui.components.DurationPicker
import app.aurora.ui.components.Eyebrow
import app.aurora.ui.components.ProgressLine
import app.aurora.ui.components.RollingClock
import app.aurora.ui.components.auroraSurface
import app.aurora.ui.components.clockTime
import app.aurora.ui.components.glass
import app.aurora.ui.components.glassControl
import app.aurora.ui.components.minutesText
import app.aurora.ui.components.pressable
import app.aurora.ui.components.rise
import app.aurora.ui.configure.Label
import app.aurora.ui.home.OpenFocusPill
import app.aurora.ui.home.RunCard
import app.aurora.ui.library.PickRow
import app.aurora.ui.protect.IconStack
import app.aurora.ui.protect.ModeChip
import app.aurora.ui.protect.ProtectData
import app.aurora.ui.protect.ProtectModel
import app.aurora.ui.theme.Aurora
import app.aurora.ui.theme.AuroraType
import app.aurora.ui.theme.Radius
import app.aurora.ui.theme.Space
import app.aurora.useUnlock
import kotlinx.coroutines.launch
import java.time.ZoneId

/*
 * Sessions: what Home shows of the one that runs (one runs at a time), its unlocks (breaks set before it starts, used
 * from here or from Focus), and the dialogs the session flow shares: conflicts, an unlock's editor, Start's chooser.
 */

// ---- Conflicts --------------------------------------------------------------------------------------

/** Why a save, start or schedule was refused: what clashes, its apps, and when. Nothing was saved. */
@Composable
fun ConflictDialog(c: Conflict, d: ProtectData, onDismiss: () -> Unit) {
    val colors = Aurora.colors
    val items = pluralStringResource(R.plurals.conflict_items, c.keys.size, c.keys.size)
    val body = when (c.kind) {
        ConflictKind.Inside -> stringResource(R.string.conflict_inside, items)
        ConflictKind.Plan -> stringResource(R.string.conflict_plan, items)
        ConflictKind.Sessions -> stringResource(R.string.conflict_sessions, c.name)
        ConflictKind.Apps -> stringResource(R.string.conflict_apps, items, c.name)
    }
    AuroraDialog(stringResource(R.string.conflict_title), onDismiss, stringResource(R.string.conflict_change), onDismiss, body) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Space.S), horizontalAlignment = Alignment.CenterHorizontally) {
            if (c.keys.isNotEmpty()) IconStack(c.keys.toList(), d, max = 6, size = 32.dp)
            if (c.end > 0 && c.name.isNotEmpty()) Text(stringResource(R.string.conflict_when, c.name, windowText(c.start, c.end)), color = colors.textSecondary, style = AuroraType.Secondary, textAlign = TextAlign.Center)
        }
    }
}

/** "Mon 10 Nov, 14:00 – 15:00", or from a time on for something with no end. */
@Composable
private fun windowText(start: Long, end: Long): String {
    val day = java.time.Instant.ofEpochMilli(start).atZone(ZoneId.systemDefault()).format(java.time.format.DateTimeFormatter.ofPattern("EEE d MMM, "))
    return day + clockTime(start) + if (end == Long.MAX_VALUE) "" else " – " + clockTime(end)
}

/** Home's way in: a new session, or a saved one. */
@Composable
fun StartChooser(onNew: () -> Unit, onSaved: () -> Unit, onDismiss: () -> Unit) {
    AuroraDialog(stringResource(R.string.home_start_session), onDismiss, stringResource(R.string.action_cancel), onDismiss) {
        Column(verticalArrangement = Arrangement.spacedBy(Space.S)) {
            ChoiceCard(stringResource(R.string.start_new_session), "", false, { onDismiss(); onNew() }, icon = AuroraIcons.Plus)
            ChoiceCard(stringResource(R.string.start_saved_sessions), "", false, { onDismiss(); onSaved() }, icon = AuroraIcons.Layers)
        }
    }
}

// ---- The running session ----------------------------------------------------------------------------

/**
 * The running session on Home, as one calm stage: a live mark and its strictness, its name, the time left large and
 * centred with when it ends, how far along it is, what it holds, then its two actions side by side (Focus, Unlock).
 * While an unlock is on, the stage belongs to it: its own clock, when everything locks again, and Lock again now.
 */
@Composable
fun SessionRow(run: ConfigRun, d: ProtectData, app: AuroraApplication, onOpen: () -> Unit, onOpenFocus: (String) -> Unit) {
    val colors = Aurora.colors
    val now = d.time.wall
    val end = run.endAt(d.time)
    val start = run.startAt(d.time)
    val used = d.runtime.unlocks[run.id].orEmpty()
    val unlocked = run.unlockedAt(used, now)
    val keys = if (run.carried) emptyList() else (run.shield?.keys.orEmpty() + run.intent?.keys.orEmpty()).distinct()
    val left = run.unlocks.sumOf { run.left(it, used) }
    var choosing by remember { mutableStateOf(false) }
    Column(
        Modifier.rise().padding(horizontal = Space.Gutter).padding(top = Space.S).fillMaxWidth()
            .auroraSurface(colors).padding(horizontal = 24.dp, vertical = 26.dp),
    ) {
        // The stage: a tap anywhere on it opens everything about the session.
        Column(
            Modifier.fillMaxWidth().clip(Radius.M).pressable(Radius.M, onClickLabel = stringResource(R.string.run_details), onClick = onOpen),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(7.dp).clip(Radius.Pill).background(if (unlocked != null) colors.textTertiary else colors.textPrimary))
                Text(
                    stringResource(if (unlocked != null) R.string.task_unlocked else R.string.session_eyebrow).uppercase(),
                    Modifier.padding(start = 10.dp).weight(1f), color = colors.textSecondary, style = AuroraType.Eyebrow,
                )
                run.shownMode?.let { ModeChip(it) }
            }
            Text(
                run.name, Modifier.fillMaxWidth().padding(top = 18.dp), color = colors.textPrimary,
                style = AuroraType.Display.copy(fontSize = androidx.compose.ui.unit.TextUnit(26f, androidx.compose.ui.unit.TextUnitType.Sp)),
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            val clockTo = unlocked?.end ?: end
            if (clockTo != null) {
                StageClock(clockTo - now, Modifier.padding(top = 30.dp))
                Text(
                    if (unlocked != null) {
                        val name = run.unlocks.find { it.id == unlocked.unlockId }?.name.orEmpty()
                        listOf(name, stringResource(R.string.task_unlock_back_at, clockTime(unlocked.end)), stringResource(if (unlocked.focusRuns) R.string.task_timer_running else R.string.task_timer_stopped))
                            .filter { it.isNotBlank() }.joinToString(" · ")
                    } else stringResource(R.string.home_ends_at, clockTime(clockTo)),
                    Modifier.padding(top = 6.dp), color = colors.textSecondary, style = AuroraType.Secondary, textAlign = TextAlign.Center,
                )
            } else Text(stringResource(R.string.home_until_off), Modifier.padding(top = 30.dp), color = colors.textSecondary, style = AuroraType.Body)
            if (end != null) ProgressLine(((now - start).toFloat() / (end - start).coerceAtLeast(1)).coerceIn(0f, 1f), Modifier.padding(top = 28.dp))
            Row(Modifier.fillMaxWidth().padding(top = 20.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.S)) {
                if (keys.isNotEmpty()) IconStack(keys, d, max = 4, size = 24.dp)
                Text(partsLine(run, d), Modifier.weight(1f), color = colors.textTertiary, style = AuroraType.Caption, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (run.unlocks.isNotEmpty()) Text(
                    if (left > 0) pluralStringResource(R.plurals.task_unlocks_left, left, left) else stringResource(R.string.task_unlock_no_more_short),
                    color = colors.textTertiary, style = AuroraType.Caption, maxLines = 1,
                )
            }
        }
        // The actions, side by side and equal; while an unlock is on, the one way back.
        val showFocus = run.focus != null
        val showUnlock = run.unlocks.isNotEmpty()
        if (unlocked != null) AuroraButton(
            stringResource(R.string.task_unlock_end), { app.scope.launch { app.saving { app.endUnlock(run.id) } } },
            Modifier.fillMaxWidth().padding(top = 24.dp), kind = ButtonKind.Secondary, icon = AuroraIcons.Lock,
        ) else if (showFocus || showUnlock) Row(Modifier.fillMaxWidth().padding(top = 24.dp), horizontalArrangement = Arrangement.spacedBy(Space.S)) {
            if (showFocus) AuroraButton(stringResource(R.string.focus_name), { onOpenFocus(run.id) }, Modifier.weight(1f), kind = ButtonKind.Secondary, icon = AuroraIcons.Focus)
            if (showUnlock) AuroraButton(stringResource(R.string.task_unlock_action), { choosing = true }, Modifier.weight(1f), kind = ButtonKind.Secondary, icon = AuroraIcons.Unlock, enabled = left > 0)
        }
    }
    if (choosing) UnlockChooser(run, d, app) { choosing = false }
}

/**
 * Home's hero while a session runs, in place of the brand line: the session's name with a short gold rule, then its
 * time left as a bare stopwatch face, nothing around it (a pass never changes it), a line for a pass while one is on,
 * and the round controls: Focus, Pass, End, Details. The time reclaimed lives on the details page.
 */
@Composable
fun SessionHero(run: ConfigRun, d: ProtectData, app: AuroraApplication, onOpen: () -> Unit, onOpenFocus: (String) -> Unit, landscape: Boolean = false, fill: Boolean = false, modifier: Modifier = Modifier) {
    val colors = Aurora.colors
    val now = d.time.wall
    val end = run.endAt(d.time) ?: return
    val used = d.runtime.unlocks[run.id].orEmpty()
    val unlocked = run.unlockedAt(used, now)
    val pass = unlocked?.let { u -> run.unlocks.find { it.id == u.unlockId } }
    val left = run.unlocks.sumOf { run.left(it, used) }
    var choosing by remember { mutableStateOf(false) }
    var confirmEnd by remember { mutableStateOf(false) }
    var ending by remember { mutableStateOf(false) }
    // What it is: the name and its gold rule.
    val title: @Composable () -> Unit = {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                run.name, Modifier.semantics { heading() }, color = colors.textPrimary,
                style = AuroraType.DisplayS.copy(fontSize = 30.sp, lineHeight = 36.sp, letterSpacing = (-0.6).sp), textAlign = TextAlign.Center,
                maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
            Box(
                Modifier.padding(top = 14.dp).size(width = 28.dp, height = 2.dp).clip(Radius.Pill)
                    .background(androidx.compose.ui.graphics.Brush.horizontalGradient(listOf(colors.plus.copy(alpha = 0.4f), colors.plus, colors.plus.copy(alpha = 0.4f)))),
            )
        }
    }
    // How long is left: the stopwatch face, when it ends, a pass if one is on.
    val time: @Composable () -> Unit = {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            // The session's time left, as a stopwatch face: a tap opens the session.
            HeroClock(
                (end - now).coerceAtLeast(0),
                Modifier.clip(Radius.L).pressable(Radius.L, onClickLabel = stringResource(R.string.run_details), onClick = onOpen).padding(horizontal = 12.dp),
                spoken = stringResource(R.string.home_now_spoken, spokenDuration((end - now).coerceAtLeast(0))),
            )
            if (unlocked != null && pass != null) Text(
                stringResource(
                    R.string.pass_on_line, pass.name, formatClock((unlocked.end - now).coerceAtLeast(0)),
                    stringResource(if (unlocked.focusRuns) R.string.task_timer_running else R.string.task_timer_stopped),
                ),
                Modifier.padding(top = 14.dp), color = colors.plus, style = AuroraType.Caption, textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
    }
    // Round controls, as a watch face has them; End is always in sight, never behind Details.
    val endable = run.cancellable(d.time) || run.endsAfterTask
    val controls: @Composable () -> Unit = {
        androidx.compose.foundation.layout.FlowRow(
            horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally), verticalArrangement = Arrangement.spacedBy(18.dp),
            maxItemsInEachRow = if (landscape) 2 else Int.MAX_VALUE,
        ) {
            if (unlocked != null) RoundControl(AuroraIcons.Lock, stringResource(R.string.task_unlock_end_short), lit = true) { app.scope.launch { app.saving { app.endUnlock(run.id) } } }
            else {
                if (run.focus != null) RoundControl(AuroraIcons.Focus, stringResource(R.string.focus_name), lit = true) { onOpenFocus(run.id) }
                // How many are left is said in the chooser, not here.
                if (run.unlocks.isNotEmpty()) RoundControl(AuroraIcons.Unlock, stringResource(R.string.task_unlock_action), lit = run.focus == null, enabled = left > 0) { choosing = true }
            }
            RoundControl(
                AuroraIcons.Close, stringResource(R.string.session_end), lit = false, enabled = endable,
            ) { if (run.cancellable(d.time)) confirmEnd = true else ending = true }
            RoundControl(AuroraIcons.Info, stringResource(R.string.run_details), lit = false, onClick = onOpen)
        }
    }
    if (landscape) Row(
        modifier.rise().fillMaxWidth().padding(horizontal = Space.Gutter, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) { title(); Box(Modifier.padding(top = 24.dp)) { time() } }
        Box(Modifier.padding(start = Space.L), contentAlignment = Alignment.Center) { controls() }
    } else if (fill) Column(modifier.rise().fillMaxWidth().padding(horizontal = Space.Gutter), horizontalAlignment = Alignment.CenterHorizontally) {
        // The whole first screen: the name high, the time at the centre, the controls low, each with room around it.
        Spacer(Modifier.weight(0.7f))
        title()
        Spacer(Modifier.weight(1.3f))
        time()
        Spacer(Modifier.weight(1.3f))
        controls()
        Spacer(Modifier.weight(0.7f))
    } else Column(modifier.rise().fillMaxWidth().padding(horizontal = Space.Gutter).padding(vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        title()
        Box(Modifier.padding(top = 30.dp)) { time() }
        Box(Modifier.padding(top = 36.dp)) { controls() }
    }
    if (choosing) UnlockChooser(run, d, app) { choosing = false }
    EndFlow(run, app, confirmEnd, ending, { confirmEnd = false }, { ending = false })
}

/**
 * Home's stopwatch face: large, light digits on a faint pool of light (by night). With hours, the seconds sit small and
 * quiet beside the hours and minutes, as on a watch; under an hour, minutes and seconds share the full size.
 */
@Composable
private fun HeroClock(ms: Long, modifier: Modifier = Modifier, spoken: String) {
    val colors = Aurora.colors
    val clock = formatClock(ms)
    val hours = clock.count { it == ':' } == 2
    val lead = if (hours) clock.substringBeforeLast(':') else clock
    val tail = if (hours) clock.substringAfterLast(':') else null
    val glow = if (colors.isDark) Color.White.copy(alpha = 0.06f) else Color.Transparent
    // The light sits outside the tap target's clip, so it fades out softly instead of ending at an edge.
    Box(
        Modifier.drawBehind {
            drawCircle(androidx.compose.ui.graphics.Brush.radialGradient(listOf(glow, Color.Transparent), center, size.maxDimension * 0.62f), size.maxDimension * 0.62f, center)
        },
        contentAlignment = Alignment.Center,
    ) {
        Row(modifier.clearAndSetSemantics { contentDescription = spoken }, verticalAlignment = Alignment.Bottom) {
            RollingClock(
                lead, colors.textPrimary,
                AuroraType.Timer.copy(fontSize = 108.sp, lineHeight = 112.sp, fontWeight = androidx.compose.ui.text.font.FontWeight(300), letterSpacing = (-0.055).em),
                Modifier.alignByBaseline(),
            )
            tail?.let {
                RollingClock(
                    it, colors.textTertiary, AuroraType.TimerTail.copy(fontSize = 32.sp, lineHeight = 36.sp, fontWeight = androidx.compose.ui.text.font.FontWeight(350)),
                    Modifier.padding(start = 8.dp).alignByBaseline(),
                )
            }
        }
    }
}

/** Ending a session: Flexible asks first; Friction and Cooldown take their challenge or wait. Never from the Shield. */
@Composable
private fun EndFlow(run: ConfigRun, app: AuroraApplication, confirm: Boolean, ending: Boolean, onConfirmDone: () -> Unit, onEndingDone: () -> Unit) {
    val host = androidx.lifecycle.compose.LocalLifecycleOwner.current
    if (confirm) AuroraDialog(
        stringResource(R.string.session_end_title), onConfirmDone, stringResource(R.string.home_turn_off),
        { onConfirmDone(); app.scope.launch { app.saving { app.turnOffRun(run.id) } } },
        stringResource(R.string.session_turn_off_body), stringResource(R.string.action_cancel),
    )
    if (ending) androidx.compose.ui.window.Dialog(onEndingDone, androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
        EndSession(app, run, host, onEnd = { onEndingDone(); app.scope.launch { app.saving { app.turnOffRun(run.id) } } }, onCancel = onEndingDone)
    }
}

/**
 * The timer ring, in Aurora Plus gold (founder, 2026-11-09), or in the brand's white for the Focus timer: a hairline track, and the time left as a ribbon of liquid
 * gold drawn clockwise from the top, draining as time passes. It glows softly, and a bright bead of light runs through
 * it from tail to head, again and again, like mercury in glass. No point at its head: the ribbon simply ends, rounded.
 * It glides with each second; with reduced motion the light stands still.
 */
@Composable
fun TimerRing(
    remaining: Float, modifier: Modifier = Modifier, stroke: androidx.compose.ui.unit.Dp = 4.dp,
    follow: androidx.compose.animation.core.AnimationSpec<Float> = androidx.compose.animation.core.tween(1000, easing = androidx.compose.animation.core.LinearEasing),
    /** The Focus timer's ring: the same ribbon, glow and light in the brand's white (ink on a light page), not gold. */
    white: Boolean = false,
) {
    val colors = Aurora.colors
    val reduced = Aurora.reducedMotion
    val shown by androidx.compose.animation.core.animateFloatAsState(
        remaining, if (reduced) androidx.compose.animation.core.snap() else follow,
        label = "ring",
    )
    val flow = androidx.compose.animation.core.rememberInfiniteTransition(label = "mercury")
    val bead by flow.animateFloat(
        0f, 1f, androidx.compose.animation.core.infiniteRepeatable(androidx.compose.animation.core.tween(2600, easing = androidx.compose.animation.core.FastOutSlowInEasing)),
        label = "bead",
    )
    val dark = colors.isDark
    val ink = colors.textPrimary
    val deep = if (white) ink.copy(alpha = 0.28f) else if (dark) app.aurora.ui.theme.Palette.PlusGoldShade else app.aurora.ui.theme.Palette.PlusGoldDeep
    val gold = if (white) ink.copy(alpha = 0.6f) else if (dark) app.aurora.ui.theme.Palette.PlusGold else app.aurora.ui.theme.Palette.PlusGoldShade
    val pale = if (white) ink.copy(alpha = 0.92f) else if (dark) app.aurora.ui.theme.Palette.PlusGoldPale else app.aurora.ui.theme.Palette.PlusGold
    val glow = if (white) ink.copy(alpha = if (dark) 0.5f else 0.22f) else gold.copy(alpha = if (dark) 0.55f else 0.35f)
    val light = if (white && !dark) colors.background else Color.White
    val track = colors.hairline
    androidx.compose.foundation.Canvas(modifier) {
        val w = stroke.toPx()
        val inset = 14.dp.toPx()
        val diameter = size.minDimension - inset * 2
        val topLeft = androidx.compose.ui.geometry.Offset((size.width - diameter) / 2, (size.height - diameter) / 2)
        val arcSize = androidx.compose.ui.geometry.Size(diameter, diameter)
        drawArc(track, 0f, 360f, false, topLeft, arcSize, style = androidx.compose.ui.graphics.drawscope.Stroke(1.dp.toPx()))
        val sweep = 360f * shown
        if (sweep <= 0.5f) return@Canvas
        // The ribbon's colour runs from deep gold at its tail to pale gold at its head.
        val f = sweep / 360f
        val brush = androidx.compose.ui.graphics.Brush.sweepGradient(
            0f to deep, (f * 0.55f) to gold, f to pale, (f + 0.0001f).coerceAtMost(1f) to deep, center = center,
        )
        rotate(-90f) {
            // The glow: the same ribbon, wide and blurred, under it.
            drawIntoCanvas { canvas ->
                val p = androidx.compose.ui.graphics.Paint().asFrameworkPaint().apply {
                    isAntiAlias = true
                    style = android.graphics.Paint.Style.STROKE
                    strokeWidth = w * 3.2f
                    strokeCap = android.graphics.Paint.Cap.ROUND
                    color = glow.toArgb()
                    maskFilter = android.graphics.BlurMaskFilter(12.dp.toPx(), android.graphics.BlurMaskFilter.Blur.NORMAL)
                }
                canvas.nativeCanvas.drawArc(topLeft.x, topLeft.y, topLeft.x + diameter, topLeft.y + diameter, 0f, sweep, false, p)
            }
            drawArc(brush, 0f, sweep, false, topLeft, arcSize, style = androidx.compose.ui.graphics.drawscope.Stroke(w, cap = androidx.compose.ui.graphics.StrokeCap.Round))
            // The mercury: a bead of light running through the ribbon, brightest at its centre.
            if (!reduced && sweep > 24f) {
                val len = minOf(46f, sweep * 0.35f)
                val at = (sweep - len) * bead
                val beadBrush = androidx.compose.ui.graphics.Brush.sweepGradient(
                    (at / 360f) to Color.Transparent,
                    ((at + len * 0.5f) / 360f) to light.copy(alpha = if (dark || white) 1f else 0.85f),
                    ((at + len) / 360f) to Color.Transparent,
                    center = center,
                )
                // The white (Focus) ring keeps only the soft halo moving: no hard sliver of light (founder, 2026-11-10).
                if (!white) drawArc(beadBrush, at, len, false, topLeft, arcSize, style = androidx.compose.ui.graphics.drawscope.Stroke(w * 0.9f, cap = androidx.compose.ui.graphics.StrokeCap.Round))
                // On a white ribbon the light carries its own halo, so it still reads as light running through glass.
                if (white) drawIntoCanvas { canvas ->
                    val p = androidx.compose.ui.graphics.Paint().asFrameworkPaint().apply {
                        isAntiAlias = true
                        style = android.graphics.Paint.Style.STROKE
                        strokeWidth = w * 2.6f
                        strokeCap = android.graphics.Paint.Cap.ROUND
                        color = light.copy(alpha = 0.9f).toArgb()
                        maskFilter = android.graphics.BlurMaskFilter(7.dp.toPx(), android.graphics.BlurMaskFilter.Blur.NORMAL)
                    }
                    canvas.nativeCanvas.drawArc(topLeft.x, topLeft.y, topLeft.x + diameter, topLeft.y + diameter, at + len * 0.3f, len * 0.4f, false, p)
                }
            }
        }
    }
}

/** A round glass control with its label under it; lit (the brand's white, ink by day) for the moment's main action. */
@Composable
private fun RoundControl(
    icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, lit: Boolean, enabled: Boolean = true, onClick: () -> Unit,
) {
    val colors = Aurora.colors
    // The disc and its label are one target.
    Column(
        Modifier.alpha(if (enabled) 1f else 0.4f).widthIn(min = 72.dp).clip(Radius.M)
            .then(if (enabled) Modifier.pressable(Radius.M, onClick = onClick) else Modifier).padding(horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.size(64.dp).clip(CircleShape)
                .then(if (lit) Modifier.background(colors.accent).glassShine(colors, CircleShape) else Modifier.glassControl(CircleShape)),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, null, tint = if (lit) colors.onAccent else colors.textPrimary, modifier = Modifier.size(24.dp)) }
        Text(label, Modifier.padding(top = 10.dp), color = colors.textPrimary, style = AuroraType.Label, maxLines = 1)
    }
}

/** The time left, large and centred: hours and minutes bright, seconds small and quiet beside them. */
@Composable
private fun StageClock(ms: Long, modifier: Modifier = Modifier) {
    val colors = Aurora.colors
    val clock = formatClock(ms.coerceAtLeast(0))
    val split = clock.lastIndexOf(':')
    val lead = if (split > 0) clock.substring(0, split) else clock
    val tail = if (split > 0) clock.substring(split + 1) else null
    Row(modifier, verticalAlignment = Alignment.Bottom) {
        RollingClock(lead, colors.textPrimary, AuroraType.Hero.copy(fontSize = androidx.compose.ui.unit.TextUnit(68f, androidx.compose.ui.unit.TextUnitType.Sp)), Modifier.alignByBaseline())
        tail?.let { RollingClock(it, colors.textTertiary, AuroraType.TimerTail, Modifier.padding(start = 6.dp).alignByBaseline()) }
    }
}

/** "2 Shields · 1 Intent". */
@Composable
private fun partsLine(run: ConfigRun, d: ProtectData): String {
    val c = d.rules.configs.find { it.id == run.configId }
    val shields = c?.allShieldIds?.size ?: if (run.shield != null) 1 else 0
    val intents = c?.allIntentIds?.size ?: if (run.intent != null) 1 else 0
    return listOfNotNull(
        pluralStringResource(R.plurals.task_shields_count, shields, shields).takeIf { shields > 0 },
        pluralStringResource(R.plurals.task_intents_count, intents, intents).takeIf { intents > 0 },
    ).joinToString(" · ")
}

/** The unlock that is on: its name, its own clock, when what it opened locks again, and a way to lock it now. */
@Composable
fun UnlockBanner(run: ConfigRun, u: UnlockUse, d: ProtectData, onEnd: () -> Unit) {
    val colors = Aurora.colors
    val name = run.unlocks.find { it.id == u.unlockId }?.name.orEmpty()
    Column(
        Modifier.fillMaxWidth().glass(colors, Radius.M).padding(horizontal = Space.M, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(Space.XS),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.S)) {
            run.unlocks.find { it.id == u.unlockId }?.let { PassGlyph(it.kind, 32.dp, lit = true) }
            Text(name, Modifier.weight(1f), color = colors.textPrimary, style = AuroraType.BodyStrong, maxLines = 1, overflow = TextOverflow.Ellipsis)
            RollingClock(formatClock(u.end - d.time.wall), colors.textPrimary, AuroraType.Title)
        }
        Text(
            stringResource(R.string.task_unlock_back_at, clockTime(u.end)) + " · " +
                stringResource(if (u.focusRuns) R.string.task_timer_running else R.string.task_timer_stopped),
            color = colors.textSecondary, style = AuroraType.Caption,
        )
        AuroraButton(stringResource(R.string.task_unlock_end), onEnd, Modifier.fillMaxWidth().padding(top = Space.XS), kind = ButtonKind.Secondary, icon = AuroraIcons.Lock, compact = true)
    }
}

/** Which pass to use: each by name with how many are left. Using one opens its apps for its time; they lock again on their own. */
@Composable
fun UnlockChooser(run: ConfigRun, d: ProtectData, app: AuroraApplication, onDismiss: () -> Unit) {
    val used = d.runtime.unlocks[run.id].orEmpty()
    var picked by remember { mutableStateOf(run.unlocks.firstOrNull { run.left(it, used) > 0 }?.id) }
    val any = run.unlocks.any { run.left(it, used) > 0 }
    AuroraDialog(
        stringResource(R.string.task_unlock_choose_title), onDismiss,
        stringResource(R.string.task_unlock_use), {
            val id = picked ?: return@AuroraDialog
            onDismiss()
            app.scope.launch { app.saving { app.useUnlock(run.id, id) } }
        },
        if (any) null else stringResource(R.string.task_unlock_no_more),
        stringResource(R.string.action_cancel),
        confirmEnabled = run.unlocks.any { it.id == picked && run.left(it, used) > 0 },
    ) {
        // Each pass by name, with how many of it are left; what it does was said when it was made.
        Column(verticalArrangement = Arrangement.spacedBy(Space.S)) {
            run.unlocks.forEach { u ->
                val left = run.left(u, used)
                PickRow(
                    u.name, if (left > 0) stringResource(R.string.pass_left_of, left, u.times) else stringResource(R.string.task_unlock_none_left),
                    picked == u.id, { if (left > 0) picked = u.id },
                    if (left > 0) Modifier else Modifier.alpha(0.45f),
                    leading = { PassGlyph(u.kind, 36.dp, lit = picked == u.id) },
                )
            }
        }
    }
}

/** Each kind of pass: its glyph, its name and the one line that says what it does. */
val PassKind.icon: androidx.compose.ui.graphics.vector.ImageVector get() = when (this) {
    PassKind.Break -> AuroraIcons.Pause
    PassKind.Work -> AuroraIcons.Briefcase
    PassKind.Other -> AuroraIcons.Apps
}
val PassKind.title: Int get() = when (this) { PassKind.Break -> R.string.pass_kind_break; PassKind.Work -> R.string.pass_kind_work; PassKind.Other -> R.string.pass_kind_other }
val PassKind.body: Int get() = when (this) { PassKind.Break -> R.string.pass_kind_break_body; PassKind.Work -> R.string.pass_kind_work_body; PassKind.Other -> R.string.pass_kind_other_body }

/** A pass's glyph in a small round disc; lit (gold) while it is on or chosen. */
@Composable
fun PassGlyph(kind: PassKind, size: androidx.compose.ui.unit.Dp = 40.dp, lit: Boolean = false) {
    val colors = Aurora.colors
    Box(
        Modifier.size(size).clip(CircleShape).then(if (lit) Modifier.background(colors.plus.copy(alpha = 0.16f)) else Modifier.glassControl(CircleShape)),
        contentAlignment = Alignment.Center,
    ) { Icon(kind.icon, null, tint = if (lit) colors.plus else colors.textPrimary, modifier = Modifier.size(size * 0.46f)) }
}

/** "Work · 30 min · twice"; the kind is left out when it is the pass's name. */
@Composable
fun passSummary(u: SessionUnlock, names: Map<String, String>, d: ProtectData?): String {
    val kindName = stringResource(u.kind.title)
    // Kind, length and uses only: which apps it opens is in the pass itself.
    return listOf(kindName.takeIf { !it.equals(u.name.trim(), ignoreCase = true) }.orEmpty(), minutesText(u.minutes), pluralStringResource(R.plurals.task_unlock_times_count, u.times, u.times))
        .filter { it.isNotBlank() }.joinToString(" · ")
}

/**
 * A running session in full, quiet and complete: its time left as a stopwatch face with how far it has gone; what it
 * has given back (reclaimed, paused, passes left); the pass on or the way to one; then what it is (strictness, start,
 * end, length), what it blocks, its passes and its message; and End last.
 */
@Composable
fun RunDetailScreen(runId: String, onBack: () -> Unit, onOpenFocus: (String) -> Unit) {
    val vm = auroraViewModel { ProtectModel(it) }
    val data by vm.data.collectAsStateWithLifecycle()
    val d = data
    val run = d?.let { vm.app.runsNow(it.time).find { r -> r.id == runId } }
    var choosing by remember { mutableStateOf(false) }
    var confirmEnd by remember { mutableStateOf(false) }
    var ending by remember { mutableStateOf(false) }
    val colors = Aurora.colors
    AuroraScreen(title = run?.name.orEmpty(), onBack = onBack) {
        if (d == null || run == null) return@AuroraScreen
        val now = d.time.wall
        val used = d.runtime.unlocks[run.id].orEmpty()
        val unlocked = run.unlockedAt(used, now)
        val start = run.startAt(d.time)
        val end = run.endAt(d.time)
        val config = d.rules.configs.find { it.id == run.configId }
        val names = config?.let { d.rules.partNames(it) }.orEmpty()
        item(key = "clock") {
            Column(Modifier.fillMaxWidth().padding(horizontal = Space.Gutter).padding(top = Space.S, bottom = Space.L), horizontalAlignment = Alignment.CenterHorizontally) {
                if (end != null) {
                    val clock = formatClock((end - now).coerceAtLeast(0))
                    RollingClock(clock, colors.textPrimary, AuroraType.Timer.copy(fontSize = (if (clock.length > 5) 56 else 68).sp, lineHeight = (if (clock.length > 5) 62 else 76).sp),
                        spoken = stringResource(R.string.run_left, spokenDuration((end - now).coerceAtLeast(0))))
                    Text(stringResource(R.string.detail_session_left, clockTime(end)), color = colors.textTertiary, style = AuroraType.Secondary, textAlign = TextAlign.Center)
                    Box(Modifier.padding(top = 18.dp).width(180.dp)) { ProgressLine(((now - start).toFloat() / (end - start).coerceAtLeast(1)).coerceIn(0f, 1f)) }
                } else Text(stringResource(R.string.home_until_off), color = colors.textSecondary, style = AuroraType.Body)
            }
        }
        item(key = "stats") {
            val left = run.unlocks.sumOf { run.left(it, used) }
            Row(Modifier.padding(horizontal = Space.Gutter).padding(bottom = Space.M).fillMaxWidth().glass(colors, Radius.L).padding(vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Stat(stringResource(R.string.reclaimed_label), formatClock(run.focusedMs(d.time, used)), Modifier.weight(1f), lit = true)
                Box(Modifier.width(1.dp).height(36.dp).background(colors.hairline))
                Stat(stringResource(R.string.detail_paused), formatClock(run.stoppedMs(used, minOf(now, end ?: now))), Modifier.weight(1f))
                if (run.unlocks.isNotEmpty()) {
                    Box(Modifier.width(1.dp).height(36.dp).background(colors.hairline))
                    Stat(stringResource(R.string.detail_passes_left), "$left", Modifier.weight(1f))
                }
            }
        }
        if (run.unlocks.isNotEmpty() || run.focus != null) item(key = "actions") {
            Column(Modifier.padding(horizontal = Space.Gutter).padding(bottom = Space.S), verticalArrangement = Arrangement.spacedBy(Space.S)) {
                if (unlocked != null) UnlockBanner(run, unlocked, d) { vm.run { vm.app.endUnlock(run.id) } }
                else if (run.unlocks.isNotEmpty()) {
                    val left = run.unlocks.sumOf { run.left(it, used) }
                    AuroraButton(stringResource(R.string.task_unlock_choose_title), { choosing = true }, Modifier.fillMaxWidth(), kind = ButtonKind.Secondary, icon = AuroraIcons.Unlock, enabled = left > 0)
                }
                if (run.focus != null) AuroraButton(stringResource(R.string.home_open_focus), { onOpenFocus(run.id) }, Modifier.fillMaxWidth(), kind = ButtonKind.Secondary, icon = AuroraIcons.Focus)
            }
        }
        item(key = "about") {
            DetailGroup(stringResource(R.string.detail_session)) {
                val rows = listOfNotNull(
                    run.shownMode?.let { stringResource(R.string.detail_strictness) to modeName(it) },
                    stringResource(R.string.detail_started) to clockTime(start),
                    end?.let { stringResource(R.string.detail_ends) to clockTime(it) },
                    end?.let { stringResource(R.string.detail_length) to formatDuration(it - start) },
                )
                rows.forEachIndexed { i, (k, v) ->
                    if (i > 0) DetailRule(18.dp)
                    Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(k, Modifier.weight(1f), color = colors.textSecondary, style = AuroraType.Body)
                        Text(v, color = colors.textPrimary, style = AuroraType.BodyStrong)
                    }
                }
            }
        }
        // A commitment carried over from another account doesn't show what it covers.
        val parts = if (run.carried) emptyList() else config?.let { c -> (c.allShieldIds + c.allIntentIds).map { id -> (names[id].orEmpty()) to d.rules.unlockKeys(c, listOf(id)).toList() } }
            ?: listOfNotNull(run.shield?.let { it.name to it.keys.toList() }, run.intent?.let { it.name to it.keys.toList() })
        if (parts.isNotEmpty()) item(key = "blocking") {
            DetailGroup(stringResource(R.string.detail_blocking)) {
                parts.forEachIndexed { i, (name, keys) ->
                    if (i > 0) DetailRule(18.dp)
                    Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.S)) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(name, color = colors.textPrimary, style = AuroraType.BodyStrong, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(pluralStringResource(R.plurals.detail_apps_count, keys.size, keys.size), color = colors.textTertiary, style = AuroraType.Caption)
                        }
                        IconStack(keys, d, max = 4, size = 26.dp)
                    }
                }
            }
        }
        if (run.unlocks.isNotEmpty()) item(key = "passes") {
            DetailGroup(stringResource(R.string.task_unlocks_title)) {
                run.unlocks.forEachIndexed { i, u ->
                    if (i > 0) DetailRule(72.dp)
                    val left = run.left(u, used)
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        PassGlyph(u.kind, lit = unlocked?.unlockId == u.id)
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(u.name, color = colors.textPrimary, style = AuroraType.Title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(passSummary(u, names, d), color = colors.textSecondary, style = AuroraType.Secondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Text(if (left > 0) pluralStringResource(R.plurals.task_unlock_left, left, left) else stringResource(R.string.task_unlock_none_left),
                            color = if (left > 0) colors.textSecondary else colors.textTertiary, style = AuroraType.Caption)
                    }
                }
            }
        }
        if (run.note.isNotBlank()) item(key = "note") {
            DetailGroup(stringResource(R.string.detail_message)) {
                Text("\u201C" + run.note + "\u201D", Modifier.padding(horizontal = 18.dp, vertical = 16.dp), color = colors.textPrimary, style = AuroraType.Body)
            }
        }
        item(key = "end") {
            val endable = run.cancellable(d.time) || run.endsAfterTask
            Column(Modifier.fillMaxWidth().padding(horizontal = Space.Gutter).padding(top = Space.S), horizontalAlignment = Alignment.CenterHorizontally) {
                if (endable) AuroraButton(stringResource(R.string.session_end_full), { if (run.cancellable(d.time)) confirmEnd = true else ending = true }, Modifier.fillMaxWidth(), kind = ButtonKind.Quiet, icon = AuroraIcons.Close)
                else Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.XS)) {
                    Icon(AuroraIcons.Lock, null, tint = colors.textTertiary, modifier = Modifier.size(16.dp))
                    Text(run.committedUntil?.let { stringResource(R.string.home_committed_until, clockTime(it)) } ?: stringResource(R.string.session_ends_automatically), color = colors.textTertiary, style = AuroraType.Caption)
                }
            }
        }
    }
    if (choosing && d != null && run != null) UnlockChooser(run, d, vm.app) { choosing = false }
    if (run != null) EndFlow(run, vm.app, confirmEnd, ending, { confirmEnd = false }, { ending = false })
    // Turned off or over: there is nothing left to show.
    if (d != null && run == null) LaunchedEffect(Unit) { onBack() }
}

/** One figure of the session, its label above it; [lit] in gold for the one that matters most. */
@Composable
private fun Stat(label: String, value: String, modifier: Modifier = Modifier, lit: Boolean = false) {
    val colors = Aurora.colors
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, color = if (lit) colors.plus else colors.textTertiary, style = AuroraType.Caption, maxLines = 1)
        RollingClock(value, colors.textPrimary, AuroraType.Title.copy(fontFeatureSettings = "tnum"))
    }
}

/** A titled group of the details page: a quiet eyebrow, then one glass card of rows. */
@Composable
private fun DetailGroup(title: String, content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = Space.Gutter).padding(top = Space.M)) {
        Eyebrow(title, Modifier.padding(start = 4.dp, bottom = Space.S), color = Aurora.colors.textTertiary)
        Column(Modifier.fillMaxWidth().glass(Aurora.colors, Radius.L).padding(vertical = 4.dp), content = content)
    }
}

@Composable
private fun DetailRule(start: androidx.compose.ui.unit.Dp) = Box(Modifier.padding(start = start).fillMaxWidth().height(1.dp).background(Aurora.colors.hairline))

// ---- Setting up a session ----------------------------------------------------------------------------

/**
 * A session's passes, in one card: each with its glyph, what it is and a switch (only passes switched on can be used
 * this time); a tap opens it to change or delete. With none yet, the card explains the three kinds, each a tap from
 * being added.
 */
fun androidx.compose.foundation.lazy.LazyListScope.unlockItems(
    unlocks: List<SessionUnlock>, names: Map<String, String>, d: ProtectData?,
    onToggle: (SessionUnlock) -> Unit, onEdit: (SessionUnlock) -> Unit, onAdd: (PassKind) -> Unit,
) {
    item(key = "passes") {
        val colors = Aurora.colors
        @Composable fun Rule() = Box(Modifier.padding(start = 72.dp).fillMaxWidth().height(1.dp).background(colors.hairline))
        Column(Modifier.fillMaxWidth().glass(colors, Radius.L).padding(vertical = 6.dp)) {
            if (unlocks.isEmpty()) {
                Eyebrow(stringResource(R.string.pass_intro_title), Modifier.padding(start = 18.dp, top = 12.dp, bottom = 4.dp), color = colors.textTertiary)
                PassKind.entries.forEachIndexed { i, k ->
                    if (i > 0) Rule()
                    Row(
                        Modifier.fillMaxWidth().clickable(onClickLabel = stringResource(R.string.task_unlock_add)) { onAdd(k) }.padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        PassGlyph(k)
                        // The name alone; the pass's page says what it does.
                        Text(stringResource(k.title), Modifier.weight(1f), color = colors.textPrimary, style = AuroraType.Title)
                        Icon(AuroraIcons.Plus, null, tint = colors.textTertiary, modifier = Modifier.size(20.dp))
                    }
                }
            } else {
                unlocks.forEachIndexed { i, u ->
                    if (i > 0) Rule()
                    Row(
                        Modifier.fillMaxWidth().clickable(onClickLabel = stringResource(R.string.task_unlock_edit)) { onEdit(u) }.padding(start = 16.dp, end = 10.dp, top = 12.dp, bottom = 12.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        PassGlyph(u.kind, lit = u.on)
                        Column(Modifier.weight(1f).alpha(if (u.on) 1f else 0.5f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(u.name, color = colors.textPrimary, style = AuroraType.Title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(passSummary(u, names, d), color = colors.textSecondary, style = AuroraType.Secondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Box(Modifier.clip(Radius.Pill).toggleable(u.on, role = Role.Switch) { onToggle(u) }.padding(6.dp)) { app.aurora.ui.components.AuroraSwitch(u.on) }
                    }
                }
                Rule()
                Row(
                    Modifier.fillMaxWidth().clickable { onAdd(if (unlocks.none { it.kind == PassKind.Break }) PassKind.Break else PassKind.Work) }.padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) { Icon(AuroraIcons.Plus, null, tint = colors.textSecondary, modifier = Modifier.size(20.dp)) }
                    Text(stringResource(R.string.task_unlock_add), color = colors.textSecondary, style = AuroraType.BodyStrong)
                }
            }
        }
    }
}

/**
 * One pass, set up, on a page of its own: what it is for (Break, Work or Other: that decides what opens and whether
 * reclaimed time keeps counting), its name, which of the session's Shields and Intents it opens (Work and Other), and
 * how long and how often. Each part in its own room; the action at the foot.
 */
@Composable
fun UnlockEditor(
    u: SessionUnlock, isNew: Boolean, parts: List<Pair<String, String>>, d: ProtectData, initialKind: PassKind? = null,
    onChange: (SessionUnlock) -> Unit, onDone: () -> Unit, onDelete: () -> Unit, onDismiss: () -> Unit,
) {
    val colors = Aurora.colors
    var kind by remember(u.id) { mutableStateOf(initialKind ?: u.kind) }
    val defaults = PassKind.entries.map { stringResource(it.title) }.toSet() + ""
    // What it opens: the session's Shields and Intents, by id. An older pass that named apps starts over here.
    val picked = u.partIds
    val defaultName = stringResource(if (kind == PassKind.Other) R.string.task_unlock_name_placeholder else kind.title)
    val names = PassKind.entries.associateWith { if (it == PassKind.Other) "" else stringResource(it.title) }
    fun choose(k: PassKind) {
        kind = k
        val next = when (k) {
            PassKind.Break -> u.copy(partIds = emptyList(), keys = emptyList(), focusRuns = false)
            PassKind.Work -> u.copy(keys = emptyList(), focusRuns = true)
            PassKind.Other -> u.copy(keys = emptyList(), focusRuns = false)
        }
        onChange(if (u.name.trim() in defaults) next.copy(name = names.getValue(k)) else next)
    }
    val needsParts = kind == PassKind.Other && picked.isEmpty()
    val valid = u.name.isNotBlank() && u.minutes > 0 && u.times > 0 && !needsParts
    androidx.compose.ui.window.Dialog(onDismiss, androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Column(Modifier.fillMaxSize().background(colors.background).statusBarsPadding().navigationBarsPadding().imePadding()) {
            Box(Modifier.fillMaxWidth().height(60.dp).padding(horizontal = Space.M), contentAlignment = Alignment.Center) {
                app.aurora.ui.components.AuroraIconButton(AuroraIcons.Close, stringResource(R.string.action_cancel), onDismiss, Modifier.align(Alignment.CenterStart))
                Text(stringResource(if (isNew) R.string.task_unlock_new else R.string.task_unlock_edit), Modifier.semantics { heading() }, color = colors.textPrimary, style = AuroraType.Bar)
            }
            Column(
                Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = Space.Gutter).padding(top = Space.M, bottom = Space.XL),
                verticalArrangement = Arrangement.spacedBy(32.dp),
            ) {
                // What it is for, as one glass capsule of three; the line under it says what that means.
                Column(Modifier.padding(top = Space.S, bottom = Space.L), verticalArrangement = Arrangement.spacedBy(Space.L)) {
                    Row(Modifier.fillMaxWidth().glassControl(Radius.Pill).padding(4.dp)) {
                        PassKind.entries.forEach { k ->
                            val on = k == kind
                            Row(
                                Modifier.weight(1f).height(48.dp).clip(Radius.Pill).then(if (on) Modifier.background(colors.accent) else Modifier)
                                    .selectable(on, role = Role.RadioButton) { choose(k) },
                                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
                            ) {
                                Icon(k.icon, null, tint = if (on) colors.onAccent else colors.textSecondary, modifier = Modifier.size(16.dp))
                                Text(stringResource(k.title), color = if (on) colors.onAccent else colors.textSecondary, style = AuroraType.Label)
                            }
                        }
                    }
                    // Left-aligned, each line filled before it wraps (not the balanced breaks of the type scale).
                    Text(stringResource(kind.body), Modifier.fillMaxWidth(), color = colors.textSecondary, style = AuroraType.Body.copy(lineBreak = androidx.compose.ui.text.style.LineBreak.Simple))
                }
                EditorSection(stringResource(R.string.pass_name_label)) {
                    AuroraTextField(u.name, { onChange(u.copy(name = it.take(30))) }, defaultName.ifBlank { stringResource(R.string.pass_name_other) }, capitalization = KeyboardCapitalization.Sentences)
                }
                if (kind != PassKind.Break && parts.isNotEmpty()) EditorSection(stringResource(R.string.task_unlock_what)) {
                    Column(Modifier.fillMaxWidth().glass(colors, Radius.L).padding(vertical = 4.dp)) {
                        val rows = (if (kind == PassKind.Work) listOf<Pair<String, String>?>(null) else emptyList()) + parts
                        rows.forEachIndexed { i, part ->
                            if (i > 0) Box(Modifier.padding(start = 18.dp).fillMaxWidth().height(1.dp).background(colors.hairline))
                            val on = if (part == null) picked.isEmpty() else part.first in picked
                            val keys = part?.let { d.rules.unlockKeys(null, listOf(it.first)).toList() }.orEmpty()
                            Row(
                                Modifier.fillMaxWidth().toggleable(on, role = if (part == null) Role.RadioButton else Role.Checkbox) {
                                    onChange(u.copy(keys = emptyList(), partIds = when {
                                        part == null -> emptyList()
                                        part.first in picked -> picked - part.first
                                        else -> picked + part.first
                                    }))
                                }.padding(horizontal = 18.dp, vertical = 14.dp),
                                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.S),
                            ) {
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text(part?.second ?: stringResource(R.string.task_unlock_all), color = colors.textPrimary, style = AuroraType.BodyStrong, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    if (part != null) Text(pluralStringResource(R.plurals.detail_apps_count, keys.size, keys.size), color = colors.textTertiary, style = AuroraType.Caption)
                                }
                                if (keys.isNotEmpty()) IconStack(keys, d, max = 3, size = 24.dp)
                                app.aurora.ui.components.SelectionMark(on)
                            }
                        }
                    }
                }
                // How long and how often: two quiet rows, each a value between minus and plus.
                Column(Modifier.fillMaxWidth().glass(colors, Radius.L).padding(vertical = 4.dp)) {
                    val i = PassLengths.indexOfFirst { it >= u.minutes }.let { if (it < 0) PassLengths.lastIndex else it }
                    StepRow(stringResource(R.string.task_unlock_length), minutesText(u.minutes), u.minutes > PassLengths.first(), u.minutes < PassLengths.last(),
                        { onChange(u.copy(minutes = PassLengths[(i - 1).coerceAtLeast(0)])) },
                        { onChange(u.copy(minutes = PassLengths[(if (PassLengths[i] == u.minutes) i + 1 else i).coerceAtMost(PassLengths.lastIndex)])) })
                    Box(Modifier.padding(start = 18.dp).fillMaxWidth().height(1.dp).background(colors.hairline))
                    StepRow(stringResource(R.string.task_unlock_times), "${u.times}", u.times > 1, u.times < 10,
                        { onChange(u.copy(times = u.times - 1)) }, { onChange(u.copy(times = u.times + 1)) })
                }
                if (!isNew) AuroraButton(stringResource(R.string.task_unlock_delete), onDelete, Modifier.fillMaxWidth(), kind = ButtonKind.Quiet, icon = AuroraIcons.Trash)
            }
            Column(Modifier.fillMaxWidth().padding(horizontal = Space.Gutter).padding(top = Space.S, bottom = Space.M), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Space.S)) {
                if (needsParts) Text(stringResource(R.string.pass_pick_apps), color = colors.textTertiary, style = AuroraType.Secondary)
                AuroraButton(stringResource(if (isNew) R.string.task_unlock_add else R.string.settings_done), onDone, Modifier.fillMaxWidth(), enabled = valid)
            }
        }
    }
}

/** A part of the pass editor: a quiet label, then what it holds. */
@Composable
private fun EditorSection(label: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Eyebrow(label, Modifier.padding(start = 4.dp), color = Aurora.colors.textTertiary)
        content()
    }
}

/** How long a pass can open for, step by step. */
private val PassLengths = listOf(5, 10, 15, 20, 30, 45, 60, 90, 120, 180, 240)

/** A label, then its value between a quiet minus and plus. */
@Composable
private fun StepRow(label: String, value: String, canLess: Boolean, canMore: Boolean, onLess: () -> Unit, onMore: () -> Unit) {
    val colors = Aurora.colors
    Row(Modifier.fillMaxWidth().padding(start = 18.dp, end = 8.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), color = colors.textSecondary, style = AuroraType.Body)
        StepButton(AuroraIcons.Minus, canLess, onLess)
        Text(value, Modifier.widthIn(min = 64.dp), color = colors.textPrimary, style = AuroraType.BodyStrong.copy(fontFeatureSettings = "tnum"), textAlign = TextAlign.Center)
        StepButton(AuroraIcons.Plus, canMore, onMore)
    }
}

@Composable
private fun StepButton(icon: androidx.compose.ui.graphics.vector.ImageVector, enabled: Boolean, onClick: () -> Unit) {
    val colors = Aurora.colors
    Box(
        Modifier.size(40.dp).clip(CircleShape).alpha(if (enabled) 1f else 0.3f).then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier).glassControl(CircleShape),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, null, tint = colors.textPrimary, modifier = Modifier.size(16.dp)) }
}

/**
 * A saved session in one quiet row, at the top of starting it: its name, its strictness and its parts. A tap shows
 * every part; nothing here changes.
 */
@Composable
fun SessionAtAGlance(c: UsageConfig, d: ProtectData, onDetails: () -> Unit) {
    val colors = Aurora.colors
    val rules = d.rules
    Row(
        Modifier.fillMaxWidth().padding(bottom = Space.S).clip(Radius.L).glass(colors, Radius.L)
            .clickable(onClickLabel = stringResource(R.string.task_view_details), onClick = onDetails).padding(start = 18.dp, end = 12.dp, top = 14.dp, bottom = 14.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.S),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(c.name, color = colors.textPrimary, style = AuroraType.Title, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                listOf(
                    modeName(rules.modeOf(c)),
                    pluralStringResource(R.plurals.task_shields_count, c.allShieldIds.size, c.allShieldIds.size),
                    pluralStringResource(R.plurals.task_intents_count, c.allIntentIds.size, c.allIntentIds.size),
                ).joinToString(" · "),
                color = colors.textSecondary, style = AuroraType.Secondary, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        Icon(AuroraIcons.Chevron, null, tint = colors.textTertiary, modifier = Modifier.size(18.dp))
    }
}

/** The choice of Shields or Intents for a session: what is chosen, as one quiet line. */
@Composable
fun ChosenLine(shields: Int, intents: Int) {
    val colors = Aurora.colors
    Text(
        if (shields + intents == 0) stringResource(R.string.task_contents_none)
        else listOf(pluralStringResource(R.plurals.task_shields_count, shields, shields), pluralStringResource(R.plurals.task_intents_count, intents, intents)).joinToString(" · "),
        Modifier.padding(start = 4.dp, bottom = Space.XS), color = if (shields + intents == 0) colors.textTertiary else colors.textPrimary, style = AuroraType.Label,
    )
}
