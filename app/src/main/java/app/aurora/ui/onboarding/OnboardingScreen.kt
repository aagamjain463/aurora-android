package app.aurora.ui.onboarding

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.togetherWith
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.aurora.data.Access
import app.aurora.data.AccessState
import app.aurora.data.openUsageAccessSettings
import app.aurora.rememberAccess
import app.aurora.ui.components.AuroraButton
import app.aurora.ui.components.AuroraIcons
import app.aurora.ui.components.AuroraMark
import app.aurora.ui.components.ButtonKind
import app.aurora.ui.components.HorizonGlow
import app.aurora.ui.components.Wordmark
import app.aurora.ui.theme.Aurora
import app.aurora.ui.theme.AuroraType
import app.aurora.ui.theme.Motion
import app.aurora.ui.theme.Radius
import app.aurora.ui.theme.Space

private const val PageCount = 3

/**
 * Three quiet steps: why, how, and privacy. As you move through them the horizon brightens,
 * light emerging as you take control.
 */
@Composable
fun OnboardingScreen(onFinish: () -> Unit) {
    val colors = Aurora.colors
    val reduced = Aurora.reducedMotion
    val context = LocalContext.current
    val usageAccess = rememberAccess(Access.Usage) == AccessState.Granted
    var page by rememberSaveable { mutableIntStateOf(0) }
    BackHandler(enabled = page > 0) { page-- }

    Box(Modifier.fillMaxSize().background(colors.background)) {
        HorizonGlow(
            intensity = 0.35f + 0.325f * page,
            warmth = 0.7f - 0.25f * page,
            modifier = Modifier.fillMaxWidth().fillMaxHeight(0.6f).align(Alignment.BottomCenter),
        )
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            Row(
                Modifier.fillMaxWidth().height(56.dp).padding(horizontal = Space.Gutter),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Wordmark(Modifier.weight(1f))
                PageDots(page)
            }
            BoxWithConstraints(Modifier.weight(1f)) {
                Column(
                    Modifier
                        .verticalScroll(rememberScrollState())
                        .heightIn(min = maxHeight)
                        .padding(horizontal = Space.Gutter),
                    verticalArrangement = Arrangement.Center,
                ) {
                    AnimatedContent(
                        targetState = page,
                        transitionSpec = {
                            val enter = if (reduced) fadeIn(tween(Motion.Standard)) else
                                fadeIn(tween(Motion.Gentle)) + slideInHorizontally(tween(Motion.Gentle, easing = Motion.Ease)) {
                                    if (targetState > initialState) it / 10 else -it / 10
                                }
                            enter togetherWith fadeOut(tween(Motion.Quick))
                        },
                        label = "page",
                    ) { p ->
                        when (p) {
                            0 -> WhyPage()
                            1 -> HowPage()
                            else -> PrivacyPage(usageAccess)
                        }
                    }
                }
            }
            Column(
                Modifier.padding(horizontal = Space.Gutter, vertical = Space.M).animateContentSize(),
                verticalArrangement = Arrangement.spacedBy(Space.XS),
            ) {
                when (page) {
                    0 -> AuroraButton("Begin", { page = 1 }, Modifier.fillMaxWidth())
                    1 -> AuroraButton("Continue", { page = 2 }, Modifier.fillMaxWidth())
                    else -> {
                        AuroraButton("Start using aurora", onFinish, Modifier.fillMaxWidth())
                        if (!usageAccess) {
                            AuroraButton(
                                "Allow usage access",
                                { openUsageAccessSettings(context) },
                                Modifier.fillMaxWidth(),
                                kind = ButtonKind.Quiet,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PageDots(page: Int) {
    val colors = Aurora.colors
    Row(
        Modifier.semantics { contentDescription = "Step ${page + 1} of $PageCount" },
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        repeat(PageCount) { i ->
            Box(
                Modifier
                    .animateContentSize(tween(Motion.Standard, easing = Motion.Ease))
                    .height(4.dp)
                    .width(if (i == page) 18.dp else 6.dp)
                    .clip(Radius.Pill)
                    .background(if (i <= page) colors.accent else colors.hairline),
            )
        }
    }
}

@Composable
private fun WhyPage() {
    val colors = Aurora.colors
    Column {
        AuroraMark(64.dp)
        Spacer(Modifier.height(Space.XL))
        Text("Your attention\nis yours.", Modifier.semantics { heading() }, color = colors.textPrimary, style = AuroraType.Display)
        Spacer(Modifier.height(Space.M))
        Text(
            "aurora helps you use your phone with intention, not out of habit. You decide what deserves your time.",
            color = colors.textSecondary,
            style = AuroraType.Body,
        )
    }
}

@Composable
private fun HowPage() {
    val colors = Aurora.colors
    Column {
        Text("how it works", color = colors.textTertiary, style = AuroraType.Eyebrow)
        Spacer(Modifier.height(Space.S))
        Text("Built around\nyour intentions.", Modifier.semantics { heading() }, color = colors.textPrimary, style = AuroraType.Display)
        Spacer(Modifier.height(Space.XL))
        Column(verticalArrangement = Arrangement.spacedBy(Space.L)) {
            Point(AuroraIcons.Focus, "Focus sessions", "Set aside time for what you came to do. Available today.")
            Point(AuroraIcons.Protect, "Protected apps", "Choose the apps that pull you in. Once you allow it, Shield pauses you when one opens.")
            Point(AuroraIcons.Insights, "Honest insight", "See where your time goes, only if you choose to allow it.")
        }
    }
}

@Composable
private fun PrivacyPage(usageAccess: Boolean) {
    val colors = Aurora.colors
    Column {
        Text("privacy", color = colors.textTertiary, style = AuroraType.Eyebrow)
        Spacer(Modifier.height(Space.S))
        Text("Everything stays\non this phone.", Modifier.semantics { heading() }, color = colors.textPrimary, style = AuroraType.Display)
        Spacer(Modifier.height(Space.M))
        Text(
            "No account. No ads. No tracking. aurora doesn't even ask for internet access.",
            color = colors.textSecondary,
            style = AuroraType.Body,
        )
        Spacer(Modifier.height(Space.XL))
        Point(
            AuroraIcons.Insights,
            if (usageAccess) "Usage access is on" else "Optional: usage access",
            if (usageAccess) "Insights can show today's screen time. You can turn this off anytime in Android Settings."
            else "To show your screen time, Android asks you to allow usage access for aurora. It's only read on this phone, and you can do it later.",
        )
    }
}

@Composable
private fun Point(icon: ImageVector, title: String, body: String) {
    val colors = Aurora.colors
    Row(horizontalArrangement = Arrangement.spacedBy(Space.M), modifier = Modifier.semantics(mergeDescendants = true) { }) {
        Box(Modifier.size(40.dp).clip(Radius.Pill).background(colors.accentSoft), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = colors.accent, modifier = Modifier.size(20.dp))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, color = colors.textPrimary, style = AuroraType.BodyStrong)
            Text(body, color = colors.textSecondary, style = AuroraType.Secondary)
        }
    }
}
