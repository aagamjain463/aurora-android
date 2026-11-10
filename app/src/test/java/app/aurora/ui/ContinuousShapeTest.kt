package app.aurora.ui

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import app.aurora.ui.theme.ContinuousShape
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ContinuousShapeTest {
    private val density = Density(1f)

    @Test fun continuousCornerFillsItsBoxExactly() {
        val outline = ContinuousShape(20.dp).createOutline(Size(300f, 120f), LayoutDirection.Ltr, density)
        assertTrue(outline is Outline.Generic)
        val b = outline.bounds
        assertEquals(0f, b.left, 0.01f)
        assertEquals(0f, b.top, 0.01f)
        assertEquals(300f, b.right, 0.01f)
        assertEquals(120f, b.bottom, 0.01f)
    }

    @Test fun tooSmallForTheEasedCornerFallsBackToRounded() {
        // 14 * 1.53 = 21.4 > 36 / 2: no room to ease.
        val outline = ContinuousShape(14.dp).createOutline(Size(200f, 36f), LayoutDirection.Ltr, density)
        assertTrue(outline is Outline.Rounded)
    }
}
