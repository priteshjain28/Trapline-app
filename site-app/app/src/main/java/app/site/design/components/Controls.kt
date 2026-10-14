package app.site.design.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.site.design.Radius
import app.site.design.SiteTheme
import app.site.design.Size
import app.site.design.Space

private val buttonPadding = PaddingValues(horizontal = Space.s4)

@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
    @DrawableRes icon: Int? = null,
    large: Boolean = false,
    danger: Boolean = false,
) {
    val c = SiteTheme.colors
    val bg = if (danger) c.danger else c.accent
    Button(
        onClick = onClick,
        enabled = enabled && !loading,
        modifier = modifier.height(if (large) Size.buttonLg else Size.buttonMd).let { if (large) it.fillMaxWidth() else it },
        shape = Radius.md,
        contentPadding = buttonPadding,
        colors = ButtonDefaults.buttonColors(
            containerColor = bg, contentColor = c.onAccent,
            disabledContainerColor = bg.copy(alpha = if (loading) 1f else 0.38f), disabledContentColor = c.onAccent.copy(alpha = if (loading) 1f else 0.7f),
        ),
    ) {
        ButtonContent(text, icon, loading, c.onAccent)
    }
}

@Composable
fun SecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    @DrawableRes icon: Int? = null,
    small: Boolean = false,
) {
    val c = SiteTheme.colors
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.height(if (small) Size.buttonSm else Size.buttonMd),
        shape = Radius.md,
        contentPadding = PaddingValues(horizontal = if (small) Space.s3 else Space.s4),
        border = BorderStroke(1.dp, c.borderInput),
        colors = ButtonDefaults.outlinedButtonColors(containerColor = c.surfaceRaised, contentColor = c.text),
    ) {
        ButtonContent(text, icon, false, c.text, small)
    }
}

/** Text-only action, e.g. "+ Update". Keeps a 48dp touch target. */
@Composable
fun TextAction(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, @DrawableRes icon: Int? = null, enabled: Boolean = true) {
    val c = SiteTheme.colors
    TextButton(onClick, modifier.heightIn(min = Size.touchMin), enabled = enabled,
        colors = ButtonDefaults.textButtonColors(contentColor = c.accent)) {
        ButtonContent(text, icon, false, c.accent, small = true)
    }
}

@Composable
private fun ButtonContent(text: String, @DrawableRes icon: Int?, loading: Boolean, color: androidx.compose.ui.graphics.Color, small: Boolean = false) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s2)) {
        if (loading) CircularProgressIndicator(Modifier.size(18.dp), color = color, strokeWidth = 2.dp)
        else if (icon != null) SiteIcon(icon, null, tint = color, size = Size.iconSm)
        Text(text, style = (if (small) SiteTheme.type.sm else SiteTheme.type.base).copy(fontWeight = FontWeight.W600), maxLines = 1)
    }
}

@Composable
fun SiteFab(text: String, @DrawableRes icon: Int, onClick: () -> Unit, modifier: Modifier = Modifier, expanded: Boolean = true) {
    val c = SiteTheme.colors
    ExtendedFloatingActionButton(
        text = { Text(text, style = SiteTheme.type.base.copy(fontWeight = FontWeight.W600)) },
        icon = { SiteIcon(icon, null, tint = c.onAccent) },
        onClick = onClick,
        expanded = expanded,
        modifier = modifier,
        shape = Radius.lg,
        containerColor = c.accent,
        contentColor = c.onAccent,
    )
}

@Composable
fun SiteIconFab(@DrawableRes icon: Int, contentDescription: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = SiteTheme.colors
    FloatingActionButton(onClick, modifier, shape = Radius.lg, containerColor = c.accent, contentColor = c.onAccent) {
        SiteIcon(icon, contentDescription, tint = c.onAccent)
    }
}

@Composable
fun SiteIconButton(@DrawableRes icon: Int, contentDescription: String, onClick: () -> Unit, modifier: Modifier = Modifier,
                   tint: androidx.compose.ui.graphics.Color = SiteTheme.colors.text, enabled: Boolean = true) {
    IconButton(onClick, modifier.size(Size.touchMin), enabled = enabled) {
        SiteIcon(icon, contentDescription, tint = if (enabled) tint else tint.copy(alpha = 0.38f))
    }
}

/** Selectable card with title, description and an optional "New" badge (report type). */
@Composable
fun ChoiceCard(title: String, description: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, badge: String? = null) {
    val c = SiteTheme.colors
    Column(
        modifier.fillMaxWidth().clip(Radius.md)
            .background(if (selected) c.accentTint else c.surfaceRaised)
            .border(if (selected) 2.dp else 1.dp, if (selected) c.accent else c.border, Radius.md)
            .selectable(selected = selected, onClick = onClick, role = Role.RadioButton)
            .padding(horizontal = Space.s4, vertical = Space.s3),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s2)) {
            Text(title, style = SiteTheme.type.base.copy(fontWeight = FontWeight.W600), color = if (selected) c.onAccentTint else c.text)
            if (badge != null) StatusBadge(badge, Tone.SUCCESS)
        }
        Text(description, style = SiteTheme.type.sm, color = c.textMuted)
    }
}

