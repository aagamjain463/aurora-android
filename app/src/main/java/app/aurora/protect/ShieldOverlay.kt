package app.aurora.protect

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.PixelFormat
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.view.KeyEvent
import android.view.WindowManager
import android.widget.FrameLayout
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.OnBackPressedDispatcherOwner
import androidx.activity.setViewTreeOnBackPressedDispatcherOwner
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner

/**
 * The Shield as an accessibility overlay window (TYPE_ACCESSIBILITY_OVERLAY), added by the bound
 * [ShieldService] over the protected app.
 *
 * Why not an activity: Android lets an accessibility service start activities from the background, but
 * device makers add their own gate. On Xiaomi HyperOS the "Display pop-up windows while running in the
 * background" permission (MIUIOP 10021) is off by default, and startActivity() then fails silently: no
 * exception, no Shield, the protected app stays usable. That was the founder's phone. An accessibility
 * overlay needs no extra permission and no background-start exemption; only the service that is bound
 * can add one, and it disappears with it.
 *
 * The protected app stays underneath. While the Shield shows, aurora holds transient audio focus, so a
 * video or reel pauses instead of playing behind it. When access is granted the overlay simply goes, and
 * the app is right there: nothing has to be launched from the background.
 */
class OverlayShieldPresenter(
    private val service: AccessibilityService,
    private val content: @Composable (request: ShieldRequest, close: (home: Boolean) -> Unit) -> Unit,
) : ShieldPresenter {
    private val windows = service.getSystemService(WindowManager::class.java)
    private val audio = service.getSystemService(AudioManager::class.java)
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION).build())
            .build()

    private var host: Host? = null
    private val request = mutableStateOf<ShieldRequest?>(null)

    /** Called when the person closed the Shield: [home] true for the home screen, false for access granted. */
    var onClosed: (home: Boolean) -> Unit = {}

    /** True while the overlay window is attached: the Shield is really on screen, not just requested. */
    val showing: Boolean get() = host?.view?.isAttachedToWindow == true

    override fun present(request: ShieldRequest): Boolean {
        this.request.value = request
        if (host != null) {
            if (request.fresh) timeFirstFrame(host!!, request)
            return true
        }
        val h = Host(service) { close(home = true) }
        h.view.addView(ComposeView(service).apply {
            setContent { this@OverlayShieldPresenter.request.value?.let { content(it, ::close) } }
        })
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED or
                WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
            title = "Aurora Shield"
        }
        return try {
            windows.addView(h.view, params)
            h.start()
            host = h
            audio.requestAudioFocus(focus)
            if (request.fresh) timeFirstFrame(h, request)
            true
        } catch (_: RuntimeException) {
            // BadTokenException once the service is unbound, or a refused window: nothing is showing.
            false
        }
    }

    override fun dismiss() {
        val h = host ?: return
        host = null
        request.value = null
        h.stop()
        try {
            windows.removeViewImmediate(h.view)
        } catch (_: RuntimeException) {
            // Already gone with the service.
        }
        audio.abandonAudioFocusRequest(focus)
    }

    /** Records when the Shield's next frame is drawn: the end of "app opened → Shield on screen". */
    private fun timeFirstFrame(h: Host, request: ShieldRequest) {
        h.view.viewTreeObserver.addOnDrawListener(object : android.view.ViewTreeObserver.OnDrawListener {
            var done = false
            override fun onDraw() {
                if (done) return
                done = true
                ShieldService.trace.shown(request, android.os.SystemClock.uptimeMillis())
                h.view.post { if (h.view.viewTreeObserver.isAlive) h.view.viewTreeObserver.removeOnDrawListener(this) }
            }
        })
        h.view.invalidate()
    }

    /** The person closed the Shield: to the home screen, or into the app they were just granted. */
    private fun close(home: Boolean) {
        if (home) service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
        dismiss()
        onClosed(home)
    }

    /** One overlay window: its view and the owners Compose expects from an activity. */
    private class Host(context: Context, private val onBack: () -> Unit) :
        LifecycleOwner, SavedStateRegistryOwner, ViewModelStoreOwner, OnBackPressedDispatcherOwner {
        private val registry = LifecycleRegistry(this)
        private val saved = SavedStateRegistryController.create(this)
        override val lifecycle: Lifecycle get() = registry
        override val savedStateRegistry: SavedStateRegistry get() = saved.savedStateRegistry
        override val viewModelStore = ViewModelStore()
        override val onBackPressedDispatcher = OnBackPressedDispatcher { onBack() }
        private var backCallback: Any? = null

        val view: FrameLayout = object : FrameLayout(context) {
            override fun dispatchKeyEvent(event: KeyEvent): Boolean {
                if (event.keyCode == KeyEvent.KEYCODE_BACK) {
                    if (event.action == KeyEvent.ACTION_UP) onBackPressedDispatcher.onBackPressed()
                    return true
                }
                return super.dispatchKeyEvent(event)
            }
        }.apply {
            isFocusableInTouchMode = true
            setViewTreeLifecycleOwner(this@Host)
            setViewTreeSavedStateRegistryOwner(this@Host)
            setViewTreeViewModelStoreOwner(this@Host)
            setViewTreeOnBackPressedDispatcherOwner(this@Host)
        }

        init {
            saved.performAttach()
            saved.performRestore(null)
        }

        fun start() {
            registry.currentState = Lifecycle.State.RESUMED
            view.requestFocus()
            // With predictive back on (enableOnBackInvokedCallback), back reaches this window's dispatcher,
            // not dispatchKeyEvent.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val callback = OnBackInvokedCallback { onBackPressedDispatcher.onBackPressed() }
                view.findOnBackInvokedDispatcher()?.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, callback)
                backCallback = callback
            }
        }

        fun stop() {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                (backCallback as? OnBackInvokedCallback)?.let { view.findOnBackInvokedDispatcher()?.unregisterOnBackInvokedCallback(it) }
            }
            registry.currentState = Lifecycle.State.DESTROYED
            viewModelStore.clear()
        }
    }
}
