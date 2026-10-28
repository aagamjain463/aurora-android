package app.aurora.protect

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import app.aurora.plan.Plan
import app.aurora.plan.ProtectedItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ProtectedItemsTest {
    @get:Rule val folder = TemporaryFolder()

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val store by lazy { PreferenceDataStoreFactory.create(scope = scope) { folder.newFile("aurora.preferences_pb").also { it.delete() } } }
    private val repo by lazy { ProtectionRepository(store) }

    @After fun close() = scope.cancel()

    private fun app(pkg: String) = ProtectedItem.App.of(pkg)!!
    private val five = (1..5).map { app("com.app$it") }

    // ---- encoding ----

    @Test
    fun encodingKeepsOrderAndKind() {
        val items = listOf(app("com.b"), ProtectedItem.Website.of("example.com")!!, app("com.a"))
        assertEquals(ProtectedList(items), decodeItems(encodeItems(items)))
        assertEquals("app:com.b\nweb:example.com\napp:com.a", encodeItems(items))
    }

    @Test
    fun decodingSkipsDuplicatesUnknownAndInvalidEntries() {
        val list = decodeItems("app:com.a\n\napp:com.a\nweb:not a domain\napp:no-dots\nzzz:future\napp:com.b")
        assertEquals(listOf(app("com.a"), app("com.b")), list.items)
        assertEquals(3, list.skipped)
        assertTrue(list.readable)
    }

    // ---- entitlement ----

    @Test
    fun freeKeepsTheOldestFiveActiveAndPausesTheRest() {
        val seven = ProtectedList(five + app("com.app6") + app("com.app7"))
        assertEquals(five, seven.active(Plan.Free))
        assertEquals(listOf(app("com.app6"), app("com.app7")), seven.paused(Plan.Free))
        assertEquals(seven.items, seven.active(Plan.Plus))
        assertTrue(seven.paused(Plan.Plus).isEmpty())
        // Only active apps are enforced.
        assertEquals(five.map { it.packageName }.toSet(), seven.toPolicyState(Plan.Free, emptySet())!!.protectedPackages)
    }

    @Test
    fun websitesShareTheFiveItemPool() {
        val mixed = ProtectedList(five.take(3) + ProtectedItem.Website.of("a.com")!! + ProtectedItem.Website.of("b.com")!! + app("com.late"))
        assertEquals(listOf(app("com.late")), mixed.paused(Plan.Free))
        assertEquals(setOf("com.app1", "com.app2", "com.app3"), mixed.toPolicyState(Plan.Free, emptySet())!!.protectedPackages)
    }

    // ---- repository ----

    @Test
    fun addAndRemovePersist() = runBlocking {
        assertEquals(AddResult.Added, repo.add(app("com.a"), Plan.Free))
        assertEquals(AddResult.AlreadyProtected, repo.add(app("com.a"), Plan.Free))
        assertEquals(AddResult.Added, repo.add(app("com.b"), Plan.Free))
        assertEquals(listOf(app("com.a"), app("com.b")), repo.list.first().items)
        repo.remove(listOf(app("com.a")), Plan.Free)
        assertEquals(listOf(app("com.b")), repo.list.first().items)
        // A second repository on the same store (a new process) reads the same list.
        assertEquals(listOf(app("com.b")), ProtectionRepository(store).list.first().items)
    }

    @Test
    fun freeRefusesTheSixthItemAndPlusDoesNot() = runBlocking {
        five.forEach { assertEquals(AddResult.Added, repo.add(it, Plan.Free)) }
        assertEquals(AddResult.AtLimit, repo.add(app("com.sixth"), Plan.Free))
        assertEquals(5, repo.list.first().items.size)
        assertEquals(AddResult.Added, repo.add(app("com.sixth"), Plan.Plus))
        // Back on Free (a downgrade): nothing is deleted, the newest pauses, and nothing new can be added.
        val list = repo.list.first()
        assertEquals(6, list.items.size)
        assertEquals(listOf(app("com.sixth")), list.paused(Plan.Free))
        assertEquals(AddResult.AtLimit, repo.add(app("com.seventh"), Plan.Free))
        // Removing an active item frees a slot, but the paused item stays paused until the person chooses it.
        repo.remove(listOf(app("com.app1")), Plan.Free)
        val afterRemove = repo.list.first()
        assertEquals(listOf(app("com.sixth")), afterRemove.paused(Plan.Free))
        assertEquals(4, afterRemove.active(Plan.Free).size)
        assertEquals(AddResult.Added, repo.resume(app("com.sixth"), Plan.Free))
        assertTrue(repo.list.first().paused(Plan.Free).isEmpty())
        // aurora plus enforces everything, held or not.
        assertEquals(5, repo.list.first().active(Plan.Plus).size)
    }

    @Test
    fun theFreedSlotGoesToTheAppThePersonChooses() = runBlocking {
        val seven = (1..7).map { app("com.old$it") }
        store.edit { it[stringSetPreferencesKey("protected_apps")] = seven.map { a -> a.packageName }.toSet() }
        repo.remove(listOf(app("com.old2")), Plan.Free)
        // Order is kept, nothing woke up, and both paused apps are saved as paused.
        val list = repo.list.first()
        assertEquals(seven - app("com.old2"), list.items)
        assertEquals(listOf(app("com.old6"), app("com.old7")), list.paused(Plan.Free))
        assertEquals("app:com.old1\napp:com.old3\napp:com.old4\napp:com.old5\npaused:app:com.old6\npaused:app:com.old7",
            store.data.first()[stringPreferencesKey("protected_items")])
        // The person picks the newer one.
        assertEquals(AddResult.Added, repo.resume(app("com.old7"), Plan.Free))
        assertEquals(listOf(app("com.old6")), repo.list.first().paused(Plan.Free))
        assertEquals(AddResult.AtLimit, repo.resume(app("com.old6"), Plan.Free))
        // A new app also takes a free slot only when one exists.
        assertEquals(AddResult.AtLimit, repo.add(app("com.new"), Plan.Free))
        // Removing a paused app frees nothing and wakes nothing.
        repo.remove(listOf(app("com.old6")), Plan.Free)
        assertEquals(5, repo.list.first().active(Plan.Free).size)
        assertTrue(repo.list.first().paused(Plan.Free).isEmpty())
    }

    @Test
    fun heldMarksSurviveEncodingAndUnknownLines() {
        val list = decodeItems("app:com.a\npaused:app:com.b\npaused:zzz:x\napp:com.c")
        assertEquals(listOf(app("com.a"), app("com.b"), app("com.c")), list.items)
        assertEquals(setOf(app("com.b")), list.held)
        assertEquals(1, list.skipped)
        assertEquals(list, decodeItems(encodeItems(list.items, list.held)).copy(skipped = 1))
    }

    @Test
    fun concurrentAddsNeverExceedTheLimit() = runBlocking {
        val results = (1..20).map { i -> async(Dispatchers.IO) { repo.add(app("com.race$i"), Plan.Free) } }.awaitAll()
        assertEquals(5, results.count { it == AddResult.Added })
        assertEquals(15, results.count { it == AddResult.AtLimit })
        assertEquals(5, repo.list.first().items.size)
    }

    @Test
    fun legacySetIsReadSortedAndMigratedOnFirstChange() = runBlocking {
        store.edit { it[stringSetPreferencesKey("protected_apps")] = setOf("com.zeta", "com.alpha", "bad", "com.mid") }
        val legacy = repo.list.first()
        assertEquals(listOf(app("com.alpha"), app("com.mid"), app("com.zeta")), legacy.items)
        assertEquals(1, legacy.skipped)

        repo.add(app("com.new"), Plan.Free)
        val prefs = store.data.first()
        assertNull(prefs[stringSetPreferencesKey("protected_apps")])
        assertEquals("app:com.alpha\napp:com.mid\napp:com.zeta\napp:com.new", prefs[stringPreferencesKey("protected_items")])
    }

    @Test
    fun legacySetLargerThanTheLimitIsKeptAndPaused() = runBlocking {
        store.edit { it[stringSetPreferencesKey("protected_apps")] = (1..7).map { "com.old$it" }.toSet() }
        val list = repo.list.first()
        assertEquals(7, list.items.size)
        assertEquals(listOf(app("com.old6"), app("com.old7")), list.paused(Plan.Free))
        assertEquals(AddResult.AtLimit, repo.add(app("com.new"), Plan.Free))
    }

    @Test
    fun corruptedStorageIsReportedNotTreatedAsEmpty() = runBlocking {
        val file = folder.newFile("broken.preferences_pb").apply { writeBytes(byteArrayOf(9, 9, 9, 9, 9)) }
        val broken = PreferenceDataStoreFactory.create(scope = scope) { file }
        val list = ProtectionRepository(broken).list.first()
        assertFalse(list.readable)
        assertNull(list.toPolicyState(Plan.Free, emptySet()))
    }
}
