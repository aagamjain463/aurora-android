package app.aurora.protect

import app.aurora.ui.components.ArtTier
import app.aurora.ui.components.AuroraIcons
import app.aurora.ui.components.FirstLightBackdrop
import app.aurora.ui.components.LitBadge
import app.aurora.ui.components.Wordmark
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
import androidx.compose.runtime.rememberCoroutineScope
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
import app.aurora.finishIntent
import app.aurora.grantAccess
import app.aurora.record
import app.aurora.reflectionStart
import app.aurora.startIntent
import app.aurora.wentBack
import app.aurora.ui.components.AuroraButton
import app.aurora.ui.components.AuroraLandscape
import app.aurora.ui.components.AuroraMark
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
    AuroraTheme(darkTheme = true, reduceMotion = prefs?.reduceMotion == true) {
        val pkg = request.packageName
        val website = pkg.startsWith(WebPrefix)
        val label by produceState(if (website) pkg.removePrefix(WebPrefix) else "", pkg) {
            if (!website) value = app.catalog.label(pkg) ?: pkg
        }
        val icon by produceState<ImageBitmap?>(null, pkg) {
            if (!website) value = app.catalog.app(pkg)?.icon
        }
        val now by produceState(System.currentTimeMillis()) {
            while (true) {
                value = System.currentTimeMillis()
                delay(500)
            }
        }
        LaunchedEffect(request.eventUptime) {
            if (request.fresh) runCatching {
                app.record { s ->
                    s.copy(
                        shields = s.shields + 1,
                        intentPrompts = s.intentPrompts + if (request.reason == ShieldReason.Intent && request.decision?.timeUp != true) 1 else 0,
                        remindersShown = s.remindersShown + if (request.decision?.timeUp == true) 1 else 0,
                    )
                }
            }
        }
        val decision = request.decision ?: Decision.Shield(pkg, request.reason, request.until)
        val home = { close(true) }
        ShieldFrame(label, icon) {
            when (decision.reason) {
                ShieldReason.AlwaysBlock, ShieldReason.Flexible, ShieldReason.Focus, ShieldReason.Limit, ShieldReason.IntentCooldown ->
                    Blocked(decision, label, now, home, onEnded = { close(false) })
                ShieldReason.Cooldown -> CooldownFlow(app, decision, label, now, home, onOpen = { close(false) })
                ShieldReason.Friction -> FrictionFlow(app, decision, label, home, onOpen = { close(false) })
                ShieldReason.Intent -> IntentFlow(app, decision, label, now, home, onOpen = { close(false) })
            }
        }
    }
}

/**
 * The Shield's stage: First Light at full strength, the mark, then the app in question held in a soft
 * halo at the centre, and the moment's content set centred beneath it.
 */
@Composable
private fun ShieldFrame(label: String, icon: ImageBitmap?, content: @Composable () -> Unit) {
    val colors = Aurora.colors
    Box(Modifier.fillMaxSize().background(colors.background)) {
        FirstLightBackdrop(ArtTier.Immersive)
        Column(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Space.Gutter),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(Modifier.fillMaxWidth().padding(top = Space.S), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.S)) {
                AuroraMark(30.dp)
                Wordmark()
            }
            Spacer(Modifier.height(104.dp))
            Box(contentAlignment = Alignment.Center) {
                Box(Modifier.size(132.dp).background(Brush.radialGradient(listOf(colors.sun.copy(alpha = 0.28f), Color.Transparent))))
                if (icon != null) {
                    Image(icon, null, Modifier.size(76.dp).clip(RoundedCornerShape(22.dp)))
                } else {
                    Box(Modifier.size(76.dp).clip(RoundedCornerShape(22.dp)).background(colors.elevated))
                }
            }
            Text(label, color = colors.textSecondary, style = AuroraType.Secondary, textAlign = TextAlign.Center)
            Spacer(Modifier.height(Space.M))
            content()
            Spacer(Modifier.height(Space.XL))
        }
    }
}

@Composable
private fun Eyebrow(text: String, warm: Boolean = false) {
    Text(text, Modifier.fillMaxWidth(), color = if (warm) Aurora.colors.sun else Aurora.colors.accent, style = AuroraType.Eyebrow, textAlign = TextAlign.Center)
}

