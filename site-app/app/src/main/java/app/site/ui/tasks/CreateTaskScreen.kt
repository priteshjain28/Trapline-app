package app.site.ui.tasks

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import app.site.design.components.TextAction
import app.site.design.components.FilterChip
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.foundation.horizontalScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import app.site.LocalContainer
import app.site.R
import app.site.core.logic.Format
import app.site.core.model.NewTask
import app.site.design.SiteTheme
import app.site.design.Size
import app.site.design.Space
import app.site.design.components.AppBarSub
import app.site.design.components.DateField
import app.site.design.components.InfoBanner
import app.site.design.components.PrimaryButton
import app.site.design.components.SiteTextField
import app.site.design.components.Tone
import app.site.ui.fieldError
import app.site.ui.generalError
import kotlinx.coroutines.launch
import java.time.LocalDate

/** S10: a new task. A planned quantity lets progress be counted in units (m2, bags) instead of guessed in %. */
@Composable
fun CreateTaskScreen(projectId: String, onBack: () -> Unit, onCreated: (String) -> Unit) {
    val container = LocalContainer.current
    val scope = rememberCoroutineScope()
    var name by rememberSaveable { mutableStateOf("") }
    var category by rememberSaveable { mutableStateOf("General") }
    var newCategory by rememberSaveable { mutableStateOf<String?>(null) }
    val categories by remember { container.tasks.categories() }.collectAsStateWithLifecycle(initialValue = listOf("General"))
    var unit by rememberSaveable { mutableStateOf("") }
    var planned by rememberSaveable { mutableStateOf("") }
    var start by rememberSaveable { mutableStateOf<LocalDate?>(null) }
    var due by rememberSaveable { mutableStateOf<LocalDate?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<Throwable?>(null) }
    val plannedQty = Format.parseQty(planned)
    val plannedBad = planned.isNotBlank() && (plannedQty == null || plannedQty <= 0)

    Scaffold(
        topBar = { AppBarSub(stringResource(R.string.new_task_title), onBack) },
        bottomBar = {
            Box(Modifier.navigationBarsPadding().imePadding().padding(Size.gutter)) {
                PrimaryButton(stringResource(R.string.action_add_task), {
                    busy = true
                    scope.launch {
                        container.tasks.create(
                            NewTask(projectId, name, category.ifBlank { "General" }, unit, plannedQty, start, due),
                        ).onSuccess { onCreated(it.id) }.onFailure { error = it }
                        busy = false
                    }
                }, large = true, loading = busy, enabled = name.isNotBlank() && !plannedBad)
            }
        },
        containerColor = SiteTheme.colors.bg,
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(Size.gutter),
            verticalArrangement = Arrangement.spacedBy(Space.s4),
        ) {
            generalError(error)?.let { InfoBanner(it, tone = Tone.DANGER, icon = R.drawable.ic_circle_alert) }
            SiteTextField(name, { name = it; error = null }, stringResource(R.string.field_task_name), required = true,
                placeholder = stringResource(R.string.field_task_name_placeholder), error = fieldError(error, "name"))
            // the company's list, so "Masonry" isn't typed five ways
            Column(verticalArrangement = Arrangement.spacedBy(Space.s2)) {
                androidx.compose.material3.Text(stringResource(R.string.field_category),
                    style = SiteTheme.type.sm.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.W600), color = SiteTheme.colors.text)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(Space.s2)) {
                    (categories + listOf(category)).distinct().forEach { c -> FilterChip(c, c == category, { category = c }) }
                }
                val typed = newCategory
                if (typed == null) {
                    TextAction(stringResource(R.string.action_new_category), { newCategory = "" }, icon = R.drawable.ic_plus)
                } else {
                    Row(verticalAlignment = androidx.compose.ui.Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(Space.s2)) {
                        SiteTextField(typed, { newCategory = it.take(60) }, stringResource(R.string.field_new_category), Modifier.weight(1f),
                            placeholder = stringResource(R.string.field_category_placeholder), error = fieldError(error, "category"))
                        app.site.design.components.SecondaryButton(stringResource(R.string.action_add), {
                            scope.launch {
                                container.tasks.addCategory(typed).onSuccess { category = it; newCategory = null }.onFailure { error = it }
                            }
                        }, enabled = typed.isNotBlank())
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(Space.s3)) {
                SiteTextField(planned, { planned = it }, stringResource(R.string.field_planned_qty), Modifier.weight(1f),
                    keyboardType = KeyboardType.Decimal,
                    error = if (plannedBad) stringResource(R.string.err_not_positive) else fieldError(error, "plannedQty"))
                SiteTextField(unit, { unit = it.take(12) }, stringResource(R.string.field_unit), Modifier.weight(1f),
                    placeholder = stringResource(R.string.field_unit_placeholder))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(Space.s3)) {
                DateField(stringResource(R.string.field_start), start, { start = it; error = null }, Modifier.weight(1f),
                    placeholder = stringResource(R.string.field_date_optional), onClear = { start = null })
                DateField(stringResource(R.string.field_due), due, { due = it; error = null }, Modifier.weight(1f),
                    placeholder = stringResource(R.string.field_date_optional), min = start, onClear = { due = null },
                    error = fieldError(error, "dueDate"))
            }
        }
    }
}
