package app.aurora.data

import android.content.Context
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import java.io.IOException

private val Context.dataStore by preferencesDataStore(name = "aurora")

enum class ThemeMode { System, Light, Dark }

val FocusLengths = listOf(15, 25, 45, 60, 90)

/**
 * A focus session is stored as wall-clock start + duration, so it keeps time across process death
 * and reboots without a running service.
 */
data class FocusSession(val startedAt: Long, val durationMs: Long, val intention: String) {
    val endsAt: Long get() = startedAt + durationMs
    fun remaining(now: Long): Long = (endsAt - now).coerceIn(0L, durationMs)
    fun progress(now: Long): Float = if (durationMs <= 0L) 1f else 1f - remaining(now).toFloat() / durationMs
    fun isComplete(now: Long): Boolean = now >= endsAt
}

data class UserPreferences(
    val onboarded: Boolean = false,
    val themeMode: ThemeMode = ThemeMode.System,
    val reduceMotion: Boolean = false,
    val defaultFocusMinutes: Int = 25,
    /** Apps the person has chosen to protect. Saved now; enforced once blocking ships. */
    val protectedApps: Set<String> = emptySet(),
    val session: FocusSession? = null,
)

/** Everything aurora remembers lives here, on the device. */
class PreferencesRepository(context: Context) {
    private val store = context.applicationContext.dataStore

    val preferences: Flow<UserPreferences> = store.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { it.toUserPreferences() }

    suspend fun setOnboarded(value: Boolean) = edit { it[Onboarded] = value }
    suspend fun setThemeMode(mode: ThemeMode) = edit { it[Theme] = mode.name }
    suspend fun setReduceMotion(value: Boolean) = edit { it[ReduceMotion] = value }
    suspend fun setDefaultFocusMinutes(minutes: Int) = edit { it[FocusMinutes] = minutes }

    suspend fun toggleProtected(packageName: String) = edit {
        val current = it[Protected].orEmpty()
        it[Protected] = if (packageName in current) current - packageName else current + packageName
    }

    suspend fun startSession(minutes: Int, intention: String, now: Long = System.currentTimeMillis()) = edit {
        it[SessionStart] = now
        it[SessionDuration] = minutes * 60_000L
        it[SessionIntention] = intention.trim()
    }

    suspend fun clearSession() = edit {
        it.remove(SessionStart)
        it.remove(SessionDuration)
        it.remove(SessionIntention)
    }

    private suspend fun edit(block: (MutablePreferences) -> Unit) {
        store.edit { block(it) }
    }

    private companion object {
        val Onboarded = booleanPreferencesKey("onboarded")
        val Theme = stringPreferencesKey("theme")
        val ReduceMotion = booleanPreferencesKey("reduce_motion")
        val FocusMinutes = intPreferencesKey("focus_minutes")
        val Protected = stringSetPreferencesKey("protected_apps")
        val SessionStart = longPreferencesKey("session_start")
        val SessionDuration = longPreferencesKey("session_duration")
        val SessionIntention = stringPreferencesKey("session_intention")

        fun Preferences.toUserPreferences(): UserPreferences {
            val start = this[SessionStart]
            val duration = this[SessionDuration]
            return UserPreferences(
                onboarded = this[Onboarded] ?: false,
                themeMode = ThemeMode.entries.find { it.name == this[Theme] } ?: ThemeMode.System,
                reduceMotion = this[ReduceMotion] ?: false,
                defaultFocusMinutes = this[FocusMinutes] ?: 25,
                protectedApps = this[Protected].orEmpty(),
                session = if (start != null && duration != null) {
                    FocusSession(start, duration, this[SessionIntention].orEmpty())
                } else null,
            )
        }
    }
}

/** Wall-clock ticks while collected. Screens only collect while visible, so this never runs in the background. */
fun clockTicks(periodMs: Long = 1_000): Flow<Long> = flow {
    while (true) {
        emit(System.currentTimeMillis())
        delay(periodMs)
    }
}
