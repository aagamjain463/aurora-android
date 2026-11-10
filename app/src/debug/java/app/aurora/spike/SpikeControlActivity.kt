package app.aurora.spike

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.security.advancedprotection.AdvancedProtectionManager
import android.view.accessibility.AccessibilityManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.aurora.AuroraApplication
import app.aurora.plan.ProtectedItem
import app.aurora.ui.components.AuroraButton
import app.aurora.ui.components.ChoicePills
import app.aurora.ui.components.Notice
import app.aurora.ui.components.StatusPill
import app.aurora.ui.theme.Aurora
import app.aurora.ui.theme.AuroraTheme
import app.aurora.ui.theme.AuroraType
import app.aurora.ui.theme.Space

/**
 * G1 spike control panel: honest status, the disclosure before Accessibility settings, and the
 * Shield mechanism switch. Protected apps come from the real Protect tab.
 */
class SpikeControlActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(SystemBarStyle.dark(Color.TRANSPARENT), SystemBarStyle.dark(Color.TRANSPARENT))
        val app = application as AuroraApplication
        setContent {
            val protectedList by app.protection.list.collectAsStateWithLifecycle(initialValue = null)
            val protectedApps = protectedList?.items.orEmpty().filterIsInstance<ProtectedItem.App>()
            var tick by remember { mutableIntStateOf(0) }
            var mode by remember { mutableStateOf(SpikeShieldService.mode(this)) }
            LifecycleResumeEffect(Unit) { tick++; onPauseOrDispose { } }
            val probe = remember(tick) { probe(this) }
            val status = spikeStatus(protectedApps.isNotEmpty(), probe.enabledInSettings, SpikeShieldService.connected)

            AuroraTheme(reduceMotion = false) {
                val colors = Aurora.colors
                Column(
                    Modifier.fillMaxSize().background(colors.background).systemBarsPadding()
                        .verticalScroll(rememberScrollState()).padding(Space.Gutter),
                    verticalArrangement = Arrangement.spacedBy(Space.M),
                ) {
                    Text("Enforcement spike", color = colors.textPrimary, style = AuroraType.Display)
                    StatusPill(status.name, live = status == SpikeStatus.Active, warm = status != SpikeStatus.Active)
                    Text(
                        "Protected apps: ${protectedApps.size} (choose them on the Protect tab)\n" +
                            "Listed in Settings: ${probe.enabledInSettings}\n" +
                            "AccessibilityManager reports enabled: ${probe.enabledInManager}\n" +
                            "Service connected (this process): ${SpikeShieldService.connected}\n" +
                            "Advanced Protection: ${probe.advancedProtection}\n" +
                            "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
                        color = colors.textSecondary,
                        style = AuroraType.Secondary,
                    )
                    Notice(
                        eyebrow = "Before you turn it on",
                        body = "aurora uses an Accessibility service to see the name of the app in front, and nothing " +
                            "else: no screen content, no typing. It uses that only to pause you before apps you chose to " +
                            "protect. Nothing leaves your phone. You can turn it off any time in Settings › Accessibility.",
                    )
                    AuroraButton("Open Accessibility settings", {
                        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    }, Modifier.fillMaxWidth())
                    Text("Shield mechanism", color = colors.textTertiary, style = AuroraType.Eyebrow)
                    ChoicePills(SpikeShieldService.Mode.entries, mode, {
                        mode = it
                        SpikeShieldService.setMode(this@SpikeControlActivity, it)
                    }, label = { it.name }, fill = true)
                }
            }
        }
    }

    private class Probe(val enabledInSettings: Boolean, val enabledInManager: Boolean, val advancedProtection: String)

    private fun probe(context: Context): Probe {
        val component = SpikeShieldService.component(context)
        val setting = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        val manager = getSystemService(AccessibilityManager::class.java)
            .getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .any { it.resolveInfo.serviceInfo.let { s -> s.packageName == packageName && s.name == component.className } }
        val aapm = if (Build.VERSION.SDK_INT >= 36) {
            runCatching { getSystemService(AdvancedProtectionManager::class.java)?.isAdvancedProtectionEnabled.toString() }
                .getOrElse { "error: ${it.javaClass.simpleName}" }
        } else "n/a (API < 36)"
        return Probe(
            isListedInEnabledServices(setting, component.flattenToString(), component.flattenToShortString()),
            manager,
            aapm,
        )
    }
}
