package app.aurora.ui.onboarding

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.togetherWith
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.aurora.AuroraApplication
import app.aurora.auroraViewModel
import app.aurora.data.Access
import app.aurora.data.AccessState
import app.aurora.data.openAccessSettings
import app.aurora.data.openUsageAccessSettings
import app.aurora.plan.Allowance
import app.aurora.plan.ProtectedItem
import app.aurora.protect.Mode
import app.aurora.protect.key
import app.aurora.rememberAccess
import app.aurora.ui.components.AuroraButton
import app.aurora.ui.components.AuroraIcons
import app.aurora.ui.components.AuroraLight
import app.aurora.ui.components.AuroraMark
import app.aurora.ui.components.AuroraTextField
import app.aurora.ui.components.ButtonKind
import app.aurora.ui.components.ChipFlow
import app.aurora.ui.components.HorizonGlow
import app.aurora.ui.components.RadioRow
import app.aurora.ui.components.RowDivider
import app.aurora.ui.components.RowGroup
import app.aurora.ui.components.SkeletonRows
import app.aurora.ui.components.Wordmark
import app.aurora.ui.components.allowanceText
import app.aurora.ui.components.bleed
import app.aurora.ui.protect.AppRow
import app.aurora.ui.protect.LimitDialog
import app.aurora.ui.protect.ProtectModel
import app.aurora.ui.protect.ShieldDisclosure
import app.aurora.ui.protect.explain
import app.aurora.ui.protect.title
import app.aurora.ui.theme.Aurora
import app.aurora.ui.theme.AuroraType
import app.aurora.ui.theme.Motion
import app.aurora.ui.theme.Radius
import app.aurora.ui.theme.Space
import kotlinx.coroutines.launch

private const val PageCount = 8

private val Motivations = listOf("Studying", "Deep work", "Reading", "Fitness", "Relationships", "Rest", "Personal projects")

/**
 * Eight quiet steps: the idea, the problem, how aurora helps, what you want time for, what pulls you away,
 * how aurora should step in, the permissions that make it work, and first light. The horizon brightens as
 * you go. Every choice is saved as it's made, and the step is remembered, so leaving and coming back
 * continues where you were. No account; nothing is required.
 */
@Composable
fun OnboardingScreen(onFinish: () -> Unit) {
    val app = LocalContext.current.applicationContext as AuroraApplication
    val vm = auroraViewModel { ProtectModel(it) }
    val colors = Aurora.colors
    val reduced = Aurora.reducedMotion
    val prefs by app.preferences.preferences.collectAsStateWithLifecycle(initialValue = null)
    var page by rememberSaveable { mutableIntStateOf(-1) }
    // Continue where the person left off, once preferences are known.
    LaunchedEffect(prefs != null) { if (page < 0 && prefs != null) page = prefs!!.onboardingStep.coerceIn(0, PageCount - 1) }
    LaunchedEffect(page) { if (page >= 0) runCatching { app.preferences.setOnboardingStep(page) } }
    val notice by vm.notice.collectAsStateWithLifecycle()
    BackHandler(enabled = page > 0) { page-- }
    val p = page.coerceAtLeast(0)

    Box(Modifier.fillMaxSize().background(colors.background)) {
        HorizonGlow(
            intensity = 0.3f + 0.7f * p / (PageCount - 1),
            warmth = 0.75f - 0.45f * p / (PageCount - 1),
            modifier = Modifier.fillMaxWidth().fillMaxHeight(0.55f).align(Alignment.BottomCenter),
        )
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()) {
            Row(
                Modifier.fillMaxWidth().height(56.dp).padding(horizontal = Space.Gutter),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Wordmark(Modifier.weight(1f))
                PageDots(p)
            }
            BoxWithConstraints(Modifier.weight(1f)) {
                Column(
                    Modifier
                        .verticalScroll(rememberScrollState())
                        .heightIn(min = maxHeight)
                        .padding(horizontal = Space.Gutter),
                    verticalArrangement = Arrangement.Center,
                ) {
                    AnimatedContent(
                        targetState = p,
                        transitionSpec = {
                            val enter = if (reduced) fadeIn(tween(Motion.Standard)) else
                                fadeIn(tween(Motion.Gentle)) + slideInHorizontally(tween(Motion.Gentle, easing = Motion.Ease)) {
                                    if (targetState > initialState) it / 10 else -it / 10
                                }
                            enter togetherWith fadeOut(tween(Motion.Quick))
                        },
                        label = "page",
                    ) { step ->
                        when (step) {
                            0 -> Welcome()
                            1 -> Problem()
                            2 -> Solution()
                            3 -> Motivation(vm)
                            4 -> Distractions(vm)
                            5 -> HowToProtect(vm)
                            6 -> Permissions()
                            else -> FirstLight(vm)
                        }
                    }
                }
            }
            Column(
                Modifier.padding(horizontal = Space.Gutter, vertical = Space.M).animateContentSize(),
                verticalArrangement = Arrangement.spacedBy(Space.XS),
            ) {
                val label = when (p) {
                    0 -> "Begin"
                    4 -> "Continue"
                    PageCount - 1 -> "Go to Today"
                    else -> "Continue"
                }
                AuroraButton(label, {
                    if (p == PageCount - 1) {
                        app.scope.launch { runCatching { app.preferences.setOnboardingStep(0) } }
                        onFinish()
                    } else page = p + 1
                }, Modifier.fillMaxWidth())
                if (p in 3..6) AuroraButton("Skip for now", { page = p + 1 }, Modifier.fillMaxWidth(), kind = ButtonKind.Quiet)
            }
        }
    }
    LimitDialog(notice, vm::dismissNotice) {}
}

