package app.aurora.spike

import android.accessibilityservice.AccessibilityService
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.PixelFormat
import android.provider.Settings
import android.telecom.TelecomManager
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.FrameLayout
import androidx.compose.ui.platform.ComposeView
import androidx.core.content.edit
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import app.aurora.AuroraApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

const val SpikeTag = "AuroraSpike"

/**
 * G1 spike: watches window-state changes only, decides with [spikeDecide], and shows the Shield
 * either as an aurora activity in its own task (default) or as a TYPE_ACCESSIBILITY_OVERLAY window.
 * Every step is logged under [SpikeTag] so the feasibility numbers can be read from logcat.
 */
class SpikeShieldService : AccessibilityService() {
    private var scope: CoroutineScope? = null
    @Volatile private var protected: Set<String> = emptySet()
    private var exempt: Set<String> = SystemExempt
    private var overlay: View? = null
    /** Last real app in front. aurora, the shade and the keyboard don't count, so a Shield doesn't reset it. */
    private var lastForeground: String? = null

    override fun onServiceConnected() {
        connected = true
        exempt = SystemExempt + runtimeExempt(this)
        scope = MainScope().also { s ->
            s.launch { (application as AuroraApplication).preferences.preferences.collect { protected = it.protectedApps } }
        }
        Log.i(SpikeTag, "service connected exempt=$exempt")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        val decision = spikeDecide(pkg, protected, exempt, grants, System.currentTimeMillis())
        Log.d(SpikeTag, "window pkg=$pkg class=${event.className} decision=$decision")
        val transient = pkg == packageName || pkg == "com.android.systemui" || pkg in imePackages
        val fresh = pkg != lastForeground
        if (!transient) lastForeground = pkg
        when {
            decision is SpikeDecision.Shield -> shield(pkg, event.eventTime, fresh)
            // Anything real in front (launcher, another app) dismisses an overlay Shield. The overlay's own
            // window, the keyboard and the notification shade don't.
            !transient -> removeOverlay()
        }
    }

    private fun shield(pkg: String, eventUptime: Long, fresh: Boolean) {
        if (fresh) probeUsageStatsLatency(pkg)
        if (mode(this) == Mode.Overlay) {
            showOverlay(pkg, eventUptime)
        } else {
            // No debounce: the protected app may start its own activity over the Shield (it keeps a
            // background-launch grace period), so every window event of a protected app re-asserts it.
            // singleTask turns repeats into onNewIntent.
            startActivity(
                Intent(this, SpikeShieldActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
                    .putExtra(SpikeShieldActivity.ExtraPackage, pkg)
                    .putExtra(SpikeShieldActivity.ExtraEventUptime, eventUptime)
                    .putExtra(SpikeShieldActivity.ExtraFresh, fresh),
            )
            Log.i(SpikeTag, "startActivity for $pkg")
        }
    }

    private fun showOverlay(pkg: String, eventUptime: Long) {
        if (overlay != null && shownFor == pkg) return
        removeOverlay()
        val owner = OverlayOwner()
        val compose = ComposeView(this).apply {
            setContent {
                SpikeShieldContent(
                    packageName = pkg,
                    eventUptime = eventUptime,
                    mechanism = "overlay",
                    onClose = { removeOverlay(); performGlobalAction(GLOBAL_ACTION_HOME) },
                    onOpen = { minutes -> grant(pkg, minutes); removeOverlay() },
                )
            }
        }
        // Back closes the app, as on the activity Shield. Without this, Back reaches the app underneath.
        val root = object : FrameLayout(this) {
            override fun dispatchKeyEvent(event: KeyEvent): Boolean {
                if (event.keyCode == KeyEvent.KEYCODE_BACK) {
                    if (event.action == KeyEvent.ACTION_UP) { removeOverlay(); performGlobalAction(GLOBAL_ACTION_HOME) }
                    return true
                }
                return super.dispatchKeyEvent(event)
            }
        }.apply {
            // Compose looks the owners up from the window's root view.
            setViewTreeLifecycleOwner(owner)
            setViewTreeSavedStateRegistryOwner(owner)
            addView(compose)
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.OPAQUE,
        ).apply { softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE }
        getSystemService(WindowManager::class.java).addView(root, params)
        overlay = root
        shownFor = pkg
        Log.i(SpikeTag, "overlay added for $pkg")
    }

    private fun removeOverlay() {
        val view = overlay ?: return
        getSystemService(WindowManager::class.java).removeView(view)
        overlay = null
        shownFor = null
        Log.i(SpikeTag, "overlay removed")
    }

    /** Measures how long after the window event UsageStatsManager reports the same app as resumed. */
    private fun probeUsageStatsLatency(pkg: String) {
        val app = application as AuroraApplication
        if (!app.screenTime.hasAccess()) return
        val usage = getSystemService(UsageStatsManager::class.java)
        val started = System.currentTimeMillis()
        scope?.launch(Dispatchers.IO) {
            while (System.currentTimeMillis() - started < 5_000) {
                val events = usage.queryEvents(started - 2_000, System.currentTimeMillis())
                val e = UsageEvents.Event()
                while (events.hasNextEvent()) {
                    events.getNextEvent(e)
                    if (e.packageName == pkg && e.eventType == 1 /* ACTIVITY_RESUMED */) {
                        Log.i(SpikeTag, "usage-latency pkg=$pkg visibleAfterMs=${System.currentTimeMillis() - started}")
                        return@launch
                    }
                }
                delay(25)
            }
            Log.i(SpikeTag, "usage-latency pkg=$pkg notVisibleWithinMs=5000")
        }
    }

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: Intent?): Boolean {
        Log.i(SpikeTag, "service unbind")
        connected = false
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        Log.i(SpikeTag, "service destroyed")
        connected = false
        removeOverlay()
        scope?.cancel()
        super.onDestroy()
    }

