package app.aurora.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp

/**
 * The seal: [content] (the logo, an app's icon) alone at the centre of a quiet square of space. No ring, no marks:
 * the empty room around it is what gives it weight.
 */
@Composable
fun Seal(size: Dp, modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    Box(modifier.size(size), contentAlignment = Alignment.Center) { content() }
}
