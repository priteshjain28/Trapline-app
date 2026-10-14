package app.site.design.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size as GSize
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.site.core.logic.Format
import app.site.design.Radius
import app.site.design.SiteTheme
import app.site.design.Size
import app.site.design.Space

@Composable
fun SiteIcon(
    @DrawableRes icon: Int,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    tint: Color = SiteTheme.colors.text,
    size: Dp = Size.icon,
) {
    Icon(painterResource(icon), contentDescription, modifier.size(size), tint = tint)
}

enum class Tone { NEUTRAL, INFO, SUCCESS, WARNING, DANGER }

@Composable
private fun toneColors(tone: Tone): Pair<Color, Color> {
    val c = SiteTheme.colors
    return when (tone) {
        Tone.NEUTRAL -> c.surfaceTrack to c.textMuted
        Tone.INFO -> c.infoTint to c.info
        Tone.SUCCESS -> c.successTint to c.success
        Tone.WARNING -> c.warningTint to c.warning
        Tone.DANGER -> c.dangerTint to c.danger
    }
}

/** Status in words on a tint. Colour is never the only cue. */
@Composable
fun StatusBadge(text: String, tone: Tone, modifier: Modifier = Modifier) {
    val (bg, fg) = toneColors(tone)
    Text(
        text,
        modifier.clip(Radius.sm).background(bg).padding(horizontal = Space.s2, vertical = 2.dp),
        color = fg,
        style = SiteTheme.type.xs.copy(fontWeight = FontWeight.W600),
        maxLines = 1,
    )
}

@Composable
fun ProgressRing(pct: Int, modifier: Modifier = Modifier, size: Dp = 20.dp) {
    val track = SiteTheme.colors.surfaceTrack
    val value = SiteTheme.colors.accent
    Box(
        modifier.size(size).drawBehind {
            val stroke = 3.dp.toPx()
            val inset = stroke / 2
            val arc = GSize(this.size.width - stroke, this.size.height - stroke)
            drawArc(track, 0f, 360f, false, Offset(inset, inset), arc, style = Stroke(stroke))
            drawArc(value, -90f, 360f * pct.coerceIn(0, 100) / 100f, false, Offset(inset, inset), arc,
                style = Stroke(stroke, cap = StrokeCap.Round))
        },
    )
}

@Composable
fun Avatar(name: String, modifier: Modifier = Modifier, size: Dp = Size.avatarMd, square: Boolean = false) {
    val c = SiteTheme.colors
    // fixed pick per name so a person keeps their colour
    val tints = listOf(c.accentTint to c.onAccentTint, c.warningTint to c.warning, c.successTint to c.success, c.infoTint to c.info)
    val (bg, fg) = tints[(name.hashCode() and 0x7fffffff) % tints.size]
    Box(
        modifier.size(size).clip(if (square) Radius.md else CircleShape).background(bg),
        contentAlignment = Alignment.Center,
    ) {
        Text(Format.initials(name), color = fg, style = if (size >= 40.dp) SiteTheme.type.sm.copy(fontWeight = FontWeight.W600)
        else SiteTheme.type.xs.copy(fontWeight = FontWeight.W600))
    }
}

@Composable
fun InfoBanner(text: String, modifier: Modifier = Modifier, tone: Tone = Tone.INFO, @DrawableRes icon: Int = app.site.R.drawable.ic_info) {
    val (bg, fg) = toneColors(tone)
    Row(
        modifier.fillMaxWidth().clip(Radius.md).background(bg).padding(Space.s4, Space.s3),
        horizontalArrangement = Arrangement.spacedBy(Space.s3),
    ) {
        SiteIcon(icon, null, tint = fg, size = Size.iconSm)
        Text(text, color = fg, style = SiteTheme.type.sm)
    }
}

/** Icon in a tinted circle, a title, one line of help, one action. No borrowed illustrations. */
@Composable
fun EmptyState(
    @DrawableRes icon: Int,
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    action: String? = null,
    onAction: (() -> Unit)? = null,
    danger: Boolean = false,
) {
    val c = SiteTheme.colors
    Column(
        modifier.fillMaxWidth().padding(horizontal = Space.s6, vertical = Space.s7),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.size(Size.thumb).clip(CircleShape).background(if (danger) c.dangerTint else c.accentTint),
            contentAlignment = Alignment.Center,
        ) { SiteIcon(icon, null, tint = if (danger) c.danger else c.accent, size = 32.dp) }
        Spacer(Modifier.height(Space.s3))
        Text(title, style = SiteTheme.type.base.copy(fontWeight = FontWeight.W600), color = c.text, textAlign = TextAlign.Center)
        Spacer(Modifier.height(Space.s1))
        Text(body, style = SiteTheme.type.sm, color = c.textMuted, textAlign = TextAlign.Center)
        if (action != null && onAction != null) {
            Spacer(Modifier.height(Space.s4))
            if (danger) SecondaryButton(action, onAction, icon = app.site.R.drawable.ic_refresh_cw)
            else PrimaryButton(action, onAction, icon = app.site.R.drawable.ic_plus)
        }
    }
}

@Composable
fun LoadingBlock(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().padding(Space.s7), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = SiteTheme.colors.accent, strokeWidth = 3.dp, modifier = Modifier.size(28.dp))
    }
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text, modifier.padding(top = Space.s5, bottom = Space.s2),
        style = SiteTheme.type.sm.copy(fontWeight = FontWeight.W600), color = SiteTheme.colors.textMuted,
    )
}

@Composable
fun Divider(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(1.dp).background(SiteTheme.colors.border))
}

/** Outline card on the raised surface. */
@Composable
fun SiteCard(modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, label: String? = null, content: @Composable () -> Unit) {
    val c = SiteTheme.colors
    var m = modifier.fillMaxWidth().clip(Radius.md).background(c.surfaceRaised).border(1.dp, c.border, Radius.md)
    if (onClick != null) m = m.clickable(role = Role.Button, onClick = onClick)
    if (label != null) m = m.semantics(mergeDescendants = true) { contentDescription = label }
    Box(m.padding(Space.s4)) { content() }
}

/** Min-height list row with a bottom divider. */
@Composable
fun ListRow(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    divider: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) {
    Column(modifier.fillMaxWidth()) {
        var m = Modifier.fillMaxWidth().heightIn(min = 56.dp)
        if (onClick != null) m = m.clickable(role = Role.Button, onClick = onClick)
        Row(m.padding(horizontal = Size.gutter, vertical = Space.s2), verticalAlignment = Alignment.CenterVertically) {
            content()
        }
        if (divider) Divider(Modifier.padding(start = Size.gutter))
    }
}

@Composable
fun HSpace(w: Dp) = Spacer(Modifier.width(w))

@Composable
fun VSpace(h: Dp) = Spacer(Modifier.height(h))
