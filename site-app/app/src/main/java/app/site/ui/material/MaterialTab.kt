package app.site.ui.material

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import app.site.core.model.Grn
import app.site.core.model.Material
import app.site.core.model.MaterialIssue
import app.site.core.model.MaterialLine
import app.site.core.model.Project
import app.site.core.model.StockItem
import app.site.design.SiteTheme
import app.site.design.Size
import app.site.design.Space
import app.site.design.components.EmptyState
import app.site.design.components.ListRow
import app.site.design.components.LoadingBlock
import app.site.design.components.SearchField
import app.site.design.components.SegmentedControl
import app.site.design.components.SiteFab
import app.site.design.components.SiteIcon
import app.site.design.components.Tabs
import app.site.design.components.TextAction

/** Where the Material tab can send you. */
sealed interface MaterialNav {
    data object AddMaterial : MaterialNav
    data object Receive : MaterialNav
    data object Issue : MaterialNav
    data class Detail(val materialId: String) : MaterialNav
}

private object MatTab {
    const val BUYING = 0
    const val STOCK = 1
    // Buying: requests, deliveries (against orders), received at site
    const val RECEIVED = 2
    // Stock: on site, sent, asked for, issued, returned
    const val ON_SITE = 0
    const val ISSUED = 3
}

/** S12-S20: material, as Buying and Stock. Built: received at site (S14), on site (S15), issued (S19). */
@Composable
fun MaterialTab(project: Project, onNav: (MaterialNav) -> Unit, startOnReceived: Boolean = false) {
    // opens on stock: what's on site is what people check most
    var side by rememberSaveable { mutableIntStateOf(if (startOnReceived) MatTab.BUYING else MatTab.STOCK) }
    var tab by rememberSaveable { mutableIntStateOf(if (startOnReceived) MatTab.RECEIVED else MatTab.ON_SITE) }
    val buying = listOf(R.string.mat_requests, R.string.mat_deliveries, R.string.mat_received)
    val stock = listOf(R.string.mat_stock, R.string.mat_transfers_out, R.string.mat_transfer_requests, R.string.mat_issued, R.string.mat_returns)
    val labels = (if (side == MatTab.BUYING) buying else stock).map { stringResource(it) }
    val current = tab.coerceAtMost(labels.lastIndex)
    Column(Modifier.fillMaxSize()) {
        SegmentedControl(listOf(stringResource(R.string.mat_buying), stringResource(R.string.mat_stock_side)), side,
            { side = it; tab = if (it == MatTab.BUYING) MatTab.RECEIVED else MatTab.ON_SITE }, Modifier.padding(Size.gutter))
        Tabs(labels, current, { tab = it })
        Box(Modifier.weight(1f)) {
            when {
                side == MatTab.BUYING && current == MatTab.RECEIVED -> ReceivedList(project, onNav)
                side == MatTab.STOCK && current == MatTab.ON_SITE -> StockList(project, onNav)
                side == MatTab.STOCK && current == MatTab.ISSUED -> IssuedList(project, onNav)
                else -> EmptyState(R.drawable.ic_package, stringResource(R.string.planned_title), stringResource(R.string.planned_material_body))
            }
        }
    }
}

// ---------------------------------------------------------------- S15 on site

@Composable
private fun StockList(project: Project, onNav: (MaterialNav) -> Unit) {
    val container = LocalContainer.current
    val flow = remember(project.id) { container.materials.stock(project.id) }
    val items by flow.collectAsStateWithLifecycle(initialValue = null)
    var query by rememberSaveable { mutableStateOf("") }
    var updating by remember { mutableStateOf<StockItem?>(null) }

    Box(Modifier.fillMaxSize()) {
        val all = items
        when {
            all == null -> LoadingBlock()
            all.isEmpty() -> EmptyState(R.drawable.ic_warehouse, stringResource(R.string.mat_none_title), stringResource(R.string.mat_none_body),
                action = stringResource(R.string.action_add_material), onAction = { onNav(MaterialNav.AddMaterial) })
            else -> {
                val q = query.trim()
                val shown = all.filter { q.isEmpty() || it.material.name.contains(q, true) || it.material.spec.contains(q, true) }
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 96.dp)) {
                    item {
                        Box(Modifier.padding(horizontal = Size.gutter, vertical = Space.s3)) {
                            SearchField(query, { query = it }, stringResource(R.string.mat_search))
                        }
                    }
                    if (shown.isEmpty()) item { EmptyState(R.drawable.ic_search, stringResource(R.string.mat_none_match), "") }
                    items(shown, key = { it.material.id }) { s ->
                        StockRow(s, onOpen = { onNav(MaterialNav.Detail(s.material.id)) }, onUpdate = { updating = s })
                    }
                }
            }
        }
        if (!items.isNullOrEmpty()) {
            SiteFab(stringResource(R.string.action_add_material), R.drawable.ic_plus, { onNav(MaterialNav.AddMaterial) },
                Modifier.align(Alignment.BottomEnd).padding(Size.gutter))
        }
    }
    updating?.let { s -> UpdateStockSheet(project.id, s, onDismiss = { updating = null }) }
}