/** The Shield's question. The app's name, when it appears, is lit in First Light cream. */
@Composable
private fun Headline(text: String, highlight: String? = null) {
    val colors = Aurora.colors
    val at = highlight?.takeIf { it.isNotBlank() }?.let { text.indexOf(it) } ?: -1
    val annotated = androidx.compose.ui.text.buildAnnotatedString {
        append(text)
        if (at >= 0) addStyle(androidx.compose.ui.text.SpanStyle(color = colors.headlineLead), at, at + highlight!!.length)
    }
    Text(annotated, Modifier.fillMaxWidth().padding(top = Space.XS).semantics { heading() }, color = colors.textPrimary, style = AuroraType.Display, textAlign = TextAlign.Center)
}

@Composable
private fun Body(text: String) {
    Text(text, Modifier.fillMaxWidth().padding(top = Space.S), color = Aurora.colors.textSecondary, style = AuroraType.Body, textAlign = TextAlign.Center)
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
            drawArc(Brush.sweepGradient(listOf(colors.accent, colors.cta.last(), colors.accent)), -90f, 360f * (1f - fraction), false, tl, box,
                style = androidx.compose.ui.graphics.drawscope.Stroke(stroke, cap = androidx.compose.ui.graphics.StrokeCap.Round))
        }
        Text(if (seconds > 0) "${seconds}s" else "✓", color = colors.textPrimary, style = AuroraType.Title)
    }
}

/** A live countdown with the clock time it ends. */
@Composable
private fun Countdown(until: Long, now: Long, caption: String) {
    val colors = Aurora.colors
    Column(Modifier.padding(vertical = Space.L), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            formatClock(until - now),
            Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            color = colors.textPrimary,
            style = AuroraType.Hero,
        )
        Text(caption, color = colors.textSecondary, style = AuroraType.Secondary, textAlign = TextAlign.Center)
    }
}

@Composable
private fun Actions(content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = Space.L), verticalArrangement = Arrangement.spacedBy(Space.S)) { content() }
}

// ---- Blocks: no way through -------------------------------------------------------------------

@Composable
private fun Blocked(decision: Decision.Shield, label: String, now: Long, home: () -> Unit, onEnded: () -> Unit) {
    val until = decision.until
    if (until != null && now >= until && decision.reason != ShieldReason.Limit) {
        Eyebrow(stringResource(R.string.shield_ended_eyebrow))
        Headline(stringResource(R.string.shield_ended_title))
        Body(stringResource(R.string.shield_ended_body, label))
        Actions {
            AuroraButton(stringResource(R.string.shield_open_app, label), onEnded, Modifier.fillMaxWidth())
            AuroraButton(stringResource(R.string.shield_return_home), home, Modifier.fillMaxWidth(), kind = ButtonKind.Quiet)
        }
        return
    }
    val schedule = decision.restriction?.label?.takeIf { it.isNotBlank() }
    when (decision.reason) {
        ShieldReason.AlwaysBlock -> {
            Eyebrow(stringResource(R.string.mode_always_block), warm = true)
            Headline(stringResource(R.string.shield_blocked_title, label), label)
            Body(stringResource(R.string.mode_always_block_desc))
        }
        ShieldReason.Flexible -> {
            Eyebrow(schedule ?: stringResource(R.string.mode_flexible))
            Headline(stringResource(R.string.shield_blocked_title, label), label)
            Body(stringResource(R.string.mode_flexible_desc))
            Body(stringResource(R.string.shield_flexible_how_to_off))
        }
        ShieldReason.Focus -> {
            Eyebrow(stringResource(R.string.focus_name))
            Headline(stringResource(R.string.shield_focus_title))
            Body(stringResource(R.string.shield_focus_body, label))
        }
        ShieldReason.Limit -> {
            Eyebrow(stringResource(R.string.limits_name))
            Headline(stringResource(R.string.shield_limit_title, label), label)
            Body(stringResource(R.string.shield_limit_body))
        }
        else -> {
            Eyebrow(stringResource(R.string.intent_name))
            Headline(stringResource(R.string.shield_intent_cooldown_title))
            Body(stringResource(R.string.shield_intent_cooldown_body, label))
        }
    }
    if (until != null) {
        val caption = when (decision.reason) {
            ShieldReason.AlwaysBlock -> stringResource(R.string.shield_always_block_ends, clockTime(until))
            ShieldReason.Limit -> stringResource(R.string.shield_limit_resets)
            ShieldReason.IntentCooldown -> stringResource(R.string.shield_opens_again_at, clockTime(until))
            else -> stringResource(R.string.shield_ends_at, clockTime(until))
        }
        Countdown(until, now, caption)
    }
    Actions { AuroraButton(stringResource(R.string.shield_return_home), home, Modifier.fillMaxWidth()) }
}

