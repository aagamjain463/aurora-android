package app.aurora

import app.aurora.protect.IntentPreset
import app.aurora.protect.Rules
import app.aurora.protect.ShieldPreset
import app.aurora.protect.UsageConfig
import org.junit.Assert.assertEquals
import org.junit.Test

class ReadyToStartTest {
    private val used = ShieldPreset("s1", "Social", listOf("com.a"), createdAt = 1)
    private val free = ShieldPreset("s2", "Games", listOf("com.b"), createdAt = 5)
    private val empty = ShieldPreset("s3", "Nothing yet", createdAt = 9)
    private val intent = IntentPreset("i1", "Video", listOf("com.c"), createdAt = 3)
    private val config = UsageConfig("c1", "Study", shieldId = "s1", createdAt = 2)

    @Test
    fun savedConfigurationsFirstThenPresetsNoConfigurationUses() {
        val ready = Rules(shields = listOf(used, free, empty), intentPresets = listOf(intent), configs = listOf(config)).readyToStart()
        assertEquals(listOf("c1", "preset:s2", "preset:i1"), ready.map { it.id })
        // A preset starts as a one-off copy, never saved as a configuration.
        assertEquals(free, ready[1].shield)
        assertEquals(false, ready[1].custom)
    }

    @Test
    fun atMostThree() {
        val many = (1..5).map { ShieldPreset("s$it", "S$it", listOf("com.$it"), createdAt = it.toLong()) }
        assertEquals(3, Rules(shields = many).readyToStart().size)
    }
}
