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
import app.aurora.AuroraApplication
import app.aurora.adoptOnboarding
import app.aurora.R
import app.aurora.ui.components.AuroraIcons
import app.aurora.ui.components.AuroraNavBar
import app.aurora.ui.components.NavItem
import app.aurora.ui.focus.FocusScreen
import app.aurora.ui.home.HomeScreen
import app.aurora.ui.insights.InsightsScreen
import app.aurora.ui.onboarding.OnboardingScreen
import android.net.Uri
import app.aurora.ui.plus.PlusScreen
import app.aurora.ui.protect.AppPickerScreen
import app.aurora.ui.protect.ItemScreen
import app.aurora.ui.protect.LimitsScreen
import app.aurora.ui.protect.ProtectRoutes
import app.aurora.ui.configure.ConfigureScreen
import app.aurora.ui.library.IntentEditorScreen
import app.aurora.ui.library.IntentLibraryScreen
import app.aurora.ui.library.ShieldEditorScreen
import app.aurora.ui.library.ShieldLibraryScreen
import app.aurora.ui.strict.FocusRunScreen
import app.aurora.ui.strict.StrictModeScreen
import app.aurora.ui.track.HabitEditorScreen
import app.aurora.ui.track.HabitScreen
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
    const val HabitEdit = "habit-edit"
    const val Habit = "habit"
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
private fun NavHostController.selectTab(route: String) = navigate(route) {
    popUpTo(Routes.Home) { saveState = true }
    launchSingleTop = true
    restoreState = true
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

    Box(Modifier.fillMaxSize().background(Aurora.colors.background)) {
        NavHost(
            navController = nav,
            startDestination = if (startOnboarded) Routes.Home else Routes.Onboarding,
            modifier = Modifier.fillMaxSize(),
            enterTransition = {
                when {
                    reduced || isTabSwitch() -> fadeIn(tween(Motion.Standard))
                    else -> fadeIn(tween(Motion.Standard)) +
                        slideInHorizontally(tween(Motion.Gentle, easing = Motion.Ease)) { it / 12 }
                }
            },
            exitTransition = { fadeOut(tween(Motion.Quick)) },
            popEnterTransition = {
                when {
                    reduced || isTabSwitch() -> fadeIn(tween(Motion.Standard))
                    else -> fadeIn(tween(Motion.Standard)) +
                        slideInHorizontally(tween(Motion.Gentle, easing = Motion.Ease)) { -it / 12 }
                }
            },
            popExitTransition = {
                when {
                    reduced || isTabSwitch() -> fadeOut(tween(Motion.Quick))
                    else -> fadeOut(tween(Motion.Quick)) +
                        slideOutHorizontally(tween(Motion.Gentle, easing = Motion.Ease)) { it / 12 }
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
                    onNormalUse = { nav.navigate("${Routes.Configure}?custom=false") },
                    onCustom = { nav.navigate("${Routes.Configure}?custom=true") },
                    onOpenFocus = { id -> nav.navigate("${Routes.FocusRun}/${Uri.encode(id)}") },
                    onOpenLegacyFocus = { nav.navigate(Routes.Focus) },
                    onOpenTrack = { nav.selectTab(Routes.Track) },
                    onOpenSettings = settings,
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
                ShieldEditorScreen(e.arguments?.getString("id"), back, onSaved = { id -> nav.returnWith("createdShield", id) })
            }
            composable(Routes.Intent) {
                IntentLibraryScreen(onCreate = { nav.navigate(Routes.IntentEdit) }, onEdit = { id -> nav.navigate("${Routes.IntentEdit}?id=$id") }, onOpenSettings = settings)
            }
            composable("${Routes.IntentEdit}?id={id}") { e ->
                IntentEditorScreen(e.arguments?.getString("id"), back, onSaved = { id -> nav.returnWith("createdIntent", id) })
            }
            composable("${Routes.Configure}?custom={custom}&config={config}&habit={habit}") { e ->
                val handle = e.savedStateHandle
                val createdShield by handle.getStateFlow<String?>("createdShield", null).collectAsStateWithLifecycle()
                val createdIntent by handle.getStateFlow<String?>("createdIntent", null).collectAsStateWithLifecycle()
                val forHabit = e.arguments?.getString("habit") == "true"
                ConfigureScreen(
                    custom = e.arguments?.getString("custom") != "false",
                    configId = e.arguments?.getString("config"),
                    forHabit = forHabit,
                    createdShield = createdShield,
                    createdIntent = createdIntent,
                    onBack = back,
                    onDone = { id -> if (forHabit) nav.returnWith("createdConfig", id) else nav.popBackStack(Routes.Home, inclusive = false) },
                    onCreateShield = { nav.navigate(Routes.ShieldEdit) },
                    onCreateIntent = { nav.navigate(Routes.IntentEdit) },
                    onStrict = { nav.navigate(Routes.Strict) },
                    onPlus = plus,
                )
            }
            composable(Routes.Track) {
                InsightsScreen(
                    onPlus = plus, onOpenSettings = settings,
                    onCreateHabit = { nav.navigate(Routes.HabitEdit) },
                    onOpenHabit = { id -> nav.navigate("${Routes.Habit}/$id") },
                )
            }
            composable("${Routes.HabitEdit}?id={id}") { e ->
                val createdConfig by e.savedStateHandle.getStateFlow<String?>("createdConfig", null).collectAsStateWithLifecycle()
                HabitEditorScreen(
                    e.arguments?.getString("id"), createdConfig, back,
                    onSaved = { id -> nav.popBackStack(); if (e.arguments?.getString("id") == null) nav.navigate("${Routes.Habit}/$id") },
                    onCreateConfig = { nav.navigate("${Routes.Configure}?custom=true&habit=true") },
                )
            }
            composable("${Routes.Habit}/{id}") { e ->
                val id = e.arguments?.getString("id").orEmpty()
                HabitScreen(id, back, onEdit = { nav.navigate("${Routes.HabitEdit}?id=$id") })
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
                    onOpenProtect = { nav.selectTab(Routes.Shield) },
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

        AnimatedVisibility(
            visible = route in TabRoutes,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = fadeIn(tween(Motion.Standard)) + if (reduced) EnterTransition.None else slideInVertically { it / 2 },
            exit = fadeOut(tween(Motion.Quick)) + if (reduced) ExitTransition.None else slideOutVertically { it / 2 },
        ) {
            AuroraNavBar(Tabs, route) { nav.selectTab(it) }
        }
    }
}

/** Focus rises gently into view, the one moment aurora allows itself a little ceremony. */
private fun focusEnter(reduced: Boolean): EnterTransition =
    if (reduced) fadeIn(tween(Motion.Standard))
    else fadeIn(tween(Motion.Gentle)) + scaleIn(tween(Motion.Gentle, easing = Motion.Ease), initialScale = 0.96f)

private fun focusExit(reduced: Boolean): ExitTransition =
    if (reduced) fadeOut(tween(Motion.Quick))
    else fadeOut(tween(Motion.Standard)) + scaleOut(tween(Motion.Standard, easing = Motion.Ease), targetScale = 0.98f)
