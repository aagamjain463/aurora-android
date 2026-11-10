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
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
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
import app.aurora.ui.track.SessionHistoryScreen
import app.aurora.ui.track.TrackScreen
import app.aurora.ui.track.TrackTab
import java.time.LocalDate
import app.aurora.ui.settings.SettingsScreen
import app.aurora.ui.theme.Aurora
import app.aurora.ui.theme.AuroraTheme
import com.github.takahirom.roborazzi.captureRoboImage
import app.aurora.data.ThemeMode
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import app.aurora.progress.RunRecord
import app.aurora.progress.Outcome
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle

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
        Light(false, 1f, false),
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
        app.preferences.setThemeMode(if (variant.dark) ThemeMode.Dark else ThemeMode.Light)
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
                AuroraTheme(reduceMotion = variant.reduceMotion, themeMode = if (variant.dark) app.aurora.data.ThemeMode.Dark else app.aurora.data.ThemeMode.Light) {
                    Box(Modifier.fillMaxSize().background(Aurora.colors.background)) { content() }
                }
            }
        }
        if (screen == "track" || screen == "track_last_night") compose.waitUntil(5_000) {
            compose.onAllNodesWithText("Succeeded").fetchSemanticsNodes().isNotEmpty()
        }
        if (screen == "track_goals") compose.waitUntil(5_000) {
            compose.onAllNodesWithText("Create a Habit").fetchSemanticsNodes().isNotEmpty()
        }
        if (screen == "track_usage" || screen == "insights") compose.waitUntil(5_000) {
            compose.onAllNodesWithText("since midnight", substring = true).fetchSemanticsNodes().isNotEmpty() ||
                compose.onAllNodesWithText("See where your time goes").fetchSemanticsNodes().isNotEmpty()
        }
        if (screen == "habit_history") compose.waitUntil(5_000) {
            compose.onAllNodesWithText("Read every day").fetchSemanticsNodes().isNotEmpty()
        }
        if (screen.startsWith("shield_")) compose.waitUntil(5_000) {
            compose.onAllNodesWithText("Photos", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        if (screen == "pass_chooser") {
            compose.waitUntil(5_000) { compose.onAllNodesWithText("Pass").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Pass").performClick()
            compose.waitForIdle()
        }
        if (screen == "pass_editor") {
            compose.waitUntil(5_000) { compose.onAllNodesWithText("Reddit for work").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Reddit for work").performClick()
            compose.waitForIdle()
        }
        compose.onRoot().captureRoboImage("src/test/screenshots/${screen}_${variant.name}.png")
    }

    @Test fun onboarding() {
        runBlocking { application.preferences.setOnboardingStep(0) }
        capture("onboarding") { OnboardingScreen(onFinish = {}) }
    }

    @Test fun home() = capture("home") { HomeUnderTest() }

    /** Include the real dock and system insets, including at 200% font scale. */
    @Test fun homeNavigation() = capture("home_navigation") { app.aurora.ui.AuroraNavHost(startOnboarded = true) }

    @Composable
    private fun HomeUnderTest() = HomeScreen(onStart = {}, onOpenFocus = {}, onOpenLegacyFocus = {}, onOpenSettings = {})

    @Test fun track() = capture("insights") { TrackScreen() }

    @Test fun settings() = capture("settings") { SettingsScreen(onBack = {}, onReplayIntro = {}) }

    @Test fun focus() = capture("focus") { FocusScreen(onClose = {}) }

    private val application get() = ApplicationProvider.getApplicationContext<AuroraApplication>()

    /** A session 30 minutes into two hours. Runtime is reset before every test. */
    @Test fun focusRunning() {
        // startFocus waits on the entitlements lock, and the lock's holder may need the main looper to finish.
        // Blocking the main thread deadlocks, so run it elsewhere and keep the looper turning until it's done.
        val start = kotlinx.coroutines.CoroutineScope(Dispatchers.Default).async {
            application.startFocus(120, null, null, "Study mathematics")
            application.runtime.update { r -> r.copy(focus = r.focus?.let { it.copy(startElapsed = it.startElapsed - 30 * 60_000L, startWall = it.startWall - 30 * 60_000L) }) }
        }
        val deadline = System.currentTimeMillis() + 10_000
        while (!start.isCompleted) {
            check(System.currentTimeMillis() < deadline) { "startFocus didn't finish" }
            shadowOf(android.os.Looper.getMainLooper()).idle()
            Thread.sleep(5)
        }
        start.getCompletionExceptionOrNull()?.let { throw it }
        capture("focus_running") { FocusScreen(onClose = {}) }
    }

    /** A 25-minute session that ended a minute ago: the level has drained. */
    @Test fun focusComplete() {
        val start = kotlinx.coroutines.CoroutineScope(Dispatchers.Default).async {
            application.startFocus(25, null, null, "Study mathematics")
            application.runtime.update { r -> r.copy(focus = r.focus?.let { it.copy(startElapsed = it.startElapsed - 26 * 60_000L, startWall = it.startWall - 26 * 60_000L) }) }
        }
        val deadline = System.currentTimeMillis() + 10_000
        while (!start.isCompleted) {
            check(System.currentTimeMillis() < deadline) { "startFocus didn't finish" }
            shadowOf(android.os.Looper.getMainLooper()).idle()
            Thread.sleep(5)
        }
        start.getCompletionExceptionOrNull()?.let { throw it }
        capture("focus_complete") { FocusScreen(onClose = {}) }
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
            record("r$back", "c1", "Study Session", true, "Social Media Shield", Mode.Cooldown, "Intentional YouTube", setOf("com.example.photos"), start, start + 7_200_000, start + 7_200_000, spans, false, null, 0, 0)
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

    /**
     * One session running with two Shields, an Intent and two unlocks (a break used once). [unlocked]: a break is on now.
     * Saved as "Deep work" for the start and contents screens.
     */
    private fun busy(unlocked: Boolean = false) = runBlocking {
        library()
        val now = System.currentTimeMillis()
        val focus = ShieldPreset("s3", "Games", listOf("web:chess.com"), Mode.Friction, createdAt = 3)
        val chat = IntentPreset("i2", "Intentional chat", listOf("web:discord.com"), IntentConfig(reflectionSeconds = 10), createdAt = 3)
        val unlocks = listOf(
            app.aurora.protect.SessionUnlock("u1", "Break", minutes = 10, times = 5),
            app.aurora.protect.SessionUnlock("u2", "Reddit for work", partIds = listOf("s1"), minutes = 30, times = 1, focusRuns = true),
        )
        val deep = UsageConfig(
            "deep", "Deep work", shieldIds = listOf("s1", "s3"), intentIds = listOf("i2"), mode = Mode.Cooldown, cooldownWaitMinutes = 5,
            focus = FocusPlan("Deep work", goal = "Draft the essay"), unlocks = unlocks, createdAt = 9,
        )
        application.rules.update { it.copy(shields = it.shields + focus, intentPresets = it.intentPresets + chat, configs = it.configs + deep) }
        val breaks = listOf(app.aurora.protect.UnlockUse("u1", now - 15 * 60_000L, now - 5 * 60_000L)) +
            if (unlocked) listOf(app.aurora.protect.UnlockUse("u1", now - 2 * 60_000L, now + 8 * 60_000L)) else emptyList()
        application.runtime.update {
            it.copy(unlocks = mapOf("now:x" to breaks), runs = listOf(
                ConfigRun(
                    "now:x", "deep", "Deep work", shield = social.copy(keys = social.keys + "web:chess.com", mode = Mode.Cooldown), intent = chat,
                    focus = deep.focus, start = now - 3_600_000, end = now + 1_800_000, mode = Mode.Cooldown, unlocks = unlocks,
                ),
            ))
        }
    }

    /** A running session fills the phone's first screen; Time Reclaimed waits a scroll below. */
    @Config(qualifiers = "w411dp-h891dp-xxhdpi")
    @Test fun homeSessionPhone() {
        busy()
        capture("home_session_phone") { HomeUnderTest() }
    }

    /** The Focus timer with passes: one quiet pill at its foot. */
    @Config(qualifiers = "w411dp-h891dp-xxhdpi")
    @Test fun focusRunPasses() {
        busy()
        capture("focus_run_passes") { FocusRunScreen("now:x") {} }
    }

    /** The same during a Break: the pill turns to Lock again. */
    @Config(qualifiers = "w411dp-h891dp-xxhdpi")
    @Test fun focusRunOnPass() {
        busy(unlocked = true)
        capture("focus_run_on_pass") { FocusRunScreen("now:x") {} }
        compose.onNodeWithText("Paused · Break").assertIsDisplayed()
    }

    /** Using a pass: each by name with how many are left, nothing more. */
    @Config(qualifiers = "w411dp-h891dp-xxhdpi")
    @Test fun passChooser() {
        busy()
        capture("pass_chooser") { HomeUnderTest() }
    }

    /** On a tablet turned sideways (phones stay upright): what it is beside its controls. */
    @Config(qualifiers = "w1280dp-h800dp-land-xhdpi")
    @Test fun homeSessionLandscape() {
        busy()
        capture("home_session_landscape") { HomeUnderTest() }
    }

    /** Home with the session running: its time, apps, Focus and Unlock. */
    @Config(qualifiers = "w411dp-h1900dp-xxhdpi")
    @Test fun homeMany() {
        busy()
        capture("home_many") { HomeUnderTest() }
    }

    /** A break on: the session says so, with its own clock and the way to lock again now. */
    @Config(qualifiers = "w411dp-h1900dp-xxhdpi")
    @Test fun sessionUnlocked() {
        busy(unlocked = true)
        capture("session_unlocked") { HomeUnderTest() }
    }

    /** A new session's Shields and Intents: as many as wanted, each app in one of them. */
    @Config(qualifiers = "w411dp-h1900dp-xxhdpi")
    @Test fun sessionContents() {
        busy()
        capture("session_contents") { ConfigureScreen(true, "deep", false, null, null, {}, {}, {}, {}, {}, {}, startStep = "Contents") }
    }

    /** A session's unlocks, set up with it. */
    @Config(qualifiers = "w411dp-h1900dp-xxhdpi")
    @Test fun sessionUnlocks() {
        busy()
        capture("session_unlocks") { ConfigureScreen(true, "deep", false, null, null, {}, {}, {}, {}, {}, {}, startStep = "Unlocks") }
    }

    /** A running session in full: its time, what it has given back, what it is, blocks and allows. */
    @Config(qualifiers = "w411dp-h2000dp-xxhdpi")
    @Test fun runDetail() {
        busy()
        capture("run_detail") { app.aurora.ui.session.RunDetailScreen("now:x", {}, {}) }
    }

    /** A new session's passes, none yet: the three kinds, each a tap from being added. */
    @Test fun passesEmpty() {
        library()
        capture("passes_empty") { Configure(custom = false, config = "c1", step = "Unlocks", timing = When.Now) }
    }

    /** One pass opened to change: what it is for, its name, its apps, how long, how often. */
    @Config(qualifiers = "w411dp-h1400dp-xxhdpi")
    @Test fun passEditor() {
        busy()
        capture("pass_editor") { ConfigureScreen(true, "deep", false, null, null, {}, {}, {}, {}, {}, {}, startSaved = true) }
    }

    /** Starting a saved session: its unlocks first, with the rest to view, not change. */
    @Config(qualifiers = "w411dp-h1900dp-xxhdpi")
    @Test fun sessionStart() {
        busy()
        capture("session_start") { ConfigureScreen(true, "deep", false, null, null, {}, {}, {}, {}, {}, {}, startSaved = true) }
    }

    /** Starting a session whose app another session already holds then. */
    @Test fun conflict() {
        library()
        capture("conflict") {
            val vm = app.aurora.auroraViewModel { app.aurora.ui.protect.ProtectModel(it) }
            val d by vm.data.collectAsStateWithLifecycle()
            val now = System.currentTimeMillis()
            d?.let { app.aurora.ui.session.ConflictDialog(app.aurora.protect.Conflict(app.aurora.protect.ConflictKind.Apps, setOf("com.example.photos"), "Study Session", now, now + 3_600_000), it) {} }
        }
    }

    /** Home's Start a session: New or Saved. */
    @Test fun startChooser() = capture("start_session") { app.aurora.ui.session.StartChooser({}, {}, {}) }

    /** Saved sessions on their own screen, opened only from Home's Saved Sessions button. */
    @Config(qualifiers = "w411dp-h1900dp-xxhdpi")
    @Test fun savedSessions() {
        library()
        capture("saved_sessions") { app.aurora.ui.home.SavedSessionsScreen({}, {}, {}) }
    }

    /** Saved sessions, nothing running: Home offers a Saved Sessions button under Start session. */
    @Config(qualifiers = "w411dp-h1900dp-xxhdpi")
    @Test fun homeReady() {
        library()
        capture("home_ready") { HomeUnderTest() }
    }

    @Config(qualifiers = "w411dp-h1900dp-xxhdpi")
    @Test fun shieldLibrary() {
        library()
        capture("library_shield") { ShieldLibraryScreen({}, {}, {}) }
    }

    @Test fun shieldBuilder() {
        library()
        capture("builder_shield") { ShieldEditorScreen("s1", {}, {}, startStep = 4) }
    }

    @Test fun shieldBuilderParts() {
        library()
        capture("builder_parts") { ShieldEditorScreen("s1", {}, {}, startStep = 3) }
    }

    @Config(qualifiers = "w411dp-h1900dp-xxhdpi")
    @Test fun intentLibrary() {
        library()
        capture("intent_library") { IntentLibraryScreen({}, {}, {}) }
    }

    @Test fun intentBuilder() {
        library()
        capture("intent_builder") { IntentEditorScreen("i1", {}, {}, startStep = 3) }
    }

    @Composable
    private fun Configure(custom: Boolean, config: String? = null, step: String? = null, timing: When? = null) =
        ConfigureScreen(custom, config, false, null, null, {}, {}, {}, {}, {}, {}, startStep = step, startWhen = timing)

    @Test fun normalUse() = capture("configure_normal") { Configure(custom = false) }

    /** How strict the session is: its own step, after what it holds. */
    @Test fun configureStrictness() {
        library()
        capture("configure_strictness") { Configure(custom = false, step = "Strictness") }
    }

    @Test fun onboardingShield() {
        runBlocking { application.preferences.setOnboardingStep(app.aurora.ui.onboarding.ShieldPage) }
        capture("onboarding_shield") { OnboardingScreen(onFinish = {}) }
    }

    /** How Aurora works: the four tabs on one card. */
    @Test fun onboardingHow() {
        runBlocking { application.preferences.setOnboardingStep(1) }
        capture("onboarding_how") { OnboardingScreen(onFinish = {}) }
    }

    /** A page for each tab. */
    @Test fun onboardingTabShield() = tab(2, "onboarding_tab_shield")
    @Test fun onboardingTabIntent() = tab(3, "onboarding_tab_intent")
    @Test fun onboardingTabHome() = tab(4, "onboarding_tab_home")
    @Test fun onboardingTabTrack() = tab(5, "onboarding_tab_track")

    private fun tab(step: Int, name: String) {
        runBlocking { application.preferences.setOnboardingStep(step) }
        capture(name) { OnboardingScreen(onFinish = {}) }
    }

    /** Habits completed move to History, with their results. */
    @Config(qualifiers = "w411dp-h1900dp-xxhdpi")
    @Test fun habitHistory() {
        library()
        runBlocking {
            application.rules.update { r ->
                r.copy(trackGoals = r.trackGoals + app.aurora.progress.Goal("done", "Read every day", GoalKind.Session, app.aurora.progress.GoalPeriod.Daily, 30, configId = "c1", createdAt = System.currentTimeMillis() - 86_400_000L * 9, status = app.aurora.progress.GoalStatus.Completed, pauses = listOf(app.aurora.progress.GoalPause(System.currentTimeMillis() - 86_400_000L))))
            }
        }
        capture("habit_history") { app.aurora.ui.track.HabitHistoryScreen(onBack = {}) }
    }

    /** Review as a person sees it with Shield on, and with Shield off. */
    private fun grantShield() = android.provider.Settings.Secure.putString(application.contentResolver, android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, "app.aurora/.protect.ShieldService")

    /** Review for a session made from New session: Save this session sits above Focus and the message. */
    @Test fun reviewNewSession() {
        library()
        grantShield()
        capture("configure_review_new") { Configure(custom = false, config = "c1", step = "Review", timing = When.Now) }
    }

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
        capture("configure_now") { Configure(custom = true, config = "c1", step = "Timing", timing = When.Now) }
    }

    @Test fun startLater() {
        library()
        capture("configure_later") { Configure(custom = true, config = "c1", step = "Timing", timing = When.Later) }
    }

    @Test fun schedule() {
        library()
        capture("configure_schedule") { Configure(custom = true, config = "c1", step = "Timing", timing = When.Recurring) }
    }

    /** Always is shown but not offered with a Strict Shield. */
    @Test fun alwaysUnavailable() {
        library()
        capture("configure_when_always_block") { Configure(custom = true, config = "c2", step = "When") }
    }

    // Tall, so the whole commitment group and "I'm ready to commit" below it are in the picture.
    @Config(qualifiers = "w411dp-h1900dp-xxhdpi")
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
        capture("track") { TrackScreen(start = TrackTab.Progress) }
    }

    /** Protection that crossed midnight: today's time is explained, since today's sessions don't count it. */
    @Config(qualifiers = "w411dp-h1900dp-xxhdpi")
    @Test fun trackLastNight() {
        val midnight = java.time.LocalDate.now().atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        runBlocking { application.history.update { it.copy(protection = listOf(app.aurora.progress.ProtectionRecord("night", midnight - 2 * 3_600_000, midnight + 6 * 3_600_000, setOf("com.example.photos")))) } }
        capture("track_last_night") { TrackScreen(start = TrackTab.Progress) }
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
    @Test fun diagnostics() = capture("diagnostics") {
        app.aurora.ui.protect.ShieldDiagnostics(app.aurora.protect.ShieldTrace.Snapshot(0, 0, 0, 0, 0, 0, 0, 0, null, null, emptyList(), emptyList()), {})
    }

    @Test fun focusRun() {
        library(running = true)
        capture("focus_run") { FocusRunScreen("now:x") {} }
        compose.onNodeWithText("Focused").assertDoesNotExist()
    }

    @Test fun shieldCooldown() = capture("shield_cooldown") { ShieldContent(shield(ShieldReason.Cooldown)) { } }

    /** The Shield follows the device's own rotation: a phone held sideways in a blocked app. */
    @Config(qualifiers = "w891dp-h411dp-land-xxhdpi")
    @Test fun shieldLandscape() = capture("shield_landscape") { ShieldContent(shield(ShieldReason.Cooldown)) { } }

    /** A running Intent session, so the cooldown belongs to one and the Shield shows its timer. */
    @Test fun shieldIntentCooldown() {
        library(running = true)
        capture("shield_intent_cooldown") { ShieldContent(shield(ShieldReason.IntentCooldown)) { } }
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

    /** Intent only exists inside a session: without one, the Shield says the timer ended. */
    @Test fun shieldIntent() {
        library(running = true)
        capture("shield_intent") { ShieldContent(shield(ShieldReason.Intent, null)) { } }
    }

    /** A session started with the person's own reason: it shows under every Shield the session raises. */
    private fun noted() {
        library(running = true)
        runBlocking { application.runtime.update { it.copy(runs = it.runs.map { r -> r.copy(note = "Exam on Friday. Every hour here is an hour of revision.") }) } }
    }

    @Test fun shieldFlexibleNote() {
        noted()
        capture("shield_flexible_note") { ShieldContent(shield(ShieldReason.Flexible)) { } }
    }

    @Test fun shieldIntentNote() {
        noted()
        capture("shield_intent_note") { ShieldContent(shield(ShieldReason.Intent, null)) { } }
    }

    // A phone on its side: every moment fits without scrolling.
    @Config(qualifiers = "w914dp-h411dp-xxhdpi")
    @Test fun shieldFlexibleNoteLand() {
        noted()
        capture("shield_flexible_note_land") { ShieldContent(shield(ShieldReason.Flexible)) { } }
    }

    @Config(qualifiers = "w914dp-h411dp-xxhdpi")
    @Test fun shieldIntentNoteLand() {
        noted()
        capture("shield_intent_note_land") { ShieldContent(shield(ShieldReason.Intent, null)) { } }
    }

    @Config(qualifiers = "w914dp-h411dp-xxhdpi")
    @Test fun shieldFrictionLand() = capture("shield_friction_land") { ShieldContent(shield(ShieldReason.Friction)) { } }

    @Config(qualifiers = "w914dp-h411dp-xxhdpi")
    @Test fun shieldIntentCooldownLand() {
        library(running = true)
        capture("shield_intent_cooldown_land") { ShieldContent(shield(ShieldReason.IntentCooldown)) { } }
    }

    @Config(qualifiers = "w411dp-h2400dp-xxhdpi")
    @Test fun plus() = capture("plus") { app.aurora.ui.plus.PlusScreen(onBack = {}) }
    @Test fun plus_trial_offer() = capture("plus_trial_offer") { app.aurora.ui.plus.TrialSection(app.aurora.account.RemoteTrial("available", days = 14), onTrial = false, free = true, message = null) {} }
    @Test fun plus_trial_active() = capture("plus_trial_active") { app.aurora.ui.plus.TrialSection(app.aurora.account.RemoteTrial("active", startedAt = 1_762_000_000_000, endsAt = 1_763_209_600_000), onTrial = true, free = false, message = null) {} }

    @Test fun login() {
        capture("login") { app.aurora.ui.account.Login(onEmail = {}) }
        val help = compose.onNodeWithText("Trouble signing in?", useUnmergedTree = true)
        val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        help.performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) { it(layouts) }
        org.junit.Assert.assertEquals("Help should remain a readable single line", 1, layouts.single().lineCount)
        org.junit.Assert.assertFalse("Help text must remain complete", layouts.single().isLineEllipsized(0))
        val privacy = compose.onNodeWithText("Privacy Policy", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        org.junit.Assert.assertTrue("Help belongs below the legal links", help.fetchSemanticsNode().boundsInRoot.top >= privacy.bottom)
    }
    @Test fun account() =capture("account") { app.aurora.ui.account.AccountScreen(onBack = {}, onPlus = {}) }
    @Test fun permission_shield() = capture("permission_shield") {
        val ask = app.aurora.ui.protect.rememberPermissionRequest()
        androidx.compose.runtime.LaunchedEffect(Unit) { ask(app.aurora.data.Access.Accessibility) }
    }
    /** A session's details: its Shield and its Intent each a card, with what each covers. */
    @Test fun sessionDetails() = capture("session_details") {
        app.aurora.ui.library.DetailsDialog(
            "Study Session",
            listOf("Your note" to "Exam on Friday."),
            {},
            listOf(
                app.aurora.ui.library.DetailSection(app.aurora.ui.components.AuroraIcons.Protect, "Shield · blocked", "Social Media Shield · Friction", listOf("Apps" to "Instagram, YouTube", "Websites" to "reddit.com")),
                app.aurora.ui.library.DetailSection(app.aurora.ui.components.AuroraIcons.Intent, "Intent · asks before opening", "Mindful Opening · 10s pause", listOf("Apps" to "Photos, Chrome")),
            ),
        ) {}
    }
    @Test fun limit_shield() = capture("limit_shield") { app.aurora.ui.plus.LimitPromptDialog(app.aurora.LimitPrompt(app.aurora.plan.LimitDecision(false, app.aurora.plan.LimitReason.ShieldTargets, 5, 5, 0, app.aurora.plan.Remedy.FreeASlot, true)), {}, {}) }
    @Test fun limit_committed() = capture("limit_committed") { app.aurora.ui.plus.LimitPromptDialog(app.aurora.LimitPrompt(app.aurora.plan.LimitDecision(false, app.aurora.plan.LimitReason.CommittedProtection, 95 * 60_000L, 120 * 60_000L, 25 * 60_000L, app.aurora.plan.Remedy.ChooseShorter, true, resetAt = 0), { }), {}, {}) }
    @Test fun goal_limit() = capture("goal_limit") { app.aurora.ui.track.GoalLimitDialog(app.aurora.progress.Goal("g", "Daily Study", app.aurora.progress.GoalKind.Session, app.aurora.progress.GoalPeriod.Daily, 60), {}, {}, { _, _ -> }) }

    /** All sessions, from Progress: each finished session with its recorded result. */
    @Test fun sessionHistory() {
        val app = ApplicationProvider.getApplicationContext<AuroraApplication>()
        val now = System.currentTimeMillis()
        runBlocking {
            app.history.update { it.copy(runs = listOf(RunRecord("f1", "c1", "Study Session", start = now - 7_800_000, plannedEnd = now - 600_000, end = now - 600_000, verifiedMs = 7_200_000, protection = Outcome.Succeeded))) }
        }
        capture("session_history") { SessionHistoryScreen(onBack = {}) }
    }

    @Test fun sessionHistoryDay() {
        val app = ApplicationProvider.getApplicationContext<AuroraApplication>()
        val now = System.currentTimeMillis()
        runBlocking {
            app.history.update { it.copy(runs = listOf(RunRecord("f1", "c1", "Study Session", start = now - 7_800_000, plannedEnd = now - 600_000, end = now - 600_000, verifiedMs = 7_200_000, protection = Outcome.Succeeded))) }
        }
        capture("session_history_day") { SessionHistoryScreen({}, initialDay = java.time.LocalDate.now().toString()) }
    }

    /** How Aurora works: every tab explained, from Settings or any tab's info button. */
    @Test fun guide() = capture("guide") { app.aurora.ui.guide.GuideScreen {} }

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun variants() = Variant.entries.map { arrayOf<Any>(it) }
    }
}
