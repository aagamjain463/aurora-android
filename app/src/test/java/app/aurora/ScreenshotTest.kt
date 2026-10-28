package app.aurora

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Density
import app.aurora.ui.focus.FocusScreen
import app.aurora.ui.home.HomeScreen
import app.aurora.ui.insights.InsightsScreen
import app.aurora.ui.onboarding.OnboardingScreen
import app.aurora.ui.protect.ProtectScreen
import app.aurora.ui.settings.SettingsScreen
import app.aurora.ui.theme.Aurora
import app.aurora.ui.theme.AuroraTheme
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.LocalTime
import java.time.ZoneOffset
import java.util.TimeZone

/**
 * Every Phase 1 screen in light, dark, 200% font scale and reduced motion, rendered on the JVM.
 *
 * - `./gradlew recordRoborazziDebug` writes the baselines in `src/test/screenshots/`.
 * - `./gradlew verifyRoborazziDebug` fails on any difference from them.
 * - A plain `testDebugUnitTest` only checks that every screen renders.
 *
 * Baselines are recorded on macOS. Text rasterises differently on Linux, so CI doesn't verify them.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h914dp-xxhdpi")
class ScreenshotTest(private val variant: Variant) {

    enum class Variant(val dark: Boolean, val fontScale: Float, val reduceMotion: Boolean) {
        Light(false, 1f, false),
        Dark(true, 1f, false),
        LargeText(false, 2f, false),
        ReducedMotion(false, 1f, true),
    }

    @get:Rule
    val compose = createComposeRule()

    private var zone: TimeZone? = null

    /** Today greets by the hour, so pin local time near 10:30 for a stable "Good morning." */
    @Before
    fun pinMorning() {
        zone = TimeZone.getDefault()
        val sinceMidnightUtc = LocalTime.now(ZoneOffset.UTC).toSecondOfDay()
        val offset = Math.floorMod(10 * 3600 + 1800 - sinceMidnightUtc + 43_200, 86_400) - 43_200
        TimeZone.setDefault(TimeZone.getTimeZone(ZoneOffset.ofTotalSeconds(offset)))
    }

    @After
    fun restoreZone() {
        TimeZone.setDefault(zone)
    }

    private fun capture(screen: String, content: @Composable () -> Unit) {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, variant.fontScale)) {
                AuroraTheme(darkTheme = variant.dark, reduceMotion = variant.reduceMotion) {
                    Box(Modifier.fillMaxSize().background(Aurora.colors.background)) { content() }
                }
            }
        }
        compose.onRoot().captureRoboImage("src/test/screenshots/${screen}_${variant.name}.png")
    }

    @Test fun onboarding() = capture("onboarding") { OnboardingScreen(onFinish = {}) }

    @Test fun today() = capture("today") { HomeScreen(onStartFocus = {}, onOpenProtect = {}, onOpenInsights = {}, onOpenSettings = {}) }

    @Test fun protect() = capture("protect") { ProtectScreen() }

    @Test fun insights() = capture("insights") { InsightsScreen() }

    @Test fun settings() = capture("settings") { SettingsScreen(onBack = {}, onReplayIntro = {}) }

    @Test fun focus() = capture("focus") { FocusScreen(onClose = {}) }

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun variants() = Variant.entries.map { arrayOf<Any>(it) }
    }
}