@Composable
private fun PageDots(page: Int) {
    val colors = Aurora.colors
    Row(
        Modifier.semantics { contentDescription = "Step ${page + 1} of $PageCount" },
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        repeat(PageCount) { i ->
            Box(
                Modifier
                    .animateContentSize(tween(Motion.Standard, easing = Motion.Ease))
                    .height(4.dp)
                    .width(if (i == page) 16.dp else 5.dp)
                    .clip(Radius.Pill)
                    .background(if (i <= page) colors.accent else colors.hairline),
            )
        }
    }
}

@Composable
private fun Title(eyebrow: String?, title: String, body: String? = null) {
    val colors = Aurora.colors
    Column {
        if (eyebrow != null) {
            Text(eyebrow, color = colors.textTertiary, style = AuroraType.Eyebrow)
            Spacer(Modifier.height(Space.S))
        }
        Text(title, Modifier.semantics { heading() }, color = colors.textPrimary, style = AuroraType.Display)
        if (body != null) {
            Spacer(Modifier.height(Space.M))
            Text(body, color = colors.textSecondary, style = AuroraType.Body)
        }
    }
}

@Composable
private fun Welcome() {
    Column {
        AuroraMark(72.dp)
        Spacer(Modifier.height(Space.XL))
        Title(null, "Make room for\nwhat matters.", "aurora helps you take back control of your attention, one intentional choice at a time.")
    }
}

@Composable
private fun Problem() {
    Title(
        "the problem",
        "Most phone time\nisn't a decision.",
        "You unlock for one thing and look up twenty minutes later. That's how many apps are designed to work. It isn't a failing of yours.\n\n" +
            "aurora doesn't scold or count your mistakes. It gives you back the moment where you choose.",
    )
}

@Composable
private fun Solution() {
    Column {
        Title("how aurora helps", "A moment to choose,\nand time to focus.")
        Spacer(Modifier.height(Space.XL))
        Column(verticalArrangement = Arrangement.spacedBy(Space.L)) {
            Point(AuroraIcons.Protect, "Shield", "A calm pause when an app you chose opens. Flexible, with a short wait, or firmly closed: you decide.")
            Point(AuroraIcons.Quote, "Intent", "Your own reason, shown at the moment you need it, before an app opens.")
            Point(AuroraIcons.Focus, "Focus", "Protected time for what matters. Shield holds your distractions back until it ends.")
        }
    }
}

@Composable
private fun Motivation(vm: ProtectModel) {
    val data by vm.data.collectAsStateWithLifecycle()
    val chosen = data?.rules?.motivations.orEmpty()
    var custom by rememberSaveable { mutableStateOf("") }
    val options = (Motivations + chosen).distinct()
    Column {
        Title("for you", "What do you want\nmore time for?", "aurora reminds you of this when it matters. It stays on this phone.")
        Spacer(Modifier.height(Space.L))
        ChipFlow(options, chosen.toSet(), { m ->
            vm.edit { r -> r.copy(motivations = if (m in r.motivations) r.motivations - m else r.motivations + m) }
        }, label = { it })
        Spacer(Modifier.height(Space.M))
        AuroraTextField(
            custom,
            { if (it.length <= 30) custom = it },
            "Something else…",
            capitalization = KeyboardCapitalization.Sentences,
            onDone = {
                val m = custom.trim()
                if (m.isNotEmpty()) vm.edit { r -> if (m in r.motivations) r else r.copy(motivations = r.motivations + m) }
                custom = ""
            },
        )
    }
}

@Composable
private fun Distractions(vm: ProtectModel) {
    val apps by vm.apps.collectAsStateWithLifecycle()
    val data by vm.data.collectAsStateWithLifecycle()
    val exempt by vm.exempt.collectAsStateWithLifecycle()
    val chosen = data?.list?.apps.orEmpty().toSet()
    // Chosen apps lead, fixed when the list loads, so ticking never moves a row.
    val ordered = remember(apps) { apps?.filter { it.packageName !in exempt }?.sortedByDescending { it.packageName in chosen } }
    Column {
        Title(
            "what pulls you away",
            "Choose your\ndistractions.",
            "aurora Free protects ${allowanceText(data?.active?.size ?: 0, data?.max(Allowance.ProtectedItems))} apps and sites. You can change these anytime.",
        )
        Spacer(Modifier.height(Space.L))
        if (ordered == null) SkeletonRows(4)
        else RowGroup(Modifier.bleed(Space.Gutter)) {
            ordered.take(30).forEachIndexed { i, app ->
                if (i > 0) RowDivider(72.dp)
                AppRow(app, app.packageName in chosen, null, enabled = true) { ProtectedItem.App.of(app.packageName)?.let { vm.toggle(it) } }
            }
        }
    }
}

