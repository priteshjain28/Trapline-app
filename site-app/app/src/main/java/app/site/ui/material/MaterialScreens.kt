package app.site.ui.material

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.site.LocalContainer
import app.site.R
import app.site.core.data.AppError
import app.site.core.logic.Format
import app.site.core.model.AdjustmentKind
import app.site.core.model.Material
import app.site.core.model.MaterialLine
import app.site.core.model.NewAdjustment
import app.site.core.model.NewGrn
import app.site.core.model.NewMaterial
import app.site.core.model.NewMaterialIssue
import app.site.core.model.StockEntry
import app.site.core.model.StockEntryKind
import app.site.core.model.StockItem
import app.site.core.model.Task
import app.site.design.Radius
import app.site.design.SiteTheme
import app.site.design.Size
import app.site.design.Space
import app.site.design.components.AppBarSub
import app.site.design.components.DateField
import app.site.design.components.EmptyState
import app.site.design.components.FilterChip
import app.site.design.components.InfoBanner
import app.site.design.components.ListRow
import app.site.design.components.LoadingBlock
import app.site.design.components.PrimaryButton
import app.site.design.components.SearchField
import app.site.design.components.SectionLabel
import app.site.design.components.SecondaryButton
import app.site.design.components.SegmentedControl
import app.site.design.components.SiteCard
import app.site.design.components.SiteIcon
import app.site.design.components.SiteIconButton
import app.site.design.components.SiteTextField
import app.site.design.components.TextAction
import app.site.design.components.Tone
import app.site.ui.errorRes
import app.site.ui.fieldError
import app.site.ui.generalError
import kotlinx.coroutines.launch
import java.time.LocalDate

private val COMMON_UNITS = listOf("bag", "kg", "nos", "m3", "cft", "ton", "ltr", "m")

// ================================================================ add material already on site

/** Material that's already lying on site when you start: it goes into the catalogue with an opening quantity. */
@Composable
fun AddMaterialScreen(projectId: String, onBack: () -> Unit, onDone: () -> Unit) {
    val container = LocalContainer.current
    val scope = rememberCoroutineScope()
    val catalogue by remember { container.materials.catalogue() }.collectAsStateWithLifecycle(initialValue = emptyList())
    var name by rememberSaveable { mutableStateOf("") }
    var spec by rememberSaveable { mutableStateOf("") }
    var unit by rememberSaveable { mutableStateOf("") }
    var qty by rememberSaveable { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<Throwable?>(null) }
    val q = Format.parseQty(qty)
    val qtyBad = qty.isNotBlank() && (q == null || q <= 0)

    Scaffold(
        topBar = { AppBarSub(stringResource(R.string.mat_add_title), onBack) },
        bottomBar = {
            Box(Modifier.navigationBarsPadding().imePadding().padding(Size.gutter)) {
                PrimaryButton(stringResource(R.string.action_add_material), {
                    busy = true
                    scope.launch {
                        val r = container.materials.addMaterial(NewMaterial(name, spec, unit)).mapCatching { m ->
                            container.materials.adjust(NewAdjustment(projectId, m.id, AdjustmentKind.OPENING, q ?: 0.0, container.today())).getOrThrow()
                        }
                        r.onSuccess { onDone() }.onFailure { error = it }
                        busy = false
                    }
                }, large = true, loading = busy, enabled = name.isNotBlank() && unit.isNotBlank() && q != null && q > 0)
            }
        },
        containerColor = SiteTheme.colors.bg,
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(Size.gutter),
            verticalArrangement = Arrangement.spacedBy(Space.s4),
        ) {
            Text(stringResource(R.string.mat_add_help), style = SiteTheme.type.sm, color = SiteTheme.colors.textMuted)
            generalError(error)?.let { InfoBanner(it, tone = Tone.DANGER, icon = R.drawable.ic_circle_alert) }
            SiteTextField(name, { name = it; error = null }, stringResource(R.string.field_material_name), required = true,
                placeholder = stringResource(R.string.field_material_name_placeholder), error = fieldError(error, "name"))
            // reuse what the company already has, so "cement" isn't in the list five ways
            val matches = if (name.trim().length < 2) emptyList()
                else catalogue.filter { it.name.contains(name.trim(), true) && !(it.name == name && it.spec == spec && it.uom == unit) }.take(3)
            if (matches.isNotEmpty()) {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(Space.s2)) {
                    matches.forEach { m -> FilterChip("${m.label} (${m.uom})", false, { name = m.name; spec = m.spec; unit = m.uom }) }
                }
            }
            SiteTextField(spec, { spec = it }, stringResource(R.string.field_spec),
                placeholder = stringResource(R.string.field_spec_placeholder), error = fieldError(error, "spec"))
            UnitField(unit, { unit = it }, fieldError(error, "uom"))
            SiteTextField(qty, { qty = it; error = null }, stringResource(R.string.field_qty_on_site), required = true,
                keyboardType = KeyboardType.Decimal, suffix = unit.ifBlank { null },
                error = if (qtyBad) stringResource(R.string.err_not_positive) else fieldError(error, "qty"))
        }
    }
}

