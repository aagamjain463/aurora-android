package app.aurora.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import app.aurora.ui.theme.AuroraColors
import app.aurora.ui.theme.DarkColors
import app.aurora.ui.theme.LightColors
import app.aurora.ui.theme.Palette
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** brand/tokens/aurora.tokens.json is the design source of truth; the Kotlin palette must match it. */
class DesignTokensTest {
    private fun contrast(a: Color, b: Color): Float {
        val x = a.luminance(); val y = b.luminance()
        return (maxOf(x, y) + 0.05f) / (minOf(x, y) + 0.05f)
    }

    /** Text must stay AA-readable in both themes: the primary action, and every text tone on page and card. */
    @Test
    fun contrastIsReadable() {
        for (c in listOf(DarkColors, LightColors)) {
            assertTrue("primary action (dark=${c.isDark})", contrast(c.onAccent, c.accent) >= 4.5f)
            for (bg in listOf(c.background, c.surface)) for (fg in listOf(c.textPrimary, c.textSecondary, c.textTertiary, c.danger)) {
                assertTrue("$fg on $bg (dark=${c.isDark}) is ${contrast(fg, bg)}", contrast(fg, bg) >= 4.5f)
            }
        }
    }

    /** Gold lives only inside the approved logo. Every UI colour except danger is neutral: no hue to speak of. */
    @Test
    fun paletteIsMonochrome() {
        val danger = setOf(Palette.SignalDark, Palette.SignalLight)
        Palette::class.java.declaredFields.filter { it.type == Long::class.javaPrimitiveType }.forEach { f ->
            f.isAccessible = true
            val color = Color(f.getLong(null).toULong())
            if (color in danger) return@forEach
            val spread = maxOf(color.red, color.green, color.blue) - minOf(color.red, color.green, color.blue)
            assertTrue("${f.name} has a hue (spread $spread)", spread <= 0.04f)
        }
    }

    @Test
    fun paletteMatchesTokens() {
        val json = File("../brand/tokens/aurora.tokens.json").readText()
        val tokens = Regex(""""([a-z]+(?:-[a-z0-9]+)*)":\s*\{\s*"\${'$'}type":\s*"color",\s*"\${'$'}value":\s*"#([0-9A-Fa-f]{6})"""")
            .findAll(json).associate { it.groupValues[1] to it.groupValues[2].uppercase() }
        // Palette's Color vals compile to static long fields named after the property: Graphite2 -> graphite-2.
        val fields = Palette::class.java.declaredFields.filter { it.type == Long::class.javaPrimitiveType }
        var checked = 0
        for (f in fields) {
            val key = f.name.replace(Regex("(\\d+)$"), "-$1").replace(Regex("([a-z])([A-Z])"), "$1-$2").lowercase()
            val hex = tokens[key] ?: error("${f.name} ($key) has no token")
            f.isAccessible = true
            assertEquals(f.name, hex, "%06X".format(Color(f.getLong(null).toULong()).toArgb() and 0xFFFFFF))
            checked++
        }
        assertEquals(tokens.size, checked)
    }

    @Suppress("unused") private val keep: AuroraColors = DarkColors
}