// ---- Cooldown: wait first, then timed access ----------------------------------------------------

@Composable
private fun CooldownFlow(app: AuroraApplication, decision: Decision.Shield, label: String, now: Long, home: () -> Unit, onOpen: () -> Unit) {
    val restriction = decision.restriction ?: return Blocked(decision, label, now, home, onOpen)
    // The wait starts at the first attempt and is stored, so leaving and coming back continues it.
    val readyAt by produceState(decision.readyAt, decision.packageName, restriction.id) {
        if (value == null) value = try { app.cooldownWait(decision.packageName, restriction) } catch (_: IOException) { null }
    }
    val ready = readyAt?.let { now >= it } == true
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    if (!ready) {
        Eyebrow(stringResource(R.string.mode_cooldown))
        Headline(stringResource(R.string.shield_cooldown_title, label), label)
        Body(stringResource(R.string.shield_cooldown_body))
        readyAt?.let { Countdown(it, now, stringResource(R.string.shield_cooldown_ready_at, clockTime(it))) }
        Actions {
            AuroraButton(stringResource(R.string.shield_return_home), {
                scope.launch { runCatching { app.wentBack(intent = false) } }
                home()
            }, Modifier.fillMaxWidth())
        }
    } else {
        Eyebrow(stringResource(R.string.mode_cooldown))
        Headline(stringResource(R.string.shield_cooldown_done_title))
        Body(stringResource(R.string.shield_cooldown_done_body))
        UsageChoice(label, decision, onBack = null) { minutes ->
            haptics.performHapticFeedback(HapticFeedbackType.Confirm)
            scope.launch {
                try {
                    app.grantAccess(decision.packageName, minutes, Mode.Cooldown)
                    onOpen()
                } catch (_: IOException) {
                    // Not stored, so not granted: the Shield stays.
                }
            }
        }
        Actions { AuroraButton(stringResource(R.string.shield_return_home), home, Modifier.fillMaxWidth(), kind = ButtonKind.Quiet) }
    }
}