@Composable
private fun StockRow(s: StockItem, onOpen: () -> Unit, onUpdate: () -> Unit) {
    val c = SiteTheme.colors
    ListRow(onClick = onOpen) {
        Column(Modifier.weight(1f).padding(end = Space.s2)) {
            Text(s.material.name, style = SiteTheme.type.base.copy(fontWeight = FontWeight.W600), color = c.text,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (s.material.spec.isNotBlank()) {
                Text(s.material.spec, style = SiteTheme.type.sm, color = c.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text(stringResource(R.string.mat_in_stock, Format.qty(s.inStock), s.material.uom),
                style = SiteTheme.type.sm.copy(fontWeight = FontWeight.W600),
                color = if (s.inStock > 0) c.text else c.danger)
        }
        TextAction(stringResource(R.string.action_update_stock), onUpdate, icon = R.drawable.ic_plus)
    }
}

// ---------------------------------------------------------------- S14 received at site

@Composable
private fun ReceivedList(project: Project, onNav: (MaterialNav) -> Unit) {
    val container = LocalContainer.current
    val flow = remember(project.id) { container.materials.grns(project.id) }
    val grns by flow.collectAsStateWithLifecycle(initialValue = null)
    val catalogue by remember { container.materials.catalogue() }.collectAsStateWithLifecycle(initialValue = emptyList())
    val byId = catalogue.associateBy { it.id }
    Box(Modifier.fillMaxSize()) {
        val all = grns
        when {
            all == null -> LoadingBlock()
            all.isEmpty() -> EmptyState(R.drawable.ic_truck, stringResource(R.string.mat_received_none_title),
                stringResource(R.string.mat_received_none_body), action = stringResource(R.string.action_record_delivery),
                onAction = { onNav(MaterialNav.Receive) })
            else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 96.dp)) {
                items(all, key = { it.id }) { g -> DocRow(R.drawable.ic_truck, grnTitle(g), Format.short(g.receivedOn),
                    g.challanNo?.let { stringResource(R.string.mat_challan, it) }, g.lines, byId) }
            }
        }
        if (!grns.isNullOrEmpty()) {
            SiteFab(stringResource(R.string.action_record_delivery), R.drawable.ic_plus, { onNav(MaterialNav.Receive) },
                Modifier.align(Alignment.BottomEnd).padding(Size.gutter))
        }
    }
}

@Composable
private fun grnTitle(g: Grn) = g.number?.let { stringResource(R.string.mat_grn_title, it.toString()) } ?: stringResource(R.string.mat_pending_number)

// ---------------------------------------------------------------- S19 issued

@Composable
private fun IssuedList(project: Project, onNav: (MaterialNav) -> Unit) {
    val container = LocalContainer.current
    val flow = remember(project.id) { container.materials.issues(project.id) }
    val issues by flow.collectAsStateWithLifecycle(initialValue = null)
    val catalogue by remember { container.materials.catalogue() }.collectAsStateWithLifecycle(initialValue = emptyList())
    val tasks by remember(project.id) { container.tasks.tasks(project.id) }.collectAsStateWithLifecycle(initialValue = emptyList())
    val byId = catalogue.associateBy { it.id }
    val taskNames = tasks.associate { it.id to ((it.number?.let { n -> "#$n " } ?: "") + it.name) }
    Box(Modifier.fillMaxSize()) {
        val all = issues
        when {
            all == null -> LoadingBlock()
            all.isEmpty() -> EmptyState(R.drawable.ic_package, stringResource(R.string.mat_issued_none_title),
                stringResource(R.string.mat_issued_none_body), action = stringResource(R.string.action_issue_material),
                onAction = { onNav(MaterialNav.Issue) })
            else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 96.dp)) {
                items(all, key = { it.id }) { i -> DocRow(R.drawable.ic_package, issueTitle(i), Format.short(i.issuedOn),
                    taskNames[i.taskId]?.let { stringResource(R.string.mat_for_task, it) }, i.lines, byId) }
            }
        }
        if (!issues.isNullOrEmpty()) {
            SiteFab(stringResource(R.string.action_issue_material), R.drawable.ic_plus, { onNav(MaterialNav.Issue) },
                Modifier.align(Alignment.BottomEnd).padding(Size.gutter))
        }
    }
}

@Composable
private fun issueTitle(i: MaterialIssue) =
    i.number?.let { stringResource(R.string.mat_issue_title, it.toString()) } ?: stringResource(R.string.mat_pending_number)

/** A delivery or an issue: number and date, who/what for, then the materials on it. */
@Composable
private fun DocRow(icon: Int, title: String, date: String, subtitle: String?, lines: List<MaterialLine>, byId: Map<String, Material>) {
    val c = SiteTheme.colors
    ListRow {
        SiteIcon(icon, null, tint = c.textMuted)
        Column(Modifier.weight(1f).padding(start = Space.s3), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("$title · $date", style = SiteTheme.type.base.copy(fontWeight = FontWeight.W600), color = c.text)
            subtitle?.let { Text(it, style = SiteTheme.type.sm, color = c.textMuted, maxLines = 2, overflow = TextOverflow.Ellipsis) }
            val shown = lines.take(3).joinToString(", ") { l ->
                val m = byId[l.materialId]
                "${m?.name ?: "…"} ${Format.qty(l.qty)} ${m?.uom.orEmpty()}".trim()
            }
            val more = if (lines.size > 3) " " + stringResource(R.string.mat_lines_more, lines.size - 3) else ""
            Text(shown + more, style = SiteTheme.type.sm, color = c.text, maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
    }
}
