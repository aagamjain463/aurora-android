package app.aurora

import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.animation.PathInterpolator
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.aurora.ui.AuroraNavHost
import app.aurora.ui.components.AuroraUnveil
import app.aurora.ui.components.Opening
import app.aurora.ui.theme.Aurora
import app.aurora.ui.theme.AuroraTheme
import app.aurora.ui.theme.DarkColors

private const val ReopenAfterMs = 5 * 60 * 1000L

class MainActivity : ComponentActivity() {
    private var stoppedAt = 0L

    override fun onResume() {
        super.onResume()
        SessionNotification.nudge()
    }

    override fun onStart() {
        super.onStart()
        // Back after a real absence is an opening too; hopping out to a permission page is not.
        if (stoppedAt > 0L && SystemClock.elapsedRealtime() - stoppedAt > ReopenAfterMs) Opening.play(fadeIn = true)
    }

    override fun onStop() {
        super.onStop()
        stoppedAt = SystemClock.elapsedRealtime()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // The approved symbol holds a moment on its midnight green, then fades, and only then does the opening start.
            splashScreen.setOnExitAnimationListener { splash ->
                val drawn = splash.iconAnimationStart?.plus(splash.iconAnimationDuration)
                val left = if (drawn == null) 0L else java.time.Duration.between(java.time.Instant.now(), drawn).toMillis().coerceAtLeast(0L)
                splash.animate().alpha(0f).setStartDelay(left + LogoHoldMs).setDuration(240).setInterpolator(PathInterpolator(0.2f, 0f, 0f, 1f)).withEndAction { splash.remove(); Opening.splashLeft() }.start()
            }
        }
        // A recreated activity (the theme settling, a rotation) restarts an opening that was still playing.
        if (savedInstanceState == null || Opening.playing) Opening.play()
        // Recreated, there is no splash coming: the opening must not sit on black waiting for one.
        if (savedInstanceState != null) Opening.splashLeft()
        val preferences = (application as AuroraApplication).preferences

        setContent {
            val prefs by preferences.preferences.collectAsStateWithLifecycle(initialValue = null)
            val current = prefs
            DisposableEffect(Unit) {
                val style = SystemBarStyle.dark(Color.TRANSPARENT)
                enableEdgeToEdge(style, style)
                onDispose { }
            }
            AuroraTheme(reduceMotion = current?.reduceMotion == true) {
                if (current == null) {
                    // DataStore answers within a frame or two; hold the window colour until it does.
                    if (Opening.playing) Box(Modifier.fillMaxSize().background(DarkColors.sky))
                    else app.aurora.ui.components.LoadingSurface()
                } else {
                    val startOnboarded = remember { current.onboarded }
                    Box(Modifier.fillMaxSize().graphicsLayer { alpha = if (Opening.pageHidden) 0f else 1f }) { AuroraNavHost(startOnboarded) }
                    if (Opening.playing) AuroraUnveil(interactive = !startOnboarded)
                }
            }
        }
    }
}

/** How long the symbol holds on the splash before it fades. */
private const val LogoHoldMs = 500L
