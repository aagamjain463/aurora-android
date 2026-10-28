package app.aurora.ui.protect

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.aurora.auroraViewModel
import app.aurora.data.Access
import app.aurora.data.AccessState
import app.aurora.data.openAccessSettings
import app.aurora.plan.Allowance
import app.aurora.plan.ProtectedItem
import app.aurora.protect.BrowserNames
import app.aurora.protect.ContentReels
import app.aurora.protect.ContentService
import app.aurora.protect.ContentShorts
import app.aurora.protect.key
import app.aurora.rememberAccess
import app.aurora.ui.components.AuroraButton
import app.aurora.ui.components.AuroraDialog
import app.aurora.ui.components.AuroraIcons
import app.aurora.ui.components.AuroraRow
import app.aurora.ui.components.AuroraScreen
import app.aurora.ui.components.AuroraTextField
import app.aurora.ui.components.ButtonKind
import app.aurora.ui.components.Chevron
import app.aurora.ui.components.IconBadge
import app.aurora.ui.components.RowDivider
import app.aurora.ui.components.RowGroup
import app.aurora.ui.components.SectionLabel
import app.aurora.ui.components.SkeletonRows
import app.aurora.ui.components.StatusPill
import app.aurora.ui.components.Tag
import app.aurora.ui.components.ToggleRow
import app.aurora.ui.components.allowanceText
import app.aurora.ui.theme.Aurora
import app.aurora.ui.theme.AuroraType
import app.aurora.ui.theme.Radius
import app.aurora.ui.theme.Space

/**
 * Websites and content surfaces. Both need the optional Web & content Shield; the screen says plainly
 * where it works and where it doesn't.
 */
