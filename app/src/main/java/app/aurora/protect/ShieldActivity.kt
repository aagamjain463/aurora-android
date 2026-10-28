package app.aurora.protect

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.FrameMetrics
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.addCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalContext
import app.aurora.AuroraApplication
import app.aurora.BuildConfig
import app.aurora.grant
import app.aurora.pauseStart
import app.aurora.record
import app.aurora.data.ThemeMode
import app.aurora.data.UserPreferences
import app.aurora.ui.theme.AuroraTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.IOException
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicLong

/**
 * The Phase 3 [ShieldPresenter]: [ShieldActivity], started from the service in its own task.
 * [onHidden] runs when the Shield leaves the screen without the person closing it.
 */
class ActivityShieldPresenter(private val context: Context, onHidden: () -> Unit) : ShieldPresenter {
    init {
        ShieldActivity.onHidden = onHidden
    }

    /** The person's theme and motion settings, kept current by the service so the Shield never waits on storage. */
    @Volatile var preferences: UserPreferences? = null

    override fun present(request: ShieldRequest): Boolean = ShieldActivity.show(context, request, preferences)

    override fun dismiss() = ShieldActivity.hide()
}

/**
 * The Shield as a full-screen activity in its own task, out of Recents (AndroidManifest.xml), so it
 * covers the protected app and finishing it never reveals that app. Repeated requests reuse it
 * (singleTask, onNewIntent). The window background is the Shield's own colour, so Android's starting
 * window already hides the app before Compose draws.
 *
 * "Close app" (or "Go back") goes home, and so does Back. Only a Choice Shield offers a way through,
 * after the pause the item asks for: the person picks how long, aurora stores the grant, then opens the
 * app (docs/SHIELD.md).
 */
