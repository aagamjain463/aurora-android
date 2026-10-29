package app.aurora.protect

import app.aurora.data.AuroraJson
import app.aurora.progress.History
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Saved data from the Phase 3 build reads back under the reinvention model, without loss. */
class MigrationTest {
    @Test
    fun phase3RulesReadBackWithSafeModes() {
        // The founder's own saved rules, from the phone (package names only).
        val raw = """{"apps":{"com.instagram.android":{"mode":"Block","waitSeconds":30,"intent":{"reason":"I want more time for deep work.","reflectionSeconds":30,"enabled":false}},"web:reddit.com":{"mode":"Cooldown"},"com.x":{"mode":"RulesOnly","cooldownMinutes":45}},"schedules":[{"id":"d48a","name":"Study hours","days":[1,2,3,4,5],"startMinute":540,"endMinute":1020}],"limits":[{"key":"com.instagram.android","minutes":10}],"motivations":["Deep work"]}"""
        val rules = AuroraJson.decodeFromString(Rules.serializer(), raw)
        assertEquals(Mode.Flexible, rules.rule("com.instagram.android").mode)
        assertEquals(Mode.Cooldown, rules.rule("web:reddit.com").mode)
        assertEquals(Mode.Flexible, rules.rule("com.x").mode)
        val note = rules.apps.getValue("com.instagram.android").intent!!
        assertEquals("I want more time for deep work.", note.reason)
        assertEquals(30, note.reflectionSeconds)
        assertEquals(IntentExpiry.Reminder, note.expiry)
        val s = rules.schedules.single()
        assertEquals(true, s.legacy)
        assertEquals(Mode.Flexible, s.mode)
        assertEquals(10, rules.limits.single().minutes)
    }

    @Test
    fun phase3RuntimeKeepsALockdownAndDropsOldGrants() {
        val raw = """{"grants":{"com.google.android.deskclock":1793223372779},"waits":{"com.google.android.deskclock":1793223374245},"lockdown":{"startWall":1793221637398,"startElapsed":34262203,"boot":3,"durationMs":900000},"ledger":[{"day":"2026-10-29","start":1793221637398,"end":1793222537398}]}"""
        val runtime = AuroraJson.decodeFromString(Runtime.serializer(), raw)
        assertEquals(900_000L, runtime.lockdown!!.durationMs)
        assertEquals(1, runtime.ledger.size)
        assertEquals(emptyMap<String, Grant>(), runtime.access)
        val migrated = runtime.migrated(Rules(), setOf("com.google.android.deskclock"))
        assertNull(migrated.lockdown)
        assertEquals(Mode.AlwaysBlock, migrated.sessions.single().mode)
    }

    @Test
    fun phase3HistoryKeepsLockdownMinutesAsAlwaysBlock() {
        val raw = """{"days":{"2026-10-29":{"focusSessions":1,"lockdownMinutes":15}},"lockdowns":[1793248625261]}"""
        val history = AuroraJson.decodeFromString(History.serializer(), raw)
        assertEquals(15, history.days.getValue("2026-10-29").alwaysBlockMinutes)
        assertEquals(listOf(1793248625261L), history.committed)
    }
}
