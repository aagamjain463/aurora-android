package app.aurora.protect

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.aurora.ui.components.AuroraButton
import app.aurora.ui.components.AuroraMark
import app.aurora.ui.components.HorizonGlow
import app.aurora.ui.components.StatusPill
import app.aurora.ui.components.Wordmark
import app.aurora.ui.theme.Aurora
import app.aurora.ui.theme.AuroraType
import app.aurora.ui.theme.Space

/**
 * The Shield: first light over a still horizon, the app's name, and one clear way back. No motion on
 * the way in, so nothing delays the first frame. [label] is null for the moment it's still loading; the
 * layout is already in place, so nothing moves when it arrives.
 */
@Composable
fun ShieldScreen(label: String?, icon: ImageBitmap?, onClose: () -> Unit) {
    val colors = Aurora.colors
    Box(
        Modifier
            .fillMaxSize()
            .background(colors.background)
            .semantics { paneTitle = "Shield" },
    ) {
        HorizonGlow(
            intensity = 0.55f,
            warmth = 0.7f,
            modifier = Modifier.fillMaxWidth().fillMaxHeight(0.42f).align(Alignment.BottomCenter),
        )
        BoxWithConstraints(Modifier.fillMaxSize().systemBarsPadding()) {
            // At least the screen's height, so the action sits low; scrolls when large text needs more.
            Column(
                Modifier
                    .verticalScroll(rememberScrollState())
                    .heightIn(min = maxHeight)
                    .fillMaxWidth()
                    .padding(horizontal = Space.Gutter, vertical = Space.L),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.XS)) {
                    AuroraMark(24.dp)
                    Wordmark()
                }
                Spacer(Modifier.weight(1f).heightIn(min = Space.XXL))
                Column(
                    Modifier.widthIn(max = 480.dp).alpha(if (label == null) 0f else 1f),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(Modifier.size(72.dp).clip(RoundedCornerShape(20.dp))) {
                        if (icon != null) Image(icon, contentDescription = null, modifier = Modifier.size(72.dp))
                    }
                    Spacer(Modifier.height(Space.L))
                    StatusPill("Protected by Shield", live = false)
                    Spacer(Modifier.height(Space.M))
                    Text(
                        "${label.orEmpty()} is protected",
                        Modifier.semantics { heading() },
                        color = colors.textPrimary,
                        style = AuroraType.Headline,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(Space.S))
                    Text(
                        "You chose to keep this app out of reach. Take a breath, then go back to what you meant to do.",
                        color = colors.textSecondary,
                        style = AuroraType.Body,
                        textAlign = TextAlign.Center,
                    )
                }
                Spacer(Modifier.weight(1f).heightIn(min = Space.XXL))
                AuroraButton("Close app", onClose, Modifier.widthIn(max = 480.dp).fillMaxWidth())
                Spacer(Modifier.height(Space.M))
                Text(
                    "You can change which apps are protected in aurora.",
                    color = colors.textTertiary,
                    style = AuroraType.Caption,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}
