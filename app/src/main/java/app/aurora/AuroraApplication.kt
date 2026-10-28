package app.aurora

import android.app.Application
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.aurora.data.Access
import app.aurora.data.AccessRepository
import app.aurora.data.AccessState
import app.aurora.data.AppCatalog
import app.aurora.data.PreferencesRepository
import app.aurora.data.ScreenTimeRepository
import app.aurora.data.accessState
import app.aurora.data.dataStore
import app.aurora.plan.Plan
import app.aurora.protect.ProtectionRepository

/** Long-lived dependencies. A handful of objects don't need a DI framework. */
class AuroraApplication : Application() {
    val preferences by lazy { PreferencesRepository(this) }
    val protection by lazy { ProtectionRepository(dataStore) }

    /**
     * The one source of the current plan (BUSINESS_MODEL.md 5.2). Always Free until Play Billing feeds
     * it in Phase 11. There is no way to switch it, in any build.
     */
    val plan: Plan = Plan.Free
    val catalog by lazy { AppCatalog(this) }
    val screenTime by lazy { ScreenTimeRepository(this) }
    val access by lazy { AccessRepository(this, screenTime) }
}

@Composable
inline fun <reified VM : ViewModel> auroraViewModel(crossinline create: (AuroraApplication) -> VM): VM {
    val app = LocalContext.current.applicationContext as AuroraApplication
    return viewModel<VM>(factory = viewModelFactory { initializer { create(app) } })
}

/** [access]'s state, re-checked every time the screen resumes (e.g. back from Settings). */
@Composable
fun rememberAccess(access: Access): AccessState {
    val app = LocalContext.current.applicationContext as AuroraApplication
    var state by remember { mutableStateOf(accessState(app.access.isGranted(access), previous = null)) }
    LifecycleResumeEffect(access) {
        state = accessState(app.access.isGranted(access), previous = state)
        onPauseOrDispose { }
    }
    return state
}
