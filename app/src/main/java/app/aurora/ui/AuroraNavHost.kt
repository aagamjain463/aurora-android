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
import androidx.compose.runtime.getValue
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
import app.aurora.ui.components.rememberGlassSource
import app.aurora.ui.components.glassSource
import app.aurora.AuroraApplication
import app.aurora.adoptOnboarding
import app.aurora.R
import app.aurora.ui.components.AuroraIcons
import app.aurora.ui.components.AuroraNavBar
import app.aurora.ui.components.NavItem
import app.aurora.ui.components.TabArrival
import app.aurora.ui.focus.FocusScreen
import app.aurora.ui.home.HomeScreen
import app.aurora.ui.onboarding.OnboardingScreen
import android.net.Uri
import app.aurora.ui.plus.PlusScreen
import app.aurora.ui.protect.AppPickerScreen
import app.aurora.ui.protect.ItemScreen
import app.aurora.ui.protect.LimitsScreen
import app.aurora.ui.protect.ProtectRoutes
import app.aurora.ui.configure.ConfigureScreen
import app.aurora.ui.configure.QuickSessionScreen
import app.aurora.ui.components.AuroraDialog
import androidx.compose.ui.res.stringResource
import app.aurora.ui.library.IntentEditorScreen
import app.aurora.ui.library.IntentLibraryScreen
import app.aurora.ui.library.ShieldEditorScreen
import app.aurora.ui.library.ShieldLibraryScreen
import app.aurora.ui.strict.FocusRunScreen
import app.aurora.ui.strict.StrictModeScreen
import app.aurora.ui.track.GoalEditorScreen
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
    const val Quick = "quick"
    const val ShieldEdit = "shield-edit"
    const val IntentEdit = "intent-edit"
    const val GoalEdit = "goal-edit"
    const val Strict = "strict"
    const val FocusRun = "focus-run"
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
    TabArrival.fresh = currentDestination?.route != route
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

    val glass = rememberGlassSource()
    val push = tween<Float>(Motion.Gentle, easing = Motion.Ease)
    val slide = tween<androidx.compose.ui.unit.IntOffset>(Motion.Gentle, easing = Motion.Ease)
    Box(Modifier.fillMaxSize().background(Aurora.colors.background)) {
        NavHost(
            navController = nav,
            startDestination = if (startOnboarded) Routes.Home else Routes.Onboarding,
            modifier = Modifier.fillMaxSize().glassSource(glass),
            // Tabs share one sky: the new one fades in over the old, so the light never dips, while the
            // new page's blocks climb in over it ([rise]).
            // Screens pushed on top slide in from the right while the one below sinks back, so depth
            // reads at a glance. Reduced motion keeps only the fades.
            enterTransition = {
                when {
                    reduced -> fadeIn(tween(Motion.Standard))
                    isTabSwitch() -> fadeIn(tween(Motion.Standard, easing = Motion.Ease))
                    else -> fadeIn(tween(Motion.Standard)) + slideInHorizontally(slide) { it / 2 }
                }
            },
            exitTransition = {
                when {
                    reduced -> fadeOut(tween(Motion.Quick))
                    isTabSwitch() -> ExitTransition.KeepUntilTransitionsFinished
                    else -> fadeOut(push) + scaleOut(push, targetScale = SunkScale) + slideOutHorizontally(slide) { -it / 8 }
                }
            },
            popEnterTransition = {
                when {
                    reduced -> fadeIn(tween(Motion.Standard))
                    isTabSwitch() -> fadeIn(tween(Motion.Standard, easing = Motion.Ease))
                    else -> fadeIn(push) + scaleIn(push, initialScale = SunkScale) + slideInHorizontally(slide) { -it / 8 }
                }
            },
            popExitTransition = {
                when {
                    reduced -> fadeOut(tween(Motion.Quick))
                    isTabSwitch() -> ExitTransition.KeepUntilTransitionsFinished
                    else -> fadeOut(tween(Motion.Standard)) + slideOutHorizontally(slide) { it / 2 }
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
            val back: () -> Unit = { nav.popBackStack() }
            val plus: () -> Unit = { nav.navigate(ProtectRoutes.Plus) }
            val settings: () -> Unit = { nav.navigate(Routes.Settings) }
            val openItem: (String) -> Unit = { key -> nav.navigate("${ProtectRoutes.Item}/${Uri.encode(key)}") }
            composable(Routes.Home) {
                HomeScreen(
                    onNormalUse = { nav.navigate(Routes.Quick) },
                    onCustom = { nav.navigate("${Routes.Configure}?custom=true") },
                    onOpenFocus = { id -> nav.navigate("${Routes.FocusRun}/${Uri.encode(id)}") },
                    onOpenLegacyFocus = { nav.navigate(Routes.Focus) },
                    onOpenSettings = settings,
                )
            }
            composable(Routes.Quick) {
                QuickSessionScreen(
                    onBack = back,
                    onDone = { nav.popBackStack(Routes.Home, inclusive = false) },
                    onMore = { nav.navigate("${Routes.Configure}?custom=false") },
                    onStrict = { nav.navigate(Routes.Strict) },
                    onPlus = plus,
                    onOpenWeb = { nav.navigate(ProtectRoutes.Web) },
                )
            }
            composable(Routes.Shield) {
                ShieldLibraryScreen(
                    onCreate = { nav.navigate(Routes.ShieldEdit) },
                    onEdit = { id -> nav.navigate("${Routes.ShieldEdit}?id=$id") },
                    onOpenLimits = { nav.navigate(ProtectRoutes.Limits) },
                    onOpenWeb = { nav.navigate(ProtectRoutes.Web) },
                    onOpenSettings = settings,
                )
            }
            composable("${Routes.ShieldEdit}?id={id}") { e ->
                ShieldEditorScreen(e.arguments?.getString("id"), back, onSaved = { id -> nav.returnWith("createdShield", id) }, onPlus = plus)
            }
            composable(Routes.Intent) {
                IntentLibraryScreen(onCreate = { nav.navigate(Routes.IntentEdit) }, onEdit = { id -> nav.navigate("${Routes.IntentEdit}?id=$id") }, onOpenSettings = settings)
            }
            composable("${Routes.IntentEdit}?id={id}") { e ->
                IntentEditorScreen(e.arguments?.getString("id"), back, onSaved = { id -> nav.returnWith("createdIntent", id) }, onPlus = plus)
            }
            composable("${Routes.Configure}?custom={custom}&config={config}&goal={goal}") { e ->
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
                )
            }
            composable(Routes.Track) {
                TrackScreen(onPlus = plus, onOpenSettings = settings, onCreateGoal = { nav.navigate(Routes.GoalEdit) })
            }
            composable(Routes.GoalEdit) { e ->
                val createdConfig by e.savedStateHandle.getStateFlow<String?>("createdConfig", null).collectAsStateWithLifecycle()
                GoalEditorScreen(createdConfig, back, onSaved = back, onCreateConfig = { nav.navigate("${Routes.Configure}?custom=true&goal=true") })
            }
            composable(Routes.Strict) { StrictModeScreen(back) }
            composable("${Routes.FocusRun}/{id}") { e -> FocusRunScreen(Uri.decode(e.arguments?.getString("id").orEmpty()), back) }
            composable(ProtectRoutes.Apps) { AppPickerScreen(back, plus) }
            composable("${ProtectRoutes.Item}/{key}") { entry ->
                ItemScreen(Uri.decode(entry.arguments?.getString("key").orEmpty()), back, onStart = { nav.selectTab(Routes.Home) }, onIntent = { nav.selectTab(Routes.Intent) }, onPlus = plus)
            }
            composable(ProtectRoutes.Limits) { LimitsScreen(back, openItem) { nav.navigate(ProtectRoutes.Apps) } }
            composable(ProtectRoutes.Web) { WebScreen(back, openItem, plus) }
            composable(ProtectRoutes.Plus) { PlusScreen(back) }
            composable(Routes.Settings) {
                SettingsScreen(
                    onBack = back,
                    onReplayIntro = {
                        scope.launch { app.preferences.setOnboarded(false) }
                        nav.navigate(Routes.Onboarding) { popUpTo(Routes.Home) { inclusive = true } }
                    },
                    onPlus = plus,
                    onOpenWeb = { nav.navigate(ProtectRoutes.Web) },
                    onOpenStrict = { nav.navigate(Routes.Strict) },
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
            AuroraNavBar(Tabs, route, glass) { nav.selectTab(it) }
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
