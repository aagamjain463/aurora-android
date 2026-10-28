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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalContext
import app.aurora.AuroraApplication
import app.aurora.BuildConfig
import app.aurora.data.ThemeMode
import app.aurora.data.UserPreferences
import app.aurora.ui.theme.AuroraTheme
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
 * The only way on is "Close app", which goes home. Back does the same. Phase 3 has no way through;
 * the person changes what's protected in aurora itself (docs/SHIELD.md).
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
                val pkg = request?.packageName ?: return@AuroraTheme
                val catalog = (LocalContext.current.applicationContext as AuroraApplication).catalog
                // Label and icon load off the main thread. The layout is there from the first frame.
                val app by produceState<Pair<String, ImageBitmap?>?>(null, pkg) {
                    value = catalog.app(pkg).let { (it?.label ?: pkg) to it?.icon }
                }
                ShieldScreen(label = app?.first, icon = app?.second, onClose = ::closeApp)
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
        if (!timed && next.fresh) untimed = next
        return true
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
                pkg, reason, getLongExtra(ExtraEvent, now), getLongExtra(ExtraReceived, now), getLongExtra(ExtraDecided, now),
                fresh = getBooleanExtra(ExtraFresh, true),
            )
        }
    }
}
