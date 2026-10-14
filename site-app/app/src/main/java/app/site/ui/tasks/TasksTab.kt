package app.site.ui.tasks

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.site.LocalContainer
import app.site.R
import app.site.core.logic.Format
import app.site.core.model.Project
import app.site.core.model.Task
import app.site.core.model.TaskStatus
import app.site.design.SiteTheme
import app.site.design.Size
import app.site.design.Space
import app.site.design.components.EmptyState
import app.site.design.components.FilterChip
import app.site.design.components.ListRow
import app.site.design.components.LoadingBlock
import app.site.design.components.ProgressRing
import app.site.design.components.SearchField
import app.site.design.components.SiteFab
import app.site.design.components.StatusBadge
import app.site.design.components.TextAction
import app.site.design.components.Tone
import java.time.LocalDate

private enum class TaskFilter(val label: Int) {
    IN_PROGRESS(R.string.filter_in_progress),
    DELAYED(R.string.filter_delayed),
    WORK_ORDER(R.string.filter_work_order),
    OPEN_ISSUES(R.string.filter_open_issues),
}

/** S08: tasks on this site, searchable by number or name, with quick filters. */
@Composable
fun TasksTab(project: Project, onNew: () -> Unit, onOpen: (String) -> Unit) {
    val container = LocalContainer.current
    val flow = remember(project.id) { container.tasks.tasks(project.id) }
    val tasks by flow.collectAsStateWithLifecycle(initialValue = null)
    var query by rememberSaveable { mutableStateOf("") }
    var filters by rememberSaveable { mutableStateOf(setOf<String>()) }
    val today = container.today()

    Box(Modifier.fillMaxSize()) {
        val all = tasks
        when {
            all == null -> LoadingBlock()
            all.isEmpty() -> EmptyState(R.drawable.ic_clipboard_check, stringResource(R.string.tasks_empty_title),
                stringResource(R.string.tasks_empty_body), action = stringResource(R.string.action_add_task), onAction = onNew)
            else -> {
                val shown = all.filter { t -> matches(t, query, filters, today) }
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 96.dp)) {
                    item {
                      Column {
                        Column(Modifier.padding(horizontal = Size.gutter).padding(top = Space.s3)) {
                            SearchField(query, { query = it }, stringResource(R.string.tasks_search))
                        }
                        Row(
                            Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = Size.gutter),
                            horizontalArrangement = Arrangement.spacedBy(Space.s2),
                        ) {
                            TaskFilter.entries.forEach { f ->
                                val on = f.name in filters
                                FilterChip(stringResource(f.label), on, { filters = if (on) filters - f.name else filters + f.name })
                            }
                        }
                      }
                    }
                    if (shown.isEmpty()) item {
                      Column {
                        EmptyState(R.drawable.ic_search, stringResource(R.string.tasks_none_match_title),
                            stringResource(R.string.tasks_none_match_body))
                        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                            TextAction(stringResource(R.string.action_clear_filters), { filters = emptySet(); query = "" })
                        }
                      }
                    }
                    items(shown, key = { it.id }) { t -> TaskRow(t, today) { onOpen(t.id) } }
                }
            }
        }
        if (!tasks.isNullOrEmpty()) {
            SiteFab(stringResource(R.string.action_add_task), R.drawable.ic_plus, onNew,
                Modifier.align(Alignment.BottomEnd).padding(Size.gutter))
        }
    }
}

private fun matches(t: Task, query: String, filters: Set<String>, today: LocalDate): Boolean {
    val raw = query.trim()
    val q = raw.removePrefix("#")
    // "#2" means task number 2 only; plain text matches the name or the number
    val textOk = q.isEmpty() || t.number?.toString() == q || (!raw.startsWith("#") && t.name.contains(q, ignoreCase = true))
    val filterOk = filters.all { f ->
        when (TaskFilter.valueOf(f)) {
            TaskFilter.IN_PROGRESS -> t.status == TaskStatus.IN_PROGRESS
            TaskFilter.DELAYED -> t.isDelayed(today)
            TaskFilter.WORK_ORDER -> t.hasWorkOrder
            TaskFilter.OPEN_ISSUES -> t.openIssues > 0
        }
    }
    return textOk && filterOk
}

@Composable
private fun TaskRow(t: Task, today: LocalDate, onClick: () -> Unit) {
    val c = SiteTheme.colors
    ListRow(onClick = onClick) {
        Column(Modifier.weight(1f).padding(end = Space.s3)) {
            Text(
                (t.number?.let { "#$it " } ?: "") + t.name,
                style = SiteTheme.type.base.copy(fontWeight = FontWeight.W600), color = c.text,
                maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
            val due = t.dueDate?.let { stringResource(R.string.task_due, Format.short(it)) }
            Text(listOfNotNull(t.category, due).joinToString(" · "), style = SiteTheme.type.sm, color = c.textMuted, maxLines = 2,
                overflow = TextOverflow.Ellipsis)
        }
        when {
            t.status == TaskStatus.COMPLETED -> StatusBadge(stringResource(R.string.status_done), Tone.SUCCESS)
            t.isDelayed(today) -> StatusBadge(stringResource(R.string.status_delayed), Tone.DANGER)
            else -> {}
        }
        Row(Modifier.padding(start = Space.s3), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s1)) {
            ProgressRing(t.progressPct)
            Text("${t.progressPct}%", style = SiteTheme.type.sm.copy(fontWeight = FontWeight.W600), color = c.text)
        }
    }
}
