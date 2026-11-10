package app.aurora

import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.ViewTreeObserver
import android.view.accessibility.AccessibilityManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.colorResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.aurora.ui.AuroraNavHost
import app.aurora.ui.components.AuroraUnveil
import app.aurora.ui.components.Opening
import app.aurora.ui.components.opensWithFirstLight
import app.aurora.ui.theme.Aurora
import app.aurora.ui.theme.AuroraTheme
import app.aurora.ui.theme.systemAnimationsOff
import app.aurora.account.AuthState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** When the person last left, in this process. A new activity in a process Shield kept alive is still a return. */
private var lastSeenAt = 0L

class MainActivity : ComponentActivity() {
    private var ready = false

    override fun onResume() {
        super.onResume()
        SessionNotification.nudge()
        val aurora = application as AuroraApplication
        aurora.scope.launch { runCatching { aurora.account.syncIfStale() } }
    }

    // The link in a sign-in email. singleTask: this instance receives it, whatever the person was doing.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleLink(intent)
    }

    private fun handleLink(intent: Intent?) {
        val link = intent?.takeIf { it.action == Intent.ACTION_VIEW }?.dataString ?: return
        val aurora = application as AuroraApplication
        aurora.scope.launch {
            // On a cold start the provider is still reading its session; only then can it read a link.
            aurora.account.state.first { it != AuthState.Loading }
            runCatching { aurora.account.handleLink(link) }
        }
    }

    override fun onStop() {
        super.onStop()
        lastSeenAt = SystemClock.elapsedRealtime()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Phones hold the app upright; tablets and unfolded foldables (600dp and wider) turn freely. Folding or
        // unfolding recreates the activity, so this is decided again for the new screen. The Shield is an overlay
        // window of the accessibility service, not this activity: it always follows the device's own rotation.
        requestedOrientation = if (resources.configuration.smallestScreenWidthDp < 600) ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            else ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        enableEdgeToEdge()
        // The page's first frame draws the same mark at the same place, so the splash goes at once. The system's own
        // exit fades the mark out and the page fades in after it: the logo would seem to load twice.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) splashScreen.setOnExitAnimationListener { it.remove() }
        val created = SystemClock.elapsedRealtime()
        // Nothing to draw until the preferences say which screen it is: the empty splash stays over that moment.
        window.decorView.viewTreeObserver.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                val go = ready || SystemClock.elapsedRealtime() - created > ReadyWaitMs
                if (go) window.decorView.viewTreeObserver.removeOnPreDrawListener(this)
                return go
            }
        })
        val away = if (lastSeenAt == 0L) null else SystemClock.elapsedRealtime() - lastSeenAt
        val exploring = getSystemService(AccessibilityManager::class.java)?.isTouchExplorationEnabled == true
        if (opensWithFirstLight(savedInstanceState != null, away, exploring)) Opening.play() else Opening.finish()
        val aurora = application as AuroraApplication
        val preferences = aurora.preferences
        // After a rotation the same link would be used twice, and links work once.
        if (savedInstanceState == null) handleLink(intent)

        setContent {
            val prefs by preferences.preferences.collectAsStateWithLifecycle(initialValue = null)
            val current = prefs
            val themeMode = current?.themeMode ?: app.aurora.data.ThemeMode.Dark
            val systemDark = androidx.compose.foundation.isSystemInDarkTheme()
            val darkBars = themeMode == app.aurora.data.ThemeMode.Dark || (themeMode == app.aurora.data.ThemeMode.System && systemDark)
            DisposableEffect(darkBars) {
                val style = if (darkBars) SystemBarStyle.dark(Color.TRANSPARENT) else SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
                enableEdgeToEdge(style, style)
                onDispose { }
            }
            val still = current?.reduceMotion == true || systemAnimationsOff(this)
            LaunchedEffect(still) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) splashScreen.setSplashScreenTheme(if (still) R.style.Theme_Aurora_StillSplash else 0)
            }
            AuroraTheme(reduceMotion = current?.reduceMotion == true, themeMode = themeMode) {
                if (current == null) {
                    // DataStore answers within a frame or two; hold the window colour until it does.
                    if (Opening.playing) Box(Modifier.fillMaxSize().background(colorResource(R.color.aurora_splash)))
                    else app.aurora.ui.components.LoadingSurface()
                } else {
                    val startOnboarded = remember { current.onboarded }
                    SideEffect { ready = true }
                    // Coming back to a running session isn't an arrival. Once the rules are read, cut it short.
                    LaunchedEffect(Unit) {
                        aurora.rules.state.first { it != null }
                        aurora.runtime.state.first { it != null }
                        if (Opening.playing && SessionNotification.running(aurora)) Opening.brief = true
                    }
                    Box(Modifier.fillMaxSize().graphicsLayer {
                        alpha = if (Opening.pageHidden) 0f else 1f
                        // Flown into through the A: the page arrives a touch large and settles to its true size.
                        val settle = 1f + Opening.lift * PageZoom
                        scaleX = settle
                        scaleY = settle
                    }) { app.aurora.ui.account.AccountGate(startOnboarded) { onboarded -> AuroraNavHost(onboarded) } }
                    if (Opening.playing) AuroraUnveil()
                }
            }
        }
    }
}

private const val ReadyWaitMs = 1000L
private const val PageZoom = 0.035f
