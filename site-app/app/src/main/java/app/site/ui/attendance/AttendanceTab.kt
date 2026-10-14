package app.site.ui.attendance

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.site.LocalContainer
import app.site.R
import app.site.core.logic.Format
import app.site.core.logic.Wages
import app.site.core.model.Attendance
import app.site.core.model.AttendanceStatus
import app.site.core.model.Project
import app.site.core.model.WageType
import app.site.core.model.Worker
import app.site.design.SiteTheme
import app.site.design.Size
import app.site.design.Space
import app.site.design.components.Avatar
import app.site.design.components.DateStepper
import app.site.design.components.EmptyState
import app.site.design.components.InfoBanner
import app.site.design.components.ListRow
import app.site.design.components.LoadingBlock
import app.site.design.components.PrimaryButton
import app.site.design.components.SearchField
import app.site.design.components.SecondaryButton
import app.site.design.components.SegmentedToggle
import app.site.design.components.SiteFab
import app.site.design.components.SiteTextField
import app.site.design.components.Tabs
import app.site.design.components.TextAction
import app.site.design.components.Tone
import kotlinx.coroutines.launch

/** S21 (register) and S23 (wages so far). */
@Composable
fun AttendanceTab(project: Project, onAddWorker: () -> Unit, onSettings: () -> Unit) {
    var pane by rememberSaveable { mutableIntStateOf(0) }
    Column(Modifier.fillMaxSize()) {
        Tabs(listOf(stringResource(R.string.attendance_register), stringResource(R.string.attendance_wages)), pane, { pane = it })
        if (pane == 0) Register(project, onAddWorker, onSettings) else WagesPane(project)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Register(project: Project, onAddWorker: () -> Unit, onSettings: () -> Unit) {
    val container = LocalContainer.current
    val scope = rememberCoroutineScope()
    val today = container.today()
    var date by rememberSaveable { mutableStateOf(today) }
    var query by rememberSaveable { mutableStateOf("") }
    var editing by remember { mutableStateOf<Worker?>(null) }

    val workersFlow = remember(project.id) { container.attendance.workers(project.id) }
    val workers by workersFlow.collectAsStateWithLifecycle(initialValue = null)
    val dayFlow = remember(project.id, date) { container.attendance.day(project.id, date) }
    val day by dayFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    val byWorker = day.associateBy { it.workerId }

    fun mark(w: Worker, status: AttendanceStatus) = scope.launch {
        val prev = byWorker[w.id]
        container.attendance.mark(project.id, w.id, date, status, prev?.otHours ?: 0.0, prev?.remarks)
    }

    Box(Modifier.fillMaxSize()) {
        val list = workers
        when {
            list == null -> LoadingBlock()
            list.isEmpty() -> EmptyState(R.drawable.ic_users, stringResource(R.string.attendance_empty_title),
                stringResource(R.string.attendance_empty_body), action = stringResource(R.string.action_add_worker), onAction = onAddWorker)
            else -> {
                val shown = list.filter { query.isBlank() || it.name.contains(query.trim(), true) || it.skill.orEmpty().contains(query.trim(), true) }
                val onSite = day.count { it.status != AttendanceStatus.ABSENT }
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 96.dp)) {
                    item {
                        Column(Modifier.padding(horizontal = Size.gutter, vertical = Space.s3), verticalArrangement = Arrangement.spacedBy(Space.s3)) {
                            DateStepper(date, { date = it }, max = today)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(stringResource(R.string.attendance_on_site, onSite, list.size), Modifier.weight(1f),
                                    style = SiteTheme.type.base.copy(fontWeight = FontWeight.W600), color = SiteTheme.colors.text)
                                TextAction(stringResource(R.string.action_mark_all_present), {
                                    scope.launch {
                                        list.filter { byWorker[it.id] == null }.forEach { w ->
                                            container.attendance.mark(project.id, w.id, date, AttendanceStatus.PRESENT)
                                        }
                                    }
                                }, enabled = list.any { byWorker[it.id] == null })
                            }
                            if (list.size > 6) SearchField(query, { query = it }, stringResource(R.string.attendance_search))
                        }
                    }
                    items(shown, key = { it.id }) { w ->
                        WorkerRow(w, byWorker[w.id], onMark = { mark(w, it) }, onMore = { editing = w })
                    }
                    item {
                        Box(Modifier.fillMaxWidth().padding(Size.gutter), contentAlignment = Alignment.Center) {
                            TextAction(stringResource(R.string.action_attendance_settings), onSettings, icon = R.drawable.ic_clock)
                        }
                    }
                }
            }
        }
        if (!workers.isNullOrEmpty()) {
            SiteFab(stringResource(R.string.action_add_worker), R.drawable.ic_plus, onAddWorker,
                Modifier.align(Alignment.BottomEnd).padding(Size.gutter))
        }
    }

    editing?.let { w ->
        val current = byWorker[w.id]
        var ot by remember(w.id) { mutableStateOf(current?.otHours?.takeIf { it > 0 }?.let { Format.qty(it) } ?: "") }
        var note by remember(w.id) { mutableStateOf(current?.remarks.orEmpty()) }
        ModalBottomSheet(onDismissRequest = { editing = null }, containerColor = SiteTheme.colors.bg) {
            Column(
                Modifier.navigationBarsPadding().padding(horizontal = Size.gutter, vertical = Space.s2),
                verticalArrangement = Arrangement.spacedBy(Space.s4),
            ) {
                Text(w.name, style = SiteTheme.type.lg, color = SiteTheme.colors.text)
                Text(Format.day(date), style = SiteTheme.type.sm, color = SiteTheme.colors.textMuted)
                val otVal = Format.parseQty(ot)
                val otBad = ot.isNotBlank() && (otVal == null || otVal > 16)
                SiteTextField(ot, { ot = it }, stringResource(R.string.field_ot_hours), keyboardType = KeyboardType.Decimal,
                    suffix = stringResource(R.string.unit_hours_short), error = if (otBad) stringResource(R.string.err_ot_hours) else null)
                if (project.attendanceRemarks) {
                    SiteTextField(note, { note = it }, stringResource(R.string.field_work_note),
                        placeholder = stringResource(R.string.field_work_note_placeholder), singleLine = false, minLines = 2)
                }
                PrimaryButton(stringResource(R.string.action_save), {
                    scope.launch {
                        container.attendance.mark(project.id, w.id, date, current?.status ?: AttendanceStatus.PRESENT, otVal ?: 0.0, note)
                        editing = null
                    }
                }, large = true, enabled = !otBad)
                if (current != null) {
                    SecondaryButton(stringResource(R.string.action_clear_mark), {
                        scope.launch { container.attendance.clear(project.id, w.id, date); editing = null }
                    }, Modifier.fillMaxWidth(), icon = R.drawable.ic_undo_2)
                }
            }
        }
    }
}

