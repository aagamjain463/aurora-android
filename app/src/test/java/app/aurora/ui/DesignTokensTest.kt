package app.aurora.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import app.aurora.ui.components.material
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
            for (bg in listOf(c.background, c.surface)) assertTrue("Plus gold on $bg (dark=${c.isDark})", contrast(c.plus, bg) >= 4.5f)
            for (bg in listOf(c.background, c.surface)) for (fg in listOf(c.textPrimary, c.textSecondary, c.textTertiary, c.danger)) {
                assertTrue("$fg on $bg (dark=${c.isDark}) is ${contrast(fg, bg)}", contrast(fg, bg) >= 4.5f)
            }
        }
    }

    /** Gold lives inside the approved logo and on Aurora Plus alone. Every other UI colour except danger is neutral. */
    @Test
    fun paletteIsMonochrome() {
        val danger = setOf(Palette.SignalDark, Palette.SignalLight)
        Palette::class.java.declaredFields.filter { it.type == Long::class.javaPrimitiveType }.forEach { f ->
            f.isAccessible = true
            val color = Color(f.getLong(null).toULong())
            if (color in danger || f.name.startsWith("PlusGold")) return@forEach
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

    /**
     * Glass stays readable whatever scrolls beneath it. Over the page itself (background, card) every ink on a pane
     * must reach AA 4.5, with or without blur. The worst passing moment, the opposite-theme primary button fully
     * under a pane, must keep primary ink at 2.5 or better: it lasts only while that button scrolls past.
     */
    @Test
    fun glassIsReadable() {
        for (c in listOf(DarkColors, LightColors)) for (level in app.aurora.ui.components.Material.entries) {
            val spec = c.material(level)
            for (fill in listOf(spec.tint, spec.fallback)) {
                for (page in listOf(c.background, c.surface)) {
                    val pane = fill.compositeOver(page)
                    for (fg in listOf(c.textPrimary, c.textSecondary)) assertTrue("$level on page (dark=${c.isDark}) ${contrast(fg, pane)}", contrast(fg, pane) >= 4.5f)
                }
                val worst = fill.compositeOver(c.accent)
                assertTrue("$level primary over accent (dark=${c.isDark}) ${contrast(c.textPrimary, worst)}", contrast(c.textPrimary, worst) >= 2.5f)
            }
        }
    }

    @Suppress("unused") private val keep: AuroraColors = DarkColors
}
