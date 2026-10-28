package app.aurora

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.aurora.data.ThemeMode
import app.aurora.ui.AuroraNavHost
import app.aurora.ui.theme.Aurora
import app.aurora.ui.theme.AuroraTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val preferences = (application as AuroraApplication).preferences

        setContent {
            val prefs by preferences.preferences.collectAsStateWithLifecycle(initialValue = null)
            val current = prefs
            val dark = when (current?.themeMode) {
                ThemeMode.Light -> false
                ThemeMode.Dark -> true
                else -> isSystemInDarkTheme()
            }
            // Keep system bar icons legible when the in-app theme differs from the system's.
            DisposableEffect(dark) {
                val style = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { dark }
                enableEdgeToEdge(style, style)
                onDispose { }
            }
            AuroraTheme(darkTheme = dark, reduceMotion = current?.reduceMotion == true) {
                if (current == null) {
                    // DataStore answers within a frame or two; hold the window colour until it does.
                    app.aurora.ui.components.LoadingSurface()
                } else {
                    val startOnboarded = remember { current.onboarded }
                    AuroraNavHost(startOnboarded)
                }
            }
        }
    }
}