    private val imePackages: Set<String>
        get() = setOfNotNull(Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)?.substringBefore('/'))

    /** Minimal owner so Compose can run in a window that has no activity. */
    private class OverlayOwner : SavedStateRegistryOwner {
        private val registry = LifecycleRegistry(this)
        private val saved = SavedStateRegistryController.create(this)
        override val lifecycle: Lifecycle get() = registry
        override val savedStateRegistry get() = saved.savedStateRegistry

        init {
            saved.performRestore(null)
            registry.currentState = Lifecycle.State.RESUMED
        }
    }

    enum class Mode { Activity, Overlay }

    companion object {
        /** In-process only: lost with the process. ponytail: Phase 4 persists grants in DataStore. */
        val grants = ConcurrentHashMap<String, Long>()

        @Volatile var connected = false
            private set

        /** Package the overlay Shield is covering, so repeated events don't stack overlays. */
        private var shownFor: String? = null

        fun grant(pkg: String, minutes: Int) {
            grants[pkg] = System.currentTimeMillis() + minutes * 60_000L
            Log.i(SpikeTag, "grant pkg=$pkg minutes=$minutes")
        }

        fun mode(context: Context): Mode =
            if (context.getSharedPreferences("spike", Context.MODE_PRIVATE).getString("mode", null) == "overlay") Mode.Overlay else Mode.Activity

        fun setMode(context: Context, mode: Mode) =
            context.getSharedPreferences("spike", Context.MODE_PRIVATE).edit { putString("mode", mode.name.lowercase()) }

        fun component(context: Context) = ComponentName(context, SpikeShieldService::class.java)

        /** Launchers, the keyboard and the default dialer on this device. */
        private fun runtimeExempt(context: Context): Set<String> {
            val pm = context.packageManager
            val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            @Suppress("DEPRECATION")
            val launchers = pm.queryIntentActivities(home, PackageManager.MATCH_DEFAULT_ONLY).map { it.activityInfo.packageName }
            val ime = Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)?.substringBefore('/')
            val dialer = context.getSystemService(TelecomManager::class.java)?.defaultDialerPackage
            return (launchers + listOfNotNull(ime, dialer, context.packageName)).toSet()
        }
    }
}