/** Checkbox row with title and an optional reason/description. [locked] = always on, explained. */
@Composable
fun CheckRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    description: String? = null,
    enabled: Boolean = true,
    indent: Boolean = false,
) {
    val c = SiteTheme.colors
    Row(
        modifier.fillMaxWidth()
            .toggleable(checked, enabled = enabled, role = Role.Checkbox, onValueChange = onCheckedChange)
            .heightIn(min = Size.touchMin)
            .padding(start = if (indent) Space.s6 else 0.dp, top = Space.s1, bottom = Space.s1),
        verticalAlignment = if (description == null) Alignment.CenterVertically else Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(Space.s3),
    ) {
        Checkbox(checked, onCheckedChange = null, enabled = enabled,
            colors = CheckboxDefaults.colors(checkedColor = c.accent, uncheckedColor = c.borderInput, checkmarkColor = c.onAccent))
        Column(Modifier.padding(top = if (description == null) 0.dp else Space.s3)) {
            Text(title, style = SiteTheme.type.base, color = if (enabled) c.text else c.textMuted)
            if (description != null) Text(description, style = SiteTheme.type.sm, color = c.textMuted)
        }
    }
}

@Composable
fun SwitchRow(title: String, description: String?, checked: Boolean, onCheckedChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    val c = SiteTheme.colors
    Row(
        modifier.fillMaxWidth()
            .toggleable(checked, role = Role.Switch, onValueChange = onCheckedChange)
            .heightIn(min = 56.dp)
            .padding(horizontal = Size.gutter, vertical = Space.s2),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = SiteTheme.type.base, color = c.text)
            if (description != null) Text(description, style = SiteTheme.type.sm, color = c.textMuted)
        }
        Switch(checked, onCheckedChange = null, colors = SwitchDefaults.colors(
            checkedTrackColor = c.accent, checkedThumbColor = c.onAccent,
            uncheckedTrackColor = c.surfaceTrack, uncheckedBorderColor = c.borderInput, uncheckedThumbColor = c.borderInput,
        ))
    }
}

/** Quick filter chip: 32dp visual, 48dp touch target. */
@Composable
fun FilterChip(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = SiteTheme.colors
    Box(
        modifier.heightIn(min = Size.touchMin).selectable(selected, onClick = onClick, role = Role.Checkbox),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            Modifier.height(32.dp).clip(Radius.md)
                .background(if (selected) c.accentTint else c.bg)
                .border(1.dp, if (selected) c.accent else c.borderInput, Radius.md)
                .padding(horizontal = Space.s3),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Space.s1),
        ) {
            if (selected) SiteIcon(app.site.R.drawable.ic_check, null, tint = c.onAccentTint, size = 16.dp)
            Text(label, style = SiteTheme.type.xs.copy(fontWeight = FontWeight.W600), color = if (selected) c.onAccentTint else c.text)
        }
    }
}

/** Three-way attendance toggle: present, half day, absent. Null = not marked. */
@Composable
fun <T> SegmentedToggle(
    options: List<Pair<T, String>>,
    selected: T?,
    onSelect: (T) -> Unit,
    descriptionFor: (T) -> String,
    modifier: Modifier = Modifier,
    selectedTone: (T) -> Tone = { Tone.SUCCESS },
) {
    val c = SiteTheme.colors
    Row(modifier.clip(Radius.md).border(1.dp, c.borderInput, Radius.md)) {
        options.forEachIndexed { i, (value, label) ->
            val on = value == selected
            val tone = selectedTone(value)
            val (bg, fg) = when {
                !on -> c.bg to c.textMuted
                tone == Tone.DANGER -> c.dangerTint to c.danger
                tone == Tone.WARNING -> c.warningTint to c.warning
                else -> c.successTint to c.success
            }
            if (i > 0) Box(Modifier.size(1.dp, Size.touchMin).background(c.borderInput))
            Box(
                Modifier.size(Size.touchMin).background(bg)
                    .selectable(on, onClick = { onSelect(value) }, role = Role.RadioButton)
                    .semantics { contentDescription = descriptionFor(value); stateDescription = if (on) "selected" else "" },
                contentAlignment = Alignment.Center,
            ) { Text(label, style = SiteTheme.type.sm.copy(fontWeight = FontWeight.W600), color = fg) }
        }
    }
}