@Composable
private fun WorkerRow(w: Worker, a: Attendance?, onMark: (AttendanceStatus) -> Unit, onMore: () -> Unit) {
    val c = SiteTheme.colors
    val present = stringResource(R.string.att_present)
    val half = stringResource(R.string.att_half_day)
    val absent = stringResource(R.string.att_absent)
    ListRow(onClick = onMore) {
        Avatar(w.name)
        Column(Modifier.weight(1f).padding(horizontal = Space.s3)) {
            Text(w.name, style = SiteTheme.type.base, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val wage = if (w.wageType == WageType.DAILY) stringResource(R.string.wage_per_day, Format.rupees(w.wagePaise))
            else stringResource(R.string.wage_per_hour, Format.rupees(w.wagePaise))
            val extra = listOfNotNull(
                a?.otHours?.takeIf { it > 0 }?.let { stringResource(R.string.att_ot_short, Format.qty(it)) },
                a?.remarks?.let { "“$it”" },
            )
            Text((listOfNotNull(w.skill, wage) + extra).joinToString(" · "), style = SiteTheme.type.sm, color = c.textMuted,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        SegmentedToggle(
            options = listOf(AttendanceStatus.PRESENT to "P", AttendanceStatus.HALF_DAY to "½", AttendanceStatus.ABSENT to "A"),
            selected = a?.status,
            onSelect = onMark,
            descriptionFor = { s -> "${w.name}: " + when (s) { AttendanceStatus.PRESENT -> present; AttendanceStatus.HALF_DAY -> half; AttendanceStatus.ABSENT -> absent } },
            selectedTone = { s -> when (s) { AttendanceStatus.ABSENT -> Tone.DANGER; AttendanceStatus.HALF_DAY -> Tone.WARNING; else -> Tone.SUCCESS } },
        )
    }
}

/** S23 (part): wages earned in the last 7 days, from attendance. Recording payments comes later. */
@Composable
private fun WagesPane(project: Project) {
    val container = LocalContainer.current
    val to = container.today()
    val from = to.minusDays(6)
    val workersFlow = remember(project.id) { container.attendance.workers(project.id) }
    val workers by workersFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    val rangeFlow = remember(project.id, from, to) { container.attendance.range(project.id, from, to) }
    val records by rangeFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    val lines = Wages.lines(workers, records, project.shiftHours)
    val c = SiteTheme.colors

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = Space.s7)) {
        item {
            Column(Modifier.padding(Size.gutter), verticalArrangement = Arrangement.spacedBy(Space.s2)) {
                Text(stringResource(R.string.wages_period, Format.range(from, to, to)), style = SiteTheme.type.sm, color = c.textMuted)
                Text(Format.rupees(lines.sumOf { it.amountPaise }), style = SiteTheme.type.xl, color = c.text)
                InfoBanner(stringResource(R.string.wages_note))
            }
        }
        if (lines.isEmpty()) item {
            EmptyState(R.drawable.ic_indian_rupee, stringResource(R.string.wages_empty_title), stringResource(R.string.wages_empty_body))
        }
        items(lines, key = { it.worker.id }) { l ->
            ListRow {
                Column(Modifier.weight(1f)) {
                    Text(l.worker.name, style = SiteTheme.type.base, color = c.text)
                    val ot = if (l.otHours > 0) " · " + stringResource(R.string.att_ot_short, Format.qty(l.otHours)) else ""
                    Text(stringResource(R.string.wages_days, Format.qty(l.days)) + ot, style = SiteTheme.type.sm, color = c.textMuted)
                }
                Text(Format.rupees(l.amountPaise), style = SiteTheme.type.base.copy(fontWeight = FontWeight.W600), color = c.text)
            }
        }
    }
}