@Composable
fun WebScreen(onBack: () -> Unit, onOpenItem: (String) -> Unit, onPlus: () -> Unit) {
    val vm = auroraViewModel { ProtectModel(it) }
    val data by vm.data.collectAsStateWithLifecycle()
    val notice by vm.notice.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val access = rememberAccess(Access.Content)
    val connected by ContentService.connected.collectAsStateWithLifecycle()
    var domain by rememberSaveable { mutableStateOf("") }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    var disclosure by rememberSaveable { mutableStateOf(false) }
    val colors = Aurora.colors
    val d = data

    AuroraScreen(title = "Websites & content", subtitle = "Protect sites in your browser, and leave short-video feeds.", onBack = onBack) {
        item(key = "service") {
            val on = access == AccessState.Granted
            Column(
                Modifier
                    .padding(horizontal = Space.M)
                    .padding(top = Space.M)
                    .fillMaxWidth()
                    .clip(Radius.L)
                    .background(if (on && connected) colors.accentSoft else colors.dawn.copy(alpha = if (colors.isDark) 0.09f else 0.14f))
                    .padding(Space.M),
                verticalArrangement = Arrangement.spacedBy(Space.XS),
            ) {
                StatusPill(
                    when {
                        on && connected -> "Active"
                        on -> "Allowed, not running"
                        else -> "Off"
                    },
                    live = on && connected,
                )
                Text("Web & content Shield", Modifier.padding(top = 4.dp).semantics { heading() }, color = colors.textPrimary, style = AuroraType.Title)
                Text(
                    if (on) "Works in ${BrowserNames.joinToString(", ")}. Other browsers, in-app browsers and some private tabs aren't covered."
                    else "Optional and separate from Shield. It needs a broader Android accessibility permission, so it's off until you choose it.",
                    color = colors.textSecondary,
                    style = AuroraType.Secondary,
                )
                if (!on) AuroraButton("Turn on", { disclosure = true }, Modifier.padding(top = Space.XS))
                else if (!connected) AuroraButton("Open Accessibility settings", { openAccessSettings(context, Access.Content) }, Modifier.padding(top = Space.XS), kind = ButtonKind.Secondary)
            }
        }
        if (d == null) {
            item { SkeletonRows(3) }
            return@AuroraScreen
        }
        val sites = d.list.items.filterIsInstance<ProtectedItem.Website>()
        item(key = "sites") {
            SectionLabel("Websites · ${allowanceText(d.active.size, d.max(Allowance.ProtectedItems))} items protected")
            Row(Modifier.padding(horizontal = Space.M), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.XS)) {
                AuroraTextField(
                    domain,
                    { domain = it.take(253); error = null },
                    "reddit.com",
                    Modifier.weight(1f),
                    icon = AuroraIcons.Globe,
                    imeAction = ImeAction.Done,
                    onDone = { add(domain, sites, vm) { e -> error = e; if (e == null) domain = "" } },
                )
                AuroraButton("Add", { add(domain, sites, vm) { e -> error = e; if (e == null) domain = "" } }, kind = ButtonKind.Secondary)
            }
            error?.let { Text(it, Modifier.padding(horizontal = Space.Gutter).padding(top = Space.XS), color = colors.dawnText, style = AuroraType.Caption) }
            Hint("A site covers its subdomains too: reddit.com includes old.reddit.com. Apps and sites share your protected items.")
            if (sites.isNotEmpty()) {
                RowGroup(Modifier.padding(top = Space.M)) {
                    sites.forEachIndexed { i, site ->
                        if (i > 0) RowDivider(72.dp)
                        AuroraRow(
                            title = site.domain,
                            subtitle = if (site in d.paused) "Paused" else d.rules.summary(site.key, d.plan, website = true),
                            leading = { IconBadge(AuroraIcons.Globe) },
                            onClick = { onOpenItem(site.key) },
                            trailing = { Chevron() },
                        )
                    }
                }
            }
        }
        item(key = "content") {
            SectionLabel("Short-video feeds")
            RowGroup {
                for ((i, surface) in listOf(ContentShorts, ContentReels).withIndex()) {
                    if (i > 0) RowDivider()
                    val on = surface in d.rules.content
                    ToggleRow(
                        title = if (surface == ContentShorts) "Leave YouTube Shorts" else "Leave Instagram Reels",
                        subtitle = if (surface == ContentShorts) "Keep the rest of YouTube. When Shorts opens, aurora goes back."
                        else "Keep messages and posts. When Reels opens, aurora goes back.",
                        checked = on,
                        onCheckedChange = { want -> vm.edit { r -> r.copy(content = if (want) r.content + surface else r.content - surface) } },
                    )
                }
            }
            Row(Modifier.padding(horizontal = Space.Gutter).padding(top = Space.S), horizontalArrangement = Arrangement.spacedBy(Space.XS), verticalAlignment = Alignment.CenterVertically) {
                Tag("experimental")
                Text("These apps change often, so this can stop working after an update. It isn't tested on every version.", color = colors.textTertiary, style = AuroraType.Caption)
            }
        }
    }

    if (disclosure) AuroraDialog(
        title = "Turn on Web & content Shield",
        body = "This is a second, optional accessibility service called aurora Web & content Shield.\n\n" +
            "In supported browsers it reads the address shown in the address bar, to pause sites you protect. " +
            "In YouTube and Instagram it checks the names of on-screen elements, only to notice Shorts or Reels if you turned that on.\n\n" +
            "It doesn't read messages, passwords or what you type. Nothing is stored or sent, and nothing leaves your phone. " +
            "You can turn it off in Settings › Accessibility at any time.",
        confirmLabel = "Open settings",
        onConfirm = {
            disclosure = false
            openAccessSettings(context, Access.Content)
        },
        dismissLabel = "Not now",
        onDismiss = { disclosure = false },
    )
    LimitDialog(notice, vm::dismissNotice, onPlus)
}

private fun add(text: String, sites: List<ProtectedItem.Website>, vm: ProtectModel, done: (String?) -> Unit) {
    val site = ProtectedItem.Website.of(text.trim().removePrefix("https://").removePrefix("http://").substringBefore('/'))
    when {
        text.isBlank() -> done(null)
        site == null -> done("That doesn't look like a website. Try something like reddit.com.")
        site in sites -> done("${site.domain} is already protected.")
        else -> {
            vm.add(site)
            done(null)
        }
    }
}