@Composable
private fun UnitField(unit: String, onChange: (String) -> Unit, error: String?) {
    Column(verticalArrangement = Arrangement.spacedBy(Space.s2)) {
        SiteTextField(unit, { onChange(it.take(12)) }, stringResource(R.string.field_unit), required = true,
            placeholder = stringResource(R.string.field_unit_placeholder), error = error)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(Space.s2)) {
            COMMON_UNITS.forEach { u -> FilterChip(u, unit.equals(u, true), { onChange(u) }) }
        }
    }
}

// ================================================================ lines shared by deliveries and issues

/** One row on a delivery or an issue while it's being filled in. */
private data class Draft(val materialId: String?, val qty: String)

private val DraftsSaver = Saver<List<Draft>, ArrayList<String>>(
    save = { ds -> ArrayList(ds.flatMap { listOf(it.materialId.orEmpty(), it.qty) }) },
    restore = { flat -> flat.chunked(2).map { (id, q) -> Draft(id.ifEmpty { null }, q) } },
)

/**
 * The material lines. [available] limits picking to what's on site and shows how much (issues); null means any
 * material, including a new one typed in the picker (deliveries).
 */
@Composable
private fun LinesEditor(
    drafts: List<Draft>,
    onChange: (List<Draft>) -> Unit,
    byId: Map<String, Material>,
    available: Map<String, Double>?,
    error: String?,
) {
    var picking by remember { mutableStateOf<Int?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(Space.s3)) {
        drafts.forEachIndexed { i, d ->
            val m = d.materialId?.let { byId[it] }
            val q = Format.parseQty(d.qty)
            val have = d.materialId?.let { available?.get(it) }
            val lineError = when {
                d.qty.isNotBlank() && (q == null || q <= 0) -> stringResource(R.string.err_not_positive)
                have != null && q != null && q > have + 0.0005 -> stringResource(R.string.err_not_enough_stock)
                else -> null
            }
            SiteCard {
                Column(Modifier.padding(Space.s3), verticalArrangement = Arrangement.spacedBy(Space.s2)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        PickerField(stringResource(R.string.field_material_name), m?.label, stringResource(R.string.field_material_pick),
                            { picking = i }, Modifier.weight(1f))
                        if (drafts.size > 1) {
                            SiteIconButton(R.drawable.ic_x, stringResource(R.string.a11y_remove_line), { onChange(drafts.filterIndexed { j, _ -> j != i }) })
                        }
                    }
                    if (have != null && m != null) {
                        Text(stringResource(R.string.mat_available, Format.qty(have), m.uom), style = SiteTheme.type.sm, color = SiteTheme.colors.textMuted)
                    }
                    SiteTextField(d.qty, { v -> onChange(drafts.mapIndexed { j, x -> if (j == i) x.copy(qty = v) else x }) },
                        stringResource(R.string.field_qty), keyboardType = KeyboardType.Decimal, suffix = m?.uom, error = lineError)
                }
            }
        }
        error?.let { Text(it, style = SiteTheme.type.sm, color = SiteTheme.colors.danger) }
        TextAction(stringResource(R.string.action_add_line), { onChange(drafts + Draft(null, "")) }, icon = R.drawable.ic_plus)
    }
    picking?.let { i ->
        MaterialPicker(
            available = available,
            byId = byId,
            onDismiss = { picking = null },
            onPick = { id -> onChange(drafts.mapIndexed { j, x -> if (j == i) x.copy(materialId = id) else x }); picking = null },
        )
    }
}

/** True when every line has a material and a valid quantity, and none is over what's [available]. */
private fun linesReady(drafts: List<Draft>, available: Map<String, Double>?): Boolean = drafts.isNotEmpty() && drafts.all { d ->
    val q = Format.parseQty(d.qty)
    d.materialId != null && q != null && q > 0 && (available == null || q <= (available[d.materialId] ?: 0.0) + 0.0005)
}

