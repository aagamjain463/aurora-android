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
import app.aurora.ui.components.AuroraIcons
import app.aurora.ui.components.AuroraNavBar
import app.aurora.ui.components.NavItem
import app.aurora.ui.focus.FocusScreen
import app.aurora.ui.home.HomeScreen
import app.aurora.ui.insights.InsightsScreen
import app.aurora.ui.onboarding.OnboardingScreen
import app.aurora.ui.protect.ProtectScreen
import app.aurora.ui.settings.SettingsScreen
import app.aurora.ui.theme.Aurora
import app.aurora.ui.theme.Motion
import kotlinx.coroutines.launch

object Routes {
    const val Onboarding = "onboarding"
    const val Today = "today"
    const val Protect = "protect"
    const val Insights = "insights"
    const val Settings = "settings"
    const val Focus = "focus"
}

/** Three tabs, by design: what's happening now, what you protect, what you've learned. */
private val Tabs = listOf(
    NavItem(Routes.Today, "Today", AuroraIcons.Today),
    NavItem(Routes.Protect, "Protect", AuroraIcons.Protect),
    NavItem(Routes.Insights, "Insights", AuroraIcons.Insights),
)
private val TabRoutes = Tabs.map { it.route }.toSet()

/** Tabs keep their own scroll and state; Today is always the root of the back stack. */
private fun NavHostController.selectTab(route: String) = navigate(route) {
    popUpTo(Routes.Today) { saveState = true }
    launchSingleTop = true
    restoreState = true
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
            startDestination = if (startOnboarded) Routes.Today else Routes.Onboarding,
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
                    scope.launch { app.preferences.setOnboarded(true) }
                    nav.navigate(Routes.Today) { popUpTo(Routes.Onboarding) { inclusive = true } }
                })
            }
            composable(Routes.Today) {
                HomeScreen(
                    onStartFocus = { nav.navigate(Routes.Focus) },
                    onOpenProtect = { nav.selectTab(Routes.Protect) },
                    onOpenInsights = { nav.selectTab(Routes.Insights) },
                    onOpenSettings = { nav.navigate(Routes.Settings) },
                )
            }
            composable(Routes.Protect) { ProtectScreen() }
            composable(Routes.Insights) { InsightsScreen() }
            composable(Routes.Settings) {
                SettingsScreen(
                    onBack = { nav.popBackStack() },
                    onReplayIntro = {
                        scope.launch { app.preferences.setOnboarded(false) }
                        nav.navigate(Routes.Onboarding) { popUpTo(Routes.Today) { inclusive = true } }
                    },
                )
            }
            composable(
                Routes.Focus,
                enterTransition = { focusEnter(reduced) },
                popExitTransition = { focusExit(reduced) },
            ) {
                // Closing waits for the session write, so a second tap can arrive after Focus has already gone.
                FocusScreen(onClose = { if (nav.currentDestination?.route == Routes.Focus) nav.popBackStack() })
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
