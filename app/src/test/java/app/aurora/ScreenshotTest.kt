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
import androidx.compose.ui.test.onAllNodesWithText
import androidx.test.core.app.ApplicationProvider
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import org.robolectric.Shadows.shadowOf
import androidx.compose.ui.unit.Density
import app.aurora.protect.Decision
import app.aurora.protect.FrictionSettings
import app.aurora.protect.FrictionTask
import app.aurora.protect.AppRule
import app.aurora.protect.Mode
import app.aurora.plan.ProtectedItem
import app.aurora.protect.IntentConfig
import app.aurora.protect.Restriction
import app.aurora.protect.ShieldContent
import app.aurora.protect.ShieldReason
import app.aurora.protect.ShieldRequest
import app.aurora.ui.focus.FocusScreen
import app.aurora.startFocus
import app.aurora.protect
import app.aurora.unprotect
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
import kotlinx.coroutines.runBlocking

/**
 * Every screen, and the Shield, in light, dark, 200% font scale and reduced motion, rendered on the JVM.
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

    /** Clock times on screen are pinned near 10:30 local, so baselines stay stable. */
    @Before
    fun pinMorning() = runBlocking {
        zone = TimeZone.getDefault()
        val sinceMidnightUtc = LocalTime.now(ZoneOffset.UTC).toSecondOfDay()
        val offset = Math.floorMod(10 * 3600 + 1800 - sinceMidnightUtc + 43_200, 86_400) - 43_200
        TimeZone.setDefault(TimeZone.getTimeZone(ZoneOffset.ofTotalSeconds(offset)))
        val app = ApplicationProvider.getApplicationContext<AuroraApplication>()
        shadowOf(app.packageManager).installPackage(PackageInfo().apply {
            packageName = "com.example.photos"
            applicationInfo = ApplicationInfo().apply {
                packageName = "com.example.photos"
                nonLocalizedLabel = "Photos"
            }
        })
        app.history.reset()
        app.runtime.reset()
        app.rules.reset()
        app.preferences.setReduceMotion(variant.reduceMotion)
        app.rules.update { it.copy(apps = mapOf("com.example.photos" to AppRule(intent = IntentConfig(reason = "I want more time for studying and building my future.", alternative = "Continue working on my project.", reflectionSeconds = 0)))) }
        Unit
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
        if (screen == "insights") compose.waitUntil(5_000) {
            compose.onAllNodesWithText("Screen time").fetchSemanticsNodes().isNotEmpty()
        }
        if (screen.startsWith("shield_")) compose.waitUntil(5_000) {
            compose.onAllNodesWithText("Photos").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onRoot().captureRoboImage("src/test/screenshots/${screen}_${variant.name}.png")
    }

    @Test fun onboarding() = capture("onboarding") { OnboardingScreen(onFinish = {}) }

    @Test fun today() = capture("today") { HomeScreen(onStartFocus = {}, onOpenProtect = {}, onStartProtection = {}, onOpenInsights = {}, onOpenSettings = {}) }

    @Test fun protect() = capture("protect") { ProtectScreen(onOpen = {}, onOpenItem = {}) }

    @Test fun insights() = capture("insights") { InsightsScreen() }

    @Test fun settings() = capture("settings") { SettingsScreen(onBack = {}, onReplayIntro = {}) }

    @Test fun focus() = capture("focus") { FocusScreen(onClose = {}) }

    private val application get() = ApplicationProvider.getApplicationContext<AuroraApplication>()

    /** A session 30 minutes into two hours. Runtime is reset before every test. */
    @Test fun focusRunning() {
        runBlocking {
            application.startFocus(120, null, null, "Study mathematics")
            application.runtime.update { r -> r.copy(focus = r.focus?.let { it.copy(startElapsed = it.startElapsed - 30 * 60_000L, startWall = it.startWall - 30 * 60_000L) }) }
        }
        capture("focus_running") { FocusScreen(onClose = {}) }
    }

    /** Protect with one saved app on Friction, so the app row and its mode chip are in the baseline. */
    @Test fun protectSaved() {
        val photos = ProtectedItem.App.of("com.example.photos")!!
        runBlocking {
            application.protect(photos)
            application.rules.update { r -> r.copy(apps = r.apps.mapValues { (_, rule) -> rule.copy(mode = Mode.Friction) }) }
        }
        try {
            capture("protect_saved") { ProtectScreen(onOpen = {}, onOpenItem = {}) }
        } finally {
            runBlocking { application.unprotect(listOf(photos)) }
        }
    }

    private fun shield(reason: ShieldReason, until: Long? = System.currentTimeMillis() + 5_400_000) = ShieldRequest(
        "com.example.photos", reason, until, 0, 0, 0,
        fresh = false,
        decision = Decision.Shield(
            "com.example.photos", reason, until,
            Restriction(reason, setOf("com.example.photos"), 0, until ?: Long.MAX_VALUE, "r", FrictionSettings(task = FrictionTask.Intention), 45 * 60_000L),
        ),
    )

    @Test fun shieldAlwaysBlock() = capture("shield_always_block") { ShieldContent(shield(ShieldReason.AlwaysBlock)) { } }

    @Test fun shieldFlexible() = capture("shield_flexible") { ShieldContent(shield(ShieldReason.Flexible)) { } }

    @Test fun shieldFriction() = capture("shield_friction") { ShieldContent(shield(ShieldReason.Friction)) { } }

    @Test fun shieldIntent() = capture("shield_intent") { ShieldContent(shield(ShieldReason.Intent, null)) { } }

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun variants() = Variant.entries.map { arrayOf<Any>(it) }
    }
}