private fun lines(drafts: List<Draft>) = drafts.map { MaterialLine(it.materialId!!, Format.parseQty(it.qty)!!) }

/** Looks like a field, opens a picker. Named for screen readers by its label and current value. */
@Composable
internal fun PickerField(label: String, value: String?, placeholder: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = SiteTheme.colors
    Column(modifier, verticalArrangement = Arrangement.spacedBy(Space.s1)) {
        Text(label, style = SiteTheme.type.sm.copy(fontWeight = FontWeight.W600), color = c.text)
        Row(
            Modifier.fillMaxWidth().heightIn(min = Size.control).clip(Radius.md).border(1.dp, c.borderInput, Radius.md)
                .clickable(role = Role.Button, onClick = onClick).semantics { contentDescription = "$label: ${value ?: placeholder}" }
                .padding(horizontal = Space.s3),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(value ?: placeholder, Modifier.weight(1f), style = SiteTheme.type.base,
                color = if (value == null) c.textMuted else c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            SiteIcon(R.drawable.ic_chevron_down, null, tint = c.textMuted, size = Size.iconSm)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MaterialPicker(available: Map<String, Double>?, byId: Map<String, Material>, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    val container = LocalContainer.current
    val scope = rememberCoroutineScope()
    var query by rememberSaveable { mutableStateOf("") }
    var name by rememberSaveable { mutableStateOf("") }
    var spec by rememberSaveable { mutableStateOf("") }
    var unit by rememberSaveable { mutableStateOf("") }
    var error by remember { mutableStateOf<Throwable?>(null) }
    val options = (if (available == null) byId.values else available.filter { it.value > 0 }.keys.mapNotNull { byId[it] })
        .filter { query.isBlank() || it.label.contains(query.trim(), true) }
        .sortedBy { it.label.lowercase() }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = SiteTheme.colors.bg) {
        LazyColumn(Modifier.navigationBarsPadding().imePadding(), contentPadding = PaddingValues(bottom = Space.s4)) {
            item {
                Column(Modifier.padding(horizontal = Size.gutter), verticalArrangement = Arrangement.spacedBy(Space.s2)) {
                    Text(stringResource(R.string.mat_pick_title), style = SiteTheme.type.lg, color = SiteTheme.colors.text)
                    if (available != null) Text(stringResource(R.string.mat_pick_only_stock), style = SiteTheme.type.sm, color = SiteTheme.colors.textMuted)
                    SearchField(query, { query = it }, stringResource(R.string.mat_search))
                }
            }
            if (options.isEmpty()) item {
                Text(stringResource(R.string.mat_pick_none), Modifier.padding(Size.gutter), style = SiteTheme.type.sm, color = SiteTheme.colors.textMuted)
            }
            items(options, key = { it.id }) { m ->
                ListRow(onClick = { onPick(m.id) }) {
                    Column(Modifier.weight(1f)) {
                        Text(m.label, style = SiteTheme.type.base, color = SiteTheme.colors.text)
                        val have = available?.get(m.id)
                        Text(if (have != null) stringResource(R.string.mat_available, Format.qty(have), m.uom) else m.uom,
                            style = SiteTheme.type.sm, color = SiteTheme.colors.textMuted)
                    }
                }
            }
            // deliveries can bring something new
            if (available == null) item {
                Column(Modifier.padding(horizontal = Size.gutter, vertical = Space.s3), verticalArrangement = Arrangement.spacedBy(Space.s3)) {
                    SectionLabel(stringResource(R.string.action_new_material))
                    SiteTextField(name, { name = it; error = null }, stringResource(R.string.field_material_name),
                        placeholder = stringResource(R.string.field_material_name_placeholder), error = fieldError(error, "name"))
                    SiteTextField(spec, { spec = it }, stringResource(R.string.field_spec), placeholder = stringResource(R.string.field_spec_placeholder))
                    UnitField(unit, { unit = it }, fieldError(error, "uom"))
                    SecondaryButton(stringResource(R.string.action_new_material), {
                        scope.launch {
                            container.materials.addMaterial(NewMaterial(name, spec, unit)).onSuccess { onPick(it.id) }.onFailure { error = it }
                        }
                    }, Modifier.fillMaxWidth(), enabled = name.isNotBlank() && unit.isNotBlank(), icon = R.drawable.ic_plus)
                }
            }
        }
    }
}

// ================================================================ S14 record a delivery

@Composable
fun ReceiveScreen(projectId: String, onBack: () -> Unit, onDone: () -> Unit) {
    val container = LocalContainer.current
    val scope = rememberCoroutineScope()
    val catalogue by remember { container.materials.catalogue() }.collectAsStateWithLifecycle(initialValue = emptyList())
    var date by rememberSaveable { mutableStateOf(container.today()) }
    var challan by rememberSaveable { mutableStateOf("") }
    var notes by rememberSaveable { mutableStateOf("") }
    var drafts by rememberSaveable(stateSaver = DraftsSaver) { mutableStateOf(listOf(Draft(null, ""))) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<Throwable?>(null) }

    Scaffold(
        topBar = { AppBarSub(stringResource(R.string.mat_receive_title), onBack) },
        bottomBar = {
            Box(Modifier.navigationBarsPadding().imePadding().padding(Size.gutter)) {
                PrimaryButton(stringResource(R.string.action_save), {
                    busy = true
                    scope.launch {
                        container.materials.receive(NewGrn(projectId, date, lines(drafts), challan, notes))
                            .onSuccess { onDone() }.onFailure { error = it }
                        busy = false
                    }
                }, large = true, loading = busy, enabled = linesReady(drafts, null))
            }
        },
        containerColor = SiteTheme.colors.bg,
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(Size.gutter),
            verticalArrangement = Arrangement.spacedBy(Space.s4),
        ) {
            generalError(error)?.let { InfoBanner(it, tone = Tone.DANGER, icon = R.drawable.ic_circle_alert) }
            Row(horizontalArrangement = Arrangement.spacedBy(Space.s3)) {
                DateField(stringResource(R.string.field_received_on), date, { date = it; error = null }, Modifier.weight(1f),
                    max = container.today(), error = fieldError(error, "date"))
                SiteTextField(challan, { challan = it.take(40) }, stringResource(R.string.field_challan), Modifier.weight(1f))
            }
            LinesEditor(drafts, { drafts = it; error = null }, catalogue.associateBy { it.id }, null,
                fieldError(error, "qty") ?: fieldError(error, "lines"))
            SiteTextField(notes, { notes = it }, stringResource(R.string.field_notes),
                placeholder = stringResource(R.string.field_notes_placeholder), singleLine = false, minLines = 2)
        }
    }
}

// ================================================================ S19 issue material to a task

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IssueScreen(projectId: String, onBack: () -> Unit, onDone: () -> Unit) {
    val container = LocalContainer.current
    val scope = rememberCoroutineScope()
    val catalogue by remember { container.materials.catalogue() }.collectAsStateWithLifecycle(initialValue = emptyList())
    val stock by remember(projectId) { container.materials.stock(projectId) }.collectAsStateWithLifecycle(initialValue = null)
    val tasks by remember(projectId) { container.tasks.tasks(projectId) }.collectAsStateWithLifecycle(initialValue = emptyList())
    var date by rememberSaveable { mutableStateOf(container.today()) }
    var taskId by rememberSaveable { mutableStateOf<String?>(null) }
    var drafts by rememberSaveable(stateSaver = DraftsSaver) { mutableStateOf(listOf(Draft(null, ""))) }
    var pickingTask by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<Throwable?>(null) }
    val available = stock?.associate { it.material.id to it.inStock }.orEmpty()
    val task = tasks.firstOrNull { it.id == taskId }

    Scaffold(
        topBar = { AppBarSub(stringResource(R.string.mat_issue_screen_title), onBack) },
        bottomBar = {
            Box(Modifier.navigationBarsPadding().imePadding().padding(Size.gutter)) {
                PrimaryButton(stringResource(R.string.action_issue_material), {
                    busy = true
                    scope.launch {
                        container.materials.issue(NewMaterialIssue(projectId, taskId.orEmpty(), date, lines(drafts)))
                            .onSuccess { onDone() }.onFailure { error = it }
                        busy = false
                    }
                }, large = true, loading = busy, enabled = taskId != null && linesReady(drafts, available))
            }
        },
        containerColor = SiteTheme.colors.bg,
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(Size.gutter),
            verticalArrangement = Arrangement.spacedBy(Space.s4),
        ) {
            generalError(error)?.let { InfoBanner(it, tone = Tone.DANGER, icon = R.drawable.ic_circle_alert) }
            when {
                stock == null -> LoadingBlock()
                stock!!.none { it.inStock > 0 } -> EmptyState(R.drawable.ic_warehouse, stringResource(R.string.mat_none_title),
                    stringResource(R.string.mat_none_body))
                tasks.isEmpty() -> InfoBanner(stringResource(R.string.mat_tasks_none), icon = R.drawable.ic_info)
                else -> {
                    PickerField(stringResource(R.string.field_task), task?.let(::taskLabel), stringResource(R.string.field_task_pick),
                        { pickingTask = true })
                    fieldError(error, "task")?.let { Text(it, style = SiteTheme.type.sm, color = SiteTheme.colors.danger) }
                    DateField(stringResource(R.string.field_issued_on), date, { date = it; error = null },
                        max = container.today(), error = fieldError(error, "date"))
                    LinesEditor(drafts, { drafts = it; error = null }, catalogue.associateBy { it.id }, available,
                        fieldError(error, "qty") ?: fieldError(error, "lines"))
                }
            }
        }
    }
    if (pickingTask) {
        ModalBottomSheet(onDismissRequest = { pickingTask = false }, containerColor = SiteTheme.colors.bg) {
            LazyColumn(Modifier.navigationBarsPadding()) {
                item {
                    Text(stringResource(R.string.field_task_pick), Modifier.padding(horizontal = Size.gutter, vertical = Space.s2),
                        style = SiteTheme.type.lg, color = SiteTheme.colors.text)
                }
                items(tasks, key = { it.id }) { t ->
                    ListRow(onClick = { taskId = t.id; pickingTask = false; error = null }) {
                        Text(taskLabel(t), Modifier.weight(1f), style = SiteTheme.type.base, color = SiteTheme.colors.text,
                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                        if (t.id == taskId) SiteIcon(R.drawable.ic_check, null, tint = SiteTheme.colors.accent)
                    }
                }
            }
        }
    }
}

private fun taskLabel(t: Task) = (t.number?.let { "#$it " } ?: "") + t.name

// ================================================================ update stock: used or counted

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UpdateStockSheet(projectId: String, item: StockItem, onDismiss: () -> Unit) {
    val container = LocalContainer.current
    val scope = rememberCoroutineScope()
    var mode by rememberSaveable { mutableIntStateOf(0) }   // 0 used, 1 counted
    var qty by rememberSaveable { mutableStateOf("") }
    var note by rememberSaveable { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<Throwable?>(null) }
    val q = Format.parseQty(qty)
    val m = item.material
    val localError = when {
        qty.isBlank() -> null
        q == null || (mode == 0 && q <= 0) -> stringResource(R.string.err_not_positive)
        mode == 0 && q > item.inStock + 0.0005 -> stringResource(R.string.err_not_enough_stock)
        else -> null
    }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = SiteTheme.colors.bg) {
        Column(
            Modifier.navigationBarsPadding().imePadding().padding(horizontal = Size.gutter, vertical = Space.s2),
            verticalArrangement = Arrangement.spacedBy(Space.s4),
        ) {
            Text(stringResource(R.string.mat_update_title, m.label), style = SiteTheme.type.lg, color = SiteTheme.colors.text)
            Text(stringResource(R.string.mat_in_stock, Format.qty(item.inStock), m.uom), style = SiteTheme.type.sm, color = SiteTheme.colors.textMuted)
            SegmentedControl(listOf(stringResource(R.string.mat_update_used), stringResource(R.string.mat_update_counted)), mode,
                { mode = it; error = null })
            Text(stringResource(if (mode == 0) R.string.mat_update_used_help else R.string.mat_update_counted_help),
                style = SiteTheme.type.sm, color = SiteTheme.colors.textMuted)
            SiteTextField(qty, { qty = it; error = null }, stringResource(if (mode == 0) R.string.field_used else R.string.field_counted),
                keyboardType = KeyboardType.Decimal, suffix = m.uom,
                error = localError ?: (error as? AppError.Invalid)?.let { stringResource(errorRes(it)) })
            SiteTextField(note, { note = it.take(200) }, stringResource(R.string.field_notes))
            generalError(error)?.let { InfoBanner(it, tone = Tone.DANGER, icon = R.drawable.ic_circle_alert) }
            PrimaryButton(stringResource(R.string.action_save), {
                busy = true
                scope.launch {
                    container.materials.adjust(NewAdjustment(projectId, m.id, if (mode == 0) AdjustmentKind.USED else AdjustmentKind.COUNTED,
                        q ?: 0.0, container.today(), note = note)).onSuccess { onDismiss() }.onFailure { error = it }
                    busy = false
                }
            }, large = true, loading = busy, enabled = q != null && localError == null)
        }
    }
}

