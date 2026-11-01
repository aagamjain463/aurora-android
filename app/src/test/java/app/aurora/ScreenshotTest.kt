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
import app.aurora.protect.IntentConfig
import app.aurora.protect.Restriction
import app.aurora.protect.ShieldContent
import app.aurora.protect.ShieldReason
import app.aurora.protect.ShieldRequest
import app.aurora.ui.focus.FocusScreen
import app.aurora.startFocus
import app.aurora.ui.home.HomeScreen
import app.aurora.ui.onboarding.OnboardingScreen
import app.aurora.progress.Goal
import app.aurora.progress.GoalKind
import app.aurora.progress.GoalPeriod
import app.aurora.progress.ServiceSpan
import app.aurora.progress.record
import app.aurora.protect.Activation
import app.aurora.protect.ConfigRun
import app.aurora.protect.FocusPlan
import app.aurora.protect.IntentExpiry
import app.aurora.protect.IntentPreset
import app.aurora.protect.ShieldPreset
import app.aurora.protect.Timing
import app.aurora.protect.UsageConfig
import app.aurora.ui.configure.ConfigureScreen
import app.aurora.ui.configure.When
import app.aurora.ui.library.IntentEditorScreen
import app.aurora.ui.library.IntentLibraryScreen
import app.aurora.ui.library.ShieldEditorScreen
import app.aurora.ui.library.ShieldLibraryScreen
import app.aurora.ui.strict.FocusRunScreen
import app.aurora.ui.strict.StrictModeScreen
import app.aurora.ui.track.GoalEditorScreen
import app.aurora.ui.track.TrackScreen
import app.aurora.ui.track.TrackTab
import java.time.LocalDate
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
import app.aurora.progress.RunRecord
import app.aurora.progress.Outcome

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
        Dark(true, 1f, false),
        LargeText(true, 2f, false),
        ReducedMotion(true, 1f, true),
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
                AuroraTheme(reduceMotion = variant.reduceMotion) {
                    Box(Modifier.fillMaxSize().background(Aurora.colors.background)) { content() }
                }
            }
        }
        if (screen == "track" || screen == "insights") compose.waitUntil(5_000) {
            compose.onAllNodesWithText("Succeeded").fetchSemanticsNodes().isNotEmpty()
        }
        if (screen == "track_goals") compose.waitUntil(5_000) {
            compose.onAllNodesWithText("Create a Goal").fetchSemanticsNodes().isNotEmpty()
        }
        if (screen == "track_usage") compose.waitUntil(5_000) {
            compose.onAllNodesWithText("since midnight", substring = true).fetchSemanticsNodes().isNotEmpty() ||
                compose.onAllNodesWithText("See where your time goes").fetchSemanticsNodes().isNotEmpty()
        }
        if (screen.startsWith("shield_")) compose.waitUntil(5_000) {
            compose.onAllNodesWithText("Photos", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onRoot().captureRoboImage("src/test/screenshots/${screen}_${variant.name}.png")
    }

    @Test fun onboarding() = capture("onboarding") { OnboardingScreen(onFinish = {}) }

    @Test fun home() = capture("home") { HomeUnderTest() }

    /** Include the real dock and system insets, including at 200% font scale. */
    @Test fun homeNavigation() = capture("home_navigation") { app.aurora.ui.AuroraNavHost(startOnboarded = true) }

    @Composable
    private fun HomeUnderTest() = HomeScreen(onNormalUse = {}, onCustom = {}, onOpenFocus = {}, onOpenLegacyFocus = {}, onOpenSettings = {})

    @Test fun track() = capture("insights") { TrackScreen() }

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

    // ---- The four-pillar model ----------------------------------------------------------------------

    private val social = ShieldPreset("s1", "Social Media Shield", listOf("com.example.photos", "web:reddit.com"), Mode.Cooldown, cooldownWaitMinutes = 1, createdAt = 2)
    private val strict = ShieldPreset("s2", "Night Shield", listOf("com.example.photos"), Mode.AlwaysBlock, createdAt = 1)
    private val video = IntentPreset("i1", "Intentional YouTube", listOf("com.example.photos"), IntentConfig(reflectionSeconds = 15, expiry = IntentExpiry.Cooldown, cooldownMinutes = 45), createdAt = 1)
    private val study = UsageConfig("c1", "Study Session", shieldId = "s1", intentId = "i1", focus = FocusPlan("Study mathematics", goal = "Finish chapter 4"), createdAt = 1)
    private val night = UsageConfig("c2", "Night", shieldId = "s2", createdAt = 2)

    /** A library, a weekday schedule, goals with two weeks of evidence, and one run going now. */
    private fun library(running: Boolean = false) = runBlocking {
        val now = System.currentTimeMillis()
        val day = 86_400_000L
        application.rules.update {
            it.copy(
                version = 1, shields = listOf(social, strict), intentPresets = listOf(video), configs = listOf(study, night),
                activations = listOf(Activation("a1", "c2", Timing.Recurring(setOf(1, 2, 3, 4, 5), 22 * 60, 8 * 60), createdAt = 0)),
                trackGoals = listOf(
                    Goal("g1", "Daily Study", GoalKind.Session, GoalPeriod.Daily, 180, configId = "c1", createdAt = now - 20 * day),
                    Goal("g2", "Less Instagram", GoalKind.Limit, GoalPeriod.Daily, 45, apps = setOf("com.example.photos"), createdAt = now - 20 * day),
                    Goal("g3", "Study sessions", GoalKind.Session, GoalPeriod.Weekly, sessions = 3, configId = "c1", createdAt = now - 20 * day),
                ),
            )
        }
        val spans = listOf(ServiceSpan(now - 30 * day, now))
        val records = (1L..14L).filter { it % 5 != 0L }.map { back ->
            val start = now - back * day
            record("r$back", "c1", "Study Session", true, "Social Media Shield", Mode.Cooldown, "Intentional YouTube", setOf("com.example.photos"), start, start + 7_200_000, start + 7_200_000, spans, false, null, 0, null, 0)
        }
        application.history.update { it.copy(runs = records, service = spans) }
        if (running) application.runtime.update {
            it.copy(runs = listOf(ConfigRun("now:x", "c1", "Study Session", shield = social, intent = video, focus = study.focus, start = now - 3_600_000, end = now + 18_000_000)))
        }
    }

    @Config(qualifiers = "w411dp-h1900dp-xxhdpi")
    @Test fun homeActive() {
        library(running = true)
        capture("home_active") { HomeUnderTest() }
    }

    /** Saved sessions, nothing running: Home offers them one tap from starting. */
    @Config(qualifiers = "w411dp-h1900dp-xxhdpi")
    @Test fun homeReady() {
        library()
        capture("home_ready") { HomeUnderTest() }
    }

    @Config(qualifiers = "w411dp-h1900dp-xxhdpi")
    @Test fun shieldLibrary() {
        library()
        capture("library_shield") { ShieldLibraryScreen({}, {}, {}, {}, {}) }
    }

    @Test fun shieldBuilder() {
        library()
        capture("builder_shield") { ShieldEditorScreen("s1", {}, {}, startStep = 3) }
    }

    @Config(qualifiers = "w411dp-h1900dp-xxhdpi")
    @Test fun intentLibrary() {
        library()
        capture("intent_library") { IntentLibraryScreen({}, {}, {}) }
    }

    @Test fun intentBuilder() {
        library()
        capture("intent_builder") { IntentEditorScreen("i1", {}, {}, startStep = 2) }
    }

    @Composable
    private fun Configure(custom: Boolean, config: String? = null, step: String? = null, timing: When? = null) =
        ConfigureScreen(custom, config, false, null, null, {}, {}, {}, {}, {}, {}, startStep = step, startWhen = timing)

    @Test fun normalUse() = capture("configure_normal") { Configure(custom = false) }

    @Test fun quickSession() {
        library()
        grantShield()
        capture("quick_session") { app.aurora.ui.configure.QuickSessionScreen({}, {}, {}, {}, {}, {}) }
    }

    @Test fun onboardingShield() {
        runBlocking { application.preferences.setOnboardingStep(2) }
        capture("onboarding_shield") { OnboardingScreen(onFinish = {}) }
    }

    /** Apps chosen and Shield on: the last step starts the first session. */
    @Test fun onboardingReady() {
        grantShield()
        runBlocking {
            application.protection.add(app.aurora.plan.ProtectedItem.App.of("com.example.photos")!!, application.plan)
            application.preferences.setOnboardingStep(3)
        }
        capture("onboarding_ready") { OnboardingScreen(onFinish = {}) }
    }

    @Test fun onboardingAlmost() {
        runBlocking { application.preferences.setOnboardingStep(3) }
        capture("onboarding_almost") { OnboardingScreen(onFinish = {}) }
    }

    /** Review as a person sees it with Shield on, and with Shield off. */
    private fun grantShield() = android.provider.Settings.Secure.putString(application.contentResolver, android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, "app.aurora/.protect.ShieldService")

    @Test fun reviewShieldOff() {
        library()
        android.provider.Settings.Secure.putString(application.contentResolver, android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, "")
        capture("configure_shield_off") { Configure(custom = true, config = "c1", step = "Review", timing = When.Now) }
    }

    @Test fun customConfiguration() {
        library()
        capture("configure_custom") { Configure(custom = true, config = "c1", step = "Contents") }
    }

    @Test fun startNow() {
        library()
        capture("configure_now") { Configure(custom = true, config = "c1", step = "When", timing = When.Now) }
    }

    @Test fun startLater() {
        library()
        capture("configure_later") { Configure(custom = true, config = "c1", step = "When", timing = When.Later) }
    }

    @Test fun schedule() {
        library()
        capture("configure_schedule") { Configure(custom = true, config = "c1", step = "When", timing = When.Recurring) }
    }

    /** Always is shown but not offered with an Always Block Shield. */
    @Test fun alwaysUnavailable() {
        library()
        capture("configure_when_always_block") { Configure(custom = true, config = "c2", step = "When") }
    }

    @Test fun alwaysBlockCommitment() {
        library()
        capture("configure_commit") { Configure(custom = true, config = "c2", step = "Review", timing = When.Now) }
    }

    @Test fun goalTrackStep() {
        library()
        capture("goal_track") { GoalEditorScreen("c1", {}, {}, {}, startStep = "Track", startKind = GoalKind.Session) }
    }

    @Config(qualifiers = "w411dp-h1900dp-xxhdpi")
    @Test fun trackProgress() {
        library()
        capture("track") { TrackScreen() }
    }

    @Config(qualifiers = "w411dp-h1900dp-xxhdpi")
    @Test fun trackGoals() {
        library()
        capture("track_goals") { TrackScreen(start = TrackTab.Goals) }
    }

    @Config(qualifiers = "w411dp-h1900dp-xxhdpi")
    @Test fun trackUsage() {
        library()
        capture("track_usage") { TrackScreen(start = TrackTab.Usage) }
    }

    @Test fun strictMode() = capture("strict") { StrictModeScreen {} }

    @Test fun focusRun() {
        library(running = true)
        capture("focus_run") { FocusRunScreen("now:x") {} }
    }

    @Test fun shieldCooldown() = capture("shield_cooldown") { ShieldContent(shield(ShieldReason.Cooldown)) { } }

    @Test fun shieldIntentCooldown() = capture("shield_intent_cooldown") { ShieldContent(shield(ShieldReason.IntentCooldown)) { } }

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

    @Test fun plus() = capture("plus") { app.aurora.ui.plus.PlusScreen(onBack = {}) }
    @Test fun limit_shield() = capture("limit_shield") { app.aurora.ui.plus.LimitPromptDialog(app.aurora.LimitPrompt(app.aurora.plan.LimitDecision(false, app.aurora.plan.LimitReason.ShieldTargets, 5, 5, 0, app.aurora.plan.Remedy.FreeASlot, true)), {}, {}) }
    @Test fun limit_committed() = capture("limit_committed") { app.aurora.ui.plus.LimitPromptDialog(app.aurora.LimitPrompt(app.aurora.plan.LimitDecision(false, app.aurora.plan.LimitReason.CommittedProtection, 95 * 60_000L, 120 * 60_000L, 25 * 60_000L, app.aurora.plan.Remedy.ChooseShorter, true, resetAt = 0), { }), {}, {}) }
    @Test fun goal_limit() = capture("goal_limit") { app.aurora.ui.track.GoalLimitDialog(app.aurora.progress.Goal("g", "Daily Study", app.aurora.progress.GoalKind.Session, app.aurora.progress.GoalPeriod.Daily, 60), {}, {}, { _, _ -> }) }

    /** A run that just succeeded: Home opens on its finish. */
    @Test fun homeFinished() {
        val app = ApplicationProvider.getApplicationContext<AuroraApplication>()
        val now = System.currentTimeMillis()
        runBlocking {
            app.history.update { it.copy(runs = listOf(RunRecord("f1", "c1", "Study Session", start = now - 7_800_000, plannedEnd = now - 600_000, end = now - 600_000, verifiedMs = 7_200_000, protection = Outcome.Succeeded))) }
        }
        capture("home_finished") { HomeUnderTest() }
    }

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun variants() = Variant.entries.map { arrayOf<Any>(it) }
    }
}
