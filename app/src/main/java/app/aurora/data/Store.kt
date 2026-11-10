package app.aurora.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.IOException

/** Newer versions may add fields; older ones ignore them instead of failing. */
val AuroraJson = Json {
    ignoreUnknownKeys = true
    coerceInputValues = true
}

/** A value and whether it could be read. An unreadable value holds the default. */
data class Loaded<T>(val value: T, val readable: Boolean = true)

/**
 * One JSON value in aurora's DataStore file, kept in memory. [state] is null until the first read and is
 * updated as soon as a write is stored, so a reader never sees an older value after [update] returns
 * (the Shield service reads a grant the moment the Shield asks for it).
 *
 * A [strict] value that can't be read stays unreadable and refuses writes, so nothing overwrites the
 * person's configuration; the Shield then shields nothing and says so. Other values (runtime, history)
 * start over from the default.
 */
class JsonValue<T>(
    private val store: DataStore<Preferences>,
    name: String,
    private val serializer: KSerializer<T>,
    private val default: T,
    scope: CoroutineScope,
    private val strict: Boolean,
) {
    private val key = stringPreferencesKey(name)
    private val mutex = Mutex()
    private val _state = MutableStateFlow<Loaded<T>?>(null)
    val state: StateFlow<Loaded<T>?> = _state

    /** The current value, or null while loading or when a strict value can't be read. */
    val value: T? get() = _state.value?.takeIf { it.readable }?.value

    init {
        scope.launch(Dispatchers.IO) {
            val loaded = try {
                decode(store.data.first()[key])
            } catch (_: IOException) {
                Loaded(default, readable = !strict)
            }
            _state.compareAndSet(null, loaded)
        }
    }

    /** Applies [transform] in one transaction and returns the stored value. Throws IOException if it can't be stored. */
    suspend fun update(transform: (T) -> T): T = withContext(Dispatchers.IO) { mutex.withLock {
        var next = default
        store.edit { prefs ->
            val current = decode(prefs[key])
            if (!current.readable) throw IOException("Stored $key can't be read")
            next = transform(current.value)
            prefs[key] = AuroraJson.encodeToString(serializer, next)
        }
        _state.value = Loaded(next)
        next
    } }

    /** Replaces the value with the default (Settings › Delete data). Works even when the stored value is unreadable. */
    suspend fun reset() = withContext(Dispatchers.IO) { mutex.withLock {
        store.edit { it.remove(key) }
        _state.value = Loaded(default)
    } }

    /** Reads again after the stored data was replaced as a whole (a different account signed in). */
    suspend fun reload() = withContext(Dispatchers.IO) { mutex.withLock {
        _state.value = try {
            decode(store.data.first()[key])
        } catch (_: IOException) {
            Loaded(default, readable = !strict)
        }
    } }

    private fun decode(raw: String?): Loaded<T> = when (raw) {
        null -> Loaded(default)
        else -> try {
            Loaded(AuroraJson.decodeFromString(serializer, raw))
        } catch (_: SerializationException) {
            Loaded(default, readable = !strict)
        } catch (_: IllegalArgumentException) {
            Loaded(default, readable = !strict)
        }
    }
}