// ================================================================ S16 one material's history on this site

@Composable
fun MaterialDetailScreen(projectId: String, materialId: String, onBack: () -> Unit) {
    val container = LocalContainer.current
    val stock by remember(projectId) { container.materials.stock(projectId) }.collectAsStateWithLifecycle(initialValue = null)
    val history by remember(projectId, materialId) { container.materials.history(projectId, materialId) }
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val tasks by remember(projectId) { container.tasks.tasks(projectId) }.collectAsStateWithLifecycle(initialValue = emptyList())
    val item = stock?.firstOrNull { it.material.id == materialId }
    var updating by remember { mutableStateOf(false) }
    val taskNames = tasks.associate { it.id to taskLabel(it) }

    Scaffold(
        topBar = { AppBarSub(item?.material?.name.orEmpty(), onBack, overline = item?.material?.spec?.ifBlank { null }) },
        containerColor = SiteTheme.colors.bg,
    ) { padding ->
        if (item == null) { Box(Modifier.padding(padding)) { LoadingBlock() }; return@Scaffold }
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            item {
                Column(Modifier.padding(Size.gutter), verticalArrangement = Arrangement.spacedBy(Space.s3)) {
                    Text(stringResource(R.string.mat_in_stock, Format.qty(item.inStock), item.material.uom),
                        style = SiteTheme.type.xl.copy(fontWeight = FontWeight.W600), color = SiteTheme.colors.text)
                    item.lastMovementOn?.let {
                        Text(stringResource(R.string.mat_last_moved, Format.day(it)), style = SiteTheme.type.sm, color = SiteTheme.colors.textMuted)
                    }
                    SecondaryButton(stringResource(R.string.action_update_stock), { updating = true }, Modifier.fillMaxWidth(), icon = R.drawable.ic_plus)
                }
            }
            item { SectionLabel(stringResource(R.string.mat_history), Modifier.padding(horizontal = Size.gutter)) }
            if (history.isEmpty()) item {
                Text(stringResource(R.string.mat_history_none), Modifier.padding(Size.gutter), style = SiteTheme.type.sm, color = SiteTheme.colors.textMuted)
            }
            items(history, key = { it.id }) { e -> HistoryRow(e, item.material.uom, taskNames) }
        }
    }
    if (updating && item != null) UpdateStockSheet(projectId, item, onDismiss = { updating = false })
}

