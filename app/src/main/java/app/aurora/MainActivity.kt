package app.aurora

import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Process
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.aurora.ui.AuroraNavHost
import app.aurora.ui.components.AuroraUnveil
import app.aurora.ui.components.Opening
import app.aurora.ui.components.opensWithFirstLight
import app.aurora.ui.theme.Aurora
import app.aurora.ui.theme.AuroraTheme
import app.aurora.ui.theme.systemAnimationsOff
import kotlinx.coroutines.flow.first

/** When the person last left, in this process. A new activity in a process Shield kept alive is still a return. */
private var lastSeenAt = 0L

class MainActivity : ComponentActivity() {
    private var ready = false

    override fun onResume() {
        super.onResume()
        SessionNotification.nudge()
    }

    override fun onStop() {
        super.onStop()
        lastSeenAt = SystemClock.elapsedRealtime()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // The splash started with the process, or with this activity if Shield had the process running already.
        val created = SystemClock.elapsedRealtime()
        val forked = Process.getStartElapsedRealtime()
        val started = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && created - forked < ColdStartMs) forked else created
        // Nothing to draw until the preferences say which screen it is: the splash keeps running over that moment.
        window.decorView.viewTreeObserver.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                val go = ready || SystemClock.elapsedRealtime() - created > ReadyWaitMs
                if (go) window.decorView.viewTreeObserver.removeOnPreDrawListener(this)
                return go
            }
        })
        val away = if (lastSeenAt == 0L) null else SystemClock.elapsedRealtime() - lastSeenAt
        val exploring = getSystemService(AccessibilityManager::class.java)?.isTouchExplorationEnabled == true
        if (opensWithFirstLight(savedInstanceState != null, away, exploring)) Opening.play(started) else Opening.finish()
        val aurora = application as AuroraApplication
        val preferences = aurora.preferences

        setContent {
            val prefs by preferences.preferences.collectAsStateWithLifecycle(initialValue = null)
            val current = prefs
            DisposableEffect(Unit) {
                val style = SystemBarStyle.dark(Color.TRANSPARENT)
                enableEdgeToEdge(style, style)
                onDispose { }
            }
            val still = current?.reduceMotion == true || systemAnimationsOff(this)
            LaunchedEffect(still) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) splashScreen.setSplashScreenTheme(if (still) R.style.Theme_Aurora_StillSplash else 0)
            }
            AuroraTheme(reduceMotion = current?.reduceMotion == true) {
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
                        translationY = Opening.lift * PageRiseDp.dp.toPx()
                    }) { AuroraNavHost(startOnboarded) }
                    if (Opening.playing) AuroraUnveil()
                }
            }
        }
    }
}

private const val ColdStartMs = 5000L
private const val ReadyWaitMs = 1000L
private const val PageRiseDp = 18
