package app.site.ui.issues

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import app.site.core.model.Issue
import app.site.core.model.IssueStatus
import app.site.core.model.NewIssue
import app.site.core.model.Task
import app.site.core.model.User
import app.site.design.SiteTheme
import app.site.design.Size
import app.site.design.Space
import app.site.design.components.AppBarSub
import app.site.design.components.Avatar
import app.site.design.components.DateField
import app.site.design.components.EmptyState
import app.site.design.components.InfoBanner
import app.site.design.components.ListRow
import app.site.design.components.LoadingBlock
import app.site.design.components.PrimaryButton
import app.site.design.components.SearchField
import app.site.design.components.SecondaryButton
import app.site.design.components.SectionLabel
import app.site.design.components.SiteFab
import app.site.design.components.SiteIcon
import app.site.design.components.SiteTextField
import app.site.design.components.StatusBadge
import app.site.design.components.Tabs
import app.site.design.components.Tone
import app.site.ui.fieldError
import app.site.ui.generalError
import app.site.ui.material.PickerField
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId

private fun taskLabel(t: Task) = (t.number?.let { "#$it " } ?: "") + t.name

/** S31: issues on this site, by Open / Closed / All, searchable. */
@Composable
fun IssuesScreen(projectId: String, onBack: () -> Unit, onNew: () -> Unit, onOpen: (String) -> Unit) {
    val container = LocalContainer.current
    val flow = remember(projectId) { container.issues.siteIssues(projectId) }
    val issues by flow.collectAsStateWithLifecycle(initialValue = null)
    val people by remember { container.people.people() }.collectAsStateWithLifecycle(initialValue = emptyMap())
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var query by rememberSaveable { mutableStateOf("") }
    val today = container.today()

    Scaffold(
        topBar = { AppBarSub(stringResource(R.string.screen_issues), onBack) },
        floatingActionButton = { SiteFab(stringResource(R.string.action_raise_issue), R.drawable.ic_plus, onNew) },
        containerColor = SiteTheme.colors.bg,
    ) { padding ->
        val all = issues
        if (all == null) { Box(Modifier.padding(padding)) { LoadingBlock() }; return@Scaffold }
        val open = all.filter { it.status == IssueStatus.OPEN }
        val closed = all.filter { it.status == IssueStatus.CLOSED }
        val inTab = when (tab) { 0 -> open; 1 -> closed; else -> all }
        val q = query.trim().removePrefix("#")
        val shown = inTab.filter { q.isEmpty() || it.title.contains(q, true) || it.number?.toString() == q }
        Column(Modifier.fillMaxSize().padding(padding)) {
            Box(Modifier.padding(horizontal = Size.gutter, vertical = Space.s3)) {
                SearchField(query, { query = it }, stringResource(R.string.issues_search))
            }
            Tabs(listOf(stringResource(R.string.issues_open, open.size), stringResource(R.string.issues_closed, closed.size),
                stringResource(R.string.issues_all, all.size)), tab, { tab = it })
            when {
                all.isEmpty() || (tab == 0 && open.isEmpty() && q.isEmpty()) ->
                    EmptyState(R.drawable.ic_triangle_alert, stringResource(R.string.issues_empty_title), stringResource(R.string.issues_empty_body),
                        action = stringResource(R.string.action_raise_issue), onAction = onNew)
                shown.isEmpty() -> EmptyState(R.drawable.ic_search, stringResource(R.string.issues_none_here), "")
                else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 96.dp)) {
                    items(shown, key = { it.id }) { i -> IssueRow(i, i.assigneeId?.let { people[it]?.name }, today) { onOpen(i.id) } }
                }
            }
        }
    }
}

