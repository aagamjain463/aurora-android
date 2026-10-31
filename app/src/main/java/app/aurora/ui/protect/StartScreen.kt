package app.aurora.ui.protect

import app.aurora.ui.components.auroraSurface
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.aurora.R
import app.aurora.StartResult
import app.aurora.alwaysBlockLeft
import app.aurora.auroraViewModel
import app.aurora.protect.Difficulty
import app.aurora.protect.FrictionSettings
import app.aurora.protect.FrictionTask
import app.aurora.protect.Mode
import app.aurora.protect.key
import app.aurora.startProtection
import app.aurora.ui.components.AuroraButton
import app.aurora.ui.components.AuroraIconButton
import app.aurora.ui.components.AuroraIcons
import app.aurora.ui.components.ButtonKind
import app.aurora.ui.components.ChoiceCard
import app.aurora.ui.components.Chip
import app.aurora.ui.components.DurationPicker
import app.aurora.ui.components.SelectionMark
import app.aurora.ui.components.minutesText
import app.aurora.ui.components.secondsText
import app.aurora.ui.theme.Aurora
import app.aurora.ui.theme.AuroraType
import app.aurora.ui.theme.Radius
import app.aurora.ui.theme.Space
import kotlinx.coroutines.launch

private enum class StartStep { Apps, Mode, Settings, Duration, Review }

/** Protection presets: the common answers to "How long should protection last?" Custom is always there. */
val ProtectionPresets = listOf(30, 60, 120, 240)
val CooldownWaitPresets = listOf(5, 15, 30, 60)
val ReflectionPauses = listOf(10, 20, 30, 60)

@Composable
fun difficultyName(d: Difficulty): String = stringResource(
    when (d) {
        Difficulty.Easy -> R.string.friction_easy
        Difficulty.Medium -> R.string.friction_medium
        Difficulty.Hard -> R.string.friction_hard
    },
)
