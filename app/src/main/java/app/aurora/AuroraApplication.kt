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
import app.aurora.data.AppCatalog
import app.aurora.data.PreferencesRepository
import app.aurora.data.ScreenTimeRepository

/** Long-lived dependencies. Three objects don't need a DI framework. */
class AuroraApplication : Application() {
    val preferences by lazy { PreferencesRepository(this) }
    val catalog by lazy { AppCatalog(this) }
    val screenTime by lazy { ScreenTimeRepository(this) }
}

@Composable
inline fun <reified VM : ViewModel> auroraViewModel(crossinline create: (AuroraApplication) -> VM): VM {
    val app = LocalContext.current.applicationContext as AuroraApplication
    return viewModel<VM>(factory = viewModelFactory { initializer { create(app) } })
}

/** Whether Usage access is granted, re-checked every time the screen resumes (e.g. back from Settings). */
@Composable
fun rememberUsageAccess(): Boolean {
    val app = LocalContext.current.applicationContext as AuroraApplication
    var granted by remember { mutableStateOf(app.screenTime.hasAccess()) }
    LifecycleResumeEffect(Unit) {
        granted = app.screenTime.hasAccess()
        onPauseOrDispose { }
    }
    return granted
}
