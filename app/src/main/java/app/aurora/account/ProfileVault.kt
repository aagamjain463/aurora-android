package app.aurora.account

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import app.aurora.data.AuroraJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/**
 * Which account the data on this phone belongs to, and each other account's data, set aside.
 *
 * Aurora's one DataStore file holds the active account's data, because the Shield reads it even with nobody
 * signed in: signing out changes nothing here, so a commitment keeps running. Only when a different account
 * signs in is the current data parked in its own private file and that account's data put in its place, in one
 * transaction. Device-wide settings (theme, motion) stay where they are.
 */
class ProfileVault(private val store: DataStore<Preferences>, private val dir: File) {
    private val owner = stringPreferencesKey("account_owner")
    private val declined = stringPreferencesKey("account_declined")

    private val strings = listOf("rules", "runtime", "history", "entitlement", "protected_items", "session_intention", "ledger_import_pending").map(::stringPreferencesKey)
    private val ints = listOf("onboarding_step").map(::intPreferencesKey)
    private val longs = listOf("session_start", "session_duration").map(::longPreferencesKey)
    private val booleans = listOf("onboarded").map(::booleanPreferencesKey)
    private val runtimeKey = stringPreferencesKey("runtime")
    private val importKey = stringPreferencesKey("ledger_import_pending")

    @Serializable
    private data class Archive(
        val strings: Map<String, String> = emptyMap(),
        val ints: Map<String, Int> = emptyMap(),
        val longs: Map<String, Long> = emptyMap(),
        val booleans: Map<String, Boolean> = emptyMap(),
    )

    suspend fun owner(): String? = store.data.first()[owner]

    /** Anything a person made here, or their having finished setting up. */
    suspend fun hasLocalData(): Boolean {
        val p = store.data.first()
        if (p[booleanPreferencesKey("onboarded")] == true) return true
        val hasOther = strings.any { it.name != "entitlement" && it.name != "runtime" && hasContent(p[it]) }
        if (hasOther) return true
        val rtRaw = p[runtimeKey]
        if (!hasContent(rtRaw)) return false
        val rt = runCatching { AuroraJson.decodeFromString(app.aurora.protect.Runtime.serializer(), rtRaw!!) }.getOrNull() ?: return true
        return rt.runs.any { !it.carried } || rt.sessions.isNotEmpty() || rt.ledger.isNotEmpty()
    }

    private fun hasContent(raw: String?) = !raw.isNullOrBlank() && raw != "{}" && raw != "[]"

    private fun fileOf(name: String): File = File(dir, MessageDigest.getInstance("SHA-256").digest(name.toByteArray()).joinToString("") { "%02x".format(it) } + ".json")

    private val unclaimed = "unclaimed"

    fun hasArchive(uid: String): Boolean = fileOf(uid).exists()
    fun hasUnclaimed(): Boolean = fileOf(unclaimed).exists()

    suspend fun hasDeclined(uid: String): Boolean = uid in store.data.first()[declined].orEmpty().split(' ')

    /** This account keeps what is here, as it is. [ledgerSince]: committed time before this joins the account's ledger at the server. */
    suspend fun claim(uid: String, ledgerSince: Long? = null) {
        store.edit {
            it[owner] = uid
            if (ledgerSince != null) it[importKey] = ledgerSince.toString()
        }
    }

    /** When the earlier committed time still has to reach the server, if it does. */
    suspend fun ledgerImportPending(): Long? = store.data.first()[importKey]?.toLongOrNull()

    suspend fun ledgerImported() {
        store.edit { it.remove(importKey) }
    }

    suspend fun rememberDeclined(uid: String) {
        store.edit { it[declined] = (it[declined].orEmpty().split(' ').filter { x -> x.isNotEmpty() } + uid).distinct().joinToString(" ") }
    }

    /**
     * Parks what is active under its owner, or as unclaimed if nobody owns it, and brings in [uid]'s data, or
     * [fromUnclaimed] the data set aside before accounts existed. [carry] gives the new runtime its commitments.
     * The park is written before anything changes, and the change is one transaction, so a crash loses nothing.
     */
    suspend fun switchTo(uid: String, fromUnclaimed: Boolean = false, carry: (activeRuntime: String?, incomingRuntime: String?) -> String?) = withContext(Dispatchers.IO) {
        val current = store.data.first()
        val currentOwner = current[owner]
        val parked = fileOf(currentOwner ?: unclaimed)
        if (currentOwner != null || !parked.exists()) {
            write(parked, current.toArchive())
        }
        val source = fileOf(if (fromUnclaimed) unclaimed else uid)
        // Parking just wrote the unclaimed file when nobody owned this data, and the person chose to bring it back.
        val incoming = if (source == parked && (currentOwner != null || !parked.exists())) current.toArchive() else read(source)
        store.edit { p ->
            strings.forEach { p.remove(it) }
            ints.forEach { p.remove(it) }
            longs.forEach { p.remove(it) }
            booleans.forEach { p.remove(it) }
            incoming.strings.forEach { (k, v) -> p[stringPreferencesKey(k)] = v }
            incoming.ints.forEach { (k, v) -> p[intPreferencesKey(k)] = v }
            incoming.longs.forEach { (k, v) -> p[longPreferencesKey(k)] = v }
            incoming.booleans.forEach { (k, v) -> p[booleanPreferencesKey(k)] = v }
            carry(current[runtimeKey], incoming.strings["runtime"])?.let { p[runtimeKey] = it }
            p[owner] = uid
        }
        if (source != parked) source.delete()
    }

    /** Forgets [uid]'s parked data (their account is being deleted), and that this phone's data was theirs. */
    suspend fun forget(uid: String) {
        fileOf(uid).delete()
        store.edit {
            if (it[owner] == uid) it.remove(owner)
            it.remove(importKey)
        }
    }

    private fun Preferences.toArchive() = Archive(
        strings.mapNotNull { k -> this[k]?.let { k.name to it } }.toMap(),
        ints.mapNotNull { k -> this[k]?.let { k.name to it } }.toMap(),
        longs.mapNotNull { k -> this[k]?.let { k.name to it } }.toMap(),
        booleans.mapNotNull { k -> this[k]?.let { k.name to it } }.toMap(),
    )

    private fun write(file: File, archive: Archive) {
        dir.mkdirs()
        val temp = File(dir, file.name + ".tmp")
        temp.writeText(AuroraJson.encodeToString(Archive.serializer(), archive))
        if (!temp.renameTo(file)) throw IOException("Can't store ${file.name}")
    }

    private fun read(file: File): Archive =
        if (!file.exists()) Archive() else AuroraJson.decodeFromString(Archive.serializer(), file.readText())
}
