package app.aurora.ui

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateSetOf
import androidx.compose.runtime.remember
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import app.aurora.AuroraApplication
import app.aurora.adoptOnboarding
import app.aurora.ui.account.AccountScreen
import app.aurora.ui.account.ConfirmingDialog
import app.aurora.ui.plus.LimitPromptDialog
import app.aurora.R
import app.aurora.ui.components.AuroraIcons
import app.aurora.ui.components.AuroraNavBar
import app.aurora.ui.components.backdropSource
import app.aurora.ui.components.rememberBackdrop
import app.aurora.ui.components.NavItem
import app.aurora.ui.focus.FocusScreen
import app.aurora.ui.guide.GuideScreen
import app.aurora.ui.home.HomeScreen
import app.aurora.ui.home.SavedSessionsScreen
import app.aurora.ui.session.RunDetailScreen
import app.aurora.ui.onboarding.OnboardingScreen
import android.net.Uri
import app.aurora.ui.plus.PlusScreen
import app.aurora.ui.protect.AppPickerScreen
import app.aurora.ui.protect.ItemScreen
import app.aurora.ui.protect.ProtectRoutes
import app.aurora.ui.configure.ConfigureScreen
import app.aurora.ui.components.AuroraDialog
import androidx.compose.ui.res.stringResource
import app.aurora.ui.library.IntentEditorScreen
import app.aurora.ui.library.IntentLibraryScreen
import app.aurora.ui.library.ShieldEditorScreen
import app.aurora.ui.library.ShieldLibraryScreen
import app.aurora.ui.strict.FocusRunScreen
import app.aurora.ui.strict.StrictModeScreen
import app.aurora.ui.track.GoalEditorScreen
import app.aurora.ui.track.SessionHistoryScreen
import app.aurora.ui.track.HabitHistoryScreen
import app.aurora.ui.insights.AppUsageScreen
import app.aurora.ui.insights.Period
import app.aurora.ui.track.TrackScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.aurora.ui.protect.WebScreen
import app.aurora.ui.settings.SettingsScreen
import app.aurora.ui.theme.Aurora
import app.aurora.ui.theme.Motion
import kotlinx.coroutines.launch

object Routes {
    const val Onboarding = "onboarding"
    const val Home = "home"
    const val Shield = "shield"
    const val Intent = "intent"
    const val Track = "track"
    const val Settings = "settings"
    const val Focus = "focus"
    const val Configure = "configure"
    const val ShieldEdit = "shield-edit"
    const val IntentEdit = "intent-edit"
    const val GoalEdit = "goal-edit"
    const val Strict = "strict"
    const val FocusRun = "focus-run"
    const val Account = "account"
    const val History = "history"
    const val HabitHistory = "habit-history"
    const val AppUsage = "app-usage"
    const val Guide = "guide"
    const val Saved = "saved"
    const val Run = "run"
}

/** Four pillars: activate (Home), create (Shield), configure (Intent), improve (Track). */
private val Tabs = listOf(
    NavItem(Routes.Home, R.string.tab_home, AuroraIcons.Home),
    NavItem(Routes.Shield, R.string.tab_shield, AuroraIcons.Protect),
    NavItem(Routes.Intent, R.string.tab_intent, AuroraIcons.Intent),
    NavItem(Routes.Track, R.string.tab_track, AuroraIcons.Track),
)
private val TabRoutes = Tabs.map { it.route }.toSet()

