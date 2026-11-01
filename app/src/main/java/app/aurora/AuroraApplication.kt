package app.aurora

import android.app.Application
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
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
import app.aurora.data.JsonValue
import app.aurora.data.PreferencesRepository
import app.aurora.data.ScreenTimeRepository
import app.aurora.data.accessState
import app.aurora.data.dataStore
import app.aurora.account.AccountApi
import app.aurora.account.AccountRepository
import app.aurora.account.AuthState
import app.aurora.account.FirebaseIdentity
import app.aurora.account.Identity
import app.aurora.account.ProfileState
import app.aurora.account.ProfileVault
import app.aurora.account.Transport
import app.aurora.account.bindProfile
import app.aurora.account.httpTransport
import app.aurora.plan.installId
import app.aurora.plan.BillingRepository
import app.aurora.plan.LimitDecision
import app.aurora.plan.SignedEntitlement
import app.aurora.plan.Plan
import app.aurora.progress.History
import app.aurora.protect.DeviceTime
import app.aurora.protect.PolicyState
import app.aurora.protect.ProtectedList
import app.aurora.protect.ProtectionRepository
import app.aurora.protect.Rules
import app.aurora.protect.Runtime
import app.aurora.protect.TrustedClock
import app.aurora.protect.policyState
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.sync.Mutex
import java.io.IOException
import java.time.ZoneId

/** Long-lived dependencies. A handful of objects don't need a DI framework. */
open class AuroraApplication : Application() {
    /** Lives as long as the process. Holds the in-memory copies the Shield reads on every event. */
    val scope = MainScope()

    val preferences by lazy { PreferencesRepository(this) }
    val protection by lazy { ProtectionRepository(dataStore) }

    /** The protected list, kept in memory for the Shield. Null until the first read. */
    val protectedList: StateFlow<ProtectedList?> by lazy { protection.list.stateIn(scope, SharingStarted.Eagerly, null) }

    /** What the person configured. Strict: if it can't be read, nothing overwrites it and Shield says so. */
    val rules by lazy { JsonValue(dataStore, "rules", Rules.serializer(), Rules(), scope, strict = true) }

    /** Sessions, grants, waits, Intent sessions and Focus. */
    val runtime by lazy { JsonValue(dataStore, "runtime", Runtime.serializer(), Runtime(), scope, strict = false) }

    /** Daily counts, Focus history. Local only. */
    val history by lazy { JsonValue(dataStore, "history", History.serializer(), History(), scope, strict = false) }

    val billing: BillingRepository by lazy {
        BillingRepository(
            this, scope, JsonValue(dataStore, "entitlement", SignedEntitlement.serializer(), SignedEntitlement(), scope, strict = false),
            publicKey = entitlementPublicKey,
            verify = { token, product, _, pkg -> account.verifyPurchase(token, product, pkg) },
            configured = accountServer != null && entitlementPublicKey.isNotBlank(),
            currentUid = { account.uid },
            identityResolved = { account.resolved },
            identityChanges = account.state,
        )
    }

    /** The authentication provider and the account service. Tests replace these three, and nothing else. */
    protected open fun createIdentity(): Identity = FirebaseIdentity(this, scope)
    protected open val accountServer: Transport? get() = BuildConfig.SERVER_URL.takeIf { it.isNotBlank() }?.let(::httpTransport)
    protected open val entitlementPublicKey: String get() = BuildConfig.ENTITLEMENT_PUBLIC_KEY

    val vault by lazy { ProfileVault(dataStore, java.io.File(noBackupFilesDir, "profiles")) }

    val account: AccountRepository by lazy {
        AccountRepository(
            createIdentity(), accountServer?.let(::AccountApi), installId(this), Build.MODEL.orEmpty().take(80), entitlementPublicKey, packageName,
            java.io.File(noBackupFilesDir, "pending_email"), storeEntitlement = { billing.store(it) },
        )
    }

    /** The one source of the current plan: what the verification server last signed (plan/Entitlement.kt). */
    val plan: Plan get() = billing.planNow()

    /**
     * Held by every operation that checks a plan limit and then stores what it allows, across rules and
     * runtime, so two of them can't each claim the last slot or minutes.
     * ponytail: one lock for all of them. These are rare, human-speed writes; split it if that ever shows.
     */
    val entitlements = Mutex()

    /** A plan limit stopped something. The app's root explains it once, with what to do next. */
    val limitPrompt = MutableStateFlow<LimitPrompt?>(null)

    /** Storage refused a write. The app's root shows it once; nothing changed. */
    val saveFailed = MutableStateFlow(false)

    /** Runs a change the person asked for, and tells them if it couldn't be stored. */
    suspend fun saving(block: suspend () -> Unit) {
        try {
            block()
        } catch (_: IOException) {
            saveFailed.value = true
        }
    }

    val catalog by lazy { AppCatalog(this) }
    val screenTime by lazy { ScreenTimeRepository(this) }
    val access by lazy { AccessRepository(this, screenTime) }

    private val boot by lazy { Settings.Global.getInt(contentResolver, Settings.Global.BOOT_COUNT, -1) }

    /** The wall clock is [TrustedClock]: during a strict commitment, changing the phone's time doesn't move it. */
    fun now() = DeviceTime(TrustedClock.wall(), SystemClock.elapsedRealtime(), boot)

    override fun onCreate() {
        super.onCreate()
        TrustedClock.init(this)
        // Start reading now, so the Shield has its configuration before the first event.
        protectedList
        rules
        runtime
        account
        billing.connect()
        SessionNotification.start(this)
        // Every sign-in binds this phone's data to that account before anything behind the gate can draw.
        scope.launch {
            protectedList.first { it != null }
            rules.state.first { it != null }
            runtime.state.first { it != null }
            history.state.first { it != null }
            billing.awaitLoaded()
            account.state.map { (it as? AuthState.SignedIn)?.user?.uid }.distinctUntilChanged().collectLatest { uid ->
                if (uid != null) runCatching { bindProfile(uid) }.onFailure { account.setProfile(ProfileState.Failed) }
            }
        }
        scope.launch {
            // Once everything is loaded: a Phase 3 Lockdown becomes Always Block, and a scheduled
            // Always Block window already running is committed.
            protectedList.first { it != null }
            rules.state.first { it != null }
            runtime.state.first { it != null }
            billing.awaitLoaded()
            account.state.first { it != AuthState.Loading }
            runCatching { migrateRuntime() }
            runCatching { commitScheduleWindows() }
            history.state.first { it != null }
            runCatching { history.update { it.withLateRunsCorrected() } }
            runCatching { migrateConfigurations() }
            runCatching { syncRuns() }
        }
    }

    override fun onTerminate() {
        super.onTerminate()
        scope.cancel()
    }

    /** The decision snapshot, from memory only. Null while anything is still loading or unreadable. */
    fun policySnapshot(exempt: Set<String>, usedToday: Map<String, Long>): PolicyState? {
        // Until the stored plan is read, a Plus person would look Free.
        if (!billing.loaded) return null
        val list = protectedList.value ?: return null
        val rules = rules.value ?: return null
        val runtime = runtime.value ?: return null
        return policyState(list, rules, runtime, plan, exempt, usedToday, now(), ZoneId.systemDefault())
    }
}

/** What the root dialog shows. [shorter] starts again with a length that fits. */
class LimitPrompt(val decision: LimitDecision, val shorter: ((Int) -> Unit)? = null)

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
