package app.aurora.protect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfigMigrationTest {
    private val old = Rules(
        apps = mapOf(
            "com.a" to AppRule(Mode.Friction, intent = IntentConfig(reason = "study", enabled = true)),
            "com.b" to AppRule(Mode.Flexible, intent = IntentConfig(enabled = false)),
            "com.c" to AppRule(Mode.Flexible),
        ),
        groups = listOf(Group("g", "Social", listOf("com.a", "com.b"))),
        schedules = listOf(Schedule("s1", "Nights", setOf(1, 2), 22 * 60, 7 * 60, keys = listOf("com.c"), mode = Mode.AlwaysBlock, enabled = false)),
    )

    @Test
    fun everythingBecomesPresetsAndNothingNewIsEnforced() {
        val m = old.migratedToConfigurations(listOf("com.a", "com.b", "com.c"), 100)
        assertEquals(1, m.version)
        assertTrue(m.schedules.isEmpty())
        // Saved preferences keep their modes, as saved presets with no activation.
        assertEquals(setOf(listOf("com.a"), listOf("com.b", "com.c")), m.shields.filter { it.id.startsWith("migrated-items") }.map { it.keys }.toSet())
        assertTrue(m.shields.any { it.name == "Social" && it.keys == listOf("com.a", "com.b") })
        // The schedule keeps its id, days, times, mode and off state.
        val a = m.activations.single { it.id == "s1" }
        assertEquals(Timing.Recurring(setOf(1, 2), 22 * 60, 9 * 60), a.timing)
        assertEquals(false, a.enabled)
        assertEquals(Mode.AlwaysBlock, m.shields.single { it.id == "migrated-schedule-s1" }.mode)
        // Intent stays on only for the app it was on for.
        val always = m.activations.filter { it.timing == Timing.Always }
        assertEquals(1, always.size)
        val config = m.configs.single { it.id == always.single().configId }
        assertEquals(listOf("com.a"), m.intentPresets.single { it.id == config.intentId }.keys)
        assertEquals(old.apps, m.apps)
    }

    @Test
    fun idempotent() {
        val once = old.migratedToConfigurations(listOf("com.a"), 1)
        assertEquals(once, once.migratedToConfigurations(listOf("com.a"), 2))
    }
}
