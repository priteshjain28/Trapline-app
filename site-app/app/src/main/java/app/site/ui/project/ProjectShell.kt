package app.site.ui.project

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.site.LocalContainer
import app.site.R
import app.site.core.model.Project
import app.site.design.SiteTheme
import app.site.design.Size
import app.site.design.Space
import app.site.design.components.AppBarProject
import app.site.design.components.BottomNav
import app.site.design.components.EmptyState
import app.site.design.components.ListRow
import app.site.design.components.LoadingBlock
import app.site.design.components.NavItem
import app.site.design.components.SiteIcon
import app.site.design.components.StatusBadge
import app.site.ui.Planned
import app.site.ui.ProjectTab
import app.site.ui.attendance.AttendanceTab
import app.site.ui.label
import app.site.ui.more.MaterialTab
import app.site.ui.more.MoreTab
import app.site.ui.report.ReportTab
import app.site.ui.tasks.TasksTab
import app.site.ui.tone

/** S05: everything inside one project, behind five tabs. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectShell(
    projectId: String,
    initialTab: Int,
    onClose: () -> Unit,
    onSwitch: (String) -> Unit,
    onNewTask: () -> Unit,
    onTask: (String) -> Unit,
    onNewWorker: () -> Unit,
    onReports: () -> Unit,
    onFeed: () -> Unit,
    onAttendanceSettings: () -> Unit,
    onPlanned: (Planned) -> Unit,
) {
    val container = LocalContainer.current
    val session by container.session.session.collectAsStateWithLifecycle()
    val projectFlow = remember(projectId) { container.projects.project(projectId) }
    val project by projectFlow.collectAsStateWithLifecycle(initialValue = null)
    var tab by rememberSaveable { mutableIntStateOf(initialTab) }
    var switcher by remember { mutableStateOf(false) }

    val items = listOf(
        NavItem(stringResource(R.string.tab_report), R.drawable.ic_file_text),
        NavItem(stringResource(R.string.tab_tasks), R.drawable.ic_clipboard_check),
        NavItem(stringResource(R.string.tab_material), R.drawable.ic_package),
        NavItem(stringResource(R.string.tab_attendance), R.drawable.ic_user_check),
        NavItem(stringResource(R.string.tab_more), R.drawable.ic_menu),
    )

    Scaffold(
        topBar = {
            AppBarProject(
                orgName = session?.org?.name.orEmpty(),
                projectName = project?.name.orEmpty(),
                onClose = onClose,
                onSwitch = { switcher = true },
                onMessages = { onPlanned(Planned.MESSAGES) },
                onHelp = { onPlanned(Planned.HELP) },
            )
        },
        bottomBar = { BottomNav(items, tab) { tab = it } },
        containerColor = SiteTheme.colors.bg,
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            val p: Project? = project
            if (p == null) LoadingBlock()
            else when (tab) {
                ProjectTab.REPORT -> ReportTab(p, onReports)
                ProjectTab.TASKS -> TasksTab(p, onNewTask, onTask)
                ProjectTab.MATERIAL -> MaterialTab()
                ProjectTab.ATTENDANCE -> AttendanceTab(p, onNewWorker, onAttendanceSettings)
                else -> MoreTab(onReports, onFeed, onAttendanceSettings, onPlanned)
            }
        }
    }

    if (switcher) {
        val allFlow = remember { container.projects.projects() }
        val all by allFlow.collectAsStateWithLifecycle(initialValue = emptyList())
        ModalBottomSheet(onDismissRequest = { switcher = false }, containerColor = SiteTheme.colors.bg) {
            Column(Modifier.navigationBarsPadding()) {
                Text(stringResource(R.string.switch_project_title), Modifier.padding(horizontal = Size.gutter, vertical = Space.s2),
                    style = SiteTheme.type.lg, color = SiteTheme.colors.text)
                if (all.isEmpty()) EmptyState(R.drawable.ic_building_2, stringResource(R.string.projects_empty_title), "")
                LazyColumn {
                    items(all, key = { it.id }) { other ->
                        ListRow(onClick = { switcher = false; if (other.id != projectId) onSwitch(other.id) }) {
                            SiteIcon(if (other.id == projectId) R.drawable.ic_check else R.drawable.ic_building_2, null,
                                tint = if (other.id == projectId) SiteTheme.colors.accent else SiteTheme.colors.textMuted)
                            Text(other.name, Modifier.weight(1f).padding(horizontal = Space.s3),
                                style = SiteTheme.type.base.copy(fontWeight = if (other.id == projectId) FontWeight.W600 else FontWeight.W400),
                                color = SiteTheme.colors.text, maxLines = 1)
                            StatusBadge(stringResource(other.status.label()), other.status.tone())
                        }
                    }
                }
            }
        }
    }
}
