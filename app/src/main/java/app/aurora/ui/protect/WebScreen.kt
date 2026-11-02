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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.aurora.ui.components.glass
import app.aurora.R
import app.aurora.auroraViewModel
import app.aurora.data.Access
import app.aurora.data.AccessState
import app.aurora.data.openAccessSettings
import app.aurora.plan.Allowance
import app.aurora.plan.ProtectedItem
import app.aurora.protect.BrowserNames
import app.aurora.protect.ContentInstagramFeed
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

    AuroraScreen(title = stringResource(R.string.web_title), subtitle = stringResource(R.string.web_subtitle), onBack = onBack) {
        item(key = "service") {
            val on = access == AccessState.Granted
            Column(
                Modifier
                    .padding(horizontal = Space.M)
                    .padding(top = Space.M)
                    .fillMaxWidth()
                    .glass(colors, Radius.L, tint = if (on && connected) colors.accent else colors.violet)
                    .padding(Space.M),
                verticalArrangement = Arrangement.spacedBy(Space.XS),
            ) {
                StatusPill(
                    when {
                        on && connected -> stringResource(R.string.web_active)
                        on -> stringResource(R.string.web_not_running)
                        else -> stringResource(R.string.web_off)
                    },
                    live = on && connected,
                )
                Text(stringResource(R.string.web_shield_title), Modifier.padding(top = 4.dp).semantics { heading() }, color = colors.textPrimary, style = AuroraType.Title)
                Text(
                    if (on) stringResource(R.string.web_supported_browsers, BrowserNames.joinToString(", "))
                    else stringResource(R.string.web_optional),
                    color = colors.textSecondary,
                    style = AuroraType.Secondary,
                )
                if (!on) AuroraButton(stringResource(R.string.web_turn_on), { disclosure = true }, Modifier.padding(top = Space.XS))
                else if (!connected) AuroraButton(stringResource(R.string.web_open_accessibility), { openAccessSettings(context, Access.Content) }, Modifier.padding(top = Space.XS), kind = ButtonKind.Secondary)
            }
        }
        if (d == null) {
            item { SkeletonRows(3) }
            return@AuroraScreen
        }
        val sites = d.list.items.filterIsInstance<ProtectedItem.Website>()
        item(key = "sites") {
            SectionLabel(stringResource(R.string.web_items, allowanceText(d.active.size, d.max(Allowance.ProtectedItems))))
            Row(Modifier.padding(horizontal = Space.M), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.XS)) {
                AuroraTextField(
                    domain,
                    { domain = it.take(253); error = null },
                    stringResource(R.string.web_example),
                    Modifier.weight(1f),
                    icon = AuroraIcons.Globe,
                    imeAction = ImeAction.Done,
                    onDone = { add(domain, sites, vm, context) { e -> error = e; if (e == null) domain = "" } },
                )
                AuroraButton(stringResource(R.string.web_add), { add(domain, sites, vm, context) { e -> error = e; if (e == null) domain = "" } }, kind = ButtonKind.Secondary)
            }
            error?.let { Text(it, Modifier.padding(horizontal = Space.Gutter).padding(top = Space.XS), color = colors.violetText, style = AuroraType.Caption) }
            WebHint(stringResource(R.string.web_hint))
            if (sites.isNotEmpty()) {
                RowGroup(Modifier.padding(top = Space.M)) {
                    sites.forEachIndexed { i, site ->
                        if (i > 0) RowDivider(72.dp)
                        AuroraRow(
                            title = site.domain,
                            subtitle = if (site in d.paused) stringResource(R.string.web_paused) else d.rules.summary(site.key, d.plan, website = true) { modeNameText(context, it) },
                            leading = { IconBadge(AuroraIcons.Globe) },
                            onClick = { onOpenItem(site.key) },
                            trailing = { Chevron() },
                        )
                    }
                }
            }
        }
        item(key = "content") {
            SectionLabel(stringResource(R.string.web_feeds))
            RowGroup {
                for ((i, surface) in listOf(ContentShorts, ContentReels, ContentInstagramFeed).withIndex()) {
                    if (i > 0) RowDivider()
                    val on = surface in d.rules.content
                    val (title, body) = when (surface) {
                        ContentShorts -> R.string.web_shorts to R.string.web_shorts_body
                        ContentReels -> R.string.web_reels to R.string.web_reels_body
                        else -> R.string.web_ig_feed to R.string.web_ig_feed_body
                    }
                    ToggleRow(
                        title = stringResource(title),
                        subtitle = stringResource(body),
                        checked = on,
                        onCheckedChange = { want -> vm.edit { r -> r.copy(content = if (want) r.content + surface else r.content - surface) } },
                    )
                }
            }
            Row(Modifier.padding(horizontal = Space.Gutter).padding(top = Space.S), horizontalArrangement = Arrangement.spacedBy(Space.XS), verticalAlignment = Alignment.CenterVertically) {
                Tag(stringResource(R.string.web_experimental))
                Text(stringResource(R.string.web_experimental_body), color = colors.textTertiary, style = AuroraType.Caption)
            }
        }
    }

    if (disclosure) AuroraDialog(
        title = stringResource(R.string.web_disclosure_title),
        body = stringResource(R.string.web_disclosure_body),
        confirmLabel = stringResource(R.string.web_open_settings),
        onConfirm = {
            disclosure = false
            openAccessSettings(context, Access.Content)
        },
        dismissLabel = stringResource(R.string.action_not_now),
        onDismiss = { disclosure = false },
    )
    notice?.let { n ->
        AuroraDialog(n.title, vm::dismissNotice, if (n.plus) stringResource(R.string.web_see_plus) else stringResource(R.string.action_ok), { vm.dismissNotice(); if (n.plus) onPlus() }, n.body, if (n.plus) stringResource(R.string.action_not_now) else null)
    }
}

private fun add(text: String, sites: List<ProtectedItem.Website>, vm: ProtectModel, context: android.content.Context, done: (String?) -> Unit) {
    val site = ProtectedItem.Website.of(text.trim().removePrefix("https://").removePrefix("http://").substringBefore('/'))
    when {
        text.isBlank() -> done(null)
        site == null -> done(context.getString(R.string.web_invalid))
        site in sites -> done(context.getString(R.string.web_duplicate, site.domain))
        else -> {
            vm.add(site)
            done(null)
        }
    }
}

@Composable
private fun WebHint(text: String) {
    Text(text, Modifier.padding(horizontal = Space.Gutter, vertical = Space.S), color = Aurora.colors.textTertiary, style = AuroraType.Caption)
}
