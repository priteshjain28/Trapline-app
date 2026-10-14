package app.site.design.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.site.R
import app.site.core.logic.Format
import app.site.design.Radius
import app.site.design.SiteTheme
import app.site.design.Size
import app.site.design.Space
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/** Label above, required marker, helper or error below. Errors are announced. */
@Composable
fun SiteTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    required: Boolean = false,
    error: String? = null,
    helper: String? = null,
    keyboardType: KeyboardType = KeyboardType.Text,
    imeAction: ImeAction = ImeAction.Next,
    prefix: String? = null,
    suffix: String? = null,
    singleLine: Boolean = true,
    minLines: Int = 1,
    enabled: Boolean = true,
    @DrawableRes leadingIcon: Int? = null,
) {
    val c = SiteTheme.colors
    val requiredText = stringResource(R.string.a11y_required)
    val errorText = error
    Column(modifier.fillMaxWidth()) {
        Row {
            Text(label, style = SiteTheme.type.sm.copy(fontWeight = FontWeight.W600), color = c.textMuted)
            if (required) Text(" *", style = SiteTheme.type.sm.copy(fontWeight = FontWeight.W600), color = c.danger,
                modifier = Modifier.semantics { contentDescription = requiredText })
        }
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth().padding(top = Space.s1).semantics {
                contentDescription = label
                if (errorText != null) this.error(errorText)
            },
            enabled = enabled,
            textStyle = SiteTheme.type.base.copy(color = c.text),
            placeholder = placeholder?.let { { Text(it, style = SiteTheme.type.base, color = c.textSubtle) } },
            leadingIcon = leadingIcon?.let { { SiteIcon(it, null, tint = c.textMuted) } },
            prefix = prefix?.let { { Text(it, style = SiteTheme.type.base, color = c.textMuted) } },
            suffix = suffix?.let { { Text(it, style = SiteTheme.type.base, color = c.textMuted) } },
            isError = error != null,
            singleLine = singleLine,
            minLines = minLines,
            shape = Radius.md,
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = imeAction),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = c.focus, unfocusedBorderColor = c.borderInput, errorBorderColor = c.danger,
                disabledBorderColor = c.border, disabledContainerColor = c.surface, cursorColor = c.accent,
            ),
        )
        when {
            error != null -> Row(Modifier.padding(top = Space.s1), horizontalArrangement = Arrangement.spacedBy(Space.s1),
                verticalAlignment = Alignment.CenterVertically) {
                SiteIcon(R.drawable.ic_circle_alert, null, tint = c.danger, size = 16.dp)
                Text(error, style = SiteTheme.type.xs, color = c.danger)
            }
            helper != null -> Text(helper, Modifier.padding(top = Space.s1), style = SiteTheme.type.xs, color = c.textMuted)
        }
    }
}

@Composable
fun SearchField(value: String, onValueChange: (String) -> Unit, placeholder: String, modifier: Modifier = Modifier) {
    val c = SiteTheme.colors
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth().semantics { contentDescription = placeholder },
        singleLine = true,
        textStyle = SiteTheme.type.base.copy(color = c.text),
        placeholder = { Text(placeholder, style = SiteTheme.type.base, color = c.textSubtle, maxLines = 1) },
        leadingIcon = { SiteIcon(R.drawable.ic_search, null, tint = c.textMuted) },
        trailingIcon = if (value.isEmpty()) null else {
            { SiteIconButton(R.drawable.ic_x, stringResource(R.string.a11y_clear_search), { onValueChange("") }, tint = c.textMuted) }
        },
        shape = Radius.md,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = c.focus, unfocusedBorderColor = c.borderInput, cursorColor = c.accent),
    )
}

internal fun LocalDate.toUtcMillis(): Long = atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
internal fun Long.toLocalDateUtc(): LocalDate = Instant.ofEpochMilli(this).atZone(ZoneOffset.UTC).toLocalDate()

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DatePickerSheet(
    initial: LocalDate?,
    onDismiss: () -> Unit,
    onPick: (LocalDate) -> Unit,
    min: LocalDate? = null,
    max: LocalDate? = null,
) {
    val state = rememberDatePickerState(
        initialSelectedDateMillis = initial?.toUtcMillis(),
        selectableDates = object : SelectableDates {
            override fun isSelectableDate(utcTimeMillis: Long): Boolean {
                val d = utcTimeMillis.toLocalDateUtc()
                return (min == null || !d.isBefore(min)) && (max == null || !d.isAfter(max))
            }
        },
    )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = { state.selectedDateMillis?.let { onPick(it.toLocalDateUtc()) }; onDismiss() }) {
                Text(stringResource(R.string.action_ok))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    ) { DatePicker(state = state) }
}

/** Looks like a text field; opens the date picker. [onClear] shows a clear button when set. */
@Composable
fun DateField(
    label: String,
    date: LocalDate?,
    onPick: (LocalDate) -> Unit,
    modifier: Modifier = Modifier,
    error: String? = null,
    placeholder: String = "",
    min: LocalDate? = null,
    max: LocalDate? = null,
    onClear: (() -> Unit)? = null,
) {
    val c = SiteTheme.colors
    var open by remember { mutableStateOf(false) }
    Column(modifier) {
        Text(label, style = SiteTheme.type.sm.copy(fontWeight = FontWeight.W600), color = c.textMuted)
        Row(
            Modifier.padding(top = Space.s1).fillMaxWidth().height(Size.control).clip(Radius.md)
                .border(if (error != null) 2.dp else 1.dp, if (error != null) c.danger else c.borderInput, Radius.md)
                .clickable(role = Role.Button, onClickLabel = label) { open = true }
                .padding(start = Space.s3),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                date?.let { Format.long(it) } ?: placeholder,
                Modifier.weight(1f),
                style = SiteTheme.type.base,
                color = if (date == null) c.textSubtle else c.text,
                maxLines = 1,
            )
            if (date != null && onClear != null) SiteIconButton(R.drawable.ic_x, stringResource(R.string.a11y_clear_date), onClear, tint = c.textMuted)
            else SiteIcon(R.drawable.ic_calendar, null, tint = c.textMuted, modifier = Modifier.padding(end = Space.s3))
        }
        if (error != null) Text(error, Modifier.padding(top = Space.s1), style = SiteTheme.type.xs, color = c.danger)
    }
    if (open) DatePickerSheet(date, { open = false }, onPick, min, max)
}

/** ‹ Mon, 12 Oct 2026 ›. Next is disabled on [max] (today). Tapping the date opens the picker. */
@Composable
fun DateStepper(date: LocalDate, onChange: (LocalDate) -> Unit, max: LocalDate, modifier: Modifier = Modifier) {
    val c = SiteTheme.colors
    var open by remember { mutableStateOf(false) }
    Row(
        modifier.fillMaxWidth().height(Size.control).clip(Radius.md).border(1.dp, c.borderInput, Radius.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SiteIconButton(R.drawable.ic_chevron_left, stringResource(R.string.a11y_previous_day), { onChange(date.minusDays(1)) })
        Text(
            Format.day(date),
            Modifier.weight(1f).clickable(role = Role.Button) { open = true },
            style = SiteTheme.type.base.copy(fontWeight = FontWeight.W600),
            color = c.text,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        SiteIconButton(R.drawable.ic_chevron_right, stringResource(R.string.a11y_next_day), { onChange(date.plusDays(1)) },
            enabled = date.isBefore(max))
    }
    if (open) DatePickerSheet(date, { open = false }, onChange, max = max)
}