class ShieldActivity : ComponentActivity() {
    private var request by mutableStateOf<ShieldRequest?>(null)
    /** The request whose first frame hasn't been timed yet. Main thread only. */
    private var untimed: ShieldRequest? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!read(intent, timed = savedInstanceState != null)) return
        current = WeakReference(this)
        val prefs = intent.getStringExtra(ExtraTheme)?.let { name -> ThemeMode.entries.find { it.name == name } }
        val reduceMotion = intent.getBooleanExtra(ExtraReduceMotion, false)

        // The first frame that started after the decision ends the timing: its intended vsync plus its
        // duration is when it reached the compositor. The display shows it at the next refresh.
        window.addOnFrameMetricsAvailableListener({ _, metrics, _ ->
            val pending = untimed ?: return@addOnFrameMetricsAvailableListener
            val vsync = metrics.getMetric(FrameMetrics.INTENDED_VSYNC_TIMESTAMP)
            if (vsync >= pending.decidedUptime * 1_000_000) {
                val timing = ShieldService.trace.shown(pending, (vsync + metrics.getMetric(FrameMetrics.TOTAL_DURATION)) / 1_000_000)
                untimed = null
                // Debug builds only, and timings only: never the package name.
                if (BuildConfig.DEBUG) Log.d("AuroraShield", "timing $timing")
            }
        }, Handler(Looper.getMainLooper()))

        onBackPressedDispatcher.addCallback(this) { closeApp() }

        setContent {
            val dark = when (prefs) {
                ThemeMode.Light -> false
                ThemeMode.Dark -> true
                else -> isSystemInDarkTheme()
            }
            DisposableEffect(dark) {
                val style = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { dark }
                enableEdgeToEdge(style, style)
                onDispose { }
            }
            AuroraTheme(darkTheme = dark, reduceMotion = reduceMotion) {
                val req = request ?: return@AuroraTheme
                val pkg = req.packageName
                val aurora = LocalContext.current.applicationContext as AuroraApplication
                val website = pkg.startsWith(WebPrefix)
                // Label and icon load off the main thread. The layout is there from the first frame.
                val target by produceState<Pair<String, ImageBitmap?>?>(null, pkg) {
                    value = if (website) pkg.removePrefix(WebPrefix) to null else aurora.catalog.app(pkg).let { (it?.label ?: pkg) to it?.icon }
                }
                val rules by aurora.rules.state.collectAsStateWithLifecycle()
                val r = rules?.value
                val rule = r?.rule(pkg)
                val note = rule?.intent?.takeIf { r.activeIntents(aurora.plan).contains(pkg) }
                val pauseMs = 1000L * maxOf(if (rule?.mode == Mode.Friction) rule.waitSeconds else 0, note?.reflectionSeconds ?: 0)
                var pauseStart by remember(req) { mutableStateOf<Long?>(null) }
                var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
                LaunchedEffect(req, pauseMs) {
                    if (req.reason == ShieldReason.Choice && pauseMs > 0) {
                        // A pause already running for this app continues: leaving and coming back neither skips nor restarts it.
                        pauseStart = try { aurora.pauseStart(pkg) } catch (_: IOException) { System.currentTimeMillis() }
                        while (System.currentTimeMillis() < pauseStart!! + pauseMs) {
                            now = System.currentTimeMillis()
                            delay(200)
                        }
                    }
                    now = System.currentTimeMillis()
                }
                val haptics = LocalHapticFeedback.current
                ShieldScreen(
                    label = target?.first,
                    icon = target?.second,
                    info = ShieldInfo(
                        reason = req.reason,
                        until = req.until,
                        mode = rule?.mode ?: Mode.Block,
                        intent = note,
                        pauseMs = pauseMs,
                        pauseStart = pauseStart,
                        cooldownMinutes = rule?.cooldownMinutes ?: 0,
                        limitMinutes = r?.limits?.find { it.key == pkg }?.minutes,
                        website = website,
                    ),
                    now = now,
                    onClose = {
                        if (req.reason == ShieldReason.Choice) aurora.scope.launch { runCatching { aurora.record { it.copy(wentBack = it.wentBack + 1) } } }
                        closeApp()
                    },
                    onContinue = { minutes ->
                        haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                        lifecycleScope.launch {
                            try {
                                aurora.grant(pkg, minutes)
                                openTarget(pkg)
                            } catch (_: IOException) {
                                // Not stored, so not granted: the Shield stays, and the person can try again.
                            }
                        }
                    },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        read(intent, timed = false)
    }

    override fun onStop() {
        super.onStop()
        // Something covered the Shield. Closing, hide() and rotation don't count.
        if (!isFinishing && !isChangingConfigurations && isWanted(intent)) onHidden?.invoke()
    }

    override fun onDestroy() {
        if (current?.get() === this) current = null
        super.onDestroy()
    }

    /** False, and finishing, when this intent is no longer the Shield aurora wants on screen. */
    private fun read(intent: Intent, timed: Boolean): Boolean {
        val next = intent.toRequest()
        if (next == null || !isWanted(intent)) {
            finish()
            return false
        }
        request = next
        if (!timed && next.fresh) {
            untimed = next
            // One intervention per opening: repeats and re-shows aren't counted.
            val app = application as AuroraApplication
            app.scope.launch { runCatching { app.record { it.copy(shields = it.shields + 1) } } }
        }
        return true
    }

    /** After a grant: back to the site (the browser is underneath), or open the app. */
    private fun openTarget(key: String) {
        if (!key.startsWith(WebPrefix)) {
            packageManager.getLaunchIntentForPackage(key)?.let {
                try {
                    startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                } catch (_: ActivityNotFoundException) {
                    // The app is gone; there's nothing to open.
                }
            }
        }
        finish()
    }

    private fun closeApp() {
        try {
            startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: ActivityNotFoundException) {
            // No home screen to go to. Finishing still leaves the Shield; Android shows what's next.
        }
        finish()
    }

    companion object {
        private const val ExtraPackage = "package"
        private const val ExtraReason = "reason"
        private const val ExtraUntil = "until"
        private const val ExtraEvent = "event_uptime"
        private const val ExtraReceived = "received_uptime"
        private const val ExtraDecided = "decided_uptime"
        private const val ExtraFresh = "fresh"
        private const val ExtraToken = "token"
        private const val ExtraTheme = "theme"
        private const val ExtraReduceMotion = "reduce_motion"

        private val generations = AtomicLong()

        /**
         * Every intent carries the [generation] it was sent in. [hide] starts a new generation, so an
         * activity started before the person switched away finishes instead of appearing. A newer
         * [show] doesn't: it's the same Shield, asked for again. A new process starts with nothing wanted.
         */
        @Volatile private var generation = 0L
        @Volatile private var wanted = false

        private fun isWanted(intent: Intent) = wanted && intent.getLongExtra(ExtraToken, -1L) == generation
        private var current: WeakReference<ShieldActivity>? = null
        internal var onHidden: (() -> Unit)? = null

        fun show(context: Context, request: ShieldRequest, preferences: UserPreferences?): Boolean {
            val intent = intentFor(context, request, preferences)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
            return try {
                context.startActivity(intent)
                true
            } catch (_: RuntimeException) {
                // ActivityNotFoundException or a SecurityException from a background-start restriction.
                false
            }
        }

        fun hide() {
            wanted = false
            generation = generations.incrementAndGet()
            current?.get()?.finish()
        }

        /** The intent for [request], which also makes it the Shield aurora wants on screen. */
        internal fun intentFor(context: Context, request: ShieldRequest, preferences: UserPreferences? = null): Intent {
            wanted = true
            val token = generation
            return Intent(context, ShieldActivity::class.java)
                .putExtra(ExtraPackage, request.packageName)
                .putExtra(ExtraReason, request.reason.name)
                .putExtra(ExtraUntil, request.until ?: -1L)
                .putExtra(ExtraEvent, request.eventUptime)
                .putExtra(ExtraReceived, request.receivedUptime)
                .putExtra(ExtraDecided, request.decidedUptime)
                .putExtra(ExtraFresh, request.fresh)
                .putExtra(ExtraToken, token)
                .putExtra(ExtraTheme, preferences?.themeMode?.name)
                .putExtra(ExtraReduceMotion, preferences?.reduceMotion == true)
        }

        private fun Intent.toRequest(): ShieldRequest? {
            val pkg = getStringExtra(ExtraPackage) ?: return null
            val reason = ShieldReason.entries.find { it.name == getStringExtra(ExtraReason) } ?: return null
            val now = SystemClock.uptimeMillis()
            return ShieldRequest(
                pkg, reason, getLongExtra(ExtraUntil, -1L).takeIf { it >= 0 },
                getLongExtra(ExtraEvent, now), getLongExtra(ExtraReceived, now), getLongExtra(ExtraDecided, now),
                fresh = getBooleanExtra(ExtraFresh, true),
            )
        }
    }
}
