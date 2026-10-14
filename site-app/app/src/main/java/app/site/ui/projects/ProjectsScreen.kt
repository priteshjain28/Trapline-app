package app.site.ui.projects

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.site.LocalContainer
import app.site.R
import app.site.core.logic.Format
import app.site.core.model.Plan
import app.site.core.model.Project
import app.site.design.Radius
import app.site.design.SiteTheme
import app.site.design.Size
import app.site.design.Space
import app.site.design.components.AppBarOrg
import app.site.design.components.Divider
import app.site.design.components.EmptyState
import app.site.design.components.LoadingBlock
import app.site.design.components.ProgressRing
import app.site.design.components.SearchField
import app.site.design.components.SiteCard
import app.site.design.components.SiteFab
import app.site.design.components.SiteIcon
import app.site.design.components.StatusBadge
import app.site.ui.Planned
import app.site.ui.label
import app.site.ui.tone
import java.time.LocalDate

/** S04: the organisation's projects. */
@Composable
fun ProjectsScreen(onOpen: (String) -> Unit, onNew: () -> Unit, onProfile: () -> Unit, onPlanned: (Planned) -> Unit) {
    val container = LocalContainer.current
    val session by container.session.session.collectAsStateWithLifecycle()
    val projectsFlow = remember { container.projects.projects() }
    val projects by projectsFlow.collectAsStateWithLifecycle(initialValue = null)
    var query by rememberSaveable { mutableStateOf("") }
    val today = container.today()

    Scaffold(
        topBar = {
          Column {
            AppBarOrg(
                orgName = session?.org?.name.orEmpty(),
                plan = stringResource(if (session?.org?.plan == Plan.FREE) R.string.plan_free else R.string.plan_paid),
                userName = session?.user?.name.orEmpty(),
                onProfile = onProfile,
                onMessages = { onPlanned(Planned.MESSAGES) },
            )
            app.site.ui.SyncBanner()
          }
        },
        floatingActionButton = { SiteFab(stringResource(R.string.action_new_project), R.drawable.ic_plus, onNew) },
        containerColor = SiteTheme.colors.bg,
    ) { padding ->
        val list = projects
        val shown = list?.filter { query.isBlank() || it.name.contains(query.trim(), ignoreCase = true) }
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(start = Size.gutter, end = Size.gutter, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(Space.s3),
        ) {
            item {
                Text(stringResource(R.string.projects_title), Modifier.padding(top = Space.s4).semantics { heading() },
                    style = SiteTheme.type.lg, color = SiteTheme.colors.text)
            }
            when {
                list == null -> item { LoadingBlock() }
                list.isEmpty() -> item {
                    EmptyState(R.drawable.ic_building_2, stringResource(R.string.projects_empty_title),
                        stringResource(R.string.projects_empty_body), action = stringResource(R.string.action_new_project), onAction = onNew)
                }
                else -> {
                    item { SearchField(query, { query = it }, stringResource(R.string.projects_search)) }
                    if (shown.isNullOrEmpty()) item {
                        EmptyState(R.drawable.ic_search, stringResource(R.string.search_none_title),
                            stringResource(R.string.search_none_body, query.trim()))
                    }
                    items(shown.orEmpty(), key = { it.id }) { p -> ProjectCard(p, today) { onOpen(p.id) } }
                }
            }
        }
    }
}

@Composable
fun ProjectCard(p: Project, today: LocalDate, onClick: () -> Unit) {
    val c = SiteTheme.colors
    val status = stringResource(p.status.label())
    val dates = if (p.startDate != null && p.endDate != null) Format.range(p.startDate!!, p.endDate!!, today) else null
    val label = stringResource(R.string.a11y_project_card, p.name, status, p.progressPct, dates ?: stringResource(R.string.project_no_dates))
    SiteCard(onClick = onClick, label = label) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s3)) {
                Box(Modifier.size(56.dp).clip(Radius.md).background(c.accentTint), contentAlignment = Alignment.Center) {
                    SiteIcon(R.drawable.ic_building_2, null, tint = c.accent)
                }
                Text(p.name, Modifier.weight(1f), style = SiteTheme.type.base.copy(fontWeight = FontWeight.W600), color = c.text,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                StatusBadge(status, p.status.tone())
            }
            Divider(Modifier.padding(vertical = Space.s3))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s2)) {
                ProgressRing(p.progressPct)
                Text("${p.progressPct}%", style = SiteTheme.type.sm.copy(fontWeight = FontWeight.W600), color = c.text)
                Box(Modifier.weight(1f))
                SiteIcon(R.drawable.ic_calendar, null, tint = c.textMuted, size = Size.iconSm)
                Text(dates ?: stringResource(R.string.project_no_dates), style = SiteTheme.type.sm, color = c.textMuted, maxLines = 1)
            }
        }
    }
}