@Composable
private fun HistoryRow(e: StockEntry, uom: String, taskNames: Map<String, String>) {
    val c = SiteTheme.colors
    val (icon, title) = when (e.kind) {
        StockEntryKind.RECEIVED -> R.drawable.ic_truck to (e.number?.let { stringResource(R.string.mat_entry_received_no, it.toString()) }
            ?: stringResource(R.string.mat_entry_received))
        StockEntryKind.ISSUED -> R.drawable.ic_package to (e.number?.let { stringResource(R.string.mat_entry_issued_no, it.toString()) }
            ?: stringResource(R.string.mat_entry_issued))
        StockEntryKind.OPENING -> R.drawable.ic_warehouse to stringResource(R.string.mat_entry_opening)
        StockEntryKind.USED -> R.drawable.ic_hard_hat to stringResource(R.string.mat_entry_used)
        StockEntryKind.CORRECTED -> R.drawable.ic_clipboard_check to stringResource(R.string.mat_entry_corrected)
    }
    ListRow {
        SiteIcon(icon, null, tint = c.textMuted)
        Column(Modifier.weight(1f).padding(horizontal = Space.s3)) {
            Text(title, style = SiteTheme.type.base, color = c.text)
            val detail = listOfNotNull(Format.short(e.date), e.taskId?.let { taskNames[it] }, e.note).joinToString(" · ")
            Text(detail, style = SiteTheme.type.sm, color = c.textMuted, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        val sign = if (e.qty > 0) "+" else "−"
        Text("$sign${Format.qty(kotlin.math.abs(e.qty))} $uom", style = SiteTheme.type.base.copy(fontWeight = FontWeight.W600),
            color = if (e.qty > 0) c.success else c.text)
    }
}
