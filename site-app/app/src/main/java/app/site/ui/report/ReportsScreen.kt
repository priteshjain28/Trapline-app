package app.site.ui.report

import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.site.LocalContainer
import app.site.R
import app.site.core.logic.Format
import app.site.core.model.Report
import app.site.core.model.ReportStatus
import app.site.core.model.ReportType
import app.site.design.SiteTheme
import app.site.design.Size
import app.site.design.Space
import app.site.design.components.AppBarSub
import app.site.design.components.EmptyState
import app.site.design.components.InfoBanner
import app.site.design.components.ListRow
import app.site.design.components.SiteIconButton
import app.site.design.components.StatusBadge
import app.site.design.components.TextAction
import app.site.design.components.Tone
import kotlinx.coroutines.launch
import java.io.File
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** S07: reports made for this site. Open, share (WhatsApp, email...) or retry. Kept 90 days. */
@Composable
fun ReportsScreen(projectId: String, onBack: () -> Unit) {
    val container = LocalContainer.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snack = remember { SnackbarHostState() }
    val noApp = stringResource(R.string.err_no_pdf_app)
    val flow = remember(projectId) { container.reports.reports(projectId) }
    val reports by flow.collectAsStateWithLifecycle(initialValue = emptyList())
    val projectFlow = remember(projectId) { container.projects.project(projectId) }
    val project by projectFlow.collectAsStateWithLifecycle(initialValue = null)
    val shareTitle = stringResource(R.string.action_share)

    /** The PDF on this phone, downloading it first if another phone made it. */
    fun withFile(r: Report, then: (File) -> Unit) = scope.launch {
        container.reports.fetchFile(r.id)
            .onSuccess { then(File(it)) }
            .onFailure { snack.showSnackbar(context.getString(R.string.err_report_download)) }
    }

    fun launch(intent: Intent) {
        try { context.startActivity(intent) } catch (e: ActivityNotFoundException) { scope.launch { snack.showSnackbar(noApp) } }
    }

    Scaffold(
        topBar = { AppBarSub(stringResource(R.string.reports_title), onBack, overline = project?.name) },
        snackbarHost = { SnackbarHost(snack) },
        containerColor = SiteTheme.colors.bg,
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(bottom = Space.s7)) {
            item { InfoBanner(stringResource(R.string.reports_kept), Modifier.padding(Size.gutter)) }
            if (reports.isEmpty()) item {
                EmptyState(R.drawable.ic_file_text, stringResource(R.string.reports_empty_title), stringResource(R.string.reports_empty_body),
                    action = stringResource(R.string.action_create_report), onAction = onBack)
            }
            items(reports, key = { it.id }) { r ->
                ReportRow(
                    r,
                    onOpen = { withFile(r) { f -> launch(container.photos.viewIntent(f, "application/pdf")) } },
                    onShare = { withFile(r) { f ->
                        val subject = "${project?.name.orEmpty()} – ${Format.range(r.from, r.to, container.today())}"
                        launch(Intent.createChooser(container.photos.shareIntent(f, "application/pdf", subject), shareTitle))
                    } },
                    onRetry = { container.reportService.retry(r) },
                )
            }
        }
    }
}

private val createdFmt = DateTimeFormatter.ofPattern("d MMM, h:mm a", Locale.ENGLISH)

@Composable
private fun ReportRow(r: Report, onOpen: () -> Unit, onShare: () -> Unit, onRetry: () -> Unit) {
    val c = SiteTheme.colors
    val container = LocalContainer.current
    val ready = r.status == ReportStatus.READY
    ListRow(onClick = if (ready) onOpen else null) {
        Column(Modifier.weight(1f)) {
            val kind = stringResource(if (r.type == ReportType.DETAILED) R.string.report_type_detailed else R.string.report_type_summary)
            Text("$kind · ${Format.range(r.from, r.to, container.today())}", style = SiteTheme.type.base.copy(fontWeight = FontWeight.W600), color = c.text)
            Text(stringResource(R.string.report_created, r.createdAt.atZone(ZoneId.systemDefault()).format(createdFmt)),
                style = SiteTheme.type.sm, color = c.textMuted)
        }
        when (r.status) {
            ReportStatus.QUEUED, ReportStatus.RENDERING -> {
                CircularProgressIndicator(Modifier.size(20.dp), color = c.accent, strokeWidth = 2.dp)
                Text(stringResource(R.string.report_making), Modifier.padding(start = Space.s2), style = SiteTheme.type.sm, color = c.textMuted)
            }
            ReportStatus.FAILED -> {
                StatusBadge(stringResource(R.string.report_failed), Tone.DANGER)
                TextAction(stringResource(R.string.action_retry), onRetry)
            }
            ReportStatus.READY -> {
                SiteIconButton(R.drawable.ic_external_link, stringResource(R.string.action_open), onOpen, tint = c.accent)
                SiteIconButton(R.drawable.ic_share_2, stringResource(R.string.action_share), onShare, tint = c.accent)
            }
        }
    }
}
