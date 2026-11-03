package app.aurora

import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.size
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.core.app.ApplicationProvider
import app.aurora.protect.AppRule
import app.aurora.protect.Decision
import app.aurora.protect.FrictionSettings
import app.aurora.protect.IntentConfig
import app.aurora.protect.Restriction
import app.aurora.protect.ShieldContent
import app.aurora.protect.ShieldReason
import app.aurora.protect.ShieldRequest
import app.aurora.ui.home.HomeScreen
import app.aurora.ui.onboarding.OnboardingScreen
import app.aurora.ui.theme.Aurora
import app.aurora.ui.theme.AuroraTheme
import com.github.takahirom.roborazzi.captureRoboImage
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The hero screens on a small phone (360dp) and a tablet (800dp), dark theme. ScreenshotTest covers the
 * 411dp phone in every variant; this catches overflow and crops at the edges of the range.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FormFactorScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    @Before
    fun setUp() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<AuroraApplication>()
        shadowOf(app.packageManager).installPackage(PackageInfo().apply {
            packageName = "com.example.photos"
            applicationInfo = ApplicationInfo().apply { packageName = "com.example.photos"; nonLocalizedLabel = "Photos" }
        })
        app.history.reset()
        app.runtime.reset()
        app.rules.reset()
        app.rules.update { it.copy(apps = mapOf("com.example.photos" to AppRule(intent = IntentConfig(reason = "I want more time for studying.", alternative = "Read a chapter.", reflectionSeconds = 0)))) }
        Unit
    }

    private fun capture(name: String, content: @Composable () -> Unit) {
        compose.setContent {
            AuroraTheme(reduceMotion = true) {
                Box(Modifier.fillMaxSize().background(Aurora.colors.background)) { content() }
            }
        }
        compose.onRoot().captureRoboImage("src/test/screenshots/formfactor/$name.png")
    }

    @Composable
    private fun Home() = HomeScreen(onStart = {}, onOpenFocus = {}, onOpenLegacyFocus = {}, onOpenSettings = {})

    private val intent = ShieldRequest(
        "com.example.photos", ShieldReason.Intent, null, 0, 0, 0, fresh = false,
        decision = Decision.Shield("com.example.photos", ShieldReason.Intent, null, Restriction(ShieldReason.Intent, setOf("com.example.photos"), 0, Long.MAX_VALUE, "r", FrictionSettings(), 0L)),
    )

    /** The home-screen widget as the launcher inflates it, at a 4x1 cell size. */
    @Test @Config(qualifiers = "w411dp-h914dp-xxhdpi")
    fun widget() {
        val app = ApplicationProvider.getApplicationContext<AuroraApplication>()
        capture("widget_idle") {
            androidx.compose.foundation.layout.Box(Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.Center) {
                androidx.compose.ui.viewinterop.AndroidView(
                    { ctx -> AuroraWidget.views(app).apply(ctx, android.widget.FrameLayout(ctx)) },
                    Modifier.size(androidx.compose.ui.unit.DpSize(360.dp, 96.dp)),
                )
            }
        }
    }

    @Test @Config(qualifiers = "w360dp-h740dp-xhdpi")
    fun homeSmall() = capture("home_360") { Home() }

    @Test @Config(qualifiers = "w360dp-h740dp-xhdpi")
    fun onboardingSmall() = capture("onboarding_360") { OnboardingScreen(onFinish = {}) }

    @Test @Config(qualifiers = "w360dp-h740dp-xhdpi")
    fun shieldSmall() = capture("shield_intent_360") { ShieldContent(intent) { } }

    @Test @Config(qualifiers = "w800dp-h1280dp-xhdpi")
    fun homeTablet() = capture("home_tablet") { Home() }

    @Test @Config(qualifiers = "w800dp-h1280dp-xhdpi")
    fun shieldTablet() = capture("shield_intent_tablet") { ShieldContent(intent) { } }
}
