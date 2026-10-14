package app.site.design.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.site.R
import app.site.design.Radius
import app.site.design.SiteTheme
import app.site.design.Size
import app.site.design.Space

/** Org home (S04): dark header with the org, its plan, messages and the user. */
@Composable
fun AppBarOrg(orgName: String, plan: String, userName: String, onProfile: () -> Unit, onMessages: () -> Unit) {
    val c = SiteTheme.colors
    Row(
        Modifier.fillMaxWidth().background(c.header).statusBarsPadding().height(Size.appBar).padding(horizontal = Size.gutter),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.s3),
    ) {
        Avatar(orgName, size = Size.avatarSm, square = true)
        Row(Modifier.weight(1f), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(Space.s2)) {
            Text(orgName, Modifier.weight(1f, fill = false).semantics { heading() }, style = SiteTheme.type.base.copy(fontWeight = FontWeight.W600),
                color = c.onHeader, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(plan, style = SiteTheme.type.xs, color = c.onHeaderMuted, maxLines = 1)
        }
        SiteIconButton(R.drawable.ic_message_circle, stringResource(R.string.a11y_messages), onMessages, tint = c.onHeader)
        Box(Modifier.size(Size.touchMin).clickable(role = Role.Button, onClickLabel = stringResource(R.string.a11y_your_profile), onClick = onProfile),
            contentAlignment = Alignment.Center) {
            Avatar(userName.ifBlank { "?" }, size = Size.avatarSm)
        }
    }
}

/** Inside a project (S05): close, org over project with a switcher, messages, help. */
@Composable
fun AppBarProject(orgName: String, projectName: String, onClose: () -> Unit, onSwitch: () -> Unit, onMessages: () -> Unit, onHelp: () -> Unit) {
    val c = SiteTheme.colors
    Column(Modifier.background(c.bg).statusBarsPadding()) {
        Row(
            Modifier.fillMaxWidth().height(Size.appBar).padding(start = Space.s1, end = Size.gutter),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SiteIconButton(R.drawable.ic_x, stringResource(R.string.a11y_close_project), onClose)
            Column(
                Modifier.weight(1f).heightIn(min = Size.touchMin)
                    .clickable(role = Role.Button, onClickLabel = stringResource(R.string.a11y_switch_project), onClick = onSwitch)
                    .padding(vertical = Space.s1),
                verticalArrangement = Arrangement.Center,
            ) {
                Text(orgName, style = SiteTheme.type.xs, color = c.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(projectName, Modifier.weight(1f, fill = false).semantics { heading() },
                        style = SiteTheme.type.base.copy(fontWeight = FontWeight.W600), color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    SiteIcon(R.drawable.ic_chevrons_up_down, null, tint = c.textMuted, size = Size.iconSm)
                }
            }
            SiteIconButton(R.drawable.ic_message_circle, stringResource(R.string.a11y_messages), onMessages)
            SecondaryButton(stringResource(R.string.action_help), onHelp, small = true)
        }
        Divider()
    }
}

/** Sub-page: back, small project name over the title, optional trailing action. */
@Composable
fun AppBarSub(title: String, onBack: () -> Unit, overline: String? = null, trailing: (@Composable () -> Unit)? = null) {
    val c = SiteTheme.colors
    Column(Modifier.background(c.bg).statusBarsPadding()) {
        Row(
            Modifier.fillMaxWidth().height(Size.appBar).padding(start = Space.s1, end = Space.s2),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SiteIconButton(R.drawable.ic_arrow_left, stringResource(R.string.a11y_back), onBack)
            Column(Modifier.weight(1f).padding(start = Space.s1)) {
                if (overline != null) Text(overline, style = SiteTheme.type.xs, color = c.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(title, Modifier.semantics { heading() }, style = SiteTheme.type.lg, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            trailing?.invoke()
        }
        Divider()
    }
}

data class NavItem(val label: String, @DrawableRes val icon: Int)

@Composable
fun BottomNav(items: List<NavItem>, selected: Int, onSelect: (Int) -> Unit) {
    val c = SiteTheme.colors
    Column(Modifier.background(c.bg).navigationBarsPadding()) {
        Divider()
        Row(Modifier.fillMaxWidth().height(Size.bottomNav).selectableGroup()) {
            items.forEachIndexed { i, item ->
                val on = i == selected
                Column(
                    Modifier.weight(1f).height(Size.bottomNav).selectable(on, onClick = { onSelect(i) }, role = Role.Tab),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Box(
                        Modifier.size(width = 56.dp, height = 28.dp).clip(Radius.lg).background(if (on) c.accentTint else c.bg),
                        contentAlignment = Alignment.Center,
                    ) { SiteIcon(item.icon, null, tint = if (on) c.accent else c.textMuted) }
                    // five labels share the width: let them grow to 1.3x, then stop, so they never run together
                    val scale = LocalDensity.current.fontScale
                    val xs = SiteTheme.type.xs
                    Text(item.label, Modifier.padding(top = Space.s1, start = 2.dp, end = 2.dp),
                        style = if (scale > 1.3f) xs.copy(fontSize = xs.fontSize * (1.3f / scale), lineHeight = xs.lineHeight * (1.3f / scale)) else xs,
                        color = if (on) c.accent else c.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
fun Tabs(labels: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val c = SiteTheme.colors
    Column(modifier.fillMaxWidth()) {
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = Space.s2).selectableGroup()) {
            labels.forEachIndexed { i, label ->
                val on = i == selected
                Column(
                    // intrinsic width so the underline can fill the label's width inside the scrolling row
                    Modifier.width(IntrinsicSize.Max).heightIn(min = Size.touchMin).selectable(on, onClick = { onSelect(i) }, role = Role.Tab)
                        .padding(horizontal = Space.s3),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(label, Modifier.padding(vertical = Space.s3), style = SiteTheme.type.sm.copy(fontWeight = FontWeight.W600),
                        color = if (on) c.accent else c.textMuted, maxLines = 1)
                    Box(Modifier.height(3.dp).fillMaxWidth().background(if (on) c.accent else c.bg))
                }
            }
        }
        Divider()
    }
}

@Composable
fun SegmentedControl(labels: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val c = SiteTheme.colors
    Row(modifier.fillMaxWidth().clip(Radius.md).background(c.surfaceTrack).padding(Space.s1).selectableGroup()) {
        labels.forEachIndexed { i, label ->
            val on = i == selected
            Box(
                Modifier.weight(1f).height(Size.touchMin).clip(Radius.md)
                    .then(if (on) Modifier.background(c.surfaceRaised).border(1.dp, c.border, Radius.md) else Modifier)
                    .selectable(on, onClick = { onSelect(i) }, role = Role.Tab),
                contentAlignment = Alignment.Center,
            ) {
                Text(label, style = SiteTheme.type.base.copy(fontWeight = FontWeight.W600), color = if (on) c.text else c.textMuted)
            }
        }
    }
}

@Composable
fun OfflineBanner() {
    val c = SiteTheme.colors
    Row(
        Modifier.fillMaxWidth().background(c.surfaceTrack).padding(horizontal = Size.gutter, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.s2),
    ) {
        SiteIcon(R.drawable.ic_wifi_off, null, tint = c.text, size = 16.dp)
        Text(stringResource(R.string.offline_banner), style = SiteTheme.type.xs, color = c.text)
    }
}