private val StarterModes = listOf(Mode.Flexible, Mode.Friction, Mode.Block)

@Composable
private fun HowToProtect(vm: ProtectModel) {
    val data by vm.data.collectAsStateWithLifecycle()
    val current = data?.rules?.defaultRule?.mode ?: Mode.Friction
    Column {
        Title("how aurora steps in", "Choose how firm\nto be.", "This applies to the apps you chose. Each app can be different later, and there's more in Protect: Cooldown, schedules, limits and Lockdown.")
        Spacer(Modifier.height(Space.L))
        RowGroup(Modifier.bleed(Space.Gutter)) {
            StarterModes.forEachIndexed { i, mode ->
                if (i > 0) RowDivider()
                RadioRow(mode.title(), mode.explain(), current == mode, onClick = {
                    vm.edit { r ->
                        val chosen = data?.list?.items.orEmpty().map { it.key }
                        r.copy(
                            defaultRule = r.defaultRule.copy(mode = mode),
                            apps = r.apps + chosen.associateWith { r.rule(it).copy(mode = mode) },
                        )
                    }
                }, badge = if (mode == Mode.Friction) "suggested" else null)
            }
        }
    }
}

@Composable
private fun Permissions() {
    val context = LocalContext.current
    val shield = rememberAccess(Access.Accessibility) == AccessState.Granted
    val usage = rememberAccess(Access.Usage) == AccessState.Granted
    var disclosure by rememberSaveable { mutableStateOf(false) }
    Column {
        Title("permissions", "Two switches,\nboth yours.", "Android asks you to turn these on yourself. aurora explains each one first and never turns anything on for you.")
        Spacer(Modifier.height(Space.L))
        Column(verticalArrangement = Arrangement.spacedBy(Space.L)) {
            Permission(
                AuroraIcons.Lock, "Shield", "Accessibility",
                "Lets aurora notice which app is in front, so it can pause you. It reads only the app's name, never your screen.",
                shield,
            ) { disclosure = true }
            Permission(
                AuroraIcons.Insights, "Screen time", "Usage access",
                "Powers Insights, goals and daily limits. Read on this phone only.",
                usage,
            ) { openUsageAccessSettings(context) }
        }
    }
    if (disclosure) ShieldDisclosure(
        onContinue = {
            disclosure = false
            openAccessSettings(context, Access.Accessibility)
        },
        onDismiss = { disclosure = false },
    )
}

@Composable
private fun Permission(icon: ImageVector, title: String, android: String, body: String, on: Boolean, onAllow: () -> Unit) {
    val colors = Aurora.colors
    Row(horizontalArrangement = Arrangement.spacedBy(Space.M)) {
        Box(Modifier.size(40.dp).clip(Radius.Pill).background(colors.accentSoft), contentAlignment = Alignment.Center) {
            Icon(if (on) AuroraIcons.Check else icon, null, tint = colors.accent, modifier = Modifier.size(20.dp))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, color = colors.textPrimary, style = AuroraType.BodyStrong)
            Text("Android calls it $android", color = colors.textTertiary, style = AuroraType.Caption)
            Text(body, color = colors.textSecondary, style = AuroraType.Secondary)
            if (on) Text("Allowed", color = colors.accent, style = AuroraType.Label)
            else AuroraButton("Allow", onAllow, Modifier.padding(top = Space.XS), kind = ButtonKind.Secondary)
        }
    }
}

@Composable
private fun FirstLight(vm: ProtectModel) {
    val data by vm.data.collectAsStateWithLifecycle()
    val count = data?.active?.size ?: 0
    val motivation = data?.rules?.motivations?.firstOrNull()?.lowercase()
    Column {
        // A first day starts dark: the light grows only from real progress.
        AuroraLight(glow = 0f, rise = 0f, modifier = Modifier.fillMaxWidth().height(180.dp), warmth = 0.6f)
        Spacer(Modifier.height(Space.L))
        Title(
            "first light",
            "This is where\nit begins.",
            buildString {
                append(if (count == 0) "Nothing is protected yet, and that's fine. " else "$count ${if (count == 1) "app is" else "apps are"} in your care. ")
                append("Your light grows with every intentional choice")
                append(if (motivation != null) ", and with more time for $motivation." else ".")
            },
        )
    }
}

@Composable
private fun Point(icon: ImageVector, title: String, body: String) {
    val colors = Aurora.colors
    Row(horizontalArrangement = Arrangement.spacedBy(Space.M), modifier = Modifier.semantics(mergeDescendants = true) { }) {
        Box(Modifier.size(40.dp).clip(Radius.Pill).background(colors.accentSoft), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = colors.accent, modifier = Modifier.size(20.dp))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, color = colors.textPrimary, style = AuroraType.BodyStrong)
            Text(body, color = colors.textSecondary, style = AuroraType.Secondary)
        }
    }
}
