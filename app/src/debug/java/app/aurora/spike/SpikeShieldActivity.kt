package app.aurora.spike

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
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import app.aurora.AuroraApplication
import app.aurora.data.InstalledApp
import app.aurora.ui.components.AppIcon
import app.aurora.ui.components.AuroraButton
import app.aurora.ui.components.AuroraTextField
import app.aurora.ui.components.ButtonKind
import app.aurora.ui.components.ChoicePills
import app.aurora.ui.theme.Aurora
import app.aurora.ui.theme.AuroraTheme
import app.aurora.ui.theme.AuroraType
import app.aurora.ui.theme.Space

/** G1 spike: the Shield as a full-screen activity in its own task, started by [SpikeShieldService]. */
class SpikeShieldActivity : ComponentActivity() {
    private var target by mutableStateOf<Pair<String, Long>?>(null)
    /** Open G1 trial, and the uptime its event reached the service. Main thread only. */
    private var trial = 0L
    private var trialReceived = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The Shield is always the dark field, so system bar icons are always light.
        enableEdgeToEdge(SystemBarStyle.dark(Color.TRANSPARENT), SystemBarStyle.dark(Color.TRANSPARENT))
        // G1: the trial ends with the first frame that started after the event reached the service. Its end
        // (intended vsync + total duration) is when the frame was handed to the compositor; the display
        // shows it at the next refresh, which the harness doesn't see.
        window.addOnFrameMetricsAvailableListener({ _, metrics, _ ->
            val vsync = metrics.getMetric(FrameMetrics.INTENDED_VSYNC_TIMESTAMP)
            if (trial != 0L && vsync >= trialReceived * 1_000_000) {
                G1.shown(this, trial, vsync + metrics.getMetric(FrameMetrics.TOTAL_DURATION))
                trial = 0L
            }
        }, Handler(Looper.getMainLooper()))
        read(intent)
        // Back never reveals the protected app: it closes it.
        onBackPressedDispatcher.addCallback(this) { closeApp() }
        setContent {
            val (pkg, uptime) = target ?: return@setContent
            // Each new Shield starts at the pause step, even when this singleTask activity is reused.
            key(pkg, uptime) {
                SpikeShieldContent(pkg, uptime, "activity", onClose = ::closeApp, onOpen = { minutes ->
                    SpikeShieldService.grant(pkg, minutes)
                    finish() // Our task goes away; the protected app's task underneath comes back.
                })
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        read(intent)
    }

    private fun read(intent: Intent) {
        val pkg = intent.getStringExtra(ExtraPackage) ?: return finish()
        intent.getLongExtra(ExtraTrial, 0L).takeIf { it != 0L }?.let {
            trial = it
            trialReceived = intent.getLongExtra(ExtraReceivedUptime, 0L)
        }
        // A repeat event from the same opening keeps the person's place in the flow.
        if (!intent.getBooleanExtra(ExtraFresh, true) && target?.first == pkg) return
        target = pkg to intent.getLongExtra(ExtraEventUptime, SystemClock.uptimeMillis())
    }

    private fun closeApp() {
        startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        finish()
    }

    companion object {
        const val ExtraPackage = "pkg"
        const val ExtraEventUptime = "event_uptime"
        const val ExtraFresh = "fresh"
        const val ExtraReceivedUptime = "received_uptime"
        const val ExtraTrial = "g1_trial"
    }
}

private enum class Step { Pause, Reflect }

private val Reasons = listOf("A message", "Something specific", "Just browsing")

/**
 * Shared by both mechanisms. Brand guardrails: dark field, dim neutral glow, one Primary ("Close app"),
 * one Quiet way through. The Reflect step is the minimum Intent flow: a reason, then a timed opening.
 */
@Composable
fun SpikeShieldContent(
    packageName: String,
    eventUptime: Long,
    mechanism: String,
    onClose: () -> Unit,
    onOpen: (minutes: Int) -> Unit,
) {
    val context = LocalContext.current
    val catalog = remember { (context.applicationContext as AuroraApplication).catalog }
    val app by produceState<InstalledApp?>(null, packageName) { value = catalog.app(packageName) }
    var step by rememberSaveable { mutableStateOf(Step.Pause) }
    var reason by rememberSaveable { mutableStateOf<String?>(null) }
    var words by rememberSaveable { mutableStateOf("") }

    LaunchedEffect(packageName, eventUptime) {
        withFrameNanos { }
        Log.i(SpikeTag, "shield-latency mechanism=$mechanism pkg=$packageName ms=${SystemClock.uptimeMillis() - eventUptime}")
    }

    AuroraTheme(reduceMotion = false) {
        val colors = Aurora.colors
        val label = app?.label ?: packageName
        Box(Modifier.fillMaxSize().background(colors.background)) {
            Column(
                Modifier
                    .fillMaxSize()
                    .systemBarsPadding()
                    .imePadding()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = Space.Gutter, vertical = Space.L),
                verticalArrangement = Arrangement.spacedBy(Space.M),
            ) {
                Text("debug spike · $mechanism · not the real Shield", color = colors.textTertiary, style = AuroraType.Eyebrow)
                Spacer(Modifier.height(Space.XL))
                app?.let { AppIcon(it) }
                when (step) {
                    Step.Pause -> {
                        Text("A pause before $label", Modifier.semantics { heading() }, color = colors.textPrimary, style = AuroraType.Headline)
                        Text("You chose to protect this app. Take a breath first.", color = colors.textSecondary, style = AuroraType.Body)
                        Spacer(Modifier.height(Space.XL))
                        AuroraButton("Close app", onClose, Modifier.fillMaxWidth())
                        AuroraButton("Open with intent", { step = Step.Reflect }, Modifier.fillMaxWidth(), kind = ButtonKind.Quiet)
                    }
                    Step.Reflect -> {
                        Text("What are you opening $label for?", Modifier.semantics { heading() }, color = colors.textPrimary, style = AuroraType.Headline)
                        ChoicePills(Reasons, reason, { reason = it }, label = { it.orEmpty() })
                        AuroraTextField(words, { words = it }, "Or in your words", capitalization = KeyboardCapitalization.Sentences)
                        Spacer(Modifier.height(Space.L))
                        AuroraButton("Open for 5 minutes", { onOpen(5) }, Modifier.fillMaxWidth(), enabled = reason != null || words.isNotBlank())
                        AuroraButton("Close app", onClose, Modifier.fillMaxWidth(), kind = ButtonKind.Quiet)
                    }
                }
            }
        }
    }
}
