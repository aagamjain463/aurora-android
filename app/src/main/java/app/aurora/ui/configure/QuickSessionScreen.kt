package app.aurora.ui.configure

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.aurora.R
import app.aurora.SessionNotification
import app.aurora.activate
import app.aurora.alwaysBlockLeft
import app.aurora.auroraViewModel
import app.aurora.data.Access
import app.aurora.data.AccessState
import app.aurora.newId
import app.aurora.plan.Allowance
import app.aurora.protect.ActivationProblem
import app.aurora.protect.FrictionSettings
import app.aurora.protect.Mode
import app.aurora.protect.ShieldPreset
import app.aurora.protect.Timing
import app.aurora.protect.UsageConfig
import app.aurora.protect.WebPrefix
import app.aurora.protect.strict
import app.aurora.rememberAccess
import app.aurora.ui.components.AuroraButton
import app.aurora.ui.components.ButtonKind
import app.aurora.ui.components.Chip
import app.aurora.ui.components.DurationPicker
import app.aurora.ui.library.StepFlow
import app.aurora.ui.library.appChecklist
import app.aurora.ui.library.stepTitle
import app.aurora.ui.protect.LimitNoticeDialog
import app.aurora.ui.protect.ProtectModel
import app.aurora.ui.protect.ShieldGate
import app.aurora.ui.protect.modeDescription
import app.aurora.ui.protect.modeName
import app.aurora.ui.protect.shieldGateNeeded
import app.aurora.ui.theme.Aurora
import app.aurora.ui.theme.AuroraType
import app.aurora.ui.theme.Space

/**
 * A Normal Session on one screen: how long, how, which apps, Start. It opens on the person's latest
 * Shield, so a repeat takes one tap, and leaves nothing saved. "More options" is the full flow, for
 * Intent, Focus, starting later and repeating.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun QuickSessionScreen(onBack: () -> Unit, onDone: () -> Unit, onMore: () -> Unit, onStrict: () -> Unit, onPlus: () -> Unit, onOpenWeb: () -> Unit) {
    val vm = auroraViewModel { ProtectModel(it) }
    val data by vm.data.collectAsStateWithLifecycle()
    val apps by vm.apps.collectAsStateWithLifecycle()
    val exempt by vm.exempt.collectAsStateWithLifecycle()
    val d = data ?: return
    val colors = Aurora.colors
    val context = LocalContext.current
    val last = d.rules.shields.maxByOrNull { it.createdAt }
    var keys by rememberSaveable { mutableStateOf(last?.keys.orEmpty()) }
    var mode by rememberSaveable { mutableStateOf(last?.mode ?: d.rules.defaultRule.mode) }
    var minutes by rememberSaveable { mutableStateOf<Int?>(60) }
    var committed by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var problem by rememberSaveable { mutableStateOf<ActivationProblem?>(null) }

    // Free's Always Block minutes left today; a time that no longer fits has to be chosen again.
    val max = (if (mode == Mode.AlwaysBlock) vm.app.alwaysBlockLeft() else null) ?: (24 * 60)
    LaunchedEffect(max) { if ((minutes ?: 0) > max) minutes = null }
    val shieldOn = rememberAccess(Access.Accessibility) == AccessState.Granted
    val hasSites = keys.any { it.startsWith(WebPrefix) }
    val gate = shieldGateNeeded(hasSites)
    val cap = d.max(Allowance.ProtectedItems)
    // What the person already chose comes first, so it's seen without searching.
    val ordered = remember(apps) { apps?.sortedBy { it.packageName !in keys } }
    val notifications = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { }
    val name = stringResource(R.string.home_normal_title)

    StepFlow(
        1, 0, onBack,
        primary = stringResource(R.string.cfg_start),
        primaryEnabled = keys.isNotEmpty() && minutes != null && shieldOn && (!mode.strict || committed),
        onPrimary = {
            val m = minutes
            if (m != null) {
                val now = System.currentTimeMillis()
                val shield = ShieldPreset(
                    newId(), name, keys, mode,
                    friction = last?.friction ?: FrictionSettings(),
                    cooldownWaitMinutes = last?.takeIf { it.mode == Mode.Cooldown }?.cooldownWaitMinutes ?: 5,
                    createdAt = now,
                )
                vm.run {
                    problem = vm.app.activate(UsageConfig(newId(), name, custom = false, shield = shield, createdAt = now), Timing.Now(m))
                    if (problem == null) {
                        if (android.os.Build.VERSION.SDK_INT >= 33 && !SessionNotification.allowed(context)) {
                            runCatching { notifications.launch(android.Manifest.permission.POST_NOTIFICATIONS) }
                        }
                        onDone()
                    }
                }
            }
        },
        footer = {
            problem?.let { p ->
                Text(problemText(p), color = colors.danger, style = AuroraType.Secondary)
                if (p == ActivationProblem.OverItems || p == ActivationProblem.OverAlwaysBlockAllowance) {
                    AuroraButton(stringResource(R.string.plus_see), onPlus, Modifier.fillMaxWidth(), kind = ButtonKind.Quiet)
                }
            }
            if (keys.isEmpty()) Text(stringResource(R.string.quick_choose_apps), color = colors.textSecondary, style = AuroraType.Secondary)
            AuroraButton(stringResource(R.string.quick_more), onMore, Modifier.fillMaxWidth(), kind = ButtonKind.Quiet)
        },
    ) {
        stepTitle(R.string.home_normal_title, R.string.quick_body)
        if (gate) item(key = "gate") { ShieldGate(hasSites, onOpenWeb) }
        item(key = "time") {
            DurationPicker(minutes, { minutes = it }, listOf(15, 30, 60, 120, 360).filter { it <= max }, title = stringResource(R.string.cfg_how_long), max = max.coerceAtLeast(1), endsFromNow = true)
        }
        item(key = "mode") {
            Column(verticalArrangement = Arrangement.spacedBy(Space.S)) {
                Text(stringResource(R.string.quick_mode), color = colors.textPrimary, style = AuroraType.Title)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.XS), verticalArrangement = Arrangement.spacedBy(Space.XS)) {
                    Mode.entries.forEach { m -> Chip(modeName(m), mode == m) { mode = m } }
                }
                Text(modeDescription(mode), color = colors.textSecondary, style = AuroraType.Secondary)
            }
        }
        if (mode.strict) item(key = "commit") { CommitBlock(committed, { committed = it }, onStrict) }
        item(key = "apps") { Text(stringResource(R.string.quick_apps), Modifier.padding(top = Space.S).fillMaxWidth(), color = colors.textPrimary, style = AuroraType.Title) }
        appChecklist(ordered, exempt, keys.toSet(), query, { query = it }, cap) { pkg ->
            when {
                pkg in keys -> keys = keys - pkg
                cap != null && keys.size >= cap -> vm.atLimit(Allowance.ProtectedItems)
                else -> keys = keys + pkg
            }
        }
    }
    LimitNoticeDialog(vm, onPlus)
}