/** "How long do you want to use {app}?": presets and a custom time, then open. */
@Composable
private fun UsageChoice(label: String, decision: Decision.Shield, initial: Int? = null, onBack: (() -> Unit)?, onChoose: (Int) -> Unit) {
    var minutes by remember { mutableStateOf(initial) }
    // Never longer than the protection that's running: access can't outlast the restriction anyway.
    val max = decision.until?.let { ((it - System.currentTimeMillis()) / 60_000).toInt().coerceIn(1, 24 * 60) } ?: (24 * 60)
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
private fun FrictionFlow(app: AuroraApplication, decision: Decision.Shield, label: String, home: () -> Unit, onOpen: () -> Unit) {
    val settings = decision.restriction?.friction ?: FrictionSettings()
    var step by remember { mutableStateOf(FrictionStep.Task) }
    val scope = rememberCoroutineScope()
    val goHome = {
        scope.launch { runCatching { app.wentBack(intent = false) } }
        home()
    }
    AnimatedContent(step, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "friction") { s ->
        Column {
            Eyebrow(stringResource(R.string.mode_friction))
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
                    Actions { AuroraButton(stringResource(R.string.shield_return_home), goHome, Modifier.fillMaxWidth(), kind = ButtonKind.Quiet) }
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
                            }
                        }
                    }
                    Actions { AuroraButton(stringResource(R.string.shield_return_home), goHome, Modifier.fillMaxWidth(), kind = ButtonKind.Quiet) }
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
            Text(stringResource(R.string.friction_math_wrong), Modifier.semantics { liveRegion = LiveRegionMode.Polite }, color = colors.dawnText, style = AuroraType.Secondary)
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
            val start by produceState<Long?>(null, key) { value = try { app.reflectionStart("friction:$key") } catch (_: IOException) { System.currentTimeMillis() } }
            val now by produceState(System.currentTimeMillis()) {
                while (true) {
                    value = System.currentTimeMillis()
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
private fun IntentFlow(app: AuroraApplication, decision: Decision.Shield, label: String, now: Long, home: () -> Unit, onOpen: () -> Unit) {
    val colors = Aurora.colors
    val pkg = decision.packageName
    val rules by app.rules.state.collectAsStateWithLifecycle()
    val config = rules?.value?.apps?.get(pkg)?.intent ?: IntentConfig()
    val use = app.runtime.value?.intentUses?.get(pkg)
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    var step by remember { mutableStateOf(IntentStep.Reflect) }
    val start = { minutes: Int ->
        haptics.performHapticFeedback(HapticFeedbackType.Confirm)
        scope.launch {
            try {
                app.startIntent(pkg, minutes, config)
                onOpen()
            } catch (_: IOException) {
                // Not stored, so not started.
            }
        }
        Unit
    }

    if (decision.timeUp && step == IntentStep.Reflect) {
        val chosen = ((use?.allowanceMs ?: 0L) / 60_000).toInt()
        Eyebrow(stringResource(R.string.intent_name))
        Headline(stringResource(R.string.intent_reminder_title, minutesText(chosen.coerceAtLeast(1))))
        Body(stringResource(R.string.intent_reminder_body, label))
        Actions {
            AuroraButton(stringResource(R.string.intent_reminder_finish), {
                scope.launch { runCatching { app.finishIntent(pkg) } }
                home()
            }, Modifier.fillMaxWidth())
            AuroraButton(stringResource(R.string.intent_reminder_continue), { step = IntentStep.Usage }, Modifier.fillMaxWidth(), kind = ButtonKind.Secondary)
        }
        return
    }

    when (step) {
        IntentStep.Reflect -> {
            val pauseStart by produceState<Long?>(null, pkg) {
                value = if (config.reflectionSeconds <= 0) 0L
                else try { app.reflectionStart(pkg) } catch (_: IOException) { System.currentTimeMillis() }
            }
            val left = pauseStart?.let { it + config.reflectionSeconds * 1000L - now } ?: (config.reflectionSeconds * 1000L)
            Eyebrow(stringResource(R.string.intent_name))
            Headline(stringResource(R.string.intent_prompt_title, label), label)
            Body(stringResource(R.string.intent_prompt_question))
            if (config.reflectionSeconds > 0) PauseRing(left.coerceAtLeast(0), config.reflectionSeconds * 1000L)
            if (config.reason.isNotBlank()) {
                ReasonCard(AuroraIcons.Spark, stringResource(R.string.intent_your_reason), config.reason, warm = false)
            }
            if (config.alternative.isNotBlank()) {
                ReasonCard(AuroraIcons.Target, stringResource(R.string.intent_instead), config.alternative, warm = true)
            }
            if (config.reflectionSeconds > 0) {
                Text(
                    if (left > 0) stringResource(R.string.intent_pause_left, ((left + 999) / 1000).toInt()) else stringResource(R.string.intent_pause_done),
                    Modifier.fillMaxWidth().padding(top = Space.M).semantics { liveRegion = LiveRegionMode.Polite },
                    color = colors.textSecondary,
                    style = AuroraType.Secondary,
                    textAlign = TextAlign.Center,
                )
            }
            Actions {
                AuroraButton(stringResource(R.string.shield_return_home), {
                    scope.launch { runCatching { app.wentBack(intent = true) } }
                    home()
                }, Modifier.fillMaxWidth())
                AuroraButton(
                    stringResource(R.string.intent_continue),
                    { step = IntentStep.Usage },
                    Modifier.fillMaxWidth(),
                    kind = ButtonKind.Secondary,
                    enabled = left <= 0 && pauseStart != null,
                )
            }
        }
        IntentStep.Usage -> {
            Eyebrow(stringResource(R.string.intent_name))
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
        LitBadge(icon, tint = if (warm) colors.sun else colors.accent, size = 52)
        Column(Modifier.weight(1f)) {
            Text(label, color = colors.textSecondary, style = AuroraType.Caption)
            Text(text, color = colors.textPrimary, style = AuroraType.Title)
        }
    }
}
