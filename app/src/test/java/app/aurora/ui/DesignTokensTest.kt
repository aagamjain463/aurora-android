package app.aurora.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import app.aurora.ui.theme.Palette
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** brand/tokens/aurora.tokens.json is the design source of truth; the Kotlin palette must match it. */
class DesignTokensTest {
    @Test
    fun paletteMatchesTokens() {
        val json = File("../brand/tokens/aurora.tokens.json").readText()
        val tokens = Regex(""""([a-z]+(?:-\d+)?)":\s*\{\s*"\${'$'}type":\s*"color",\s*"\${'$'}value":\s*"#([0-9A-Fa-f]{6})"""")
            .findAll(json).associate { it.groupValues[1] to it.groupValues[2].uppercase() }
        // Palette's Color vals compile to static long fields named after the property.
        val fields = Palette::class.java.declaredFields.filter { it.type == Long::class.javaPrimitiveType }
        var checked = 0
        for (f in fields) {
            val key = f.name.replace(Regex("(\\d+)$"), "-$1").lowercase()
            val hex = tokens[key] ?: continue
            f.isAccessible = true
            val argb = Color(f.getLong(null).toULong()).toArgb()
            assertEquals(f.name, hex, "%06X".format(argb and 0xFFFFFF))
            checked++
        }
        assertTrue("only $checked palette colours matched a token", checked >= 20)
    }
}