@Composable
private fun IssueRow(i: Issue, assignee: String?, today: LocalDate, onClick: () -> Unit) {
    val c = SiteTheme.colors
    ListRow(onClick = onClick) {
        Column(Modifier.weight(1f).padding(end = Space.s3), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text((i.number?.let { "#$it " } ?: "") + i.title, style = SiteTheme.type.base.copy(fontWeight = FontWeight.W600), color = c.text,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            val meta = listOfNotNull(
                i.dueDate?.let { stringResource(R.string.issue_due, Format.short(it)) },
                assignee,
                i.commentCount.takeIf { it > 0 }?.let { stringResource(R.string.issue_comment_count, it) },
            ).joinToString(" · ")
            if (meta.isNotEmpty()) Text(meta, style = SiteTheme.type.sm, color = c.textMuted, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        StatusBadge(stringResource(statusLabel(i, today)), statusTone(i, today))
    }
}

private fun statusLabel(i: Issue, today: LocalDate) = when {
    i.status == IssueStatus.CLOSED -> R.string.issue_status_closed
    i.isOverdue(today) -> R.string.issue_overdue
    else -> R.string.issue_status_open
}

private fun statusTone(i: Issue, today: LocalDate) = when {
    i.status == IssueStatus.CLOSED -> Tone.SUCCESS
    i.isOverdue(today) -> Tone.DANGER
    else -> Tone.WARNING
}

/** Raise an issue: what, optional details, task, owner and date. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewIssueScreen(projectId: String, onBack: () -> Unit, onCreated: (String) -> Unit) {
    val container = LocalContainer.current
    val scope = rememberCoroutineScope()
    val tasks by remember(projectId) { container.tasks.tasks(projectId) }.collectAsStateWithLifecycle(initialValue = emptyList())
    val team by remember(projectId) { container.team.team(projectId) }.collectAsStateWithLifecycle(initialValue = null)
    var title by rememberSaveable { mutableStateOf("") }
    var details by rememberSaveable { mutableStateOf("") }
    var taskId by rememberSaveable { mutableStateOf<String?>(null) }
    var assignee by rememberSaveable { mutableStateOf<String?>(null) }
    var due by rememberSaveable { mutableStateOf<LocalDate?>(null) }
    var picking by remember { mutableStateOf<String?>(null) }   // "task" | "person"
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<Throwable?>(null) }
    val members = team?.members.orEmpty()

    Scaffold(
        topBar = { AppBarSub(stringResource(R.string.issue_new_title), onBack) },
        bottomBar = {
            Box(Modifier.navigationBarsPadding().imePadding().padding(Size.gutter)) {
                PrimaryButton(stringResource(R.string.action_raise_issue), {
                    busy = true
                    scope.launch {
                        container.issues.raise(NewIssue(projectId, title, details, taskId, due, assignee))
                            .onSuccess { onCreated(it.id) }.onFailure { error = it }
                        busy = false
                    }
                }, large = true, loading = busy, enabled = title.isNotBlank())
            }
        },
        containerColor = SiteTheme.colors.bg,
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(Size.gutter),
            verticalArrangement = Arrangement.spacedBy(Space.s4),
        ) {
            generalError(error)?.let { InfoBanner(it, tone = Tone.DANGER, icon = R.drawable.ic_circle_alert) }
            SiteTextField(title, { title = it.take(200); error = null }, stringResource(R.string.field_issue_title), required = true,
                placeholder = stringResource(R.string.field_issue_title_placeholder), error = fieldError(error, "title"))
            SiteTextField(details, { details = it.take(4000) }, stringResource(R.string.field_issue_details),
                placeholder = stringResource(R.string.field_issue_details_placeholder), singleLine = false, minLines = 3)
            PickerField(stringResource(R.string.field_issue_task), tasks.firstOrNull { it.id == taskId }?.let(::taskLabel),
                stringResource(R.string.field_issue_task_none), { picking = "task" })
            PickerField(stringResource(R.string.field_assignee), members.firstOrNull { it.userId == assignee }?.name,
                stringResource(R.string.field_assignee_none), { picking = "person" })
            DateField(stringResource(R.string.field_due), due, { due = it }, placeholder = stringResource(R.string.field_date_optional),
                onClear = { due = null })
        }
    }

    picking?.let { what ->
        ModalBottomSheet(onDismissRequest = { picking = null }, containerColor = SiteTheme.colors.bg) {
            LazyColumn(Modifier.navigationBarsPadding()) {
                item {
                    Text(stringResource(if (what == "task") R.string.field_issue_task else R.string.field_assignee),
                        Modifier.padding(horizontal = Size.gutter, vertical = Space.s2), style = SiteTheme.type.lg, color = SiteTheme.colors.text)
                }
                item {
                    ListRow(onClick = { if (what == "task") taskId = null else assignee = null; picking = null }) {
                        Text(stringResource(if (what == "task") R.string.field_issue_task_none else R.string.field_assignee_none),
                            style = SiteTheme.type.base, color = SiteTheme.colors.textMuted)
                    }
                }
                if (what == "task") items(tasks, key = { it.id }) { t ->
                    ListRow(onClick = { taskId = t.id; picking = null }) {
                        Text(taskLabel(t), Modifier.weight(1f), style = SiteTheme.type.base, color = SiteTheme.colors.text, maxLines = 2,
                            overflow = TextOverflow.Ellipsis)
                    }
                } else items(members, key = { it.userId }) { m ->
                    ListRow(onClick = { assignee = m.userId; picking = null }) {
                        Avatar(m.name.ifBlank { m.phone })
                        Text(m.name.ifBlank { m.phone }, Modifier.weight(1f).padding(start = Space.s3), style = SiteTheme.type.base,
                            color = SiteTheme.colors.text)
                    }
                }
            }
        }
    }
}

/** One issue: what, who, by when; the conversation; mark fixed or reopen. */
@Composable
fun IssueDetailScreen(issueId: String, onBack: () -> Unit) {
    val container = LocalContainer.current
    val scope = rememberCoroutineScope()
    val issue by remember(issueId) { container.issues.siteIssue(issueId) }.collectAsStateWithLifecycle(initialValue = null)
    val comments by remember(issueId) { container.issues.comments(issueId) }.collectAsStateWithLifecycle(initialValue = emptyList())
    val people by remember { container.people.people() }.collectAsStateWithLifecycle(initialValue = emptyMap())
    val tasks by remember(issue?.projectId) { container.tasks.tasks(issue?.projectId.orEmpty()) }
        .collectAsStateWithLifecycle(initialValue = emptyList())
    var draft by rememberSaveable { mutableStateOf("") }
    var error by remember { mutableStateOf<Throwable?>(null) }
    val today = container.today()
    fun name(id: String?): String = id?.let { people[it]?.name?.ifBlank { null } } ?: "–"

    Scaffold(
        topBar = { AppBarSub(issue?.number?.let { stringResource(R.string.issue_number, it.toString()) } ?: stringResource(R.string.screen_issues), onBack) },
        bottomBar = {
            Row(Modifier.navigationBarsPadding().imePadding().padding(Size.gutter), verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(Space.s2)) {
                SiteTextField(draft, { draft = it.take(4000); error = null }, stringResource(R.string.field_comment), Modifier.weight(1f),
                    singleLine = false, error = fieldError(error, "body"))
                PrimaryButton(stringResource(R.string.action_send), {
                    scope.launch { container.issues.comment(issueId, draft).onSuccess { draft = "" }.onFailure { error = it } }
                }, enabled = draft.isNotBlank())
            }
        },
        containerColor = SiteTheme.colors.bg,
    ) { padding ->
        val i = issue
        if (i == null) { Box(Modifier.padding(padding)) { LoadingBlock() }; return@Scaffold }
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(bottom = Space.s4)) {
            item {
                Column(Modifier.padding(Size.gutter), verticalArrangement = Arrangement.spacedBy(Space.s2)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(i.title, Modifier.weight(1f), style = SiteTheme.type.lg, color = SiteTheme.colors.text)
                        StatusBadge(stringResource(statusLabel(i, today)), statusTone(i, today))
                    }
                    Text(stringResource(R.string.issue_raised_by, name(i.raisedBy), Format.day(i.createdAt.atZone(ZoneId.systemDefault()).toLocalDate())),
                        style = SiteTheme.type.sm, color = SiteTheme.colors.textMuted)
                    i.description?.let { Text(it, style = SiteTheme.type.base, color = SiteTheme.colors.text) }
                    val meta = listOfNotNull(
                        i.taskId?.let { id -> tasks.firstOrNull { it.id == id }?.let { stringResource(R.string.issue_task_link, taskLabel(it)) } },
                        i.assigneeId?.let { "${stringResource(R.string.field_assignee)}: ${name(it)}" },
                        i.dueDate?.let { stringResource(R.string.issue_due, Format.day(it)) },
                    )
                    meta.forEach { Text(it, style = SiteTheme.type.sm, color = SiteTheme.colors.text) }
                    generalError(error)?.let { InfoBanner(it, tone = Tone.DANGER, icon = R.drawable.ic_circle_alert) }
                    if (i.status == IssueStatus.OPEN) {
                        SecondaryButton(stringResource(R.string.action_close_issue), {
                            scope.launch { container.issues.setStatus(i.id, IssueStatus.CLOSED).onFailure { error = it } }
                        }, Modifier.fillMaxWidth(), icon = R.drawable.ic_check)
                    } else {
                        SecondaryButton(stringResource(R.string.action_reopen_issue), {
                            scope.launch { container.issues.setStatus(i.id, IssueStatus.OPEN).onFailure { error = it } }
                        }, Modifier.fillMaxWidth(), icon = R.drawable.ic_undo_2)
                    }
                }
            }
            item { SectionLabel(stringResource(R.string.issue_comments), Modifier.padding(horizontal = Size.gutter)) }
            if (comments.isEmpty()) item {
                Text(stringResource(R.string.issue_comments_none), Modifier.padding(horizontal = Size.gutter, vertical = Space.s2),
                    style = SiteTheme.type.sm, color = SiteTheme.colors.textMuted)
            }
            items(comments, key = { it.id }) { cm ->
                Row(Modifier.fillMaxWidth().padding(horizontal = Size.gutter, vertical = Space.s2), horizontalArrangement = Arrangement.spacedBy(Space.s3)) {
                    Avatar(name(cm.authorId), size = Size.avatarSm)
                    Column(Modifier.weight(1f)) {
                        Text("${name(cm.authorId)} · ${Format.short(cm.createdAt.atZone(ZoneId.systemDefault()).toLocalDate())}",
                            style = SiteTheme.type.sm.copy(fontWeight = FontWeight.W600), color = SiteTheme.colors.text)
                        Text(cm.body, style = SiteTheme.type.base, color = SiteTheme.colors.text)
                    }
                }
            }
        }
    }
}