/** Tabs keep their own scroll and state; Home is always the root of the back stack. */
private fun NavHostController.selectTab(route: String) {
    navigate(route) {
        popUpTo(Routes.Home) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

/** Hands [value] back to the screen below (a flow that sent the person to create something), then returns. */
private fun NavHostController.returnWith(key: String, value: String) {
    previousBackStackEntry?.savedStateHandle?.set(key, value)
    popBackStack()
}

@Composable
fun AuroraNavHost(startOnboarded: Boolean) {
    val nav = rememberNavController()
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route
    val reduced = Aurora.reducedMotion
    val app = LocalContext.current.applicationContext as AuroraApplication
    val scope = rememberCoroutineScope()

    fun AnimatedContentTransitionScope<NavBackStackEntry>.isTabSwitch() =
        initialState.destination.route in TabRoutes && targetState.destination.route in TabRoutes

    val push = tween<Float>(Motion.Standard, easing = Motion.Ease)
    val slide = tween<androidx.compose.ui.unit.IntOffset>(Motion.Standard, easing = Motion.Ease)
    val back: () -> Unit = { nav.popBackStack() }
    val plus: () -> Unit = { nav.navigate(ProtectRoutes.Plus) }
    val settings: () -> Unit = { nav.navigate(Routes.Settings) }
    val guide: () -> Unit = { nav.navigate(Routes.Guide) }
    val openItem: (String) -> Unit = { key -> nav.navigate("${ProtectRoutes.Item}/${Uri.encode(key)}") }
    // The tabs whose slots are composed: on screen, or beneath a screen sliding in or out over them.
    val slots = remember { mutableStateSetOf<String>() }
    // Everything the dock floats over: its glass blurs this, never itself.
    val backdrop = rememberBackdrop()
    Box(Modifier.fillMaxSize().background(Aurora.colors.background)) {
        Box(Modifier.fillMaxSize().backdropSource(backdrop)) {
        TabPages(route, slots) { tab ->
            when (tab) {
                Routes.Home -> HomeScreen(
                    onStart = { nav.navigate("${Routes.Configure}?custom=false") },
                    onOpenFocus = { id -> nav.navigate("${Routes.FocusRun}/${Uri.encode(id)}") },
                    onOpenLegacyFocus = { nav.navigate(Routes.Focus) },
                    onOpenSettings = settings,
                    // Saved without starting: the goal flow is Configure's edit-and-save path.
                    onEditSession = { id -> nav.navigate("${Routes.Configure}?custom=true&config=${Uri.encode(id)}&goal=true") },
                    // Start on a saved session asks when, like Start session: now, later or on a schedule.
                    onStartSession = { id -> nav.navigate("${Routes.Configure}?custom=true&config=${Uri.encode(id)}&saved=true") },
                    onOpenSaved = { nav.navigate(Routes.Saved) },
                    onOpenRun = { id -> nav.navigate("${Routes.Run}/${Uri.encode(id)}") },
                )
                Routes.Shield -> ShieldLibraryScreen(
                    onCreate = { nav.navigate(Routes.ShieldEdit) },
                    onEdit = { id -> nav.navigate("${Routes.ShieldEdit}?id=$id") },
                    onOpenSettings = settings,
                )
                Routes.Intent -> IntentLibraryScreen(onCreate = { nav.navigate(Routes.IntentEdit) }, onEdit = { id -> nav.navigate("${Routes.IntentEdit}?id=$id") }, onOpenSettings = settings)
                Routes.Track -> TrackScreen(onPlus = plus, onOpenSettings = settings, onCreateGoal = { nav.navigate(Routes.GoalEdit) }, onOpenHistory = { nav.navigate(Routes.History) }, onOpenHabitHistory = { nav.navigate(Routes.HabitHistory) }, onOpenAppUsage = { nav.navigate("${Routes.AppUsage}/${it.name}") })
            }
        }
        NavHost(
            navController = nav,
            startDestination = if (startOnboarded) Routes.Home else Routes.Onboarding,
            modifier = Modifier.fillMaxSize(),
            // Tabs switch at once, as a tab bar does: no frame of two pages fading over each other, and the tab
            // answers the finger the moment it is touched. Its blocks still climb in on the first visit ([rise]).
            // Screens pushed on top slide in over the one below, which sinks back, so depth reads at a glance.
            // Only transforms, never page-wide fades: a fading page is redrawn offscreen every frame, and that is
            // what made pushes stutter on mid-range GPUs. Reduced motion keeps only the fades.
            enterTransition = {
                when {
                    reduced -> fadeIn(tween(Motion.Standard))
                    isTabSwitch() -> EnterTransition.None
                    else -> slideInHorizontally(slide) { it }
                }
            },
            exitTransition = {
                when {
                    reduced -> fadeOut(tween(Motion.Quick))
                    isTabSwitch() -> ExitTransition.None
                    else -> scaleOut(push, targetScale = SunkScale) + slideOutHorizontally(slide) { -it / 8 }
                }
            },
            popEnterTransition = {
                when {
                    reduced -> fadeIn(tween(Motion.Standard))
                    isTabSwitch() -> EnterTransition.None
                    else -> scaleIn(push, initialScale = SunkScale) + slideInHorizontally(slide) { -it / 8 }
                }
            },
            popExitTransition = {
                when {
                    reduced -> fadeOut(tween(Motion.Quick))
                    isTabSwitch() -> ExitTransition.None
                    else -> slideOutHorizontally(slide) { it }
                }
            },
        ) {
            composable(Routes.Onboarding) {
                OnboardingScreen(onFinish = {
                    scope.launch {
                        runCatching { app.adoptOnboarding() }
                        app.preferences.setOnboarded(true)
                    }
                    nav.navigate(Routes.Home) { popUpTo(Routes.Onboarding) { inclusive = true } }
                })
            }
            composable(Routes.Home) { TabSlot(Routes.Home, slots) }
            composable(Routes.Saved) {
                SavedSessionsScreen(
                    back,
                    onEdit = { id -> nav.navigate("${Routes.Configure}?custom=true&config=${Uri.encode(id)}&goal=true") },
                    onStart = { id -> nav.navigate("${Routes.Configure}?custom=true&config=${Uri.encode(id)}&saved=true") },
                    onNew = { nav.navigate("${Routes.Configure}?custom=true") },
                )
            }
            composable("${Routes.Run}/{id}") { e ->
                RunDetailScreen(Uri.decode(e.arguments?.getString("id").orEmpty()), back) { id -> nav.navigate("${Routes.FocusRun}/${Uri.encode(id)}") }
            }
            composable(Routes.Shield) { TabSlot(Routes.Shield, slots) }
            composable("${Routes.ShieldEdit}?id={id}") { e ->
                ShieldEditorScreen(e.arguments?.getString("id"), back, onSaved = { id -> nav.returnWith("createdShield", id) }, onPlus = plus, onOpenWeb = { nav.navigate(ProtectRoutes.Web) })
            }
            composable(Routes.Intent) { TabSlot(Routes.Intent, slots) }
            composable("${Routes.IntentEdit}?id={id}") { e ->
                IntentEditorScreen(e.arguments?.getString("id"), back, onSaved = { id -> nav.returnWith("createdIntent", id) }, onPlus = plus)
            }
            composable("${Routes.Configure}?custom={custom}&config={config}&goal={goal}&saved={saved}") { e ->
                val handle = e.savedStateHandle
                val createdShield by handle.getStateFlow<String?>("createdShield", null).collectAsStateWithLifecycle()
                val createdIntent by handle.getStateFlow<String?>("createdIntent", null).collectAsStateWithLifecycle()
                val forGoal = e.arguments?.getString("goal") == "true"
                ConfigureScreen(
                    custom = e.arguments?.getString("custom") != "false",
                    configId = e.arguments?.getString("config"),
                    forGoal = forGoal,
                    createdShield = createdShield,
                    createdIntent = createdIntent,
                    onBack = back,
                    onDone = { id -> if (forGoal) nav.returnWith("createdConfig", id) else nav.popBackStack(Routes.Home, inclusive = false) },
                    onCreateShield = { nav.navigate(Routes.ShieldEdit) },
                    onCreateIntent = { nav.navigate(Routes.IntentEdit) },
                    onStrict = { nav.navigate(Routes.Strict) },
                    onPlus = plus,
                    onOpenWeb = { nav.navigate(ProtectRoutes.Web) },
                    startSaved = e.arguments?.getString("saved") == "true",
                )
            }
            composable(Routes.Track) { TabSlot(Routes.Track, slots) }
            composable(Routes.GoalEdit) { e ->
                val createdConfig by e.savedStateHandle.getStateFlow<String?>("createdConfig", null).collectAsStateWithLifecycle()
                GoalEditorScreen(createdConfig, back, onSaved = back, onCreateConfig = { nav.navigate("${Routes.Configure}?custom=true&goal=true") }, onPlus = plus)
            }
            composable(Routes.Strict) { StrictModeScreen(back) }
            composable("${Routes.FocusRun}/{id}") { e -> FocusRunScreen(Uri.decode(e.arguments?.getString("id").orEmpty()), back) }
            composable(ProtectRoutes.Apps) { AppPickerScreen(back, plus) }
            composable("${ProtectRoutes.Item}/{key}") { entry ->
                ItemScreen(Uri.decode(entry.arguments?.getString("key").orEmpty()), back, onStart = { nav.selectTab(Routes.Home) }, onIntent = { nav.selectTab(Routes.Intent) }, onPlus = plus)
            }
            composable(ProtectRoutes.Web) { WebScreen(back, openItem, plus) }
            composable(ProtectRoutes.Plus) { PlusScreen(back) }
            composable(Routes.Account) { AccountScreen(back, plus) }
            composable(Routes.History) { SessionHistoryScreen(back) }
            composable(Routes.HabitHistory) { HabitHistoryScreen(back, plus) }
            composable("${Routes.AppUsage}/{period}") { e -> AppUsageScreen(Period.valueOf(e.arguments?.getString("period") ?: "Today"), back) }
            composable(Routes.Guide) { GuideScreen(back) }
            composable(Routes.Settings) {
                SettingsScreen(
                    onBack = back,
                    onAccount = { nav.navigate(Routes.Account) },
                    onReplayIntro = {
                        scope.launch { app.preferences.setOnboarded(false) }
                        nav.navigate(Routes.Onboarding) { popUpTo(Routes.Home) { inclusive = true } }
                    },
                    onPlus = plus,
                    onOpenWeb = { nav.navigate(ProtectRoutes.Web) },
                    onOpenStrict = { nav.navigate(Routes.Strict) },
                    onOpenGuide = guide,
                )
            }
            composable(
                Routes.Focus,
                enterTransition = { focusEnter(reduced) },
                popExitTransition = { focusExit(reduced) },
            ) {
                // Closing waits for the session write, so a second tap can arrive after Focus has already gone.
                FocusScreen(onClose = { if (nav.currentDestination?.route == Routes.Focus) nav.popBackStack() }, onPlus = plus)
            }
        }
        }

        val prompt by app.limitPrompt.collectAsStateWithLifecycle()
        prompt?.let { LimitPromptDialog(it, { app.limitPrompt.value = null }) { nav.navigate(ProtectRoutes.Plus) } }

        val confirming by app.account.authorizing.collectAsStateWithLifecycle()
        if (confirming) ConfirmingDialog()

        val failed by app.saveFailed.collectAsStateWithLifecycle()
        if (failed) AuroraDialog(
            stringResource(R.string.error_save_title), { app.saveFailed.value = false },
            stringResource(R.string.action_ok), { app.saveFailed.value = false },
            stringResource(R.string.error_save_body),
        )

        AnimatedVisibility(
            visible = route in TabRoutes,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = fadeIn(tween(Motion.Standard)) + if (reduced) EnterTransition.None else slideInVertically(tween(Motion.Gentle, easing = Motion.Ease)) { it },
            exit = fadeOut(tween(Motion.Quick)) + if (reduced) ExitTransition.None else slideOutVertically(tween(Motion.Standard, easing = Motion.Ease)) { it },
        ) {
            AuroraNavBar(Tabs, route, backdrop) { nav.selectTab(it) }
        }
    }
}

private const val SunkScale = 0.92f

/** Focus rises gently into view, the one moment aurora allows itself a little ceremony. */
private fun focusEnter(reduced: Boolean): EnterTransition =
    if (reduced) fadeIn(tween(Motion.Standard))
    else fadeIn(tween(Motion.Gentle)) + scaleIn(tween(Motion.Reveal, easing = Motion.Ease), initialScale = 0.86f)

private fun focusExit(reduced: Boolean): ExitTransition =
    if (reduced) fadeOut(tween(Motion.Quick))
    else fadeOut(tween(Motion.Standard)) + scaleOut(tween(Motion.Standard, easing = Motion.Ease), targetScale = 0.92f)

/**
 * A tab's place in the navigation graph. Its page lives in [TabPages], built once and kept, so choosing a tab never
 * composes its page again (that one frame was the stutter on every tab change). While the slot is composed, its tab
 * is in view: chosen, or beneath a screen sliding over it.
 */
@Composable
private fun TabSlot(route: String, slots: MutableSet<String>) {
    DisposableEffect(route) {
        slots += route
        onDispose { slots -= route }
    }
}

/**
 * Every tab visited so far, kept composed: scroll, state and layout caches survive. Only the tab in view is measured,
 * placed and drawn; the others are skipped by layout, hidden from accessibility, and run a lifecycle held at CREATED,
 * so their flows pause, their back handlers are off, and resume effects run again when they return.
 */
@Composable
private fun TabPages(route: String?, slots: Set<String>, page: @Composable (String) -> Unit) {
    val shown: Set<String> = if (route in TabRoutes) setOf(route!!) else slots.toSet()
    // Plain set: it only grows when [shown] changes, which recomposes this anyway.
    val visited = remember { LinkedHashSet<String>() }
    visited += shown
    val order = visited.toList()
    val parent = androidx.lifecycle.compose.LocalLifecycleOwner.current
    androidx.compose.ui.layout.Layout(
        content = {
            order.forEach { tab ->
                androidx.compose.runtime.key(tab) {
                    val owner = remember { TabLifecycle() }
                    owner.cap = when {
                        tab == route -> Lifecycle.State.RESUMED
                        tab in shown -> Lifecycle.State.STARTED
                        else -> Lifecycle.State.CREATED
                    }
                    androidx.compose.runtime.SideEffect { owner.follow(parent.lifecycle.currentState) }
                    DisposableEffect(parent) {
                        val observer = LifecycleEventObserver { _, _ -> owner.follow(parent.lifecycle.currentState) }
                        parent.lifecycle.addObserver(observer)
                        onDispose {
                            parent.lifecycle.removeObserver(observer)
                            if (owner.lifecycle.currentState.isAtLeast(Lifecycle.State.CREATED)) owner.registry.currentState = Lifecycle.State.DESTROYED
                        }
                    }
                    Box(if (tab in shown) Modifier else Modifier.clearAndSetSemantics { }) {
                        CompositionLocalProvider(androidx.lifecycle.compose.LocalLifecycleOwner provides owner) { page(tab) }
                    }
                }
            }
        },
        modifier = Modifier.fillMaxSize(),
    ) { measurables, constraints ->
        val placeables = measurables.mapIndexedNotNull { i, m -> if (order.getOrNull(i) in shown) m.measure(constraints) else null }
        layout(constraints.maxWidth, constraints.maxHeight) { placeables.forEach { it.place(0, 0) } }
    }
}

/** A kept tab's lifecycle: the activity's, never above [cap]. */
private class TabLifecycle : LifecycleOwner {
    val registry = LifecycleRegistry(this)
    override val lifecycle: Lifecycle get() = registry
    var cap = Lifecycle.State.CREATED

    fun follow(activity: Lifecycle.State) {
        val target = minOf(activity, cap)
        // Below CREATED the activity is going away, and this composition with it.
        if (target.isAtLeast(Lifecycle.State.CREATED)) registry.currentState = target
    }
}
